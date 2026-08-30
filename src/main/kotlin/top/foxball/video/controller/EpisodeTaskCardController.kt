package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.TaskCardStatus
import top.foxball.video.service.EpisodeTaskCardService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** 每集任务卡接口，负责下游制作约束的保存、锁定和历史查询。 */
class EpisodeTaskCardController(
    private val taskCardService: EpisodeTaskCardService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/task-card")
    fun getTaskCard(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        data class CardData(
            val id: Long,
            @param:JsonProperty("episode_id") val episodeId: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            @param:JsonProperty("primary_goal") val primaryGoal: String,
            @param:JsonProperty("must_include") val mustInclude: List<String>,
            val prohibitions: List<String>,
            @param:JsonProperty("previous_summary") val previousSummary: String,
            @param:JsonProperty("next_goal") val nextGoal: String,
            @param:JsonProperty("ending_hook") val endingHook: String,
            val status: String,
            val completion: Int,
            @param:JsonProperty("locked_at") val lockedAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(val card: CardData, @param:JsonProperty("history_count") val historyCount: Int)

        val snapshot = taskCardService.load(projectId, episodeId) ?: return responseBuilder.notFound().build()
        val card = snapshot.card
        val rs = Response(
            CardData(requireNotNull(card.id), card.episodeId, card.versionNo, card.primaryGoal, card.mustInclude, card.prohibitions, card.previousSummary, card.nextGoal, card.endingHook, card.status.name, taskCardService.completion(card), card.lockedAt, card.updatedAt),
            snapshot.history.size,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/episodes/{episode_id}/task-card")
    fun saveTaskCard(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @RequestBody cardInput: top.foxball.video.entity.jdbc.EpisodeTaskCard,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class CardData(@param:JsonProperty("version_no") val versionNo: Int, val status: String, val completion: Int, @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?)
        try {
            val snapshot = taskCardService.save(projectId, episodeId, cardInput.primaryGoal, cardInput.mustInclude, cardInput.prohibitions, cardInput.previousSummary, cardInput.nextGoal, cardInput.endingHook, userId)
                ?: return responseBuilder.notFound().build()
            val card = snapshot.card
            val rs = CardData(card.versionNo, card.status.name, taskCardService.completion(card), card.updatedAt)
            return responseBuilder.ok().data(rs).build()
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/task-card/lock")
    fun lockTaskCard(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val status: String, @param:JsonProperty("locked_at") val lockedAt: LocalDateTime?)
        val snapshot = taskCardService.lock(projectId, episodeId, userId) ?: return responseBuilder.notFound().build()
        val card = snapshot.card
        val rs = Response(requireNotNull(card.id), card.status.name, card.lockedAt)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/task-card/unlock")
    fun unlockTaskCard(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        data class Response(val id: Long, val status: String)
        val snapshot = taskCardService.unlock(projectId, episodeId) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(snapshot.card.id), snapshot.card.status.name)
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/task-card/history")
    fun listHistory(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        data class RevisionData(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            @param:JsonProperty("primary_goal") val primaryGoal: String,
            @param:JsonProperty("must_include") val mustInclude: List<String>,
            val prohibitions: List<String>,
            @param:JsonProperty("previous_summary") val previousSummary: String,
            @param:JsonProperty("next_goal") val nextGoal: String,
            @param:JsonProperty("ending_hook") val endingHook: String,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
        )
        data class Response(val list: List<RevisionData>)
        val history = taskCardService.history(projectId, episodeId) ?: return responseBuilder.notFound().build()
        val rs = Response(history.map { RevisionData(requireNotNull(it.id), it.versionNo, it.primaryGoal, it.mustInclude, it.prohibitions, it.previousSummary, it.nextGoal, it.endingHook, it.createdAt) })
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/projects/{project_id}/task-cards")
    fun listSummary(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class ItemData(
            val id: Long,
            @param:JsonProperty("episode_id") val episodeId: Long,
            @param:JsonProperty("episode_no") val episodeNo: Int,
            val title: String,
            val status: String?,
            val completion: Int,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(val list: List<ItemData>, val total: Int, val created: Int, val locked: Int)
        val summary = taskCardService.summary(projectId) ?: return responseBuilder.notFound().build()
        val list = summary.map { item -> ItemData(item.card?.id ?: 0, requireNotNull(item.episode.id), item.episode.episodeNo, item.episode.title, item.card?.status?.name, item.completion, item.card?.updatedAt) }
        val rs = Response(list, list.size, list.count { it.id > 0 }, list.count { it.status == TaskCardStatus.LOCKED.name })
        return responseBuilder.ok().data(rs).build()
    }
}
