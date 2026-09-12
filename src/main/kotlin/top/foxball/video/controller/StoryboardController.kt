package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import top.foxball.video.service.StoryboardTaskService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
class StoryboardController(private val service: StoryboardTaskService, private val responseBuilder: ResponseBuilder) {
    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/storyboard-tasks")
    fun submit(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestHeader("Idempotency-Key", required = false) @Size(max = 200) key: String?,
        @RequestParam("batch_no") @Min(1) batchNo: Int,
        @RequestParam("shot_ids") shotIds: List<Long>,
        @RequestParam("provider_config_id") @Min(1) providerConfigId: Long,
        @RequestParam("model") @NotBlank @Size(max = 200) model: String,
        @RequestParam("size", defaultValue = "2048x2048") size: String,
        @RequestParam("style_supplement", required = false) @Size(max = 500) style: String?,
        @RequestParam("prompt_provider_config_id", required = false) @Min(1) promptProviderConfigId: Long? = null,
        @RequestParam("prompt_model", required = false) @Size(max = 200) promptModel: String? = null,
    ): ResponseEntity<Response> {
        data class Result(@param:JsonProperty("task_id") val taskId: String, val status: String, @param:JsonProperty("batch_no") val batchNo: Int, @param:JsonProperty("prompt_key") val promptKey: String)
        val submitted = try { service.submit(projectId, userId, episodeId, batchNo, shotIds, providerConfigId, model, size, style, key, promptProviderConfigId, promptModel) } catch (e: IllegalArgumentException) { return responseBuilder.badRequest().message(e.message).build() } catch (_: StoryboardTaskService.QueueFullException) { return responseBuilder.tooManyRequests().retryAfter(2).message("分镜图任务队列已满，请稍后重试").build() } ?: return responseBuilder.notFound().build()
        val task = submitted.task
        return responseBuilder.accepted().data(Result(requireNotNull(task.id).toString(), task.status.name, batchNo, "shot_storyboard_grid")).build()
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/storyboard-tasks/{task_id}")
    fun detail(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long, @PathVariable("task_id") taskId: Long, @AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class Result(@param:JsonProperty("task_id") val taskId: String, val status: String, val progress: Int, val stage: String?, @param:JsonProperty("batch_no") val batchNo: Int?, @param:JsonProperty("grid_file_id") val gridFileId: String?, @param:JsonProperty("signed_grid_url") val signedGridUrl: String?, @param:JsonProperty("error_code") val errorCode: String?, @param:JsonProperty("error_message") val errorMessage: String?)
        val task = service.detail(projectId, userId, episodeId, taskId) ?: return responseBuilder.notFound().build()
        val p = task.inputPayload
        return responseBuilder.ok().data(Result(taskId.toString(), task.status.name, task.progress, task.stage, (p["batch_no"] as? Number)?.toInt(), p["grid_file_id"]?.toString(), p["signed_grid_url"]?.toString(), task.errorCode, task.errorMessage)).build()
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/storyboards")
    fun list(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long, @AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class Item(@param:JsonProperty("batch_no") val batchNo: Int, val status: String, @param:JsonProperty("grid_file_id") val gridFileId: String?, @param:JsonProperty("signed_grid_url") val signedGridUrl: String?, val cells: Any?, @param:JsonProperty("imported_shot_ids") val importedShotIds: List<Long>)
        data class Result(val list: List<Item>)
        val items = service.batches(projectId, userId, episodeId).map { task -> val p = task.inputPayload; Item((p["batch_no"] as Number).toInt(), if (p["imported"] != null) "IMPORTED" else if (task.status.name == "SUCCEEDED") "GENERATED" else task.status.name, p["grid_file_id"]?.toString(), p["signed_grid_url"]?.toString(), p["cells"], (p["imported"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("shot_id")?.toString()?.toLongOrNull() } ?: emptyList()) }.sortedBy { it.batchNo }
        return responseBuilder.ok().data(Result(items)).build()
    }

    @PutMapping("/api/projects/{project_id}/episodes/{episode_id}/storyboards/{batch_no}/cells")
    fun cells(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long, @PathVariable("batch_no") @Min(1) batchNo: Int, @AuthenticationPrincipal userId: Long?, @RequestBody body: Map<String, Any?>): ResponseEntity<Response> {
        data class Result(@param:JsonProperty("batch_no") val batchNo: Int, val saved: Boolean)
        val ok = service.saveCells(projectId, userId, episodeId, batchNo, body["grid_file_id"]?.toString() ?: "", body["cells"] as? List<Map<String, Any?>> ?: emptyList())
        return if (ok) responseBuilder.ok().data(Result(batchNo, true)).build() else responseBuilder.notFound().build()
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/storyboards/{batch_no}/import")
    fun import(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long, @PathVariable("batch_no") @Min(1) batchNo: Int, @AuthenticationPrincipal userId: Long?, @RequestBody(required = false) body: Map<String, Any?>?): ResponseEntity<Response> {
        data class FileData(@param:JsonProperty("shot_id") val shotId: Long, @param:JsonProperty("file_id") val fileId: String)
        data class Result(@param:JsonProperty("batch_no") val batchNo: Int, val imported: Int, val files: List<FileData>)
        val cells = body?.get("cells") as? List<Map<String, Any?>>
        val result = try { service.importCells(projectId, userId, episodeId, batchNo, cells) } catch (e: Exception) { return responseBuilder.badRequest().message(e.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Result(batchNo, result.size, result.map { FileData(it["shot_id"]!!.toLong(), it["file_id"]!!) })).build()
    }
}
