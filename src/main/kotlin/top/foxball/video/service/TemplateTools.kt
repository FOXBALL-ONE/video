package top.foxball.video.service

import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import top.foxball.video.entity.jdbc.EpisodeAssetType

/**
 * 分镜列表校验工具。
 *
 * 模板中的导演技能在产出分镜列表后可以调用本工具，让服务端补全序号、裁剪超限字段并回报
 * 结构问题，从而在不额外发起模型调用的情况下提高分镜产出的可用性。
 */
@Component
class StoryboardListTool(private val objectMapper: ObjectMapper) : TemplateTool {
    override val callbacks: List<ToolCallback> = listOf(
        SimpleToolCallback(
            name = "storyboard_list_validate",
            description = "校验并规范化分镜列表，返回补全序号后的镜头数组与警告列表",
            inputSchema = SHOT_LIST_SCHEMA,
            handler = ::validate,
        ),
    )

    private fun validate(toolInput: String): String {
        val root = objectMapper.readTree(toolInput)
        val shots = root.path("shots").takeIf { it.isArray }
            ?: throw IllegalArgumentException("shots 必须是数组")
        require(shots.size() <= MAX_SHOTS) { "分镜数量不能超过 $MAX_SHOTS 个" }
        val normalized = objectMapper.createArrayNode()
        val warnings = objectMapper.createArrayNode()
        shots.forEachIndexed { position, shot ->
            require(shot.isObject) { "第 ${position + 1} 个镜头必须是对象" }
            val index = position + 1
            val node = objectMapper.createObjectNode()
            node.put("index", index)
            node.put("shot_type", trimmed(shot, "shot_type", MAX_TEXT_LENGTH))
            node.put("camera", trimmed(shot, "camera", MAX_TEXT_LENGTH))
            node.put("description", trimmed(shot, "description", MAX_DESCRIPTION_LENGTH))
            node.put("dialogue", trimmed(shot, "dialogue", MAX_DESCRIPTION_LENGTH))
            node.put("duration_seconds", duration(shot, warnings, index))
            node.replace("characters", characters(shot))
            if (node.path("description").stringValue(null).isNullOrBlank()) {
                warnings.add("第 $index 个镜头缺少画面描述")
            }
            if (node.path("shot_type").stringValue(null).isNullOrBlank()) {
                warnings.add("第 $index 个镜头缺少景别")
            }
            normalized.add(node)
        }
        val result = objectMapper.createObjectNode()
        result.set("shots", normalized)
        result.set("warnings", warnings)
        return objectMapper.writeValueAsString(result)
    }

    /** 时长缺省按 3 秒补齐，超出 1..60 秒范围时裁剪并给出警告。 */
    private fun duration(shot: JsonNode, warnings: ArrayNode, index: Int): Double {
        val value = shot.path("duration_seconds")
        if (!value.isNumber) {
            warnings.add("第 $index 个镜头时长缺省，已按 3 秒处理")
            return DEFAULT_DURATION
        }
        val seconds = value.doubleValue()
        if (seconds < MIN_DURATION || seconds > MAX_DURATION) {
            warnings.add("第 $index 个镜头时长 ${seconds} 秒超出范围，已裁剪")
            return seconds.coerceIn(MIN_DURATION, MAX_DURATION)
        }
        return seconds
    }

    private fun characters(shot: JsonNode): ArrayNode {
        val result = objectMapper.createArrayNode()
        val characters = shot.path("characters")
        if (!characters.isArray) return result
        characters.take(MAX_CHARACTERS).forEach { name ->
            val text = name.stringValue(null)?.trim()?.take(MAX_TEXT_LENGTH)
            if (!text.isNullOrBlank()) result.add(text)
        }
        return result
    }

    private fun trimmed(shot: JsonNode, field: String, limit: Int): String =
        shot.path(field).stringValue(null)?.trim()?.take(limit).orEmpty()

    private companion object {
        const val MAX_SHOTS = 200
        const val MAX_CHARACTERS = 10
        const val MAX_TEXT_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 2000
        const val DEFAULT_DURATION = 3.0
        const val MIN_DURATION = 1.0
        const val MAX_DURATION = 60.0
        val SHOT_LIST_SCHEMA = """
            {"type":"object","properties":{"shots":{"type":"array","items":{"type":"object","properties":{
            "shot_type":{"type":"string"},"camera":{"type":"string"},"description":{"type":"string"},
            "dialogue":{"type":"string"},"duration_seconds":{"type":"number"},
            "characters":{"type":"array","items":{"type":"string"}}}}}},"required":["shots"]}
        """.trimIndent().replace("\n", "")
    }
}

