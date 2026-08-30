package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.Episode
import top.foxball.video.entity.jdbc.EpisodeTaskCard
import top.foxball.video.entity.jdbc.EpisodeTaskCardRevision
import top.foxball.video.entity.jdbc.TaskCardStatus
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.EpisodeTaskCardRepository
import top.foxball.video.repository.EpisodeTaskCardRevisionRepository
import top.foxball.video.repository.ProjectRepository

/** 每集任务卡服务；任务卡是下游分镜和视频制作的剧集级约束。 */
@Service
class EpisodeTaskCardService(
    private val projectRepository: ProjectRepository,
    private val episodeRepository: EpisodeRepository,
    private val cardRepository: EpisodeTaskCardRepository,
    private val revisionRepository: EpisodeTaskCardRevisionRepository,
) {
    data class CardSnapshot(val card: EpisodeTaskCard, val history: List<EpisodeTaskCardRevision>)

    data class EpisodeSummary(
        val episode: Episode,
        val card: EpisodeTaskCard?,
        val completion: Int,
    )

    @Transactional
    fun load(projectId: Long, episodeId: Long): CardSnapshot? {
        if (projectRepository.findById(projectId).orElse(null) == null) return null
        val episode = episodeRepository.findByIdAndProjectId(episodeId, projectId) ?: return null
        val card = cardRepository.findByProjectIdAndEpisodeId(projectId, episodeId) ?: createDefault(projectId, episode)
        return CardSnapshot(card, revisionRepository.findAllByTaskCardIdOrderByVersionNoDesc(requireNotNull(card.id)))
    }

    @Transactional
    fun save(
        projectId: Long,
        episodeId: Long,
        primaryGoal: String,
        mustInclude: List<String>,
        prohibitions: List<String>,
        previousSummary: String,
        nextGoal: String,
        endingHook: String,
        userId: Long?,
    ): CardSnapshot? {
        val episode = episodeRepository.findByIdAndProjectId(episodeId, projectId) ?: return null
        val card = cardRepository.findByProjectIdAndEpisodeId(projectId, episodeId) ?: createDefault(projectId, episode)
        check(card.status != TaskCardStatus.LOCKED) { "任务卡已锁定，请先解锁后修改" }
        val oldVersion = card.versionNo
        if (card.id != null && hasContent(card)) {
            revisionRepository.save(
                EpisodeTaskCardRevision(
                    taskCardId = requireNotNull(card.id),
                    versionNo = oldVersion,
                    primaryGoal = card.primaryGoal,
                    mustInclude = card.mustInclude,
                    prohibitions = card.prohibitions,
                    previousSummary = card.previousSummary,
                    nextGoal = card.nextGoal,
                    endingHook = card.endingHook,
                ),
            )
            card.versionNo = oldVersion + 1
        }
        card.primaryGoal = primaryGoal.trim()
        card.mustInclude = mustInclude.map(String::trim).filter(String::isNotBlank)
        card.prohibitions = prohibitions.map(String::trim).filter(String::isNotBlank)
        card.previousSummary = previousSummary.trim()
        card.nextGoal = nextGoal.trim()
        card.endingHook = endingHook.trim()
        if (userId != null) card.createdBy = userId
        val saved = cardRepository.save(card)
        episode.currentStage = top.foxball.video.entity.jdbc.ProductionStage.ASSETS
        episode.progress = maxOf(episode.progress, 25)
        episode.status = top.foxball.video.entity.jdbc.EpisodeStatus.IN_PRODUCTION
        episodeRepository.save(episode)
        return CardSnapshot(saved, revisionRepository.findAllByTaskCardIdOrderByVersionNoDesc(requireNotNull(saved.id)))
    }

    @Transactional
    fun lock(projectId: Long, episodeId: Long, userId: Long?): CardSnapshot? {
        val card = cardRepository.findByProjectIdAndEpisodeId(projectId, episodeId) ?: return null
        card.status = TaskCardStatus.LOCKED
        card.lockedBy = userId
        card.lockedAt = java.time.LocalDateTime.now()
        val saved = cardRepository.save(card)
        return CardSnapshot(saved, revisionRepository.findAllByTaskCardIdOrderByVersionNoDesc(requireNotNull(saved.id)))
    }

    @Transactional
    fun unlock(projectId: Long, episodeId: Long): CardSnapshot? {
        val card = cardRepository.findByProjectIdAndEpisodeId(projectId, episodeId) ?: return null
        card.status = TaskCardStatus.DRAFT
        card.lockedBy = null
        card.lockedAt = null
        val saved = cardRepository.save(card)
        return CardSnapshot(saved, revisionRepository.findAllByTaskCardIdOrderByVersionNoDesc(requireNotNull(saved.id)))
    }

    @Transactional(readOnly = true)
    fun history(projectId: Long, episodeId: Long): List<EpisodeTaskCardRevision>? {
        val card = cardRepository.findByProjectIdAndEpisodeId(projectId, episodeId) ?: return null
        return revisionRepository.findAllByTaskCardIdOrderByVersionNoDesc(requireNotNull(card.id))
    }

    @Transactional(readOnly = true)
    fun summary(projectId: Long): List<EpisodeSummary>? {
        if (!projectRepository.existsById(projectId)) return null
        return episodeRepository.findAllByProjectIdOrderByEpisodeNoAsc(projectId).map { episode ->
            val card = cardRepository.findByProjectIdAndEpisodeId(projectId, requireNotNull(episode.id))
            EpisodeSummary(episode, card, card?.let(::completion) ?: 0)
        }
    }

    fun completion(card: EpisodeTaskCard): Int {
        val sections = listOf(
            card.primaryGoal.isNotBlank(),
            card.mustInclude.isNotEmpty(),
            card.prohibitions.isNotEmpty(),
            card.previousSummary.isNotBlank(),
            card.nextGoal.isNotBlank(),
            card.endingHook.isNotBlank(),
        )
        return sections.count { it } * 100 / sections.size
    }

    private fun createDefault(projectId: Long, episode: Episode): EpisodeTaskCard {
        val previous = episodeRepository.findByProjectIdAndEpisodeNo(projectId, episode.episodeNo - 1)
            ?.let { cardRepository.findByProjectIdAndEpisodeId(projectId, requireNotNull(it.id)) }
        return cardRepository.save(
            EpisodeTaskCard(
                projectId = projectId,
                episodeId = requireNotNull(episode.id),
                previousSummary = previous?.endingHook ?: if (episode.episodeNo == 1) "首集，无上一集剧情。" else "",
            ),
        )
    }

    private fun hasContent(card: EpisodeTaskCard) = card.primaryGoal.isNotBlank() || card.mustInclude.isNotEmpty() || card.prohibitions.isNotEmpty() || card.previousSummary.isNotBlank() || card.nextGoal.isNotBlank() || card.endingHook.isNotBlank()
}
