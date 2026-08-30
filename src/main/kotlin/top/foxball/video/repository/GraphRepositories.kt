package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.graph.GraphCanvas
import top.foxball.video.entity.jdbc.graph.GraphGroup
import top.foxball.video.entity.jdbc.graph.GraphLink
import top.foxball.video.entity.jdbc.graph.GraphNode
import top.foxball.video.entity.jdbc.graph.GraphReroute
import top.foxball.video.entity.jdbc.graph.GraphSubgraph
import top.foxball.video.entity.jdbc.graph.GraphSubgraphInstance
import top.foxball.video.entity.jdbc.graph.GraphWorkflowSnapshot
import java.util.UUID

/** ComfyUI 画布的数据访问接口。 */
interface GraphCanvasRepository : JpaRepository<GraphCanvas, Long> {
    fun findAllByProjectIdOrderByUpdatedAtDesc(projectId: Long): List<GraphCanvas>
    fun findAllByProjectIdAndEpisodeIdOrderByUpdatedAtDesc(projectId: Long, episodeId: Long): List<GraphCanvas>
    fun findAllByProjectIdAndProjectVersionIdOrderByUpdatedAtDesc(projectId: Long, projectVersionId: Long): List<GraphCanvas>
    fun findAllByProjectIdAndEpisodeIdAndProjectVersionIdOrderByUpdatedAtDesc(projectId: Long, episodeId: Long, projectVersionId: Long): List<GraphCanvas>
    fun findAllByParentGraphIdOrderByIdAsc(parentGraphId: Long): List<GraphCanvas>
    fun findByProjectIdAndGraphUuid(projectId: Long, graphUuid: UUID): GraphCanvas?
}

/** 画布节点的数据访问接口。 */
interface GraphNodeRepository : JpaRepository<GraphNode, Long> {
    fun findAllByGraphIdOrderByExecutionOrderAscIdAsc(graphId: Long): List<GraphNode>
    fun findByGraphIdAndId(graphId: Long, id: Long): GraphNode?
    fun deleteAllByGraphId(graphId: Long)
}

/** 画布连线的数据访问接口。 */
interface GraphLinkRepository : JpaRepository<GraphLink, Long> {
    fun findAllByGraphIdOrderByLinkKeyAsc(graphId: Long): List<GraphLink>
    fun findByGraphIdAndId(graphId: Long, id: Long): GraphLink?
    fun deleteAllByGraphId(graphId: Long)
}

/** 画布分组的数据访问接口。 */
interface GraphGroupRepository : JpaRepository<GraphGroup, Long> {
    fun findAllByGraphIdOrderByIdAsc(graphId: Long): List<GraphGroup>
    fun findByGraphIdAndId(graphId: Long, id: Long): GraphGroup?
    fun deleteAllByGraphId(graphId: Long)
}

/** 画布 reroute 的数据访问接口。 */
interface GraphRerouteRepository : JpaRepository<GraphReroute, Long> {
    fun findAllByGraphIdOrderByRerouteKeyAsc(graphId: Long): List<GraphReroute>
    fun findByGraphIdAndId(graphId: Long, id: Long): GraphReroute?
    fun deleteAllByGraphId(graphId: Long)
}

/** 子图定义的数据访问接口。 */
interface GraphSubgraphRepository : JpaRepository<GraphSubgraph, Long> {
    fun findAllByOwnerGraphIdOrderByNameAsc(ownerGraphId: Long): List<GraphSubgraph>
    fun findByOwnerGraphIdAndId(ownerGraphId: Long, id: Long): GraphSubgraph?
    fun findByOwnerGraphIdAndSubgraphUuid(ownerGraphId: Long, subgraphUuid: String): GraphSubgraph?
    fun deleteAllByOwnerGraphId(ownerGraphId: Long)
}

/** 父图中子图实例的数据访问接口。 */
interface GraphSubgraphInstanceRepository : JpaRepository<GraphSubgraphInstance, Long> {
    fun findAllByGraphIdOrderByExecutionOrderAscIdAsc(graphId: Long): List<GraphSubgraphInstance>
    fun findByGraphIdAndId(graphId: Long, id: Long): GraphSubgraphInstance?
    fun deleteAllByGraphId(graphId: Long)
}

/** 无损工作流快照的数据访问接口。 */
interface GraphWorkflowSnapshotRepository : JpaRepository<GraphWorkflowSnapshot, Long> {
    fun findAllByProjectIdOrderByCreatedAtDesc(projectId: Long): List<GraphWorkflowSnapshot>
    fun findAllByProjectIdAndEpisodeIdOrderByCreatedAtDesc(projectId: Long, episodeId: Long): List<GraphWorkflowSnapshot>
    fun findAllByProjectIdAndEpisodeIdAndProjectVersionIdOrderByCreatedAtDesc(projectId: Long, episodeId: Long, projectVersionId: Long): List<GraphWorkflowSnapshot>
    fun findAllByProjectVersionIdOrderByCreatedAtDesc(projectVersionId: Long): List<GraphWorkflowSnapshot>
    fun findFirstByRootGraphIdOrderByCreatedAtDesc(rootGraphId: Long): GraphWorkflowSnapshot?
    fun findByProjectIdAndContentHash(projectId: Long, contentHash: String): GraphWorkflowSnapshot?
    fun deleteAllByRootGraphId(rootGraphId: Long)
}
