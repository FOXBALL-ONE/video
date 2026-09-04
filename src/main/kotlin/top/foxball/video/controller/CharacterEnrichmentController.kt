package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import top.foxball.video.service.CharacterEnrichmentTaskService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
/** 角色内容丰富任务接口；任务结果必须由用户明确采纳后才更新角色资产。 */
class CharacterEnrichmentController(
    private val service: CharacterEnrichmentTaskService,
    private val responseBuilder: ResponseBuilder,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/character-enrichment-tasks")
    fun submit(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("asset_id") assetId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestHeader("Idempotency-Key", required = false) @Size(max = 200) idempotencyKey: String?,
        @RequestParam("provider_config_id") @Min(1) providerConfigId: Long,
        @RequestParam("model") @NotBlank @Size(max = 200) model: String,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            val status: String,
            @param:JsonProperty("asset_id") val assetId: Long,
            @param:JsonProperty("prompt_key") val promptKey: String?,
            @param:JsonProperty("prompt_version_id") val promptVersionId: Long?,
            @param:JsonProperty("template_hash") val templateHash: String?,
            @param:JsonProperty("correlation_id") val correlationId: String?,
        )
        val submitted = try {
            service.submit(projectId, userId, episodeId, assetId, providerConfigId, model, idempotencyKey)
        } catch (_: CharacterEnrichmentTaskService.QueueFullException) {
            return responseBuilder.tooManyRequests().retryAfter(2).message("角色扩写任务队列已满，请稍后重试").build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val task = submitted.task
        val rs = Response(requireNotNull(task.id).toString(), task.status.name, assetId, task.inputPayload["prompt_key"]?.toString(), (task.inputPayload["prompt_version_id"] as? Number)?.toLong(), task.inputPayload["template_hash"]?.toString(), task.correlationId)
        return responseBuilder.accepted().data(rs).build()
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/character-enrichment-tasks/{task_id}")
    fun detail(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("task_id") taskId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            @param:JsonProperty("task_status") val taskStatus: String,
            @param:JsonProperty("result_status") val resultStatus: String,
            @param:JsonProperty("asset_id") val assetId: Long,
            @param:JsonProperty("script_version_id") val scriptVersionId: Long?,
            @param:JsonProperty("content_hash") val contentHash: String?,
            @param:JsonProperty("prompt_version_id") val promptVersionId: Long,
            @param:JsonProperty("result_hash") val resultHash: String?,
            val result: Any?,
            @param:JsonProperty("error_code") val errorCode: String?,
            @param:JsonProperty("error_message") val errorMessage: String?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("completed_at") val completedAt: LocalDateTime?,
            @param:JsonProperty("adopted_at") val adoptedAt: LocalDateTime?,
        )
        val detail = service.detail(projectId, userId, taskId) ?: return responseBuilder.notFound().build()
        if (detail.result.episodeId != episodeId) return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(detail.task.id).toString(), detail.task.status.name, detail.result.resultStatus.name, detail.result.episodeAssetId,
            detail.result.scriptVersionId, detail.result.contentHash, detail.result.promptVersionId, detail.result.resultHash,
            detail.result.resultJson?.let { value -> runCatching { objectMapper.readTree(value) }.getOrNull() }, detail.result.errorCode, detail.result.errorMessage,
            detail.task.createdAt, detail.task.completedAt, detail.result.adoptedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/character-enrichment-tasks/{task_id}/adopt")
    fun adopt(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("task_id") taskId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestHeader("Idempotency-Key", required = false) @Size(max = 200) idempotencyKey: String?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            @param:JsonProperty("task_status") val taskStatus: String,
            @param:JsonProperty("adoption_status") val adoptionStatus: String,
            @param:JsonProperty("asset_id") val assetId: Long,
            @param:JsonProperty("adopted_at") val adoptedAt: LocalDateTime?,
        )
        val adopted = try {
            service.adopt(projectId, userId, taskId, idempotencyKey)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } catch (exception: IllegalStateException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        if (adopted.result.episodeId != episodeId) return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(adopted.task.id).toString(), adopted.task.status.name, adopted.result.resultStatus.name, adopted.assetId, adopted.result.adoptedAt)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/character-enrichment-tasks/{task_id}/discard")
    fun discard(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("task_id") taskId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            @param:JsonProperty("task_status") val taskStatus: String,
            @param:JsonProperty("result_status") val resultStatus: String,
            @param:JsonProperty("asset_id") val assetId: Long,
            @param:JsonProperty("discarded_at") val discardedAt: LocalDateTime?,
        )
        val discarded = try {
            service.discard(projectId, userId, taskId)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        if (discarded.result.episodeId != episodeId) return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(discarded.task.id).toString(), discarded.task.status.name, discarded.result.resultStatus.name, discarded.result.episodeAssetId, discarded.result.discardedAt)
        return responseBuilder.ok().data(rs).build()
    }
}
