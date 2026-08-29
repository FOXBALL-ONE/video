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

/**
 * 画布中的一条连线查询投影。
 *
 * 普通 links 和 floatingLinks 共用该表，通过 [floating] 区分；
 * [serializedLinkKind] 保存 0.4 数组格式与 1.x 对象格式的差异。
 */
@Entity
@Table(
    name = "graph_links",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["graph_id", "link_key"]),
    ],
)
class GraphLink(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "graph_id", nullable = false)
    /** 所属 GraphCanvas 主键。 */
    var graphId: Long = 0,

    @Column(name = "link_key", nullable = false)
    /** ComfyUI 连线 ID，在同一画布内唯一。 */
    var linkKey: Long = 0,

    @Column(name = "origin_node_key", nullable = false, columnDefinition = "text")
    /** 输出端节点 ID；浮动输入端连线可能使用未分配节点 ID。 */
    var originNodeKey: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_node_key_kind", length = 10)
    /** 输出端节点 ID 在原始 JSON 中的类型。 */
    var originNodeKeyKind: SerializedIdKind? = null,

    @Column(name = "origin_slot", nullable = false)
    /** 输出端插槽索引。 */
    var originSlot: Int = 0,

    @Column(name = "target_node_key", nullable = false, columnDefinition = "text")
    /** 输入端节点 ID；浮动输出端连线可能使用未分配节点 ID。 */
    var targetNodeKey: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "target_node_key_kind", length = 10)
    /** 输入端节点 ID 在原始 JSON 中的类型。 */
    var targetNodeKeyKind: SerializedIdKind? = null,

    @Column(name = "target_slot", nullable = false)
    /** 输入端插槽索引。 */
    var targetSlot: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_type", columnDefinition = "jsonb")
    /** 原格式允许字符串、数字或字符串数组。 */
    var dataType: JsonNode? = null,

    @Column(name = "parent_reroute_key")
    /** 连线穿过的最后一个 reroute ID。 */
    var parentRerouteKey: Long? = null,

    @Column(name = "is_floating", nullable = false)
    /** true 表示属于 floatingLinks。 */
    var floating: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "serialized_link_kind", length = 10)
    /** 0.4 普通 links 为数组，1.x links/floatingLinks 为对象。 */
    var serializedLinkKind: SerializedLinkKind? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extensions", columnDefinition = "jsonb")
    /** 连线的命名空间扩展字段。 */
    var extensions: JsonNode? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serialized_payload", columnDefinition = "jsonb")
    /** 原始连线对象或数组转换后的完整载荷。 */
    var serializedPayload: JsonNode? = null,
)
