package top.foxball.video.shared

/** 所有 HTTP 接口共用的响应体：状态码、消息和业务数据。 */
data class Response(
    val status: Int,
    val message: String,
    val data: Any?
)
