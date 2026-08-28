package top.foxball.video.service

import org.springframework.stereotype.Service
import top.foxball.video.entity.jdbc.AiModelProviderConfig
import top.foxball.video.repository.AiModelProviderConfigRepository

/** AI 模型提供商配置服务，封装配置的持久化和批量生命周期操作。 */
@Service
class AiModelProviderConfigService(private val repository: AiModelProviderConfigRepository) {
    /** 保存单个提供商配置。 */
    fun save(config: AiModelProviderConfig) = repository.save(config)
    /** 批量保存提供商配置。 */
    fun saveAll(configs: Iterable<AiModelProviderConfig>) = repository.saveAll(configs)
    /** 按主键查询配置。 */
    fun findById(id: Long): AiModelProviderConfig? = repository.findById(id).orElse(null)
    /** 批量按主键查询配置。 */
    fun findAllByIds(ids: Iterable<Long>) = repository.findAllById(ids)
    /** 更新配置；空白密钥表示保留原密钥。 */
    fun update(
        id: Long,
        provider: String,
        model: Map<String, String>,
        baseUrl: String,
        secretKey: String?,
        enabled: Boolean
    ): AiModelProviderConfig? {
        val config = findById(id) ?: return null
        config.provider = provider; config.model = model; config.baseUrl = baseUrl; config.enabled = enabled
        if (!secretKey.isNullOrBlank()) config.secretKey = secretKey
        return repository.save(config)
    }

    /** 删除单个配置，并返回是否实际删除。 */
    fun deleteById(id: Long): Boolean {
        if (!repository.existsById(id)) return false
        repository.deleteById(id); return true
    }

    /** 批量删除配置，返回实际存在并已删除的主键。 */
    fun deleteAllByIds(ids: Iterable<Long>): List<Long> {
        val existingIds = repository.findAllById(ids).mapNotNull { it.id }
        if (existingIds.isNotEmpty()) repository.deleteAllById(existingIds)
        return existingIds
    }
}
