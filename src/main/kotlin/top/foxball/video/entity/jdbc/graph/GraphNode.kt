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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

/**
 * 画布中的一个 ComfyUI 节点查询投影。
 *
 * 节点插槽、控件值和自定义节点字段结构不固定，因此保持为 JSONB；
 * [serializedPayload] 保留投影未覆盖的原始字段。
 */
@Entity
@Table(
    name = "graph_nodes",
    uniqueConstraints = [UniqueConstraint(columnNames = ["graph_id", "node_key"])],
)
class GraphNode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属 GraphCanvas 主键。 */
    var graphId: Long = 0,

    @Column(name = "node_key", nullable = false, columnDefinition = "text")
    /** ComfyUI 序列化节点 ID。 */
    var nodeKey: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "node_key_kind", length = 10)
    /** 节点 ID 在原始 JSON 中的类型。 */
    var nodeKeyKind: SerializedIdKind? = null,

    @Column(name = "node_type", nullable = false, columnDefinition = "text")
    /** ComfyUI 节点类型，例如 KSampler、LoadImage 或子图定义 ID。 */
    var nodeType: String = "",

    @Column(columnDefinition = "text")
    /** 用户自定义的节点标题；未设置时由节点类型提供默认标题。 */
    var title: String? = null,

    @Column(name = "position_x", nullable = false)
    /** 节点在图坐标系中的左上角 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** 节点在图坐标系中的左上角 Y 坐标。 */
    var positionY: Double = 0.0,

    @Column(name = "width", nullable = false)
    /** 节点宽度。 */
    var width: Double = 0.0,

    @Column(name = "height", nullable = false)
    /** 节点高度。 */
    var height: Double = 0.0,

    @Column(name = "execution_order", nullable = false)
    /** LiteGraph 为执行和绘制维护的节点顺序。 */
    var executionOrder: Int = 0,

    @Column(nullable = false)
    /** LiteGraph 节点运行模式，例如启用、绕过或禁用。 */
    var mode: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    /** collapsed、pinned 等节点标志。 */
    var flags: JsonNode = JsonNodeFactory.instance.objectNode(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 输入插槽列表，包含类型、名称、控件关联和连线 ID 等信息。 */
    var inputs: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 输出插槽列表，包含类型、名称和下游连线 ID 等信息。 */
    var outputs: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 节点属性，包括模型、代理控件和自定义节点元数据。 */
    var properties: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets_values", columnDefinition = "jsonb")
    /** ComfyUI 允许控件值使用数组或对象。 */
    var widgetsValues: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets_values_named", columnDefinition = "jsonb")
    /** 按控件名称保存的控件值，用于名称稳定的控件恢复。 */
    var widgetsValuesNamed: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** RenderShape 可为数值，使用 JSONB 保留原始表示。 */
    var shape: JsonNode? = null,

    @Column(name = "box_color", columnDefinition = "text")
    var boxColor: String? = null,

    @Column(columnDefinition = "text")
    var color: String? = null,

    @Column(name = "background_color", columnDefinition = "text")
    var backgroundColor: String? = null,

    @Column(name = "show_advanced")
    /** 是否展开节点的高级控件。 */
    var showAdvanced: Boolean? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** ComfyUI extensions 命名空间内的节点扩展数据。 */
    var extensions: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serialized_payload", columnDefinition = "jsonb")
    /** 节点的未解释字段，供投影恢复使用。 */
    var serializedPayload: JsonNode? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)
