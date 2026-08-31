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
import top.foxball.video.entity.jdbc.EpisodeAsset
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.repository.AssetIssueRepository
import top.foxball.video.repository.AssetRepository
import top.foxball.video.repository.EpisodeAssetRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.ScriptDocumentRepository
import top.foxball.video.repository.ScriptVersionRepository
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.ProjectRepository
import java.time.LocalDateTime
import java.util.UUID

/** 项目资产及其完整性问题服务。 */
@Service
class AssetService(
    private val projectRepository: ProjectRepository,
    private val assetRepository: AssetRepository,
    private val issueRepository: AssetIssueRepository,
    private val episodeRepository: EpisodeRepository,
    private val episodeAssetRepository: EpisodeAssetRepository,
    private val scriptDocumentRepository: ScriptDocumentRepository,
    private val scriptVersionRepository: ScriptVersionRepository,
    private val aiInvocationService: AiInvocationService,
    private val objectMapper: ObjectMapper,
    private val providerConfigRepository: AiModelProviderConfigRepository,
) {
    data class Snapshot(val assets: List<Asset>, val issues: List<AssetIssue>)
    data class ExtractionResult(val assets: List<EpisodeAsset>, val provider: String, val model: String, val requestId: String)

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
            val items = parseExtractionItems(result.primaryChoice?.message?.content.orEmpty())
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
        val category = when (type) {
            EpisodeAssetType.CHARACTER -> "人物角色，包含人物名称、身份、外貌、性格、关系和出场要求"
            EpisodeAssetType.SCENE_BACKGROUND -> "场景或背景，包含场景名称、地点、时间、天气、光线、氛围和视觉风格"
            EpisodeAssetType.PROP -> "道具或物件，包含道具名称、类别、外观、持有人、剧情功能和连续性要求"
            EpisodeAssetType.COSTUME -> "服装，包含人物、服装描述、风格、颜色和连续性要求"
            else -> "与剧本制作相关的资产"
        }
        return """你是一名专业的影视剧本资产提取师。请从以下剧本中只提取$category。合并重复实体，不要凭空补充；不明确的字段填 null。只输出合法 JSON，不要 Markdown 或解释文字，格式必须是 {\"items\":[{\"name\":\"名称\",\"description\":\"描述\",\"location\":\"地点\",\"identity\":\"身份\",\"continuity_notes\":\"连续性备注\"}]}。

【剧本文本】
$content"""
    }

    private fun parseExtractionItems(raw: String): List<JsonNode> {
        val normalized = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = try { objectMapper.readTree(normalized) } catch (exception: Exception) {
            throw IllegalStateException("模型返回的资产结果不是合法 JSON", exception)
        }
        val items = when {
            root.isArray -> root
            root.path("items").isArray -> root.path("items")
            root.path("characters").isArray -> root.path("characters")
            root.path("scenes").isArray -> root.path("scenes")
            root.path("props").isArray -> root.path("props")
            root.path("costumes").isArray -> root.path("costumes")
            else -> throw IllegalStateException("模型返回的资产结果缺少 items 数组")
        }
        return items.toList()
    }
}
