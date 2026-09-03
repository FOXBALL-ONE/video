package top.foxball.video.service

import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory
import top.foxball.video.ai.AiChatCompletionCommand
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.ai.AiUpstreamAuthenticationException
import top.foxball.video.ai.AiUpstreamException
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.repository.AiModelProviderConfigRepository
import java.util.UUID

/** 仅调用 AI 并规范化结果的剧本资产解析服务；不写入剧本或资产表。 */
@Service
class ScriptAssetAnalysisService(
    private val aiInvocationService: AiInvocationService,
    private val providerConfigRepository: AiModelProviderConfigRepository,
    private val objectMapper: ObjectMapper,
) {
    private companion object {
        const val MAX_ITEMS_PER_TYPE = 50
        const val MAX_FIELD_TEXT_LENGTH = 10_000
        const val MAX_RESULT_BYTES = 2_000_000
    }

    data class TypeResult(
        val assetType: EpisodeAssetType,
        val providerConfigId: Long,
        val model: String,
        val provider: String,
        val requestId: String,
        val items: List<JsonNode> = emptyList(),
        val errorCode: String? = null,
        val errorMessage: String? = null,
    ) {
        val succeeded: Boolean get() = errorCode == null
    }

    /** 在创建任务前校验配置，避免明知不可用的配置进入队列。 */
    fun validateConfiguration(providerConfigId: Long, model: String) {
        require(providerConfigId > 0) { "提供商配置不正确" }
        require(model.trim().isNotBlank()) { "模型不能为空" }
        val config = providerConfigRepository.findById(providerConfigId).orElse(null)
            ?: throw IllegalArgumentException("AI 提供商配置不存在")
        require(config.enabled) { "AI 提供商配置已停用" }
    }

    fun analyze(
        assetType: EpisodeAssetType,
        providerConfigId: Long,
        model: String,
        sourceContent: String,
    ): TypeResult {
        val requestId = UUID.randomUUID().toString()
        val provider = providerConfigRepository.findById(providerConfigId).orElse(null)?.provider?.trim().orEmpty().ifBlank { "unknown" }
        return try {
            val completion = aiInvocationService.createChatCompletion(
                AiChatCompletionCommand(
                    providerConfigId = providerConfigId,
                    model = model.trim(),
                    messages = listOf(AiChatMessage(AiChatRole.SYSTEM, extractionPrompt(assetType, sourceContent))),
                    temperature = 0.1,
                    maxCompletionTokens = 8_000,
                    requestId = requestId,
                ),
            ).block() ?: throw IllegalStateException("模型服务未返回结果")
            val items = parseItems(completion.primaryChoice?.message?.content.orEmpty(), assetType)
                .map { normalizeItem(assetType, it) }
            if (objectMapper.writeValueAsBytes(items).size > MAX_RESULT_BYTES) throw ResultTooLargeException()
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, items)
        } catch (exception: AiConfigurationException) {
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = "AI_CONFIGURATION_ERROR", errorMessage = "AI 提供商配置不可用")
        } catch (exception: AiClientException) {
            val code = when (exception) {
                is AiUpstreamException, is AiUpstreamAuthenticationException -> "AI_PROVIDER_ERROR"
                is AiRateLimitException -> "AI_RATE_LIMITED"
                else -> "AI_PROVIDER_ERROR"
            }
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = code, errorMessage = when (code) {
                "AI_RATE_LIMITED" -> "模型服务请求过于频繁，请稍后重试"
                else -> "模型服务暂时不可用，请稍后重试"
            })
        } catch (exception: Exception) {
            val code = when (exception) {
                is InvalidAiJsonException -> "INVALID_AI_JSON"
                is ResultTooLargeException -> "RESULT_TOO_LARGE"
                else -> "AI_ANALYSIS_FAILED"
            }
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = code, errorMessage = when (code) {
                "INVALID_AI_JSON" -> "模型返回结果不是合法 JSON"
                "RESULT_TOO_LARGE" -> "模型返回结果超过大小限制"
                else -> "剧本资产解析失败"
            })
        }
    }

    private fun extractionPrompt(type: EpisodeAssetType, content: String): String {
        if (type !in setOf(EpisodeAssetType.CHARACTER, EpisodeAssetType.SCENE_BACKGROUND, EpisodeAssetType.PROP)) {
            throw IllegalArgumentException("该资产类型不支持剧本解析")
        }
        return """你是一名专业的影视剧本资产提取师,负责从 AI 短剧剧本正文中提取结构化资产信息。

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
- characters:name 角色名;identity 身份职业;appearance 外貌(性别/年龄/造型);personality 性格;relationships 与其他角色的关系;dialogue_intent 台词意图或出场要求;remarks 其他备注。
- scenes:name 场景名;location 具体地点;environment_type 内景/外景/实景/虚拟;time 时间段;weather 天气;lighting 光线氛围;visual_style 视觉风格;plot_purpose 该场景承担的剧情功能;actions 场景中的典型动作。
- props:name 道具名;category 类别(手持/穿戴/陈设/交通工具等);description 外观;owner 持有角色;function 剧情功能;continuity_notes 连续性要求(磨损、数量、位置变化等)。

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
$content"""
    }

    private fun parseItems(raw: String, type: EpisodeAssetType): List<JsonNode> {
        val normalized = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (normalized.isBlank()) throw InvalidAiJsonException("模型返回的资产结果为空")
        val root = try {
            objectMapper.readTree(normalized)
        } catch (exception: Exception) {
            throw InvalidAiJsonException("模型返回的资产结果不是合法 JSON")
        }
        if (root == null || root.isNull) throw InvalidAiJsonException("模型返回的资产结果为空")
        val items = when {
            root.isArray -> root
            root.path("items").isArray -> root.path("items")
            type == EpisodeAssetType.CHARACTER && root.path("characters").isArray -> root.path("characters")
            type == EpisodeAssetType.SCENE_BACKGROUND && root.path("scenes").isArray -> root.path("scenes")
            type == EpisodeAssetType.PROP && root.path("props").isArray -> root.path("props")
            else -> throw InvalidAiJsonException("模型返回的资产结果缺少 items 数组")
        }
        return items.toList().take(MAX_ITEMS_PER_TYPE)
    }

    private fun normalizeItem(type: EpisodeAssetType, source: JsonNode): JsonNode {
        val fields = when (type) {
            EpisodeAssetType.CHARACTER -> listOf("id", "name", "identity", "appearance", "personality", "relationships", "dialogue_intent", "remarks", "description")
            EpisodeAssetType.SCENE_BACKGROUND -> listOf("id", "name", "location", "environment_type", "time", "weather", "lighting", "visual_style", "plot_purpose", "actions", "remarks", "description")
            EpisodeAssetType.PROP -> listOf("id", "name", "category", "description", "owner", "function", "continuity_notes", "remarks")
            else -> emptyList()
        }
        val normalized = objectMapper.createObjectNode()
        fields.forEach { field ->
            val value = source.path(field)
            if (!value.isMissingNode && !value.isNull) {
                normalizeValue(value)?.let { normalized.set(field, it) }
            }
        }
        return normalized
    }

    private fun normalizeValue(value: JsonNode, depth: Int = 0): JsonNode? {
        if (depth > 4) return null
        return when {
            value.isTextual -> JsonNodeFactory.instance.textNode(value.textValue().trim().take(MAX_FIELD_TEXT_LENGTH))
            value.isNumber -> JsonNodeFactory.instance.numberNode(value.decimalValue())
            value.isBoolean -> JsonNodeFactory.instance.booleanNode(value.booleanValue())
            value.isArray -> objectMapper.createArrayNode().also { array ->
                value.take(50).mapNotNull { normalizeValue(it, depth + 1) }.forEach(array::add)
            }
            value.isObject -> objectMapper.createObjectNode().also { normalizedObject ->
                value.properties().asSequence().take(30).forEach { (key, child) ->
                    normalizeValue(child, depth + 1)?.let { normalizedObject.set(key, it) }
                }
            }
            else -> null
        }
    }

    private class InvalidAiJsonException(message: String) : IllegalStateException(message)
    private class ResultTooLargeException : IllegalStateException("模型返回的资产结果超过大小限制")
}
