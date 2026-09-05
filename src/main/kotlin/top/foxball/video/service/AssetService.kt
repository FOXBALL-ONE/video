package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.AiAnalysisException
import top.foxball.video.ai.AiChatCompletionCommand
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.ai.AiUpstreamAuthenticationException
import top.foxball.video.ai.AiUpstreamException
import top.foxball.video.entity.jdbc.Asset
import top.foxball.video.entity.jdbc.AssetIssue
import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.AssetType
import top.foxball.video.entity.jdbc.CharacterProfile
import top.foxball.video.entity.jdbc.EpisodeAsset
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.SceneProfile
import top.foxball.video.entity.jdbc.PropProfile
import top.foxball.video.repository.AssetIssueRepository
import top.foxball.video.repository.AssetRepository
import top.foxball.video.repository.CharacterProfileRepository
import top.foxball.video.repository.EpisodeAssetRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.ScriptDocumentRepository
import top.foxball.video.repository.ScriptVersionRepository
import top.foxball.video.repository.SceneProfileRepository
import top.foxball.video.repository.PropProfileRepository
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.ProjectRepository
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/** 项目资产及其完整性问题服务。 */
@Service
class AssetService(
    private val projectRepository: ProjectRepository,
    private val assetRepository: AssetRepository,
    private val issueRepository: AssetIssueRepository,
    private val episodeRepository: EpisodeRepository,
    private val episodeAssetRepository: EpisodeAssetRepository,
    private val characterProfileRepository: CharacterProfileRepository,
    private val sceneProfileRepository: SceneProfileRepository,
    private val propProfileRepository: PropProfileRepository,
    private val scriptDocumentRepository: ScriptDocumentRepository,
    private val scriptVersionRepository: ScriptVersionRepository,
    private val aiInvocationService: AiInvocationService,
    private val objectMapper: ObjectMapper,
    private val providerConfigRepository: AiModelProviderConfigRepository,
) {
    data class Snapshot(val assets: List<Asset>, val issues: List<AssetIssue>)
    data class ExtractionResult(val assets: List<EpisodeAsset>, val provider: String, val model: String, val requestId: String)
    data class AnalysisAdoptionResult(
        val assets: List<EpisodeAsset>,
        val conflictCount: Int,
        val missingNameCount: Int,
    )

    data class CharacterProfileView(
        val asset: EpisodeAsset,
        val profile: CharacterProfile,
        val voiceAssetName: String? = null,
        val expressionFileIdList: List<String> = emptyList(),
    )

    data class CharacterProfileCommand(
        val name: String? = null,
        val description: String? = null,
        val referenceUrl: String? = null,
        val status: AssetStatus? = null,
        val identityTitle: String? = null,
        val roleTag: String? = null,
        val gender: String? = null,
        val ageStage: String? = null,
        val ageNote: String? = null,
        val isRealPerson: Boolean = false,
        val faceDescription: String? = null,
        val costumeDescription: String? = null,
        val threeViewFrontFileId: String? = null,
        val threeViewSideFileId: String? = null,
        val threeViewBackFileId: String? = null,
        val threeViewMergedFileId: String? = null,
        val expressionFileIds: List<String> = emptyList(),
        val hairstyle: String? = null,
        val costumeRef: String? = null,
        val accessoryRef: String? = null,
        val voiceAssetId: Long? = null,
    )

    data class SceneProfileView(
        val asset: EpisodeAsset,
        val profile: SceneProfile,
    )

    data class PropProfileView(
        val asset: EpisodeAsset,
        val profile: PropProfile,
    )

    data class PropProfileCommand(
        val identityTitle: String? = null,
        val category: String? = null,
        val plotFunction: String? = null,
        val description: String? = null,
        val conceptImageFileId: String? = null,
    )

    data class SceneProfileCommand(
        val identityTitle: String? = null,
        val location: String? = null,
        val time: String? = null,
        val atmosphere: String? = null,
        val description: String? = null,
        val mainImageFileId: String? = null,
        val viewEastFileId: String? = null,
        val viewSouthFileId: String? = null,
        val viewWestFileId: String? = null,
        val viewNorthFileId: String? = null,
        val angleLongShotFileId: String? = null,
        val angleFullShotFileId: String? = null,
        val angleMediumShotFileId: String? = null,
        val angleCloseShotFileId: String? = null,
        val lightMorningFileId: String? = null,
        val lightAfternoonFileId: String? = null,
        val lightNightFileId: String? = null,
    )

    @Transactional(readOnly = true)
    fun load(projectId: Long): Snapshot? {
        if (!projectRepository.existsById(projectId)) return null
        return Snapshot(assetRepository.findAllByProjectIdOrderByUpdatedAtDesc(projectId), issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId))
    }

    @Transactional
    fun create(projectId: Long, name: String, type: AssetType, description: String?, ownerId: Long?, status: AssetStatus): Asset? {
        if (!projectRepository.existsById(projectId)) return null
        return assetRepository.save(Asset(projectId = projectId, name = name, type = type, description = description, ownerId = ownerId, status = status))
    }

    @Transactional
    fun update(projectId: Long, assetId: Long, name: String, type: AssetType, description: String?, status: AssetStatus): Asset? {
        val asset = assetRepository.findById(assetId).orElse(null) ?: return null
        if (asset.projectId != projectId) return null
        asset.name = name
        asset.type = type
        asset.description = description
        asset.status = status
        if (status == AssetStatus.NEEDS_COPYRIGHT) asset.copyrightRequired = true
        return assetRepository.save(asset)
    }

    @Transactional
    fun resolveIssues(projectId: Long): Int {
        if (!projectRepository.existsById(projectId)) return 0
        val issues = issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId)
        issues.forEach { it.resolved = true; it.resolvedAt = LocalDateTime.now() }
        issueRepository.saveAll(issues)
        return issues.size
    }

    @Transactional(readOnly = true)
    fun loadEpisode(projectId: Long, episodeId: Long): List<EpisodeAsset>? {
        if (!projectRepository.existsById(projectId) || episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        return episodeAssetRepository.findAllByProjectIdAndEpisodeIdOrderByUpdatedAtDesc(projectId, episodeId)
    }

    @Transactional
    fun createEpisodeAsset(projectId: Long, episodeId: Long, name: String, type: EpisodeAssetType, description: String?, referenceUrl: String?, notes: String?, status: AssetStatus): EpisodeAsset? {
        if (!projectRepository.existsById(projectId) || episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        return episodeAssetRepository.save(EpisodeAsset(projectId = projectId, episodeId = episodeId, name = name, type = type, description = description, referenceUrl = referenceUrl, notes = notes, status = status))
    }

    @Transactional
    fun updateEpisodeAsset(projectId: Long, episodeId: Long, assetId: Long, name: String, type: EpisodeAssetType, description: String?, referenceUrl: String?, notes: String?, status: AssetStatus): EpisodeAsset? {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return null
        asset.name = name
        asset.type = type
        asset.description = description
        asset.referenceUrl = referenceUrl
        asset.notes = notes
        asset.status = status
        return episodeAssetRepository.save(asset)
    }

    @Transactional
    fun deleteEpisodeAsset(projectId: Long, episodeId: Long, assetId: Long): Boolean {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return false
        episodeAssetRepository.delete(asset)
        return true
    }

    /** 加载单集角色详情；尚无详情记录时返回一份未持久化的默认视图。 */
    @Transactional(readOnly = true)
    fun loadCharacterProfile(projectId: Long, episodeId: Long, assetId: Long): CharacterProfileView? {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return null
        if (asset.type != EpisodeAssetType.CHARACTER) return null
        val profile = characterProfileRepository.findByEpisodeAssetId(assetId) ?: CharacterProfile(projectId = projectId, episodeId = episodeId, episodeAssetId = assetId)
        val voiceAssetName = profile.voiceAssetId?.let { voiceAssetId -> episodeAssetRepository.findById(voiceAssetId).orElse(null)?.name }
        return CharacterProfileView(asset, profile, voiceAssetName, parseExpressionFileIds(profile.expressionFileIds))
    }

    /** 保存单集角色详情；不存在时创建，存在时全量更新可编辑字段。 */
    @Transactional
    fun saveCharacterProfile(projectId: Long, episodeId: Long, assetId: Long, command: CharacterProfileCommand): CharacterProfileView? {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return null
        if (asset.type != EpisodeAssetType.CHARACTER) return null
        val profile = characterProfileRepository.findByEpisodeAssetId(assetId) ?: CharacterProfile(projectId = projectId, episodeId = episodeId, episodeAssetId = assetId)
        command.name?.trim()?.takeIf { it.isNotBlank() }?.take(100)?.let { asset.name = it }
        if (command.description != null) {
            asset.description = command.description.trim().ifBlank { null }?.take(100_000)
        }
        if (command.referenceUrl != null) {
            asset.referenceUrl = command.referenceUrl.trim().ifBlank { null }?.take(1_000)
        }
        command.status?.let { asset.status = it }
        profile.identityTitle = command.identityTitle?.trim()?.ifBlank { null }?.take(200)
        profile.roleTag = command.roleTag?.trim()?.ifBlank { null }?.take(50)
        profile.gender = command.gender?.trim()?.ifBlank { null }?.take(50)
        profile.ageStage = command.ageStage?.trim()?.ifBlank { null }?.take(50)
        profile.ageNote = command.ageNote?.trim()?.ifBlank { null }?.take(50)
        profile.isRealPerson = command.isRealPerson
        profile.faceDescription = command.faceDescription?.trim()?.ifBlank { null }?.take(100_000)
        profile.costumeDescription = command.costumeDescription?.trim()?.ifBlank { null }?.take(100_000)
        profile.threeViewFrontFileId = command.threeViewFrontFileId?.trim()?.ifBlank { null }?.take(64)
        profile.threeViewSideFileId = command.threeViewSideFileId?.trim()?.ifBlank { null }?.take(64)
        profile.threeViewBackFileId = command.threeViewBackFileId?.trim()?.ifBlank { null }?.take(64)
        profile.threeViewMergedFileId = command.threeViewMergedFileId?.trim()?.ifBlank { null }?.take(64)
        profile.expressionFileIds = objectMapper.writeValueAsString(command.expressionFileIds.filter { it.isNotBlank() }.distinct().take(50))
        profile.hairstyle = command.hairstyle?.trim()?.ifBlank { null }?.take(100)
        profile.costumeRef = command.costumeRef?.trim()?.ifBlank { null }?.take(100)
        profile.accessoryRef = command.accessoryRef?.trim()?.ifBlank { null }?.take(100)
        profile.voiceAssetId = command.voiceAssetId
            ?.takeIf {
                it > 0 && episodeAssetRepository.findById(it).orElse(null)?.let { voice ->
                    voice.projectId == projectId && voice.episodeId == episodeId && voice.type == EpisodeAssetType.VOICE
                } == true
            }
        episodeAssetRepository.save(asset)
        val saved = characterProfileRepository.save(profile)
        val voiceAssetName = saved.voiceAssetId?.let { voiceAssetId -> episodeAssetRepository.findById(voiceAssetId).orElse(null)?.name }
        return CharacterProfileView(asset, saved, voiceAssetName, parseExpressionFileIds(saved.expressionFileIds))
    }

    private fun parseExpressionFileIds(json: String): List<String> =
        runCatching { objectMapper.readTree(json) }.getOrNull()
            ?.takeIf { it.isArray }
            ?.mapNotNull { node -> node.textValue()?.takeIf { it.isNotBlank() } }
            ?: emptyList()

    /** 加载单集场景详情；尚无详情记录时返回一份未持久化的默认视图。 */
    @Transactional(readOnly = true)
    fun loadSceneProfile(projectId: Long, episodeId: Long, assetId: Long): SceneProfileView? {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return null
        if (asset.type != EpisodeAssetType.SCENE_BACKGROUND) return null
        val profile = sceneProfileRepository.findByEpisodeAssetId(assetId) ?: SceneProfile(projectId = projectId, episodeId = episodeId, episodeAssetId = assetId)
        return SceneProfileView(asset, profile)
    }

    /** 保存单集场景详情；不存在时创建，存在时全量更新可编辑字段。场景描述直接落在本集资产描述上。 */
    @Transactional
    fun saveSceneProfile(projectId: Long, episodeId: Long, assetId: Long, command: SceneProfileCommand): SceneProfileView? {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return null
        if (asset.type != EpisodeAssetType.SCENE_BACKGROUND) return null
        val profile = sceneProfileRepository.findByEpisodeAssetId(assetId) ?: SceneProfile(projectId = projectId, episodeId = episodeId, episodeAssetId = assetId)
        profile.identityTitle = command.identityTitle?.trim()?.ifBlank { null }?.take(200)
        profile.location = command.location?.trim()?.ifBlank { null }?.take(100)
        profile.timeNote = command.time?.trim()?.ifBlank { null }?.take(100)
        profile.atmosphere = command.atmosphere?.trim()?.ifBlank { null }?.take(200)
        asset.description = command.description?.trim()?.ifBlank { null }?.take(100_000)
        profile.mainImageFileId = command.mainImageFileId?.trim()?.ifBlank { null }?.take(64)
        profile.viewEastFileId = command.viewEastFileId?.trim()?.ifBlank { null }?.take(64)
        profile.viewSouthFileId = command.viewSouthFileId?.trim()?.ifBlank { null }?.take(64)
        profile.viewWestFileId = command.viewWestFileId?.trim()?.ifBlank { null }?.take(64)
        profile.viewNorthFileId = command.viewNorthFileId?.trim()?.ifBlank { null }?.take(64)
        profile.angleLongShotFileId = command.angleLongShotFileId?.trim()?.ifBlank { null }?.take(64)
        profile.angleFullShotFileId = command.angleFullShotFileId?.trim()?.ifBlank { null }?.take(64)
        profile.angleMediumShotFileId = command.angleMediumShotFileId?.trim()?.ifBlank { null }?.take(64)
        profile.angleCloseShotFileId = command.angleCloseShotFileId?.trim()?.ifBlank { null }?.take(64)
        profile.lightMorningFileId = command.lightMorningFileId?.trim()?.ifBlank { null }?.take(64)
        profile.lightAfternoonFileId = command.lightAfternoonFileId?.trim()?.ifBlank { null }?.take(64)
        profile.lightNightFileId = command.lightNightFileId?.trim()?.ifBlank { null }?.take(64)
        episodeAssetRepository.save(asset)
        return SceneProfileView(asset, sceneProfileRepository.save(profile))
    }

    /** 加载单集道具详情；尚无详情记录时返回一份未持久化的默认视图。 */
    @Transactional(readOnly = true)
    fun loadPropProfile(projectId: Long, episodeId: Long, assetId: Long): PropProfileView? {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return null
        if (asset.type != EpisodeAssetType.PROP) return null
        val profile = propProfileRepository.findByEpisodeAssetId(assetId) ?: PropProfile(projectId = projectId, episodeId = episodeId, episodeAssetId = assetId)
        return PropProfileView(asset, profile)
    }

    /** 保存单集道具详情；不存在时创建，存在时全量更新可编辑字段。道具描述直接落在本集资产描述上。 */
    @Transactional
    fun savePropProfile(projectId: Long, episodeId: Long, assetId: Long, command: PropProfileCommand): PropProfileView? {
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(assetId, projectId, episodeId) ?: return null
        if (asset.type != EpisodeAssetType.PROP) return null
        val profile = propProfileRepository.findByEpisodeAssetId(assetId) ?: PropProfile(projectId = projectId, episodeId = episodeId, episodeAssetId = assetId)
        profile.identityTitle = command.identityTitle?.trim()?.ifBlank { null }?.take(200)
        profile.category = command.category?.trim()?.ifBlank { null }?.take(100)
        profile.plotFunction = command.plotFunction?.trim()?.ifBlank { null }?.take(100_000)
        asset.description = command.description?.trim()?.ifBlank { null }?.take(100_000)
        profile.conceptImageFileId = command.conceptImageFileId?.trim()?.ifBlank { null }?.take(64)
        episodeAssetRepository.save(asset)
        return PropProfileView(asset, propProfileRepository.save(profile))
    }

    /** 将已由用户采纳的 AI 解析项创建为剧集资产；既有人工资产不会被覆盖。 */
    @Transactional
    fun adoptAnalysisAssets(
        projectId: Long,
        episodeId: Long,
        type: EpisodeAssetType,
        items: List<JsonNode>,
    ): AnalysisAdoptionResult? {
        require(type in setOf(EpisodeAssetType.CHARACTER, EpisodeAssetType.SCENE_BACKGROUND, EpisodeAssetType.PROP)) { "该资产类型不支持剧本解析" }
        if (!projectRepository.existsById(projectId) || episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val knownKeys = episodeAssetRepository.findAllWithLockByProjectIdAndEpisodeIdOrderByUpdatedAtDesc(projectId, episodeId)
            .associateByTo(mutableMapOf()) { asset ->
                "${asset.type.name}:${normalizeAssetName(asset.name)}"
            }
        val created = mutableListOf<EpisodeAsset>()
        var conflicts = 0
        var missingNames = 0
        items.forEach { item ->
            val name = item.path("name").textValue()?.trim().orEmpty()
            if (name.isBlank()) {
                missingNames += 1
                return@forEach
            }
            val storedName = name.take(100)
            val key = "${type.name}:${normalizeAssetName(storedName)}"
            if (knownKeys.containsKey(key)) {
                conflicts += 1
                return@forEach
            }
            val descriptionFields = when (type) {
                EpisodeAssetType.CHARACTER -> listOf("identity", "appearance", "personality", "description")
                EpisodeAssetType.SCENE_BACKGROUND -> listOf("location", "time", "weather", "lighting", "visual_style", "description")
                EpisodeAssetType.PROP -> listOf("description", "category", "owner", "function")
                else -> emptyList()
            }
            val description = descriptionFields.mapNotNull { field ->
                item.path(field).takeIf { !it.isMissingNode && !it.isNull }?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            }.joinToString("；").ifBlank { null }
            val asset = episodeAssetRepository.save(
                EpisodeAsset(
                    projectId = projectId,
                    episodeId = episodeId,
                    name = storedName,
                    type = type,
                    description = description?.take(100_000),
                    notes = item.toString().take(100_000),
                    status = AssetStatus.DRAFT,
                ),
            )
            knownKeys[key] = asset
            created += asset
        }
        return AnalysisAdoptionResult(created, conflicts, missingNames)
    }

    private fun normalizeAssetName(name: String): String =
        java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** 按资产类别使用指定模型提取当前剧集资产。 */
    @Transactional
    fun extractEpisodeAssets(projectId: Long, episodeId: Long, type: EpisodeAssetType, providerConfigId: Long, model: String): ExtractionResult? {
        if (!projectRepository.existsById(projectId) || episodeRepository.findByIdAndProjectId(episodeId, projectId) == null) return null
        val document = scriptDocumentRepository.findByProjectIdAndEpisodeId(projectId, episodeId) ?: throw IllegalArgumentException("当前剧集尚未导入剧本")
        val versionId = document.currentVersionId ?: throw IllegalArgumentException("当前剧集尚未保存剧本")
        val version = scriptVersionRepository.findById(versionId).orElse(null) ?: throw IllegalArgumentException("当前剧本版本不存在")
        if (version.content.isBlank()) throw IllegalArgumentException("剧本内容不能为空")
        val requestId = UUID.randomUUID().toString()
        val provider = providerConfigRepository.findById(providerConfigId).orElse(null)?.provider?.trim().orEmpty().ifBlank { "unknown" }
        try {
            val result = aiInvocationService.createChatCompletion(
                AiChatCompletionCommand(
                    providerConfigId = providerConfigId,
                    model = model.trim(),
                    messages = listOf(AiChatMessage(AiChatRole.SYSTEM, extractionPrompt(type, version.content))),
                    temperature = 0.1,
                    maxCompletionTokens = 8_000,
                    requestId = requestId,
                ),
            ).block() ?: throw IllegalStateException("模型服务未返回结果")
            val items = parseExtractionItems(result.primaryChoice?.message?.content.orEmpty(), type)
            val assets = items.mapNotNull { node ->
                val name = node.path("name").textValue()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val existing = episodeAssetRepository.findByProjectIdAndEpisodeIdAndNameAndType(projectId, episodeId, name, type)
                val asset = existing ?: EpisodeAsset(projectId = projectId, episodeId = episodeId, name = name, type = type)
                asset.description = listOfNotNull(node.path("description").textValue(), node.path("location").textValue(), node.path("identity").textValue()).joinToString("；").ifBlank { null }
                asset.notes = node.toString().take(100_000)
                episodeAssetRepository.save(asset)
            }
            return ExtractionResult(assets, provider, model.trim(), requestId)
        } catch (exception: AiClientException) {
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
            throw AiAnalysisException(exception.message ?: "模型服务调用失败", errorCode, responseContent, provider, requestId, exception)
        }
    }

    private fun extractionPrompt(type: EpisodeAssetType, content: String): String {
        if (type !in setOf(EpisodeAssetType.CHARACTER, EpisodeAssetType.SCENE_BACKGROUND, EpisodeAssetType.PROP)) {
            throw IllegalArgumentException("该资产类型不支持剧本解析")
        }
        return """你是一名专业的影视剧本资产提取师,负责从 AI 短剧剧本正文中提取结构化资产信息。

## 任务
阅读【剧本文本】,提取三类资产:角色(characters)、场景(scenes)、道具(props),输出一个 json 对象。

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
- characters:name 角色名;identity 身份职业;appearance 外貌(性别/年龄/造型);personality 性格;relationships 与其他角色的关系;dialogue_intent 台词意图或出场要求;remarks 其他备注。
- scenes:name 场景名;location 具体地点;environment_type 内景/外景/实景/虚拟;time 时间段;weather 天气;lighting 光线氛围;visual_style 视觉风格;plot_purpose 该场景承担的剧情功能;actions 场景中的典型动作。
- props:name 道具名;category 类别(手持/穿戴/陈设/交通工具等);description 外观;owner 持有角色;function 剧情功能;continuity_notes 连续性要求(磨损、数量、位置变化等)。

## 输出格式
只输出一个合法 json 对象,不要 Markdown 代码块、注释或任何解释文字。结构如下(值仅为示例,须替换为实际提取结果):
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
$content"""
    }

    private fun parseExtractionItems(raw: String, type: EpisodeAssetType): List<JsonNode> {
        val normalized = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = try { objectMapper.readTree(normalized) } catch (exception: Exception) {
            throw IllegalStateException("模型返回的资产结果不是合法 JSON", exception)
        }
        val items = when {
            root.isArray -> root
            root.path("items").isArray -> root.path("items")
            type == EpisodeAssetType.CHARACTER && root.path("characters").isArray -> root.path("characters")
            type == EpisodeAssetType.SCENE_BACKGROUND && root.path("scenes").isArray -> root.path("scenes")
            type == EpisodeAssetType.PROP && root.path("props").isArray -> root.path("props")
            type == EpisodeAssetType.COSTUME && root.path("costumes").isArray -> root.path("costumes")
            else -> throw IllegalStateException("模型返回的资产结果缺少 items 数组")
        }
        return items.toList().take(50)
    }
}
