package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** 面向一个业务场景的提示词定义；定义本身不随版本发布而变化。 */
@Entity
@Table(name = "ai_prompt_definitions")
class PromptDefinition(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "prompt_key", nullable = false, length = 100)
    var key: String = "",

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String = "",

    @Column(nullable = false, length = 1000)
    var purpose: String = "",

    /** 受控业务分类，用于将提示词限制在对应的资产提取场景。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    var category: PromptCategory? = null,

    @Column(name = "is_category_default")
    var categoryDefault: Boolean? = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    var scopeType: PromptScopeType = PromptScopeType.GLOBAL,

    @Column(name = "project_id")
    var projectId: Long? = null,

    @Column(name = "owner_user_id")
    var ownerUserId: Long? = null,

    @Column(name = "default_locale", nullable = false, length = 20)
    var defaultLocale: String = "zh-CN",

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "created_by")
    var createdBy: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    var entityVersion: Long = 0,
)

/** 提示词定义的作用域。 */
enum class PromptScopeType { GLOBAL, PROJECT, USER }

/** 提示词业务分类；分类决定可用于哪些剧本资产类型。 */
enum class PromptCategory {
    SCRIPT_GENERAL,
    SCRIPT_CHARACTER,
    SCRIPT_SCENE,
    SCRIPT_PROP,
    CHARACTER_ENRICHMENT,
    CHARACTER_EXPRESSION_IMAGE,
}

/** 不可变的提示词内容版本。只有 DRAFT 可以覆盖保存。 */
@Entity
@Table(name = "ai_prompt_versions")
class PromptVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "prompt_definition_id", nullable = false)
    var promptDefinitionId: Long = 0,

    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 1,

    @Column(nullable = false, length = 20)
    var locale: String = "zh-CN",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PromptVersionStatus = PromptVersionStatus.DRAFT,

    @Column(name = "messages_json", nullable = false, columnDefinition = "text")
    var messagesJson: String = "[]",

    @Column(name = "variables_schema_json", nullable = false, columnDefinition = "text")
    var variablesSchemaJson: String = "{}",

    @Column(name = "output_schema_json", nullable = false, columnDefinition = "text")
    var outputSchemaJson: String = "{}",

    @Column(name = "generation_policy_json", nullable = false, columnDefinition = "text")
    var generationPolicyJson: String = "{}",

    @Column(name = "compatibility_json", nullable = false, columnDefinition = "text")
    var compatibilityJson: String = "{}",

    @Column(name = "content_hash", nullable = false, length = 80)
    var contentHash: String = "",

    @Column(name = "change_summary", nullable = false, length = 1000)
    var changeSummary: String = "",

    @Column(name = "created_by")
    var createdBy: Long? = null,

    @Column(name = "reviewed_by")
    var reviewedBy: Long? = null,

    @Column(name = "reviewed_at")
    var reviewedAt: LocalDateTime? = null,

    @Column(name = "published_by")
    var publishedBy: Long? = null,

    @Column(name = "published_at")
    var publishedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    var entityVersion: Long = 0,
)

/** 提示词版本状态。 */
enum class PromptVersionStatus { DRAFT, IN_REVIEW, PUBLISHED, ARCHIVED }
