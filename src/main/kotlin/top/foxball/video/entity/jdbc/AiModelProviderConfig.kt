package top.foxball.video.entity.jdbc

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "ai_model_provider_configs")
class AiModelProviderConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    
    @Column(nullable = false, unique = true, length = 50)
    var provider: String = "",
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var model: Map<String, String> = emptyMap(),
    
    @Column(name = "base_url", nullable = false, length = 500)
    var baseUrl: String = "",
    
    @Column(name = "api_key", nullable = false, length = 1024)
    var secretKey: String = "",
    
    @Column(nullable = false)
    var enabled: Boolean = true,
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
    
    @UpdateTimestamp @Column(nullable = false)
    var updatedAt: LocalDateTime? = null,
)
