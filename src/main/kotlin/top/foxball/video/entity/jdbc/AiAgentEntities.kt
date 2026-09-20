package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** 面向业务场景的稳定智能体定义。版本内容存放在 [AiAgentVersion]。 */
@Entity
@Table(
    name = "ai_agents",
    indexes = [
        Index(name = "idx_ai_agent_key", columnList = "agent_key"),
        Index(name = "idx_ai_agent_scope", columnList = "scope_type,project_id"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_ai_agent_scope", columnNames = ["agent_key", "scope_type", "project_id", "owner_user_id"]),
    ],
)
class AiAgent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "agent_key", nullable = false, length = 100)
    var agentKey: String = "",

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String = "",

    @Column(nullable = false, length = 1000)
    var purpose: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var category: AgentCategory = AgentCategory.GENERAL_ASSISTANT,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    var scopeType: AgentScopeType = AgentScopeType.GLOBAL,

    @Column(name = "project_id")
    var projectId: Long? = null,

    @Column(name = "owner_user_id")
    var ownerUserId: Long? = null,

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
    @Column(name = "entity_version", nullable = false)
    var entityVersion: Long = 0,
)

/** 智能体的业务分类。 */
enum class AgentCategory {
    SCRIPT_PARSING,
    ASSET_ENRICHMENT,
    STORYBOARD_GENERATION,
    DIALOGUE_GENERATION,
    ASSET_ANALYSIS,
    GENERAL_ASSISTANT,
}

/** 智能体定义的作用域。解析优先级为 USER、PROJECT、GLOBAL。 */
enum class AgentScopeType { GLOBAL, PROJECT, USER }

/** 智能体版本生命周期。进入评审后内容冻结。 */
enum class AgentVersionStatus { DRAFT, IN_REVIEW, PUBLISHED, ARCHIVED }

/** 不可变的智能体执行审计记录。 */
@Entity
@Table(
    name = "ai_agent_versions",
    uniqueConstraints = [UniqueConstraint(name = "uk_ai_agent_version", columnNames = ["agent_id", "version_no"])],
    indexes = [Index(name = "idx_ai_agent_version_stage", columnList = "production_stage,stage_order")],
)
class AiAgentVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "agent_id", nullable = false)
    var agentId: Long = 0,

    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AgentVersionStatus = AgentVersionStatus.DRAFT,

    @Column(name = "input_schema_json", nullable = false, columnDefinition = "text")
    var inputSchemaJson: String = "{}",

    @Column(name = "output_schema_json", nullable = false, columnDefinition = "text")
    var outputSchemaJson: String = "{}",

    @Column(name = "template_key", nullable = false, length = 100)
    var templateKey: String = "",

    @Column(name = "template_version_id", nullable = false)
    var templateVersionId: Long = 0,

    @Column(name = "additional_skills_json", nullable = false, columnDefinition = "text")
    var additionalSkillsJson: String = "[]",

    @Column(name = "additional_mcp_servers_json", nullable = false, columnDefinition = "text")
    var additionalMcpServersJson: String = "[]",

    @Column(name = "input_mapping_json", nullable = false, columnDefinition = "text")
    var inputMappingJson: String = "{}",

    @Column(name = "output_mapping_json", nullable = false, columnDefinition = "text")
    var outputMappingJson: String = "{}",

    @Column(name = "execution_config_json", nullable = false, columnDefinition = "text")
    var executionConfigJson: String = "{}",

    @Enumerated(EnumType.STRING)
    @Column(name = "production_stage", length = 20)
    var productionStage: ProductionStage? = null,

    @Column(name = "stage_order", nullable = false)
    var stageOrder: Int = 0,

    @Column(name = "task_type", length = 50)
    var taskType: String? = null,

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
    @Column(name = "entity_version", nullable = false)
    var entityVersion: Long = 0,
)

/** 一次智能体执行的可追溯审计。 */
@Entity
@Table(
    name = "ai_agent_runs",
    indexes = [
        Index(name = "idx_ai_agent_run_version", columnList = "agent_version_id"),
        Index(name = "idx_ai_agent_run_request", columnList = "request_id"),
        Index(name = "idx_ai_agent_run_episode", columnList = "episode_id"),
        Index(name = "idx_ai_agent_run_created", columnList = "created_at"),
    ],
)
class AiAgentRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "agent_version_id", nullable = false)
    var agentVersionId: Long = 0,

    @Column(name = "template_version_id", nullable = false)
    var templateVersionId: Long = 0,

    @Column(name = "content_hash", nullable = false, length = 80)
    var contentHash: String = "",

    @Column(name = "request_id", length = 100)
    var requestId: String? = null,

    @Column(name = "task_id")
    var taskId: Long? = null,

    @Column(name = "episode_id")
    var episodeId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "production_stage", length = 20)
    var productionStage: ProductionStage? = null,

    @Column(name = "production_task_id")
    var productionTaskId: Long? = null,

    @Column(name = "provider_config_id", nullable = false)
    var providerConfigId: Long = 0,

    @Column(nullable = false, length = 100)
    var model: String = "",

    @Column(name = "input_summary_json", columnDefinition = "text")
    var inputSummaryJson: String? = null,

    @Column(name = "output_summary_json", columnDefinition = "text")
    var outputSummaryJson: String? = null,

    @Column(name = "skills_json", nullable = false, columnDefinition = "text")
    var skillsJson: String = "[]",

    @Column(name = "mcp_servers_json", nullable = false, columnDefinition = "text")
    var mcpServersJson: String = "[]",

    @Column(name = "dependency_run_ids_json", columnDefinition = "text")
    var dependencyRunIdsJson: String? = null,

    @Column(name = "tool_call_count", nullable = false)
    var toolCallCount: Int = 0,

    @Column(name = "input_tokens")
    var inputTokens: Int? = null,

    @Column(name = "output_tokens")
    var outputTokens: Int? = null,

    @Column(name = "attempt_rounds", nullable = false)
    var attemptRounds: Int = 0,

    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AgentRunStatus = AgentRunStatus.SUCCESS,

    @Column(name = "error_code", length = 50)
    var errorCode: String? = null,

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null,

    @Column(name = "created_by")
    var createdBy: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)

enum class AgentRunStatus { SUCCESS, FAILURE, TIMEOUT }

/** Episode 线性工作流的持久化快照。 */
@Entity
@Table(name = "episode_workflow_snapshots")
class EpisodeWorkflowSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "episode_id", nullable = false, unique = true)
    var episodeId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 20)
    var currentStage: ProductionStage = ProductionStage.SCRIPT,

    @Column(name = "executed_agents_json", nullable = false, columnDefinition = "text")
    var executedAgentsJson: String = "[]",

    @Column(name = "stage_progress_json", nullable = false, columnDefinition = "text")
    var stageProgressJson: String = "{}",

    @Column(name = "pending_agents_json", nullable = false, columnDefinition = "text")
    var pendingAgentsJson: String = "[]",

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    @Column(name = "entity_version", nullable = false)
    var entityVersion: Long = 0,
)
