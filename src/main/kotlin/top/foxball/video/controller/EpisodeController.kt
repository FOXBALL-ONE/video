package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
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
import top.foxball.video.entity.jdbc.EpisodeStatus
import top.foxball.video.service.EpisodeService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** 项目剧集导航与项目级角色库接口。 */
class EpisodeController(
    private val episodeService: EpisodeService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/projects/{project_id}/episodes")
    fun listEpisodes(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class EpisodeData(
            val id: Long,
            @param:JsonProperty("episode_no") val episodeNo: Int,
            val title: String,
            val description: String?,
            val status: String,
            @param:JsonProperty("current_stage") val currentStage: String,
            val progress: Int,
            @param:JsonProperty("duration_seconds") val durationSeconds: Int,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(val list: List<EpisodeData>)

        val episodes = episodeService.list(projectId) ?: return responseBuilder.notFound().build()
        val list = episodes.map { EpisodeData(requireNotNull(it.id), it.episodeNo, it.title, it.description, it.status.name, it.currentStage.name, it.progress, it.durationSeconds, it.updatedAt) }
        val rs = Response(list)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/episodes")
    fun createEpisode(
        @PathVariable("project_id") projectId: Long,
        @RequestParam("title") @NotBlank @Size(max = 200) title: String,
        @RequestParam("description", required = false) @Size(max = 1000) description: String?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("episode_no") val episodeNo: Int, val title: String, val status: String)
        val episode = episodeService.create(projectId, title, description) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(episode.id), episode.episodeNo, episode.title, episode.status.name)
        return responseBuilder.created().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/episodes/{episode_id}")
    fun updateEpisode(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @RequestParam("title") @NotBlank @Size(max = 200) title: String,
        @RequestParam("description", required = false) @Size(max = 1000) description: String?,
        @RequestParam("status", defaultValue = "DRAFT") status: EpisodeStatus,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val title: String, val status: String, val progress: Int)
        val episode = episodeService.update(projectId, episodeId, title, description, status) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(episode.id), episode.title, episode.status.name, episode.progress)
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping("/api/projects/{project_id}/episodes/{episode_id}")
    fun deleteEpisode(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)
        if (!episodeService.delete(projectId, episodeId)) return responseBuilder.badRequest().message("项目至少需要保留一集，或剧集不存在").build()
        val rs = Response(episodeId, true)
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/projects/{project_id}/library")
    fun getLibrary(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class CharacterData(val name: String, val description: String?)
        data class Response(@param:JsonProperty("characters") val characters: List<CharacterData>, @param:JsonProperty("asset_summary") val assetSummary: Map<String, Int>)
        val snapshot = episodeService.library(projectId) ?: return responseBuilder.notFound().build()
        val rs = Response(snapshot.characters.map { CharacterData(it.name, it.description) }, snapshot.assetCounts)
        return responseBuilder.ok().data(rs).build()
    }
}