/**
 * 分镜连续性检查工具。
 *
 * 在分镜列表进入图像生成前给出确定性检查：序号连续性、画面描述缺失、重复镜头、台词缺少角色
 * 以及超出常规时长的镜头，帮助导演技能在输出前自我修正。
 */
@Component
class ShotContinuityTool(private val objectMapper: ObjectMapper) : TemplateTool {
    override val callbacks: List<ToolCallback> = listOf(
        SimpleToolCallback(
            name = "shot_continuity_check",
            description = "检查分镜列表的连续性问题，返回问题清单",
            inputSchema = SHOT_LIST_SCHEMA,
            handler = ::check,
        ),
    )

    private fun check(toolInput: String): String {
        val root = objectMapper.readTree(toolInput)
        val shots = root.path("shots").takeIf { it.isArray }
            ?: throw IllegalArgumentException("shots 必须是数组")
        require(shots.size() <= MAX_SHOTS) { "分镜数量不能超过 $MAX_SHOTS 个" }
        val issues = objectMapper.createArrayNode()
        var previousDescription: String? = null
        shots.forEachIndexed { position, shot ->
            require(shot.isObject) { "第 ${position + 1} 个镜头必须是对象" }
            val index = position + 1
            val description = shot.path("description").stringValue(null)?.trim().orEmpty()
            if (description.isBlank()) {
                issues.add(issue(index, "MISSING_DESCRIPTION", "镜头缺少画面描述"))
            }
            if (description.isNotBlank() && description == previousDescription) {
                issues.add(issue(index, "DUPLICATE_SHOT", "与上一镜头画面描述完全相同"))
            }
            if (!shot.path("dialogue").stringValue(null).isNullOrBlank() && shot.path("characters").path(0).isMissingNode) {
                issues.add(issue(index, "DIALOGUE_WITHOUT_CHARACTER", "有台词但未标注出场角色"))
            }
            val duration = shot.path("duration_seconds")
            if (duration.isNumber && duration.doubleValue() > LONG_SHOT_SECONDS) {
                issues.add(issue(index, "LONG_SHOT", "镜头时长超过 $LONG_SHOT_SECONDS 秒，可能难以在单张分镜图中表达"))
            }
            previousDescription = description.ifBlank { null }
        }
        val result = objectMapper.createObjectNode()
        result.set("issues", issues)
        result.put("shot_count", shots.size())
        return objectMapper.writeValueAsString(result)
    }

    private fun issue(index: Int, code: String, message: String): ObjectNode =
        objectMapper.createObjectNode().apply {
            put("index", index)
            put("code", code)
            put("message", message)
        }

    private companion object {
        const val MAX_SHOTS = 200
        const val LONG_SHOT_SECONDS = 15.0
        val SHOT_LIST_SCHEMA = """
            {"type":"object","properties":{"shots":{"type":"array","items":{"type":"object","properties":{
            "description":{"type":"string"},"dialogue":{"type":"string"},"duration_seconds":{"type":"number"},
            "characters":{"type":"array","items":{"type":"string"}}}}}},"required":["shots"]}
        """.trimIndent().replace("\n", "")
    }
}

/**
 * 资产补丁校验工具。
 *
 * 资产清单助手在产出修改建议前可以调用本工具：工具按 [AssetFieldCatalog] 的字段目录核对每条补丁的
 * 资产、字段、取值与长度，丢弃无法写入的条目并回报原因，让模型在给出最终答案前自行修正。
 * 工具只做结构校验，不读取数据库；资产是否存在、字段当前值是否已变化由采纳补丁时的服务端校验决定。
 */
@Component
class AssetPatchTool(private val objectMapper: ObjectMapper) : TemplateTool {
    override val callbacks: List<ToolCallback> = listOf(
        SimpleToolCallback(
            name = "asset_patch_validate",
            description = "校验并规范化资产修改建议，返回可用补丁、被丢弃的条目与警告列表",
            inputSchema = PATCH_SCHEMA,
            handler = ::validate,
        ),
    )

