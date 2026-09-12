package top.foxball.video.service

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * TemplateSchemaValidator 的契约层单测：覆盖消息、变量、生成策略、技能/MCP/工具绑定与输出 schema。
 */
class TemplateSchemaValidatorTest {
    private val objectMapper = ObjectMapper()
    private val validator = TemplateSchemaValidator(objectMapper)

    // region validateMessages

    @Test
    fun `validateMessages accepts system and user pair`() {
        validator.validateMessages("""[{"role":"system","content":"You are helpful."},{"role":"user","content":"Hello"}]""")
    }

    @Test
    fun `validateMessages rejects missing system message`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMessages("""[{"role":"user","content":"Hello"}]""")
        }
        assertTrue(ex.message!!.contains("system"))
    }

    @Test
    fun `validateMessages rejects unsupported role`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMessages(
                """[{"role":"system","content":"ok"},{"role":"user","content":"hi"},{"role":"FUNCTION","content":"data"}]""",
            )
        }
        assertTrue(ex.message!!.contains("role"))
    }

    @Test
    fun `validateMessages rejects extra field`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMessages(
                """[{"role":"system","content":"ok","extra":"bad"},{"role":"user","content":"hi"}]""",
            )
        }
        assertTrue(ex.message!!.contains("未允许字段"))
    }

    @Test
    fun `validateMessages rejects double-brace variable in system message`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMessages("""[{"role":"system","content":"Hello {{name}}"},{"role":"user","content":"Hi"}]""")
        }
        assertTrue(ex.message!!.contains("system 消息不得插入运行时变量"))
    }

    // endregion

    // region validateVariablesSchema

    @Test
    fun `validateVariablesSchema rejects undeclared placeholder`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateVariablesSchema(
                """{"properties":{}}""",
                """[{"role":"system","content":"ok"},{"role":"user","content":"Hello {{undeclared}}"}]""",
            )
        }
        assertTrue(ex.message!!.contains("未声明变量"))
    }

    // endregion

    // region validateGenerationPolicy

    @Test
    fun `validateGenerationPolicy accepts seeded policy`() {
        validator.validateGenerationPolicy(
            """{"temperature":0.7,"max_completion_tokens":500,"response_format":"json_object","output_mode":"text"}""",
        )
    }

    @Test
    fun `validateGenerationPolicy rejects unknown field`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateGenerationPolicy("""{"temperature":0.7,"unknown_field":true}""")
        }
        assertTrue(ex.message!!.contains("未允许字段"))
    }

    @Test
    fun `validateGenerationPolicy rejects temperature above 2`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateGenerationPolicy("""{"temperature":3.0}""")
        }
        assertTrue(ex.message!!.contains("temperature"))
    }

    @Test
    fun `validateGenerationPolicy rejects response_format yaml`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateGenerationPolicy("""{"response_format":"yaml"}""")
        }
        assertTrue(ex.message!!.contains("response_format"))
    }

    @Test
    fun `validateGenerationPolicy rejects output_mode audio`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateGenerationPolicy("""{"output_mode":"audio"}""")
        }
        assertTrue(ex.message!!.contains("output_mode"))
    }

    @Test
    fun `validateGenerationPolicy rejects image count 0`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateGenerationPolicy("""{"image":{"count":0}}""")
        }
        assertTrue(ex.message!!.contains("image.count"))
    }

    @Test
    fun `validateGenerationPolicy rejects unknown image field`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateGenerationPolicy("""{"image":{"unknown_thing":true}}""")
        }
        assertTrue(ex.message!!.contains("image"))
    }

    // endregion

    // region validateSkills / validateMcpServers / validateTools

    @Test
    fun `validateSkills accepts valid payload`() {
        validator.validateSkills("""[{"key":"director"}]""")
    }

    @Test
    fun `validateSkills rejects non-array`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateSkills("""{"key":"director"}""")
        }
        assertTrue(ex.message!!.contains("JSON 数组"))
    }

    @Test
    fun `validateSkills rejects missing key`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateSkills("""[{}]""")
        }
        assertTrue(ex.message!!.contains("缺少 key"))
    }

    @Test
    fun `validateSkills rejects duplicate key`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateSkills("""[{"key":"director"},{"key":"director"}]""")
        }
        assertTrue(ex.message!!.contains("重复绑定"))
    }

    @Test
    fun `validateSkills rejects unknown field inside binding`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateSkills("""[{"key":"director","extra":"bad"}]""")
        }
        assertTrue(ex.message!!.contains("未允许字段"))
    }

    @Test
    fun `skillKeys returns declared keys in order`() {
        val keys = validator.skillKeys("""[{"key":"director"},{"key":"writer"}]""")
        assertEquals(listOf("director", "writer"), keys)
    }

    @Test
    fun `validateMcpServers accepts valid payload`() {
        validator.validateMcpServers("""[{"key":"fs","tools":["read_file"]}]""")
    }

    @Test
    fun `validateMcpServers rejects non-array`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMcpServers("""{"key":"fs"}""")
        }
        assertTrue(ex.message!!.contains("JSON 数组"))
    }

    @Test
    fun `validateMcpServers rejects missing key`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMcpServers("""[{}]""")
        }
        assertTrue(ex.message!!.contains("缺少 key"))
    }

    @Test
    fun `validateMcpServers rejects duplicate key`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMcpServers("""[{"key":"fs"},{"key":"fs"}]""")
        }
        assertTrue(ex.message!!.contains("重复绑定"))
    }

    @Test
    fun `validateMcpServers rejects unknown field inside binding`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMcpServers("""[{"key":"fs","extra":"bad"}]""")
        }
        assertTrue(ex.message!!.contains("未允许字段"))
    }

    @Test
    fun `validateMcpServers rejects non-string tool name`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateMcpServers("""[{"key":"fs","tools":[123]}]""")
        }
        assertTrue(ex.message!!.contains("工具名"))
    }

    @Test
    fun `mcpServerKeys returns declared keys in order`() {
        val keys = validator.mcpServerKeys("""[{"key":"fs"},{"key":"bash"}]""")
        assertEquals(listOf("fs", "bash"), keys)
    }

    @Test
    fun `validateTools accepts valid payload`() {
        validator.validateTools("""["storyboard_list_validate"]""")
    }

    @Test
    fun `validateTools rejects non-array`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateTools("{}")
        }
        assertTrue(ex.message!!.contains("JSON 数组"))
    }

    @Test
    fun `validateTools rejects non-string tool name`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateTools("""[123]""")
        }
        assertTrue(ex.message!!.contains("内置工具名"))
    }

    @Test
    fun `validateTools rejects duplicate tool`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateTools("""["tool_a","tool_a"]""")
        }
        assertTrue(ex.message!!.contains("重复绑定"))
    }

    @Test
    fun `toolNames returns declared names in order`() {
        val names = validator.toolNames("""["storyboard_list_validate","shot_continuity_check"]""")
        assertEquals(listOf("storyboard_list_validate", "shot_continuity_check"), names)
    }

    @Test
    fun `mcpToolWhitelist returns whitelist for known server`() {
        val whitelist = validator.mcpToolWhitelist("""[{"key":"fs","tools":["read_file","write_file"]}]""", "fs")
        assertEquals(listOf("read_file", "write_file"), whitelist)
    }

    @Test
    fun `mcpToolWhitelist returns null for unknown server`() {
        val whitelist = validator.mcpToolWhitelist("""[{"key":"fs","tools":["read_file"]}]""", "bash")
        assertEquals(null, whitelist)
    }

    // endregion

    // region validateVariables

    @Test
    fun `validateVariables rejects undeclared variable`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateVariables("""{"properties":{}}""", mapOf("undeclared" to "value"))
        }
        assertTrue(ex.message!!.contains("未声明变量"))
    }

    @Test
    fun `validateVariables rejects missing required variable`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateVariables(
                """{"properties":{"name":{"type":"string"}},"required":["name"]}""",
                emptyMap(),
            )
        }
        assertTrue(ex.message!!.contains("缺少必填字段"))
    }

    @Test
    fun `validateVariables accepts omitted optional variable`() {
        validator.validateVariables(
            """{"properties":{"name":{"type":"string","default":"hello"}}}""",
            emptyMap(),
        )
    }

    // endregion

    // region validateOutput

    @Test
    fun `validateOutput accepts matching object`() {
        validator.validateOutput(
            """{"type":"object","properties":{"ok":{"type":"boolean"}},"additionalProperties":false}""",
            objectMapper.readTree("""{"ok":true}"""),
        )
    }

    @Test
    fun `validateOutput reports wrong type`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateOutput(
                """{"type":"object","properties":{"count":{"type":"integer"}}}""",
                objectMapper.readTree("""{"count":"not_a_number"}"""),
            )
        }
        assertTrue(ex.message!!.contains("类型不正确"))
    }

    @Test
    fun `validateOutput reports extra field when additionalProperties is false`() {
        val ex = assertFailsWith<TemplateSchemaValidator.SchemaViolationException> {
            validator.validateOutput(
                """{"type":"object","properties":{"ok":{"type":"boolean"}},"additionalProperties":false}""",
                objectMapper.readTree("""{"ok":true,"extra":"bad"}"""),
            )
        }
        assertTrue(ex.message!!.contains("未允许字段"))
    }

    // endregion
}
