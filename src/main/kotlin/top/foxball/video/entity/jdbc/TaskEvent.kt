package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/** 任务状态和执行阶段的脱敏时间线事件。 */
@Entity
@Table(name = "task_events")
class TaskEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "task_id", nullable = false)
    var taskId: Long = 0,

    @Column(name = "event_type", nullable = false, length = 40)
    var eventType: String = "CREATED",

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    var fromStatus: ProductionTaskStatus? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30)
    var toStatus: ProductionTaskStatus? = null,

    @Column(length = 80)
    var stage: String? = null,

    @Column
    var progress: Int? = null,

    @Column(name = "message_code", length = 100)
    var messageCode: String? = null,

    @Column(name = "actor_id")
    var actorId: Long? = null,

    @Column(name = "correlation_id", length = 100)
    var correlationId: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)
