package top.foxball.video.service

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.foxball.video.ai.AiApiType
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.ProductionTaskRepository
import top.foxball.video.repository.ShotRepository
import top.foxball.video.entity.jdbc.apiType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@Service
class StoryboardTaskService(
    private val taskRepository: ProductionTaskRepository,
    private val shotRepository: ShotRepository,
    private val episodeRepository: EpisodeRepository,
    private val providerRepository: AiModelProviderConfigRepository,
    private val taskCenter: TaskCenterService,
    private val media: AiMediaGenerationService,
    private val files: FileService,
    private val templateCatalog: TemplateCatalogService,
    private val templateRenderer: TemplateRenderer,
    private val templateAgentRunner: TemplateAgentRunner,
) {
    data class Submission(val task: ProductionTask, val existing: Boolean)
    class QueueFullException : IllegalStateException("分镜图任务队列已满，请稍后重试")
    private val executor: ExecutorService = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16), Executors.defaultThreadFactory(), ThreadPoolExecutor.AbortPolicy())

    @Transactional
    fun submit(projectId: Long, userId: Long?, episodeId: Long, batchNo: Int, shotIds: List<Long>, providerConfigId: Long, model: String, size: String, style: String?, key: String?, promptProviderConfigId: Long? = null, promptModel: String? = null): Submission? {
        if (!taskCenter.canWriteProject(projectId, userId) || episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        require(batchNo > 0 && shotIds.isNotEmpty()) { "批次和镜头不能为空" }
        val shots = shotRepository.findAllByProjectIdAndEpisodeIdOrderBySequenceNoAsc(projectId, episodeId)
        val expected = shots.sortedBy { it.sequenceNo }.chunked(9).getOrNull(batchNo - 1)?.mapNotNull { it.id }.orEmpty()
        require(expected == shotIds) { "镜头集合已变化，请刷新后重新提交" }
        val config = providerRepository.findById(providerConfigId).orElse(null) ?: throw IllegalArgumentException("AI 提供商配置不存在")
        require(config.enabled && config.apiType == AiApiType.IMAGE) { "该提供商配置不是启用的图片模型" }
        require(model.isNotBlank() && size in setOf("1024x1024", "2048x2048", "4096x4096")) { "模型或尺寸不正确" }
        // 可选：提供对话模型时先由分镜模板调用技能产出版式提示词，再交给图片模型成图。
        promptProviderConfigId?.let { id ->
            val promptConfig = providerRepository.findById(id).orElse(null) ?: throw IllegalArgumentException("AI 提供商配置不存在")
            require(promptConfig.enabled && promptConfig.apiType == AiApiType.CHAT) { "提示词提供商配置不是启用的对话模型" }
            require(!promptModel.isNullOrBlank()) { "使用模板生成分镜提示词时必须提供对话模型" }
        }
        val normalized = key?.trim()?.takeIf { it.isNotBlank() }
        normalized?.let { existing ->
            taskRepository.findFirstByProjectIdAndEpisodeIdAndTaskTypeAndIdempotencyKey(projectId, episodeId, ProductionTaskType.IMAGE_GENERATION, existing)?.let { task ->
                val existingShotIds = (task.inputPayload["shot_ids"] as? Collection<*>)?.mapNotNull { (it as? Number)?.toLong() }.orEmpty()
                require((task.inputPayload["batch_no"] as? Number)?.toInt() == batchNo && existingShotIds == shotIds) { "幂等键已用于不同请求" }
                return Submission(task, true)
            }
        }
        if ((executor as ThreadPoolExecutor).queue.remainingCapacity() <= 0) throw QueueFullException()
        // 提交时固定模板版本与内容哈希：出图提示词不会因为期间发布新版本而静默改变。
        val template = templateCatalog.resolve("shot_storyboard_grid", projectId, userId, "zh-CN")
        val payload = buildMap<String, Any> {
            put("prompt_key", template.template.key)
            put("prompt_version_id", requireNotNull(template.version.id))
            put("template_hash", template.version.contentHash)
            put("batch_no", batchNo)
            put("shot_ids", shotIds)
            put("provider_config_id", providerConfigId)
            put("model", model.trim())
            put("size", size)
            put("style_supplement", style.orEmpty())
            promptProviderConfigId?.let { put("prompt_provider_config_id", it) }
            promptModel?.trim()?.takeIf { it.isNotBlank() }?.let { put("prompt_model", it) }
        }
        val task = taskRepository.save(ProductionTask(projectId = projectId, createdBy = userId, episodeId = episodeId, taskType = ProductionTaskType.IMAGE_GENERATION, status = ProductionTaskStatus.QUEUED, title = "生成九宫格分镜图", providerConfigId = providerConfigId, modelName = model.trim(), correlationId = UUID.randomUUID().toString(), idempotencyKey = normalized, inputPayload = payload))
        taskCenter.recordEnqueued(listOf(task))
        val id = requireNotNull(task.id)
        val dispatch = { try { executor.execute { execute(id) } } catch (_: RejectedExecutionException) { taskCenter.markFailed(id, "TASK_QUEUE_FULL", "分镜图任务队列已满") } }
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization { override fun afterCommit() { dispatch() } }) else dispatch()
        return Submission(task, false)
    }

    @Transactional(readOnly = true)
    fun detail(projectId: Long, userId: Long?, episodeId: Long, taskId: Long): ProductionTask? = taskRepository.findByIdAndProjectId(taskId, projectId)?.takeIf { taskCenter.canReadProject(projectId, userId) && it.episodeId == episodeId && it.inputPayload["prompt_key"] == "shot_storyboard_grid" }

    @Transactional(readOnly = true)
    fun batches(projectId: Long, userId: Long?, episodeId: Long): List<ProductionTask> = if (!taskCenter.canReadProject(projectId, userId)) emptyList() else taskRepository.findAllByProjectIdAndEpisodeIdOrderByCreatedAtDesc(projectId, episodeId).filter { it.inputPayload["prompt_key"] == "shot_storyboard_grid" }

    @Transactional
    fun saveCells(projectId: Long, userId: Long?, episodeId: Long, batchNo: Int, gridFileId: String, cells: List<Map<String, Any?>>): Boolean {
        if (!taskCenter.canWriteProject(projectId, userId)) return false
        val task = batches(projectId, userId, episodeId).firstOrNull { (it.inputPayload["batch_no"] as? Number)?.toInt() == batchNo && it.status == ProductionTaskStatus.SUCCEEDED } ?: return false
        require(cells.mapNotNull { (it["cell"] as? Number)?.toInt() }.distinct().size == cells.size && cells.all { ((it["cell"] as? Number)?.toInt() ?: -1) in 0..8 }) { "格位映射不正确" }
        task.inputPayload = task.inputPayload + mapOf("grid_file_id" to gridFileId, "cells" to cells)
        taskRepository.save(task)
        return true
    }

    @Transactional
    fun importCells(projectId: Long, userId: Long?, episodeId: Long, batchNo: Int, cells: List<Map<String, Any?>>?): List<Map<String, String>>? {
        if (!taskCenter.canWriteProject(projectId, userId)) return null
        val task = batches(projectId, userId, episodeId).firstOrNull { (it.inputPayload["batch_no"] as? Number)?.toInt() == batchNo && it.status == ProductionTaskStatus.SUCCEEDED } ?: return null
        val gridId = (task.inputPayload["grid_file_id"] ?: return null).toString()
        val mapping = cells ?: (task.inputPayload["cells"] as? List<Map<String, Any?>> ?: emptyList())
        val image = files.readForInternalUse(UUID.fromString(gridId))
        val source = ImageIO.read(image.bytes.inputStream()) ?: throw IllegalArgumentException("九宫格文件不是有效图片")
        val result = mutableListOf<Map<String, String>>()
        mapping.forEach { item ->
            val cell = (item["cell"] as Number).toInt(); val shotId = (item["shot_id"] as Number).toLong()
            val shot = shotRepository.findById(shotId).orElse(null) ?: throw IllegalArgumentException("镜头不存在")
            require(shot.projectId == projectId && shot.episodeId == episodeId) { "镜头不属于当前剧集" }
            val x = (cell % 3) * source.width / 3; val y = (cell / 3) * source.height / 3
            val w = source.width / 3; val h = source.height / 3
            val tile = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val graphics = tile.createGraphics()
            try { graphics.drawImage(source, 0, 0, w, h, x, y, x + w, y + h, null) } finally { graphics.dispose() }
            val out = ByteArrayOutputStream(); ImageIO.write(tile, "png", out)
            val stored = files.storeGenerated(requireNotNull(userId), "storyboard-${episodeId}-${batchNo}-${cell}.png", "image/png", out.toByteArray())
            shot.storyboardFileId = stored.file.id.toString()
            shotRepository.save(shot)
            result += mapOf("shot_id" to shotId.toString(), "file_id" to stored.file.id.toString())
        }
        task.inputPayload = task.inputPayload + mapOf("imported" to result)
        taskRepository.save(task)
        return result
    }

    private fun execute(taskId: Long) {
        val task = taskCenter.markRunning(taskId) ?: return
        if (task.status != ProductionTaskStatus.RUNNING) return
        try {
            val shots = (task.inputPayload["shot_ids"] as Collection<*>).map { shotRepository.findById((it as Number).toLong()).orElseThrow() }
            val shotLines = shots.mapIndexed { i, shot -> "${i + 1}. ${(shot.prompt ?: shot.visualDescription ?: shot.action ?: "无画面描述").replace("\\s+".toRegex(), " ").take(500)}" }.joinToString("\\n")
            // 分镜提示词由 shot_storyboard_grid 模板提供：模板负责版式与连续性约束，技能负责画面组织。
            val pinnedVersionId = (task.inputPayload["prompt_version_id"] as? Number)?.toLong()
            val template = pinnedVersionId?.let { templateCatalog.resolveExecutableVersion(it) }
                ?: templateCatalog.resolve("shot_storyboard_grid", task.projectId, requireNotNull(task.createdBy), "zh-CN")
            require(task.inputPayload["template_hash"]?.toString().isNullOrBlank() || task.inputPayload["template_hash"] == template.version.contentHash) { "分镜模板版本已变化，请重新提交任务" }
            val rendered = templateRenderer.render(
                template,
                mapOf("shots" to shotLines, "style_supplement" to task.inputPayload["style_supplement"].toString().trim()),
            )
            val promptProviderConfigId = (task.inputPayload["prompt_provider_config_id"] as? Number)?.toLong()
            val promptModel = task.inputPayload["prompt_model"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val useModelPrompt = promptProviderConfigId != null && promptModel != null &&
                templateAgentRunner.imagePromptSource(template.version) == TemplateImagePromptSource.MODEL_OUTPUT
            val imagePrompt = if (useModelPrompt) {
                templateAgentRunner.chat(
                    TemplateAgentRunner.ChatRequest(
                        template = template,
                        rendered = rendered,
                        providerConfigId = promptProviderConfigId,
                        model = promptModel,
                        requestId = UUID.randomUUID().toString(),
                    ),
                ).text
            } else {
                rendered.toPlainPrompt()
            }
            val imageSettings = templateAgentRunner.imageSettings(template.version)
            val size = imageSettings.size ?: task.inputPayload["size"].toString()
            val count = imageSettings.count ?: 1
            val filesResult = media.generateImage(requireNotNull(task.createdBy), (task.providerConfigId ?: 0), requireNotNull(task.modelName), imagePrompt, count, size, null).block() ?: error("图片服务未返回结果")
            val file = filesResult.firstOrNull() ?: error("图片服务未返回结果")
            task.inputPayload = task.inputPayload + mapOf("grid_file_id" to file.file.id.toString(), "signed_grid_url" to file.signedDownloadUrl)
            taskRepository.save(task); taskCenter.markSucceeded(taskId, "GENERATING_GRID")
        } catch (e: Exception) { taskCenter.markFailed(taskId, "STORYBOARD_GENERATION_FAILED", e.message ?: "分镜图生成失败") }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recover() { taskRepository.findAllByTaskTypeAndStatusIn(ProductionTaskType.IMAGE_GENERATION, listOf(ProductionTaskStatus.PENDING, ProductionTaskStatus.QUEUED, ProductionTaskStatus.RUNNING)).filter { it.inputPayload["prompt_key"] == "shot_storyboard_grid" }.forEach { if (it.status == ProductionTaskStatus.RUNNING) { it.status = ProductionTaskStatus.QUEUED; taskRepository.save(it) }; runCatching { executor.execute { execute(requireNotNull(it.id)) } } } }
    @PreDestroy fun shutdown() { executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS) }
}
