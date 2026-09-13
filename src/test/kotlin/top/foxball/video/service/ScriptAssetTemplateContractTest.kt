package top.foxball.video.service

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import top.foxball.video.entity.jdbc.TemplateCategory

class ScriptAssetTemplateContractTest {
    @Test
    fun `asset types resolve to dedicated template categories`() {
        assertEquals(TemplateCategory.SCRIPT_CHARACTER, TemplateCatalogService.CATEGORY_BY_KEY["script_character_extraction"])
        assertEquals(TemplateCategory.SCRIPT_SCENE, TemplateCatalogService.CATEGORY_BY_KEY["script_scene_extraction"])
        assertEquals(TemplateCategory.SCRIPT_PROP, TemplateCatalogService.CATEGORY_BY_KEY["script_prop_extraction"])
        assertEquals(TemplateCategory.CHARACTER_EXPRESSION_IMAGE, TemplateCatalogService.CATEGORY_BY_KEY["character_expression_image_prompt"])
        assertEquals(TemplateCategory.CHARACTER_ENRICHMENT, TemplateCatalogService.CATEGORY_BY_KEY["character_three_view_merge"])
        assertEquals(TemplateCategory.CHARACTER_ENRICHMENT, TemplateCatalogService.CATEGORY_BY_KEY["character_enrichment"])
        assertEquals(TemplateCategory.STORYBOARD_IMAGE, TemplateCatalogService.CATEGORY_BY_KEY["shot_storyboard_grid"])
        assertEquals(TemplateCategory.SCRIPT_GENERAL, TemplateCatalogService.CATEGORY_BY_KEY["asset_list_assistant"])
    }

    @Test
    fun `dedicated template keys are registered`() {
        assertTrue("script_character_extraction" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("script_scene_extraction" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("script_prop_extraction" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("character_expression_image_prompt" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("character_three_view_merge" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("character_enrichment" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("shot_storyboard_grid" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("script_analysis" in TemplateCatalogService.REGISTERED_KEYS)
        assertTrue("asset_list_assistant" in TemplateCatalogService.REGISTERED_KEYS)
        assertEquals(9, TemplateCatalogService.CATEGORY_BY_KEY.size)    }
}
