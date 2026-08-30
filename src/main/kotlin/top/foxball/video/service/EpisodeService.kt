package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.AssetType
import top.foxball.video.entity.jdbc.Episode
import top.foxball.video.entity.jdbc.EpisodeStatus
import top.foxball.video.repository.AssetRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.ProjectRepository
import top.foxball.video.repository.ScriptCharacterRepository
import top.foxball.video.repository.ScriptDocumentRepository
import top.foxball.video.repository.ScriptVersionRepository

/** 项目剧集及项目级资产导航数据服务。 */
@Service
class EpisodeService(
    private val projectRepository: ProjectRepository,
    private val episodeRepository: EpisodeRepository,
    private val assetRepository: AssetRepository,
    private val documentRepository: ScriptDocumentRepository,
    private val versionRepository: ScriptVersionRepository,
    private val characterRepository: ScriptCharacterRepository,
    private val sceneRepository: top.foxball.video.repository.ScriptSceneRepository,
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
        val assets = assetRepository.findAllByProjectIdOrderByUpdatedAtDesc(projectId)
        val counts = linkedMapOf(
            "character" to assets.count { it.type == AssetType.CHARACTER },
            "scene" to assets.count { it.type == AssetType.SCENE },
            "prop" to assets.count { it.type == AssetType.PROP },
            "voice" to assets.count { it.type == AssetType.VOICE },
            "music" to assets.count { it.type == AssetType.MUSIC },
            "sound_effect" to assets.count { it.type == AssetType.SOUND_EFFECT },
            "total" to assets.size,
        )
        val latestCharacters = linkedMapOf<String, CharacterSummary>()
        var scriptSceneCount = 0
        documentRepository.findAllByProjectId(projectId).forEach { document ->
            val versionId = document.currentVersionId
                ?: versionRepository.findFirstByScriptDocumentIdOrderByVersionNoDesc(requireNotNull(document.id))?.id
            if (versionId != null) {
                scriptSceneCount += sceneRepository.findAllByScriptVersionIdOrderBySortOrderAsc(versionId).size
                characterRepository.findAllByScriptVersionIdOrderByNameAsc(versionId).forEach { character ->
                    latestCharacters.putIfAbsent(character.name, CharacterSummary(character.name, character.description))
                }
            }
        }
        if (latestCharacters.size > (counts["character"] ?: 0)) counts["character"] = latestCharacters.size
        if (scriptSceneCount > (counts["scene"] ?: 0)) counts["scene"] = scriptSceneCount
        return LibrarySnapshot(latestCharacters.values.toList(), counts)
    }
}
