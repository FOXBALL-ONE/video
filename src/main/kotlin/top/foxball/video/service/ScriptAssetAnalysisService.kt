package top.foxball.video.service

import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory
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
    private val promptCatalog: PromptCatalogService,
    private val promptRenderer: PromptRenderer,
    private val promptSchemaValidator: PromptSchemaValidator,
    private val promptRunAuditService: PromptRunAuditService,
    private val promptInvocationBuilder: PromptInvocationBuilder,
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
        promptVersionId: Long? = null,
        promptHash: String? = null,
        episodeId: Long? = null,
        taskId: Long? = null,
        attempt: Int = 1,
    ): TypeResult {
        val requestId = UUID.randomUUID().toString()
        val provider = providerConfigRepository.findById(providerConfigId).orElse(null)?.provider?.trim().orEmpty().ifBlank { "unknown" }
        var resolvedPrompt: PromptCatalogService.ResolvedPrompt? = null
        var rendered: PromptRenderer.RenderedPrompt? = null
        val startedAt = System.nanoTime()
        return try {
            val prompt = promptVersionId?.let { promptCatalog.resolveExecutableVersion(it) }
                ?: promptCatalog.resolve("script_analysis", null, null)
            resolvedPrompt = prompt
            require(promptHash.isNullOrBlank() || promptHash.equals(prompt.version.contentHash, ignoreCase = true)) { "提示词版本内容已变化" }
            rendered = promptRenderer.render(
                prompt,
                mapOf(
                    "script_content" to sourceContent,
                    "asset_type" to assetType.name,
                    "episode_context_json" to objectMapper.writeValueAsString(mapOf("episode_id" to episodeId, "asset_type" to assetType.name)),
                    "output_language" to "zh-CN",
                    "detail_level" to "STANDARD",
                ),
            )
            val completion = aiInvocationService.createChatCompletion(
                promptInvocationBuilder.build(prompt, rendered, providerConfigId, model, requestId),
            ).block() ?: throw IllegalStateException("模型服务未返回结果")
            val root = parseRoot(completion.primaryChoice?.message?.content.orEmpty())
            promptSchemaValidator.validateOutput(prompt.version.outputSchemaJson, root)
            val items = parseItems(root, assetType)
                .map { normalizeItem(assetType, it) }
            if (objectMapper.writeValueAsBytes(items).size > MAX_RESULT_BYTES) throw ResultTooLargeException()
            recordAudit(resolvedPrompt, rendered, taskId, requestId, providerConfigId, model, "SUCCEEDED", attempt, startedAt, completion.usage?.inputTokens, completion.usage?.outputTokens, null)
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, items)
        } catch (exception: AiConfigurationException) {
            recordAudit(resolvedPrompt, rendered, taskId, requestId, providerConfigId, model, "FAILED", attempt, startedAt, null, null, "AI_CONFIGURATION_ERROR")
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = "AI_CONFIGURATION_ERROR", errorMessage = "AI 提供商配置不可用")
        } catch (exception: AiClientException) {
            val code = when (exception) {
                is AiUpstreamException, is AiUpstreamAuthenticationException -> "AI_PROVIDER_ERROR"
                is AiRateLimitException -> "AI_RATE_LIMITED"
                else -> "AI_PROVIDER_ERROR"
            }
            recordAudit(resolvedPrompt, rendered, taskId, requestId, providerConfigId, model, "FAILED", attempt, startedAt, null, null, code)
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = code, errorMessage = when (code) {
                "AI_RATE_LIMITED" -> "模型服务请求过于频繁，请稍后重试"
                else -> "模型服务暂时不可用，请稍后重试"
            })
        } catch (exception: Exception) {
            val code = when (exception) {
                is InvalidAiJsonException -> "INVALID_AI_JSON"
                is ResultTooLargeException -> "RESULT_TOO_LARGE"
                is PromptSchemaValidator.SchemaViolationException -> "INVALID_OUTPUT_SCHEMA"
                is PromptCatalogService.NotFoundException -> "PROMPT_VERSION_NOT_FOUND"
                else -> "AI_ANALYSIS_FAILED"
            }
            recordAudit(resolvedPrompt, rendered, taskId, requestId, providerConfigId, model, "FAILED", attempt, startedAt, null, null, code)
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = code, errorMessage = when (code) {
                "INVALID_AI_JSON" -> "模型返回结果不是合法 JSON"
                "RESULT_TOO_LARGE" -> "模型返回结果超过大小限制"
                "INVALID_OUTPUT_SCHEMA" -> "模型返回结果不符合输出契约"
                "PROMPT_VERSION_NOT_FOUND" -> "提示词版本不存在"
                else -> "剧本资产解析失败"
            })
        }
    }

    private fun recordAudit(
        prompt: PromptCatalogService.ResolvedPrompt?,
        rendered: PromptRenderer.RenderedPrompt?,
        taskId: Long?,
        requestId: String,
        providerConfigId: Long,
        model: String,
        status: String,
        attempt: Int,
        startedAt: Long,
        inputTokens: Int?,
        outputTokens: Int?,
        validationErrorCode: String?,
    ) {
        val resolved = prompt ?: return
        runCatching {
            promptRunAuditService.record(
                promptKey = resolved.definition.key,
                promptVersionId = requireNotNull(resolved.version.id),
                taskId = taskId,
                invocationId = requestId,
                providerConfigId = providerConfigId,
                model = model,
                status = status,
                attempt = attempt,
                latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                inputTokens = inputTokens ?: rendered?.inputTokens,
                outputTokens = outputTokens,
                templateHash = rendered?.templateHash ?: resolved.version.contentHash,
                variablesHash = rendered?.variablesHash,
                renderedInputHash = rendered?.renderedInputHash,
                validationErrorCode = validationErrorCode,
            )
        }
    }

    private fun parseRoot(raw: String): JsonNode {
        val normalized = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (normalized.isBlank()) throw InvalidAiJsonException("模型返回的资产结果为空")
        val root = try {
            objectMapper.readTree(normalized)
        } catch (exception: Exception) {
            throw InvalidAiJsonException("模型返回的资产结果不是合法 JSON")
        }
        if (root == null || root.isNull) throw InvalidAiJsonException("模型返回的资产结果为空")
        return root
    }

    private fun parseItems(root: JsonNode, type: EpisodeAssetType): List<JsonNode> {
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
