package top.foxball.video.service

import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import top.foxball.video.entity.jdbc.graph.GraphCanvas
import top.foxball.video.entity.jdbc.graph.GraphGroup
import top.foxball.video.entity.jdbc.graph.GraphLink
import top.foxball.video.entity.jdbc.graph.GraphNode
import top.foxball.video.entity.jdbc.graph.GraphReroute
import top.foxball.video.entity.jdbc.graph.GraphSubgraph
import top.foxball.video.entity.jdbc.graph.GraphSubgraphInstance
import top.foxball.video.entity.jdbc.graph.GraphWorkflowSnapshot
import top.foxball.video.repository.GraphCanvasRepository
import top.foxball.video.repository.GraphGroupRepository
import top.foxball.video.repository.GraphLinkRepository
import top.foxball.video.repository.GraphNodeRepository
import top.foxball.video.repository.GraphRerouteRepository
import top.foxball.video.repository.GraphSubgraphInstanceRepository
import top.foxball.video.repository.GraphSubgraphRepository
import top.foxball.video.repository.GraphWorkflowSnapshotRepository
import top.foxball.video.repository.ProjectRepository
import top.foxball.video.repository.ProjectVersionRepository

/** ComfyUI 画布聚合服务，负责画布及其投影实体的一致性读写。 */
@Service
class GraphService(
    private val canvasRepository: GraphCanvasRepository,
    private val nodeRepository: GraphNodeRepository,
    private val linkRepository: GraphLinkRepository,
    private val groupRepository: GraphGroupRepository,
    private val rerouteRepository: GraphRerouteRepository,
    private val subgraphRepository: GraphSubgraphRepository,
    private val subgraphInstanceRepository: GraphSubgraphInstanceRepository,
    private val snapshotRepository: GraphWorkflowSnapshotRepository,
    private val projectRepository: ProjectRepository,
    private val projectVersionRepository: ProjectVersionRepository,
) {
    fun findCanvas(id: Long): GraphCanvas? = canvasRepository.findById(id).orElse(null)

    fun findCanvases(projectId: Long, projectVersionId: Long?): List<GraphCanvas> =
        if (projectVersionId == null) canvasRepository.findAllByProjectIdOrderByUpdatedAtDesc(projectId)
        else canvasRepository.findAllByProjectIdAndProjectVersionIdOrderByUpdatedAtDesc(projectId, projectVersionId)

    fun findChildCanvases(parentGraphId: Long): List<GraphCanvas> = canvasRepository.findAllByParentGraphIdOrderByIdAsc(parentGraphId)

    @Transactional
    fun createCanvas(canvas: GraphCanvas): GraphCanvas {
        validateCanvasReferences(canvas)
        return canvasRepository.save(canvas)
    }

    @Transactional
    fun updateCanvas(id: Long, source: GraphCanvas): GraphCanvas? {
        val target = findCanvas(id) ?: return null
        if (source.parentGraphId == id) throw IllegalArgumentException("画布不能将自身设置为父图")
        if (source.projectId != target.projectId) throw IllegalArgumentException("画布所属项目不可修改")
        validateCanvasReferences(source)
        target.projectId = source.projectId
        target.projectVersionId = source.projectVersionId
        target.parentGraphId = source.parentGraphId
        target.graphUuid = source.graphUuid
        target.graphKind = source.graphKind
        target.revision = source.revision
        target.graphVersion = source.graphVersion
        target.lastNodeId = source.lastNodeId
        target.lastNodeIdKind = source.lastNodeIdKind
        target.lastLinkId = source.lastLinkId
        target.lastGroupId = source.lastGroupId
        target.lastRerouteId = source.lastRerouteId
        target.config = source.config
        target.extra = source.extra
        target.state = source.state
        target.models = source.models
        target.extensions = source.extensions
        target.serializedPayload = source.serializedPayload
        return canvasRepository.save(target)
    }

    @Transactional
    fun deleteCanvas(id: Long): Boolean {
        if (!canvasRepository.existsById(id)) return false
        subgraphRepository.findAllByOwnerGraphIdOrderByNameAsc(id).forEach { child -> deleteCanvas(child.canvasGraphId) }
        nodeRepository.deleteAllByGraphId(id)
        linkRepository.deleteAllByGraphId(id)
        groupRepository.deleteAllByGraphId(id)
        rerouteRepository.deleteAllByGraphId(id)
        subgraphInstanceRepository.deleteAllByGraphId(id)
        subgraphRepository.deleteAllByOwnerGraphId(id)
        snapshotRepository.deleteAllByRootGraphId(id)
        canvasRepository.deleteById(id)
        return true
    }

    fun nodes(graphId: Long): List<GraphNode> = nodeRepository.findAllByGraphIdOrderByExecutionOrderAscIdAsc(graphId)
    fun links(graphId: Long): List<GraphLink> = linkRepository.findAllByGraphIdOrderByLinkKeyAsc(graphId)
    fun groups(graphId: Long): List<GraphGroup> = groupRepository.findAllByGraphIdOrderByIdAsc(graphId)
    fun reroutes(graphId: Long): List<GraphReroute> = rerouteRepository.findAllByGraphIdOrderByRerouteKeyAsc(graphId)
    fun subgraphInstances(graphId: Long): List<GraphSubgraphInstance> = subgraphInstanceRepository.findAllByGraphIdOrderByExecutionOrderAscIdAsc(graphId)
    fun subgraphs(ownerGraphId: Long): List<GraphSubgraph> = subgraphRepository.findAllByOwnerGraphIdOrderByNameAsc(ownerGraphId)

    @Transactional fun saveNode(graphId: Long, node: GraphNode): GraphNode? = if (findCanvas(graphId) == null || (node.id != null && findNode(graphId, node.id!!) == null)) null else nodeRepository.save(node.apply { this.graphId = graphId })
    @Transactional fun saveLink(graphId: Long, link: GraphLink): GraphLink? = if (findCanvas(graphId) == null || (link.id != null && findLink(graphId, link.id!!) == null)) null else linkRepository.save(link.apply { this.graphId = graphId })
    @Transactional fun saveGroup(graphId: Long, group: GraphGroup): GraphGroup? = if (findCanvas(graphId) == null || (group.id != null && findGroup(graphId, group.id!!) == null)) null else groupRepository.save(group.apply { this.graphId = graphId })
    @Transactional fun saveReroute(graphId: Long, reroute: GraphReroute): GraphReroute? = if (findCanvas(graphId) == null || (reroute.id != null && findReroute(graphId, reroute.id!!) == null)) null else rerouteRepository.save(reroute.apply { this.graphId = graphId })
    @Transactional fun saveSubgraphInstance(graphId: Long, instance: GraphSubgraphInstance): GraphSubgraphInstance? = if (findCanvas(graphId) == null || (instance.id != null && findSubgraphInstance(graphId, instance.id!!) == null)) null else subgraphInstanceRepository.save(instance.apply { this.graphId = graphId })
    @Transactional fun saveSubgraph(ownerGraphId: Long, subgraph: GraphSubgraph): GraphSubgraph? = if (findCanvas(ownerGraphId) == null || (subgraph.id != null && findSubgraph(ownerGraphId, subgraph.id!!) == null)) null else subgraphRepository.save(subgraph.apply { this.ownerGraphId = ownerGraphId })

    fun findNode(graphId: Long, id: Long): GraphNode? = nodeRepository.findByGraphIdAndId(graphId, id)
    fun findLink(graphId: Long, id: Long): GraphLink? = linkRepository.findByGraphIdAndId(graphId, id)
    fun findGroup(graphId: Long, id: Long): GraphGroup? = groupRepository.findByGraphIdAndId(graphId, id)
    fun findReroute(graphId: Long, id: Long): GraphReroute? = rerouteRepository.findByGraphIdAndId(graphId, id)
    fun findSubgraphInstance(graphId: Long, id: Long): GraphSubgraphInstance? = subgraphInstanceRepository.findByGraphIdAndId(graphId, id)
    fun findSubgraph(ownerGraphId: Long, id: Long): GraphSubgraph? = subgraphRepository.findByOwnerGraphIdAndId(ownerGraphId, id)

    @Transactional fun deleteNode(graphId: Long, id: Long): Boolean = deleteChild(findNode(graphId, id), nodeRepository)
    @Transactional fun deleteLink(graphId: Long, id: Long): Boolean = deleteChild(findLink(graphId, id), linkRepository)
    @Transactional fun deleteGroup(graphId: Long, id: Long): Boolean = deleteChild(findGroup(graphId, id), groupRepository)
    @Transactional fun deleteReroute(graphId: Long, id: Long): Boolean = deleteChild(findReroute(graphId, id), rerouteRepository)
    @Transactional fun deleteSubgraphInstance(graphId: Long, id: Long): Boolean = deleteChild(findSubgraphInstance(graphId, id), subgraphInstanceRepository)
    @Transactional
    fun deleteSubgraph(ownerGraphId: Long, id: Long): Boolean {
        val subgraph = findSubgraph(ownerGraphId, id) ?: return false
        deleteCanvas(subgraph.canvasGraphId)
        subgraphRepository.delete(subgraph)
        return true
    }

    fun latestSnapshot(graphId: Long): GraphWorkflowSnapshot? = snapshotRepository.findFirstByRootGraphIdOrderByCreatedAtDesc(graphId)
    fun snapshots(projectId: Long, projectVersionId: Long?): List<GraphWorkflowSnapshot> = if (projectVersionId == null) snapshotRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId) else snapshotRepository.findAllByProjectVersionIdOrderByCreatedAtDesc(projectVersionId)

    @Transactional
    fun saveSnapshot(snapshot: GraphWorkflowSnapshot): GraphWorkflowSnapshot {
        if (snapshot.id != null) throw IllegalArgumentException("工作流快照只支持新增，不能覆盖历史记录")
        if (!projectRepository.existsById(snapshot.projectId)) throw IllegalArgumentException("项目不存在")
        if (snapshot.projectVersionId != null) {
            val version = projectVersionRepository.findById(snapshot.projectVersionId!!).orElse(null)
                ?: throw IllegalArgumentException("项目版本不存在")
            if (version.projectId != snapshot.projectId) throw IllegalArgumentException("项目版本不属于该项目")
        }
        return snapshotRepository.save(snapshot)
    }

    private fun validateCanvasReferences(canvas: GraphCanvas) {
        if (!projectRepository.existsById(canvas.projectId)) throw IllegalArgumentException("项目不存在")
        if (canvas.projectVersionId != null) {
            val version = projectVersionRepository.findById(canvas.projectVersionId!!).orElse(null)
                ?: throw IllegalArgumentException("项目版本不存在")
            if (version.projectId != canvas.projectId) throw IllegalArgumentException("项目版本不属于该项目")
        }
        if (canvas.parentGraphId != null) {
            val parent = findCanvas(canvas.parentGraphId!!) ?: throw IllegalArgumentException("父画布不存在")
            if (parent.projectId != canvas.projectId || parent.projectVersionId != canvas.projectVersionId) throw IllegalArgumentException("父画布不属于同一项目版本")
        }
    }

    private fun <T : Any> deleteChild(entity: T?, repository: org.springframework.data.jpa.repository.JpaRepository<T, Long>): Boolean {
        if (entity == null) return false
        repository.delete(entity)
        return true
    }
}
