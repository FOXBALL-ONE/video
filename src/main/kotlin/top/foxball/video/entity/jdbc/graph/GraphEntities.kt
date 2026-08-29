package top.foxball.video.entity.jdbc.graph

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
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * ComfyUI 的一张序列化画布。
 *
 * 根图和子图都使用该实体表示；[parentGraphId] 为空的记录是根图，
 * 子图节点、连线和分组通过 [id] 关联到对应的画布记录。
 */
@Entity
@Table(name = "graph_canvases")
class GraphCanvas(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "project_version_id")
    /** 所属项目版本快照主键。 */
    var projectVersionId: Long? = null,

    @Column(name = "parent_graph_id")
    /** 父图主键；根图为空。 */
    var parentGraphId: Long? = null,

    @Column(name = "graph_uuid", unique = true)
    /** ComfyUI 序列化数据中的图 UUID。 */
    var graphUuid: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "graph_kind", nullable = false, length = 20)
    /** 根图或子图定义。 */
    var graphKind: GraphKind = GraphKind.ROOT,

    @Column(nullable = false)
    /** ComfyUI 工作流的 revision。 */
    var revision: Long = 0,

    @Column(name = "graph_version", nullable = false, precision = 4, scale = 2)
    /** ComfyUI 工作流格式版本，例如 0.4 或 1.0。 */
    var graphVersion: BigDecimal = BigDecimal("0.4"),

    @Column(name = "last_node_id", nullable = false, length = 100)
    /** 最近分配的节点 ID；ComfyUI 允许数字或字符串 ID。 */
    var lastNodeId: String = "0",

    @Column(name = "last_link_id", nullable = false)
    /** 最近分配的连线 ID。 */
    var lastLinkId: Long = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 画布配置，例如 links_ontop 和 align_to_grid。 */
    var config: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 视口、渲染器版本及其他前端扩展数据。 */
    var extra: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** LiteGraph 的运行状态和 ID 分配状态。 */
    var state: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 工作流中声明的模型元数据。 */
    var models: List<Map<String, Any?>> = emptyList(),

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 记录创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 记录最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)

/** ComfyUI 画布记录的类型。 */
enum class GraphKind {
    ROOT,
    SUBGRAPH,
}

/** ComfyUI 当前使用的画布渲染器版本。 */
enum class GraphRendererVersion {
    LG,
    VUE,
    VUE_CORRECTED,
}

/** 画布中的一个节点。 */
@Entity
@Table(name = "graph_nodes")
class GraphNode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属画布主键。 */
    var graphId: Long = 0,

    @Column(name = "node_key", nullable = false, length = 100)
    /** ComfyUI 序列化节点 ID。使用字符串兼容数字和 GroupNode 的复合 ID。 */
    var nodeKey: String = "",

    @Column(name = "node_type", nullable = false, length = 300)
    /** 节点类型，例如 KSampler 或 LoadImage。 */
    var nodeType: String = "",

    @Column(length = 500)
    /** 节点显示标题。 */
    var title: String? = null,

    @Column(name = "position_x", nullable = false)
    /** 节点左上角 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** 节点左上角 Y 坐标。 */
    var positionY: Double = 0.0,

    @Column(name = "width", nullable = false)
    /** 节点宽度。 */
    var width: Double = 0.0,

    @Column(name = "height", nullable = false)
    /** 节点高度。 */
    var height: Double = 0.0,

    @Column(name = "execution_order", nullable = false)
    /** 节点执行顺序。 */
    var executionOrder: Int = 0,

    @Column(nullable = false)
    /** LiteGraph 节点模式。 */
    var mode: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    /** collapsed、pinned 等节点标志。 */
    var flags: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 节点输入插槽定义。插槽字段随自定义节点变化，因此保留为 JSONB。 */
    var inputs: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 节点输出插槽定义。 */
    var outputs: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 节点属性及自定义节点元数据。 */
    var properties: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets_values", columnDefinition = "jsonb")
    /** 节点控件值；ComfyUI 允许数组或对象两种形式。 */
    var widgetsValues: Any? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets_values_named", columnDefinition = "jsonb")
    /** 按控件名称保存的控件值。 */
    var widgetsValuesNamed: Map<String, Any?> = emptyMap(),

    @Column(length = 50)
    /** 节点形状。 */
    var shape: String? = null,

    @Column(name = "box_color", length = 100)
    /** 节点边框颜色。 */
    var boxColor: String? = null,

    @Column(length = 100)
    /** 节点标题颜色。 */
    var color: String? = null,

    @Column(name = "background_color", length = 100)
    /** 节点背景颜色。 */
    var backgroundColor: String? = null,

    @Column(name = "show_advanced")
    /** 是否显示高级控件。 */
    var showAdvanced: Boolean? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 自定义节点扩展载荷。 */
    var extensions: Map<String, Any?> = emptyMap(),

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)

/** 画布中的一条节点连线。 */
@Entity
@Table(name = "graph_links")
class GraphLink(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属画布主键。 */
    var graphId: Long = 0,

    @Column(name = "link_key", nullable = false)
    /** ComfyUI 序列化连线 ID。 */
    var linkKey: Long = 0,

    @Column(name = "origin_node_key", nullable = false, length = 100)
    /** 输出节点的 ComfyUI 节点 ID。 */
    var originNodeKey: String = "",

    @Column(name = "origin_slot", nullable = false)
    /** 输出插槽序号。 */
    var originSlot: Int = 0,

    @Column(name = "target_node_key", nullable = false, length = 100)
    /** 输入节点的 ComfyUI 节点 ID。 */
    var targetNodeKey: String = "",

    @Column(name = "target_slot", nullable = false)
    /** 输入插槽序号。 */
    var targetSlot: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_type", nullable = false, columnDefinition = "jsonb")
    /** 连线类型；原格式允许字符串、数字或字符串数组。 */
    var dataType: Any = "",

    @Column(name = "parent_reroute_key")
    /** 连线经过的最后一个 reroute ID。 */
    var parentRerouteKey: Long? = null,
)

