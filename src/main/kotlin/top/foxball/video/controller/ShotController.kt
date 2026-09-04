package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
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
    @GetMapping(value = ["/api/projects/{project_id}/shots", "/api/projects/{project_id}/episodes/{episode_id}/shots"])
    fun listShots(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class ShotData(
            val id: Long,
            @param:JsonProperty("episode_id") val episodeId: Long?,
            @param:JsonProperty("sequence_no") val sequenceNo: Int,
            val title: String,
            val location: String?,
            @param:JsonProperty("time_of_day") val timeOfDay: String?,
            @param:JsonProperty("interior_exterior") val interiorExterior: String?,
            @param:JsonProperty("shot_size") val shotSize: String?,
            @param:JsonProperty("camera_movement") val cameraMovement: String?,
            @param:JsonProperty("camera_angle") val cameraAngle: String?,
            @param:JsonProperty("focal_length") val focalLength: String?,
            val composition: String?,
            @param:JsonProperty("visual_description") val visualDescription: String?,
            val action: String?,
            val dialogue: String?,
            @param:JsonProperty("dialogue_type") val dialogueType: String?,
            @param:JsonProperty("sound_effects") val soundEffects: String?,
            val characters: String?,
            val props: String?,
            val prompt: String?,
            @param:JsonProperty("negative_prompt") val negativePrompt: String?,
            val atmosphere: String?,
            @param:JsonProperty("narrative_purpose") val narrativePurpose: String?,
            @param:JsonProperty("duration_seconds") val durationSeconds: Double,
            val status: String,
            @param:JsonProperty("shot_group_id") val shotGroupId: Long?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class IssueData(val id: Long, @param:JsonProperty("from_shot_id") val fromShotId: Long, @param:JsonProperty("to_shot_id") val toShotId: Long, @param:JsonProperty("issue_type") val issueType: String, val severity: String, val description: String, val suggestion: String?, val resolved: Boolean)
        data class GroupData(val id: Long, val name: String, @param:JsonProperty("scene_label") val sceneLabel: String?, @param:JsonProperty("sort_order") val sortOrder: Int)
        data class Response(val list: List<ShotData>, @param:JsonProperty("continuity_issues") val continuityIssues: List<IssueData>, val groups: List<GroupData>)

        val effectiveEpisodeId = pathEpisodeId ?: episodeId
        val snapshot = shotService.load(projectId, effectiveEpisodeId) ?: return responseBuilder.notFound().build()
        val list = snapshot.shots.map {
            ShotData(
                requireNotNull(it.id), it.episodeId, it.sequenceNo, it.title, it.location, it.timeOfDay, it.interiorExterior,
                it.shotSize, it.cameraMovement, it.cameraAngle, it.focalLength, it.composition, it.visualDescription, it.action,
                it.dialogue, it.dialogueType, it.soundEffects, it.characterNames, it.propNames, it.prompt, it.negativePrompt, it.atmosphere,
                it.narrativePurpose, it.durationSeconds, it.status.name, it.shotGroupId, it.updatedAt,
            )
        }
        val issues = snapshot.issues.map { IssueData(requireNotNull(it.id), it.fromShotId, it.toShotId, it.issueType.name, it.severity.name, it.description, it.suggestion, it.resolved) }
        val groups = snapshot.groups.map { GroupData(requireNotNull(it.id), it.name, it.sceneLabel, it.sortOrder) }
        val rs = Response(list, issues, groups)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping(value = ["/api/projects/{project_id}/shots", "/api/projects/{project_id}/episodes/{episode_id}/shots"])
    fun createShot(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @RequestParam("title") @NotBlank @Size(max = 200) title: String,
        @RequestParam("location", required = false) @Size(max = 200) location: String?,
        @RequestParam("time_of_day", required = false) @Size(max = 50) timeOfDay: String?,
        @RequestParam("interior_exterior", required = false) @Size(max = 20) interiorExterior: String?,
        @RequestParam("shot_size", required = false) @Size(max = 50) shotSize: String?,
        @RequestParam("camera_movement", required = false) cameraMovement: String?,
        @RequestParam("camera_angle", required = false) cameraAngle: String?,
        @RequestParam("focal_length", required = false) @Size(max = 30) focalLength: String?,
        @RequestParam("composition", required = false) composition: String?,
        @RequestParam("visual_description", required = false) visualDescription: String?,
        @RequestParam("action", required = false) action: String?,
        @RequestParam("dialogue", required = false) dialogue: String?,
        @RequestParam("characters", required = false) characters: String?,
        @RequestParam("props", required = false) props: String?,
        @RequestParam("prompt", required = false) prompt: String?,
        @RequestParam("negative_prompt", required = false) negativePrompt: String?,
        @RequestParam("atmosphere", required = false) atmosphere: String?,
        @RequestParam("narrative_purpose", required = false) narrativePurpose: String?,
        @RequestParam("duration_seconds", defaultValue = "0") @PositiveOrZero durationSeconds: Double,
        @RequestParam("status", defaultValue = "DRAFT") status: ShotStatus,
        @RequestParam("episode_id", required = false) episodeId: Long?,
        @RequestParam("shot_group_id", required = false) shotGroupId: Long?,
        @RequestParam("dialogue_type", required = false) @Size(max = 20) dialogueType: String?,
        @RequestParam("sound_effects", required = false) soundEffects: String?,
        @RequestParam("after_sequence", required = false) @PositiveOrZero afterSequence: Int?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("sequence_no") val sequenceNo: Int, val title: String, val status: String)
        val shot = shotService.create(
            projectId, title.trim(), location, timeOfDay, interiorExterior, shotSize, cameraMovement, action,
            dialogue, prompt, durationSeconds, status, pathEpisodeId ?: episodeId, focalLength, composition, visualDescription,
            characters, props, negativePrompt, atmosphere, narrativePurpose, cameraAngle,
            shotGroupId, dialogueType, soundEffects, afterSequence,
        ) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(shot.id), shot.sequenceNo, shot.title, shot.status.name)
        return responseBuilder.created().data(rs).build()
    }

    @PutMapping(value = ["/api/projects/{project_id}/shots/{shot_id}", "/api/projects/{project_id}/episodes/{episode_id}/shots/{shot_id}"])
    fun updateShot(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @PathVariable("shot_id") shotId: Long,
        @RequestParam("title") @NotBlank @Size(max = 200) title: String,
        @RequestParam("location", required = false) @Size(max = 200) location: String?,
        @RequestParam("time_of_day", required = false) @Size(max = 50) timeOfDay: String?,
        @RequestParam("interior_exterior", required = false) @Size(max = 20) interiorExterior: String?,
        @RequestParam("shot_size", required = false) @Size(max = 50) shotSize: String?,
        @RequestParam("camera_movement", required = false) cameraMovement: String?,
        @RequestParam("camera_angle", required = false) cameraAngle: String?,
        @RequestParam("focal_length", required = false) @Size(max = 30) focalLength: String?,
        @RequestParam("composition", required = false) composition: String?,
        @RequestParam("visual_description", required = false) visualDescription: String?,
        @RequestParam("action", required = false) action: String?,
        @RequestParam("dialogue", required = false) dialogue: String?,
        @RequestParam("characters", required = false) characters: String?,
        @RequestParam("props", required = false) props: String?,
        @RequestParam("prompt", required = false) prompt: String?,
        @RequestParam("negative_prompt", required = false) negativePrompt: String?,
        @RequestParam("atmosphere", required = false) atmosphere: String?,
        @RequestParam("narrative_purpose", required = false) narrativePurpose: String?,
        @RequestParam("duration_seconds", defaultValue = "0") @PositiveOrZero durationSeconds: Double,
        @RequestParam("status") status: ShotStatus,
        @RequestParam("episode_id", required = false) episodeId: Long?,
        @RequestParam("shot_group_id", required = false) shotGroupId: Long?,
        @RequestParam("dialogue_type", required = false) @Size(max = 20) dialogueType: String?,
        @RequestParam("sound_effects", required = false) soundEffects: String?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("sequence_no") val sequenceNo: Int, val title: String, val status: String)
        val shot = shotService.update(
            projectId, shotId, title.trim(), location, timeOfDay, interiorExterior, shotSize, cameraMovement,
            action, dialogue, prompt, durationSeconds, status, pathEpisodeId ?: episodeId, focalLength, composition,
            visualDescription, characters, props, negativePrompt, atmosphere, narrativePurpose, cameraAngle,
            shotGroupId, dialogueType, soundEffects,
        ) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(shot.id), shot.sequenceNo, shot.title, shot.status.name)
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping(value = ["/api/projects/{project_id}/shots/{shot_id}", "/api/projects/{project_id}/episodes/{episode_id}/shots/{shot_id}"])
    fun deleteShot(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @PathVariable("shot_id") shotId: Long,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)
        if (!shotService.delete(projectId, shotId, pathEpisodeId ?: episodeId)) return responseBuilder.notFound().build()
        val rs = Response(shotId, true)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping(value = ["/api/projects/{project_id}/shots/continuity/resolve", "/api/projects/{project_id}/episodes/{episode_id}/shots/continuity/resolve"])
    fun resolveIssues(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class Response(val resolved: Int)
        val effectiveEpisodeId = pathEpisodeId ?: episodeId
        if (shotService.load(projectId, effectiveEpisodeId) == null) return responseBuilder.notFound().build()
        val rs = Response(shotService.resolveIssues(projectId, effectiveEpisodeId))
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping(value = ["/api/projects/{project_id}/shots/groups", "/api/projects/{project_id}/episodes/{episode_id}/shots/groups"])
    fun createGroup(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @RequestParam("name", required = false) @Size(max = 100) name: String?,
        @RequestParam("scene_label", required = false) @Size(max = 300) sceneLabel: String?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, @param:JsonProperty("scene_label") val sceneLabel: String?, @param:JsonProperty("sort_order") val sortOrder: Int)
        val group = shotService.createGroup(projectId, name, sceneLabel, pathEpisodeId ?: episodeId) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(group.id), group.name, group.sceneLabel, group.sortOrder)
        return responseBuilder.created().data(rs).build()
    }

    @PutMapping(value = ["/api/projects/{project_id}/shots/groups/{group_id}", "/api/projects/{project_id}/episodes/{episode_id}/shots/groups/{group_id}"])
    fun updateGroup(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("group_id") groupId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @RequestParam("name") @NotBlank @Size(max = 100) name: String,
        @RequestParam("scene_label", required = false) @Size(max = 300) sceneLabel: String?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, @param:JsonProperty("scene_label") val sceneLabel: String?, @param:JsonProperty("sort_order") val sortOrder: Int)
        val group = shotService.updateGroup(projectId, groupId, name, sceneLabel, pathEpisodeId ?: episodeId) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(group.id), group.name, group.sceneLabel, group.sortOrder)
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping(value = ["/api/projects/{project_id}/shots/groups/{group_id}", "/api/projects/{project_id}/episodes/{episode_id}/shots/groups/{group_id}"])
    fun deleteGroup(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("group_id") groupId: Long,
        @PathVariable("episode_id", required = false) pathEpisodeId: Long?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)
        if (!shotService.deleteGroup(projectId, groupId, pathEpisodeId ?: episodeId)) return responseBuilder.notFound().build()
        val rs = Response(groupId, true)
        return responseBuilder.ok().data(rs).build()
    }
}
