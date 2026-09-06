package top.foxball.video.service

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import top.foxball.video.entity.jdbc.PromptCategory

class ScriptAssetPromptContractTest {
    @Test
    fun `asset types resolve to dedicated prompt categories`() {
        assertEquals(PromptCategory.SCRIPT_CHARACTER, PromptCatalogService.CATEGORY_BY_KEY["script_character_extraction"])
        assertEquals(PromptCategory.SCRIPT_SCENE, PromptCatalogService.CATEGORY_BY_KEY["script_scene_extraction"])
        assertEquals(PromptCategory.SCRIPT_PROP, PromptCatalogService.CATEGORY_BY_KEY["script_prop_extraction"])
    }

    @Test
    fun `dedicated prompt keys are registered`() {
        assertTrue("script_character_extraction" in PromptCatalogService.REGISTERED_KEYS)
        assertTrue("script_scene_extraction" in PromptCatalogService.REGISTERED_KEYS)
        assertTrue("script_prop_extraction" in PromptCatalogService.REGISTERED_KEYS)
        assertEquals(5, PromptCatalogService.CATEGORY_BY_KEY.size)
    }
}
