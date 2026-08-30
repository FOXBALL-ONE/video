package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.ShotStatus
import top.foxball.video.service.ShotService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** 项目分镜及连续性检查接口。 */
class ShotController(
    private val shotService: ShotService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/projects/{project_id}/shots")
    fun listShots(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class ShotData(val id: Long, @param:JsonProperty("sequence_no") val sequenceNo: Int, val title: String, val location: String?, @param:JsonProperty("time_of_day") val timeOfDay: String?, @param:JsonProperty("interior_exterior") val interiorExterior: String?, @param:JsonProperty("shot_size") val shotSize: String?, @param:JsonProperty("camera_movement") val cameraMovement: String?, val action: String?, val dialogue: String?, val prompt: String?, @param:JsonProperty("duration_seconds") val durationSeconds: Double, val status: String)
        data class IssueData(val id: Long, @param:JsonProperty("from_shot_id") val fromShotId: Long, @param:JsonProperty("to_shot_id") val toShotId: Long, @param:JsonProperty("issue_type") val issueType: String, val severity: String, val description: String, val suggestion: String?, val resolved: Boolean)
        data class Response(val list: List<ShotData>, @param:JsonProperty("continuity_issues") val continuityIssues: List<IssueData>)

        val snapshot = shotService.load(projectId) ?: return responseBuilder.notFound().build()
        val list = snapshot.shots.map { ShotData(requireNotNull(it.id), it.sequenceNo, it.title, it.location, it.timeOfDay, it.interiorExterior, it.shotSize, it.cameraMovement, it.action, it.dialogue, it.prompt, it.durationSeconds, it.status.name) }
        val issues = snapshot.issues.map { IssueData(requireNotNull(it.id), it.fromShotId, it.toShotId, it.issueType.name, it.severity.name, it.description, it.suggestion, it.resolved) }
        val rs = Response(list, issues)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/shots")
    fun createShot(
        @PathVariable("project_id") projectId: Long,
        @RequestParam("title") @NotBlank @Size(max = 200) title: String,
        @RequestParam("location", required = false) @Size(max = 200) location: String?,
        @RequestParam("time_of_day", required = false) @Size(max = 50) timeOfDay: String?,
        @RequestParam("interior_exterior", required = false) @Size(max = 20) interiorExterior: String?,
        @RequestParam("shot_size", required = false) @Size(max = 50) shotSize: String?,
        @RequestParam("camera_movement", required = false) cameraMovement: String?,
        @RequestParam("action", required = false) action: String?,
        @RequestParam("dialogue", required = false) dialogue: String?,
        @RequestParam("prompt", required = false) prompt: String?,
        @RequestParam("duration_seconds", defaultValue = "0") @PositiveOrZero durationSeconds: Double,
        @RequestParam("status", defaultValue = "DRAFT") status: ShotStatus,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("sequence_no") val sequenceNo: Int, val title: String, val status: String)
        val shot = shotService.create(projectId, title.trim(), location, timeOfDay, interiorExterior, shotSize, cameraMovement, action, dialogue, prompt, durationSeconds, status) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(shot.id), shot.sequenceNo, shot.title, shot.status.name)
        return responseBuilder.created().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/shots/{shot_id}")
    fun updateShot(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("shot_id") shotId: Long,
        @RequestParam("title") @NotBlank @Size(max = 200) title: String,
        @RequestParam("location", required = false) @Size(max = 200) location: String?,
        @RequestParam("time_of_day", required = false) @Size(max = 50) timeOfDay: String?,
        @RequestParam("interior_exterior", required = false) @Size(max = 20) interiorExterior: String?,
        @RequestParam("shot_size", required = false) @Size(max = 50) shotSize: String?,
        @RequestParam("camera_movement", required = false) cameraMovement: String?,
        @RequestParam("action", required = false) action: String?,
        @RequestParam("dialogue", required = false) dialogue: String?,
        @RequestParam("prompt", required = false) prompt: String?,
        @RequestParam("duration_seconds", defaultValue = "0") @PositiveOrZero durationSeconds: Double,
        @RequestParam("status") status: ShotStatus,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("sequence_no") val sequenceNo: Int, val title: String, val status: String)
        val shot = shotService.update(projectId, shotId, title.trim(), location, timeOfDay, interiorExterior, shotSize, cameraMovement, action, dialogue, prompt, durationSeconds, status) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(shot.id), shot.sequenceNo, shot.title, shot.status.name)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/shots/continuity/resolve")
    fun resolveIssues(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class Response(val resolved: Int)
        if (shotService.load(projectId) == null) return responseBuilder.notFound().build()
        val rs = Response(shotService.resolveIssues(projectId))
        return responseBuilder.ok().data(rs).build()
    }
}
