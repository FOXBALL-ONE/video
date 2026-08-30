package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.entity.jdbc.SubtitleMode
import top.foxball.video.entity.jdbc.VideoRenderSettings
import top.foxball.video.repository.ProductionTaskRepository
import top.foxball.video.repository.ProjectRepository
import top.foxball.video.repository.VideoRenderSettingsRepository
import java.time.LocalDateTime

/** 视频参数和制作队列服务。 */
@Service
class VideoWorkspaceService(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: VideoRenderSettingsRepository,
    private val taskRepository: ProductionTaskRepository,
) {
    data class Snapshot(val settings: VideoRenderSettings, val tasks: List<ProductionTask>)

    @Transactional
    fun load(projectId: Long): Snapshot? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        val settings = settingsRepository.findFirstByProjectIdOrderByUpdatedAtDesc(projectId) ?: settingsRepository.save(VideoRenderSettings(projectId = projectId, projectVersionId = project.currentVersionId))
        return Snapshot(settings, taskRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId))
    }

    @Transactional
    fun saveSettings(projectId: Long, aspectRatio: String, resolution: String, style: String, fps: Int, subtitleMode: SubtitleMode): VideoRenderSettings? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        val settings = settingsRepository.findFirstByProjectIdOrderByUpdatedAtDesc(projectId) ?: VideoRenderSettings(projectId = projectId, projectVersionId = project.currentVersionId)
        settings.aspectRatio = aspectRatio
        settings.resolution = resolution
        settings.style = style
        settings.fps = fps
        settings.subtitleMode = subtitleMode
        return settingsRepository.save(settings)
    }

    @Transactional
    fun queue(projectId: Long): Snapshot? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        val settings = settingsRepository.findFirstByProjectIdOrderByUpdatedAtDesc(projectId) ?: settingsRepository.save(VideoRenderSettings(projectId = projectId, projectVersionId = project.currentVersionId))
        val active = taskRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId).filter { it.status == ProductionTaskStatus.PENDING || it.status == ProductionTaskStatus.QUEUED || it.status == ProductionTaskStatus.RUNNING }
        if (active.isEmpty()) {
            val payload = mapOf("aspect_ratio" to settings.aspectRatio, "resolution" to settings.resolution, "style" to settings.style, "fps" to settings.fps, "subtitle_mode" to settings.subtitleMode.name)
            val tasks = listOf(ProductionTaskType.IMAGE_GENERATION, ProductionTaskType.VOICEOVER, ProductionTaskType.MUSIC, ProductionTaskType.SUBTITLE, ProductionTaskType.COMPOSITION).map { type -> ProductionTask(projectId = projectId, projectVersionId = project.currentVersionId, taskType = type, status = ProductionTaskStatus.QUEUED, inputPayload = payload) }
            taskRepository.saveAll(tasks)
        }
        return Snapshot(settings, taskRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId))
    }
}
