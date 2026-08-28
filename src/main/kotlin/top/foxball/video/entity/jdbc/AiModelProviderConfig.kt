package top.foxball.video.entity.jdbc

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** AI 模型提供商连接配置，密钥仅存储在服务端，不通过响应返回。 */
@Entity
@Table(name = "ai_model_provider_configs")
class AiModelProviderConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,
    
    @Column(nullable = false, unique = true, length = 50)
    /** 提供商唯一标识，例如 openai 或 anthropic。 */
    var provider: String = "",
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    /** 模型名称到模型 ID 的 JSON 映射。 */
    var model: Map<String, String> = emptyMap(),
    
    @Column(name = "base_url", nullable = false, length = 500)
    /** 提供商 API 基础地址。 */
    var baseUrl: String = "",
    
    @Column(name = "api_key", nullable = false, length = 1024)
    /** 提供商访问密钥，对外响应时必须忽略。 */
    var secretKey: String = "",
    
    @Column(nullable = false)
    /** 是否允许业务使用该提供商。 */
    var enabled: Boolean = true,
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
    
    @UpdateTimestamp @Column(nullable = false)
    var updatedAt: LocalDateTime? = null,
)
