package top.foxball.video.entity.jdbc.graph

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode

/** 画布中的一个 LiteGraph 分组查询投影。 */
@Entity
@Table(name = "graph_groups")
class GraphGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属 GraphCanvas 主键。 */
    var graphId: Long = 0,

    @Column(name = "group_key")
    /** ComfyUI 分组 ID；旧版工作流可能未提供。 */
    var groupKey: Long? = null,

    @Column(nullable = false, columnDefinition = "text")
    /** 分组标题。 */
    var title: String = "",

    @Column(name = "position_x", nullable = false)
    /** 分组边界左上角 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** 分组边界左上角 Y 坐标。 */
    var positionY: Double = 0.0,

    @Column(name = "width", nullable = false)
    /** 分组边界宽度。 */
    var width: Double = 0.0,

    @Column(name = "height", nullable = false)
    /** 分组边界高度。 */
    var height: Double = 0.0,

    @Column(length = 100)
    /** 分组显示颜色。 */
    var color: String? = null,

    @Column(name = "font_size")
    /** 分组标题字号。 */
    var fontSize: Double? = null,

    @Column(name = "locked")
    /** 是否锁定分组的移动和缩放操作。 */
    var locked: Boolean? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** pinned 等分组标志。 */
    var flags: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extensions", columnDefinition = "jsonb")
    /** 分组的命名空间扩展字段。 */
    var extensions: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serialized_payload", columnDefinition = "jsonb")
    /** 分组原始 JSON 对象，保留投影未覆盖字段。 */
    var serializedPayload: JsonNode? = null,
)
