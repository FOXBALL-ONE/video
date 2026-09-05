package top.foxball.video.mq

import org.springframework.stereotype.Service

/** 提供给任务、AI、通知等上层模块的 MQ 发布门面。 */
@Service
class MqInvocationService(
    private val client: RabbitMqClient,
) : MqMessagePublisher {
    /**
     * 将上层模块的发布命令交给 RabbitMQ 下层客户端执行。
     *
     * @param command 包含逻辑目标、消息类型、业务载荷及可选路由信息的发布命令。
     * @return RabbitMQ publisher confirm 成功后的消息回执。
     * @throws MqConfigurationException 目标未定义或 MQ 运行参数非法时抛出。
     * @throws MqPublishException 消息未确认、不可路由、超时或网络发布失败时抛出。
     */
    override fun publish(command: MqPublishCommand): MqPublishReceipt = client.publish(command)
}
