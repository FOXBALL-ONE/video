package top.foxball.video.ai

/** 剧本解析失败时返回给业务层的可反馈错误上下文。 */
class AiAnalysisException(
    message: String,
    val errorCode: Int?,
    val responseContent: String?,
    val provider: String,
    val requestId: String,
    cause: Throwable? = null,
) : AiClientException(message, cause)
