package top.foxball.video.ai

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
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
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return Mono.error(AiProtocolException("模型图像 URL 不符合安全策略"))
        }
        if (uri.host != connection.baseUri.host && !uriPolicy.matchesHost(connection.baseUri.host, uri.host)) {
            return Mono.error(AiProtocolException("模型图像 URL 主机与提供商不匹配"))
        }
        uriPolicy.validateDestination(uri, connection.profile)
        if (maxBytes <= 0 || maxBytes > MAX_ALLOWED_BYTES) return Mono.error(AiConfigurationException("图像下载大小限制不正确"))

        return transportFactory.client(connection.profile)
            .get()
            .uri(uri)
            .headers { headers ->
                headers.setBearerAuth(connection.apiKey)
                headers.set(HttpHeaders.USER_AGENT, "video-ai-client")
            }
            .exchangeToMono { response ->
                if (!response.statusCode().is2xxSuccessful) {
                    return@exchangeToMono Mono.error(AiUpstreamException(response.statusCode().value()))
                }
                val contentType = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM)
                if (!contentType.type.equals("image", ignoreCase = true)) {
                    return@exchangeToMono Mono.error(AiProtocolException("模型图像响应不是图片内容"))
                }
                response.bodyToMono(ByteArray::class.java).flatMap { bytes ->
                    if (bytes.size > maxBytes) Mono.error(AiProtocolException("模型图像超过大小限制"))
                    else Mono.just(DownloadedAiImage(contentType.toString(), bytes))
                }
            }
    }

    data class DownloadedAiImage(val contentType: String, val bytes: ByteArray)

    private companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
        const val MAX_ALLOWED_BYTES = 100L * 1024 * 1024
    }
}
