package top.foxball.video.entity.jdbc.graph

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode

/** 画布连线上的一个 reroute 中转点查询投影。 */
@Entity
@Table(
    name = "graph_reroutes",
    uniqueConstraints = [UniqueConstraint(columnNames = ["graph_id", "reroute_key"])],
)
class GraphReroute(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属 GraphCanvas 主键。 */
    var graphId: Long = 0,

    @Column(name = "reroute_key", nullable = false)
    /** ComfyUI reroute ID，在同一画布内唯一。 */
    var rerouteKey: Long = 0,

    @Column(name = "parent_reroute_key")
    /** 上游父 reroute ID，用于表示 reroute 链。 */
    var parentRerouteKey: Long? = null,

    @Column(name = "position_x", nullable = false)
    /** 中转点在图坐标系中的 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** 中转点在图坐标系中的 Y 坐标。 */
    var positionY: Double = 0.0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "link_ids", columnDefinition = "jsonb")
    /** 经过该 reroute 的连线 ID 列表。 */
    var linkIds: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 浮动 reroute 的插槽方向等状态。 */
    var floating: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extensions", columnDefinition = "jsonb")
    /** reroute 的命名空间扩展字段。 */
    var extensions: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serialized_payload", columnDefinition = "jsonb")
    /** reroute 原始 JSON 对象，保留投影未覆盖字段。 */
    var serializedPayload: JsonNode? = null,
)
