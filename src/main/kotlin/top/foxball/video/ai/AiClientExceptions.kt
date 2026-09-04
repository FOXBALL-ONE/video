package top.foxball.video.ai

/** AI 提供商调用异常的基类，异常消息不得包含密钥、提示词或原始响应。 */
open class AiClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 表示本地 AI 配置或调用参数不满足校验约束。 */
class AiConfigurationException(message: String) : AiClientException(message)

/** 表示代理建立连接、代理认证或代理策略执行失败。 */
class AiProxyException(message: String, cause: Throwable? = null) : AiClientException(message, cause)

/** 表示上游服务拒绝认证，响应正文仅作为内部诊断上下文保留。 */
class AiUpstreamAuthenticationException(val statusCode: Int, val responseContent: String? = null) : AiClientException("模型服务认证失败（$statusCode）")

/** 表示触发上游或本地并发限流，可选携带 Retry-After 秒数。 */
class AiRateLimitException(val retryAfterSeconds: Long?, val responseContent: String? = null) : AiClientException("模型服务请求过于频繁")

/** 表示连接断路器处于打开状态，当前请求尚未发送到上游。 */
class AiCircuitOpenException : AiClientException("模型服务暂时不可用，请稍后重试")

/** 表示上游返回非认证、非限流的 HTTP 错误状态。 */
class AiUpstreamException(val statusCode: Int, val responseContent: String? = null) : AiClientException("模型服务返回错误（$statusCode）")

/** 表示上游响应无法按约定的 JSON、SSE 或媒体格式解析。 */
class AiProtocolException(message: String) : AiClientException(message)

/** 表示 DNS、连接、读取等底层传输操作失败。 */
class AiTransportException(message: String, cause: Throwable? = null) : AiClientException(message, cause)
