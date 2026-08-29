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
import tools.jackson.databind.node.JsonNodeFactory

/**
 * ComfyUI `definitions.subgraphs` 中的一条可复用子图定义。
 *
 * [canvasGraphId] 指向子图内部画布；嵌套子图定义和未知字段保留在
 * [serializedPayload]，完整工作流仍由 GraphWorkflowSnapshot 无损保存。
 */
@Entity
@Table(
    name = "graph_subgraphs",
    uniqueConstraints = [UniqueConstraint(columnNames = ["owner_graph_id", "subgraph_uuid"])],
)
class GraphSubgraph(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "owner_graph_id", nullable = false)
    /** 保存该定义的父画布主键。 */
    var ownerGraphId: Long = 0,

    @Column(name = "canvas_graph_id", nullable = false)
    /** 子图自身对应的 GraphCanvas 主键。 */
    var canvasGraphId: Long = 0,

    @Column(name = "subgraph_uuid", nullable = false, columnDefinition = "text")
    /** ComfyUI 子图定义 ID，也是父图中子图节点的 type 值。 */
    var subgraphUuid: String = "",

    @Column(nullable = false, precision = 20, scale = 4)
    /** 子图定义 revision。 */
    var revision: java.math.BigDecimal = java.math.BigDecimal.ZERO,

    @Column(nullable = false, columnDefinition = "text")
    /** 子图在节点库和画布中显示的名称。 */
    var name: String = "",

    @Column(columnDefinition = "text")
    /** 子图在节点库中的分类路径。 */
    var category: String? = null,

    @Column(name = "essentials_category", columnDefinition = "text")
    /** ComfyUI Essentials 提供的子图分类。 */
    var essentialsCategory: String? = null,

    @Column(columnDefinition = "text")
    /** 子图用途说明。 */
    var description: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_node", nullable = false, columnDefinition = "jsonb")
    /** 子图输入边界节点的 ID、边界和固定状态。 */
    var inputNode: JsonNode = JsonNodeFactory.instance.objectNode(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_node", nullable = false, columnDefinition = "jsonb")
    /** 子图输出边界节点的 ID、边界和固定状态。 */
    var outputNode: JsonNode = JsonNodeFactory.instance.objectNode(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图公开的输入插槽定义。 */
    var inputs: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图公开的输出插槽定义。 */
    var outputs: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exposed_widgets", columnDefinition = "jsonb")
    /** 在父图子图节点上展示的内部控件引用。 */
    var exposedWidgets: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图画布的视口和前端扩展数据。 */
    var extra: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图的命名空间扩展字段。 */
    var extensions: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serialized_payload", columnDefinition = "jsonb")
    /** 子图定义原始 JSON，包含递归 definitions 等未拆分字段。 */
    var serializedPayload: JsonNode? = null,
)