/** 画布中的一个分组。 */
@Entity
@Table(name = "graph_groups")
class GraphGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属画布主键。 */
    var graphId: Long = 0,

    @Column(name = "group_key")
    /** ComfyUI 序列化分组 ID。旧工作流可能没有该字段。 */
    var groupKey: Long? = null,

    @Column(nullable = false, length = 500)
    /** 分组标题。 */
    var title: String = "",

    @Column(name = "position_x", nullable = false)
    /** 分组左上角 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** 分组左上角 Y 坐标。 */
    var positionY: Double = 0.0,

    @Column(name = "width", nullable = false)
    /** 分组宽度。 */
    var width: Double = 0.0,

    @Column(name = "height", nullable = false)
    /** 分组高度。 */
    var height: Double = 0.0,

    @Column(length = 100)
    /** 分组颜色。 */
    var color: String? = null,

    @Column(name = "font_size")
    /** 分组标题字号。 */
    var fontSize: Int? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 分组的其他标志和扩展字段。 */
    var flags: Map<String, Any?> = emptyMap(),
)

/** 画布中的一个 reroute 点。 */
@Entity
@Table(name = "graph_reroutes")
class GraphReroute(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属画布主键。 */
    var graphId: Long = 0,

    @Column(name = "reroute_key", nullable = false)
    /** ComfyUI 序列化 reroute ID。 */
    var rerouteKey: Long = 0,

    @Column(name = "parent_reroute_key")
    /** 父 reroute ID。 */
    var parentRerouteKey: Long? = null,

    @Column(name = "position_x", nullable = false)
    /** reroute 的 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** reroute 的 Y 坐标。 */
    var positionY: Double = 0.0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "link_ids", nullable = false, columnDefinition = "jsonb")
    /** 连接到该 reroute 的连线 ID 列表。 */
    var linkIds: List<Long> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** floating 插槽信息。 */
    var floating: Map<String, Any?> = emptyMap(),
)

/** ComfyUI definitions.subgraphs 中的一条子图定义。 */
@Entity
@Table(name = "graph_subgraphs")
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

    @Column(name = "subgraph_uuid", nullable = false, unique = true)
    /** 子图定义 UUID。 */
    var subgraphUuid: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    /** 子图定义 revision。 */
    var revision: Long = 0,

    @Column(nullable = false, length = 300)
    /** 子图显示名称。 */
    var name: String = "",

    @Column(length = 100)
    /** 子图分类。 */
    var category: String? = null,

    @Column(length = 100)
    /** 子图的 essentials 分类。 */
    var essentialsCategory: String? = null,

    @Column(columnDefinition = "text")
    /** 子图说明。 */
    var description: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_node", nullable = false, columnDefinition = "jsonb")
    /** 子图输入边界节点的 id、bounding 和 pinned 信息。 */
    var inputNode: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_node", nullable = false, columnDefinition = "jsonb")
    /** 子图输出边界节点的 id、bounding 和 pinned 信息。 */
    var outputNode: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图输入插槽定义。 */
    var inputs: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图输出插槽定义。 */
    var outputs: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exposed_widgets", columnDefinition = "jsonb")
    /** 显示在父图子图节点上的控件引用。 */
    var exposedWidgets: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 子图级扩展元数据。 */
    var extra: Map<String, Any?> = emptyMap(),
)

/** 根图中 subgraphs 数组的一条子图实例。 */
@Entity
@Table(name = "graph_subgraph_instances")
class GraphSubgraphInstance(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 父画布主键。 */
    var graphId: Long = 0,

    @Column(name = "subgraph_uuid", nullable = false)
    /** 被实例化的子图 UUID。 */
    var subgraphUuid: UUID = UUID.randomUUID(),

    @Column(name = "node_key", nullable = false, length = 100)
    /** 子图实例在父图中的节点 ID。 */
    var nodeKey: String = "",

    @Column(name = "position_x", nullable = false)
    /** 实例左上角 X 坐标。 */
    var positionX: Double = 0.0,

    @Column(name = "position_y", nullable = false)
    /** 实例左上角 Y 坐标。 */
    var positionY: Double = 0.0,

    @Column(name = "width", nullable = false)
    /** 实例宽度。 */
    var width: Double = 0.0,

    @Column(name = "height", nullable = false)
    /** 实例高度。 */
    var height: Double = 0.0,

    @Column(name = "execution_order", nullable = false)
    /** 实例执行顺序。 */
    var executionOrder: Int = 0,

    @Column(nullable = false)
    /** LiteGraph 节点模式。 */
    var mode: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    /** 实例节点标志。 */
    var flags: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 实例输入插槽。 */
    var inputs: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 实例输出插槽。 */
    var outputs: List<Map<String, Any?>> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets_values", columnDefinition = "jsonb")
    /** 实例控件值。 */
    var widgetsValues: Any? = null,

    @Column(length = 100)
    /** 实例标题颜色。 */
    var color: String? = null,

    @Column(name = "background_color", length = 100)
    /** 实例背景颜色。 */
    var backgroundColor: String? = null,
)
