package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import top.foxball.video.entity.jdbc.AiModelProviderConfig
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
    /** 保存提供商配置；api_key 只写入数据库，不包含在响应中。 */
    fun save(
        @RequestParam("provider") @NotBlank @Size(max = 50) provider: String,
        @RequestParam("model_name") @NotBlank @Size(max = 100) modelName: String,
        @RequestParam("model_id") @NotBlank @Size(max = 200) modelId: String,
        @RequestParam("base_url") @NotBlank @Size(max = 500) baseUrl: String,
        @RequestParam("api_key") @NotBlank @Size(max = 1024) secretKey: String,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class ResponseData(
            val id: Long,
            val provider: String,
            val model: Map<String, String>,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        
        val config = service.save(
            AiModelProviderConfig(
                provider = provider,
                model = mapOf(modelName to modelId),
                baseUrl = baseUrl,
                secretKey = secretKey,
                enabled = enabled
            )
        )
        val rs = ResponseData(
            requireNotNull(config.id),
            config.provider,
            config.model,
            config.baseUrl,
            config.enabled,
            config.createdAt,
            config.updatedAt
        )
        return responseBuilder.ok().data(rs).build()
    }
    
    @GetMapping("/api/ai/model-providers/{id}")
    /** 按主键查询提供商配置。 */
    fun get(@PathVariable("id") id: Long): ResponseEntity<Response> {
        data class ResponseData(
            val id: Long,
            val provider: String,
            val model: Map<String, String>,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        
        val config = service.findById(id) ?: return responseBuilder.notFound().build()
        val rs = ResponseData(
            requireNotNull(config.id),
            config.provider,
            config.model,
            config.baseUrl,
            config.enabled,
            config.createdAt,
            config.updatedAt
        )
        return responseBuilder.ok().data(rs).build()
    }
    
    @GetMapping("/api/ai/model-providers")
    /** 按主键批量查询提供商配置。 */
    fun getBatch(@RequestParam("ids") ids: List<Long>): ResponseEntity<Response> {
        data class ConfigData(
            val id: Long,
            val provider: String,
            val model: Map<String, String>,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        
        data class ResponseData(val list: List<ConfigData>)
        
        val list = service.findAllByIds(ids).map {
            ConfigData(requireNotNull(it.id), it.provider, it.model, it.baseUrl, it.enabled, it.createdAt, it.updatedAt)
        }
        val rs = ResponseData(list)
        return responseBuilder.ok().data(rs).build()
    }
    
    @PutMapping("/api/ai/model-providers/{id}")
    /** 更新提供商配置；未传或为空的 api_key 保留原值。 */
    fun update(
        @PathVariable("id") id: Long,
        @RequestParam("provider") @NotBlank @Size(max = 50) provider: String,
        @RequestParam("model_name") @NotBlank @Size(max = 100) modelName: String,
        @RequestParam("model_id") @NotBlank @Size(max = 200) modelId: String,
        @RequestParam("base_url") @NotBlank @Size(max = 500) baseUrl: String,
        @RequestParam("api_key", required = false) @Size(max = 1024) secretKey: String?,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class ResponseData(
            val id: Long,
            val provider: String,
            val model: Map<String, String>,
            @param:JsonProperty("base_url") val baseUrl: String,
            val enabled: Boolean,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        
        val config = service.update(id, provider, mapOf(modelName to modelId), baseUrl, secretKey, enabled)
            ?: return responseBuilder.notFound().build()
        val rs = ResponseData(
            requireNotNull(config.id),
            config.provider,
            config.model,
            config.baseUrl,
            config.enabled,
            config.createdAt,
            config.updatedAt
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
