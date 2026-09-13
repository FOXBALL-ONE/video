package top.foxball.video.service

import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.EpisodeAssetType

/**
 * 资产清单助手可读写的字段目录。
 *
 * 同一份目录同时服务于三处：注入模板的字段说明、内置工具 `asset_patch_validate` 的入参校验，
 * 以及采纳补丁时的服务端写入。三者共用一份定义，模型返回的字段名不会与写入逻辑脱节。
 *
 * 需要文件库参与的字段（三视图、表情图、参考图文件、配音绑定）不在目录内：它们由图片与音频
 * 流程维护，纯文本补丁不能修改。
 */
object AssetFieldCatalog {
    /** 一个可编辑字段：写入长度上限，以及枚举字段的取值集合。 */
    data class Field(
        val key: String,
        val label: String,
        val maxLength: Int,
        val allowedValues: List<String> = emptyList(),
    )

    /** 所有资产类型共有的字段。 */
    val COMMON: List<Field> = listOf(
        Field("name", "名称", 100),
        Field("description", "描述", 100_000),
        Field("reference_url", "参考图链接", 1_000),
        Field("notes", "连续性备注", 100_000),
        Field("status", "资产状态", 20, AssetStatus.entries.map { it.name }),
    )

    private val CHARACTER_FIELDS: List<Field> = listOf(
        Field("identity_title", "身份/职业", 200),
        Field("role_tag", "角色定位", 50),
        Field("gender", "性别", 50),
        Field("age_stage", "年龄段", 50),
        Field("age_note", "年龄备注", 50),
        Field("face_description", "人脸描述", 100_000),
        Field("costume_description", "服装描述", 100_000),
        Field("hairstyle", "发型", 100),
        Field("costume_ref", "服饰", 100),
        Field("accessory_ref", "配饰", 100),
    )

    private val SCENE_FIELDS: List<Field> = listOf(
        Field("identity_title", "场景副标题", 200),
        Field("location", "地点", 100),
        Field("time", "时间", 100),
        Field("atmosphere", "氛围", 200),
    )

    private val PROP_FIELDS: List<Field> = listOf(
        Field("identity_title", "道具副标题", 200),
        Field("category", "道具类别", 100),
        Field("plot_function", "剧情作用", 100_000),
    )

    private val TYPE_FIELDS: Map<EpisodeAssetType, List<Field>> = mapOf(
        EpisodeAssetType.CHARACTER to CHARACTER_FIELDS,
        EpisodeAssetType.SCENE_BACKGROUND to SCENE_FIELDS,
        EpisodeAssetType.PROP to PROP_FIELDS,
    )

    /** 目录覆盖的资产类型；声音、音乐与音效资产只有共有字段可改。 */
    fun coveredTypes(): List<EpisodeAssetType> = listOf(
        EpisodeAssetType.CHARACTER,
        EpisodeAssetType.SCENE_BACKGROUND,
        EpisodeAssetType.PROP,
        EpisodeAssetType.COSTUME,
    )

    /** 指定资产类型允许修改的全部字段（共有字段 + 类型扩展字段）。 */
    fun fieldsFor(type: EpisodeAssetType): List<Field> = COMMON + TYPE_FIELDS.getOrDefault(type, emptyList())

    /** 取回字段定义；字段名不存在或不属于该资产类型时返回 null。 */
    fun fieldFor(type: EpisodeAssetType, key: String): Field? = fieldsFor(type).firstOrNull { it.key == key }

    /** 供模板变量使用的字段目录。 */
    fun describe(): Map<String, Any?> = linkedMapOf(
        "asset_types" to coveredTypes().map { it.name },
        "common_fields" to COMMON.map(::describeField),
        "fields_by_type" to TYPE_FIELDS.entries.associate { (type, fields) -> type.name to fields.map(::describeField) },
        "usage_notes" to listOf(
            "field 只能取 common_fields 与对应类型 fields_by_type 中出现的字段名。",
            "status 为枚举字段，只能取 common_fields 中列出的取值。",
            "value 是替换后的完整字段值，不是片段、不是增量描述；留空字符串表示清空该字段。",
        ),
    )

    private fun describeField(field: Field): Map<String, Any?> = linkedMapOf(
        "field" to field.key,
        "label" to field.label,
        "max_length" to field.maxLength,
        "allowed_values" to field.allowedValues,
    )
}
