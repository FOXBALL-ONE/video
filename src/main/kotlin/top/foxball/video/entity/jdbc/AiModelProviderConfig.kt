package top.foxball.video.entity.jdbc

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import top.foxball.video.ai.AiApiType

/** AI 模型提供商连接配置，密钥仅存储在服务端，不通过响应返回。 */
@Entity
@Table(name = "ai_model_provider_configs")
class AiModelProviderConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,
    
    @Column(nullable = false, length = 50)
    /** 提供商标识；同一提供商可以分别保存对话、图片和视频连接。 */
    var provider: String = "",
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    /** 模型配置 JSON，目前仅保存模型 ID。 */
    var model: Map<String, String> = emptyMap(),
    
    @Column(name = "base_url", nullable = false, length = 500)
    /** 提供商 API 基础地址。 */
    var baseUrl: String = "",
    
    @Column(name = "api_key", nullable = false, length = 1024)
    /** 提供商访问密钥，对外响应时必须忽略。 */
    var secretKey: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connection_config", columnDefinition = "jsonb")
    /** 提供商专属的网络连接设置；代理密码只保存在服务端。 */
    var connectionConfig: Map<String, String>? = null,
    
    @Column(nullable = false)
    /** 是否允许业务使用该提供商。 */
    var enabled: Boolean = true,
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    /** 配置创建时间。 */
    var createdAt: LocalDateTime? = null,
    
    @UpdateTimestamp @Column(nullable = false)
    /** 配置最近更新时间。 */
    var updatedAt: LocalDateTime? = null,
)

/** 从旧配置兼容读取能力类型；历史记录默认视为对话 API。 */
val AiModelProviderConfig.apiType: AiApiType
    get() = model["api_type"]?.let { value ->
        runCatching { AiApiType.valueOf(value.uppercase()) }.getOrNull()
    } ?: AiApiType.CHAT
