package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ScriptAnalysisResultStatus
import top.foxball.video.repository.ProductionTaskRepository
import top.foxball.video.repository.ScriptAnalysisResultRepository
import java.time.LocalDateTime

/**
 * 将同一剧本解析父任务的子任务状态和结果串行化写入同一份 JSON 聚合结果。
 * AI 网络调用始终在本服务事务之外执行。
 */
@Service
class ScriptAnalysisResultWriter(
    private val taskRepository: ProductionTaskRepository,
    private val resultRepository: ScriptAnalysisResultRepository,
    private val objectMapper: ObjectMapper,
) {
    data class Aggregation(
        val status: Status,
        val stage: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    ) {
        enum class Status { PENDING, CANCELLED, SUCCEEDED, FAILED, TERMINAL }
    }

    @Transactional
    fun initialize(taskId: Long, items: Map<String, Map<String, Any?>>): Boolean {
        val task = taskRepository.findWithLockById(taskId) ?: return false
        if (task.status != ProductionTaskStatus.RUNNING) return false
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return false
        val root = readObject(result.typeResultsJson)
        items.forEach { (assetType, item) ->
            if (root.path(assetType).isMissingNode) root.set(assetType, objectMapper.valueToTree(item))
        }
        result.typeResultsJson = objectMapper.writeValueAsString(root)
        resultRepository.save(result)
        return true
    }

    /** 返回 false 表示父任务已经结束或该资产类型已经处于终态。 */
    @Transactional
    fun updateTypeResult(taskId: Long, assetType: String, values: Map<String, Any?>): Boolean {
        val task = taskRepository.findWithLockById(taskId) ?: return false
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return false
        val root = readObject(result.typeResultsJson)
        val current = root.path(assetType).path("status").textValue()
        if (current in TERMINAL_STATUSES) return false
        val next = values.toMutableMap()
        val requestedStatus = next["status"]?.toString()
        val merged = if (root.path(assetType).isObject) {
            (root.path(assetType) as ObjectNode).deepCopy()
        } else {
            objectMapper.createObjectNode()
        }
        if (task.status in setOf(ProductionTaskStatus.CANCEL_REQUESTED, ProductionTaskStatus.CANCELLED) && requestedStatus != "CANCELLED") {
            next["status"] = "CANCELLED"
            next["stage"] = "CANCELLED"
            next["progress"] = 100
            next["errorCode"] = "TASK_CANCELLED"
            next["errorMessage"] = "任务已取消"
            next["completedAt"] = LocalDateTime.now()
        }
        next.forEach { (key, value) -> merged.set(key, objectMapper.valueToTree(value)) }
        root.set(assetType, merged)
        result.typeResultsJson = objectMapper.writeValueAsString(root)
        resultRepository.save(result)
        return true
    }

    @Transactional
    fun cancelUnfinished(taskId: Long, assetTypes: Collection<String>) {
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return
        val root = readObject(result.typeResultsJson)
        var changed = false
        assetTypes.forEach { assetType ->
            val item = root.path(assetType)
            val status = item.path("status").textValue()
            if (status !in TERMINAL_STATUSES) {
                val cancelled = if (item.isObject) (item as ObjectNode).deepCopy() else objectMapper.createObjectNode()
                cancelled.put("status", "CANCELLED")
                cancelled.put("stage", "CANCELLED")
                cancelled.put("progress", 100)
                cancelled.put("errorCode", "TASK_CANCELLED")
                cancelled.put("errorMessage", "任务已取消")
                cancelled.put("completedAt", LocalDateTime.now().toString())
                root.set(assetType, cancelled)
                changed = true
            }
        }
        if (changed) {
            result.typeResultsJson = objectMapper.writeValueAsString(root)
            resultRepository.save(result)
        }
    }

    @Transactional(readOnly = true)
    fun progress(taskId: Long, assetTypes: Collection<String>): Int? {
        val result = resultRepository.findByTaskId(taskId) ?: return null
        if (assetTypes.isEmpty()) return 0
        val root = readObject(result.typeResultsJson)
        return assetTypes.sumOf { root.path(it).path("progress").intValue().coerceIn(0, 100) } / assetTypes.size
    }

    /**
     * 只有所有子任务终态后才汇聚。父任务取消时保留诊断子结果，但不生成可采纳的结果。
     */
    @Transactional
    fun aggregate(taskId: Long, assetTypes: Collection<String>): Aggregation {
        val task = taskRepository.findWithLockById(taskId) ?: return Aggregation(Aggregation.Status.TERMINAL)
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return Aggregation(Aggregation.Status.TERMINAL)
        if (task.status in setOf(ProductionTaskStatus.CANCEL_REQUESTED, ProductionTaskStatus.CANCELLED)) {
            cancelUnfinishedLocked(result, assetTypes)
            return Aggregation(Aggregation.Status.CANCELLED)
        }
        if (task.status in setOf(ProductionTaskStatus.SUCCEEDED, ProductionTaskStatus.FAILED)) return Aggregation(Aggregation.Status.TERMINAL)

        val root = readObject(result.typeResultsJson)
        val statuses = assetTypes.associateWith { root.path(it).path("status").textValue() ?: "PENDING" }
        if (statuses.values.any { it !in TERMINAL_STATUSES }) return Aggregation(Aggregation.Status.PENDING)

        val successful = assetTypes.filter { statuses[it] == "SUCCEEDED" }
        if (successful.isEmpty()) {
            val failure = assetTypes.asSequence().map { root.path(it) }
                .firstOrNull { it.path("errorCode").textValue() != null || it.path("error_code").textValue() != null }
            return Aggregation(
                Aggregation.Status.FAILED,
                errorCode = failure?.path("errorCode")?.textValue() ?: failure?.path("error_code")?.textValue() ?: "SCRIPT_ANALYSIS_FAILED",
                errorMessage = failure?.path("errorMessage")?.textValue() ?: failure?.path("error_message")?.textValue() ?: "所有资产类型解析失败",
            )
        }

        val aggregated = objectMapper.createObjectNode()
        listOf("characters", "scenes", "props").forEach { aggregated.set(it, objectMapper.createArrayNode()) }
        successful.sorted().forEach { assetType ->
            val field = RESULT_FIELDS[assetType] ?: return@forEach
            val target = aggregated.withArray(field)
            root.path(assetType).path("items").takeIf { it.isArray }?.forEach(target::add)
        }
        result.resultJson = objectMapper.writeValueAsString(aggregated)
        result.resultStatus = ScriptAnalysisResultStatus.STAGED
        resultRepository.save(result)
        return Aggregation(
            Aggregation.Status.SUCCEEDED,
            if (successful.size == assetTypes.size) "RESULT_STAGED" else "PARTIAL_RESULT_STAGED",
        )
    }

    private fun cancelUnfinishedLocked(result: top.foxball.video.entity.jdbc.ScriptAnalysisResult, assetTypes: Collection<String>) {
        val root = readObject(result.typeResultsJson)
        var changed = false
        assetTypes.forEach { assetType ->
            val item = root.path(assetType)
            if (item.path("status").textValue() !in TERMINAL_STATUSES) {
                val cancelled = if (item.isObject) (item as ObjectNode).deepCopy() else objectMapper.createObjectNode()
                cancelled.put("status", "CANCELLED")
                cancelled.put("stage", "CANCELLED")
                cancelled.put("progress", 100)
                cancelled.put("errorCode", "TASK_CANCELLED")
                cancelled.put("errorMessage", "任务已取消")
                cancelled.put("completedAt", LocalDateTime.now().toString())
                root.set(assetType, cancelled)
                changed = true
            }
        }
        if (changed) {
            result.typeResultsJson = objectMapper.writeValueAsString(root)
            resultRepository.save(result)
        }
    }

    private fun readObject(json: String): ObjectNode = runCatching { objectMapper.readTree(json) as? ObjectNode }
        .getOrNull() ?: objectMapper.createObjectNode()

    private companion object {
        val TERMINAL_STATUSES = setOf("SUCCEEDED", "FAILED", "CANCELLED")
        val RESULT_FIELDS = mapOf(
            "CHARACTER" to "characters",
            "SCENE_BACKGROUND" to "scenes",
            "PROP" to "props",
        )
    }
}
