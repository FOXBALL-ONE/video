package top.foxball.video.mq

/** MQ 拓扑名称由代码统一维护，避免不同部署环境误用不同的队列或交换机。 */
object MqTopologyDefinitions {
    /** 任务调度消息使用的逻辑目标名。 */
    const val TASK_DISPATCH = "task-dispatch"
    /** AI 工作消息使用的逻辑目标名。 */
    const val AI_WORK = "ai-work"
    /** 任务状态事件使用的逻辑目标名。 */
    const val TASK_EVENTS = "task-events"

    /**
     * 所有受支持的业务目标定义。
     *
     * 名称、交换机、队列、路由键、死信拓扑和消费者参数均在代码中固定维护；
     * [MqDestinationResolver] 会在使用前校验该列表的完整性和资源名称唯一性。
     */
    val destinations: List<MqDestination> = listOf(
        MqDestination(
            name = TASK_DISPATCH,
            exchange = "video.task.dispatch",
            exchangeType = MqExchangeType.TOPIC,
            queue = "video.task.dispatch",
            routingKey = "task.dispatch",
            deadLetterExchange = "video.task.dispatch.dlx",
            deadLetterQueue = "video.task.dispatch.dlq",
            deadLetterRoutingKey = "task.dispatch.dead",
            concurrentConsumers = 2,
            maxConcurrentConsumers = 4,
            prefetchCount = 2,
        ),
        MqDestination(
            name = AI_WORK,
            exchange = "video.ai.work",
            exchangeType = MqExchangeType.TOPIC,
            queue = "video.ai.work",
            routingKey = "ai.work",
            deadLetterExchange = "video.ai.work.dlx",
            deadLetterQueue = "video.ai.work.dlq",
            deadLetterRoutingKey = "ai.work.dead",
            concurrentConsumers = 2,
            maxConcurrentConsumers = 4,
            prefetchCount = 1,
        ),
        MqDestination(
            name = TASK_EVENTS,
            exchange = "video.task.event",
            exchangeType = MqExchangeType.TOPIC,
            queue = "video.task.event",
            routingKey = "task.event.#",
            deadLetterExchange = "video.task.event.dlx",
            deadLetterQueue = "video.task.event.dlq",
            deadLetterRoutingKey = "task.event.dead",
            concurrentConsumers = 1,
            maxConcurrentConsumers = 2,
            prefetchCount = 8,
        ),
    )
}
