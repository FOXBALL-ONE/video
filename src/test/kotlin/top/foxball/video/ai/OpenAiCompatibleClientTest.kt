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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiCompatibleClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: OpenAiCompatibleClient
    private lateinit var connection: AiProviderConnection
    private val requests = CopyOnWriteArrayList<CapturedRequest>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
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
        connection = AiProviderConnection(1, "test", URI("http://127.0.0.1:${server.address.port}"), "test-key", profile)
        client = OpenAiCompatibleClient(
            ProxyAwareHttpTransportFactory(),
            AiInvocationGuard(),
            JsonMapper.builder().build(),
            AiClientProperties(modelCatalogTtl = Duration.ZERO),
        )
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `uses OpenAI compatible model chat stream and image endpoints`() {
        val models = client.listModels(connection).block()
        assertEquals(listOf("demo-model"), models?.map { it.id })

        val chat = client.createChatCompletion(
            connection,
            AiChatCompletionCommand(
                providerConfigId = 1,
                model = "demo-model",
                messages = listOf(AiChatMessage(AiChatRole.USER, "hello")),
            ),
        ).block()
        assertEquals("world", chat?.primaryChoice?.message?.content)

        val deltas = client.streamChatCompletion(
            connection,
            AiChatCompletionCommand(
                providerConfigId = 1,
                model = "demo-model",
                messages = listOf(AiChatMessage(AiChatRole.USER, "stream")),
            ),
        ).collectList().block()
        assertEquals(listOf("part one", "part two"), deltas?.mapNotNull { it.content })

        val generated = client.generateImage(
            connection,
            AiImageGenerationCommand(1, "image-model", "a blue square"),
        ).block()
        assertEquals("YWJj", generated?.images?.single()?.base64Data)

        val edited = client.editImage(
            connection,
            AiImageEditCommand(
                providerConfigId = 1,
                model = "image-model",
                prompt = "make it green",
                images = listOf(AiImageInput("source.png", "image/png", byteArrayOf(1, 2, 3))),
            ),
        ).block()
        assertEquals("https://example.test/output.png", edited?.images?.single()?.url)

        assertEquals(
            listOf("/v1/models", "/v1/chat/completions", "/v1/chat/completions", "/v1/images/generations", "/v1/images/edits"),
            requests.map { it.path },
        )
        assertTrue(requests.all { it.authorization == "Bearer test-key" })
        assertTrue(requests.last().contentType?.startsWith("multipart/form-data") == true)
        assertTrue(requests.last().body.contains("image[]"))
    }

    @Test
    fun `rejects private destinations when profile does not allow them`() {
        val policy = AiProviderUriPolicy()
        val protectedConnection = connection.copy(profile = connection.profile.copy(allowPrivateNetwork = false))
        val exception = kotlin.runCatching { policy.validateDestination(protectedConnection.baseUri, protectedConnection.profile) }.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is AiConfigurationException)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().decodeToString()
        requests += CapturedRequest(
            path = exchange.requestURI.path,
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            body = body,
        )
        when (exchange.requestURI.path) {
            "/v1/models" -> respondJson(exchange, """{"data":[{"id":"demo-model","owned_by":"test"}]}""")
            "/v1/chat/completions" -> {
                if (body.contains("\"stream\":true")) {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        output.write("data: {\"id\":\"chat-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"part one\"}}]}\n\n".toByteArray())
                        output.write("data: {\"id\":\"chat-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"part two\"},\"finish_reason\":\"stop\"}]}\n\n".toByteArray())
                        output.write("data: [DONE]\n\n".toByteArray())
                    }
                } else {
                    respondJson(exchange, """{"id":"chat-1","model":"demo-model","choices":[{"index":0,"message":{"role":"assistant","content":"world"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
                }
            }
            "/v1/images/generations" -> respondJson(exchange, """{"created":1,"data":[{"b64_json":"YWJj"}]}""")
            "/v1/images/edits" -> respondJson(exchange, """{"created":2,"data":[{"url":"https://example.test/output.png"}]}""")
            else -> respondJson(exchange, """{"error":"not found"}""", 404)
        }
    }

    private fun respondJson(exchange: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class CapturedRequest(
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )
}
