package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.CharacterProfile
import top.foxball.video.entity.jdbc.Episode
import top.foxball.video.entity.jdbc.EpisodeAsset
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.PropProfile
import top.foxball.video.entity.jdbc.SceneProfile
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.CharacterProfileRepository
import top.foxball.video.repository.EpisodeAssetRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.PropProfileRepository
import top.foxball.video.repository.SceneProfileRepository
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeoutException

/**
 * 资产清单助手。
 *
 * 助手把当前剧集的资产清单（含角色、场景、道具详情与图片就绪情况）作为模型输入，产出**建议**而不是
 * 直接写入：模型返回的补丁必须由用户在前端逐条确认，再由 [applyPatches] 落地。这样一次模型调用不会
 * 悄悄改坏创作数据，而用户可以只采纳自己认可的部分。
 *
 * 调用本身是同步的：单轮对话通常在一次模型往返内完成，[AI_TIMEOUT_SECONDS] 统一限时，超时不会留下
 * 半成品数据。写入前的字段合法性、资产归属与「字段是否已被他人修改」都在服务端重新校验，不信任模型
 * 通过内置工具自校验的结果。
 */
@Service
class AssetAssistantService(
    private val episodeRepository: EpisodeRepository,
    private val episodeAssetRepository: EpisodeAssetRepository,
    private val characterProfileRepository: CharacterProfileRepository,
    private val sceneProfileRepository: SceneProfileRepository,
    private val propProfileRepository: PropProfileRepository,
    private val providerConfigRepository: AiModelProviderConfigRepository,
    private val taskCenterService: TaskCenterService,
    private val templateCatalog: TemplateCatalogService,
    private val templateRenderer: TemplateRenderer,
    private val templateSchemaValidator: TemplateSchemaValidator,
    private val templateAgentRunner: TemplateAgentRunner,
    private val templateRunAuditService: TemplateRunAuditService,
    private val objectMapper: ObjectMapper,
) {
    /** 单轮对话中的历史消息；只用于给模型提供上下文，不落库。 */
    data class HistoryTurn(val role: String, val content: String)

    data class MessageRequest(
        val providerConfigId: Long,
        val model: String,
        val message: String,
        val history: List<HistoryTurn> = emptyList(),
    )

    /** 一条待采纳的字段修改；[currentValue] 由服务端回填，不来自模型。 */
    data class PatchView(
        val assetId: Long,
        val assetName: String,
        val assetType: String,
        val field: String,
        val fieldLabel: String,
        val value: String,
        val currentValue: String?,
        val reason: String,
        val changed: Boolean,
    )

    data class ContextView(
        val episodeId: Long,
        val episodeTitle: String,
        val episodeNo: Int,
        val assetCount: Int,
        val readyCount: Int,
        val draftCount: Int,
        val completeness: Int,
        val typeCounts: Map<String, Int>,
        val assetListTruncated: Boolean,
        val omittedAssetCount: Int,
    )

    data class Proposal(
        val reply: String,
        val patches: List<PatchView>,
        val warnings: List<String>,
        val suggestions: List<String>,
        val context: ContextView,
        val provider: String,
        val model: String,
        val requestId: String,
        val templateKey: String,
        val templateVersionId: Long?,
        val inputTokens: Int?,
        val outputTokens: Int?,
    )

    data class ApplyItem(val assetId: Long, val field: String, val value: String, val expectedValue: String? = null)

    data class ApplyOutcome(
        val assetId: Long,
        val field: String,
        val status: String,
        val code: String?,
        val message: String?,
        val currentValue: String?,
    )

    data class ApplyResult(val applied: Int, val unchanged: Int, val rejected: Int, val items: List<ApplyOutcome>)

    /** 助手调用失败；错误码供控制器映射 HTTP 状态与前端提示。 */
    class AssetAssistantException(val errorCode: String, message: String) : RuntimeException(message)

    /**
     * 读取当前剧集资产并返回一轮建议。
     *
     * 返回 null 表示项目或剧集不存在（由控制器转换为 404）。
     */
    fun propose(projectId: Long, userId: Long?, episodeId: Long, request: MessageRequest): Proposal? {
        // 只读权限即可提问；写入发生在 applyPatches，那里要求编辑权限。
        if (!taskCenterService.canReadProject(projectId, userId)) return null
        val episode = episodeRepository.findByIdAndProjectId(episodeId, projectId) ?: return null
        require(request.providerConfigId > 0) { "提供商配置不正确" }
        val model = request.model.trim()
        require(model.isNotBlank()) { "模型不能为空" }
        val userRequest = request.message.trim()
        require(userRequest.isNotBlank()) { "请输入要询问或优化的内容" }
        require(userRequest.length <= MAX_USER_REQUEST_LENGTH) { "单条消息不能超过 $MAX_USER_REQUEST_LENGTH 字" }
        val config = providerConfigRepository.findById(request.providerConfigId).orElse(null)
            ?: throw IllegalArgumentException("AI 提供商配置不存在")
        require(config.enabled) { "AI 提供商配置已停用" }

        val assets = episodeAssetRepository.findAllByProjectIdAndEpisodeIdOrderByUpdatedAtDesc(projectId, episodeId)
        require(assets.isNotEmpty()) { "当前剧集还没有资产，请先添加或提取资产" }
        val profiles = loadProfiles(projectId, episodeId)
        val snapshot = buildAssetList(assets, profiles)
        val context = contextOf(episode, assets, snapshot)

        val template = templateCatalog.resolve(TEMPLATE_KEY, projectId, userId, "zh-CN")
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.nanoTime()
        var rendered: TemplateRenderer.RenderedTemplate? = null
        try {
            val variables = linkedMapOf<String, Any?>(
                "episode_context_json" to objectMapper.writeValueAsString(episodeContextJson(context)),
                "asset_list_json" to snapshot.json,
                "field_catalog_json" to objectMapper.writeValueAsString(AssetFieldCatalog.describe()),
                "conversation_json" to objectMapper.writeValueAsString(historyOf(request.history)),
                "user_request" to userRequest,
                "output_language" to "zh-CN",
            )
            val renderedTemplate = templateRenderer.render(template, variables)
            rendered = renderedTemplate
            val outcome = Mono.fromCallable {
                templateAgentRunner.chat(
                    TemplateAgentRunner.ChatRequest(
                        template = template,
                        rendered = renderedTemplate,
                        providerConfigId = request.providerConfigId,
                        model = model,
                        requestId = requestId,
                    ),
                )
            }.subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                .block() ?: throw IllegalStateException("模型服务未返回结果")

            val root = parseRoot(outcome.text)
            templateSchemaValidator.validateOutput(template.version.outputSchemaJson, root)
            val mapped = mapPatches(root, assets, profiles)
            val warnings = mapped.warnings.toMutableList()
            if (snapshot.truncated) {
                warnings += "资产清单较长，助手只读取了精简后的内容，涉及的字段可能不完整"
            }
            if (snapshot.omittedAssetCount > 0) {
                warnings += "资产数量超出单次读取上限，本次未读取 ${snapshot.omittedAssetCount} 个资产"
            }
            recordAudit(template, rendered, requestId, request.providerConfigId, model, "SUCCEEDED", startedAt, outcome.inputTokens, outcome.outputTokens, null, outcome.skillKeys, outcome.mcpServerKeys, outcome.toolCallCount)
            return Proposal(
                reply = root.path("reply").stringValue(null)?.trim()?.take(MAX_REPLY_LENGTH).orEmpty(),
                patches = mapped.patches,
                warnings = warnings.take(MAX_WARNINGS),
                suggestions = suggestionsOf(root),
                context = context,
                provider = config.provider,
                model = model,
                requestId = requestId,
                templateKey = template.template.key,
                templateVersionId = template.version.id,
                inputTokens = outcome.inputTokens ?: renderedTemplate.inputTokens,
                outputTokens = outcome.outputTokens,
            )
        } catch (exception: AiConfigurationException) {
            recordAudit(template, rendered, requestId, request.providerConfigId, model, "FAILED", startedAt, null, null, "AI_CONFIGURATION_ERROR")
            throw AssetAssistantException("AI_CONFIGURATION_ERROR", "AI 提供商配置不可用，请检查模型配置")
        } catch (exception: AiClientException) {
            val code = if (exception is AiRateLimitException) "AI_RATE_LIMITED" else "AI_PROVIDER_ERROR"
            recordAudit(template, rendered, requestId, request.providerConfigId, model, "FAILED", startedAt, null, null, code)
            throw AssetAssistantException(code, if (code == "AI_RATE_LIMITED") "模型服务请求过于频繁，请稍后重试" else "模型服务暂时不可用，请稍后重试")
        } catch (exception: TimeoutException) {
            recordAudit(template, rendered, requestId, request.providerConfigId, model, "FAILED", startedAt, null, null, "AI_TIMEOUT")
            throw AssetAssistantException("AI_TIMEOUT", "模型服务响应超时，请稍后重试或换用更快的模型")
        } catch (exception: AssetAssistantException) {
            throw exception
        } catch (exception: Exception) {
            val code = when {
                exception is TemplateSchemaValidator.SchemaViolationException -> "INVALID_OUTPUT_SCHEMA"
                exception is InvalidAssistantJsonException -> "INVALID_AI_JSON"
                exception is TemplateCatalogService.NotFoundException -> "TEMPLATE_NOT_FOUND"
                exception.cause is TimeoutException -> "AI_TIMEOUT"
                else -> "ASSET_ASSISTANT_FAILED"
            }
            recordAudit(template, rendered, requestId, request.providerConfigId, model, "FAILED", startedAt, null, null, code)
            throw AssetAssistantException(
                code,
                when (code) {
                    "INVALID_OUTPUT_SCHEMA" -> "模型返回结果不符合输出契约：${exception.message ?: "结构不匹配"}"
                    "INVALID_AI_JSON" -> "模型返回结果不是合法 JSON"
                    "TEMPLATE_NOT_FOUND" -> "资产清单助手模板不存在，请检查模板配置"
                    "AI_TIMEOUT" -> "模型服务响应超时，请稍后重试或换用更快的模型"
                    else -> exception.message?.take(500) ?: "资产清单助手调用失败"
                },
            )
        }
    }

    /**
     * 采纳用户选中的修改建议。
     *
     * 每条补丁在写入前重新校验：资产仍属于当前剧集、字段对当前资产类型合法、取值符合长度与枚举约束；
     * 当调用方带上 [ApplyItem.expectedValue] 且与当前库中取值不一致时，该条被拒绝而不是覆盖他人改动。
     */
    @Transactional
    fun applyPatches(projectId: Long, userId: Long?, episodeId: Long, items: List<ApplyItem>): ApplyResult? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        episodeRepository.findByIdAndProjectId(episodeId, projectId) ?: return null
        require(items.isNotEmpty()) { "请至少选择一条修改" }
        require(items.size <= MAX_APPLY_ITEMS) { "单次最多应用 $MAX_APPLY_ITEMS 条修改" }

        val assets = episodeAssetRepository.findAllByProjectIdAndEpisodeIdOrderByUpdatedAtDesc(projectId, episodeId)
            .associateBy { requireNotNull(it.id) }
        val profiles = loadProfiles(projectId, episodeId)
        val characters = profiles.characters.toMutableMap()
        val scenes = profiles.scenes.toMutableMap()
        val props = profiles.props.toMutableMap()
        val originals = profiles
        val touchedAssets = mutableSetOf<Long>()
        val touchedCharacters = mutableSetOf<Long>()
        val touchedScenes = mutableSetOf<Long>()
        val touchedProps = mutableSetOf<Long>()
        val outcomes = mutableListOf<ApplyOutcome>()
        val seen = mutableSetOf<Pair<Long, String>>()

        items.forEach { item ->
            val asset = assets[item.assetId]
            if (asset == null) {
                outcomes += rejected(item, "ASSET_NOT_FOUND", "资产不存在或不属于当前剧集")
                return@forEach
            }
            val field = AssetFieldCatalog.fieldFor(asset.type, item.field.trim())
            if (field == null) {
                outcomes += rejected(item, "FIELD_NOT_ALLOWED", "${asset.name}（${asset.type.name}）不支持字段 ${item.field}")
                return@forEach
            }
            val current = readField(asset, originals, field.key)
            val value = normalizeValue(field, item.value)
            if (value == null) {
                outcomes += rejected(item, "INVALID_VALUE", "取值不符合字段约束", current)
                return@forEach
            }
            if (item.expectedValue != null && item.expectedValue != current) {
                outcomes += rejected(item, "ASSET_FIELD_CHANGED", "该字段已被其他操作修改，请重新读取资产后再试", current)
                return@forEach
            }
            // 只有通过校验的补丁才占用 (资产, 字段) 位置，被拒绝的补丁不会挡掉同一批里后续的正确取值。
            if (!seen.add(item.assetId to field.key)) {
                outcomes += rejected(item, "DUPLICATE_PATCH", "同一字段在一次提交中重复出现")
                return@forEach
            }
            if (value == current) {
                outcomes += ApplyOutcome(item.assetId, field.key, "UNCHANGED", "UNCHANGED", "字段值与当前一致，未写入", current)
                return@forEach
            }
            val assetId = item.assetId
            when {
                field.key in COMMON_KEYS -> touchedAssets += assetId
                asset.type == EpisodeAssetType.CHARACTER -> touchedCharacters += assetId
                asset.type == EpisodeAssetType.SCENE_BACKGROUND -> touchedScenes += assetId
                asset.type == EpisodeAssetType.PROP -> touchedProps += assetId
            }
            writeField(asset, characters, scenes, props, field.key, value)
            outcomes += ApplyOutcome(assetId, field.key, "APPLIED", null, null, current)
        }

        touchedAssets.forEach { assetId -> assets[assetId]?.let(episodeAssetRepository::save) }
        touchedCharacters.forEach { assetId -> characters[assetId]?.let(characterProfileRepository::save) }
        touchedScenes.forEach { assetId -> scenes[assetId]?.let(sceneProfileRepository::save) }
        touchedProps.forEach { assetId -> props[assetId]?.let(propProfileRepository::save) }

        return ApplyResult(
            applied = outcomes.count { it.status == "APPLIED" },
            unchanged = outcomes.count { it.status == "UNCHANGED" },
            rejected = outcomes.count { it.status == "REJECTED" },
            items = outcomes,
        )
    }

    private fun rejected(item: ApplyItem, code: String, message: String, currentValue: String? = null): ApplyOutcome =
        ApplyOutcome(item.assetId, item.field, "REJECTED", code, message, currentValue)

    private fun normalizeValue(field: AssetFieldCatalog.Field, raw: String): String? {
        val value = raw.trim()
        if (field.allowedValues.isNotEmpty()) {
            return field.allowedValues.firstOrNull { it.equals(value, ignoreCase = true) }
        }
        if (value.length > field.maxLength) return value.take(field.maxLength)
        return value
    }

    /** 读取字段当前取值；资产或字段尚未有记录时返回空串。 */
    private fun readField(asset: EpisodeAsset, profiles: AssetProfiles, key: String): String {
        val assetId = requireNotNull(asset.id)
        val direct = when (key) {
            "name" -> asset.name
            "description" -> asset.description
            "reference_url" -> asset.referenceUrl
            "notes" -> asset.notes
            "status" -> asset.status.name
            else -> null
        }
        if (direct != null) return direct
        return when (asset.type) {
            EpisodeAssetType.CHARACTER -> profiles.characters[assetId]?.let { characterField(it, key) }
            EpisodeAssetType.SCENE_BACKGROUND -> profiles.scenes[assetId]?.let { sceneField(it, key) }
            EpisodeAssetType.PROP -> profiles.props[assetId]?.let { propField(it, key) }
            else -> null
        }.orEmpty()
    }

    private fun characterField(profile: CharacterProfile, key: String): String? = when (key) {
        "identity_title" -> profile.identityTitle
        "role_tag" -> profile.roleTag
        "gender" -> profile.gender
        "age_stage" -> profile.ageStage
        "age_note" -> profile.ageNote
        "face_description" -> profile.faceDescription
        "costume_description" -> profile.costumeDescription
        "hairstyle" -> profile.hairstyle
        "costume_ref" -> profile.costumeRef
        "accessory_ref" -> profile.accessoryRef
        else -> null
    }

    private fun sceneField(profile: SceneProfile, key: String): String? = when (key) {
        "identity_title" -> profile.identityTitle
        "location" -> profile.location
        "time" -> profile.timeNote
        "atmosphere" -> profile.atmosphere
        else -> null
    }

    private fun propField(profile: PropProfile, key: String): String? = when (key) {
        "identity_title" -> profile.identityTitle
        "category" -> profile.category
        "plot_function" -> profile.plotFunction
        else -> null
    }

    /** 把一条补丁写入内存实体；持久化由调用方按资产维度统一提交。 */
    private fun writeField(
        asset: EpisodeAsset,
        characters: MutableMap<Long, CharacterProfile>,
        scenes: MutableMap<Long, SceneProfile>,
        props: MutableMap<Long, PropProfile>,
        key: String,
        value: String,
    ) {
        val assetId = requireNotNull(asset.id)
        when (key) {
            "name" -> asset.name = value.take(100)
            "description" -> asset.description = value.ifBlank { null }
            "reference_url" -> asset.referenceUrl = value.ifBlank { null }
            "notes" -> asset.notes = value.ifBlank { null }
            "status" -> asset.status = AssetStatus.entries.firstOrNull { it.name == value } ?: asset.status
            else -> when (asset.type) {
                EpisodeAssetType.CHARACTER -> {
                    val profile = characters.getOrPut(assetId) { CharacterProfile(projectId = asset.projectId, episodeId = asset.episodeId, episodeAssetId = assetId) }
                    when (key) {
                        "identity_title" -> profile.identityTitle = value.ifBlank { null }
                        "role_tag" -> profile.roleTag = value.ifBlank { null }
                        "gender" -> profile.gender = value.ifBlank { null }
                        "age_stage" -> profile.ageStage = value.ifBlank { null }
                        "age_note" -> profile.ageNote = value.ifBlank { null }
                        "face_description" -> profile.faceDescription = value.ifBlank { null }
                        "costume_description" -> profile.costumeDescription = value.ifBlank { null }
                        "hairstyle" -> profile.hairstyle = value.ifBlank { null }
                        "costume_ref" -> profile.costumeRef = value.ifBlank { null }
                        "accessory_ref" -> profile.accessoryRef = value.ifBlank { null }
                    }
                }
                EpisodeAssetType.SCENE_BACKGROUND -> {
                    val profile = scenes.getOrPut(assetId) { SceneProfile(projectId = asset.projectId, episodeId = asset.episodeId, episodeAssetId = assetId) }
                    when (key) {
                        "identity_title" -> profile.identityTitle = value.ifBlank { null }
                        "location" -> profile.location = value.ifBlank { null }
                        "time" -> profile.timeNote = value.ifBlank { null }
                        "atmosphere" -> profile.atmosphere = value.ifBlank { null }
                    }
                }
                EpisodeAssetType.PROP -> {
                    val profile = props.getOrPut(assetId) { PropProfile(projectId = asset.projectId, episodeId = asset.episodeId, episodeAssetId = assetId) }
                    when (key) {
                        "identity_title" -> profile.identityTitle = value.ifBlank { null }
                        "category" -> profile.category = value.ifBlank { null }
                        "plot_function" -> profile.plotFunction = value.ifBlank { null }
                    }
                }
                else -> Unit
            }
        }
    }

    private class AssetProfiles(
        val characters: Map<Long, CharacterProfile>,
        val scenes: Map<Long, SceneProfile>,
        val props: Map<Long, PropProfile>,
    )

    private fun loadProfiles(projectId: Long, episodeId: Long): AssetProfiles = AssetProfiles(
        characters = characterProfileRepository.findAllByProjectIdAndEpisodeId(projectId, episodeId).associateBy { it.episodeAssetId },
        scenes = sceneProfileRepository.findAllByProjectIdAndEpisodeId(projectId, episodeId).associateBy { it.episodeAssetId },
        props = propProfileRepository.findAllByProjectIdAndEpisodeId(projectId, episodeId).associateBy { it.episodeAssetId },
    )

    /** 资产清单快照的构建结果；超限时记录被省略的资产数量。 */
    private data class Snapshot(val json: String, val truncated: Boolean, val omittedAssetCount: Int)

    /**
     * 序列化资产清单，并按文本预算逐级降低细节。
     *
     * 模板渲染有 token 预算，超限会直接拒绝执行；因此这里先按完整细节构建，超出预算就缩短长文本，
     * 仍然超限再按重要性保留资产并记录省略数量，让模型知道自己看不到全部资产。
     */
    private fun buildAssetList(assets: List<EpisodeAsset>, profiles: AssetProfiles): Snapshot {
        DETAIL_TIERS.forEachIndexed { index, tier ->
            val json = assetListNode(assets, profiles, tier.first, tier.second).toString()
            if (json.length <= MAX_ASSET_LIST_CHARS) {
                return Snapshot(json, index > 0, 0)
            }
        }
        val kept = assets.sortedBy { TYPE_PRIORITY[it.type] ?: Int.MAX_VALUE }.take(MAX_ASSETS_WHEN_OVERSIZED)
        val json = assetListNode(kept, profiles, MINIMAL_TEXT_LENGTH, 0).toString()
        return Snapshot(json, true, assets.size - kept.size)
    }

    private fun assetListNode(
        assets: List<EpisodeAsset>,
        profiles: AssetProfiles,
        textLimit: Int,
        noteLimit: Int,
    ): ArrayNode {
        val list = objectMapper.createArrayNode()
        assets.forEach { asset ->
            val assetId = requireNotNull(asset.id)
            val node = objectMapper.createObjectNode()
            node.put("id", assetId)
            node.put("name", asset.name)
            node.put("type", asset.type.name)
            node.put("status", asset.status.name)
            node.put("description", truncate(asset.description, textLimit))
            if (noteLimit > 0) node.put("notes", truncate(asset.notes, noteLimit))
            node.put("reference_url", asset.referenceUrl)
            when (asset.type) {
                EpisodeAssetType.CHARACTER -> {
                    val profile = profiles.characters[assetId]
                    putProfileFields(node, profile?.let { characterField(it, "identity_title") }, profile?.let { characterField(it, "role_tag") }, profile?.let { characterField(it, "gender") }, profile?.let { characterField(it, "age_stage") }, profile?.let { characterField(it, "age_note") })
                    node.put("face_description", truncate(profile?.faceDescription, textLimit))
                    node.put("costume_description", truncate(profile?.costumeDescription, textLimit))
                    node.put("hairstyle", profile?.hairstyle)
                    node.put("costume_ref", profile?.costumeRef)
                    node.put("accessory_ref", profile?.accessoryRef)
                    node.set("images", objectMapper.createObjectNode().apply {
                        put("three_view_front", !profile?.threeViewFrontFileId.isNullOrBlank())
                        put("three_view_side", !profile?.threeViewSideFileId.isNullOrBlank())
                        put("three_view_back", !profile?.threeViewBackFileId.isNullOrBlank())
                        put("three_view_merged", !profile?.threeViewMergedFileId.isNullOrBlank())
                        put("expression_count", expressionCount(profile?.expressionFileIds))
                        put("voice_bound", (profile?.voiceAssetId ?: 0) > 0)
                    })
                }
                EpisodeAssetType.SCENE_BACKGROUND -> {
                    val profile = profiles.scenes[assetId]
                    node.put("identity_title", profile?.identityTitle)
                    node.put("location", profile?.location)
                    node.put("time", profile?.timeNote)
                    node.put("atmosphere", profile?.atmosphere)
                    node.set("images", objectMapper.createObjectNode().apply {
                        put("main", !profile?.mainImageFileId.isNullOrBlank())
                        put("orthographic_view_count", listOf(profile?.viewEastFileId, profile?.viewSouthFileId, profile?.viewWestFileId, profile?.viewNorthFileId).count { !it.isNullOrBlank() })
                        put("angle_count", listOf(profile?.angleLongShotFileId, profile?.angleFullShotFileId, profile?.angleMediumShotFileId, profile?.angleCloseShotFileId).count { !it.isNullOrBlank() })
                        put("light_count", listOf(profile?.lightMorningFileId, profile?.lightAfternoonFileId, profile?.lightNightFileId).count { !it.isNullOrBlank() })
                    })
                }
                EpisodeAssetType.PROP -> {
                    val profile = profiles.props[assetId]
                    node.put("identity_title", profile?.identityTitle)
                    node.put("category", profile?.category)
                    node.put("plot_function", truncate(profile?.plotFunction, textLimit))
                    node.set("images", objectMapper.createObjectNode().apply {
                        put("concept", !profile?.conceptImageFileId.isNullOrBlank())
                    })
                }
                else -> Unit
            }
            list.add(node)
        }
        return list
    }

    private fun putProfileFields(node: ObjectNode, identityTitle: String?, roleTag: String?, gender: String?, ageStage: String?, ageNote: String?) {
        node.put("identity_title", identityTitle)
        node.put("role_tag", roleTag)
        node.put("gender", gender)
        node.put("age_stage", ageStage)
        node.put("age_note", ageNote)
    }

    private fun expressionCount(json: String?): Int =
        runCatching { objectMapper.readTree(json.orEmpty()) }.getOrNull()?.takeIf { it.isArray }?.size() ?: 0

    private fun truncate(value: String?, limit: Int): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (text.length <= limit) text else text.take(limit) + "…（已截断）"
    }

    private fun contextOf(episode: Episode, assets: List<EpisodeAsset>, snapshot: Snapshot): ContextView {
        val ready = assets.count { it.status == AssetStatus.READY || it.status == AssetStatus.LOCKED }
        return ContextView(
            episodeId = requireNotNull(episode.id),
            episodeTitle = episode.title,
            episodeNo = episode.episodeNo,
            assetCount = assets.size,
            readyCount = ready,
            draftCount = assets.count { it.status == AssetStatus.DRAFT || it.status == AssetStatus.TO_GENERATE },
            completeness = ready * 100 / assets.size,
            typeCounts = assets.groupingBy { it.type.name }.eachCount(),
            assetListTruncated = snapshot.truncated,
            omittedAssetCount = snapshot.omittedAssetCount,
        )
    }

    private fun episodeContextJson(context: ContextView): Map<String, Any?> = linkedMapOf(
        "episode_id" to context.episodeId,
        "episode_no" to context.episodeNo,
        "title" to context.episodeTitle,
        "asset_count" to context.assetCount,
        "ready_count" to context.readyCount,
        "draft_count" to context.draftCount,
        "type_counts" to context.typeCounts,
        "asset_list_truncated" to context.assetListTruncated,
        "omitted_asset_count" to context.omittedAssetCount,
    )

    /** 只保留最近的对话轮次，并按字符预算从最旧一轮开始丢弃。 */
    private fun historyOf(history: List<HistoryTurn>): List<Map<String, String>> {
        val turns = history.mapNotNull { turn ->
            val content = turn.content.trim().take(MAX_HISTORY_TURN_LENGTH)
            if (content.isBlank()) return@mapNotNull null
            val role = if (turn.role.trim().equals("assistant", ignoreCase = true)) "assistant" else "user"
            mapOf("role" to role, "content" to content)
        }.takeLast(MAX_HISTORY_TURNS)
        val kept = ArrayDeque<Map<String, String>>()
        var total = 0
        turns.asReversed().forEach { turn ->
            val length = turn.getValue("content").length
            if (total + length <= MAX_HISTORY_CHARS) {
                kept.addFirst(turn)
                total += length
            }
        }
        return kept.toList()
    }

    private fun suggestionsOf(root: JsonNode): List<String> {
        val suggestions = root.path("suggestions").takeIf { it.isArray }
            ?.mapNotNull { node -> node.stringValue(null)?.trim()?.take(200)?.takeIf { it.isNotBlank() } }
            .orEmpty()
        return if (suggestions.isEmpty()) DEFAULT_SUGGESTIONS else suggestions.take(MAX_SUGGESTIONS)
    }

    private data class MappedPatches(val patches: List<PatchView>, val warnings: List<String>)

    /** 把模型返回的补丁映射为可审阅视图，并回填服务端持有的当前取值。 */
    private fun mapPatches(root: JsonNode, assets: List<EpisodeAsset>, profiles: AssetProfiles): MappedPatches {
        val warnings = root.path("warnings").takeIf { it.isArray }
            ?.mapNotNull { node -> node.stringValue(null)?.trim()?.take(1000)?.takeIf { it.isNotBlank() } }
            ?.take(MAX_WARNINGS)
            ?.toMutableList() ?: mutableListOf()
        val byId = assets.associateBy { requireNotNull(it.id) }
        val patches = mutableListOf<PatchView>()
        val seen = mutableSetOf<Pair<Long, String>>()
        val rawPatches = root.path("patches").takeIf { it.isArray } ?: return MappedPatches(emptyList(), warnings)
        rawPatches.forEach { node ->
            val assetId = node.path("asset_id").takeIf { it.isIntegralNumber }?.longValue()
            val fieldKey = node.path("field").stringValue(null)?.trim().orEmpty()
            if (assetId == null || fieldKey.isBlank()) return@forEach
            val asset = byId[assetId]
            if (asset == null) {
                warnings += "已忽略一条指向不存在资产的建议（asset_id=$assetId）"
                return@forEach
            }
            val field = AssetFieldCatalog.fieldFor(asset.type, fieldKey)
            if (field == null) {
                warnings += "已忽略「${asset.name}」的未知字段：$fieldKey"
                return@forEach
            }
            if (!seen.add(assetId to field.key)) return@forEach
            val value = node.path("value").stringValue(null)?.trim()
            if (value == null || value.isBlank()) {
                warnings += "已忽略「${asset.name}」的 ${field.label}：建议值为空"
                return@forEach
            }
            val normalized = normalizeValue(field, value)
            if (normalized == null) {
                warnings += "已忽略「${asset.name}」的 ${field.label}：取值不合法"
                return@forEach
            }
            val current = readField(asset, profiles, field.key)
            patches += PatchView(
                assetId = assetId,
                assetName = asset.name,
                assetType = asset.type.name,
                field = field.key,
                fieldLabel = field.label,
                value = normalized,
                currentValue = current,
                reason = node.path("reason").stringValue(null)?.trim()?.take(1000).orEmpty(),
                changed = normalized != current,
            )
        }
        val unchanged = patches.count { !it.changed }
        if (unchanged > 0) warnings += "有 $unchanged 条建议与当前取值相同，可直接忽略"
        return MappedPatches(patches.take(MAX_PATCHES), warnings.take(MAX_WARNINGS))
    }

    private fun parseRoot(raw: String): JsonNode {
        val normalized = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (normalized.isBlank()) throw InvalidAssistantJsonException("模型返回结果为空")
        return runCatching { objectMapper.readTree(normalized) }.getOrElse { throw InvalidAssistantJsonException("模型返回结果不是合法 JSON") }
    }

    /** 写入脱敏的运行审计；这里没有任务记录，task_id 留空。 */
    private fun recordAudit(
        template: TemplateCatalogService.ResolvedTemplate,
        rendered: TemplateRenderer.RenderedTemplate?,
        requestId: String,
        providerConfigId: Long,
        model: String,
        status: String,
        startedAt: Long,
        inputTokens: Int?,
        outputTokens: Int?,
        validationErrorCode: String?,
        skillKeys: List<String> = emptyList(),
        mcpServerKeys: List<String> = emptyList(),
        toolCallCount: Int = 0,
    ) {
        runCatching {
            templateRunAuditService.record(
                TemplateRunRecord(
                    templateKey = template.template.key,
                    templateVersionId = requireNotNull(template.version.id),
                    taskId = null,
                    invocationId = requestId,
                    providerConfigId = providerConfigId,
                    model = model,
                    status = status,
                    latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                    inputTokens = inputTokens ?: rendered?.inputTokens,
                    outputTokens = outputTokens,
                    templateHash = rendered?.templateHash ?: template.version.contentHash,
                    variablesHash = rendered?.variablesHash,
                    renderedInputHash = rendered?.renderedInputHash,
                    skillKeys = skillKeys.ifEmpty { templateSchemaValidator.skillKeys(template.version.skillsJson) },
                    mcpServerKeys = mcpServerKeys.ifEmpty { templateSchemaValidator.mcpServerKeys(template.version.mcpServersJson) },
                    toolCallCount = toolCallCount,
                    validationErrorCode = validationErrorCode,
                ),
            )
        }
    }

    private class InvalidAssistantJsonException(message: String) : RuntimeException(message)

    private companion object {
        const val TEMPLATE_KEY = AssetAssistantTemplate.TEMPLATE_KEY
        const val AI_TIMEOUT_SECONDS = 150L
        const val MAX_USER_REQUEST_LENGTH = 8000
        const val MAX_REPLY_LENGTH = 8000
        const val MAX_PATCHES = 50
        const val MAX_APPLY_ITEMS = 50
        const val MAX_WARNINGS = 30
        const val MAX_SUGGESTIONS = 4
        const val MAX_HISTORY_TURNS = 6
        const val MAX_HISTORY_TURN_LENGTH = 1500
        const val MAX_HISTORY_CHARS = 18_000
        const val MAX_ASSET_LIST_CHARS = 150_000
        const val MAX_ASSETS_WHEN_OVERSIZED = 300
        const val MINIMAL_TEXT_LENGTH = 300

        /** 资产清单的细节档位：长文本上限与备注上限，从完整到精简。 */
        val DETAIL_TIERS = listOf(4000 to 800, 1200 to 300, 400 to 0)

        val COMMON_KEYS = AssetFieldCatalog.COMMON.map { it.key }.toSet()

        /** 清单超长时的保留顺序：角色与场景比声音类资产更需要助手关注。 */
        val TYPE_PRIORITY = mapOf(
            EpisodeAssetType.CHARACTER to 0,
            EpisodeAssetType.SCENE_BACKGROUND to 1,
            EpisodeAssetType.PROP to 2,
            EpisodeAssetType.COSTUME to 3,
        )

        val DEFAULT_SUGGESTIONS = listOf("检查本集资产缺口", "补全角色一致性描述", "整理未就绪资产")
    }
}
