package top.foxball.video.service

import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 模板契约的轻量 JSON Schema 校验器。
 *
 * 支持管理端和首期场景实际需要的 JSON Schema 子集：object、array、string、number、integer、boolean、null、
 * properties、required、items、enum、additionalProperties、maxLength 和 maxItems。未知关键字会被忽略，
 * 但不会把未知字段写入模型调用参数。除消息与 schema 外，还负责校验模板引用的技能、MCP 服务端与内置工具绑定。
 */
@Service
class TemplateSchemaValidator(private val objectMapper: ObjectMapper) {
    class SchemaViolationException(message: String) : IllegalArgumentException(message)

    fun validateMessages(json: String) {
        val root = parse(json, "消息模板")
        requireSchema(root.isArray, "消息模板必须是 JSON 数组")
        requireSchema(root.size() in 1..50, "消息模板数量必须在 1 到 50 条之间")
        requireSchema(root.any { it.path("role").stringValue(null)?.equals("system", true) == true }, "消息模板必须包含 system 消息")
        requireSchema(root.any { it.path("role").stringValue(null)?.equals("user", true) == true }, "消息模板必须包含 user 消息")
        root.forEachIndexed { index, node ->
            requireSchema(node.isObject, "消息 $index 必须是对象")
            val role = node.path("role").stringValue(null)?.uppercase()
            requireSchema(role in setOf("SYSTEM", "USER", "ASSISTANT"), "消息 $index 的 role 不受支持")
            requireSchema(node.path("content").isString, "消息 $index 的 content 必须是字符串")
            requireSchema(node.properties().all { (key, _) -> key in setOf("role", "content") }, "消息 $index 包含未允许字段")
            val content = node.path("content").stringValue(null).orEmpty()
            requireSchema(!VARIABLE_PATTERN.replace(content) { "" }.contains("{{"), "消息 $index 包含无法解析的占位符")
            if (role == "SYSTEM") {
                requireSchema(!VARIABLE_PATTERN.containsMatchIn(node.path("content").stringValue(null).orEmpty()), "system 消息不得插入运行时变量")
            }
        }
    }

    fun validateVariablesSchema(json: String, messagesJson: String) {
        val root = parse(json, "变量 schema")
        requireSchema(root.isObject, "变量 schema 顶层必须是 object")
        val properties = root.path("properties")
        requireSchema(properties.isObject, "变量 schema 必须声明 properties")
        properties.properties().forEach { (name, schema) -> validateValueSchema(schema, "变量 $name") }
        validateRequired(root, properties)
        val placeholders = VARIABLE_PATTERN.findAll(messagesJson).map { it.groupValues[1] }.toSet()
        val declared = properties.properties().map { it.key }.toSet()
        requireSchema(placeholders.all { it in declared }, "消息模板引用了未声明变量")
    }

    fun validateOutputSchema(json: String) {
        val root = parse(json, "输出 schema")
        requireSchema(root.isObject, "输出 schema 顶层必须是 object")
        validateValueSchema(root, "输出 schema")
    }

