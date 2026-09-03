package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.Episode
import top.foxball.video.entity.jdbc.EpisodeStatus
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.repository.EpisodeAssetRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.ProjectRepository
import java.util.Locale

/** 项目剧集及工作流剧集资产导航数据服务。 */
@Service
class EpisodeService(
    private val projectRepository: ProjectRepository,
    private val episodeRepository: EpisodeRepository,
    private val episodeAssetRepository: EpisodeAssetRepository,
) {
    data class CharacterSummary(val name: String, val description: String?)

    data class LibrarySnapshot(
        val characters: List<CharacterSummary>,
        val assetCounts: Map<String, Int>,
    )

    @Transactional
    fun list(projectId: Long): List<Episode>? {
        if (!projectRepository.existsById(projectId)) return null
        val episodes = episodeRepository.findAllByProjectIdOrderByEpisodeNoAsc(projectId)
        if (episodes.isNotEmpty()) return episodes
        return listOf(episodeRepository.save(Episode(projectId = projectId, episodeNo = 1, title = "第001集")))
    }

    @Transactional
    fun create(projectId: Long, title: String, description: String?): Episode? {
        if (!projectRepository.existsById(projectId)) return null
        val nextNo = (episodeRepository.findAllByProjectIdOrderByEpisodeNoAsc(projectId).maxOfOrNull { it.episodeNo } ?: 0) + 1
        return episodeRepository.save(Episode(projectId = projectId, episodeNo = nextNo, title = title.trim(), description = description?.trim()?.ifBlank { null }))
    }

    @Transactional
    fun update(projectId: Long, episodeId: Long, title: String, description: String?, status: EpisodeStatus): Episode? {
        val episode = episodeRepository.findByIdAndProjectId(episodeId, projectId) ?: return null
        episode.title = title.trim()
        episode.description = description?.trim()?.ifBlank { null }
        episode.status = status
        return episodeRepository.save(episode)
    }

    @Transactional
    fun delete(projectId: Long, episodeId: Long): Boolean {
        val episode = episodeRepository.findByIdAndProjectId(episodeId, projectId) ?: return false
        val count = episodeRepository.findAllByProjectIdOrderByEpisodeNoAsc(projectId).size
        if (count <= 1) return false
        episodeRepository.delete(episode)
        return true
    }

    @Transactional(readOnly = true)
    fun library(projectId: Long): LibrarySnapshot? {
        if (!projectRepository.existsById(projectId)) return null
        val assets = episodeAssetRepository.findAllByProjectIdOrderByUpdatedAtDesc(projectId)
        val counts = linkedMapOf(
            "character" to assets.count { it.type == EpisodeAssetType.CHARACTER },
            "scene_background" to assets.count { it.type == EpisodeAssetType.SCENE_BACKGROUND },
            "prop" to assets.count { it.type == EpisodeAssetType.PROP },
            "costume" to assets.count { it.type == EpisodeAssetType.COSTUME },
            "voice" to assets.count { it.type == EpisodeAssetType.VOICE },
            "music" to assets.count { it.type == EpisodeAssetType.MUSIC },
            "sound_effect" to assets.count { it.type == EpisodeAssetType.SOUND_EFFECT },
            "total" to assets.size,
        )
        val characters = assets
            .asSequence()
            .filter { it.type == EpisodeAssetType.CHARACTER }
            .distinctBy { it.name.trim().lowercase(Locale.ROOT) }
            .map { CharacterSummary(it.name, it.description) }
            .toList()
        return LibrarySnapshot(characters, counts)
    }
}
