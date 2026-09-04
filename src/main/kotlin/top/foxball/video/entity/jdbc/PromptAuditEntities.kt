package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/** 一次提示词评测运行的统计记录；不保存样本正文或模型原始响应。 */
@Entity
@Table(name = "ai_prompt_evaluations")
class PromptEvaluation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "prompt_key", nullable = false, length = 100)
    var promptKey: String = "",

    @Column(name = "prompt_version_id", nullable = false)
    var promptVersionId: Long = 0,

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

/** 提示词实际运行审计；只保存版本、哈希、统计和稳定错误码。 */
@Entity
@Table(name = "ai_prompt_run_audits")
class PromptRunAudit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "prompt_key", nullable = false, length = 100)
    var promptKey: String = "",

    @Column(name = "prompt_version_id", nullable = false)
    var promptVersionId: Long = 0,

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

    @Column(name = "validation_error_code", length = 100)
    var validationErrorCode: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)
