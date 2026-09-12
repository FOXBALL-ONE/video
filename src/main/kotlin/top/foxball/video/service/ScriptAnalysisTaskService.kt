package top.foxball.video.service

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.TemplateCategory
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val templateCatalog: TemplateCatalogService,
    private val templateRenderer: TemplateRenderer,
    private val resultWriter: ScriptAnalysisResultWriter,
    @Value("\${video.script-asset-parallel-extraction:true}") private val parallelEnabled: Boolean,
) {
    data class ModelSelection(
        val assetType: EpisodeAssetType,
        val providerConfigId: Long,
        val model: String,
        val promptVersionId: Long? = null,
    )

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
        val promptKey: String = "",
        val promptCategory: String = "",
        val promptVersionId: Long = 0,
        val templateHash: String = "",
        val variablesHash: String = "",
        val outputSchemaVersion: String = "",
        val selectionSource: String = "DEFAULT",
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

    private val childExecutor: ExecutorService = ThreadPoolExecutor(
        if (parallelEnabled) 4 else 1,
        if (parallelEnabled) 4 else 1,
        0L,
        MILLISECONDS,
        ArrayBlockingQueue(32),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val aggregationExecutor: ScheduledExecutorService = ScheduledThreadPoolExecutor(1).also {
        it.setRemoveOnCancelPolicy(true)
    }

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
        stream: Boolean = true,
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
        val storedSelections = normalizedSelections.map { selection ->
            analysisService.validateConfiguration(selection.providerConfigId, selection.model)
            val template = templateCatalog.resolveScriptAssetTemplate(
                selection.assetType,
                projectId,
                userId,
                selection.promptVersionId,
            )
            val variables = linkedMapOf<String, Any?>(
                "script_content" to snapshot.version.content,
                "episode_context_json" to objectMapper.writeValueAsString(mapOf("episode_id" to episodeId, "asset_type" to selection.assetType.name)),
                "output_language" to template.version.locale,
                "detail_level" to "STANDARD",
            )
            if (template.template.key == "script_analysis") variables["asset_type"] = selection.assetType.name
            StoredSelection(
                selection.assetType.name,
                selection.providerConfigId,
                selection.model,
                template.template.key,
                templateCatalog.effectiveCategory(template.template).name,
                requireNotNull(template.version.id),
                template.version.contentHash,
                templateRenderer.variablesHash(variables),
                "${template.template.key}.v${template.version.versionNo}",
                if (selection.promptVersionId != null) "EXPLICIT" else if (template.template.key == "script_analysis") "LEGACY_FALLBACK" else "DEFAULT",
            )
        }
        val normalizedKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedKey != null) {
            val existing = resultRepository.findFirstByProjectIdAndEpisodeIdAndIdempotencyKey(projectId, episodeId, normalizedKey)
            if (existing != null) {
                if (existing.scriptVersionId != versionId || !hashMatches(existing.contentHash, actualHash) || !sameSelections(existing.selectionsJson, storedSelections)) {
                    throw IllegalArgumentException("幂等键已用于不同的解析请求")
                }
                val task = taskRepository.findById(existing.taskId).orElse(null) ?: return null
                return Submission(task, existing, true)
            }
        }
        if ((executor as ThreadPoolExecutor).queue.remainingCapacity() <= 0) throw QueueFullException()
        val first = storedSelections.first()
        val uniformPrompt = storedSelections.map { Triple(it.promptKey, it.promptVersionId, it.templateHash) }.distinct().singleOrNull()
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
                    "prompt_key" to uniformPrompt?.first,
                    "prompt_version_id" to uniformPrompt?.second,
                    "template_hash" to uniformPrompt?.third,
                    "prompt_selection_mode" to "PER_ASSET_TYPE",
                    "prompt_selections" to storedSelections.map(::selectionSnapshot),
                    "variables_hashes" to storedSelections.associate { it.assetType to it.variablesHash },
                    "output_schema_version" to first.outputSchemaVersion,
                    "version_id" to versionId,
                    "content_hash" to actualHash,
                    "content_length" to snapshot.version.content.length,
                    "content_summary" to "已保存剧本来源引用（正文不写入任务输入）",
                    "stream" to stream,
                    "asset_types" to storedSelections.map { it.assetType },
                    "selections" to storedSelections.map { selectionSnapshot(it) },
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
                selectionsJson = selectionsJson(storedSelections),
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

    @Transactional
    fun retry(projectId: Long, userId: Long?, taskId: Long): Submission? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        require(task.taskType == ProductionTaskType.SCRIPT_ANALYSIS) { "该任务不支持剧本解析重试" }
        require(task.status == ProductionTaskStatus.FAILED) { "只有失败的剧本解析任务可以重试" }
        val result = resultRepository.findByTaskId(taskId) ?: throw IllegalStateException("解析结果记录不存在")
        val selections = executionSelections(result, task).map { selection ->
            ModelSelection(
                assetType = EpisodeAssetType.valueOf(selection.assetType),
                providerConfigId = selection.providerConfigId,
                model = selection.model,
                promptVersionId = selection.promptVersionId.takeIf { it > 0 },
            )
        }
        val stream = when (val value = task.inputPayload["stream"]) {
            is Boolean -> value
            else -> value?.toString()?.toBooleanStrictOrNull() ?: true
        }
        return submit(
            projectId = projectId,
            userId = userId,
            episodeId = result.episodeId,
            versionId = result.scriptVersionId,
            contentHash = result.contentHash,
            selections = selections,
            idempotencyKey = "script-analysis-retry-$taskId-${UUID.randomUUID()}",
            stream = stream,
        )
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
        val record = records.firstOrNull { it.get("idempotencyKey")?.takeIf { value -> value.isString }?.stringValue() == idempotencyKey } ?: return null
        return RecordedAdoption(
            assetTypes = record.path("assetTypes").takeIf { it.isArray }?.toList()?.mapNotNull { it.textValue() }.orEmpty(),
            assetIds = record.path("assetIds").takeIf { it.isArray }?.toList()?.mapNotNull { it.longValue().takeIf { value -> value > 0 } }.orEmpty(),
            conflictCount = record.path("conflictCount").intValue(),
            missingNameCount = record.path("missingNameCount").intValue(),
        )
    }

    private fun execute(taskId: Long) {
        val started = runCatching { taskCenterService.markRunning(taskId) }.getOrNull() ?: return
        if (started.status != ProductionTaskStatus.RUNNING) {
            if (started.status == ProductionTaskStatus.CANCELLED) {
                val assetTypes = (started.inputPayload["asset_types"] as? Collection<*>)
                    ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                    .orEmpty()
                if (assetTypes.isNotEmpty()) resultWriter.cancelUnfinished(taskId, assetTypes)
            }
            return
        }
        val result = resultRepository.findByTaskId(taskId) ?: run {
            taskCenterService.markFailed(taskId, "MISSING_ANALYSIS_RESULT", "解析结果记录不存在")
            return
        }
        if (result.resultStatus == ScriptAnalysisResultStatus.SUPERSEDED || isResultSuperseded(result)) {
            taskCenterService.markFailed(taskId, "SCRIPT_VERSION_SUPERSEDED", "剧本版本已更新，请重新提交解析任务")
            return
        }
        val selections = runCatching { executionSelections(result, started) }.getOrElse { exception ->
            val reason = exception.message?.trim()?.takeIf { it.isNotBlank() }?.take(200)
            taskCenterService.markFailed(taskId, "INVALID_SELECTION_SNAPSHOT", reason?.let { "解析任务配置快照无效：$it" } ?: "解析任务配置快照无效")
            return
        }
        if (selections.isEmpty()) {
            taskCenterService.markFailed(taskId, "INVALID_SELECTION_SNAPSHOT", "解析任务没有资产类型")
            return
        }
        val initial = selections.associate { selection ->
            selection.assetType to mapOf(
                "status" to "PENDING",
                "providerConfigId" to selection.providerConfigId,
                "model" to selection.model,
                "promptKey" to selection.promptKey,
                "promptCategory" to selection.promptCategory,
                "promptVersionId" to selection.promptVersionId,
                "templateHash" to selection.templateHash,
                "variablesHash" to selection.variablesHash,
                "outputSchemaVersion" to selection.outputSchemaVersion,
                "selectionSource" to selection.selectionSource,
                "progress" to 0,
                "stage" to "PENDING",
            )
        }
        if (!resultWriter.initialize(taskId, initial)) {
            if (taskCenterService.isCancellationRequested(taskId)) {
                resultWriter.cancelUnfinished(taskId, selections.map { it.assetType })
            }
            return
        }
        val currentTypeResults = runCatching { objectMapper.readTree(result.typeResultsJson) }.getOrNull()
        val pendingSelections = selections.filter { selection ->
            currentTypeResults?.get(selection.assetType)?.get("status")?.takeIf { it.isString }?.stringValue() !in setOf("SUCCEEDED", "FAILED", "CANCELLED")
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(pendingSelections.size)
        val aggregateOnce = AtomicBoolean(false)
        if (pendingSelections.isEmpty()) {
            scheduleAggregation(taskId, selections.map { it.assetType })
            return
        }
        pendingSelections.forEach { selection ->
            if (!resultWriter.updateTypeResult(taskId, selection.assetType, mapOf("status" to "QUEUED", "stage" to "QUEUED", "progress" to 0))) {
                if (remaining.decrementAndGet() == 0 && aggregateOnce.compareAndSet(false, true)) scheduleAggregation(taskId, selections.map { it.assetType })
                return@forEach
            }
            try {
                CompletableFuture.runAsync({ runChildSafely(taskId, started, result, selection) }, childExecutor)
                    .whenComplete { _, _ ->
                        if (remaining.decrementAndGet() == 0 && aggregateOnce.compareAndSet(false, true)) scheduleAggregation(taskId, selections.map { it.assetType })
                    }
            } catch (_: RejectedExecutionException) {
                resultWriter.updateTypeResult(taskId, selection.assetType, failureValues(selection, "TASK_QUEUE_FULL", "解析任务队列已满，请稍后重试"))
                if (remaining.decrementAndGet() == 0 && aggregateOnce.compareAndSet(false, true)) scheduleAggregation(taskId, selections.map { it.assetType })
            }
        }
    }

    private fun runChild(taskId: Long, parent: ProductionTask, result: ScriptAnalysisResult, selection: StoredSelection) {
        if (taskCenterService.isCancellationRequested(taskId)) {
            resultWriter.updateTypeResult(taskId, selection.assetType, failureValues(selection, "TASK_CANCELLED", "任务已取消", cancelled = true))
            return
        }
        val startedAt = LocalDateTime.now()
        if (!resultWriter.updateTypeResult(taskId, selection.assetType, mapOf("status" to "RUNNING", "stage" to "CALL_PROVIDER", "progress" to 10, "startedAt" to startedAt.toString()))) return
        if (taskCenterService.isCancellationRequested(taskId)) {
            resultWriter.updateTypeResult(taskId, selection.assetType, failureValues(selection, "TASK_CANCELLED", "任务已取消", cancelled = true))
            return
        }
        val stream = when (val value = parent.inputPayload["stream"]) {
            is Boolean -> value
            else -> value?.toString()?.toBooleanStrictOrNull() ?: true
        }
        val analysis = try {
            analysisService.analyze(
                assetType = EpisodeAssetType.valueOf(selection.assetType),
                providerConfigId = selection.providerConfigId,
                model = selection.model,
                sourceContent = result.sourceContent,
                promptVersionId = selection.promptVersionId.takeIf { it > 0 },
                promptHash = selection.templateHash.takeIf { it.isNotBlank() },
                episodeId = result.episodeId,
                taskId = parent.id,
                attempt = parent.attempt,
                stream = stream,
                promptKey = selection.promptKey.takeIf { it.isNotBlank() },
                promptCategory = selection.promptCategory.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            resultWriter.updateTypeResult(taskId, selection.assetType, failureValues(selection, "AI_ANALYSIS_FAILED", "剧本资产解析失败"))
            return
        }
        val cancelled = taskCenterService.isCancellationRequested(taskId)
        if (cancelled) {
            resultWriter.updateTypeResult(taskId, selection.assetType, failureValues(selection, "TASK_CANCELLED", "任务已取消", cancelled = true))
            return
        }
        val completedAt = LocalDateTime.now()
        resultWriter.updateTypeResult(
            taskId,
            selection.assetType,
            mapOf(
                "status" to if (analysis.succeeded) "SUCCEEDED" else "FAILED",
                "stage" to if (analysis.succeeded) "COMPLETED" else "FAILED",
                "progress" to 100,
                "providerConfigId" to analysis.providerConfigId,
                "model" to analysis.model,
                "provider" to analysis.provider,
                "requestId" to analysis.requestId,
                "promptKey" to selection.promptKey,
                "promptCategory" to selection.promptCategory,
                "promptVersionId" to selection.promptVersionId,
                "templateHash" to selection.templateHash,
                "variablesHash" to selection.variablesHash,
                "outputSchemaVersion" to selection.outputSchemaVersion,
                "selectionSource" to selection.selectionSource,
                "items" to analysis.items,
                "resultHash" to analysis.items.takeIf { analysis.succeeded }?.let { contentHashOf(objectMapper.writeValueAsString(it)) },
                "errorCode" to analysis.errorCode,
                "errorMessage" to analysis.errorMessage?.take(2_000),
                "completedAt" to completedAt.toString(),
                "latencyMs" to java.time.Duration.between(startedAt, completedAt).toMillis(),
            ),
        )
        updateParentProgress(taskId, parent.id, result.selectionsJson)
    }

    private fun runChildSafely(taskId: Long, parent: ProductionTask, result: ScriptAnalysisResult, selection: StoredSelection) {
        try {
            runChild(taskId, parent, result, selection)
        } catch (_: Exception) {
            resultWriter.updateTypeResult(taskId, selection.assetType, failureValues(selection, "AI_ANALYSIS_FAILED", "剧本资产解析失败"))
        } finally {
            updateParentProgress(taskId, parent.id, result.selectionsJson)
        }
    }

    private fun scheduleAggregation(taskId: Long, assetTypes: List<String>, delayMs: Long = 0L) {
        try {
            aggregationExecutor.schedule({
                val aggregation = resultWriter.aggregate(taskId, assetTypes)
                when (aggregation.status) {
                    ScriptAnalysisResultWriter.Aggregation.Status.SUCCEEDED -> taskCenterService.markSucceeded(taskId, aggregation.stage)
                    ScriptAnalysisResultWriter.Aggregation.Status.FAILED -> taskCenterService.markFailed(taskId, aggregation.errorCode, aggregation.errorMessage)
                    ScriptAnalysisResultWriter.Aggregation.Status.CANCELLED -> taskCenterService.markCancelledByWorker(taskId)
                    ScriptAnalysisResultWriter.Aggregation.Status.PENDING -> scheduleAggregation(taskId, assetTypes, 50L)
                    ScriptAnalysisResultWriter.Aggregation.Status.TERMINAL -> Unit
                }
            }, delayMs, MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            taskCenterService.markFailed(taskId, "TASK_QUEUE_FULL", "解析结果汇聚队列已满，请稍后重试")
        }
    }

    private fun failureValues(selection: StoredSelection, code: String, message: String, cancelled: Boolean = false): Map<String, Any?> = mapOf(
        "status" to if (cancelled) "CANCELLED" else "FAILED",
        "stage" to if (cancelled) "CANCELLED" else "FAILED",
        "progress" to 100,
        "providerConfigId" to selection.providerConfigId,
        "model" to selection.model,
        "promptKey" to selection.promptKey,
        "promptCategory" to selection.promptCategory,
        "promptVersionId" to selection.promptVersionId,
        "templateHash" to selection.templateHash,
        "variablesHash" to selection.variablesHash,
        "outputSchemaVersion" to selection.outputSchemaVersion,
        "selectionSource" to selection.selectionSource,
        "provider" to "unknown",
        "requestId" to "",
        "items" to emptyList<JsonNode>(),
        "errorCode" to code,
        "errorMessage" to message,
        "completedAt" to LocalDateTime.now().toString(),
    )

    private fun updateParentProgress(taskId: Long, parentId: Long?, selectionsJson: String) {
        val parentTaskId = parentId ?: taskId
        val types = runCatching { parseStoredSelections(selectionsJson).map { it.assetType } }.getOrDefault(emptyList())
        resultWriter.progress(taskId, types)?.let { taskCenterService.updateProgress(parentTaskId, it, "ANALYZING_ASSETS", indeterminate = false) }
    }

    private fun parseStoredSelections(json: String): List<StoredSelection> {
        val root = runCatching { objectMapper.readTree(json) }.getOrElse { throw IllegalStateException("解析任务配置快照不是合法 JSON") }
        require(root.isArray) { "解析任务配置快照必须是数组" }
        return root.toList().map { node ->
            val typeName = (node.get("assetType")?.takeIf { it.isString }?.stringValue()
                ?: node.get("asset_type")?.takeIf { it.isString }?.stringValue())
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("解析任务资产类型缺失")
            val type = runCatching { EpisodeAssetType.valueOf(typeName.uppercase()) }.getOrElse { throw IllegalStateException("解析资产类型不正确") }
            val providerConfigId = (node.get("providerConfigId")?.takeIf { it.isIntegralNumber }?.longValue()?.takeIf { it > 0 }
                ?: node.get("provider_config_id")?.takeIf { it.isIntegralNumber }?.longValue()
                ?: 0L)
            val model = (node.get("model")?.takeIf { it.isString }?.stringValue()
                ?: node.get("model_name")?.takeIf { it.isString }?.stringValue())?.trim().orEmpty()
            require(providerConfigId > 0 && model.isNotBlank()) { "解析模型配置不正确" }
            StoredSelection(
                assetType = type.name,
                providerConfigId = providerConfigId,
                model = model,
                promptKey = node.get("promptKey")?.takeIf { it.isString }?.stringValue()
                    ?: node.get("prompt_key")?.takeIf { it.isString }?.stringValue().orEmpty(),
                promptCategory = node.get("promptCategory")?.takeIf { it.isString }?.stringValue()
                    ?: node.get("prompt_category")?.takeIf { it.isString }?.stringValue().orEmpty(),
                promptVersionId = node.get("promptVersionId")?.takeIf { it.isIntegralNumber }?.longValue()?.takeIf { it > 0 }
                    ?: node.get("prompt_version_id")?.takeIf { it.isIntegralNumber }?.longValue()
                    ?: 0L,
                templateHash = node.get("templateHash")?.takeIf { it.isString }?.stringValue()
                    ?: node.get("template_hash")?.takeIf { it.isString }?.stringValue().orEmpty(),
                variablesHash = node.get("variablesHash")?.takeIf { it.isString }?.stringValue()
                    ?: node.get("variables_hash")?.takeIf { it.isString }?.stringValue().orEmpty(),
                outputSchemaVersion = node.get("outputSchemaVersion")?.takeIf { it.isString }?.stringValue()
                    ?: node.get("output_schema_version")?.takeIf { it.isString }?.stringValue().orEmpty(),
                selectionSource = (
                    node.get("selectionSource")?.takeIf { it.isString }?.stringValue()
                        ?: node.get("selection_source")?.takeIf { it.isString }?.stringValue()
                )?.trim()?.takeIf { it.isNotBlank() } ?: "DEFAULT",
            )
        }
    }

    private fun successfulTypeItems(result: ScriptAnalysisResult): Map<EpisodeAssetType, List<JsonNode>> {
        val root = objectMapper.readTree(result.typeResultsJson)
        val selectedTypes = parseStoredSelections(result.selectionsJson).map { EpisodeAssetType.valueOf(it.assetType) }.toSet()
        return selectedTypes.mapNotNull { type ->
            val typeResult = root.path(type.name)
            if (typeResult.get("status")?.takeIf { it.isString }?.stringValue() != "SUCCEEDED") return@mapNotNull null
            val items = typeResult.path("items").takeIf { it.isArray }?.toList().orEmpty()
            type to items
        }.toMap()
    }

    private fun selectionsJson(selections: List<StoredSelection>): String = objectMapper.writeValueAsString(selections.map(::selectionSnapshot))

    private fun sameSelections(existingJson: String, expected: List<StoredSelection>): Boolean {
        val existing = runCatching { parseStoredSelections(existingJson) }.getOrNull() ?: return false
        if (existing.size != expected.size) return false
        return existing.sortedBy { it.assetType }.zip(expected.sortedBy { it.assetType }).all { (left, right) ->
            left.assetType == right.assetType &&
                left.providerConfigId == right.providerConfigId &&
                left.model == right.model &&
                (left.promptVersionId <= 0 || left.promptVersionId == right.promptVersionId) &&
                (left.promptKey.isBlank() || left.promptKey == right.promptKey) &&
                (left.promptCategory.isBlank() || left.promptCategory == right.promptCategory) &&
                (left.templateHash.isBlank() || hashMatches(left.templateHash, right.templateHash))
        }
    }

    private fun selectionSnapshot(selection: StoredSelection): Map<String, Any?> = mapOf(
        "asset_type" to selection.assetType,
        "provider_config_id" to selection.providerConfigId,
        "model" to selection.model.trim(),
        "prompt_key" to selection.promptKey,
        "prompt_category" to selection.promptCategory,
        "prompt_version_id" to selection.promptVersionId,
        "template_hash" to selection.templateHash,
        "variables_hash" to selection.variablesHash,
        "output_schema_version" to selection.outputSchemaVersion,
        "selection_source" to selection.selectionSource,
    )

    private fun executionSelections(result: ScriptAnalysisResult, task: ProductionTask): List<StoredSelection> {
        val legacyVersion = (task.inputPayload["prompt_version_id"] as? Number)?.toLong()
        val legacyHash = task.inputPayload["template_hash"]?.toString()
        val legacyKey = task.inputPayload["prompt_key"]?.toString()?.trim().takeIf { !it.isNullOrBlank() } ?: "script_analysis"
        val legacyCategory = task.inputPayload["prompt_category"]?.toString()?.trim().takeIf { !it.isNullOrBlank() }
            ?: TemplateCategory.SCRIPT_GENERAL.name
        val parsed = parseStoredSelections(result.selectionsJson)
        require(parsed.isNotEmpty()) { "解析任务没有资产类型" }
        return parsed.map { selection ->
            if (selection.promptVersionId > 0 && selection.promptKey.isNotBlank() && selection.templateHash.isNotBlank()) return@map selection
            require(legacyVersion != null && legacyVersion > 0 && !legacyHash.isNullOrBlank()) { "历史任务缺少模板快照" }
            selection.copy(
                promptKey = legacyKey,
                promptCategory = legacyCategory,
                promptVersionId = legacyVersion,
                templateHash = legacyHash,
                outputSchemaVersion = "script_analysis.legacy",
                selectionSource = "LEGACY_FALLBACK",
            )
        }
    }

    private fun isResultSuperseded(result: ScriptAnalysisResult): Boolean {
        val snapshot = scriptService.loadExisting(result.projectId, result.episodeId) ?: return true
        return snapshot.version.id != result.scriptVersionId || !hashMatches(contentHashOf(snapshot.version.content), result.contentHash)
    }

    private fun contentHashOf(content: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun hashMatches(left: String, right: String): Boolean = left.trim().equals(right.trim(), ignoreCase = true)

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        childExecutor.shutdown()
        aggregationExecutor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        childExecutor.awaitTermination(5, TimeUnit.SECONDS)
        aggregationExecutor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
