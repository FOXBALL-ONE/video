package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/** 模板质量评估结果；首期只保留结构与读写接口，不接入自动评估流程。 */
@Entity
@Table(name = "ai_template_evaluations")
class AiTemplateEvaluation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "template_key", nullable = false, length = 100)
    var templateKey: String = "",

    @Column(name = "template_version_id", nullable = false)
    var templateVersionId: Long = 0,

    @Column(name = "dataset_id", nullable = false, length = 200)
    var datasetId: String = "",

    @Column(name = "model_name", nullable = false, length = 200)
    var modelName: String = "",

    @Column(name = "sample_count", nullable = false)
    var sampleCount: Int = 0,

    @Column(name = "schema_pass_rate")
    var schemaPassRate: Double? = null,

    @Column(name = "quality_score")
    var qualityScore: Double? = null,

    @Column(name = "input_tokens")
    var inputTokens: Long? = null,

    @Column(name = "output_tokens")
    var outputTokens: Long? = null,

    @Column(name = "p95_latency_ms")
    var p95LatencyMs: Long? = null,

    @Column(name = "failure_summary", length = 2000)
    var failureSummary: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)

/** 模板实际运行审计；只保存版本、哈希、技能与统计信息，不保存渲染后的提示词正文。 */
@Entity
@Table(name = "ai_template_runs")
class AiTemplateRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "template_key", nullable = false, length = 100)
    var templateKey: String = "",

    @Column(name = "template_version_id", nullable = false)
    var templateVersionId: Long = 0,

    @Column(name = "task_id")
    var taskId: Long? = null,

    @Column(name = "invocation_id", nullable = false, length = 100)
    var invocationId: String = "",

    @Column(name = "provider_config_id")
    var providerConfigId: Long? = null,

    @Column(name = "model_name", nullable = false, length = 200)
    var modelName: String = "",

    @Column(nullable = false, length = 30)
    var status: String = "RUNNING",

    @Column(nullable = false)
    var attempt: Int = 1,

    @Column(name = "latency_ms")
    var latencyMs: Long? = null,

    @Column(name = "input_tokens")
    var inputTokens: Int? = null,

    @Column(name = "output_tokens")
    var outputTokens: Int? = null,

    @Column(name = "template_hash", length = 80)
    var templateHash: String? = null,

    @Column(name = "variables_hash", length = 80)
    var variablesHash: String? = null,

    @Column(name = "rendered_input_hash", length = 80)
    var renderedInputHash: String? = null,

    /** 本次运行实际装配的技能 key 列表。 */
    @Column(name = "skills_json", nullable = false, columnDefinition = "text")
    var skillsJson: String = "[]",

    /** 本次运行实际装配的 MCP 服务端 key 列表。 */
    @Column(name = "mcp_servers_json", nullable = false, columnDefinition = "text")
    var mcpServersJson: String = "[]",

    /** 模型实际发起的工具调用次数。 */
    @Column(name = "tool_call_count", nullable = false)
    var toolCallCount: Int = 0,

    @Column(name = "validation_error_code", length = 100)
    var validationErrorCode: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)
