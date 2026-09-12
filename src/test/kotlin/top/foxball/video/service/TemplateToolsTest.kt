package top.foxball.video.service

import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateToolsTest {
    private val mapper = ObjectMapper()

    private val registry = TemplateToolRegistry(
        listOf(StoryboardListTool(mapper), ShotContinuityTool(mapper)),
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
        assertEquals(listOf("shot_continuity_check", "storyboard_list_validate"), registry.listTools())

        assertFailsWith<IllegalArgumentException> { registry.requireAvailable(listOf("nope")) }

        assertEquals(2, registry.callbacksFor(listOf("storyboard_list_validate", "shot_continuity_check")).size)
        assertEquals(1, registry.callbacksFor(listOf("storyboard_list_validate", "storyboard_list_validate")).size)
    }
}
