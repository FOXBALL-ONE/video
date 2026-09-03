package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.AiChatCompletionCommand
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiAnalysisException
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.ai.AiUpstreamAuthenticationException
import top.foxball.video.ai.AiUpstreamException
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.entity.jdbc.*
import top.foxball.video.repository.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

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
    private val episodeAssetRepository: EpisodeAssetRepository,
    private val aiInvocationService: AiInvocationService,
    private val objectMapper: ObjectMapper,
    private val providerConfigRepository: AiModelProviderConfigRepository,
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

    /** 只读取已存在的剧本文档，不为查询或任务校验创建空文档/空版本。 */
    @Transactional(readOnly = true)
    fun loadExisting(projectId: Long, episodeId: Long? = null): ScriptSnapshot? {
        if (!projectRepository.existsById(projectId)) return null
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val document = if (episodeId == null) {
            documentRepository.findByProjectIdAndEpisodeIdIsNull(projectId)
        } else {
            documentRepository.findByProjectIdAndEpisodeId(projectId, episodeId)
        } ?: return null
        val version = currentVersion(document) ?: return null
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
        version.aiAnalysisJson = null
        version.aiAnalysisStatus = ScriptAnalysisStatus.NOT_PARSED
        version.aiAnalysisProviderConfigId = null
        version.aiAnalysisModel = null
        version.aiAnalysisAt = null
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

    /** 使用固定拆解模板调用大模型，并将原始 JSON 与可执行资产同步保存。 */
    @Transactional
    fun analyze(projectId: Long, episodeId: Long?, providerConfigId: Long, model: String): ScriptSnapshot? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        if (episodeId != null && episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val document = (if (episodeId == null) documentRepository.findByProjectIdAndEpisodeIdIsNull(projectId) else documentRepository.findByProjectIdAndEpisodeId(projectId, episodeId)) ?: return null
        val version = currentVersion(document) ?: return null
        if (version.content.isBlank()) throw IllegalArgumentException("剧本内容不能为空")
        version.aiAnalysisStatus = ScriptAnalysisStatus.PROCESSING
        version.aiAnalysisProviderConfigId = providerConfigId
        version.aiAnalysisModel = model.trim()
        versionRepository.save(version)
        val requestId = UUID.randomUUID().toString()
        val providerName = providerConfigRepository.findById(providerConfigId).orElse(null)?.provider?.trim().orEmpty().ifBlank { "unknown" }
        try {
            val result = aiInvocationService.createChatCompletion(
                AiChatCompletionCommand(
                    providerConfigId = providerConfigId,
                    model = model.trim(),
                    messages = listOf(AiChatMessage(AiChatRole.SYSTEM, ANALYSIS_TEMPLATE.replace("{{剧本文本}}", version.content))),
                    temperature = 0.1,
                    maxCompletionTokens = 32_000,
                    requestId = requestId,
                )
            ).block() ?: throw IllegalStateException("模型服务未返回结果")
            val raw = result.primaryChoice?.message?.content?.trim().orEmpty()
            val analysis = parseAnalysisJson(raw)
            version.aiAnalysisJson = objectMapper.writeValueAsString(analysis)
            version.aiAnalysisStatus = ScriptAnalysisStatus.COMPLETED
            version.aiAnalysisAt = LocalDateTime.now()
            updateParsedDataFromAnalysis(version, analysis)
            val savedVersion = versionRepository.save(version)
            replaceStructuredDataFromAnalysis(savedVersion, analysis)
            syncEpisodeAssets(projectId, episodeId, analysis)
        } catch (exception: AiClientException) {
            version.aiAnalysisStatus = ScriptAnalysisStatus.FAILED
            versionRepository.save(version)
            val errorCode = when (exception) {
                is AiUpstreamException -> exception.statusCode
                is AiUpstreamAuthenticationException -> exception.statusCode
                is AiRateLimitException -> 429
                else -> null
            }
            val responseContent = when (exception) {
                is AiUpstreamException -> exception.responseContent
                is AiUpstreamAuthenticationException -> exception.responseContent
                is AiRateLimitException -> exception.responseContent
                else -> null
            }
            throw AiAnalysisException(exception.message ?: "模型服务调用失败", errorCode, responseContent, providerName, requestId, exception)
        } catch (exception: Exception) {
            version.aiAnalysisStatus = ScriptAnalysisStatus.FAILED
            versionRepository.save(version)
            throw exception
        }
        return snapshot(document, version)
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

    private fun updateParsedDataFromAnalysis(version: ScriptVersion, analysis: JsonNode) {
        val scenes = analysis.path("scenes").takeIf { it.isArray }?.size() ?: 0
        val characters = analysis.path("characters").takeIf { it.isArray }?.size() ?: 0
        // 新资产模板不输出 dialogues；没有该字段时保留保存正文时计算出的对白数。
        val dialogues = analysis.path("dialogues").takeIf { it.isArray }?.size() ?: version.dialogueCount
        version.sceneCount = scenes
        version.characterCount = characters
        version.dialogueCount = dialogues
        version.estimatedDurationSeconds = (version.content.length / 4).coerceAtLeast(0)
        version.structureScore = if (version.content.isBlank()) BigDecimal.ZERO else BigDecimal("10.00")
    }

    private fun parseAnalysisJson(raw: String): JsonNode {
        val normalized = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (normalized.isBlank()) throw IllegalStateException("模型未返回剧本解析结果")
        val node = try { objectMapper.readTree(normalized) } catch (exception: Exception) {
            throw IllegalStateException("模型返回的解析结果不是合法 JSON", exception)
        }
        if (!node.isObject) throw IllegalStateException("模型返回的解析结果必须是 JSON 对象")
        return node
    }

    private fun replaceStructuredDataFromAnalysis(version: ScriptVersion, analysis: JsonNode) {
        val versionId = requireNotNull(version.id)
        val oldScenes = sceneRepository.findAllByScriptVersionIdOrderBySortOrderAsc(versionId)
        oldScenes.forEach { scene -> dialogueRepository.deleteAllByScriptSceneId(requireNotNull(scene.id)) }
        sceneRepository.deleteAllByScriptVersionId(versionId)
        characterRepository.deleteAllByScriptVersionId(versionId)
        qualityCheckRepository.deleteAllByScriptVersionId(versionId)

        val sceneNodes = analysis.path("scenes").takeIf { it.isArray }?.toList().orEmpty()
        val scenes = (if (sceneNodes.isEmpty()) listOf(objectMapper.createObjectNode().put("name", "正文")) else sceneNodes).mapIndexed { index, node ->
            val title = node.path("name").textValue()?.takeIf { it.isNotBlank() } ?: node.path("location").textValue()?.takeIf { it.isNotBlank() } ?: "场景${index + 1}"
            sceneRepository.save(ScriptScene(scriptVersionId = versionId, sceneNo = index + 1, title = title, location = node.path("location").textValue(), timeOfDay = node.path("time").textValue(), interiorExterior = node.path("environment_type").textValue(), content = node.toString(), durationSeconds = version.estimatedDurationSeconds / sceneNodes.size.coerceAtLeast(1), sortOrder = index))
        }
        val characterNodes = analysis.path("characters").takeIf { it.isArray }?.toList().orEmpty()
        val characters = characterNodes.mapNotNull { node ->
            val name = node.path("name").textValue()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val description = listOfNotNull(
                node.path("identity").textValue(),
                node.path("appearance").textValue(),
                node.path("personality").textValue() ?: node.path("personality").takeIf { it.isArray }?.joinToString("、") { it.textValue() },
            ).filter { it.isNotBlank() }.joinToString("；").ifBlank { null }
            val motivation = node.path("dialogue_intent").textValue()
                ?: node.path("dialogue_intent").takeIf { it.isArray }?.joinToString("、") { it.textValue() }
            val relationship = node.path("relationships").textValue()
                ?: node.path("relationships").toString().takeIf { it != "[]" && it != "null" }
            name to characterRepository.save(ScriptCharacter(scriptVersionId = versionId, name = name, description = description, motivation = motivation, relationship = relationship))
        }.toMap()
        val sceneBySourceId = sceneNodes.mapIndexedNotNull { index, node -> node.path("id").textValue()?.let { it to scenes[index] } }.toMap()
        analysis.path("dialogues").takeIf { it.isArray }?.toList().orEmpty().forEachIndexed { index, node ->
            val scene = node.path("scene_id").textValue()?.let(sceneBySourceId::get) ?: scenes.firstOrNull() ?: return@forEachIndexed
            val speaker = node.path("speaker_id").textValue()?.let { sourceId -> characterNodes.firstOrNull { it.path("id").textValue() == sourceId }?.path("name")?.textValue() } ?: node.path("speaker").textValue()
            val character = speaker?.let(characters::get)
            val text = node.path("text").textValue() ?: node.path("content").textValue() ?: return@forEachIndexed
            dialogueRepository.save(ScriptDialogue(scriptSceneId = requireNotNull(scene.id), characterId = character?.id, sequenceNo = index, content = text, dialogueType = if (character == null) DialogueType.NARRATION else DialogueType.DIALOGUE, durationSeconds = (text.length / 4).coerceAtLeast(1)))
        }
        qualityCheckRepository.saveAll(listOf(ScriptQualityCheck(scriptVersionId = versionId, ruleCode = "AI_ANALYSIS", title = "AI 结构化解析", note = "已保存大模型拆解结果", passed = true, score = BigDecimal("1.00"))))
    }

    private fun syncEpisodeAssets(projectId: Long, episodeId: Long?, analysis: JsonNode) {
        if (episodeId == null) return
        fun sync(nodes: JsonNode, type: EpisodeAssetType, description: (JsonNode) -> String?, notes: (JsonNode) -> String?) {
            if (!nodes.isArray) return
            nodes.forEach { node ->
                val name = node.path("name").textValue()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val existing = episodeAssetRepository.findByProjectIdAndEpisodeIdAndNameAndType(projectId, episodeId, name, type)
                val asset = existing ?: EpisodeAsset(projectId = projectId, episodeId = episodeId, name = name, type = type)
                asset.description = description(node)?.take(100_000)
                asset.notes = notes(node)?.take(100_000)
                episodeAssetRepository.save(asset)
            }
        }
        sync(analysis.path("characters"), EpisodeAssetType.CHARACTER, { node -> listOfNotNull(node.path("identity").textValue(), node.path("appearance").textValue(), node.path("personality").textValue() ?: node.path("personality").takeIf { it.isArray }?.joinToString("、") { it.textValue() }).joinToString("；").ifBlank { null } }, { it.toString() })
        sync(analysis.path("scenes"), EpisodeAssetType.SCENE_BACKGROUND, { node -> listOfNotNull(node.path("location").textValue(), node.path("time").textValue(), node.path("lighting").textValue(), node.path("visual_style").textValue()).joinToString("；").ifBlank { null } }, { it.toString() })
        sync(analysis.path("props"), EpisodeAssetType.PROP, { it.path("description").textValue() }, { it.toString() })
        sync(analysis.path("costumes"), EpisodeAssetType.COSTUME, { it.path("description").textValue() }, { it.toString() })
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

    companion object {
        private const val ANALYSIS_TEMPLATE = """你是一名专业的影视剧本资产提取师,负责从 AI 短剧剧本正文中提取结构化资产信息。

## 任务
阅读【剧本文本】,提取三类资产:角色(characters)、场景(scenes)、道具(props),输出一个 JSON 对象。

## 提取规则
1. 忠实原文:只提取正文中出现、或能从上下文明确推断的实体;禁止虚构、扩写或引入剧本之外的设定。
2. 合并去重:同一实体的不同写法(如"林总"与"林正雄"、台词署名与动作描写中的称呼)合并为一条;name 使用正文中最正式、最完整的名称。
3. 类型判定:
   - characters:有名有姓、或以明确指代参与剧情的人物(如"外卖员")均提取;仅顺带提及、无剧情作用的背景人群不入列。
   - scenes:剧情实际发生的地点(内景/外景、实景/虚拟空间);仅在对白中被追述、无呈现需求的地点不入列。
   - props:被角色使用、持有或推动剧情的物件;纯氛围装饰且无连续性要求的不入列。
4. 关联引用:为每条记录生成稳定 id(角色 C1/C2…,场景 S1/S2…,道具 P1/P2…);relationships、owner、plot_purpose 中涉及其他实体时,用对应 id 指代,禁止只写名字。
5. 字段填写:字段值为简体中文短语或短句;正文无依据的字段填 null,不要猜测;description 仅在信息零散、不便归入具体字段时作兜底概述。每个对象的字段名只能出现一次,禁止重复键。
6. 数量与顺序:每类最多 50 条,按首次出场顺序排列;某类没有则输出空数组 []。

## 字段说明
- characters:name 角色名;identity 身份职业;appearance 外貌(性别/年龄/造型);personality 性格;relationships 与其他角色的关系;dialogue_intent 台词意图或出场要求;remarks 其他备注;description 信息零散时的兜底概述。
- scenes:name 场景名;location 具体地点;environment_type 内景/外景/实景/虚拟;time 时间段;weather 天气;lighting 光线氛围;visual_style 视觉风格;plot_purpose 该场景承担的剧情功能;actions 场景中的典型动作;remarks 其他备注;description 信息零散时的兜底概述。
- props:name 道具名;category 类别(手持/穿戴/陈设/交通工具等);description 外观;owner 持有角色;function 剧情功能;continuity_notes 连续性要求(磨损、数量、位置变化等);remarks 其他备注。

## 输出格式
只输出一个合法 JSON 对象,不要 Markdown 代码块、注释或任何解释文字。结构如下(值仅为示例,须替换为实际提取结果):
{
  "characters": [
    {"id": "C1", "name": null, "identity": null, "appearance": null, "personality": null, "relationships": null, "dialogue_intent": null, "remarks": null, "description": null}
  ],
  "scenes": [
    {"id": "S1", "name": null, "location": null, "environment_type": null, "time": null, "weather": null, "lighting": null, "visual_style": null, "plot_purpose": null, "actions": null, "remarks": null, "description": null}
  ],
  "props": [
    {"id": "P1", "name": null, "category": null, "description": null, "owner": null, "function": null, "continuity_notes": null, "remarks": null}
  ]
}

【剧本文本】
{{剧本文本}}"""
    }
}
