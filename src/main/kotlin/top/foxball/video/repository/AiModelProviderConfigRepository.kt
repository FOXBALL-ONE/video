package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.AiModelProviderConfig

interface AiModelProviderConfigRepository : JpaRepository<AiModelProviderConfig, Long> {
    fun findByProvider(provider: String): AiModelProviderConfig?
}
