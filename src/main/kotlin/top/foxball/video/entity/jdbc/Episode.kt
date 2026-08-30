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

/** 项目下的单集制作单元；剧本、分镜和视频任务均可通过该主键隔离。 */
@Entity
@Table(name = "episodes")
class Episode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0,

    @Column(name = "episode_no", nullable = false)
    var episodeNo: Int = 1,

    @Column(nullable = false, length = 200)
    var title: String = "",

    @Column(length = 1000)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EpisodeStatus = EpisodeStatus.DRAFT,

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 20)
    var currentStage: ProductionStage = ProductionStage.SCRIPT,

    @Column(nullable = false)
    var progress: Int = 0,

    @Column(name = "duration_seconds", nullable = false)
    var durationSeconds: Int = 0,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    var entityVersion: Long = 0,
)

enum class EpisodeStatus {
    DRAFT,
    IN_PRODUCTION,
    COMPLETED,
    ARCHIVED,
}
