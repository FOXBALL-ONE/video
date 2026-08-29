package top.foxball.video.entity.jdbc.graph

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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * ComfyUI 的一张序列化画布。
 *
 * 根图和子图统一使用该实体表示，节点、连线、分组和 reroute 通过
 * [id] 关联到所属画布。完整原始工作流由 GraphWorkflowSnapshot 保存。
 */
@Entity
@Table(
    name = "graph_canvases",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["project_id", "project_version_id", "graph_uuid"]),
    ],
)
class GraphCanvas(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "project_version_id")
    /** 所属项目版本主键；同一项目的不同版本可保存相同 ComfyUI 图 ID。 */
    var projectVersionId: Long? = null,

    @Column(name = "parent_graph_id")
    /** 父图主键；根图为空。 */
    var parentGraphId: Long? = null,

    @Column(name = "graph_uuid")
    /** ComfyUI 序列化数据中的图 UUID；同一 UUID 可以出现在多个版本快照中。 */
    var graphUuid: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "graph_kind", nullable = false, length = 20)
    /** 画布是项目根图还是 definitions.subgraphs 中的子图。 */
    var graphKind: GraphKind = GraphKind.ROOT,

    @Column(nullable = false, precision = 20, scale = 4)
    /** ComfyUI 图 revision，用于工作流版本协商。 */
    var revision: BigDecimal = BigDecimal.ZERO,

    @Column(name = "graph_version", nullable = false, precision = 4, scale = 2)
    /** ComfyUI 工作流格式版本，例如 0.4 或 1。 */
    var graphVersion: BigDecimal = BigDecimal("0.4"),

    @Column(name = "last_node_id", nullable = false, columnDefinition = "text")
    /** 兼容 ComfyUI 的数字、字符串和复合节点 ID。 */
    var lastNodeId: String = "0",

    @Enumerated(EnumType.STRING)
    @Column(name = "last_node_id_kind", length = 10)
    /** 0.4 中 last_node_id 可以是 number 或 string。 */
    var lastNodeIdKind: SerializedIdKind? = null,

    @Column(name = "last_link_id", nullable = false)
    /** 最近分配的连线 ID。 */
    var lastLinkId: Long = 0,

    @Column(name = "last_group_id")
    /** 1.x state.lastGroupId；0.4 中没有该字段。 */
    var lastGroupId: Long? = null,

    @Column(name = "last_reroute_id")
    /** 1.x state.lastRerouteId；0.4 中没有该字段。 */
    var lastRerouteId: Long? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** ComfyUI 的画布配置，例如 links_ontop 和 align_to_grid。 */
    var config: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 视口、渲染器版本和 ComfyUI 前端扩展数据。 */
    var extra: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 1.x 工作流中的 ID 分配状态。 */
    var state: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 工作流声明的模型文件信息。 */
    var models: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 图对象的命名空间扩展字段。 */
    var extensions: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serialized_payload", columnDefinition = "jsonb")
    /** 图对象的非规范字段；用于投影之外的恢复。 */
    var serializedPayload: JsonNode? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 记录最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)
