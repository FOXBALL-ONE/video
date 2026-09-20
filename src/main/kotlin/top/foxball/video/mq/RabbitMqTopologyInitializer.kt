package top.foxball.video.mq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ShutdownSignalException
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** RabbitMQ 拓扑操作的最小封装，便于启动校验与无代理单元测试。 */
interface MqTopologyOperator {
    /**
     * 被动检查交换机是否存在。
     *
     * 实现不得在检查阶段创建资源；资源不存在应返回 `false`，连接、权限或其他协议错误应抛出异常。
     *
     * @param name 待检查的交换机名称。
     * @return 交换机存在返回 `true`，RabbitMQ 返回 404 表示不存在时返回 `false`。
     */
    fun exchangeExists(name: String): Boolean

    /**
     * 被动检查队列是否存在。
     *
     * @param name 待检查的队列名称。
     * @return 队列存在返回 `true`，RabbitMQ 返回 404 表示不存在时返回 `false`。
     */
    fun queueExists(name: String): Boolean

    /**
     * 声明一个持久化交换机。
     *
     * @param name 交换机名称。
     * @param type 代码定义的交换机类型。
     */
    fun declareExchange(name: String, type: MqExchangeType)

    /**
     * 声明一个持久化、非排他且不自动删除的队列。
     *
     * @param name 队列名称。
     * @param arguments RabbitMQ 队列参数，例如死信交换机参数。
     */
    fun declareQueue(name: String, arguments: Map<String, Any> = emptyMap())

    /**
     * 幂等地为队列补充交换机绑定。
     *
     * @param queue 目标队列名称。
     * @param exchange 源交换机名称。
     * @param routingKey 绑定使用的路由键。
     */
    fun bindQueue(queue: String, exchange: String, routingKey: String)
}

/** 使用 Spring AMQP ConnectionFactory 执行实际的 passive declare、声明和绑定操作。 */
@Component
class RabbitMqTopologyOperator(
    private val connectionFactory: ConnectionFactory,
) : MqTopologyOperator {
    /**
     * 使用 RabbitMQ passive declare 检查交换机，不主动创建资源。
     *
     * @param name 待检查的交换机名称。
     * @return 交换机存在返回 `true`，代理以 404 关闭 channel 时返回 `false`。
     * @throws MqException 连接、认证、权限或其他 RabbitMQ 协议错误时抛出。
     */
    override fun exchangeExists(name: String): Boolean = try {
        withChannel { it.exchangeDeclarePassive(name) }
        true
    } catch (exception: Exception) {
        if (exception.isNotFound()) false else throw MqException("检查 MQ 交换机失败：$name", exception)
    }

    /**
     * 使用 RabbitMQ passive declare 检查队列，不主动创建资源。
     *
     * @param name 待检查的队列名称。
     * @return 队列存在返回 `true`，代理以 404 关闭 channel 时返回 `false`。
     * @throws MqException 连接、认证、权限或其他 RabbitMQ 协议错误时抛出。
     */
    override fun queueExists(name: String): Boolean = try {
        withChannel { it.queueDeclarePassive(name) }
        true
    } catch (exception: Exception) {
        if (exception.isNotFound()) false else throw MqException("检查 MQ 队列失败：$name", exception)
    }

    /**
     * 创建持久化、非自动删除的交换机。
     *
     * @param name 代码拓扑定义中的交换机名称。
     * @param type 交换机协议类型。
     * @throws MqException RabbitMQ 拒绝声明或连接操作失败时抛出。
     */
    override fun declareExchange(name: String, type: MqExchangeType) {
        withChannel {
            it.exchangeDeclare(
                name,
                when (type) {
                    MqExchangeType.DIRECT -> BuiltinExchangeType.DIRECT
                    MqExchangeType.TOPIC -> BuiltinExchangeType.TOPIC
                },
                true,
                false,
                emptyMap(),
            )
        }
    }

    /**
     * 创建持久化、非排他且不自动删除的队列。
     *
     * @param name 代码拓扑定义中的队列名称。
     * @param arguments RabbitMQ 队列参数，通常包含死信交换机和死信路由键。
     * @throws MqException RabbitMQ 拒绝声明或连接操作失败时抛出。
     */
    override fun declareQueue(name: String, arguments: Map<String, Any>) {
        withChannel { it.queueDeclare(name, true, false, false, arguments) }
    }

    /**
     * 创建队列到交换机的绑定。
     *
     * RabbitMQ 对相同队列、交换机和路由键的重复绑定保持幂等，因此启动时可以无条件补绑。
     *
     * @param queue 目标队列名称。
     * @param exchange 源交换机名称。
     * @param routingKey 绑定路由键。
     * @throws MqException RabbitMQ 拒绝绑定或连接操作失败时抛出。
     */
    override fun bindQueue(queue: String, exchange: String, routingKey: String) {
        withChannel { it.queueBind(queue, exchange, routingKey) }
    }

    /**
     * 在一次独立 AMQP 连接和 channel 中执行单项拓扑操作。
     *
     * passive declare 在资源不存在时会导致 RabbitMQ 关闭当前 channel；每次操作使用独立
     * channel 可以把这种协议行为隔离开，避免影响后续目标的检查或声明。
     *
     * @param action 需要在已创建 channel 上执行的 RabbitMQ 操作。
     * @return [action] 返回的操作结果。
     * @throws RuntimeException [action] 或连接工厂抛出的异常会原样向上传播。
     */
    private fun <T> withChannel(action: (Channel) -> T): T {
        val connection = connectionFactory.createConnection()
        try {
            val channel = connection.createChannel(false)
            try {
                return action(channel)
            } finally {
                runCatching(channel::close)
            }
        } finally {
            runCatching(connection::close)
        }
    }

    /**
     * 判断异常链是否包含 RabbitMQ “资源不存在”的 channel close（回复码 404）。
     *
     * @return 异常链中存在 404 channel close 原因时返回 `true`，否则返回 `false`。
     */
    private fun Throwable.isNotFound(): Boolean = generateSequence(this) { it.cause }
        .filterIsInstance<ShutdownSignalException>()
        .mapNotNull { it.reason as? AMQP.Channel.Close }
        .any { it.replyCode == 404 }
}

