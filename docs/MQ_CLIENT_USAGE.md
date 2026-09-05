# RabbitMQ 通用基础客户端

MQ 基础客户端位于 `top.foxball.video.mq`，上层模块只依赖 `MqMessagePublisher`、`MqPublishCommand` 和 `MqMessageHandler`。RabbitMQ 连接、JSON 信封、发布确认、交换机/队列声明和死信处理均封装在下层。拓扑名称固定在 `MqTopologyDefinitions`，不从环境变量读取。

## 发布消息

注入 `MqMessagePublisher`（或 `MqInvocationService`），使用配置中的逻辑目标名发布：

```kotlin
publisher.publish(
    MqPublishCommand(
        destination = MqTopologyDefinitions.TASK_DISPATCH,
        messageType = "script.analysis.submitted",
        correlationId = task.correlationId,
        payload = mapOf("task_id" to task.id, "project_id" to task.projectId),
    ),
)
```

客户端会生成 `message_id`，并发送以下 JSON 信封字段：`message_id`、`message_type`、`correlation_id`、`occurred_at`、`payload`。发布只有在 publisher confirm 成功且消息可路由到队列后才返回回执。

`routingKey` 可选；不填写时使用目标的代码默认路由键。同一交换机需要按事件类型路由时，可在发布命令中传入具体路由键。

## 消费消息

每个逻辑目标注册一个处理器：

```kotlin
@Component
class TaskDispatchHandler : MqMessageHandler {
    override val destination = MqTopologyDefinitions.TASK_DISPATCH

    override fun handle(message: MqReceivedMessage) {
        val taskId = message.payload.path("task_id").longValue()
        // 调用任务执行器
    }
}
```

处理器抛出异常或消息格式不合法时，消息不会重新入队，而是路由到该目标的死信队列。处理器必须保证按 `message_id` 幂等。

## 新增业务目标

在 `MqTopologyDefinitions` 添加目标。每次启动会先被动检查主/死信交换机和队列：资源存在时直接复用，资源不存在时创建并补齐绑定。每个目标必须提供独立的主交换机、主队列、路由键、死信交换机、死信队列和死信路由键；消费并发与预取数量也在此处定义。
