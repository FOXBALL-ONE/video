package top.foxball.video.service

import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.stereotype.Service

/** 由项目代码实现、可被模板直接调用的内置工具。 */
interface TemplateTool {
    /** 该工具暴露给模型的全部回调；一个实现可以提供多个工具。 */
    val callbacks: List<ToolCallback>
}

/**
 * 内置工具注册表。
 *
 * 与 MCP 工具不同，内置工具由项目自身实现，无需外部进程或网络服务即可使用。
 * 模板版本通过 [AiTemplateVersion.toolsJson] 引用工具名，技能通过自身声明引用同一批工具。
 */
@Service
class TemplateToolRegistry(tools: List<TemplateTool>) {
    private val callbacksByName: Map<String, ToolCallback> =
        tools.flatMap { it.callbacks }.associateBy { it.toolDefinition.name() }

    /** 内置工具描述，用于管理端枚举可用工具。 */
data class TemplateToolDescriptor(val name: String, val description: String)

    /** 返回全部已注册的工具名。 */
    fun listTools(): List<String> = callbacksByName.keys.sorted()

    /** 返回全部已注册工具的名称与描述。 */
    fun describeTools(): List<TemplateToolDescriptor> =
        callbacksByName.values.map { TemplateToolDescriptor(it.toolDefinition.name(), it.toolDefinition.description()) }

    /** 校验工具引用；未注册的工具会导致模板无法发布。 */
    fun requireAvailable(toolNames: Collection<String>) {
        val missing = toolNames.filter { it !in callbacksByName }
        require(missing.isEmpty()) { "未注册的内置工具：${missing.joinToString(", ")}" }
    }

    /** 按名称取回工具回调；顺序与请求顺序一致，重复名称只装配一次。 */
    fun callbacksFor(toolNames: List<String>): List<ToolCallback> =
        toolNames.distinct().mapNotNull { callbacksByName[it] }
}

/**
 * 以纯函数方式实现一个内置工具：定义描述工具签名，处理函数把 JSON 输入映射为 JSON 输出。
 * 工具执行不接触数据库或网络，异常会被 Spring AI 转换为工具错误返回给模型。
 */
class SimpleToolCallback(
    name: String,
    description: String,
    inputSchema: String,
    private val handler: (String) -> String,
) : ToolCallback {
    private val definition: ToolDefinition = DefaultToolDefinition.builder()
        .name(name)
        .description(description)
        .inputSchema(inputSchema)
        .build()

    override fun getToolDefinition(): ToolDefinition = definition

    override fun call(toolInput: String): String = handler(toolInput)
}
