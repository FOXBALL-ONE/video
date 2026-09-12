package top.foxball.video.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.ai.AiUpstreamAuthenticationException
import top.foxball.video.ai.AiUpstreamException
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.repository.AiModelProviderConfigRepository
import java.util.UUID
import java.time.Duration
import java.util.concurrent.TimeoutException

/** 仅调用 AI 并规范化结果的剧本资产解析服务；不写入剧本或资产表。 */
@Service
class ScriptAssetAnalysisService(
    private val providerConfigRepository: AiModelProviderConfigRepository,
    private val objectMapper: ObjectMapper,
    private val templateCatalog: TemplateCatalogService,
    private val templateRenderer: TemplateRenderer,
    private val templateSchemaValidator: TemplateSchemaValidator,
    private val templateRunAuditService: TemplateRunAuditService,
    private val templateAgentRunner: TemplateAgentRunner,
) {
    private companion object {
        const val MAX_ITEMS_PER_TYPE = 50
        const val MAX_FIELD_TEXT_LENGTH = 10_000
        const val MAX_RESULT_BYTES = 2_000_000
        const val AI_TIMEOUT_SECONDS = 120L
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

    /**
     * 按模板版本解析一类剧本资产。
     *
     * 模板执行由 [TemplateAgentRunner] 完成：技能说明注入 system 通道，模板声明的内置工具与
     * MCP 工具装配到模型请求上。`stream` 参数保留用于兼容既有任务快照，模板执行统一使用
     * 非流式工具循环，并由 `AI_TIMEOUT_SECONDS` 统一限时。
     */
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
        @Suppress("UNUSED_PARAMETER") stream: Boolean = true,
        promptKey: String? = null,
        promptCategory: String? = null,
    ): TypeResult {
        val requestId = UUID.randomUUID().toString()
        val provider = providerConfigRepository.findById(providerConfigId).orElse(null)?.provider?.trim().orEmpty().ifBlank { "unknown" }
        var resolvedTemplate: TemplateCatalogService.ResolvedTemplate? = null
        var rendered: TemplateRenderer.RenderedTemplate? = null
        val startedAt = System.nanoTime()
        return try {
            val template = promptVersionId?.let { templateCatalog.resolveExecutableVersion(it) }
                ?: templateCatalog.resolve(promptKey ?: "script_analysis", null, null)
            require(promptKey.isNullOrBlank() || template.template.key == promptKey) { "模板版本与任务快照不匹配" }
            val expectedCategory = templateCatalog.categoryForAssetType(assetType)
            require(template.template.enabled) { "模板已停用" }
            require(template.template.key == "script_analysis" || templateCatalog.effectiveCategory(template.template).name == (promptCategory ?: expectedCategory.name)) { "模板版本与资产类型不匹配" }
            resolvedTemplate = template
            require(promptHash.isNullOrBlank() || promptHash.equals(template.version.contentHash, ignoreCase = true)) { "模板版本内容已变化" }
            val variables = linkedMapOf<String, Any?>(
                "script_content" to sourceContent,
                "episode_context_json" to objectMapper.writeValueAsString(mapOf("episode_id" to episodeId, "asset_type" to assetType.name)),
                "output_language" to template.version.locale,
                "detail_level" to "STANDARD",
            )
            if (template.template.key == "script_analysis") variables["asset_type"] = assetType.name
            val renderedTemplate = templateRenderer.render(template, variables)
            rendered = renderedTemplate
            val outcome = Mono.fromCallable {
                templateAgentRunner.chat(
                    TemplateAgentRunner.ChatRequest(
                        template = template,
                        rendered = renderedTemplate,
                        providerConfigId = providerConfigId,
                        model = model,
                        requestId = requestId,
                    ),
                )
            }.subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                .block() ?: throw IllegalStateException("模型服务未返回结果")
            val root = normalizeOutputRoot(parseRoot(outcome.text), assetType)
            templateSchemaValidator.validateOutput(template.version.outputSchemaJson, root)
            val items = parseItems(root, assetType)
                .map { normalizeItem(assetType, it) }
            if (objectMapper.writeValueAsBytes(items).size > MAX_RESULT_BYTES) throw ResultTooLargeException()
            recordAudit(
                resolvedTemplate,
                rendered,
                taskId,
                requestId,
                providerConfigId,
                model,
                "SUCCEEDED",
                attempt,
                startedAt,
                outcome.inputTokens,
                outcome.outputTokens,
                null,
                outcome.skillKeys,
                outcome.mcpServerKeys,
                outcome.toolCallCount,
            )
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, items)
        } catch (exception: AiConfigurationException) {
            recordAudit(resolvedTemplate, rendered, taskId, requestId, providerConfigId, model, "FAILED", attempt, startedAt, null, null, "AI_CONFIGURATION_ERROR")
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = "AI_CONFIGURATION_ERROR", errorMessage = "AI 提供商配置不可用")
        } catch (exception: AiClientException) {
            val code = when (exception) {
                is AiUpstreamException, is AiUpstreamAuthenticationException -> "AI_PROVIDER_ERROR"
                is AiRateLimitException -> "AI_RATE_LIMITED"
                else -> "AI_PROVIDER_ERROR"
            }
            recordAudit(resolvedTemplate, rendered, taskId, requestId, providerConfigId, model, "FAILED", attempt, startedAt, null, null, code)
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = code, errorMessage = when (code) {
                "AI_RATE_LIMITED" -> "模型服务请求过于频繁，请稍后重试"
                else -> "模型服务暂时不可用，请稍后重试"
            })
        } catch (exception: TimeoutException) {
            recordAudit(resolvedTemplate, rendered, taskId, requestId, providerConfigId, model, "FAILED", attempt, startedAt, null, null, "AI_TIMEOUT")
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = "AI_TIMEOUT", errorMessage = "模型服务响应超时，请稍后重试")
        } catch (exception: Exception) {
            val code = when (exception) {
                is InvalidAiJsonException -> "INVALID_AI_JSON"
                is ResultTooLargeException -> "RESULT_TOO_LARGE"
                is TemplateSchemaValidator.SchemaViolationException -> "INVALID_OUTPUT_SCHEMA"
                is TemplateCatalogService.NotFoundException -> "TEMPLATE_VERSION_NOT_FOUND"
                is TimeoutException -> "AI_TIMEOUT"
                else -> when {
                    exception.cause is TimeoutException -> "AI_TIMEOUT"
                    exception.message?.contains("模板版本内容已变化") == true -> "TEMPLATE_VERSION_CHANGED"
                    exception.message?.contains("模板版本与资产类型不匹配") == true -> "TEMPLATE_CATEGORY_MISMATCH"
                    exception.message?.contains("模板版本与任务快照不匹配") == true -> "TEMPLATE_VERSION_CHANGED"
                    exception.message?.contains("模板已停用") == true -> "TEMPLATE_VERSION_DISABLED"
                    else -> "AI_ANALYSIS_FAILED"
                }
            }
            recordAudit(resolvedTemplate, rendered, taskId, requestId, providerConfigId, model, "FAILED", attempt, startedAt, null, null, code)
            TypeResult(assetType, providerConfigId, model.trim(), provider, requestId, errorCode = code, errorMessage = when (code) {
                "INVALID_AI_JSON" -> "模型返回结果不是合法 JSON"
                "RESULT_TOO_LARGE" -> "模型返回结果超过大小限制"
                "INVALID_OUTPUT_SCHEMA" -> "模型返回结果不符合输出契约：${exception.message ?: "结构不匹配"}"
                "TEMPLATE_VERSION_NOT_FOUND" -> "模板版本不存在"
                "TEMPLATE_VERSION_CHANGED" -> "模板版本已变化，请重新提交解析任务"
                "TEMPLATE_CATEGORY_MISMATCH" -> "模板分类与资产类型不匹配"
                "TEMPLATE_VERSION_DISABLED" -> "模板已停用，请重新选择当前版本"
                "AI_TIMEOUT" -> "模型服务响应超时，请稍后重试"
                else -> "剧本资产解析失败"
            })
        }
    }

    /** 写入运行审计；技能与 MCP 绑定可从模板版本回读，保证失败路径也记录实际装配内容。 */
    private fun recordAudit(
        template: TemplateCatalogService.ResolvedTemplate?,
        rendered: TemplateRenderer.RenderedTemplate?,
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
        skillKeys: List<String> = emptyList(),
        mcpServerKeys: List<String> = emptyList(),
        toolCallCount: Int = 0,
    ) {
        val resolved = template ?: return
        runCatching {
            templateRunAuditService.record(
                TemplateRunRecord(
                    templateKey = resolved.template.key,
                    templateVersionId = requireNotNull(resolved.version.id),
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
                    skillKeys = skillKeys.ifEmpty { templateSchemaValidator.skillKeys(resolved.version.skillsJson) },
                    mcpServerKeys = mcpServerKeys.ifEmpty { templateSchemaValidator.mcpServerKeys(resolved.version.mcpServersJson) },
                    toolCallCount = toolCallCount,
                    validationErrorCode = validationErrorCode,
                ),
            )
        }
    }

    private fun parseRoot(raw: String): JsonNode {
        val normalized = raw.trim().removePrefix("\uFEFF").trim()
        if (normalized.isBlank()) throw InvalidAiJsonException("模型返回的资产结果为空")
        val root = runCatching { objectMapper.readTree(normalized) }.getOrNull()
            ?: extractEmbeddedJson(normalized)
            ?: throw InvalidAiJsonException("模型返回的资产结果不是合法 JSON")
        if (root.isNull) throw InvalidAiJsonException("模型返回的资产结果为空")
        if (root.isTextual) {
            val unwrapped = root.textValue().trim()
            if (unwrapped.isNotBlank()) {
                val unwrappedRoot = runCatching { objectMapper.readTree(unwrapped) }.getOrNull()
                    ?: extractEmbeddedJson(unwrapped)
                unwrappedRoot?.takeUnless { it.isNull }?.let { return it }
            }
            throw InvalidAiJsonException("模型返回的资产结果不是合法 JSON")
        }
        return root
    }

    /** 从代码块或解释文字中提取第一个完整 JSON 值，兼容模型偶发的格式包裹。 */
    private fun extractEmbeddedJson(raw: String): JsonNode? {
        val starts = raw.indices.filter { raw[it] == '{' || raw[it] == '[' }
        starts.forEach { start ->
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until raw.length) {
                val character = raw[index]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                    continue
                }
                when (character) {
                    '"' -> quoted = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            runCatching { objectMapper.readTree(raw.substring(start, index + 1)) }
                                .getOrNull()?.let { return it }
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 单类型解析允许模型只返回当前数组，但历史输出 schema 要求完整顶层 envelope。
     * 在校验前补齐未选择类型的空数组，并兼容常见的数组/items 包裹格式。
     */
    private fun normalizeOutputRoot(root: JsonNode, type: EpisodeAssetType): ObjectNode {
        val targetField = when (type) {
            EpisodeAssetType.CHARACTER -> "characters"
            EpisodeAssetType.SCENE_BACKGROUND -> "scenes"
            EpisodeAssetType.PROP -> "props"
            else -> throw InvalidAiJsonException("不支持的资产类型")
        }
        val normalized: ObjectNode = if (root.isObject) {
            (root as ObjectNode).deepCopy()
        } else {
            objectMapper.createObjectNode()
        }
        if (root.isArray) {
            normalized.set(targetField, root.deepCopy())
        } else {
            val aliases = when (targetField) {
                "characters" -> listOf("character", "character_list")
                "scenes" -> listOf("scene", "scene_background", "scene_backgrounds", "locations")
                "props" -> listOf("prop", "props_list", "objects")
                else -> emptyList()
            }
            val envelopeFields = aliases + listOf("items", "data", "result")
            val candidate = normalized.get(targetField)
                ?: aliases.firstNotNullOfOrNull(normalized::get)
                ?: listOf("items", "data", "result").firstNotNullOfOrNull { field -> normalized.get(field) }
            val arrayCandidate = candidate?.let { value ->
                when {
                    value.isArray -> value
                    value.isObject -> listOf("items", "data", "result", targetField).firstNotNullOfOrNull { field ->
                        value.get(field)?.takeIf { it.isArray }
                    }
                    else -> null
                }
            }
            if (arrayCandidate != null && normalized.get(targetField)?.isArray != true) {
                normalized.set(targetField, arrayCandidate.deepCopy())
            }
            envelopeFields.filter { it != targetField }.forEach(normalized::remove)
        }
        listOf("characters", "scenes", "props", "warnings")
            .filter { it != targetField }
            .forEach { field ->
                if (normalized.get(field)?.isArray != true) normalized.set(field, objectMapper.createArrayNode())
            }
        val allowedFields = setOf("characters", "scenes", "props", "warnings")
        normalized.properties()
            .map { it.key }
            .filterNot(allowedFields::contains)
            .toList()
            .forEach(normalized::remove)
        return normalized
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
