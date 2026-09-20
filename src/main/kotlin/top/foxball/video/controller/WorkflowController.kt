package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import top.foxball.video.entity.jdbc.ProductionStage
import top.foxball.video.service.WorkflowExecutionService
import top.foxball.video.service.WorkflowProgressService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

/** Episode 线性工作流接口。 */
@RestController
class WorkflowController(
    private val progressService: WorkflowProgressService,
    private val executionService: WorkflowExecutionService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/episodes/{episode_id}/workflow")
    fun state(@PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        val state = progressService.getWorkflowState(episodeId) ?: return responseBuilder.notFound().build()
        data class Response(
            @param:JsonProperty("episode_id") val episodeId: Long,
            @param:JsonProperty("current_stage") val currentStage: String,
            @param:JsonProperty("executed_agents") val executedAgents: List<String>,
            @param:JsonProperty("stage_progress") val stageProgress: Map<String, Int>,
            @param:JsonProperty("pending_agents") val pendingAgents: List<String>,
        )
        return responseBuilder.ok().data(Response(state.episodeId, state.currentStage.name, state.executedAgents, state.stageProgress, state.pendingAgents)).build()
    }

    @GetMapping("/api/episodes/{episode_id}/workflow/stages")
    fun stages(@PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        data class StageData(val stage: String, val progress: Int, @param:JsonProperty("total_agents") val totalAgents: Int, @param:JsonProperty("completed_agents") val completedAgents: Int, @param:JsonProperty("pending_agents") val pendingAgents: List<String>)
        data class Response(val items: List<StageData>)
        return responseBuilder.ok().data(Response(progressService.getAllStagesInfo(episodeId).map { StageData(it.stage.name, it.progress, it.totalAgents, it.completedAgents, it.pendingAgents) })).build()
    }

    @PostMapping("/api/episodes/{episode_id}/workflow/initialize")
    fun initialize(@PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        data class Response(val episodeId: Long, val currentStage: String, val executedAgents: List<String>, val stageProgress: Map<String, Int>, val pendingAgents: List<String>)
        return try {
            val state = progressService.initializeWorkflow(episodeId)
            responseBuilder.ok().data(Response(state.episodeId, state.currentStage.name, state.executedAgents, state.stageProgress, state.pendingAgents)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PostMapping("/api/episodes/{episode_id}/workflow/advance")
    fun advance(@PathVariable("episode_id") episodeId: Long, @RequestParam("user_id", required = false) userId: Long?): ResponseEntity<Response> {
        data class Response(val stage: String)
        return try {
            responseBuilder.ok().data(Response(progressService.advanceStage(episodeId, userId).name)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PostMapping("/api/episodes/{episode_id}/workflow/chain")
    fun chain(@PathVariable("episode_id") episodeId: Long, @AuthenticationPrincipal userId: Long?, @RequestBody body: JsonNode): ResponseEntity<Response> {
        data class Response(val status: String, val results: List<top.foxball.video.service.AgentExecutionResult>, @param:JsonProperty("failed_at_step") val failedAtStep: Int?, @param:JsonProperty("error_message") val errorMessage: String?)
        val steps = body.path("steps")
        if (!steps.isArray) return responseBuilder.badRequest().message("steps 必须是数组").build()
        return try {
            val result = executionService.executeChain(
                WorkflowExecutionService.AgentChainRequest(
                    episodeId = episodeId,
                    projectId = body.path("project_id").takeIf { it.isIntegralNumber }?.longValue(),
                    steps = steps.toList().map { step ->
                        WorkflowExecutionService.AgentChainStep(
                            step.path("agent_key").stringValue(null).orEmpty(),
                            step.path("input_mapping").properties().associate { field -> field.key to field.value.stringValue("") },
                        )
                    },
                    initialInput = body.path("initial_input"),
                    providerConfigId = body.path("provider_config_id").longValue(),
                    model = body.path("model").stringValue(null).orEmpty(),
                    userId = userId,
                    productionStage = body.path("production_stage").stringValue(null)?.let(ProductionStage::valueOf),
                    onFailure = body.path("on_failure").stringValue(null)?.let(WorkflowExecutionService.FailurePolicy::valueOf) ?: WorkflowExecutionService.FailurePolicy.STOP,
                ),
            )
            responseBuilder.ok().data(Response(result.status.name, result.results, result.failedAtStep, result.errorMessage)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }
}
