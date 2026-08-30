package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.SubtitleMode
import top.foxball.video.service.VideoWorkspaceService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** 视频成片参数和制作队列接口。 */
class VideoWorkspaceController(
    private val videoService: VideoWorkspaceService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/projects/{project_id}/video")
    fun getVideo(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class SettingsData(val id: Long, @param:JsonProperty("aspect_ratio") val aspectRatio: String, val resolution: String, val style: String, val fps: Int, @param:JsonProperty("subtitle_mode") val subtitleMode: String)
        data class TaskData(val id: Long, @param:JsonProperty("task_type") val taskType: String, val status: String, val progress: Int, @param:JsonProperty("error_message") val errorMessage: String?, @param:JsonProperty("created_at") val createdAt: LocalDateTime?)
        data class Response(val settings: SettingsData, val tasks: List<TaskData>, val counts: Map<String, Int>)
        val snapshot = videoService.load(projectId) ?: return responseBuilder.notFound().build()
        val settings = SettingsData(requireNotNull(snapshot.settings.id), snapshot.settings.aspectRatio, snapshot.settings.resolution, snapshot.settings.style, snapshot.settings.fps, snapshot.settings.subtitleMode.name)
        val tasks = snapshot.tasks.map { TaskData(requireNotNull(it.id), it.taskType.name, it.status.name, it.progress, it.errorMessage, it.createdAt) }
        val counts = tasks.groupingBy { it.taskType }.eachCount()
        val rs = Response(settings, tasks, counts)
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/video/settings")
    fun saveSettings(
        @PathVariable("project_id") projectId: Long,
        @RequestParam("aspect_ratio") @NotBlank @Size(max = 20) aspectRatio: String,
        @RequestParam("resolution") @NotBlank @Size(max = 30) resolution: String,
        @RequestParam("style") @NotBlank @Size(max = 50) style: String,
        @RequestParam("fps") @Min(1) @Max(240) fps: Int,
        @RequestParam("subtitle_mode") subtitleMode: SubtitleMode,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("aspect_ratio") val aspectRatio: String, val resolution: String, val style: String, val fps: Int, @param:JsonProperty("subtitle_mode") val subtitleMode: String)
        val settings = videoService.saveSettings(projectId, aspectRatio, resolution, style, fps, subtitleMode) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(settings.id), settings.aspectRatio, settings.resolution, settings.style, settings.fps, settings.subtitleMode.name)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/video/queue")
    fun queue(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class TaskData(val id: Long, @param:JsonProperty("task_type") val taskType: String, val status: String, val progress: Int)
        data class Response(val list: List<TaskData>)
        val snapshot = videoService.queue(projectId) ?: return responseBuilder.notFound().build()
        val rs = Response(snapshot.tasks.map { TaskData(requireNotNull(it.id), it.taskType.name, it.status.name, it.progress) })
        return responseBuilder.created().data(rs).build()
    }
}
