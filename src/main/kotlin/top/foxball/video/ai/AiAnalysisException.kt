package top.foxball.video.ai

/**
 * 包装剧本解析失败时的业务上下文，保留状态码、响应摘要、提供商和请求追踪号，
 * 供业务层将上游错误转换为可反馈的结果。
 */
class AiAnalysisException(
    message: String,
    val errorCode: Int?,
    val responseContent: String?,
    val provider: String,
    val requestId: String,
    cause: Throwable? = null,
) : AiClientException(message, cause)
