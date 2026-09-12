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

/**
 * 面向一个业务场景的 AI 模板；定义本身不随版本发布而变化。
 *
 * 模板把“提示词、技能、MCP 工具与输出契约”作为一个可版本化的业务资产统一管理，
 * 运行时由稳定的模板 key 解析到一个已发布版本。
 */
@Entity
@Table(name = "ai_templates")
class AiTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "template_key", nullable = false, length = 100)
    var key: String = "",

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String = "",

    @Column(nullable = false, length = 1000)
    var purpose: String = "",

    /** 受控业务分类，用于将模板限制在对应的资产业务场景。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    var category: TemplateCategory? = null,

    @Column(name = "is_category_default")
    var categoryDefault: Boolean? = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    var scopeType: TemplateScopeType = TemplateScopeType.GLOBAL,

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

/** 模板定义的作用域。 */
enum class TemplateScopeType { GLOBAL, PROJECT, USER }

/** 模板业务分类；分类决定可用于哪些业务场景。 */
enum class TemplateCategory {
    SCRIPT_GENERAL,
    SCRIPT_CHARACTER,
    SCRIPT_SCENE,
    SCRIPT_PROP,
    CHARACTER_ENRICHMENT,
    CHARACTER_EXPRESSION_IMAGE,
    STORYBOARD_IMAGE,
}

/** 不可变的模板内容版本。只有 DRAFT 可以覆盖保存。 */
@Entity
@Table(name = "ai_template_versions")
class AiTemplateVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "template_id", nullable = false)
    var templateId: Long = 0,

    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 1,

    @Column(nullable = false, length = 20)
    var locale: String = "zh-CN",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TemplateVersionStatus = TemplateVersionStatus.DRAFT,

    @Column(name = "messages_json", nullable = false, columnDefinition = "text")
    var messagesJson: String = "[]",

    @Column(name = "variables_schema_json", nullable = false, columnDefinition = "text")
    var variablesSchemaJson: String = "{}",

    @Column(name = "output_schema_json", nullable = false, columnDefinition = "text")
    var outputSchemaJson: String = "{}",

    @Column(name = "generation_policy_json", nullable = false, columnDefinition = "text")
    var generationPolicyJson: String = "{}",

    /** 该版本引用的技能列表，形如 `[{"key":"director"}]`。 */
    @Column(name = "skills_json", nullable = false, columnDefinition = "text")
    var skillsJson: String = "[]",

    /** 该版本引用的 MCP 服务端列表，形如 `[{"key":"filesystem","tools":["read_file"]}]`。 */
    @Column(name = "mcp_servers_json", nullable = false, columnDefinition = "text")
    var mcpServersJson: String = "[]",

    /** 该版本启用的内置工具名列表，形如 `["storyboard_list_validate"]`。 */
    @Column(name = "tools_json", nullable = false, columnDefinition = "text")
    var toolsJson: String = "[]",

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

/** 模板版本状态。 */
enum class TemplateVersionStatus { DRAFT, IN_REVIEW, PUBLISHED, ARCHIVED }

/** 技能来源；文件与内置技能在启动时导入，数据库技能由管理接口维护。 */
enum class SkillSource { BUILTIN, FILE, DATABASE }

/**
 * 可被模板引用的技能。
 *
 * 技能是一段稳定的方法论说明（导演、分镜师等），可以声明需要一并装配的内置工具与
 * MCP 服务端；模板执行时技能说明会作为独立的 system 区块注入对话。
 */
@Entity
@Table(name = "ai_skills")
class AiSkill(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "skill_key", nullable = false, length = 100)
    var key: String = "",

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String = "",

    @Column(nullable = false, length = 1000)
    var description: String = "",

    /** 技能正文（Markdown）；不允许包含模板变量占位符。 */
    @Column(nullable = false, columnDefinition = "text")
    var instructions: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var source: SkillSource = SkillSource.DATABASE,

    /** 文件技能的来源路径；数据库技能为空。 */
    @Column(name = "storage_path", length = 500)
    var storagePath: String? = null,

    /** 技能声明的默认内置工具名列表。 */
    @Column(name = "tools_json", nullable = false, columnDefinition = "text")
    var toolsJson: String = "[]",

    /** 技能声明的默认 MCP 服务端列表。 */
    @Column(name = "mcp_servers_json", nullable = false, columnDefinition = "text")
    var mcpServersJson: String = "[]",

    @Column(name = "content_hash", nullable = false, length = 80)
    var contentHash: String = "",

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

/** MCP 服务端传输方式。 */
enum class McpTransport { STDIO, SSE, STREAMABLE_HTTP }

/**
 * 可被模板或技能引用的 MCP 服务端。
 *
 * 连接信息只在服务端保存与使用，不会返回给前端；密钥类环境变量与请求头在响应中脱敏。
 */
@Entity
@Table(name = "ai_mcp_servers")
class AiMcpServer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "server_key", nullable = false, length = 100)
    var key: String = "",

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String = "",

    @Column(nullable = false, length = 1000)
    var description: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var transport: McpTransport = McpTransport.STREAMABLE_HTTP,

    /** STDIO 传输启动命令。 */
    @Column(length = 500)
    var command: String? = null,

    @Column(name = "args_json", nullable = false, columnDefinition = "text")
    var argsJson: String = "[]",

    @Column(name = "env_json", nullable = false, columnDefinition = "text")
    var envJson: String = "{}",

    /** SSE 或 Streamable HTTP 传输的服务地址。 */
    @Column(length = 1000)
    var url: String? = null,

    @Column(name = "headers_json", nullable = false, columnDefinition = "text")
    var headersJson: String = "{}",

    /** 允许模型调用的工具名白名单；为空表示该服务端全部工具。 */
    @Column(name = "tool_allowlist_json", nullable = false, columnDefinition = "text")
    var toolAllowlistJson: String = "[]",

    @Column(name = "request_timeout_seconds", nullable = false)
    var requestTimeoutSeconds: Long = 30,

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
