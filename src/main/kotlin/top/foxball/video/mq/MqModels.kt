package top.foxball.video.mq

import tools.jackson.databind.JsonNode
import java.time.LocalDateTime

/** 上层模块发布消息时使用的通用命令。 */
data class MqPublishCommand(
    /** 代码内置的逻辑目标名，例如 [MqTopologyDefinitions.TASK_DISPATCH]。 */
    val destination: String,
    /** 用于消费者分派和协议识别的业务消息类型。 */
    val messageType: String,
    /** 会被序列化为消息信封 `payload` 的业务对象。 */
    val payload: Any,
    /** 用于关联任务、请求或工作流的业务关联标识。 */
    val correlationId: String? = null,
    /** 可选的具体路由键；为空时使用目标定义中的默认路由键。 */
    val routingKey: String? = null,
    /** 需要写入 RabbitMQ 消息属性的字符串头。 */
    val headers: Map<String, String> = emptyMap(),
)

/** 发布确认成功后返回给调用方的消息投递回执。 */
data class MqPublishReceipt(
    /** 客户端生成并写入信封的唯一消息 ID。 */
    val messageId: String,
    /** 使用的逻辑目标名。 */
    val destination: String,
    /** 实际投递到的交换机名称。 */
    val exchange: String,
    /** 实际使用的路由键。 */
    val routingKey: String,
    /** 客户端创建消息信封的本地时间。 */
    val publishedAt: LocalDateTime,
)

/** 下层客户端解码后的统一接收消息模型。 */
data class MqReceivedMessage(
    /** 消息唯一 ID，优先读取 JSON 信封，兼容 RabbitMQ message-id 属性。 */
    val messageId: String,
    /** 业务消息类型，优先读取 JSON 信封，兼容 RabbitMQ type 属性。 */
    val messageType: String,
    /** 发布方传入的业务关联标识。 */
    val correlationId: String?,
    /** 消息信封生成时间，使用无时区 ISO-8601 本地时间。 */
    val occurredAt: LocalDateTime,
    /** 未绑定具体业务 DTO 的 JSON 载荷。 */
    val payload: JsonNode,
    /** RabbitMQ 消息头，空值头会在解码时过滤。 */
    val headers: Map<String, Any>,
    /** RabbitMQ 实际消费队列名称。 */
    val queue: String?,
    /** RabbitMQ 实际接收交换机名称。 */
    val exchange: String?,
    /** RabbitMQ 实际接收路由键。 */
    val routingKey: String?,
)

/** 上层模块仅依赖此发布接口，不直接依赖 RabbitMQ 客户端。 */
interface MqMessagePublisher {
    /**
     * 发布一条消息并等待代理确认。
     *
     * @param command 待发布的消息命令。
     * @return 消息已被代理确认后的投递回执。
     * @throws MqConfigurationException 逻辑目标不存在或 MQ 运行配置非法时抛出。
     * @throws MqPublishException 消息未确认、不可路由、超时或网络发布失败时抛出。
     */
    fun publish(command: MqPublishCommand): MqPublishReceipt
}

/** 上层模块实现此接口即可订阅配置中指定的逻辑目标。 */
interface MqMessageHandler {
    /** 处理器订阅的代码内置逻辑目标名。 */
    val destination: String

    /**
     * 处理一条已经完成协议校验和 JSON 解码的消息。
     *
     * 方法抛出的异常会由监听容器转换为拒绝确认，消息随后进入该目标的死信队列。
     * 实现必须按 [MqReceivedMessage.messageId] 保证幂等。
     *
     * @param message 统一接收消息模型。
     */
    fun handle(message: MqReceivedMessage)
}

/** MQ 基础客户端的异常根类型。 */
open class MqException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** MQ 代码定义、逻辑目标或运行参数非法。 */
class MqConfigurationException(message: String) : MqException(message)

/** 消息发布未完成或未得到可靠确认。 */
class MqPublishException(message: String, cause: Throwable? = null) : MqException(message, cause)

/** 收到的消息不符合统一 JSON 信封协议。 */
class MqMessageFormatException(message: String, cause: Throwable? = null) : MqException(message, cause)
