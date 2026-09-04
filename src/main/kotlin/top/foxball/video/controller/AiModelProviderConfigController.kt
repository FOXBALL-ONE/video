package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiApiType
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiDnsMode
import top.foxball.video.ai.AiProviderConnectionSettings
import top.foxball.video.ai.AiProxyMode
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.ai.AiUpstreamAuthenticationException
import top.foxball.video.entity.jdbc.AiModelProviderConfig
import top.foxball.video.entity.jdbc.apiType
import top.foxball.video.service.AiModelProviderConfigService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** AI 模型提供商配置管理接口，支持单条和批量 CRUD。 */
class AiModelProviderConfigController(
    private val service: AiModelProviderConfigService,
    private val responseBuilder: ResponseBuilder,
) {
    @PostMapping("/api/ai/model-providers")
    /** 保存提供商配置；服务密钥和代理密码只写入数据库，不包含在响应中。 */
    fun save(
        @RequestParam("provider") @NotBlank @Size(max = 50) provider: String,
        @RequestParam("api_type", defaultValue = "CHAT") apiType: AiApiType,
        @RequestParam("model_id") @NotBlank @Size(max = 200) modelId: String,
        @RequestParam("base_url") @NotBlank @Size(max = 500) baseUrl: String,
        @RequestParam("api_key") @NotBlank @Size(max = 1024) secretKey: String,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
        @RequestParam("use_default_transport", defaultValue = "true") useDefaultTransport: Boolean,
        @RequestParam("proxy_mode", defaultValue = "DIRECT") proxyMode: AiProxyMode,
        @RequestParam("proxy_host", required = false) @Size(max = 255) proxyHost: String?,
        @RequestParam("proxy_port", required = false) @Min(1) @Max(65535) proxyPort: Int?,
        @RequestParam("proxy_username", required = false) @Size(max = 255) proxyUsername: String?,
        @RequestParam("proxy_password", required = false) @Size(max = 1024) proxyPassword: String?,
        @RequestParam("non_proxy_hosts", required = false) @Size(max = 2000) nonProxyHosts: String?,
        @RequestParam("dns_mode", defaultValue = "LOCAL") dnsMode: AiDnsMode,
        @RequestParam("proxy_remote_dns_allowed_hosts", required = false) @Size(max = 2000) proxyRemoteDnsAllowedHosts: String?,
        @RequestParam("allow_private_network", defaultValue = "false") allowPrivateNetwork: Boolean,
        @RequestParam("connect_timeout_seconds", defaultValue = "10") @Min(1) @Max(300) connectTimeoutSeconds: Long,
        @RequestParam("response_timeout_seconds", defaultValue = "90") @Min(1) @Max(900) responseTimeoutSeconds: Long,
    ): ResponseEntity<Response> {
        data class ResponseData(
            val id: Long,
            val provider: String,
            @param:JsonProperty("api_type") val apiType: String,
            @param:JsonProperty("model_id") val modelId: String,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("use_default_transport") val useDefaultTransport: Boolean,
            @param:JsonProperty("proxy_mode") val proxyMode: String,
            @param:JsonProperty("proxy_host") val proxyHost: String?,
            @param:JsonProperty("proxy_port") val proxyPort: Int?,
            @param:JsonProperty("proxy_username") val proxyUsername: String?,
            @param:JsonProperty("proxy_password_configured") val proxyPasswordConfigured: Boolean,
            @param:JsonProperty("non_proxy_hosts") val nonProxyHosts: List<String>,
            @param:JsonProperty("dns_mode") val dnsMode: String,
            @param:JsonProperty("proxy_remote_dns_allowed_hosts") val proxyRemoteDnsAllowedHosts: List<String>,
            @param:JsonProperty("allow_private_network") val allowPrivateNetwork: Boolean,
            @param:JsonProperty("connect_timeout_seconds") val connectTimeoutSeconds: Long,
            @param:JsonProperty("response_timeout_seconds") val responseTimeoutSeconds: Long,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )

        val nonProxyHostList = nonProxyHosts?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val remoteDnsHostList = proxyRemoteDnsAllowedHosts?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val resolvedDnsMode = if (proxyMode == AiProxyMode.DIRECT) AiDnsMode.LOCAL else dnsMode
        if (!useDefaultTransport && proxyMode != AiProxyMode.DIRECT && (proxyHost.isNullOrBlank() || proxyPort == null)) {
            return responseBuilder.badRequest().message("请填写代理主机和端口").build()
        }
        if (!useDefaultTransport && resolvedDnsMode == AiDnsMode.PROXY && remoteDnsHostList.isEmpty()) {
            return responseBuilder.badRequest().message("代理 DNS 模式需要代理及允许远程解析的主机").build()
        }
        val config = service.save(
            AiModelProviderConfig(
                provider = provider,
                model = mapOf("id" to modelId, "api_type" to apiType.name),
                baseUrl = baseUrl,
                secretKey = secretKey,
                connectionConfig = AiProviderConnectionSettings(
                    useDefaultTransport = useDefaultTransport,
                    proxyMode = proxyMode,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword?.takeIf { it.isNotBlank() },
                    nonProxyHosts = nonProxyHostList,
                    dnsMode = resolvedDnsMode,
                    proxyRemoteDnsAllowedHosts = remoteDnsHostList,
                    allowPrivateNetwork = allowPrivateNetwork,
                    connectTimeoutSeconds = connectTimeoutSeconds,
                    responseTimeoutSeconds = responseTimeoutSeconds,
                ).toStorage(),
                enabled = enabled,
            )
        )
        val connection = AiProviderConnectionSettings.fromStorage(config.connectionConfig)
        val rs = ResponseData(
            requireNotNull(config.id), config.provider, config.apiType.name, config.model["id"] ?: config.model.values.firstOrNull().orEmpty(),
            config.baseUrl, config.enabled, connection.useDefaultTransport, connection.proxyMode.name, connection.proxyHost,
            connection.proxyPort, connection.proxyUsername, !connection.proxyPassword.isNullOrBlank(), connection.nonProxyHosts,
            connection.dnsMode.name, connection.proxyRemoteDnsAllowedHosts, connection.allowPrivateNetwork,
            connection.connectTimeoutSeconds, connection.responseTimeoutSeconds, config.createdAt, config.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/model-providers/{id}")
    /** 按主键查询提供商配置。 */
    fun get(@PathVariable("id") id: Long): ResponseEntity<Response> {
        data class ResponseData(
            val id: Long,
            val provider: String,
            @param:JsonProperty("api_type") val apiType: String,
            @param:JsonProperty("model_id") val modelId: String,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("use_default_transport") val useDefaultTransport: Boolean,
            @param:JsonProperty("proxy_mode") val proxyMode: String,
            @param:JsonProperty("proxy_host") val proxyHost: String?,
            @param:JsonProperty("proxy_port") val proxyPort: Int?,
            @param:JsonProperty("proxy_username") val proxyUsername: String?,
            @param:JsonProperty("proxy_password_configured") val proxyPasswordConfigured: Boolean,
            @param:JsonProperty("non_proxy_hosts") val nonProxyHosts: List<String>,
            @param:JsonProperty("dns_mode") val dnsMode: String,
            @param:JsonProperty("proxy_remote_dns_allowed_hosts") val proxyRemoteDnsAllowedHosts: List<String>,
            @param:JsonProperty("allow_private_network") val allowPrivateNetwork: Boolean,
            @param:JsonProperty("connect_timeout_seconds") val connectTimeoutSeconds: Long,
            @param:JsonProperty("response_timeout_seconds") val responseTimeoutSeconds: Long,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )

        val config = service.findById(id) ?: return responseBuilder.notFound().build()
        val connection = AiProviderConnectionSettings.fromStorage(config.connectionConfig)
        val rs = ResponseData(
            requireNotNull(config.id), config.provider, config.apiType.name, config.model["id"] ?: config.model.values.firstOrNull().orEmpty(),
            config.baseUrl, config.enabled, connection.useDefaultTransport, connection.proxyMode.name, connection.proxyHost,
            connection.proxyPort, connection.proxyUsername, !connection.proxyPassword.isNullOrBlank(), connection.nonProxyHosts,
            connection.dnsMode.name, connection.proxyRemoteDnsAllowedHosts, connection.allowPrivateNetwork,
            connection.connectTimeoutSeconds, connection.responseTimeoutSeconds, config.createdAt, config.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/model-providers")
    /** 查询全部提供商配置，传入 ids 时仅返回对应配置。 */
    fun list(@RequestParam("ids", required = false) ids: List<Long>?): ResponseEntity<Response> {
        data class ConfigData(
            val id: Long,
            val provider: String,
            @param:JsonProperty("api_type") val apiType: String,
            @param:JsonProperty("model_id") val modelId: String,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("use_default_transport") val useDefaultTransport: Boolean,
            @param:JsonProperty("proxy_mode") val proxyMode: String,
            @param:JsonProperty("proxy_host") val proxyHost: String?,
            @param:JsonProperty("proxy_port") val proxyPort: Int?,
            @param:JsonProperty("proxy_username") val proxyUsername: String?,
            @param:JsonProperty("proxy_password_configured") val proxyPasswordConfigured: Boolean,
            @param:JsonProperty("non_proxy_hosts") val nonProxyHosts: List<String>,
            @param:JsonProperty("dns_mode") val dnsMode: String,
            @param:JsonProperty("proxy_remote_dns_allowed_hosts") val proxyRemoteDnsAllowedHosts: List<String>,
            @param:JsonProperty("allow_private_network") val allowPrivateNetwork: Boolean,
            @param:JsonProperty("connect_timeout_seconds") val connectTimeoutSeconds: Long,
            @param:JsonProperty("response_timeout_seconds") val responseTimeoutSeconds: Long,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class ResponseData(val list: List<ConfigData>)

        val configs = ids?.takeIf { it.isNotEmpty() }?.let(service::findAllByIds) ?: service.findAll()
        val list = configs.map { config ->
            val connection = AiProviderConnectionSettings.fromStorage(config.connectionConfig)
            ConfigData(
                requireNotNull(config.id), config.provider, config.apiType.name, config.model["id"] ?: config.model.values.firstOrNull().orEmpty(),
                config.baseUrl, config.enabled, connection.useDefaultTransport, connection.proxyMode.name, connection.proxyHost,
                connection.proxyPort, connection.proxyUsername, !connection.proxyPassword.isNullOrBlank(), connection.nonProxyHosts,
                connection.dnsMode.name, connection.proxyRemoteDnsAllowedHosts, connection.allowPrivateNetwork,
                connection.connectTimeoutSeconds, connection.responseTimeoutSeconds, config.createdAt, config.updatedAt,
            )
        }
        val rs = ResponseData(list)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/ai/model-providers/models")
    /** 通过 OpenAI 兼容的 /v1/models 接口拉取模型 ID，并使用表单中的连接设置。 */
    fun fetchModels(
        @RequestParam("base_url") @NotBlank @Size(max = 500) baseUrl: String,
        @RequestParam("api_type", defaultValue = "CHAT") apiType: AiApiType,
        @RequestParam("api_key", required = false) @Size(max = 1024) apiKey: String?,
        @RequestParam("config_id", required = false) configId: Long?,
        @RequestParam("provider", required = false) @Size(max = 50) provider: String?,
        @RequestParam("use_default_transport", defaultValue = "true") useDefaultTransport: Boolean,
        @RequestParam("proxy_mode", defaultValue = "DIRECT") proxyMode: AiProxyMode,
        @RequestParam("proxy_host", required = false) @Size(max = 255) proxyHost: String?,
        @RequestParam("proxy_port", required = false) @Min(1) @Max(65535) proxyPort: Int?,
        @RequestParam("proxy_username", required = false) @Size(max = 255) proxyUsername: String?,
        @RequestParam("proxy_password", required = false) @Size(max = 1024) proxyPassword: String?,
        @RequestParam("non_proxy_hosts", required = false) @Size(max = 2000) nonProxyHosts: String?,
        @RequestParam("dns_mode", defaultValue = "LOCAL") dnsMode: AiDnsMode,
        @RequestParam("proxy_remote_dns_allowed_hosts", required = false) @Size(max = 2000) proxyRemoteDnsAllowedHosts: String?,
        @RequestParam("allow_private_network", defaultValue = "false") allowPrivateNetwork: Boolean,
        @RequestParam("connect_timeout_seconds", defaultValue = "10") @Min(1) @Max(300) connectTimeoutSeconds: Long,
        @RequestParam("response_timeout_seconds", defaultValue = "90") @Min(1) @Max(900) responseTimeoutSeconds: Long,
    ): ResponseEntity<Response> {
        data class ModelData(val id: String)
        data class ResponseData(val list: List<ModelData>)

        val config = configId?.let { service.findById(it) ?: return responseBuilder.notFound().build() }
        val secretKey = apiKey?.takeIf { it.isNotBlank() } ?: config?.secretKey
            ?: return responseBuilder.badRequest().message("请先填写 API Key").build()
        val nonProxyHostList = nonProxyHosts?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val remoteDnsHostList = proxyRemoteDnsAllowedHosts?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val resolvedDnsMode = if (proxyMode == AiProxyMode.DIRECT) AiDnsMode.LOCAL else dnsMode
        if (!useDefaultTransport && proxyMode != AiProxyMode.DIRECT && (proxyHost.isNullOrBlank() || proxyPort == null)) {
            return responseBuilder.badRequest().message("请填写代理主机和端口").build()
        }
        if (!useDefaultTransport && resolvedDnsMode == AiDnsMode.PROXY && remoteDnsHostList.isEmpty()) {
            return responseBuilder.badRequest().message("代理 DNS 模式需要代理及允许远程解析的主机").build()
        }
        val previousSettings = config?.let { AiProviderConnectionSettings.fromStorage(it.connectionConfig) }
        val connectionSettings = AiProviderConnectionSettings(
            useDefaultTransport = useDefaultTransport,
            proxyMode = proxyMode,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = proxyPassword?.takeIf { it.isNotBlank() } ?: previousSettings?.proxyPassword,
            nonProxyHosts = nonProxyHostList,
            dnsMode = resolvedDnsMode,
            proxyRemoteDnsAllowedHosts = remoteDnsHostList,
            allowPrivateNetwork = allowPrivateNetwork,
            connectTimeoutSeconds = connectTimeoutSeconds,
            responseTimeoutSeconds = responseTimeoutSeconds,
        )
        val list = try {
            service.fetchModelIds(
                config?.provider ?: provider?.trim().orEmpty().ifBlank { "default" },
                baseUrl,
                secretKey,
                connectionSettings,
            ).map(::ModelData)
        } catch (exception: AiConfigurationException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } catch (exception: AiUpstreamAuthenticationException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } catch (exception: AiRateLimitException) {
            return responseBuilder.tooManyRequests().retryAfter(exception.retryAfterSeconds ?: 1).message(exception.message).build()
        } catch (exception: AiClientException) {
            return responseBuilder.serviceUnavailable().message(exception.message).build()
        }
        val rs = ResponseData(list)
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/ai/model-providers/{id}")
    /** 更新提供商配置；为空的服务或代理密钥保留原值。 */
    fun update(
        @PathVariable("id") id: Long,
        @RequestParam("provider") @NotBlank @Size(max = 50) provider: String,
        @RequestParam("api_type", defaultValue = "CHAT") apiType: AiApiType,
        @RequestParam("model_id") @NotBlank @Size(max = 200) modelId: String,
        @RequestParam("base_url") @NotBlank @Size(max = 500) baseUrl: String,
        @RequestParam("api_key", required = false) @Size(max = 1024) secretKey: String?,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
        @RequestParam("use_default_transport", defaultValue = "true") useDefaultTransport: Boolean,
        @RequestParam("proxy_mode", defaultValue = "DIRECT") proxyMode: AiProxyMode,
        @RequestParam("proxy_host", required = false) @Size(max = 255) proxyHost: String?,
        @RequestParam("proxy_port", required = false) @Min(1) @Max(65535) proxyPort: Int?,
        @RequestParam("proxy_username", required = false) @Size(max = 255) proxyUsername: String?,
        @RequestParam("proxy_password", required = false) @Size(max = 1024) proxyPassword: String?,
        @RequestParam("non_proxy_hosts", required = false) @Size(max = 2000) nonProxyHosts: String?,
        @RequestParam("dns_mode", defaultValue = "LOCAL") dnsMode: AiDnsMode,
        @RequestParam("proxy_remote_dns_allowed_hosts", required = false) @Size(max = 2000) proxyRemoteDnsAllowedHosts: String?,
        @RequestParam("allow_private_network", defaultValue = "false") allowPrivateNetwork: Boolean,
        @RequestParam("connect_timeout_seconds", defaultValue = "10") @Min(1) @Max(300) connectTimeoutSeconds: Long,
        @RequestParam("response_timeout_seconds", defaultValue = "90") @Min(1) @Max(900) responseTimeoutSeconds: Long,
    ): ResponseEntity<Response> {
        data class ResponseData(
            val id: Long,
            val provider: String,
            @param:JsonProperty("api_type") val apiType: String,
            @param:JsonProperty("model_id") val modelId: String,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("use_default_transport") val useDefaultTransport: Boolean,
            @param:JsonProperty("proxy_mode") val proxyMode: String,
            @param:JsonProperty("proxy_host") val proxyHost: String?,
            @param:JsonProperty("proxy_port") val proxyPort: Int?,
            @param:JsonProperty("proxy_username") val proxyUsername: String?,
            @param:JsonProperty("proxy_password_configured") val proxyPasswordConfigured: Boolean,
            @param:JsonProperty("non_proxy_hosts") val nonProxyHosts: List<String>,
            @param:JsonProperty("dns_mode") val dnsMode: String,
            @param:JsonProperty("proxy_remote_dns_allowed_hosts") val proxyRemoteDnsAllowedHosts: List<String>,
            @param:JsonProperty("allow_private_network") val allowPrivateNetwork: Boolean,
            @param:JsonProperty("connect_timeout_seconds") val connectTimeoutSeconds: Long,
            @param:JsonProperty("response_timeout_seconds") val responseTimeoutSeconds: Long,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )

        val nonProxyHostList = nonProxyHosts?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val remoteDnsHostList = proxyRemoteDnsAllowedHosts?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val resolvedDnsMode = if (proxyMode == AiProxyMode.DIRECT) AiDnsMode.LOCAL else dnsMode
        if (!useDefaultTransport && proxyMode != AiProxyMode.DIRECT && (proxyHost.isNullOrBlank() || proxyPort == null)) {
            return responseBuilder.badRequest().message("请填写代理主机和端口").build()
        }
        if (!useDefaultTransport && resolvedDnsMode == AiDnsMode.PROXY && remoteDnsHostList.isEmpty()) {
            return responseBuilder.badRequest().message("代理 DNS 模式需要代理及允许远程解析的主机").build()
        }
        val config = service.update(
            id, provider, modelId, baseUrl, secretKey, enabled,
            AiProviderConnectionSettings(
                useDefaultTransport = useDefaultTransport,
                proxyMode = proxyMode,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword?.takeIf { it.isNotBlank() },
                nonProxyHosts = nonProxyHostList,
                dnsMode = resolvedDnsMode,
                proxyRemoteDnsAllowedHosts = remoteDnsHostList,
                allowPrivateNetwork = allowPrivateNetwork,
                connectTimeoutSeconds = connectTimeoutSeconds,
                responseTimeoutSeconds = responseTimeoutSeconds,
            ), apiType,
        ) ?: return responseBuilder.notFound().build()
        val connection = AiProviderConnectionSettings.fromStorage(config.connectionConfig)
        val rs = ResponseData(
            requireNotNull(config.id), config.provider, config.apiType.name, config.model["id"] ?: config.model.values.firstOrNull().orEmpty(),
            config.baseUrl, config.enabled, connection.useDefaultTransport, connection.proxyMode.name, connection.proxyHost,
            connection.proxyPort, connection.proxyUsername, !connection.proxyPassword.isNullOrBlank(), connection.nonProxyHosts,
            connection.dnsMode.name, connection.proxyRemoteDnsAllowedHosts, connection.allowPrivateNetwork,
            connection.connectTimeoutSeconds, connection.responseTimeoutSeconds, config.createdAt, config.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping("/api/ai/model-providers/{id}")
    /** 删除单个提供商配置。 */
    fun delete(@PathVariable("id") id: Long): ResponseEntity<Response> {
        if (!service.deleteById(id)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to id, "deleted" to true)).build()
    }

    @DeleteMapping("/api/ai/model-providers")
    /** 批量删除提供商配置，并返回实际删除的主键。 */
    fun deleteBatch(@RequestParam("ids") ids: List<Long>): ResponseEntity<Response> {
        val deletedIds = service.deleteAllByIds(ids)
        return responseBuilder.ok().data(mapOf("ids" to deletedIds, "deleted" to deletedIds.size)).build()
    }
}
