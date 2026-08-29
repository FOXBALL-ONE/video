package top.foxball.video.entity.jdbc.graph

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * ComfyUI 工作流的无损 JSONB 快照。
 *
 * [document] 是恢复工作流的唯一权威来源；GraphCanvas、GraphNode 等拆分实体
 * 仅用于列表、筛选和统计查询，可在需要时从该快照重新构建。
 */
@Entity
@Table(name = "graph_workflow_snapshots")
class GraphWorkflowSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "project_version_id")
    /** 所属项目版本主键。 */
    var projectVersionId: Long? = null,

    @Column(name = "root_graph_id")
    /** 对应根 GraphCanvas 的数据库主键。 */
    var rootGraphId: Long? = null,

    @Column(name = "workflow_uuid", columnDefinition = "text")
    /** ComfyUI 图 ID；用文本兼容缺失或未来非 UUID 标识。 */
    var workflowUuid: String? = null,

    @Column(name = "format_version", precision = 10, scale = 4)
    /** ComfyUI 工作流格式版本，例如 0.4 或 1。 */
    var formatVersion: BigDecimal? = null,

    @Column(precision = 20, scale = 4)
    /** 工作流 revision。 */
    var revision: BigDecimal? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false, columnDefinition = "jsonb")
    /** 完整的 ComfyUI 工作流 JSON，包含所有未知扩展字段。 */
    var document: JsonNode = JsonNodeFactory.instance.objectNode(),

    @Column(name = "content_hash", length = 128)
    /** 规范化工作流文档的内容哈希，用于去重和条件保存。 */
    var contentHash: String? = null,

    @Column(name = "created_by")
    /** 创建该快照的用户主键。 */
    var createdBy: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 快照最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)