    fun validateGenerationPolicy(json: String) {
        val root = parse(json, "生成策略")
        requireSchema(root.isObject, "生成策略顶层必须是 object")
        requireSchema(root.properties().all { (key, _) -> key in ALLOWED_POLICY_FIELDS }, "生成策略包含未允许字段")
        root.path("temperature").takeIf { !it.isMissingNode }?.let {
            requireSchema(it.isNumber && it.doubleValue() in 0.0..2.0, "temperature 必须在 0 到 2 之间")
        }
        listOf("max_completion_tokens", "max_input_tokens").forEach { name ->
            root.path(name).takeIf { !it.isMissingNode }?.let { value ->
                requireSchema(value.isIntegralNumber && value.intValue() in 1..1_000_000, "$name 超出允许范围")
            }
        }
        root.path("response_format").takeIf { !it.isMissingNode }?.let { value ->
            requireSchema(value.isString && value.stringValue() in setOf("text", "json_object", "json_schema"), "response_format 不受支持")
        }
        root.path("output_mode").takeIf { !it.isMissingNode }?.let { value ->
            requireSchema(value.isString && value.stringValue() in setOf("text", "json", "image"), "output_mode 不受支持")
        }
        root.path("image").takeIf { !it.isMissingNode }?.let { image ->
            requireSchema(image.isObject, "image 必须是对象")
            requireSchema(image.properties().all { (key, _) -> key in ALLOWED_IMAGE_FIELDS }, "image 包含未允许字段")
            image.path("count").takeIf { !it.isMissingNode }?.let { value ->
                requireSchema(value.isIntegralNumber && value.intValue() in 1..10, "image.count 超出允许范围")
            }
            image.path("prompt_source").takeIf { !it.isMissingNode }?.let { value ->
                requireSchema(value.isString && value.stringValue() in setOf("model_output", "rendered_message"), "image.prompt_source 不受支持")
            }
            image.path("size").takeIf { !it.isMissingNode }?.let { value ->
                requireSchema(value.isString && value.stringValue().length <= 20, "image.size 不合法")
            }
            image.path("quality").takeIf { !it.isMissingNode }?.let { value ->
                requireSchema(value.isString && value.stringValue().length <= 30, "image.quality 不合法")
            }
            image.path("style").takeIf { !it.isMissingNode }?.let { value ->
                requireSchema(value.isString && value.stringValue().length <= 60, "image.style 不合法")
            }
        }
    }

    /** 校验技能绑定：只允许 key 字段，且必须是非空字符串。 */
    fun validateSkills(json: String) {
        val root = parse(json, "技能绑定")
        requireSchema(root.isArray, "技能绑定必须是 JSON 数组")
        requireSchema(root.size() <= MAX_SKILLS, "技能数量不能超过 $MAX_SKILLS 个")
        root.forEachIndexed { index, node ->
            requireSchema(node.isObject, "技能 $index 必须是对象")
            requireSchema(node.properties().all { (key, _) -> key == "key" }, "技能 $index 包含未允许字段")
            requireSchema(!node.path("key").stringValue(null).isNullOrBlank(), "技能 $index 缺少 key")
        }
        requireSchema(skillKeys(json).distinct().size == skillKeys(json).size, "技能不能重复绑定")
    }

    /** 校验 MCP 服务端绑定：允许 key 与可选的 tools 白名单。 */
    fun validateMcpServers(json: String) {
        val root = parse(json, "MCP 服务端绑定")
        requireSchema(root.isArray, "MCP 服务端绑定必须是 JSON 数组")
        requireSchema(root.size() <= MAX_MCP_SERVERS, "MCP 服务端数量不能超过 $MAX_MCP_SERVERS 个")
        root.forEachIndexed { index, node ->
            requireSchema(node.isObject, "MCP 服务端 $index 必须是对象")
            requireSchema(node.properties().all { (key, _) -> key in setOf("key", "tools") }, "MCP 服务端 $index 包含未允许字段")
            requireSchema(!node.path("key").stringValue(null).isNullOrBlank(), "MCP 服务端 $index 缺少 key")
            node.path("tools").takeIf { !it.isMissingNode }?.let { tools ->
                requireSchema(tools.isArray, "MCP 服务端 $index 的 tools 必须是数组")
                tools.forEach { requireSchema(it.isString && !it.stringValue(null).isNullOrBlank(), "MCP 服务端 $index 的工具名不合法") }
            }
        }
        val keys = mcpServerKeys(json)
        requireSchema(keys.distinct().size == keys.size, "MCP 服务端不能重复绑定")
    }

    /** 校验内置工具绑定：只接受已注册的工具名。 */
    fun validateTools(json: String) {
        val root = parse(json, "内置工具绑定")
        requireSchema(root.isArray, "内置工具绑定必须是 JSON 数组")
        requireSchema(root.size() <= MAX_TOOLS, "内置工具数量不能超过 $MAX_TOOLS 个")
        root.forEach { requireSchema(it.isString && !it.stringValue(null).isNullOrBlank(), "内置工具名不合法") }
        val names = toolNames(json)
        requireSchema(names.distinct().size == names.size, "内置工具不能重复绑定")
    }

