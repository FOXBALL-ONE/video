package top.foxball.video.ai

/** AI 提供商调用异常的基类，异常消息不得包含密钥、提示词或原始响应。 */
open class AiClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class AiConfigurationException(message: String) : AiClientException(message)

class AiProxyException(message: String, cause: Throwable? = null) : AiClientException(message, cause)

class AiUpstreamAuthenticationException(val statusCode: Int, val responseContent: String? = null) : AiClientException("模型服务认证失败（$statusCode）")

class AiRateLimitException(val retryAfterSeconds: Long?, val responseContent: String? = null) : AiClientException("模型服务请求过于频繁")

class AiCircuitOpenException : AiClientException("模型服务暂时不可用，请稍后重试")

class AiUpstreamException(val statusCode: Int, val responseContent: String? = null) : AiClientException("模型服务返回错误（$statusCode）")

class AiProtocolException(message: String) : AiClientException(message)

class AiTransportException(message: String, cause: Throwable? = null) : AiClientException(message, cause)
