package top.foxball.video.mq

import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.MessageListener
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.beans.factory.DisposableBean
import org.springframework.core.Ordered
import org.springframework.stereotype.Component

/** 为上层注册的 [MqMessageHandler] 创建并管理 RabbitMQ 消费容器。 */
@Component
class MqMessageListenerRegistrar(
    private val connectionFactory: ConnectionFactory,
    private val destinationResolver: MqDestinationResolver,
    private val client: RabbitMqClient,
    private val handlers: List<MqMessageHandler>,
) : ApplicationRunner, Ordered, DisposableBean {
    /** 当前注册器创建并负责关闭的所有监听容器。 */
    private val containers = mutableListOf<SimpleMessageListenerContainer>()

    /**
     * 在 Spring 应用启动阶段为每个上层处理器创建 RabbitMQ 消费容器。
     *
     * 一个逻辑目标只允许注册一个处理器，避免同一应用内重复消费或重复执行业务逻辑。
     * 容器使用目标定义的并发数和预取数，并采用自动确认模式；处理器正常返回时确认消息，
     * 处理器抛出异常时拒绝消息且不重新入队，使消息进入目标配置的死信队列。
     * 该方法由 [RabbitMqTopologyInitializer] 之后执行，确保队列已经完成存在性检查和创建。
     *
     * @param args Spring 启动参数；当前实现不读取具体参数。
     * @throws MqConfigurationException 处理器目标重复或目标名未在代码中定义时抛出。
     */
    override fun run(args: ApplicationArguments) {
        val duplicateDestinations = handlers.groupBy { it.destination.trim() }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateDestinations.isNotEmpty()) {
            throw MqConfigurationException("每个 MQ 目标只能注册一个处理器：${duplicateDestinations.joinToString(",")}")
        }
        handlers.forEach { handler ->
            val destination = destinationResolver.resolve(handler.destination)
            val container = SimpleMessageListenerContainer(connectionFactory).apply {
                setQueueNames(destination.queue)
                setConcurrentConsumers(destination.concurrentConsumers)
                setMaxConcurrentConsumers(destination.maxConcurrentConsumers)
                setPrefetchCount(destination.prefetchCount)
                setAcknowledgeMode(AcknowledgeMode.AUTO)
                setDefaultRequeueRejected(false)
                setMessageListener(MessageListener { message -> handler.handle(client.decode(message)) })
            }
            containers += container
            container.start()
        }
    }

    /**
     * 在 Spring 容器销毁时停止所有消费者。
     *
     * 停止监听容器可以释放 RabbitMQ consumer、线程和网络连接，避免应用关闭过程中继续接收新消息。
     */
    override fun destroy() {
        containers.forEach(SimpleMessageListenerContainer::stop)
    }

    /**
     * 返回启动顺序，使监听容器晚于拓扑初始化器启动。
     *
     * @return 紧随最高优先级启动器之后的顺序值。
     */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1
}
