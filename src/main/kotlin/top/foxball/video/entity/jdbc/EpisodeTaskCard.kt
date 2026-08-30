package top.foxball.video.entity.jdbc

import com.fasterxml.jackson.annotation.JsonProperty
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/** 每集下游分镜、视频 agent 必须遵守的制作任务卡。 */
@Entity
@Table(name = "episode_task_cards")
class EpisodeTaskCard(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0,

    @Column(name = "episode_id", nullable = false, unique = true)
    @param:JsonProperty("episode_id") var episodeId: Long = 0,

    @Column(name = "version_no", nullable = false)
    @param:JsonProperty("version_no") var versionNo: Int = 1,

    @Column(name = "primary_goal", nullable = false, columnDefinition = "text")
    @param:JsonProperty("primary_goal") var primaryGoal: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "must_include", nullable = false, columnDefinition = "jsonb")
    @param:JsonProperty("must_include") var mustInclude: List<String> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prohibitions", nullable = false, columnDefinition = "jsonb")
    var prohibitions: List<String> = emptyList(),

    @Column(name = "previous_summary", nullable = false, columnDefinition = "text")
    @param:JsonProperty("previous_summary") var previousSummary: String = "",

    @Column(name = "next_goal", nullable = false, columnDefinition = "text")
    @param:JsonProperty("next_goal") var nextGoal: String = "",

    @Column(name = "ending_hook", nullable = false, columnDefinition = "text")
    @param:JsonProperty("ending_hook") var endingHook: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TaskCardStatus = TaskCardStatus.DRAFT,

    @Column(name = "locked_at")
    @param:JsonProperty("locked_at") var lockedAt: LocalDateTime? = null,

    @Column(name = "locked_by")
    @param:JsonProperty("locked_by") var lockedBy: Long? = null,

    @Column(name = "created_by")
    @param:JsonProperty("created_by") var createdBy: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    var entityVersion: Long = 0,
)

/** 任务卡当前编辑状态。 */
enum class TaskCardStatus { DRAFT, LOCKED }

/** 任务卡历史版本，保存时写入，供编辑器回看。 */
@Entity
@Table(name = "episode_task_card_revisions")
class EpisodeTaskCardRevision(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "task_card_id", nullable = false)
    var taskCardId: Long = 0,

    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 1,

    @Column(name = "primary_goal", nullable = false, columnDefinition = "text")
    var primaryGoal: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "must_include", nullable = false, columnDefinition = "jsonb")
    var mustInclude: List<String> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prohibitions", nullable = false, columnDefinition = "jsonb")
    var prohibitions: List<String> = emptyList(),

    @Column(name = "previous_summary", nullable = false, columnDefinition = "text")
    var previousSummary: String = "",

    @Column(name = "next_goal", nullable = false, columnDefinition = "text")
    var nextGoal: String = "",

    @Column(name = "ending_hook", nullable = false, columnDefinition = "text")
    var endingHook: String = "",

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)
