package top.foxball.video.service

import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateToolsTest {
    private val mapper = ObjectMapper()

    private val registry = TemplateToolRegistry(
        listOf(StoryboardListTool(mapper), ShotContinuityTool(mapper), AssetPatchTool(mapper)),
    )

    private fun callback(name: String) = registry.callbacksFor(listOf(name)).first()

    @Test
    fun `storyboard_list_validate normalizes indices and fills missing duration`() {
        val input = mapper.writeValueAsString(
            mapOf(
                "shots" to listOf(
                    mapOf("description" to "远景", "duration_seconds" to 2.0),
                    mapOf("description" to "中景", "duration_seconds" to 90.0),
                    mapOf("description" to "近景"),
                ),
            ),
        )

        val root = mapper.readTree(callback("storyboard_list_validate").call(input))

        val shots = root.path("shots")
        assertEquals(3, shots.size())
        assertEquals(1, shots[0].path("index").asInt())
        assertEquals(2, shots[1].path("index").asInt())
        assertEquals(3, shots[2].path("index").asInt())
        assertEquals(2.0, shots[0].path("duration_seconds").asDouble())
        assertEquals(60.0, shots[1].path("duration_seconds").asDouble())
        assertEquals(3.0, shots[2].path("duration_seconds").asDouble())

        val warnings = root.path("warnings")
        val warningText = (0 until warnings.size()).map { warnings[it].asString() }
        assertTrue(warningText.any { it.contains("时长") }, "缺少时长相关警告：$warningText")
    }

    @Test
    fun `storyboard_list_validate truncates overlong description and rejects non-array shots`() {
        val tool = callback("storyboard_list_validate")

        val invalid = mapper.writeValueAsString(mapOf("shots" to "not-an-array"))
        assertFailsWith<IllegalArgumentException> { tool.call(invalid) }

        val longInput = mapper.writeValueAsString(
            mapOf(
                "shots" to listOf(
                    mapOf("description" to "x".repeat(3000), "duration_seconds" to 3.0),
                ),
            ),
        )
        val root = mapper.readTree(tool.call(longInput))
        assertEquals(2000, root.path("shots")[0].path("description").asString().length)
    }

    @Test
    fun `shot_continuity_check reports issues and shot_count`() {
        val input = mapper.writeValueAsString(
            mapOf(
                "shots" to listOf(
                    mapOf("description" to "同一画面", "duration_seconds" to 20.0),
                    mapOf("description" to "同一画面", "dialogue" to "你好", "characters" to emptyList<String>()),
                    mapOf(),
                ),
            ),
        )

        val root = mapper.readTree(callback("shot_continuity_check").call(input))

        val issues = root.path("issues")
        val codes = (0 until issues.size()).map { issues[it].path("code").asString() }.toSet()
        assertTrue("MISSING_DESCRIPTION" in codes, "缺少 MISSING_DESCRIPTION：$codes")
        assertTrue("DUPLICATE_SHOT" in codes, "缺少 DUPLICATE_SHOT：$codes")
        assertTrue("DIALOGUE_WITHOUT_CHARACTER" in codes, "缺少 DIALOGUE_WITHOUT_CHARACTER：$codes")
        assertTrue("LONG_SHOT" in codes, "缺少 LONG_SHOT：$codes")
        assertEquals(3, root.path("shot_count").asInt())
    }

    @Test
    fun `TemplateToolRegistry lists tools and deduplicates callbacks`() {
        assertEquals(listOf("asset_patch_validate", "shot_continuity_check", "storyboard_list_validate"), registry.listTools())

        assertFailsWith<IllegalArgumentException> { registry.requireAvailable(listOf("nope")) }

        assertEquals(2, registry.callbacksFor(listOf("storyboard_list_validate", "shot_continuity_check")).size)
        assertEquals(1, registry.callbacksFor(listOf("storyboard_list_validate", "storyboard_list_validate")).size)
    }

    @Test
    fun `asset_patch_validate keeps writable patches and settles enum values`() {
        val input = mapper.writeValueAsString(
            mapOf(
                "assets" to listOf(
                    mapOf("id" to 1, "type" to "CHARACTER"),
                    mapOf("id" to 2, "type" to "PROP"),
                ),
                "patches" to listOf(
                    mapOf("asset_id" to 1, "field" to "face_description", "value" to "  圆脸，单眼皮  ", "reason" to "补充人脸特征"),
                    mapOf("asset_id" to 2, "field" to "plot_function", "value" to "推动真相揭露", "reason" to "补全剧情作用"),
                    mapOf("asset_id" to 2, "field" to "status", "value" to "ready", "reason" to "已确认"),
                ),
            ),
        )

        val root = mapper.readTree(callback("asset_patch_validate").call(input))

        val patches = root.path("patches")
        assertEquals(3, patches.size())
        assertEquals("圆脸，单眼皮", patches[0].path("value").asString())
        assertEquals("CHARACTER", patches[0].path("asset_type").asString())
        assertEquals("READY", patches[2].path("value").asString())
        assertEquals(0, root.path("dropped").size())
    }

    @Test
    fun `asset_patch_validate drops patches the server could not write`() {
        val input = mapper.writeValueAsString(
            mapOf(
                "assets" to listOf(
                    mapOf("id" to 1, "type" to "PROP"),
                    mapOf("id" to 2, "type" to "CHARACTER"),
                ),
                "patches" to listOf(
                    mapOf("asset_id" to 99, "field" to "description", "value" to "不存在", "reason" to "x"),
                    mapOf("asset_id" to 1, "field" to "face_description", "value" to "道具没有人脸", "reason" to "x"),
                    mapOf("asset_id" to 1, "field" to "unknown_field", "value" to "值", "reason" to "x"),
                    mapOf("asset_id" to 1, "field" to "status", "value" to "DONE", "reason" to "x"),
                    mapOf("asset_id" to 1, "field" to "category", "value" to "   ", "reason" to "x"),
                    mapOf("asset_id" to 2, "field" to "hairstyle", "value" to "齐肩短发", "reason" to "x"),
                    mapOf("asset_id" to 2, "field" to "hairstyle", "value" to "高马尾", "reason" to "x"),
                    mapOf("asset_id" to 2, "field" to "notes", "value" to "y".repeat(120_000), "reason" to "x"),
                ),
            ),
        )

        val root = mapper.readTree(callback("asset_patch_validate").call(input))

        val dropped = root.path("dropped")
        val codes = (0 until dropped.size()).map { dropped[it].path("code").asString() }.toSet()
        assertEquals(
            setOf("UNKNOWN_ASSET", "FIELD_NOT_ALLOWED", "INVALID_VALUE", "EMPTY_VALUE", "DUPLICATE_PATCH"),
            codes,
        )
        val patches = root.path("patches")
        assertEquals(2, patches.size())
        assertEquals("齐肩短发", patches[0].path("value").asString())
        assertEquals(100_000, patches[1].path("value").asString().length)
        val warnings = root.path("warnings")
        val warningText = (0 until warnings.size()).map { warnings[it].asString() }
        assertTrue(warningText.any { it.contains("截断") }, "缺少截断警告：$warningText")
    }

    @Test
    fun `asset_patch_validate requires an array of patches`() {
        val tool = callback("asset_patch_validate")
        assertFailsWith<IllegalArgumentException> { tool.call(mapper.writeValueAsString(mapOf("patches" to "nope"))) }
        assertFailsWith<IllegalArgumentException> { tool.call(mapper.writeValueAsString(mapOf("assets" to emptyList<Any>(), "patches" to List(51) { mapOf("asset_id" to 1, "field" to "name", "value" to "n") }))) }
    }
}
