package top.foxball.video.service

import org.springframework.stereotype.Service
import top.foxball.video.entity.jdbc.AiModelProviderConfig
import top.foxball.video.repository.AiModelProviderConfigRepository

@Service
class AiModelProviderConfigService(private val repository: AiModelProviderConfigRepository) {
    fun save(config: AiModelProviderConfig) = repository.save(config)
    fun saveAll(configs: Iterable<AiModelProviderConfig>) = repository.saveAll(configs)
    fun findById(id: Long): AiModelProviderConfig? = repository.findById(id).orElse(null)
    fun findAllByIds(ids: Iterable<Long>) = repository.findAllById(ids)
    fun update(id: Long, provider: String, model: Map<String, String>, baseUrl: String, secretKey: String?, enabled: Boolean): AiModelProviderConfig? {
        val config = findById(id) ?: return null
        config.provider = provider; config.model = model; config.baseUrl = baseUrl; config.enabled = enabled
        if (!secretKey.isNullOrBlank()) config.secretKey = secretKey
        return repository.save(config)
    }
    fun deleteById(id: Long): Boolean {
        if (!repository.existsById(id)) return false
        repository.deleteById(id); return true
    }
    fun deleteAllByIds(ids: Iterable<Long>): List<Long> {
        val existingIds = repository.findAllById(ids).mapNotNull { it.id }
        if (existingIds.isNotEmpty()) repository.deleteAllById(existingIds)
        return existingIds
    }
}
