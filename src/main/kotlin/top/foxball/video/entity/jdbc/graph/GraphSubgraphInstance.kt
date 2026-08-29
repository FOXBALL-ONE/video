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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

/**
 * 父图中的一个子图节点实例。
 *
 * 子图实例本质上也是节点，因此保留完整的节点展示、插槽、控件和扩展字段；
 * [subgraphUuid] 指向 GraphSubgraph 的 ComfyUI 定义 ID。
 */
@Entity
@Table(
    name = "graph_subgraph_instances",
    uniqueConstraints = [UniqueConstraint(columnNames = ["graph_id", "node_key"])],
)
class GraphSubgraphInstance(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 放置该实例的父 GraphCanvas 主键。 */
    var graphId: Long = 0,

    @Column(name = "subgraph_uuid", nullable = false, columnDefinition = "text")
    /** 被实例化的 ComfyUI 子图定义 ID。 */
    var subgraphUuid: String = "",

    @Column(name = "node_key", nullable = false, columnDefinition = "text")
    /** 该子图实例在父图中的节点 ID。 */
    var nodeKey: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "node_key_kind", length = 10)
    /** 子图实例节点 ID 在原始 JSON 中的类型。 */
    var nodeKeyKind: SerializedIdKind? = null,

    @Column(name = "position_x", nullable = false)
    /** 实例在父图坐标系中的左上角 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** 实例在父图坐标系中的左上角 Y 坐标。 */
    var positionY: Double = 0.0,

    @Column(name = "width", nullable = false)
    /** 实例节点宽度。 */
    var width: Double = 0.0,

    @Column(name = "height", nullable = false)
    /** 实例节点高度。 */
    var height: Double = 0.0,

    @Column(name = "execution_order", nullable = false)
    /** LiteGraph 节点顺序。 */
    var executionOrder: Int = 0,

    @Column(nullable = false)
    /** LiteGraph 节点模式。 */
    var mode: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    /** collapsed、pinned 等实例标志。 */
    var flags: JsonNode = JsonNodeFactory.instance.objectNode(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图公开输入在父图节点上的插槽投影。 */
    var inputs: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图公开输出在父图节点上的插槽投影。 */
    var outputs: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets_values", columnDefinition = "jsonb")
    /** 子图提升到父图节点上的控件值。 */
    var widgetsValues: JsonNode? = null,

    @Column(columnDefinition = "text")
    /** 实例标题颜色。 */
    var color: String? = null,

    @Column(name = "background_color", columnDefinition = "text")
    /** 实例背景颜色。 */
    var backgroundColor: String? = null,

    @Column(columnDefinition = "text")
    /** 实例显示标题。 */
    var title: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shape", columnDefinition = "jsonb")
    /** 子图实例的渲染形状，保留 ComfyUI 原始 JSON 表示。 */
    var shape: JsonNode? = null,

    @Column(name = "box_color", columnDefinition = "text")
    /** 实例边框颜色。 */
    var boxColor: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties", columnDefinition = "jsonb")
    /** 子图实例特有属性，例如代理控件和预览暴露信息。 */
    var properties: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets_values_named", columnDefinition = "jsonb")
    /** 按名称保存的提升控件值。 */
    var widgetsValuesNamed: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extensions", columnDefinition = "jsonb")
    /** 实例的命名空间扩展字段。 */
    var extensions: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serialized_payload", columnDefinition = "jsonb")
    /** 子图实例原始 JSON，保留投影未覆盖字段。 */
    var serializedPayload: JsonNode? = null,
)
