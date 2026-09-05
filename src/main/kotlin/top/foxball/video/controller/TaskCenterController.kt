package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.service.ScriptAnalysisTaskService
import top.foxball.video.service.TaskCenterService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@Validated
@RestController
/** 项目异步任务的统一查询和取消接口。 */
class TaskCenterController(
    private val taskCenterService: TaskCenterService,
    private val scriptAnalysisTaskService: ScriptAnalysisTaskService,
    private val objectMapper: ObjectMapper,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/tasks")
    fun listMyTasks(
        @RequestParam("project_id", required = false) projectId: Long?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
        @RequestParam("status", required = false) statuses: List<String>?,
        @RequestParam("task_type", required = false) taskTypes: List<String>?,
        @RequestParam("created_from", required = false) createdFrom: LocalDateTime?,
        @RequestParam("created_to", required = false) createdTo: LocalDateTime?,
        @RequestParam("page", defaultValue = "0") @Min(0) page: Int,
        @RequestParam("page_size", defaultValue = "20") @Min(1) @Max(100) pageSize: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class TaskData(
            val id: Long,
            @param:JsonProperty("project_id") val projectId: Long,
            @param:JsonProperty("episode_id") val episodeId: Long?,
            @param:JsonProperty("task_type") val taskType: String,
            val title: String,
            val status: String,
            val progress: Int,
            @param:JsonProperty("progress_indeterminate") val progressIndeterminate: Boolean,
            val stage: String?,
            @param:JsonProperty("model_name") val modelName: String?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("started_at") val startedAt: LocalDateTime?,
            @param:JsonProperty("completed_at") val completedAt: LocalDateTime?,
            @param:JsonProperty("error_code") val errorCode: String?,
            @param:JsonProperty("error_message") val errorMessage: String?,
            @param:JsonProperty("can_cancel") val canCancel: Boolean,
        )
        data class Response(
            val list: List<TaskData>,
            val page: Int,
            @param:JsonProperty("page_size") val pageSize: Int,
            val total: Long,
            val counts: Map<String, Int>,
        )
        val parsedStatuses = mutableSetOf<ProductionTaskStatus>()
        statuses.orEmpty().forEach { value ->
            val parsed = runCatching { ProductionTaskStatus.valueOf(value.trim().uppercase()) }.getOrNull()
                ?: return responseBuilder.badRequest().message("不支持的任务状态：$value").build()
            parsedStatuses += parsed
        }
        val parsedTypes = mutableSetOf<ProductionTaskType>()
        taskTypes.orEmpty().forEach { value ->
            val parsed = runCatching { ProductionTaskType.valueOf(value.trim().uppercase()) }.getOrNull()
                ?: return responseBuilder.badRequest().message("不支持的任务类型：$value").build()
            parsedTypes += parsed
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            return responseBuilder.badRequest().message("created_from 不能晚于 created_to").build()
        }
        val snapshot = taskCenterService.listMine(
            userId,
            TaskCenterService.Filter(episodeId, parsedStatuses, parsedTypes, createdFrom, createdTo, page, pageSize, projectId),
        ) ?: return responseBuilder.notFound().build()
        val list = snapshot.tasks.map { task ->
            TaskData(
                requireNotNull(task.id), task.projectId, task.episodeId, task.taskType.name,
                task.title ?: task.taskType.name, task.status.name, task.progress, task.progressIndeterminate,
                task.stage, task.modelName, task.createdAt, task.startedAt, task.completedAt,
                task.errorCode, task.errorMessage,
                taskCenterService.canCancel(task, userId),
            )
        }
        return responseBuilder.ok().data(Response(list, snapshot.page, snapshot.pageSize, snapshot.total, snapshot.counts)).build()
    }

    @GetMapping("/api/projects/{project_id}/tasks")
    fun listTasks(
        @PathVariable("project_id") projectId: Long,
        @RequestParam("episode_id", required = false) episodeId: Long?,
        @RequestParam("status", required = false) statuses: List<String>?,
        @RequestParam("task_type", required = false) taskTypes: List<String>?,
        @RequestParam("created_from", required = false) createdFrom: LocalDateTime?,
        @RequestParam("created_to", required = false) createdTo: LocalDateTime?,
        @RequestParam("page", defaultValue = "0") @Min(0) page: Int,
        @RequestParam("page_size", defaultValue = "20") @Min(1) @Max(100) pageSize: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class TaskData(
            val id: Long,
            @param:JsonProperty("project_id") val projectId: Long,
            @param:JsonProperty("episode_id") val episodeId: Long?,
            @param:JsonProperty("task_type") val taskType: String,
            val title: String,
            val status: String,
            val progress: Int,
            @param:JsonProperty("progress_indeterminate") val progressIndeterminate: Boolean,
            val stage: String?,
            @param:JsonProperty("model_name") val modelName: String?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("started_at") val startedAt: LocalDateTime?,
            @param:JsonProperty("completed_at") val completedAt: LocalDateTime?,
            @param:JsonProperty("error_code") val errorCode: String?,
            @param:JsonProperty("error_message") val errorMessage: String?,
            @param:JsonProperty("can_cancel") val canCancel: Boolean,
        )
        data class Response(
            val list: List<TaskData>,
            val page: Int,
            @param:JsonProperty("page_size") val pageSize: Int,
            val total: Long,
            val counts: Map<String, Int>,
        )

        val parsedStatuses = mutableSetOf<ProductionTaskStatus>()
        statuses.orEmpty().forEach { value ->
            val parsed = runCatching { ProductionTaskStatus.valueOf(value.trim().uppercase()) }.getOrNull()
                ?: return responseBuilder.badRequest().message("不支持的任务状态：$value").build()
            parsedStatuses += parsed
        }
        val parsedTypes = mutableSetOf<ProductionTaskType>()
        taskTypes.orEmpty().forEach { value ->
            val parsed = runCatching { ProductionTaskType.valueOf(value.trim().uppercase()) }.getOrNull()
                ?: return responseBuilder.badRequest().message("不支持的任务类型：$value").build()
            parsedTypes += parsed
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            return responseBuilder.badRequest().message("created_from 不能晚于 created_to").build()
        }
        val snapshot = taskCenterService.list(
            projectId,
            userId,
            TaskCenterService.Filter(episodeId, parsedStatuses, parsedTypes, createdFrom, createdTo, page, pageSize),
        ) ?: return responseBuilder.notFound().build()
        val list = snapshot.tasks.map { task ->
            TaskData(
                requireNotNull(task.id), task.projectId, task.episodeId, task.taskType.name,
                task.title ?: task.taskType.name, task.status.name, task.progress, task.progressIndeterminate,
                task.stage, task.modelName, task.createdAt, task.startedAt, task.completedAt,
                task.errorCode, task.errorMessage,
                taskCenterService.canCancel(task, userId),
            )
        }
        val rs = Response(list, snapshot.page, snapshot.pageSize, snapshot.total, snapshot.counts)
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/projects/{project_id}/tasks/{task_id}")
    fun getTask(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("task_id") taskId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class EventData(
            val id: Long,
            @param:JsonProperty("event_type") val eventType: String,
            @param:JsonProperty("from_status") val fromStatus: String?,
            @param:JsonProperty("to_status") val toStatus: String?,
            val stage: String?,
            val progress: Int?,
            @param:JsonProperty("message_code") val messageCode: String?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
        )
        data class AnalysisTypeData(
            val type: String,
            val status: String,
            val progress: Int,
            @param:JsonProperty("progress_indeterminate") val progressIndeterminate: Boolean,
            val count: Int,
            val provider: String?,
            val model: String?,
            @param:JsonProperty("request_id") val requestId: String?,
            val error: String?,
        )
        data class AnalysisProgressData(
            val progress: Int,
            @param:JsonProperty("progress_indeterminate") val progressIndeterminate: Boolean,
            @param:JsonProperty("current_type") val currentType: String?,
            @param:JsonProperty("result_status") val resultStatus: String?,
            val stream: Boolean,
            val total: Int,
            val completed: Int,
            val failed: Int,
            val running: Int,
            val pending: Int,
            val types: List<AnalysisTypeData>,
        )
        data class TaskData(
            val id: Long,
            @param:JsonProperty("project_id") val projectId: Long,
            @param:JsonProperty("episode_id") val episodeId: Long?,
            @param:JsonProperty("project_version_id") val projectVersionId: Long?,
            @param:JsonProperty("task_type") val taskType: String,
            val title: String,
            val status: String,
            val progress: Int,
            @param:JsonProperty("progress_indeterminate") val progressIndeterminate: Boolean,
            val stage: String?,
            @param:JsonProperty("provider_config_id") val providerConfigId: Long?,
            @param:JsonProperty("model_name") val modelName: String?,
            @param:JsonProperty("input_summary") val inputSummary: String?,
            @param:JsonProperty("output_file_ids") val outputFileIds: List<Long>,
            @param:JsonProperty("error_code") val errorCode: String?,
            @param:JsonProperty("error_message") val errorMessage: String?,
            val attempt: Int,
            @param:JsonProperty("max_attempts") val maxAttempts: Int,
            @param:JsonProperty("parent_task_id") val parentTaskId: Long?,
            @param:JsonProperty("cancel_requested_at") val cancelRequestedAt: LocalDateTime?,
            @param:JsonProperty("cancelled_by") val cancelledBy: Long?,
            @param:JsonProperty("cancel_reason") val cancelReason: String?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("queued_at") val queuedAt: LocalDateTime?,
            @param:JsonProperty("started_at") val startedAt: LocalDateTime?,
            @param:JsonProperty("completed_at") val completedAt: LocalDateTime?,
            @param:JsonProperty("can_cancel") val canCancel: Boolean,
            val events: List<EventData>,
            @param:JsonProperty("analysis_progress") val analysisProgress: AnalysisProgressData?,
        )

        val snapshot = taskCenterService.detail(projectId, taskId, userId) ?: return responseBuilder.notFound().build()
        val task = snapshot.task
        val analysisProgress = if (task.taskType == ProductionTaskType.SCRIPT_ANALYSIS) {
            val analysis = scriptAnalysisTaskService.detail(projectId, userId, taskId)
            val typeResultsNode = analysis?.let { runCatching { objectMapper.readTree(it.result.typeResultsJson) }.getOrNull() }
            val assetTypes = (task.inputPayload["asset_types"] as? Collection<*>)?.mapNotNull { it?.toString() }.orEmpty()
            val typeResults = assetTypes.map { type ->
                val node = typeResultsNode?.get(type)
                val storedStatus = node?.path("status")?.takeIf { it.isTextual }?.stringValue()?.takeIf { it.isNotBlank() } ?: "PENDING"
                val status = if (storedStatus == "PENDING" && task.stage?.removePrefix("ANALYZE_") == type && task.status == ProductionTaskStatus.RUNNING) "RUNNING" else storedStatus
                val terminal = status == "SUCCEEDED" || status == "FAILED"
                AnalysisTypeData(
                    type = type,
                    status = status,
                    progress = if (terminal) 100 else 0,
                    progressIndeterminate = status == "RUNNING",
                    count = node?.path("items")?.takeIf { it.isArray }?.size() ?: 0,
                    provider = node?.path("provider")?.takeIf { it.isTextual }?.stringValue(),
                    model = node?.path("model")?.takeIf { it.isTextual }?.stringValue(),
                    requestId = node?.path("requestId")?.takeIf { it.isTextual }?.stringValue()
                        ?: node?.path("request_id")?.takeIf { it.isTextual }?.stringValue(),
                    error = node?.path("errorMessage")?.takeIf { it.isTextual }?.stringValue()
                        ?: node?.path("error_message")?.takeIf { it.isTextual }?.stringValue(),
                )
            }
            val currentType = typeResults.firstOrNull { it.status == "RUNNING" }?.type
                ?: task.stage?.removePrefix("ANALYZE_")?.takeIf { it != task.stage && task.status == ProductionTaskStatus.RUNNING && task.progressIndeterminate }
            AnalysisProgressData(
                progress = task.progress,
                progressIndeterminate = task.progressIndeterminate || typeResults.any { it.progressIndeterminate },
                currentType = currentType,
                resultStatus = analysis?.resultStatus?.name,
                stream = when (val value = task.inputPayload["stream"]) {
                    is Boolean -> value
                    else -> value?.toString()?.toBooleanStrictOrNull() ?: true
                },
                total = typeResults.size,
                completed = typeResults.count { it.status == "SUCCEEDED" },
                failed = typeResults.count { it.status == "FAILED" },
                running = typeResults.count { it.status == "RUNNING" },
                pending = typeResults.count { it.status == "PENDING" },
                types = typeResults,
            )
        } else null
        val rs = TaskData(
            requireNotNull(task.id), task.projectId, task.episodeId, task.projectVersionId, task.taskType.name,
            task.title ?: task.taskType.name, task.status.name, task.progress, task.progressIndeterminate,
            task.stage, task.providerConfigId, task.modelName, task.inputPayload["summary"] as? String,
            listOfNotNull(task.outputFileId), task.errorCode, task.errorMessage, task.attempt, task.maxAttempts,
            task.parentTaskId, task.cancelRequestedAt, task.cancelledBy, task.cancelReason, task.createdAt,
            task.queuedAt, task.startedAt, task.completedAt,
            taskCenterService.canCancel(task, userId),
            snapshot.events.map { event ->
                EventData(requireNotNull(event.id), event.eventType, event.fromStatus?.name, event.toStatus?.name, event.stage, event.progress, event.messageCode, event.createdAt)
            },
            analysisProgress,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/tasks/{task_id}/cancel")
    fun cancelTask(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("task_id") taskId: Long,
        @RequestParam("reason", required = false) @Size(max = 200) reason: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val status: String,
            @param:JsonProperty("cancel_requested_at") val cancelRequestedAt: LocalDateTime?,
            @param:JsonProperty("can_cancel") val canCancel: Boolean,
        )
        idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        val task = taskCenterService.cancel(projectId, taskId, userId, reason) ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(task.id), task.status.name, task.cancelRequestedAt,
            taskCenterService.canCancel(task, userId),
        )
        return responseBuilder.ok().data(rs).build()
    }
}
