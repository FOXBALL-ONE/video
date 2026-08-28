package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.AiModelProviderConfig

/** AI 模型提供商配置的数据访问接口。 */
interface AiModelProviderConfigRepository : JpaRepository<AiModelProviderConfig, Long> {
    /** 按提供商唯一标识查询配置。 */
    fun findByProvider(provider: String): AiModelProviderConfig?
}
