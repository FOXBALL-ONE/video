package top.foxball.video.ai.springai

import com.openai.errors.OpenAIException
import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import org.springframework.stereotype.Component
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiProtocolException
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.ai.AiTransportException
import top.foxball.video.ai.AiUpstreamAuthenticationException
import top.foxball.video.ai.AiUpstreamException
import java.io.IOException

/**
 * 把 Spring AI 与 OpenAI SDK 抛出的异常翻译为项目统一的 AI 客户端异常。
 *
 * 业务层、断路器与全局异常处理器只认识 [AiClientException] 家族，
 * 因此任何经 Spring AI 发出的调用都必须在离开调用层前完成翻译。
 */
@Component
class SpringAiExceptionMapper {
    /** 返回可直接抛出的异常对象；已经是项目异常的实例原样返回。 */
    fun translate(exception: Throwable): Throwable = when {
        exception is AiClientException -> exception
        exception is OpenAIServiceException -> mapServiceException(exception)
        exception is OpenAIIoException -> AiTransportException("无法连接模型服务", exception)
        exception is OpenAIInvalidDataException -> AiProtocolException("模型服务响应不符合协议", null)
        exception is OpenAIException -> AiTransportException("模型服务调用失败", exception)
        exception is IllegalArgumentException -> AiProtocolException(exception.message?.take(MAX_MESSAGE_CHARS) ?: "模型服务调用参数不正确", null)
        hasIoCause(exception) -> AiTransportException("无法连接模型服务", exception)
        else -> AiTransportException("模型服务调用失败", exception)
    }

    /** 按上游 HTTP 状态码映射，保留截断后的响应正文用于诊断。 */
    private fun mapServiceException(exception: OpenAIServiceException): AiClientException {
        val statusCode = exception.statusCode()
        val responseContent = runCatching { exception.body().toString() }.getOrNull()?.take(MAX_ERROR_BODY_CHARS)
        return when (statusCode) {
            401, 403 -> AiUpstreamAuthenticationException(statusCode, responseContent)
            429 -> AiRateLimitException(retryAfterSeconds(exception), responseContent)
            else -> AiUpstreamException(statusCode, responseContent)
        }
    }

    /** 上游限流响应可能通过 Retry-After 头给出建议等待秒数。 */
    private fun retryAfterSeconds(exception: OpenAIServiceException): Long? =
        runCatching {
            exception.headers().values("retry-after").firstOrNull()?.trim()?.toDoubleOrNull()?.toLong()
        }.getOrNull()

    /** 网络层异常可能被包装多层，逐层检查原因链中的 IO 失败。 */
    private fun hasIoCause(exception: Throwable): Boolean {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is IOException) return true
            current = current.cause
            depth++
        }
        return false
    }

    private companion object {
        const val MAX_ERROR_BODY_CHARS = 8_000
        const val MAX_MESSAGE_CHARS = 500
        const val MAX_CAUSE_DEPTH = 8
    }
}
