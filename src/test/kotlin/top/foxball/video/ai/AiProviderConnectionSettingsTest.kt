package top.foxball.video.ai

import org.junit.jupiter.api.Test
import top.foxball.video.config.AiClientProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AiProviderConnectionSettingsTest {
    @Test
    fun `persists custom proxy settings and restores an isolated client profile`() {
        val settings = AiProviderConnectionSettings(
            useDefaultTransport = false,
            proxyMode = AiProxyMode.SOCKS5,
            proxyHost = "127.0.0.1",
            proxyPort = 1080,
            proxyUsername = "proxy-user",
            proxyPassword = "proxy-password",
            nonProxyHosts = listOf("localhost", "*.internal"),
            dnsMode = AiDnsMode.PROXY,
            proxyRemoteDnsAllowedHosts = listOf("api.example.com"),
            allowPrivateNetwork = true,
            connectTimeoutSeconds = 12,
            responseTimeoutSeconds = 95,
        )

        val restored = AiProviderConnectionSettings.fromStorage(settings.toStorage())
        val profile = restored.resolveProfile(
            source = AiClientProperties.ConnectionProfile(),
            name = "provider:1",
            cacheKey = "provider:1:v1",
        )

        assertEquals(settings, restored)
        assertEquals(AiProxyMode.SOCKS5, profile.proxyMode)
        assertEquals("127.0.0.1", profile.proxyHost)
        assertEquals(1080, profile.proxyPort)
        assertEquals("provider:1:v1", profile.cacheKey)
    }

    @Test
    fun `default transport storage does not retain custom proxy credentials`() {
        val settings = AiProviderConnectionSettings(
            useDefaultTransport = true,
            proxyMode = AiProxyMode.HTTP,
            proxyPassword = "proxy-password",
        )

        val stored = settings.toStorage()
        val restored = AiProviderConnectionSettings.fromStorage(stored)

        assertEquals(mapOf("use_default_transport" to "true"), stored)
        assertEquals(AiProviderConnectionSettings(), restored)
        assertFalse(stored.containsKey("proxy_password"))
        assertNull(restored.proxyPassword)

        val directStorage = AiProviderConnectionSettings(
            useDefaultTransport = false,
            proxyMode = AiProxyMode.DIRECT,
            proxyPassword = "proxy-password",
            dnsMode = AiDnsMode.PROXY,
        ).toStorage()

        assertEquals(AiDnsMode.LOCAL.name, directStorage["dns_mode"])
        assertFalse(directStorage.containsKey("proxy_password"))
    }
}
