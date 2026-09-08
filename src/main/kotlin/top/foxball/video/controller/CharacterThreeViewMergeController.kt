package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.service.CharacterThreeViewMergeTaskService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
class CharacterThreeViewMergeController(
    private val service: CharacterThreeViewMergeTaskService,
    private val responseBuilder: ResponseBuilder,
) {
    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/character-three-view-merge-tasks")
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
        )
        val submitted = try {
            service.submit(projectId, userId, episodeId, assetId, providerConfigId, model, idempotencyKey)
        } catch (_: CharacterThreeViewMergeTaskService.QueueFullException) {
            return responseBuilder.tooManyRequests().retryAfter(2).message("三视图合并任务队列已满，请稍后重试").build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val task = submitted.task
        val rs = Response(
            requireNotNull(task.id).toString(), task.status.name, assetId,
            task.inputPayload["prompt_key"]?.toString(),
            (task.inputPayload["prompt_version_id"] as? Number)?.toLong(),
            task.inputPayload["template_hash"]?.toString(),
        )
        return responseBuilder.accepted().data(rs).build()
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/character-three-view-merge-tasks/{task_id}")
    fun detail(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("task_id") taskId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            val status: String,
            val progress: Int,
            val stage: String?,
            @param:JsonProperty("asset_id") val assetId: Long?,
            @param:JsonProperty("merged_file_id") val mergedFileId: String?,
            @param:JsonProperty("error_code") val errorCode: String?,
            @param:JsonProperty("error_message") val errorMessage: String?,
        )
        val task = service.detail(projectId, userId, taskId) ?: return responseBuilder.notFound().build()
        if (task.episodeId != episodeId) return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(task.id).toString(), task.status.name, task.progress, task.stage,
            (task.inputPayload["episode_asset_id"] as? Number)?.toLong(),
            task.inputPayload["merged_file_id"]?.toString(), task.errorCode, task.errorMessage,
        )
        return responseBuilder.ok().data(rs).build()
    }
}