/** 每次应用启动均检查并补齐 MQ 拓扑，已有资源不重复声明。 */
@Component
@ConditionalOnProperty(prefix = "mq", name = ["topology-enabled"], havingValue = "true", matchIfMissing = true)
class RabbitMqTopologyInitializer(
    private val operator: MqTopologyOperator,
    private val destinationResolver: MqDestinationResolver,
) : ApplicationRunner, Ordered {
    /**
     * Spring 启动回调；每次启动都执行完整的 MQ 拓扑检查与补齐。
     *
     * @param args Spring 启动参数；当前实现不读取具体参数。
     */
    override fun run(args: ApplicationArguments) {
        ensureTopologies()
    }

    /**
     * 检查并创建所有代码定义的目标拓扑。
     *
     * 对主交换机、主队列、死信交换机和死信队列分别执行存在性检查；只有检查结果为不存在时
     * 才创建资源，随后无条件补充绑定以修复缺失绑定。资源已存在时会直接复用，不会覆盖已有
     * 资源参数；如果需要修改交换机类型或队列参数，应先执行明确的 RabbitMQ 迁移操作。
     *
     * @throws MqConfigurationException 代码内置拓扑定义非法时抛出。
     * @throws MqException RabbitMQ 不可连接、无权限或资源声明失败时抛出。
     */
    fun ensureTopologies() {
        destinationResolver.all().forEach { destination ->
            if (!operator.exchangeExists(destination.deadLetterExchange)) {
                operator.declareExchange(destination.deadLetterExchange, MqExchangeType.DIRECT)
            }
            if (!operator.queueExists(destination.deadLetterQueue)) {
                operator.declareQueue(destination.deadLetterQueue)
            }
            operator.bindQueue(destination.deadLetterQueue, destination.deadLetterExchange, destination.deadLetterRoutingKey)

            if (!operator.exchangeExists(destination.exchange)) {
                operator.declareExchange(destination.exchange, destination.exchangeType)
            }
            if (!operator.queueExists(destination.queue)) {
                operator.declareQueue(
                    destination.queue,
                    mapOf(
                        "x-dead-letter-exchange" to destination.deadLetterExchange,
                        "x-dead-letter-routing-key" to destination.deadLetterRoutingKey,
                    ),
                )
            }
            operator.bindQueue(destination.queue, destination.exchange, destination.routingKey)
        }
    }

    /** 让拓扑初始化器先于消费者监听容器执行。 */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}
