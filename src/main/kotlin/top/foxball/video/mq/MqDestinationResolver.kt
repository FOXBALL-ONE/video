package top.foxball.video.mq

import org.springframework.stereotype.Component

/** RabbitMQ 交换机类型；值必须与代码声明时使用的协议类型一致。 */
enum class MqExchangeType { DIRECT, TOPIC }

/** 一个逻辑 MQ 目标的完整拓扑及消费并发参数。 */
data class MqDestination(
    /** 上层发布或订阅时使用的稳定逻辑名称。 */
    val name: String,
    /** 主交换机名称。 */
    val exchange: String,
    /** 主交换机类型。 */
    val exchangeType: MqExchangeType,
    /** 主消费队列名称。 */
    val queue: String,
    /** 默认发布路由键。 */
    val routingKey: String,
    /** 死信交换机名称。 */
    val deadLetterExchange: String,
    /** 死信队列名称。 */
    val deadLetterQueue: String,
    /** 投递到死信交换机时使用的路由键。 */
    val deadLetterRoutingKey: String,
    /** 初始消费者数量。 */
    val concurrentConsumers: Int,
    /** 消费者自动扩展允许达到的最大数量。 */
    val maxConcurrentConsumers: Int,
    /** 每个消费者从代理预取的消息数量。 */
    val prefetchCount: Int,
)

/** 将上层使用的逻辑目标名解析为受控 RabbitMQ 拓扑。 */
@Component
class MqDestinationResolver {
    /**
     * 根据逻辑目标名查找代码内置的 MQ 拓扑定义。
     *
     * @param name 上层模块传入的逻辑目标名；首尾空白会被忽略。
     * @return 与名称匹配且已通过完整性校验的目标定义。
     * @throws MqConfigurationException 名称为空、未定义或拓扑配置非法时抛出。
     */
    fun resolve(name: String): MqDestination {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) throw MqConfigurationException("MQ 目标名不能为空")
        return all().firstOrNull { it.name == normalizedName }
            ?: throw MqConfigurationException("未配置 MQ 目标：$normalizedName")
    }

    /**
     * 返回全部代码内置的 MQ 目标，并执行全量配置校验。
     *
     * 除了校验单个目标的字段，还会确保逻辑目标名、交换机和队列名称不冲突，
     * 使不同业务模块不会意外共享同一套 RabbitMQ 拓扑。
     *
     * @return 按 [MqTopologyDefinitions.destinations] 声明顺序排列的目标列表。
     * @throws MqConfigurationException 没有目标、字段非法或资源名称冲突时抛出。
     */
    fun all(): List<MqDestination> {
        val destinations = MqTopologyDefinitions.destinations
        if (destinations.isEmpty()) throw MqConfigurationException("至少需要配置一个 MQ 目标")
        destinations.forEach { it.validate() }
        if (destinations.map { it.name }.distinct().size != destinations.size) {
            throw MqConfigurationException("MQ 目标名不能重复")
        }
        val duplicateExchanges = destinations.flatMap { listOf(it.exchange, it.deadLetterExchange) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateExchanges.isNotEmpty()) {
            throw MqConfigurationException("MQ 目标必须使用不同的交换机：${duplicateExchanges.joinToString(",")}")
        }
        val duplicateQueues = destinations.flatMap { listOf(it.queue, it.deadLetterQueue) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateQueues.isNotEmpty()) {
            throw MqConfigurationException("MQ 目标必须使用不同的队列：${duplicateQueues.joinToString(",")}")
        }
        return destinations
    }
}

/**
 * 验证代码内置 MQ 目标的完整性。
 *
 * 此函数只检查本地定义，不访问 RabbitMQ；代理资源是否存在由启动期拓扑初始化器负责。
 *
 * @throws MqConfigurationException 交换机、队列、路由键、死信配置或并发参数非法时抛出。
 */
fun MqDestination.validate() {
    val targetName = name.trim()
    val targetExchange = exchange.trim()
    val targetQueue = queue.trim()
    val targetRoutingKey = routingKey.trim()
    val targetDeadLetterExchange = deadLetterExchange.trim()
    val targetDeadLetterQueue = deadLetterQueue.trim()
    val targetDeadLetterRoutingKey = deadLetterRoutingKey.trim()
    if (targetExchange.isEmpty() || targetQueue.isEmpty() || targetRoutingKey.isEmpty()) {
        throw MqConfigurationException("MQ 目标 $targetName 缺少主交换机、队列或路由键配置")
    }
    if (targetDeadLetterExchange.isEmpty() || targetDeadLetterQueue.isEmpty() || targetDeadLetterRoutingKey.isEmpty()) {
        throw MqConfigurationException("MQ 目标 $targetName 缺少死信交换机、队列或路由键配置")
    }
    if (targetExchange == targetDeadLetterExchange || targetQueue == targetDeadLetterQueue) {
        throw MqConfigurationException("MQ 目标 $targetName 的主拓扑与死信拓扑必须分离")
    }
    if (concurrentConsumers <= 0 || maxConcurrentConsumers < concurrentConsumers || prefetchCount <= 0) {
        throw MqConfigurationException("MQ 目标 $targetName 的消费者并发或预取数量不正确")
    }
}
