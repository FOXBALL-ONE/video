package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.graph.GraphCanvas
import top.foxball.video.entity.jdbc.graph.GraphGroup
import top.foxball.video.entity.jdbc.graph.GraphLink
import top.foxball.video.entity.jdbc.graph.GraphNode
import top.foxball.video.entity.jdbc.graph.GraphNodeCatalog
import top.foxball.video.entity.jdbc.graph.GraphReroute
import top.foxball.video.entity.jdbc.graph.GraphSubgraph
import top.foxball.video.entity.jdbc.graph.GraphSubgraphInstance
import top.foxball.video.entity.jdbc.graph.GraphWorkflowSnapshot
import top.foxball.video.service.GraphService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** ComfyUI 画布接口；画布是聚合根，节点、连线和子图均通过 graph_id 归属。 */
class GraphController(
    private val graphService: GraphService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/graphs")
    /** 按项目和项目版本查询画布列表。 */
    fun listCanvases(
        @RequestParam("project_id") projectId: Long,
        @RequestParam("project_version_id", required = false) projectVersionId: Long?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class CanvasData(
            val id: Long,
            @param:JsonProperty("project_id") val projectId: Long,
            @param:JsonProperty("project_version_id") val projectVersionId: Long?,
            @param:JsonProperty("parent_graph_id") val parentGraphId: Long?,
            @param:JsonProperty("graph_uuid") val graphUuid: String?,
            @param:JsonProperty("graph_kind") val graphKind: String,
            val revision: String,
            @param:JsonProperty("graph_version") val graphVersion: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class ResponseData(val list: List<CanvasData>)

        val list = graphService.findCanvases(projectId, projectVersionId, episodeId).map {
            CanvasData(requireNotNull(it.id), it.projectId, it.projectVersionId, it.parentGraphId, it.graphUuid?.toString(), it.graphKind.name, it.revision.toPlainString(), it.graphVersion.toPlainString(), it.updatedAt)
        }
        return responseBuilder.ok().data(ResponseData(list)).build()
    }

    @PostMapping("/api/graphs")
    /** 创建画布。 */
    fun createCanvas(@Valid @RequestBody canvas: GraphCanvas): ResponseEntity<Response> {
        data class ResponseData(val id: Long, @param:JsonProperty("project_id") val projectId: Long, @param:JsonProperty("graph_kind") val graphKind: String)
        val saved = try {
            graphService.createCanvas(canvas)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        val rs = ResponseData(requireNotNull(saved.id), saved.projectId, saved.graphKind.name)
        return responseBuilder.created().data(rs).build()
    }

    @GetMapping("/api/graphs/node-catalog")
    /** 查询画布节点类型目录：前端节点面板与节点端口渲染的数据源。 */
    fun nodeCatalog(): ResponseEntity<Response> {
        data class PortData(
            val handle: String,
            val label: String,
            val color: String,
            @param:JsonProperty("required") val required: Boolean,
        )
        data class NodeData(
            val type: String,
            val title: String,
            @param:JsonProperty("category_key") val categoryKey: String,
            val color: String,
            val icon: List<String>,
            val singleton: Boolean,
            val inputs: List<PortData>,
            val outputs: List<PortData>,
        )
        data class CategoryData(
            val key: String,
            val label: String,
            @param:JsonProperty("node_count") val nodeCount: Int,
            val nodes: List<NodeData>,
        )
        data class ResponseData(val list: List<CategoryData>)

        val list = GraphNodeCatalog.categories.map { category ->
            CategoryData(
                category.key,
                category.label,
                category.nodes.size,
                category.nodes.map { node ->
                    NodeData(
                        node.type,
                        node.title,
                        node.categoryKey,
                        node.color,
                        node.icon,
                        node.singleton,
                        node.inputs.map { port -> PortData(port.handle, port.label, port.color, port.required) },
                        node.outputs.map { port -> PortData(port.handle, port.label, port.color, port.required) },
                    )
                },
            )
        }
        return responseBuilder.ok().data(ResponseData(list)).build()
    }

    @GetMapping("/api/graphs/{graph_id}")
    /** 查询画布及其全部投影数据和最近快照。 */
    fun getCanvas(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        data class GraphData(
            val canvas: GraphCanvas,
            val nodes: List<GraphNode>,
            val links: List<GraphLink>,
            val groups: List<GraphGroup>,
            val reroutes: List<GraphReroute>,
            @param:JsonProperty("subgraph_instances") val subgraphInstances: List<GraphSubgraphInstance>,
            val subgraphs: List<GraphSubgraph>,
            @param:JsonProperty("latest_snapshot") val latestSnapshot: GraphWorkflowSnapshot?,
        )

        val canvas = graphService.findCanvas(graphId) ?: return responseBuilder.notFound().build()
        val rs = GraphData(
            canvas = canvas,
            nodes = graphService.nodes(graphId),
            links = graphService.links(graphId),
            groups = graphService.groups(graphId),
            reroutes = graphService.reroutes(graphId),
            subgraphInstances = graphService.subgraphInstances(graphId),
            subgraphs = graphService.subgraphs(graphId),
            latestSnapshot = graphService.latestSnapshot(graphId),
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/graphs/{graph_id}")
    /** 更新画布元数据和 ComfyUI 配置。 */
    fun updateCanvas(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody canvas: GraphCanvas): ResponseEntity<Response> {
        val saved = try {
            graphService.updateCanvas(graphId, canvas)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to requireNotNull(saved.id), "updated_at" to saved.updatedAt)).build()
    }

    @DeleteMapping("/api/graphs/{graph_id}")
    /** 删除画布及其所有投影实体。 */
    fun deleteCanvas(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        if (!graphService.deleteCanvas(graphId)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to graphId, "deleted" to true)).build()
    }

    @GetMapping("/api/graphs/{graph_id}/nodes")
    fun listNodes(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        if (graphService.findCanvas(graphId) == null) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("list" to graphService.nodes(graphId))).build()
    }

    @GetMapping("/api/graphs/{graph_id}/links")
    fun listLinks(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        if (graphService.findCanvas(graphId) == null) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("list" to graphService.links(graphId))).build()
    }

    @GetMapping("/api/graphs/{graph_id}/groups")
    fun listGroups(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        if (graphService.findCanvas(graphId) == null) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("list" to graphService.groups(graphId))).build()
    }

    @GetMapping("/api/graphs/{graph_id}/reroutes")
    fun listReroutes(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        if (graphService.findCanvas(graphId) == null) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("list" to graphService.reroutes(graphId))).build()
    }

    @GetMapping("/api/graphs/{graph_id}/subgraph-instances")
    fun listSubgraphInstances(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        if (graphService.findCanvas(graphId) == null) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("list" to graphService.subgraphInstances(graphId))).build()
    }

    @GetMapping("/api/graphs/{graph_id}/subgraphs")
    fun listSubgraphs(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        if (graphService.findCanvas(graphId) == null) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("list" to graphService.subgraphs(graphId))).build()
    }

    @PostMapping("/api/graphs/{graph_id}/nodes")
    fun saveNode(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody node: GraphNode): ResponseEntity<Response> {
        val saved = graphService.saveNode(graphId, node) ?: return responseBuilder.notFound().build()
        return (if (node.id == null) responseBuilder.created() else responseBuilder.ok()).data(saved).build()
    }

    @GetMapping("/api/graphs/{graph_id}/nodes/{id}")
    fun getNode(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> = graphService.findNode(graphId, id)?.let { responseBuilder.ok().data(it).build() } ?: responseBuilder.notFound().build()

    @PutMapping("/api/graphs/{graph_id}/nodes/{id}")
    fun updateNode(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long, @Valid @RequestBody node: GraphNode): ResponseEntity<Response> {
        node.id = id
        val saved = graphService.saveNode(graphId, node) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(saved).build()
    }

    @PostMapping("/api/graphs/{graph_id}/links")
    fun saveLink(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody link: GraphLink): ResponseEntity<Response> {
        val saved = graphService.saveLink(graphId, link) ?: return responseBuilder.notFound().build()
        return (if (link.id == null) responseBuilder.created() else responseBuilder.ok()).data(saved).build()
    }

    @GetMapping("/api/graphs/{graph_id}/links/{id}")
    fun getLink(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> = graphService.findLink(graphId, id)?.let { responseBuilder.ok().data(it).build() } ?: responseBuilder.notFound().build()

    @PutMapping("/api/graphs/{graph_id}/links/{id}")
    fun updateLink(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long, @Valid @RequestBody link: GraphLink): ResponseEntity<Response> {
        link.id = id
        val saved = graphService.saveLink(graphId, link) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(saved).build()
    }

    @PostMapping("/api/graphs/{graph_id}/groups")
    fun saveGroup(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody group: GraphGroup): ResponseEntity<Response> {
        val saved = graphService.saveGroup(graphId, group) ?: return responseBuilder.notFound().build()
        return (if (group.id == null) responseBuilder.created() else responseBuilder.ok()).data(saved).build()
    }

    @GetMapping("/api/graphs/{graph_id}/groups/{id}")
    fun getGroup(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> = graphService.findGroup(graphId, id)?.let { responseBuilder.ok().data(it).build() } ?: responseBuilder.notFound().build()

    @PutMapping("/api/graphs/{graph_id}/groups/{id}")
    fun updateGroup(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long, @Valid @RequestBody group: GraphGroup): ResponseEntity<Response> {
        group.id = id
        val saved = graphService.saveGroup(graphId, group) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(saved).build()
    }

    @PostMapping("/api/graphs/{graph_id}/reroutes")
    fun saveReroute(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody reroute: GraphReroute): ResponseEntity<Response> {
        val saved = graphService.saveReroute(graphId, reroute) ?: return responseBuilder.notFound().build()
        return (if (reroute.id == null) responseBuilder.created() else responseBuilder.ok()).data(saved).build()
    }

    @GetMapping("/api/graphs/{graph_id}/reroutes/{id}")
    fun getReroute(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> = graphService.findReroute(graphId, id)?.let { responseBuilder.ok().data(it).build() } ?: responseBuilder.notFound().build()

    @PutMapping("/api/graphs/{graph_id}/reroutes/{id}")
    fun updateReroute(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long, @Valid @RequestBody reroute: GraphReroute): ResponseEntity<Response> {
        reroute.id = id
        val saved = graphService.saveReroute(graphId, reroute) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(saved).build()
    }

    @PostMapping("/api/graphs/{graph_id}/subgraph-instances")
    fun saveSubgraphInstance(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody instance: GraphSubgraphInstance): ResponseEntity<Response> {
        val saved = graphService.saveSubgraphInstance(graphId, instance) ?: return responseBuilder.notFound().build()
        return (if (instance.id == null) responseBuilder.created() else responseBuilder.ok()).data(saved).build()
    }

    @GetMapping("/api/graphs/{graph_id}/subgraph-instances/{id}")
    fun getSubgraphInstance(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> = graphService.findSubgraphInstance(graphId, id)?.let { responseBuilder.ok().data(it).build() } ?: responseBuilder.notFound().build()

    @PutMapping("/api/graphs/{graph_id}/subgraph-instances/{id}")
    fun updateSubgraphInstance(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long, @Valid @RequestBody instance: GraphSubgraphInstance): ResponseEntity<Response> {
        instance.id = id
        val saved = graphService.saveSubgraphInstance(graphId, instance) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(saved).build()
    }

    @PostMapping("/api/graphs/{graph_id}/subgraphs")
    fun saveSubgraph(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody subgraph: GraphSubgraph): ResponseEntity<Response> {
        val saved = graphService.saveSubgraph(graphId, subgraph) ?: return responseBuilder.notFound().build()
        return (if (subgraph.id == null) responseBuilder.created() else responseBuilder.ok()).data(saved).build()
    }

    @GetMapping("/api/graphs/{graph_id}/subgraphs/{id}")
    fun getSubgraph(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> = graphService.findSubgraph(graphId, id)?.let { responseBuilder.ok().data(it).build() } ?: responseBuilder.notFound().build()

    @PutMapping("/api/graphs/{graph_id}/subgraphs/{id}")
    fun updateSubgraph(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long, @Valid @RequestBody subgraph: GraphSubgraph): ResponseEntity<Response> {
        subgraph.id = id
        val saved = graphService.saveSubgraph(graphId, subgraph) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(saved).build()
    }

    @DeleteMapping("/api/graphs/{graph_id}/nodes/{id}")
    fun deleteNode(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> {
        if (!graphService.deleteNode(graphId, id)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to id, "deleted" to true)).build()
    }

    @DeleteMapping("/api/graphs/{graph_id}/links/{id}")
    fun deleteLink(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> {
        if (!graphService.deleteLink(graphId, id)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to id, "deleted" to true)).build()
    }

    @DeleteMapping("/api/graphs/{graph_id}/groups/{id}")
    fun deleteGroup(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> {
        if (!graphService.deleteGroup(graphId, id)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to id, "deleted" to true)).build()
    }

    @DeleteMapping("/api/graphs/{graph_id}/reroutes/{id}")
    fun deleteReroute(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> {
        if (!graphService.deleteReroute(graphId, id)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to id, "deleted" to true)).build()
    }

    @DeleteMapping("/api/graphs/{graph_id}/subgraph-instances/{id}")
    fun deleteSubgraphInstance(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> {
        if (!graphService.deleteSubgraphInstance(graphId, id)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to id, "deleted" to true)).build()
    }

    @DeleteMapping("/api/graphs/{graph_id}/subgraphs/{id}")
    fun deleteSubgraph(@PathVariable("graph_id") graphId: Long, @PathVariable("id") id: Long): ResponseEntity<Response> {
        if (!graphService.deleteSubgraph(graphId, id)) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to id, "deleted" to true)).build()
    }

    @GetMapping("/api/graphs/{graph_id}/snapshots")
    fun listSnapshots(@PathVariable("graph_id") graphId: Long): ResponseEntity<Response> {
        val canvas = graphService.findCanvas(graphId) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("list" to graphService.snapshots(canvas.projectId, canvas.projectVersionId, canvas.episodeId).filter { it.rootGraphId == graphId })).build()
    }

    @PostMapping("/api/graphs/{graph_id}/snapshots")
    fun saveSnapshot(@PathVariable("graph_id") graphId: Long, @Valid @RequestBody snapshot: GraphWorkflowSnapshot): ResponseEntity<Response> {
        val canvas = graphService.findCanvas(graphId) ?: return responseBuilder.notFound().build()
        snapshot.rootGraphId = graphId
        snapshot.projectId = canvas.projectId
        snapshot.episodeId = canvas.episodeId
        snapshot.projectVersionId = canvas.projectVersionId
        val saved = try {
            graphService.saveSnapshot(snapshot)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        return responseBuilder.created().data(mapOf("id" to requireNotNull(saved.id), "root_graph_id" to graphId)).build()
    }

}
