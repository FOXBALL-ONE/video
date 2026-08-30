package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.Shot
import top.foxball.video.entity.jdbc.ShotContinuityIssue
import top.foxball.video.entity.jdbc.ShotStatus
import top.foxball.video.repository.ProjectRepository
import top.foxball.video.repository.ShotContinuityIssueRepository
import top.foxball.video.repository.ShotRepository
import java.time.LocalDateTime

/** 分镜及镜头连续性服务。 */
@Service
class ShotService(
    private val projectRepository: ProjectRepository,
    private val shotRepository: ShotRepository,
    private val issueRepository: ShotContinuityIssueRepository,
) {
    data class Snapshot(val shots: List<Shot>, val issues: List<ShotContinuityIssue>)

    @Transactional(readOnly = true)
    fun load(projectId: Long): Snapshot? {
        if (!projectRepository.existsById(projectId)) return null
        return Snapshot(shotRepository.findAllByProjectIdOrderBySequenceNoAsc(projectId), issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId))
    }

    @Transactional
    fun create(projectId: Long, title: String, location: String?, timeOfDay: String?, interiorExterior: String?, shotSize: String?, cameraMovement: String?, action: String?, dialogue: String?, prompt: String?, durationSeconds: Double, status: ShotStatus): Shot? {
        if (!projectRepository.existsById(projectId)) return null
        val nextSequence = (shotRepository.findAllByProjectIdOrderBySequenceNoAsc(projectId).maxOfOrNull { it.sequenceNo } ?: 0) + 1
        return shotRepository.save(Shot(projectId = projectId, sequenceNo = nextSequence, title = title, location = location, timeOfDay = timeOfDay, interiorExterior = interiorExterior, shotSize = shotSize, cameraMovement = cameraMovement, action = action, dialogue = dialogue, prompt = prompt, durationSeconds = durationSeconds, status = status))
    }

    @Transactional
    fun update(projectId: Long, shotId: Long, title: String, location: String?, timeOfDay: String?, interiorExterior: String?, shotSize: String?, cameraMovement: String?, action: String?, dialogue: String?, prompt: String?, durationSeconds: Double, status: ShotStatus): Shot? {
        val shot = shotRepository.findById(shotId).orElse(null) ?: return null
        if (shot.projectId != projectId) return null
        shot.title = title
        shot.location = location
        shot.timeOfDay = timeOfDay
        shot.interiorExterior = interiorExterior
        shot.shotSize = shotSize
        shot.cameraMovement = cameraMovement
        shot.action = action
        shot.dialogue = dialogue
        shot.prompt = prompt
        shot.durationSeconds = durationSeconds
        shot.status = status
        return shotRepository.save(shot)
    }

    @Transactional
    fun resolveIssues(projectId: Long): Int {
        if (!projectRepository.existsById(projectId)) return 0
        val issues = issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId)
        issues.forEach { it.resolved = true; it.resolvedAt = LocalDateTime.now() }
        issueRepository.saveAll(issues)
        return issues.size
    }
}