    private fun validate(toolInput: String): String {
        val root = objectMapper.readTree(toolInput)
        val assetTypes = assetTypes(root.path("assets"))
        val patches = root.path("patches").takeIf { it.isArray }
            ?: throw IllegalArgumentException("patches 必须是数组")
        require(patches.size() <= MAX_PATCHES) { "补丁数量不能超过 $MAX_PATCHES 条" }
        val normalized = objectMapper.createArrayNode()
        val dropped = objectMapper.createArrayNode()
        val warnings = objectMapper.createArrayNode()
        val seen = mutableSetOf<Pair<Int, String>>()
        patches.forEach { patch ->
            val assetId = patch.path("asset_id").takeIf { it.isIntegralNumber }?.intValue()
            val field = patch.path("field").stringValue(null)?.trim().orEmpty()
            if (assetId == null || field.isBlank()) {
                drop(dropped, assetId, field, "MALFORMED_PATCH", "补丁缺少 asset_id 或 field")
                return@forEach
            }
            val type = assetTypes[assetId]
            if (type == null) {
                drop(dropped, assetId, field, "UNKNOWN_ASSET", "asset_id 不在本次资产清单中")
                return@forEach
            }
            val catalogField = AssetFieldCatalog.fieldFor(type, field)
            if (catalogField == null) {
                drop(dropped, assetId, field, "FIELD_NOT_ALLOWED", "${type.name} 资产不支持字段 $field")
                return@forEach
            }
            if (!seen.add(assetId to catalogField.key)) {
                drop(dropped, assetId, field, "DUPLICATE_PATCH", "同一资产的同一字段出现多次，只保留第一条")
                return@forEach
            }
            val rawValue = patch.path("value").let { value -> value.stringValue(null) ?: value.takeIf { !it.isNull && !it.isMissingNode }?.toString() }.orEmpty().trim()
            if (rawValue.isBlank()) {
                drop(dropped, assetId, field, "EMPTY_VALUE", "value 为空；如需清空字段请明确说明")
                return@forEach
            }
            val value = when {
                catalogField.allowedValues.isNotEmpty() -> {
                    val matched = catalogField.allowedValues.firstOrNull { it.equals(rawValue, ignoreCase = true) }
                    if (matched == null) {
                        drop(dropped, assetId, field, "INVALID_VALUE", "取值必须是 ${catalogField.allowedValues.joinToString("/")} 之一")
                        return@forEach
                    }
                    matched
                }
                else -> rawValue
            }
            val stored = when {
                value.length > catalogField.maxLength -> {
                    warnings.add("资产 $assetId 的 $field 超过 ${catalogField.maxLength} 字，已截断")
                    value.take(catalogField.maxLength)
                }
                else -> value
            }
            normalized.add(
                objectMapper.createObjectNode().apply {
                    put("asset_id", assetId)
                    put("asset_type", type.name)
                    put("field", catalogField.key)
                    put("value", stored)
                    put("reason", patch.path("reason").stringValue(null)?.trim()?.take(MAX_REASON_LENGTH).orEmpty())
                },
            )
        }
        val result = objectMapper.createObjectNode()
        result.set("patches", normalized)
        result.set("dropped", dropped)
        result.set("warnings", warnings)
        return objectMapper.writeValueAsString(result)
    }

    private fun assetTypes(node: JsonNode): Map<Int, EpisodeAssetType> {
        if (!node.isArray) return emptyMap()
        val result = mutableMapOf<Int, EpisodeAssetType>()
        node.forEach { entry ->
            val id = entry.path("id").takeIf { it.isIntegralNumber }?.intValue() ?: return@forEach
            val type = entry.path("type").stringValue(null)?.trim()?.uppercase()?.let { name ->
                EpisodeAssetType.entries.firstOrNull { it.name == name }
            } ?: return@forEach
            result[id] = type
        }
        return result
    }

    private fun drop(dropped: ArrayNode, assetId: Int?, field: String, code: String, message: String) {
        dropped.add(
            objectMapper.createObjectNode().apply {
                assetId?.let { put("asset_id", it) }
                put("field", field)
                put("code", code)
                put("message", message)
            },
        )
    }

    private companion object {
        const val MAX_PATCHES = 50
        const val MAX_REASON_LENGTH = 1000
        val PATCH_SCHEMA = """
            {"type":"object","properties":{
            "assets":{"type":"array","items":{"type":"object","properties":{
            "id":{"type":"integer"},"type":{"type":"string"}}}},
            "patches":{"type":"array","items":{"type":"object","properties":{
            "asset_id":{"type":"integer"},"field":{"type":"string"},"value":{"type":"string"},
            "reason":{"type":"string"}}}}},
            "required":["assets","patches"]}
        """.trimIndent().replace("\n", "")
    }
}