    /** 读取 MCP 绑定列表，返回服务端 key 与其工具白名单（空列表表示该服务端全部工具）。 */
    fun mcpServerBindings(json: String): List<Pair<String, List<String>>> {
        val node = runCatching { objectMapper.readTree(json) }.getOrNull()
        if (node == null || !node.isArray) return emptyList()
        return node.mapNotNull { item ->
            val key = item.path("key").stringValue(null)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val tools = item.path("tools").takeIf { it.isArray }?.mapNotNull { it.stringValue(null)?.takeIf(String::isNotBlank) }
            key to tools.orEmpty()
        }
    }

    /** 读取技能 key 列表；绑定 JSON 不合法时返回空列表。 */
    fun skillKeys(json: String): List<String> = stringList(json) { it.path("key").stringValue(null) }

    /** 读取 MCP 服务端 key 列表；绑定 JSON 不合法时返回空列表。 */
    fun mcpServerKeys(json: String): List<String> = stringList(json) { it.path("key").stringValue(null) }

    /** 读取内置工具名列表；绑定 JSON 不合法时返回空列表。 */
    fun toolNames(json: String): List<String> = stringList(json) { it.stringValue(null) }

    /** 读取某个 MCP 服务端绑定的工具白名单；未声明时返回 null 表示服务端全部工具。 */
    fun mcpToolWhitelist(json: String, serverKey: String): List<String>? {
        val node = runCatching { objectMapper.readTree(json) }.getOrNull()
        if (node == null || !node.isArray) return null
        val matched = node.firstOrNull { it.path("key").stringValue(null) == serverKey } ?: return null
        val tools = matched.path("tools")
        if (!tools.isArray) return null
        return tools.mapNotNull { it.stringValue(null)?.takeIf(String::isNotBlank) }
    }

    fun validateVariables(schemaJson: String, variables: Map<String, Any?>) {
        val schema = parse(schemaJson, "变量 schema")
        val properties = schema.path("properties")
        requireSchema(properties.isObject, "变量 schema 必须声明 properties")
        val declared = properties.properties().associate { it.key to it.value }
        variables.keys.forEach { name -> requireSchema(name in declared, "未声明变量：$name") }
        validateRequired(schema, properties) { name -> variables[name] != null }
        variables.forEach { (name, value) -> validateValue(declared.getValue(name), value, "变量 $name") }
    }

    fun validateOutput(schemaJson: String, output: JsonNode) {
        validateValue(parse(schemaJson, "输出 schema"), output, "模型输出")
    }

    fun parseGenerationPolicy(json: String): JsonNode = parse(json, "生成策略")

    fun parseOutputSchema(json: String): JsonNode = parse(json, "输出 schema")

