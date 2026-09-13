package top.foxball.video.service

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.TemplateCategory

/**
 * 资产清单助手的种子内容契约。
 *
 * 内置模板与技能只在应用启动时才会写入数据库并由 [TemplateCatalogService] 校验，这里用同一份常量
 * 提前跑一遍契约校验：模板 JSON 写错时在测试阶段就暴露，而不是等到启动或首次调用助手时。
 */
class AssetAssistantTemplateContractTest {
    private val objectMapper = ObjectMapper()
    private val validator = TemplateSchemaValidator(objectMapper)

    private companion object {
        /** 现有库 `ai_templates_category_check` 约束允许的分类取值。 */
        val DATABASE_ALLOWED_CATEGORIES = setOf(
            "SCRIPT_GENERAL",
            "SCRIPT_CHARACTER",
            "SCRIPT_SCENE",
            "SCRIPT_PROP",
            "CHARACTER_ENRICHMENT",
            "CHARACTER_EXPRESSION_IMAGE",
            "STORYBOARD_IMAGE",
        )
    }

    @Test
    fun `assistant template seed satisfies the template contract`() {
        validator.validateMessages(AssetAssistantTemplate.MESSAGES_JSON)
        validator.validateVariablesSchema(AssetAssistantTemplate.VARIABLES_SCHEMA_JSON, AssetAssistantTemplate.MESSAGES_JSON)
        validator.validateOutputSchema(AssetAssistantTemplate.OUTPUT_SCHEMA_JSON)
        validator.validateGenerationPolicy(AssetAssistantTemplate.GENERATION_POLICY_JSON)
    }

    @Test
    fun `assistant template resolves to a category the database constraint accepts`() {
        assertTrue(AssetAssistantTemplate.TEMPLATE_KEY in TemplateCatalogService.REGISTERED_KEYS)
        val category = TemplateCatalogService.CATEGORY_BY_KEY[AssetAssistantTemplate.TEMPLATE_KEY]
        assertEquals(TemplateCategory.SCRIPT_GENERAL, category)
        // 现有库的 ai_templates.category 带 CHECK 约束且 ddl-auto 不会更新它：
        // 这里把数据库允许的取值固化为契约，避免新增枚举值后应用启动失败。
        assertTrue(
            category?.name in DATABASE_ALLOWED_CATEGORIES,
            "分类 $category 不在数据库约束允许的取值内：$DATABASE_ALLOWED_CATEGORIES",
        )
    }

    @Test
    fun `assistant skill only declares registered tools and readable instructions`() {
        TemplateToolRegistry(listOf(AssetPatchTool(objectMapper))).requireAvailable(listOf(AssetAssistantTemplate.TOOL_NAME))
        assertTrue(AssetAssistantTemplate.SKILL_INSTRUCTIONS.isNotBlank())
        assertTrue(!AssetAssistantTemplate.SKILL_INSTRUCTIONS.contains("{{"), "技能说明不得包含模板变量")
        assertTrue(AssetAssistantTemplate.SKILLS_JSON.contains(AssetAssistantTemplate.SKILL_KEY))
        assertTrue(AssetAssistantTemplate.TOOLS_JSON.contains(AssetAssistantTemplate.TOOL_NAME))
    }

    @Test
    fun `field catalog fits the template variable budget`() {
        val catalog = objectMapper.writeValueAsString(AssetFieldCatalog.describe())
        assertTrue(catalog.contains("face_description"), "字段目录缺少角色字段：$catalog")
        assertTrue(catalog.contains("plot_function"), "字段目录缺少道具字段：$catalog")
        assertTrue(catalog.length < 20_000, "字段目录长度 ${catalog.length} 超出模板变量上限")
    }

    @Test
    fun `field catalog only exposes fields the assistant service can write`() {
        // 目录是模型、内置工具与写入逻辑的共同来源：新增字段必须同时能在 applyPatches 中落地。
        val writable = AssetFieldCatalog.fieldsFor(top.foxball.video.entity.jdbc.EpisodeAssetType.CHARACTER)
        assertTrue(writable.any { it.key == "status" && it.allowedValues.isNotEmpty() }, "状态字段必须声明枚举取值")
        assertTrue(writable.none { it.key.contains("file_id") }, "目录不得包含需要文件库参与的字段")
    }
}
