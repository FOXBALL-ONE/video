package top.foxball.video.controller

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * 资产清单助手接口的请求绑定契约。
 *
 * 前端提交的是 snake_case 的 JSON 正文，服务端用 Kotlin 数据类接收：这里用前端真实载荷确认字段
 * 能绑定成功，避免首次调用时才出现「字段全为 null」这类问题。
 */
class AssetAssistantControllerTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `binds the message payload the assets page sends`() {
        val payload = """
            {
              "provider_config_id": 3,
              "model": "gpt-4o-mini",
              "message": "本集还有哪些资产没准备好",
              "history": [
                {"role": "user", "content": "先看角色"},
                {"role": "assistant", "content": "林夏缺少人脸描述"}
              ]
            }
        """.trimIndent()

        val request = objectMapper.readValue(payload, AssetAssistantController.MessageRequest::class.java)

        assertEquals(3L, request.providerConfigId)
        assertEquals("gpt-4o-mini", request.model)
        assertEquals("本集还有哪些资产没准备好", request.message)
        assertEquals(2, request.history?.size)
        assertEquals("assistant", request.history?.get(1)?.role)
        assertEquals("林夏缺少人脸描述", request.history?.get(1)?.content)
    }

    @Test
    fun `binds the apply payload with expected values`() {
        val payload = """
            {
              "items": [
                {"asset_id": 31, "field": "face_description", "value": "鹅蛋脸，细眉", "expected_value": ""},
                {"asset_id": 32, "field": "status", "value": "READY", "expected_value": "DRAFT"},
                {"asset_id": 33, "field": "notes", "value": "换装后不再佩戴项圈"}
              ]
            }
        """.trimIndent()

        val request = objectMapper.readValue(payload, AssetAssistantController.ApplyRequest::class.java)

        assertEquals(3, request.items?.size)
        assertEquals(31L, request.items?.get(0)?.assetId)
        assertEquals("face_description", request.items?.get(0)?.field)
        assertEquals("", request.items?.get(0)?.expectedValue)
        assertEquals("DRAFT", request.items?.get(1)?.expectedValue)
        // 不带 expected_value 的补丁表示调用方不做并发校验。
        assertNull(request.items?.get(2)?.expectedValue)
        assertEquals("换装后不再佩戴项圈", request.items?.get(2)?.value)
    }

    @Test
    fun `keeps optional fields nullable when the client omits them`() {
        // Jackson 不会为缺失的构造参数填 Kotlin 默认值：可空声明 + 在控制器里兜底，避免空指针。
        val request = objectMapper.readValue(
            """{"provider_config_id": 1, "model": "m", "message": "检查资产"}""",
            AssetAssistantController.MessageRequest::class.java,
        )

        assertTrue(request.history.isNullOrEmpty())

        val apply = objectMapper.readValue("""{"items": [{"asset_id": 7, "field": "notes"}]}""", AssetAssistantController.ApplyRequest::class.java)
        assertEquals("notes", apply.items?.get(0)?.field)
        assertNull(apply.items?.get(0)?.value)

        val emptyApply = objectMapper.readValue("{}", AssetAssistantController.ApplyRequest::class.java)
        assertNull(emptyApply.items)
    }
}
