package top.foxball.video.ai

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiResultDownloaderTest {
    private lateinit var server: HttpServer
    private lateinit var imageServer: HttpServer
    private lateinit var downloader: AiResultDownloader
    private lateinit var connection: AiProviderConnection
    private var authorization: String? = null

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        imageServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        imageServer.createContext("/image.png") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        imageServer.start()
        val profile = AiConnectionProfile(
            name = "test",
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
        connection = AiProviderConnection(
            providerConfigId = 1,
            provider = "test",
            baseUri = URI("http://127.0.0.1:${server.address.port}"),
            apiKey = "secret",
            profile = profile,
        )
        downloader = AiResultDownloader(ProxyAwareHttpTransportFactory())
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        imageServer.stop(0)
    }

    @Test
    fun `downloads external signed url without forwarding api key`() {
        val result = downloader.download(
            connection,
            "http://localhost:${imageServer.address.port}/image.png?sig=signed",
        ).block()

        assertEquals("image/png", result?.contentType)
        assertEquals(8, result?.bytes?.size)
        assertNull(authorization)
    }
}
