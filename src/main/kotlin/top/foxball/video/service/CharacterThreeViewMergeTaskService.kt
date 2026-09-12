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
import top.foxball.video.entity.jdbc.apiType
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.ProductionTaskRepository
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 将角色正、侧、背三张参考图提交给图片编辑模型，结果回填到角色合并三视图。 */
@Service
class CharacterThreeViewMergeTaskService(
    private val taskRepository: ProductionTaskRepository,
    private val providerConfigRepository: AiModelProviderConfigRepository,
    private val taskCenterService: TaskCenterService,
    private val assetService: AssetService,
    private val aiMediaGenerationService: AiMediaGenerationService,
    private val templateCatalog: TemplateCatalogService,
    private val templateRenderer: TemplateRenderer,
    private val templateAgentRunner: TemplateAgentRunner,
    private val templateRunAuditService: TemplateRunAuditService,
    private val fileService: FileService,
) {
    data class Submission(val task: ProductionTask, val existing: Boolean)

    class QueueFullException : IllegalStateException("三视图合并任务队列已满，请稍后重试")

    private val executor: ExecutorService = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        Executors.defaultThreadFactory(), ThreadPoolExecutor.AbortPolicy(),
    )

    @Transactional
    fun submit(
        projectId: Long,
        userId: Long?,
        episodeId: Long,
        assetId: Long,
        providerConfigId: Long,
        model: String,
        idempotencyKey: String?,
    ): Submission? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        val profileView = assetService.loadCharacterProfile(projectId, episodeId, assetId) ?: return null
        val sourceFileIds = listOf(
            profileView.profile.threeViewFrontFileId,
            profileView.profile.threeViewSideFileId,
            profileView.profile.threeViewBackFileId,
        ).map { it?.trim().orEmpty() }
        require(sourceFileIds.all { it.isNotBlank() }) { "请先准备正面、侧面和背面三张参考图" }
        require(providerConfigId > 0) { "提供商配置不正确" }
        require(model.trim().isNotBlank()) { "模型不能为空" }
        val config = providerConfigRepository.findById(providerConfigId).orElse(null)
            ?: throw IllegalArgumentException("AI 提供商配置不存在")
        require(config.enabled) { "AI 提供商配置已停用" }
        require(config.apiType == AiApiType.IMAGE) { "该提供商配置不是图片模型" }
        val normalizedKey = idempotencyKey?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedKey != null) {
            val existing = taskRepository.findFirstByProjectIdAndEpisodeIdAndTaskTypeAndIdempotencyKey(
                projectId, episodeId, ProductionTaskType.IMAGE_EDIT, normalizedKey,
            )
            if (existing != null) {
                val existingSourceFileIds = (existing.inputPayload["source_file_ids"] as? Collection<*>)?.map { it.toString() }
                require(
                    existing.createdBy == userId &&
                        existing.providerConfigId == providerConfigId &&
                        existing.modelName == model.trim() &&
                        existing.inputPayload["prompt_key"] == "character_three_view_merge" &&
                        (existing.inputPayload["episode_asset_id"] as? Number)?.toLong() == assetId &&
                        existingSourceFileIds == sourceFileIds
                ) { "幂等键已用于不同的三视图合并请求" }
                return Submission(existing, true)
            }
        }
        if ((executor as ThreadPoolExecutor).queue.remainingCapacity() <= 0) throw QueueFullException()
        val template = templateCatalog.resolve("character_three_view_merge", projectId, userId, "zh-CN")
        val task = taskRepository.save(
            ProductionTask(
                projectId = projectId,
                createdBy = userId,
                episodeId = episodeId,
                taskType = ProductionTaskType.IMAGE_EDIT,
                status = ProductionTaskStatus.QUEUED,
                title = "合并角色三视图",
                providerConfigId = providerConfigId,
                modelName = model.trim(),
                correlationId = UUID.randomUUID().toString(),
                idempotencyKey = normalizedKey,
                inputPayload = mapOf(
                    "prompt_key" to template.template.key,
                    "prompt_version_id" to requireNotNull(template.version.id),
                    "template_hash" to template.version.contentHash,
                    "episode_asset_id" to assetId,
                    "source_file_ids" to sourceFileIds,
                    "provider_config_id" to providerConfigId,
                    "model" to model.trim(),
                    "summary" to "合并正面、侧面、背面三视图",
                ),
            ),
        )
        taskCenterService.recordEnqueued(listOf(task))
        val taskId = requireNotNull(task.id)
        val dispatch = {
            try {
                executor.execute { execute(taskId) }
            } catch (_: RejectedExecutionException) {
                taskCenterService.markFailed(taskId, "TASK_QUEUE_FULL", "三视图合并任务队列已满，请稍后重试")
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    dispatch()
                }
            })
        } else dispatch()
        return Submission(task, false)
    }

    @Transactional(readOnly = true)
    fun detail(projectId: Long, userId: Long?, taskId: Long): ProductionTask? {
        if (!taskCenterService.canReadProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        return task.takeIf { it.taskType == ProductionTaskType.IMAGE_EDIT && it.inputPayload["prompt_key"] == "character_three_view_merge" }
    }

    private fun execute(taskId: Long) {
        val started = taskCenterService.markRunning(taskId) ?: return
        if (started.status != ProductionTaskStatus.RUNNING) return
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.nanoTime()
        var rendered: TemplateRenderer.RenderedTemplate? = null
        var generatedFileId: UUID? = null
        var mergedFileApplied = false
        try {
            val projectId = started.projectId
            val episodeId = requireNotNull(started.episodeId)
            val assetId = (started.inputPayload["episode_asset_id"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("合并任务缺少角色资产")
            val sourceFileIds = (started.inputPayload["source_file_ids"] as? Collection<*>)?.map { it.toString() }.orEmpty()
            require(sourceFileIds.size == 3 && sourceFileIds.all { it.isNotBlank() }) { "合并任务缺少三张参考图" }
            val template = templateCatalog.resolveExecutableVersion((started.inputPayload["prompt_version_id"] as Number).toLong())
                ?: throw IllegalArgumentException("三视图合并模板不存在")
            require(started.inputPayload["template_hash"]?.toString() == template.version.contentHash) { "三视图合并模板版本已变化" }
            val profile = assetService.loadCharacterProfile(projectId, episodeId, assetId)
                ?: throw IllegalArgumentException("角色资产不存在")
            val currentSourceFileIds = listOf(profile.profile.threeViewFrontFileId, profile.profile.threeViewSideFileId, profile.profile.threeViewBackFileId).map { it?.trim().orEmpty() }
            require(currentSourceFileIds == sourceFileIds) { "角色三视图已变化，请重新提交合并任务" }
            val variables = mapOf(
                "character_name" to profile.asset.name,
                "character_description" to listOfNotNull(profile.asset.description, profile.profile.faceDescription, profile.profile.costumeDescription, profile.profile.hairstyle, profile.profile.costumeRef, profile.profile.accessoryRef).filter(String::isNotBlank).joinToString("；"),
                "visual_style" to "电影级角色设定图，清晰线稿或高质量写实风格，统一色彩管理",
            )
            val renderedTemplate = templateRenderer.render(template, variables)
            rendered = renderedTemplate
            if (taskCenterService.isCancellationRequested(taskId)) {
                taskCenterService.markCancelledByWorker(taskId)
                return
            }
            // 三视图合并是图像编辑任务，任务只携带图像模型，模板渲染结果直接作为图像提示词：
            // 该模板不含技能与工具，无需额外的对话改写步骤。
            val imagePrompt = renderedTemplate.toPlainPrompt()
            val imageSettings = templateAgentRunner.imageSettings(template.version)
            val files = aiMediaGenerationService.editStoredImages(
                requireNotNull(started.createdBy), requireNotNull(started.providerConfigId), requireNotNull(started.modelName),
                imagePrompt, sourceFileIds, imageSettings.count ?: 1, imageSettings.size ?: "2K", requestId,
            ).block()
                ?: throw IllegalStateException("图片编辑服务未返回结果")
            val merged = files.firstOrNull() ?: throw IllegalStateException("图片编辑服务未返回图片")
            generatedFileId = merged.file.id
            if (taskCenterService.isCancellationRequested(taskId)) {
                fileService.delete(requireNotNull(started.createdBy), merged.file.id)
                generatedFileId = null
                taskCenterService.markCancelledByWorker(taskId)
                return
            }
            assetService.saveMergedCharacterThreeView(projectId, episodeId, assetId, sourceFileIds, merged.file.id.toString())
                ?: throw IllegalArgumentException("角色资产不存在")
            mergedFileApplied = true
            started.inputPayload = started.inputPayload + ("merged_file_id" to merged.file.id.toString())
            taskRepository.save(started)
            taskCenterService.markSucceeded(taskId, "MERGED_THREE_VIEW")
            runCatching {
                templateRunAuditService.record(
                    TemplateRunRecord(
                        templateKey = template.template.key,
                        templateVersionId = requireNotNull(template.version.id),
                        taskId = taskId,
                        invocationId = requestId,
                        providerConfigId = started.providerConfigId,
                        model = requireNotNull(started.modelName),
                        status = "SUCCEEDED",
                        attempt = started.attempt,
                        latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                        inputTokens = rendered.inputTokens,
                        templateHash = rendered.templateHash,
                        variablesHash = rendered.variablesHash,
                        renderedInputHash = rendered.renderedInputHash,
                        toolCallCount = 0,
                    ),
                )
            }
        } catch (exception: Exception) {
            if (!mergedFileApplied) generatedFileId?.let { fileId -> runCatching { fileService.delete(requireNotNull(started.createdBy), fileId) } }
            taskCenterService.markFailed(taskId, "CHARACTER_THREE_VIEW_MERGE_FAILED", exception.message?.take(2000) ?: "三视图合并失败")
            runCatching {
                val templateVersionId = (started.inputPayload["prompt_version_id"] as? Number)?.toLong() ?: return@runCatching
                templateRunAuditService.record(
                    TemplateRunRecord(
                        templateKey = "character_three_view_merge",
                        templateVersionId = templateVersionId,
                        taskId = taskId,
                        invocationId = requestId,
                        providerConfigId = started.providerConfigId,
                        model = started.modelName.orEmpty(),
                        status = "FAILED",
                        attempt = started.attempt,
                        latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                        inputTokens = rendered?.inputTokens,
                        templateHash = rendered?.templateHash,
                        variablesHash = rendered?.variablesHash,
                        renderedInputHash = rendered?.renderedInputHash,
                        validationErrorCode = "CHARACTER_THREE_VIEW_MERGE_FAILED",
                    ),
                )
            }
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverQueuedTasks() {
        taskRepository.findAllByTaskTypeAndStatusIn(
            ProductionTaskType.IMAGE_EDIT,
            listOf(ProductionTaskStatus.PENDING, ProductionTaskStatus.QUEUED, ProductionTaskStatus.RUNNING),
        ).filter { it.inputPayload["prompt_key"] == "character_three_view_merge" }.forEach { task ->
            if (task.status == ProductionTaskStatus.RUNNING) {
                task.status = ProductionTaskStatus.QUEUED
                taskRepository.save(task)
            }
            runCatching { executor.execute { execute(requireNotNull(task.id)) } }
                .onFailure { taskCenterService.markFailed(requireNotNull(task.id), "TASK_QUEUE_FULL", "三视图合并任务队列已满，请稍后重试") }
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
