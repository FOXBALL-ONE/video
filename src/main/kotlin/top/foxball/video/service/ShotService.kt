package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.Shot
import top.foxball.video.entity.jdbc.ShotContinuityIssue
import top.foxball.video.entity.jdbc.ShotStatus
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.ProjectRepository
import top.foxball.video.repository.ShotContinuityIssueRepository
import top.foxball.video.repository.ShotRepository
import java.time.LocalDateTime

/** 分镜及镜头连续性服务。 */
@Service
class ShotService(
    private val projectRepository: ProjectRepository,
    private val episodeRepository: EpisodeRepository,
    private val shotRepository: ShotRepository,
    private val issueRepository: ShotContinuityIssueRepository,
) {
    data class Snapshot(val shots: List<Shot>, val issues: List<ShotContinuityIssue>)

    @Transactional(readOnly = true)
    fun load(projectId: Long, episodeId: Long? = null): Snapshot? {
        if (!projectRepository.existsById(projectId)) return null
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val shots = if (episodeId == null) shotRepository.findAllByProjectIdOrderBySequenceNoAsc(projectId) else shotRepository.findAllByProjectIdAndEpisodeIdOrderBySequenceNoAsc(projectId, episodeId)
        val issues = issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId)
        val shotIds = shots.mapNotNull { it.id }.toSet()
        return Snapshot(shots, if (episodeId == null) issues else issues.filter { it.fromShotId in shotIds || it.toShotId in shotIds })
    }

    @Transactional
    fun create(
        projectId: Long,
        title: String,
        location: String?,
        timeOfDay: String?,
        interiorExterior: String?,
        shotSize: String?,
        cameraMovement: String?,
        action: String?,
        dialogue: String?,
        prompt: String?,
        durationSeconds: Double,
        status: ShotStatus,
        episodeId: Long? = null,
        focalLength: String? = null,
        composition: String? = null,
        visualDescription: String? = null,
        characterNames: String? = null,
        propNames: String? = null,
        negativePrompt: String? = null,
        atmosphere: String? = null,
        narrativePurpose: String? = null,
        cameraAngle: String? = null,
    ): Shot? {
        if (!projectRepository.existsById(projectId)) return null
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val shots = if (episodeId == null) shotRepository.findAllByProjectIdAndEpisodeIdIsNullOrderBySequenceNoAsc(projectId) else shotRepository.findAllByProjectIdAndEpisodeIdOrderBySequenceNoAsc(projectId, episodeId)
        val nextSequence = (shots.maxOfOrNull { it.sequenceNo } ?: 0) + 1
        val saved = shotRepository.save(
            Shot(
                projectId = projectId,
                episodeId = episodeId,
                sequenceNo = nextSequence,
                title = title,
                location = location,
                timeOfDay = timeOfDay,
                interiorExterior = interiorExterior,
                shotSize = shotSize,
                cameraMovement = cameraMovement,
                cameraAngle = cameraAngle,
                focalLength = focalLength,
                composition = composition,
                visualDescription = visualDescription,
                action = action,
                dialogue = dialogue,
                characterNames = characterNames,
                propNames = propNames,
                prompt = prompt,
                negativePrompt = negativePrompt,
                atmosphere = atmosphere,
                narrativePurpose = narrativePurpose,
                durationSeconds = durationSeconds,
                status = status,
            ),
        )
        touchEpisode(episodeId)
        return saved
    }

    @Transactional
    fun update(
        projectId: Long,
        shotId: Long,
        title: String,
        location: String?,
        timeOfDay: String?,
        interiorExterior: String?,
        shotSize: String?,
        cameraMovement: String?,
        action: String?,
        dialogue: String?,
        prompt: String?,
        durationSeconds: Double,
        status: ShotStatus,
        episodeId: Long? = null,
        focalLength: String? = null,
        composition: String? = null,
        visualDescription: String? = null,
        characterNames: String? = null,
        propNames: String? = null,
        negativePrompt: String? = null,
        atmosphere: String? = null,
        narrativePurpose: String? = null,
        cameraAngle: String? = null,
    ): Shot? {
        val shot = shotRepository.findById(shotId).orElse(null) ?: return null
        if (shot.projectId != projectId) return null
        if (episodeId != null && shot.episodeId != episodeId) return null
        shot.title = title
        shot.location = location
        shot.timeOfDay = timeOfDay
        shot.interiorExterior = interiorExterior
        shot.shotSize = shotSize
        shot.cameraMovement = cameraMovement
        shot.cameraAngle = cameraAngle
        shot.focalLength = focalLength
        shot.composition = composition
        shot.visualDescription = visualDescription
        shot.action = action
        shot.dialogue = dialogue
        shot.characterNames = characterNames
        shot.propNames = propNames
        shot.prompt = prompt
        shot.negativePrompt = negativePrompt
        shot.atmosphere = atmosphere
        shot.narrativePurpose = narrativePurpose
        shot.durationSeconds = durationSeconds
        shot.status = status
        val saved = shotRepository.save(shot)
        touchEpisode(shot.episodeId ?: episodeId)
        return saved
    }

    @Transactional
    fun delete(projectId: Long, shotId: Long, episodeId: Long? = null): Boolean {
        val shot = shotRepository.findById(shotId).orElse(null) ?: return false
        if (shot.projectId != projectId) return false
        if (episodeId != null) {
            if (episodeRepository.findByIdAndProjectId(episodeId, projectId) == null || shot.episodeId != episodeId) return false
        }
        val effectiveEpisodeId = shot.episodeId ?: episodeId
        shotRepository.delete(shot)
        val remaining = if (effectiveEpisodeId == null) {
            shotRepository.findAllByProjectIdAndEpisodeIdIsNullOrderBySequenceNoAsc(projectId)
        } else {
            shotRepository.findAllByProjectIdAndEpisodeIdOrderBySequenceNoAsc(projectId, effectiveEpisodeId)
        }
        remaining.forEachIndexed { index, item -> item.sequenceNo = index + 1 }
        shotRepository.saveAll(remaining)
        touchEpisode(effectiveEpisodeId)
        return true
    }

    @Transactional
    fun resolveIssues(projectId: Long, episodeId: Long? = null): Int {
        if (!projectRepository.existsById(projectId)) return 0
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return 0
        val allIssues = issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId)
        val issues = if (episodeId == null) allIssues else {
            val shotIds = shotRepository.findAllByProjectIdAndEpisodeIdOrderBySequenceNoAsc(projectId, episodeId).mapNotNull { it.id }.toSet()
            allIssues.filter { it.fromShotId in shotIds || it.toShotId in shotIds }
        }
        issues.forEach { it.resolved = true; it.resolvedAt = LocalDateTime.now() }
        issueRepository.saveAll(issues)
        return issues.size
    }

    private fun touchEpisode(episodeId: Long?) {
        if (episodeId == null) return
        val episode = episodeRepository.findById(episodeId).orElse(null) ?: return
        episode.currentStage = top.foxball.video.entity.jdbc.ProductionStage.SHOTS
        episode.status = top.foxball.video.entity.jdbc.EpisodeStatus.IN_PRODUCTION
        episode.progress = maxOf(episode.progress, 50)
        episodeRepository.save(episode)
    }
}
