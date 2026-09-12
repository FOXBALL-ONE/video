package top.foxball.video.service

import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 模板渲染器，负责变量边界、哈希和 token 预算，不持久化完整渲染消息。 */
@Service
class TemplateRenderer(
    private val objectMapper: ObjectMapper,
    private val schemaValidator: TemplateSchemaValidator,
) {
    data class RenderedTemplate(
        val messages: List<AiChatMessage>,
        val templateHash: String,
        val variablesHash: String,
        val renderedInputHash: String,
        val inputTokens: Int,
        val variableNames: List<String>,
        val variableLengths: Map<String, Int>,
    ) {
        /** 把渲染后的消息合并为一段文本，供只接受单一提示词的图像接口使用。 */
        fun toPlainPrompt(): String = messages.joinToString("\n") { it.content.orEmpty() }
    }

    fun render(template: TemplateCatalogService.ResolvedTemplate, variables: Map<String, Any?>): RenderedTemplate {
        val preparedVariables = prepareVariables(template.version.variablesSchemaJson, variables)
        schemaValidator.validateVariables(template.version.variablesSchemaJson, preparedVariables)
        val messageNodes = objectMapper.readTree(template.version.messagesJson)
        require(messageNodes.isArray) { "模板消息必须是数组" }
        val messages = messageNodes.toList().map { node ->
            val role = node.path("role").stringValue(null)?.uppercase() ?: throw IllegalArgumentException("模板消息缺少 role")
            val content = node.path("content").stringValue(null) ?: throw IllegalArgumentException("模板消息缺少 content")
            val rendered = renderContent(content, preparedVariables)
            val aiRole = when (role) {
                "SYSTEM" -> AiChatRole.SYSTEM
                "USER" -> AiChatRole.USER
                "ASSISTANT" -> AiChatRole.ASSISTANT
                else -> throw IllegalArgumentException("不支持的模板消息角色")
            }
            AiChatMessage(aiRole, rendered)
        }
        val renderedText = messages.joinToString("\n") { "${it.role.name}: ${it.content}" }
        val tokenCount = (renderedText.length / 4.0).toInt().coerceAtLeast(1)
        val policy = runCatching { objectMapper.readTree(template.version.generationPolicyJson) }.getOrNull()
        val maxInputNode = policy?.path("max_input_tokens")
        val maxInputTokens = maxInputNode?.takeIf { it.isIntegralNumber }?.intValue()?.takeIf { it > 0 }
        require(maxInputTokens == null || tokenCount <= maxInputTokens) { "模板输入超过 token 上限" }
        val variableLengths = preparedVariables.mapValues { value -> value.value?.let { stringify(it).length } ?: 0 }
        return RenderedTemplate(
            messages = messages,
            templateHash = template.version.contentHash,
            variablesHash = hash(canonicalVariables(preparedVariables)),
            renderedInputHash = hash(renderedText),
            inputTokens = tokenCount,
            variableNames = preparedVariables.keys.sorted(),
            variableLengths = variableLengths,
        )
    }

    /** 仅计算变量摘要，不保留变量正文，用于任务创建时的可追溯元数据。 */
    fun variablesHash(variables: Map<String, Any?>): String = hash(canonicalVariables(variables))

    /** 使用固定的非敏感样例值，仅返回哈希和统计信息给预览接口。 */
    fun preview(template: TemplateCatalogService.ResolvedTemplate, sampleId: String): RenderedTemplate {
        require(sampleId.trim().isNotBlank()) { "sample_id 不能为空" }
        val properties = objectMapper.readTree(template.version.variablesSchemaJson).path("properties")
        val variables = properties.properties().associate { (name, _) ->
            name to when {
                name == "asset_type" -> "CHARACTER"
                name == "output_language" -> "zh-CN"
                name == "detail_level" -> "STANDARD"
                name == "expression_count" -> 4
                name == "script_content" -> "示例剧本：人物在室内对话。"
                name.endsWith("_json") -> objectMapper.writeValueAsString(mapOf("sample_id" to sampleId, "content" to "脱敏示例"))
                else -> "脱敏示例：$sampleId"
            }
        }
        return render(template, variables)
    }

    private fun renderContent(template: String, variables: Map<String, Any?>): String {
        val pattern = Regex("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}")
        // 先确认模板本体没有无法解析的占位符；变量值里出现的 {{ 属于业务正文（例如剧本原文），不视为未解析变量。
        require(!pattern.replace(template) { "" }.contains("{{")) { "模板包含未解析的变量" }
        return pattern.replace(template) { match ->
            val name = match.groupValues[1]
            require(variables.containsKey(name)) { "缺少模板变量：$name" }
            stringify(variables[name])
        }
    }

    private fun stringify(value: Any?): String = when (value) {
        null -> ""
        is String -> sanitize(value)
        is JsonNode -> sanitize(value.stringValue(null) ?: value.toString())
        else -> sanitize(objectMapper.writeValueAsString(value))
    }

    private fun sanitize(value: String): String {
        require(value.none { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) { "模板变量包含不允许的控制字符" }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun prepareVariables(schemaJson: String, variables: Map<String, Any?>): Map<String, Any?> {
        val schema = objectMapper.readTree(schemaJson)
        val properties = schema.path("properties")
        if (!properties.isObject) return variables
        val required = schema.path("required").takeIf { it.isArray }?.mapNotNull { it.stringValue(null) }?.toSet().orEmpty()
        val prepared = variables.toMutableMap()
        properties.properties().forEach { (name, propertySchema) ->
            if (prepared.containsKey(name)) return@forEach
            val defaultValue = propertySchema.path("default").takeIf { !it.isMissingNode && !it.isNull }
            if (defaultValue != null) {
                prepared[name] = defaultValue
                return@forEach
            }
            // 必填且无默认值的变量保持缺失，让变量校验给出明确错误，而不是用空串静默满足必填约束。
            if (name in required) return@forEach
            prepared[name] = when (propertySchema.path("type").stringValue(null)) {
                "array" -> emptyList<Any>()
                else -> ""
            }
        }
        return prepared
    }

    private fun canonicalVariables(variables: Map<String, Any?>): String =
        variables.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key=${stringify(value)}" }

    private fun hash(value: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