    private fun stringList(json: String, extract: (JsonNode) -> String?): List<String> {
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!root.isArray) return emptyList()
        return root.mapNotNull { extract(it)?.takeIf(String::isNotBlank) }
    }

    private fun validateValueSchema(schema: JsonNode, path: String) {
        requireSchema(schema.isObject, "$path 必须是 schema 对象")
        val type = schema.path("type").stringValue(null)
        if (type != null) requireSchema(type in SUPPORTED_TYPES, "$path 的 type 不受支持")
        schema.path("enum").takeIf { it.isArray }?.forEach { value ->
            requireSchema(value.isValueNode, "$path 的 enum 值必须是标量")
        }
        schema.path("properties").takeIf { it.isObject }?.properties()?.forEach { (name, child) -> validateValueSchema(child, "$path.$name") }
        schema.path("items").takeIf { !it.isMissingNode && !it.isNull }?.let { validateValueSchema(it, "$path.items") }
        validateRequired(schema, schema.path("properties"))
        schema.path("maxLength").takeIf { !it.isMissingNode }?.let { requireSchema(it.isIntegralNumber && it.intValue() in 0..2_000_000, "$path.maxLength 不合法") }
        schema.path("maxItems").takeIf { !it.isMissingNode }?.let { requireSchema(it.isIntegralNumber && it.intValue() in 0..10_000, "$path.maxItems 不合法") }
    }

    private fun validateValue(schema: JsonNode, value: Any?, path: String) {
        val node = when (value) {
            null -> objectMapper.nullNode()
            is JsonNode -> value
            else -> objectMapper.valueToTree<JsonNode>(value)
        }
        validateValue(schema, node, path)
    }

    private fun validateValue(schema: JsonNode, value: JsonNode, path: String) {
        val type = schema.path("type").stringValue(null)
        if (type != null) {
            val matches = when (type) {
                "object" -> value.isObject
                "array" -> value.isArray
                "string" -> value.isString
                "number" -> value.isNumber
                "integer" -> value.isIntegralNumber
                "boolean" -> value.isBoolean
                "null" -> value.isNull
                else -> false
            }
            requireSchema(matches, "$path 类型不正确，应为 $type")
        }
        schema.path("enum").takeIf { it.isArray }?.let { enumValues ->
            requireSchema(enumValues.any { it == value }, "$path 不在允许枚举中")
        }
        schema.path("maxLength").takeIf { !it.isMissingNode && value.isString }?.let { requireSchema(value.stringValue().length <= it.intValue(), "$path 超出长度限制") }
        schema.path("maxItems").takeIf { !it.isMissingNode && value.isArray }?.let { requireSchema(value.size() <= it.intValue(), "$path 超出数量限制") }
        if (value.isObject) {
            val properties = schema.path("properties")
            val known = if (properties.isObject) properties.properties().associate { it.key to it.value } else emptyMap()
            if (schema.path("additionalProperties").isBoolean && !schema.path("additionalProperties").booleanValue()) {
                value.properties().forEach { (name, _) -> requireSchema(name in known, "$path 包含未允许字段：$name") }
            }
            known.forEach { (name, child) ->
                val childValue = value.get(name)
                if (childValue != null && !childValue.isMissingNode) validateValue(child, childValue, "$path.$name")
            }
            validateRequired(schema, properties) { name -> value.get(name) != null && !value.get(name).isNull }
        }
        if (value.isArray) schema.path("items").takeIf { it.isObject }?.let { itemSchema -> value.forEachIndexed { index, child -> validateValue(itemSchema, child, "$path[$index]") } }
    }

    private fun validateRequired(schema: JsonNode, properties: JsonNode, present: (String) -> Boolean = { true }) {
        schema.path("required").takeIf { it.isArray }?.forEach { required ->
            val name = required.stringValue(null)
            requireSchema(!name.isNullOrBlank() && properties.isObject && properties.get(name) != null && present(name), "缺少必填字段：$name")
        }
    }

    private fun parse(json: String, label: String): JsonNode = try {
        objectMapper.readTree(json) ?: throw SchemaViolationException("$label 不能为空")
    } catch (exception: SchemaViolationException) {
        throw exception
    } catch (exception: Exception) {
        throw SchemaViolationException("$label 不是合法 JSON")
    }

    private fun requireSchema(condition: Boolean, message: String) {
        if (!condition) throw SchemaViolationException(message)
    }

    private companion object {
        val SUPPORTED_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
        val VARIABLE_PATTERN = Regex("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}")
        val ALLOWED_POLICY_FIELDS = setOf(
            "temperature",
            "max_completion_tokens",
            "max_input_tokens",
            "response_format",
            "output_mode",
            "image",
        )
        val ALLOWED_IMAGE_FIELDS = setOf("size", "count", "quality", "style", "prompt_source")
        const val MAX_SKILLS = 8
        const val MAX_MCP_SERVERS = 8
        const val MAX_TOOLS = 16
    }
}
