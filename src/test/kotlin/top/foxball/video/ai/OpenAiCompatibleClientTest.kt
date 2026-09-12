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
import kotlin.test.assertFailsWith
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
        assertEquals(listOf("第一段", "第二段"), deltas?.mapNotNull { it.content })

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
            listOf("/v1/models", "/v1/chat/completions", "/v1/responses", "/v1/images/generations", "/v1/images/edits"),
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

    @Test
    fun `normalizes image size aliases and sends generation options`() {
        requests.clear()
        client.generateImage(
            connection,
            AiImageGenerationCommand(
                providerConfigId = 1,
                model = "image-model",
                prompt = "a blue square",
                count = 2,
                size = "2k",
                quality = "high",
            ),
        ).block()

        val body = requests.single().body
        assertTrue(body.contains("\"n\":2"))
        assertTrue(body.contains("\"size\":\"2K\""))
        assertTrue(body.contains("\"quality\":\"high\""))
    }

    @Test
    fun `sends chat json schema response format`() {
        requests.clear()
        client.createChatCompletion(
            connection,
            AiChatCompletionCommand(
                providerConfigId = 1,
                model = "demo-model",
                messages = listOf(AiChatMessage(AiChatRole.USER, "hello")),
                responseFormat = "json_schema",
                responseSchema = JsonMapper.builder().build().readTree("""{"type":"object","properties":{"ok":{"type":"boolean"}}}"""),
            ),
        ).block()

        val body = requests.single().body
        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("\"schema\":{\"type\":\"object\""))
        assertTrue(body.contains("请以 json 格式输出结果。"))
    }

    @Test
    fun `adds json instruction for OpenAI Responses structured output`() {
        requests.clear()
        client.createChatCompletion(
            connection.copy(provider = "openai"),
            AiChatCompletionCommand(
                providerConfigId = 1,
                model = "gpt-test",
                messages = listOf(AiChatMessage(AiChatRole.SYSTEM, "只输出 JSON"), AiChatMessage(AiChatRole.USER, "分析剧本")),
                responseFormat = "json_object",
            ),
        ).block()

        val request = requests.single()
        assertEquals("/v1/responses", request.path)
        assertTrue(request.body.contains("\"type\":\"json_object\""))
        assertTrue(request.body.contains("请以 json 格式输出结果。"))
    }

    @Test
    fun `streams Responses text deltas for every provider`() {
        requests.clear()
        val deltas = client.streamChatCompletion(
            connection,
            AiChatCompletionCommand(
                providerConfigId = 1,
                model = "gpt-test",
                messages = listOf(AiChatMessage(AiChatRole.USER, "讲一个故事")),
            ),
        ).collectList().block()

        assertEquals(listOf("第一段", "第二段"), deltas?.mapNotNull { it.content })
        assertEquals("completed", deltas?.lastOrNull()?.finishReason)
        val request = requests.single()
        assertEquals("/v1/responses", request.path)
        assertTrue(request.body.contains("\"stream\":true"))
    }

    @Test
    fun `rejects malformed image options before sending`() {
        assertFailsWith<AiConfigurationException> {
            client.generateImage(
                connection,
                AiImageGenerationCommand(
                    providerConfigId = 1,
                    model = "image-model",
                    prompt = "test",
                    size = "2K/unsafe",
                ),
            )
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `reports an upstream error object returned with HTTP success`() {
        val exception = assertFailsWith<AiProtocolException> {
            client.generateImage(
                connection,
                AiImageGenerationCommand(1, "image-model", "trigger-image-error"),
            ).block()
        }

        assertEquals("模型服务返回错误：openai_error", exception.message)
        assertEquals("{\"error\":{\"message\":\"openai_error\",\"code\":\"upstream_failed\"}}", exception.responseContent)
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
            "/v1/responses" -> {
                if (body.contains("\"stream\":true")) {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        output.write("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"item-1\",\"delta\":\"第一段\"}\n\n".toByteArray())
                        output.write("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"item-1\",\"delta\":\"第二段\"}\n\n".toByteArray())
                        output.write("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"response-1\",\"status\":\"completed\"}}\n\n".toByteArray())
                    }
                } else {
                    respondJson(exchange, """{"id":"response-1","model":"gpt-test","output_text":"{\"ok\":true}","status":"completed"}""")
                }
            }
            "/v1/chat/completions" -> {
                if (body.contains("\"stream\":true")) {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        output.write("data: {\"id\":\"chat-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"part one\"}}]}\n\n".toByteArray())
                        output.write("data: {\"id\":\"chat-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"part two\"},\"finish_reason\":\"stop\"}]}\n\n".toByteArray())
                        output.write("data: {\"id\":\"chat-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\n".toByteArray())
                        output.write("data: [DONE]\n\n".toByteArray())
                    }
                } else {
                    respondJson(exchange, """{"id":"chat-1","model":"demo-model","choices":[{"index":0,"message":{"role":"assistant","content":"world"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
                }
            }
            "/v1/images/generations" -> if (body.contains("trigger-image-error")) {
                respondJson(exchange, """{"error":{"message":"openai_error","code":"upstream_failed"}}""")
            } else {
                respondJson(exchange, """{"created":1,"data":[{"b64_json":"YWJj"}]}""")
            }
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
