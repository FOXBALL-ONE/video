package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.*
import top.foxball.video.repository.*
import java.math.BigDecimal
import java.time.LocalDateTime

/** 剧本文档及其版本的持久化服务。 */
@Service
class ScriptService(
    private val projectRepository: ProjectRepository,
    private val episodeRepository: EpisodeRepository,
    private val documentRepository: ScriptDocumentRepository,
    private val versionRepository: ScriptVersionRepository,
    private val sceneRepository: ScriptSceneRepository,
    private val characterRepository: ScriptCharacterRepository,
    private val dialogueRepository: ScriptDialogueRepository,
    private val qualityCheckRepository: ScriptQualityCheckRepository,
) {
    data class ScriptSnapshot(
        val document: ScriptDocument,
        val version: ScriptVersion,
        val versions: List<ScriptVersion>,
        val scenes: List<ScriptScene>,
        val characters: List<ScriptCharacter>,
        val dialogues: List<ScriptDialogue>,
        val qualityChecks: List<ScriptQualityCheck>,
    )

    @Transactional
    fun load(projectId: Long, episodeId: Long? = null): ScriptSnapshot? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val document = (if (episodeId == null) documentRepository.findByProjectIdAndEpisodeIdIsNull(projectId) else documentRepository.findByProjectIdAndEpisodeId(projectId, episodeId))
            ?: createDocument(projectId, project.name, episodeId)
        val version = currentVersion(document) ?: createVersion(document, projectId, project.versionName, "")
        if (document.currentVersionId != version.id) {
            document.currentVersionId = version.id
            documentRepository.save(document)
        }
        return snapshot(document, version)
    }

    @Transactional
    fun save(projectId: Long, content: String, title: String?, episodeId: Long? = null): ScriptSnapshot? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val document = (if (episodeId == null) documentRepository.findByProjectIdAndEpisodeIdIsNull(projectId) else documentRepository.findByProjectIdAndEpisodeId(projectId, episodeId))
            ?: createDocument(projectId, title ?: project.name, episodeId)
        if (!title.isNullOrBlank()) document.title = title.trim()
        var version = currentVersion(document)
        if (version == null || version.status != VersionStatus.DRAFT) {
            val previous = version
            val nextNo = (versionRepository.findAllByScriptDocumentIdOrderByVersionNoDesc(requireNotNull(document.id))
                .firstOrNull()?.versionNo ?: 0) + 1
            version = ScriptVersion(
                projectId = projectId,
                scriptDocumentId = requireNotNull(document.id),
                versionNo = nextNo,
                versionName = nextVersionName(previous?.versionName, nextNo),
                content = content,
            )
        } else {
            version.content = content
        }
        updateParsedData(version)
        val savedVersion = versionRepository.save(version)
        project.versionName = savedVersion.versionName
        if (episodeId != null) {
            val episode = episodeRepository.findByIdAndProjectId(episodeId, projectId)
            if (episode != null) {
                episode.durationSeconds = savedVersion.estimatedDurationSeconds
                episode.progress = if (content.isBlank()) 0 else 25
                episode.status =
                    if (content.isBlank()) episode.status else EpisodeStatus.IN_PRODUCTION
                episodeRepository.save(episode)
            }
        }
        project.durationSeconds = if (episodeId == null) savedVersion.estimatedDurationSeconds else
            episodeRepository.findAllByProjectIdOrderByEpisodeNoAsc(projectId).sumOf { it.durationSeconds }
        project.qualityScore = savedVersion.structureScore
        projectRepository.save(project)
        document.currentVersionId = savedVersion.id
        documentRepository.save(document)
        replaceStructuredData(savedVersion)
        return snapshot(document, savedVersion)
    }

    @Transactional
    fun lock(projectId: Long, userId: Long?, episodeId: Long? = null): ScriptSnapshot? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val document = (if (episodeId == null) documentRepository.findByProjectIdAndEpisodeIdIsNull(projectId) else documentRepository.findByProjectIdAndEpisodeId(projectId, episodeId)) ?: return null
        val version = currentVersion(document) ?: return null
        if (version.status == VersionStatus.DRAFT) {
            version.status = VersionStatus.LOCKED
            version.lockedBy = userId
            version.lockedAt = LocalDateTime.now()
            versionRepository.save(version)
        }
        return snapshot(document, version)
    }

    private fun createDocument(projectId: Long, title: String, episodeId: Long? = null): ScriptDocument =
        documentRepository.save(
            ScriptDocument(
                projectId = projectId,
                episodeId = episodeId,
                title = title.ifBlank { "未命名剧本" })
        )

    private fun createVersion(
        document: ScriptDocument,
        projectId: Long,
        projectVersion: String,
        content: String
    ): ScriptVersion {
        val version = ScriptVersion(
            projectId = projectId,
            scriptDocumentId = requireNotNull(document.id),
            versionNo = 1,
            versionName = if (projectVersion.matches(Regex("^v\\d+\\.\\d+$"))) projectVersion else "v0.1",
            content = content,
        )
        val saved = versionRepository.save(version)
        document.currentVersionId = saved.id
        documentRepository.save(document)
        return saved
    }

    private fun currentVersion(document: ScriptDocument): ScriptVersion? =
        document.currentVersionId?.let { versionRepository.findById(it).orElse(null) }
            ?: versionRepository.findFirstByScriptDocumentIdOrderByVersionNoDesc(requireNotNull(document.id))

    private fun nextVersionName(previous: String?, versionNo: Int): String {
        val match = previous?.let { Regex("^v(\\d+)\\.(\\d+)$").matchEntire(it) }
        if (match == null) return "v$versionNo.0"
        return "v${match.groupValues[1]}.${match.groupValues[2].toInt() + 1}"
    }

    private fun updateParsedData(version: ScriptVersion) {
        val text = version.content.trim()
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val sceneLines =
            lines.filter { it.matches(Regex("^(第\\s*\\d+\\s*场|(?:INT|EXT|内|外)[.：:].*)$", RegexOption.IGNORE_CASE)) }
        val dialogueLines = lines.mapNotNull { line ->
            Regex("^([^：:]{1,30})[：:](.+)$").matchEntire(line)
                ?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }
        }
        val characters = dialogueLines.map { it.first }.filterNot { it in setOf("旁白", "画外音", "音效") }.distinct()
        version.sceneCount = (sceneLines.size.takeIf { it > 0 } ?: if (text.isBlank()) 0 else 1)
        version.characterCount = characters.size
        version.dialogueCount = dialogueLines.size
        version.estimatedDurationSeconds = (text.length / 4).coerceAtLeast(0)
        version.structureScore = if (text.isBlank()) BigDecimal.ZERO else BigDecimal(
            "${
                (5.0 + (version.sceneCount.coerceAtMost(5) * 0.5) + if (characters.isNotEmpty()) 1.0 else 0.0).coerceAtMost(
                    10.0
                )
            }"
        ).setScale(2)
    }

    private fun replaceStructuredData(version: ScriptVersion) {
        val versionId = requireNotNull(version.id)
        val oldScenes = sceneRepository.findAllByScriptVersionIdOrderBySortOrderAsc(versionId)
        oldScenes.forEach { scene -> dialogueRepository.deleteAllByScriptSceneId(requireNotNull(scene.id)) }
        sceneRepository.deleteAllByScriptVersionId(versionId)
        characterRepository.deleteAllByScriptVersionId(versionId)
        qualityCheckRepository.deleteAllByScriptVersionId(versionId)

        val lines = version.content.lines().map { it.trim() }.filter { it.isNotBlank() }
        val sceneHeaders = lines.withIndex().filter { (_, line) ->
            line.matches(
                Regex(
                    "^(第\\s*\\d+\\s*场|(?:INT|EXT|内|外)[.：:].*)$",
                    RegexOption.IGNORE_CASE
                )
            )
        }
        val sceneData =
            if (sceneHeaders.isEmpty() && version.content.isNotBlank()) listOf(0 to "正文") else sceneHeaders.map { it.index to it.value }
        val scenes = sceneData.mapIndexed { index, (_, title) ->
            sceneRepository.save(
                ScriptScene(
                    scriptVersionId = versionId,
                    sceneNo = index + 1,
                    title = title,
                    content = version.content,
                    durationSeconds = version.estimatedDurationSeconds / version.sceneCount.coerceAtLeast(1),
                    sortOrder = index
                )
            )
        }
        val characterNames =
            lines.mapNotNull { Regex("^([^：:]{1,30})[：:](.+)$").matchEntire(it)?.groupValues?.get(1)?.trim() }
                .filterNot { it in setOf("旁白", "画外音", "音效") }.distinct()
        val characters = characterNames.associateWith { name ->
            characterRepository.save(
                ScriptCharacter(
                    scriptVersionId = versionId,
                    name = name
                )
            )
        }
        lines.mapNotNull { Regex("^([^：:]{1,30})[：:](.+)$").matchEntire(it) }.forEachIndexed { index, match ->
            val character = characters[match.groupValues[1].trim()]
            dialogueRepository.save(
                ScriptDialogue(
                    scriptSceneId = requireNotNull(scenes.firstOrNull()?.id),
                    characterId = character?.id,
                    sequenceNo = index,
                    content = match.groupValues[2].trim(),
                    dialogueType = if (character == null) DialogueType.NARRATION else DialogueType.DIALOGUE,
                    durationSeconds = (match.groupValues[2].length / 4).coerceAtLeast(1)
                )
            )
        }
        val checks = listOf(
            Triple("CONFLICT", "主冲突清晰", version.content.length >= 20),
            Triple("MOTIVATION", "人物动机完整", version.characterCount > 0),
            Triple("PACING", "场次节奏稳定", version.sceneCount in 1..12),
            Triple("ASSETS", "资产可提取", version.content.isNotBlank()),
        )
        qualityCheckRepository.saveAll(checks.map { (code, title, passed) ->
            ScriptQualityCheck(
                scriptVersionId = versionId,
                ruleCode = code,
                title = title,
                note = if (passed) "已通过当前版本检查" else "补充内容后重新检查",
                passed = passed,
                score = if (passed) BigDecimal("1.00") else BigDecimal.ZERO
            )
        })
    }

    private fun snapshot(document: ScriptDocument, version: ScriptVersion): ScriptSnapshot {
        val versionId = requireNotNull(version.id)
        val scenes = sceneRepository.findAllByScriptVersionIdOrderBySortOrderAsc(versionId)
        val dialogues =
            scenes.flatMap { scene -> dialogueRepository.findAllByScriptSceneIdOrderBySequenceNoAsc(requireNotNull(scene.id)) }
        return ScriptSnapshot(
            document,
            version,
            versionRepository.findAllByScriptDocumentIdOrderByVersionNoDesc(requireNotNull(document.id)),
            scenes,
            characterRepository.findAllByScriptVersionIdOrderByNameAsc(versionId),
            dialogues,
            qualityCheckRepository.findAllByScriptVersionIdOrderByCreatedAtDesc(versionId)
        )
    }
}
