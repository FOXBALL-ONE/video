package top.foxball.video.handler

import org.junit.jupiter.api.Test
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiRateLimitException
import kotlin.test.assertEquals

class GlobalExceptionHandlerAiTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `maps ai configuration errors to bad request`() {
        val response = handler.onAiConfigurationException(AiConfigurationException("配置错误"))

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `maps ai rate limits with retry header`() {
        val response = handler.onAiRateLimitException(AiRateLimitException(7))

        assertEquals(429, response.statusCode.value())
        assertEquals("7", response.headers.getFirst("Retry-After"))
    }
}
