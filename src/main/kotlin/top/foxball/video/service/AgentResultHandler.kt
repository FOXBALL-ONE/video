package top.foxball.video.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

/** 智能体输出回填入口；首期保留统一钩子，具体业务可按 agent_key 扩展。 */
@Service
class AgentResultHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handleResult(agentKey: String, output: JsonNode, episodeId: Long, projectId: Long?, userId: Long?) {
        logger.info(
            "智能体结果待回填：agentKey={}, episodeId={}, projectId={}, userId={}, outputType={}",
            agentKey,
            episodeId,
            projectId,
            userId,
            when {
                output.isObject -> "object"
                output.isArray -> "array"
                else -> "value"
            },
        )
    }
}
