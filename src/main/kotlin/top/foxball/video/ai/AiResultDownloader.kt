package top.foxball.video.ai

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientRequestException
import reactor.core.publisher.Mono
import java.net.URI

/** 安全下载上游图像临时 URL；调用方不得绕过该组件直接请求模型返回地址。 */
@Component
class AiResultDownloader(
    private val transportFactory: ProxyAwareHttpTransportFactory,
) {
    private val uriPolicy = AiProviderUriPolicy()

    fun download(connection: AiProviderConnection, imageUrl: String, maxBytes: Long = DEFAULT_MAX_BYTES): Mono<DownloadedAiImage> {
        val uri = try {
            URI.create(imageUrl.trim())
        } catch (exception: IllegalArgumentException) {
            return Mono.error(AiProtocolException("模型图像 URL 格式不正确"))
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            return Mono.error(AiProtocolException("模型图像 URL 不符合安全策略"))
        }
        uriPolicy.validateDestination(uri, connection.profile)
        if (maxBytes <= 0 || maxBytes > MAX_ALLOWED_BYTES) return Mono.error(AiConfigurationException("图像下载大小限制不正确"))

        return transportFactory.client(connection.profile)
            .get()
            .uri(uri)
            .headers { headers ->
                // 临时图片经常位于供应商 CDN；跨域请求不能把模型 API Key 带过去。
                if (uri.host.equals(connection.baseUri.host, ignoreCase = true)) {
                    headers.setBearerAuth(connection.apiKey)
                }
                headers.set(HttpHeaders.USER_AGENT, "video-ai-client")
            }
            .exchangeToMono { response ->
                if (!response.statusCode().is2xxSuccessful) {
                    return@exchangeToMono Mono.error(AiUpstreamException(response.statusCode().value()))
                }
                val contentLength = response.headers().asHttpHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)?.toLongOrNull()
                if (contentLength != null && contentLength > maxBytes) {
                    return@exchangeToMono Mono.error(AiProtocolException("模型图像超过大小限制"))
                }
                response.bodyToMono(ByteArray::class.java).flatMap { bytes ->
                    if (bytes.size > maxBytes) Mono.error(AiProtocolException("模型图像超过大小限制"))
                    else {
                        val detectedType = runCatching { AiImageContentDetector.detect(bytes) }
                            .getOrElse { return@flatMap Mono.error(AiProtocolException("模型图像响应不是有效图片")) }
                        Mono.just(DownloadedAiImage(detectedType, bytes))
                    }
                }
            }
            .onErrorMap { exception ->
                when (exception) {
                    is AiClientException -> exception
                    is WebClientRequestException -> AiTransportException("无法下载模型图像", exception)
                    else -> AiTransportException("模型图像下载失败", exception)
                }
            }
    }

    data class DownloadedAiImage(val contentType: String, val bytes: ByteArray)

    private companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
        const val MAX_ALLOWED_BYTES = 100L * 1024 * 1024
    }
}
