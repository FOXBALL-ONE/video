package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** 角色扩写任务的暂存结果；只有采纳动作会修改角色资产。 */
@Entity
@Table(
    name = "character_enrichment_results",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_character_enrichment_task", columnNames = ["task_id"]),
        UniqueConstraint(name = "uk_character_enrichment_idempotency", columnNames = ["project_id", "episode_id", "episode_asset_id", "idempotency_key"]),
    ],
)
class CharacterEnrichmentResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "task_id", nullable = false)
    var taskId: Long = 0,

    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0,

    @Column(name = "episode_id", nullable = false)
    var episodeId: Long = 0,

    @Column(name = "episode_asset_id", nullable = false)
    var episodeAssetId: Long = 0,

    @Column(name = "script_version_id")
    var scriptVersionId: Long? = null,

    @Column(name = "content_hash", length = 80)
    var contentHash: String? = null,

    @Column(name = "prompt_version_id", nullable = false)
    var promptVersionId: Long = 0,

    @Column(name = "result_json", columnDefinition = "text")
    var resultJson: String? = null,

    @Column(name = "result_hash", length = 80)
    var resultHash: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 30)
    var resultStatus: CharacterEnrichmentResultStatus = CharacterEnrichmentResultStatus.PROCESSING,

    @Column(name = "idempotency_key", length = 200)
    var idempotencyKey: String? = null,

    @Column(name = "adoption_idempotency_key", length = 200)
    var adoptionIdempotencyKey: String? = null,

    @Column(name = "error_code", length = 100)
    var errorCode: String? = null,

    @Column(name = "error_message", length = 2000)
    var errorMessage: String? = null,

    @Column(name = "adopted_at")
    var adoptedAt: LocalDateTime? = null,

    @Column(name = "discarded_at")
    var discardedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    var entityVersion: Long = 0,
)

enum class CharacterEnrichmentResultStatus { PROCESSING, STAGED, ADOPTED, DISCARDED, FAILED }
