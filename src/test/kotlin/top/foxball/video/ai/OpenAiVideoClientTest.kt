package top.foxball.video.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import top.foxball.video.config.AiClientProperties
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OpenAiVideoClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: OpenAiCompatibleClient
    private lateinit var connection: AiProviderConnection

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        val profile = AiConnectionProfile(
            name = "video-test",
            proxyMode = AiProxyMode.DIRECT,
            proxyHost = null,
            proxyPort = null,
            proxyUsername = null,
            proxyPassword = null,
            nonProxyHosts = emptyList(),
            dnsMode = AiDnsMode.LOCAL,
            proxyRemoteDnsAllowedHosts = emptyList(),
            allowPrivateNetwork = true,
            connectTimeout = Duration.ofSeconds(2),
            responseTimeout = Duration.ofSeconds(5),
            maxInFlightChatRequests = 2,
            maxInFlightImageRequests = 2,
            maxInFlightModelListRequests = 2,
        )
        connection = AiProviderConnection(1, "sora", URI("http://127.0.0.1:${server.address.port}"), "test-key", profile)
        client = OpenAiCompatibleClient(
            ProxyAwareHttpTransportFactory(),
            AiInvocationGuard(),
            JsonMapper.builder().build(),
            AiClientProperties(modelCatalogTtl = Duration.ZERO),
        )
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    @Test
    fun `creates polls and downloads video`() {
        val created = client.createVideo(connection, AiVideoGenerationCommand(1, "sora-2", "A paper plane", seconds = 8)).block()
        assertEquals("video_123", created?.id)
        assertEquals(AiVideoStatus.QUEUED, created?.status)

        val status = client.getVideo(connection, "video_123").block()
        assertEquals(AiVideoStatus.COMPLETED, status?.status)
        assertEquals(100, status?.progress)

        val content = client.downloadVideo(connection, "video_123").block()
        assertEquals("video/mp4", content?.contentType)
        assertContentEquals(byteArrayOf(1, 2, 3), content?.bytes)
    }

    private fun handle(exchange: HttpExchange) {
        exchange.requestBody.readAllBytes()
        when (exchange.requestURI.path) {
            "/v1/videos" -> respond(exchange, "{\"id\":\"video_123\",\"model\":\"sora-2\",\"status\":\"queued\"}")
            "/v1/videos/video_123" -> respond(exchange, "{\"id\":\"video_123\",\"model\":\"sora-2\",\"status\":\"completed\",\"progress\":100}")
            "/v1/videos/video_123/content" -> {
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                val bytes = byteArrayOf(1, 2, 3)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            else -> respond(exchange, "{\"error\":\"not found\"}", 404)
        }
    }

    private fun respond(exchange: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
