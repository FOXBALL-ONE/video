package top.foxball.video.service

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.entity.jdbc.ScriptAnalysisResult
import top.foxball.video.entity.jdbc.ScriptAnalysisResultStatus
import top.foxball.video.repository.ProductionTaskRepository
import top.foxball.video.repository.ScriptAnalysisResultRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * 剧本资产解析任务：执行阶段只暂存按类型拆分的 AI 结果，采纳阶段才写入剧集资产库。
 */
@Service
class ScriptAnalysisTaskService(
    private val scriptService: ScriptService,
    private val assetService: AssetService,
    private val taskRepository: ProductionTaskRepository,
    private val resultRepository: ScriptAnalysisResultRepository,
    private val taskCenterService: TaskCenterService,
    private val analysisService: ScriptAssetAnalysisService,
    private val objectMapper: ObjectMapper,
) {
    data class ModelSelection(val assetType: EpisodeAssetType, val providerConfigId: Long, val model: String)

    data class Submission(val task: ProductionTask, val result: ScriptAnalysisResult, val existing: Boolean)

    data class Detail(
        val task: ProductionTask,
        val result: ScriptAnalysisResult,
        val resultStatus: ScriptAnalysisResultStatus,
    )

    data class Adoption(
        val task: ProductionTask,
        val result: ScriptAnalysisResult,
        val selectedTypes: List<EpisodeAssetType>,
        val createdAssetIds: List<Long>,
        val createdCount: Int,
        val conflictCount: Int,
        val missingNameCount: Int,
    )

    class ContentConflictException : IllegalStateException("剧本内容已变化，请重新解析当前版本")
    class QueueFullException : IllegalStateException("解析任务队列已满，请稍后重试")

    private data class StoredSelection(
        val assetType: String,
        val providerConfigId: Long,
        val model: String,
    )

    private data class StoredTypeResult(
        val status: String,
        val providerConfigId: Long,
        val model: String,
        val provider: String,
        val requestId: String,
        val items: List<JsonNode> = emptyList(),
        val resultHash: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    private data class StoredAdoption(
        val idempotencyKey: String?,
        val assetTypes: List<String>,
        val assetIds: List<Long>,
        val conflictCount: Int,
        val missingNameCount: Int,
        val adoptedBy: Long?,
        val adoptedAt: LocalDateTime,
    )

    private data class RecordedAdoption(
        val assetTypes: List<String>,
        val assetIds: List<Long>,
        val conflictCount: Int,
        val missingNameCount: Int,
    )

    private val executor: ExecutorService = ThreadPoolExecutor(
        2,
        2,
        0L,
        MILLISECONDS,
        ArrayBlockingQueue(32),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** 应用重启后恢复尚未产生暂存结果的解析任务。 */
    @EventListener(ApplicationReadyEvent::class)
    fun recoverProcessingTasks() {
        taskRepository.findAllByTaskTypeAndStatusIn(
            ProductionTaskType.SCRIPT_ANALYSIS,
            listOf(ProductionTaskStatus.PENDING, ProductionTaskStatus.QUEUED, ProductionTaskStatus.RUNNING),
        ).forEach { task ->
            val taskId = task.id ?: return@forEach
            val result = resultRepository.findByTaskId(taskId) ?: return@forEach
            if (result.resultStatus != ScriptAnalysisResultStatus.PROCESSING) return@forEach
            if (isResultSuperseded(result)) {
                taskCenterService.markFailed(taskId, "SCRIPT_VERSION_SUPERSEDED", "剧本版本已更新，请重新提交解析任务")
                return@forEach
            }
            if (task.status == ProductionTaskStatus.RUNNING) {
                task.status = ProductionTaskStatus.QUEUED
                task.stage = "RECOVERED"
                taskRepository.save(task)
            }
            try {
                executor.execute { execute(taskId) }
            } catch (_: RejectedExecutionException) {
                taskCenterService.markFailed(taskId, "TASK_QUEUE_FULL", "解析任务队列已满，请稍后重试")
            }
        }
    }

    @Transactional
    fun submit(
        projectId: Long,
        userId: Long?,
        episodeId: Long,
        versionId: Long,
        contentHash: String,
        selections: List<ModelSelection>,
        idempotencyKey: String?,
    ): Submission? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        require(episodeId > 0) { "必须选择剧集" }
        require(versionId > 0) { "剧本版本不正确" }
        val normalizedSelections = selections.map { it.copy(model = it.model.trim()) }.sortedBy { it.assetType.name }
        require(normalizedSelections.isNotEmpty()) { "至少选择一种资产类型" }
        require(normalizedSelections.map { it.assetType }.distinct().size == normalizedSelections.size) { "同一种资产只能选择一次" }
        require(normalizedSelections.all { it.assetType in setOf(EpisodeAssetType.CHARACTER, EpisodeAssetType.SCENE_BACKGROUND, EpisodeAssetType.PROP) }) { "该资产类型不支持剧本解析" }
        val snapshot = scriptService.loadExisting(projectId, episodeId) ?: return null
        val snapshotVersionId = requireNotNull(snapshot.version.id)
        if (snapshotVersionId != versionId) throw ContentConflictException()
        if (snapshot.version.content.isBlank()) throw IllegalArgumentException("剧本内容不能为空")
        val actualHash = contentHashOf(snapshot.version.content)
        if (!hashMatches(contentHash, actualHash)) throw ContentConflictException()
        val normalizedKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedKey != null) {
            val existing = resultRepository.findFirstByProjectIdAndEpisodeIdAndIdempotencyKey(projectId, episodeId, normalizedKey)
            if (existing != null) {
                if (existing.scriptVersionId != versionId || !hashMatches(existing.contentHash, actualHash) || existing.selectionsJson != selectionsJson(normalizedSelections)) {
                    throw IllegalArgumentException("幂等键已用于不同的解析请求")
                }
                val task = taskRepository.findById(existing.taskId).orElse(null) ?: return null
                return Submission(task, existing, true)
            }
        }
        normalizedSelections.forEach { selection -> analysisService.validateConfiguration(selection.providerConfigId, selection.model) }
        if ((executor as ThreadPoolExecutor).queue.remainingCapacity() <= 0) throw QueueFullException()
        val first = normalizedSelections.first()
        val task = taskRepository.save(
            ProductionTask(
                projectId = projectId,
                episodeId = episodeId,
                createdBy = userId,
                taskType = ProductionTaskType.SCRIPT_ANALYSIS,
                status = ProductionTaskStatus.QUEUED,
                title = "剧本资产解析",
                correlationId = UUID.randomUUID().toString(),
                idempotencyKey = normalizedKey,
                providerConfigId = first.providerConfigId,
                modelName = first.model,
                inputPayload = mapOf(
                    "version_id" to versionId,
                    "content_hash" to actualHash,
                    "content_length" to snapshot.version.content.length,
                    "content_summary" to "已保存剧本正文快照（正文不写入任务输入）",
                    "asset_types" to normalizedSelections.map { it.assetType.name },
                    "selections" to normalizedSelections.map { mapOf("asset_type" to it.assetType.name, "provider_config_id" to it.providerConfigId, "model" to it.model) },
                    "summary" to "解析剧本并暂存所选资产",
                ),
                maxAttempts = 1,
            ),
        )
        val result = resultRepository.save(
            ScriptAnalysisResult(
                taskId = requireNotNull(task.id),
                projectId = projectId,
                episodeId = episodeId,
                scriptVersionId = versionId,
                contentHash = actualHash,
                sourceContent = snapshot.version.content,
                selectionsJson = selectionsJson(normalizedSelections),
                idempotencyKey = normalizedKey,
            ),
        )
        taskCenterService.recordEnqueued(listOf(task))
        val taskId = requireNotNull(task.id)
        val dispatch: () -> Unit = {
            try {
                executor.execute { execute(taskId) }
            } catch (_: RejectedExecutionException) {
                taskCenterService.markFailed(taskId, "TASK_QUEUE_FULL", "解析任务队列已满，请稍后重试")
            }
            Unit
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    dispatch()
                }
            })
        } else {
            dispatch()
        }
        return Submission(task, result, false)
    }

    fun detail(projectId: Long, userId: Long?, taskId: Long): Detail? {
        if (!taskCenterService.canReadProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        val result = resultRepository.findByTaskId(taskId) ?: return null
        if (result.projectId != projectId || result.taskId != taskId || task.episodeId != result.episodeId) return null
        val resultStatus = if (
            result.resultStatus in setOf(
                ScriptAnalysisResultStatus.PROCESSING,
                ScriptAnalysisResultStatus.STAGED,
                ScriptAnalysisResultStatus.PARTIALLY_ADOPTED,
            ) && isResultSuperseded(result)
        ) {
            // 旧数据库约束可能不允许持久化 SUPERSEDED；这里只在响应层派生过期状态。
            ScriptAnalysisResultStatus.SUPERSEDED
        } else {
            result.resultStatus
        }
        return Detail(task, result, resultStatus)
    }

    @Transactional
    fun discard(projectId: Long, userId: Long?, taskId: Long): Detail? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return null
        if (result.projectId != projectId || result.taskId != taskId || task.episodeId != result.episodeId) return null
        if (task.status != ProductionTaskStatus.SUCCEEDED) throw IllegalStateException("解析任务尚未成功")
        if (result.resultStatus == ScriptAnalysisResultStatus.ADOPTED) throw IllegalStateException("解析结果已采纳，不能放弃")
        if (result.resultStatus != ScriptAnalysisResultStatus.DISCARDED) {
            result.resultStatus = ScriptAnalysisResultStatus.DISCARDED
            result.discardedAt = LocalDateTime.now()
            resultRepository.save(result)
        }
        return Detail(task, result, result.resultStatus)
    }

    @Transactional
    fun adopt(
        projectId: Long,
        userId: Long?,
        taskId: Long,
        versionId: Long,
        requestedTypes: List<EpisodeAssetType>,
        idempotencyKey: String?,
    ): Adoption? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        require(versionId > 0) { "剧本版本不正确" }
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return null
        if (result.projectId != projectId || result.taskId != taskId || task.episodeId != result.episodeId) return null
        if (isResultSuperseded(result)) throw ContentConflictException()
        val snapshot = scriptService.loadExisting(projectId, result.episodeId) ?: return null
        if (snapshot.version.id != versionId || versionId != result.scriptVersionId || !hashMatches(contentHashOf(snapshot.version.content), result.contentHash)) {
            throw ContentConflictException()
        }
        if (result.resultStatus == ScriptAnalysisResultStatus.SUPERSEDED) throw ContentConflictException()
        if (task.status != ProductionTaskStatus.SUCCEEDED || result.resultStatus !in setOf(ScriptAnalysisResultStatus.STAGED, ScriptAnalysisResultStatus.PARTIALLY_ADOPTED, ScriptAnalysisResultStatus.ADOPTED)) {
            throw IllegalStateException("解析结果尚不可采纳")
        }
        val successful = successfulTypeItems(result)
        val selected = if (requestedTypes.isEmpty()) successful.keys.toList() else requestedTypes.distinct()
        if (selected.isEmpty() || selected.any { it !in successful }) throw IllegalArgumentException("只能采纳解析成功的资产类型")
        if (selected.any { successful[it].isNullOrEmpty() }) throw IllegalArgumentException("所选解析结果为空，无法采纳")
        val normalizedAdoptionKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        val selectedNames = selected.map { it.name }
        val recorded = normalizedAdoptionKey?.let { existingAdoption(result, it) }
        if (recorded != null) {
            if (recorded.assetTypes.toSet() != selectedNames.toSet()) throw IllegalArgumentException("幂等键已用于不同的采纳请求")
            return Adoption(task, result, selected, recorded.assetIds, recorded.assetIds.size, recorded.conflictCount, recorded.missingNameCount)
        }
        val adopted = result.adoptedTypes.split(',').mapNotNull { value -> runCatching { EpisodeAssetType.valueOf(value) }.getOrNull() }.toMutableSet()
        val toApply = selected.filter { it !in adopted }
        if (toApply.isEmpty()) {
            return Adoption(task, result, selected, result.adoptedAssetIds.split(',').mapNotNull { it.toLongOrNull() }, 0, 0, 0)
        }
        val assetIds = mutableListOf<Long>()
        var conflicts = 0
        var missingNames = 0
        toApply.forEach { type ->
            val applied = assetService.adoptAnalysisAssets(projectId, result.episodeId, type, successful.getValue(type))
                ?: throw IllegalStateException("剧集不存在或已移除")
            assetIds += applied.assets.mapNotNull { it.id }
            conflicts += applied.conflictCount
            missingNames += applied.missingNameCount
            adopted += type
        }
        result.adoptedTypes = adopted.joinToString(",") { it.name }
        result.adoptedAssetIds = (result.adoptedAssetIds.split(',').mapNotNull { it.toLongOrNull() } + assetIds).distinct().joinToString(",")
        result.resultStatus = if (successful.keys.all { it in adopted }) ScriptAnalysisResultStatus.ADOPTED else ScriptAnalysisResultStatus.PARTIALLY_ADOPTED
        result.adoptedAt = LocalDateTime.now()
        val record = objectMapper.writeValueAsString(
            StoredAdoption(
                idempotencyKey = normalizedAdoptionKey,
                assetTypes = toApply.map { it.name },
                assetIds = assetIds,
                conflictCount = conflicts,
                missingNameCount = missingNames,
                adoptedBy = userId,
                adoptedAt = LocalDateTime.now(),
            ),
        )
        val records = objectMapper.createArrayNode()
        objectMapper.readTree(result.adoptionRecordsJson).takeIf { it.isArray }?.forEach(records::add)
        records.add(objectMapper.readTree(record))
        result.adoptionRecordsJson = objectMapper.writeValueAsString(records)
        val saved = resultRepository.saveAndFlush(result)
        return Adoption(task, saved, selected, assetIds, assetIds.size, conflicts, missingNames)
    }

    private fun existingAdoption(result: ScriptAnalysisResult, idempotencyKey: String): RecordedAdoption? {
        val records = runCatching { objectMapper.readTree(result.adoptionRecordsJson) }.getOrNull()
            ?.takeIf { it.isArray }?.toList().orEmpty()
        val record = records.firstOrNull { it.path("idempotencyKey").textValue() == idempotencyKey } ?: return null
        return RecordedAdoption(
            assetTypes = record.path("assetTypes").takeIf { it.isArray }?.toList()?.mapNotNull { it.textValue() }.orEmpty(),
            assetIds = record.path("assetIds").takeIf { it.isArray }?.toList()?.mapNotNull { it.longValue().takeIf { value -> value > 0 } }.orEmpty(),
            conflictCount = record.path("conflictCount").intValue(),
            missingNameCount = record.path("missingNameCount").intValue(),
        )
    }

    private fun execute(taskId: Long) {
        val started = runCatching { taskCenterService.markRunning(taskId) }.getOrNull() ?: return
        if (started.status != ProductionTaskStatus.RUNNING) return
        val result = resultRepository.findByTaskId(taskId) ?: run {
            taskCenterService.markFailed(taskId, "MISSING_ANALYSIS_RESULT", "解析结果记录不存在")
            return
        }
        if (result.resultStatus == ScriptAnalysisResultStatus.SUPERSEDED || isResultSuperseded(result)) {
            taskCenterService.markFailed(taskId, "SCRIPT_VERSION_SUPERSEDED", "剧本版本已更新，请重新提交解析任务")
            return
        }
        val typeResults = linkedMapOf<String, StoredTypeResult>()
        var selections = emptyList<ModelSelection>()
        try {
            selections = parseSelections(result.selectionsJson)
            selections.forEachIndexed { index, selection ->
                checkNotCancelled(taskId)
                val currentResult = resultRepository.findByTaskId(taskId)
                if (currentResult?.resultStatus == ScriptAnalysisResultStatus.SUPERSEDED || currentResult?.let(::isResultSuperseded) == true) {
                    throw SupersededResultException()
                }
                val analysis = analysisService.analyze(selection.assetType, selection.providerConfigId, selection.model, result.sourceContent)
                typeResults[selection.assetType.name] = StoredTypeResult(
                    status = if (analysis.succeeded) "SUCCEEDED" else "FAILED",
                    providerConfigId = analysis.providerConfigId,
                    model = analysis.model,
                    provider = analysis.provider,
                    requestId = analysis.requestId,
                    items = analysis.items,
                    resultHash = analysis.items.takeIf { analysis.succeeded }?.let { contentHashOf(objectMapper.writeValueAsString(it)) },
                    errorCode = analysis.errorCode,
                    errorMessage = analysis.errorMessage?.take(2_000),
                )
                taskCenterService.updateProgress(taskId, ((index + 1) * 100 / selections.size), "ANALYZE_${selection.assetType.name}")
            }
            checkNotCancelled(taskId)
            result.typeResultsJson = objectMapper.writeValueAsString(typeResults)
            val succeeded = typeResults.filterValues { it.status == "SUCCEEDED" }
            if (succeeded.isEmpty()) {
                resultRepository.save(result)
                val failure = typeResults.values.firstOrNull { it.errorCode != null }
                taskCenterService.markFailed(taskId, failure?.errorCode ?: "SCRIPT_ANALYSIS_FAILED", failure?.errorMessage ?: "所有资产类型解析失败")
                return
            }
            val resultNode = objectMapper.createObjectNode()
            succeeded.forEach { (typeName, typeResult) ->
                val resultField = when (typeName) {
                    EpisodeAssetType.CHARACTER.name -> "characters"
                    EpisodeAssetType.SCENE_BACKGROUND.name -> "scenes"
                    EpisodeAssetType.PROP.name -> "props"
                    else -> null
                } ?: return@forEach
                val items = objectMapper.createArrayNode()
                typeResult.items.forEach(items::add)
                resultNode.set(resultField, items)
            }
            result.resultJson = objectMapper.writeValueAsString(resultNode)
            result.resultStatus = ScriptAnalysisResultStatus.STAGED
            resultRepository.save(result)
            val completed = taskCenterService.markSucceeded(taskId, if (succeeded.size == selections.size) "RESULT_STAGED" else "PARTIAL_RESULT_STAGED")
            if (completed?.status != ProductionTaskStatus.SUCCEEDED) return
        } catch (_: CancellationRequestedException) {
            selections.filter { it.assetType.name !in typeResults }.forEach { selection ->
                typeResults[selection.assetType.name] = StoredTypeResult(
                    status = "CANCELLED",
                    providerConfigId = selection.providerConfigId,
                    model = selection.model,
                    provider = "unknown",
                    requestId = "",
                    errorCode = "TASK_CANCELLED",
                    errorMessage = "任务已取消",
                )
            }
            if (typeResults.isNotEmpty()) {
                result.typeResultsJson = objectMapper.writeValueAsString(typeResults)
                resultRepository.save(result)
            }
            taskCenterService.markCancelledByWorker(taskId)
        } catch (_: SupersededResultException) {
            taskCenterService.markFailed(taskId, "SCRIPT_VERSION_SUPERSEDED", "剧本版本已更新，请重新提交解析任务")
        } catch (exception: Exception) {
            if (typeResults.isNotEmpty()) {
                result.typeResultsJson = objectMapper.writeValueAsString(typeResults)
                resultRepository.save(result)
            }
            taskCenterService.markFailed(taskId, "SCRIPT_ANALYSIS_FAILED", "剧本资产解析失败")
        }
    }

    private fun parseSelections(json: String): List<ModelSelection> = objectMapper.readTree(json).toList().map { node ->
        val typeName = node.path("assetType").textValue() ?: node.path("asset_type").textValue()
        val type = runCatching { EpisodeAssetType.valueOf(typeName) }.getOrElse { throw IllegalStateException("解析资产类型不正确") }
        val providerConfigId = (node.path("providerConfigId").longValue().takeIf { it > 0 }
            ?: node.path("provider_config_id").longValue())
        val model = (node.path("model").textValue() ?: node.path("model_name").textValue())?.trim().orEmpty()
        require(providerConfigId > 0 && model.isNotBlank()) { "解析模型配置不正确" }
        ModelSelection(type, providerConfigId, model)
    }

    private fun successfulTypeItems(result: ScriptAnalysisResult): Map<EpisodeAssetType, List<JsonNode>> {
        val root = objectMapper.readTree(result.typeResultsJson)
        val selectedTypes = parseSelections(result.selectionsJson).map { it.assetType }.toSet()
        return selectedTypes.mapNotNull { type ->
            val typeResult = root.path(type.name)
            if (typeResult.path("status").textValue() != "SUCCEEDED") return@mapNotNull null
            val items = typeResult.path("items").takeIf { it.isArray }?.toList().orEmpty()
            type to items
        }.toMap()
    }

    private fun selectionsJson(selections: List<ModelSelection>): String = objectMapper.writeValueAsString(
        selections.map { StoredSelection(it.assetType.name, it.providerConfigId, it.model.trim()) },
    )

    private fun checkNotCancelled(taskId: Long) {
        if (taskCenterService.isCancellationRequested(taskId)) throw CancellationRequestedException()
    }

    private fun isResultSuperseded(result: ScriptAnalysisResult): Boolean {
        val snapshot = scriptService.loadExisting(result.projectId, result.episodeId) ?: return true
        return snapshot.version.id != result.scriptVersionId || !hashMatches(contentHashOf(snapshot.version.content), result.contentHash)
    }

    private fun contentHashOf(content: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun hashMatches(left: String, right: String): Boolean = left.trim().equals(right.trim(), ignoreCase = true)

    private class CancellationRequestedException : RuntimeException()
    private class SupersededResultException : RuntimeException()

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
