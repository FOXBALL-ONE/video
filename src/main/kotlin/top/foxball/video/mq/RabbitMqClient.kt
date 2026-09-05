package top.foxball.video.mq

import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import top.foxball.video.config.MqProperties
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** RabbitMQ 下层客户端，统一消息信封、发布确认和协议解析。 */
@Component
class RabbitMqClient(
    private val rabbitTemplate: RabbitTemplate,
    private val destinationResolver: MqDestinationResolver,
    private val objectMapper: ObjectMapper,
    private val properties: MqProperties,
) {
    /**
     * 将业务命令封装成统一 JSON 信封并发布到目标交换机。
     *
     * 客户端会生成唯一 `message_id`，写入消息类型、关联 ID、ISO-8601 本地时间和业务载荷，
     * 以持久化消息属性投递，并等待 RabbitMQ publisher confirm。只有代理确认成功且消息未被
     * mandatory return 判定为不可路由时，方法才返回回执。
     *
     * @param command 目标、消息类型、载荷、可选关联 ID、路由键和消息头。
     * @return 已获得代理确认的消息投递回执。
     * @throws MqConfigurationException 目标不存在或发布确认超时时间非法时抛出。
     * @throws MqPublishException 消息类型/消息头非法、代理拒绝、不可路由、超时或网络错误时抛出。
     */
    fun publish(command: MqPublishCommand): MqPublishReceipt {
        val destination = destinationResolver.resolve(command.destination)
        val messageType = command.messageType.trim()
        if (messageType.isEmpty()) throw MqPublishException("MQ 消息类型不能为空")
        val routingKey = command.routingKey?.trim()?.takeIf { it.isNotEmpty() } ?: destination.routingKey
        if (properties.publishConfirmTimeout.isZero || properties.publishConfirmTimeout.isNegative) {
            throw MqConfigurationException("MQ 发布确认超时时间必须大于零")
        }
        val messageId = UUID.randomUUID().toString()
        val publishedAt = LocalDateTime.now()
        val body = objectMapper.createObjectNode().apply {
            put("message_id", messageId)
            put("message_type", messageType)
            command.correlationId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("correlation_id", it) }
            put("occurred_at", publishedAt.format(ISO_LOCAL_DATE_TIME))
            set("payload", objectMapper.valueToTree(command.payload))
        }
        val messageProperties = MessageProperties().apply {
            this.messageId = messageId
            type = messageType
            correlationId = command.correlationId?.trim()?.takeIf { it.isNotEmpty() }
            contentType = MessageProperties.CONTENT_TYPE_JSON
            contentEncoding = StandardCharsets.UTF_8.name()
            deliveryMode = MessageDeliveryMode.PERSISTENT
            command.headers.forEach { (name, value) ->
                if (name.isBlank()) throw MqPublishException("MQ 消息头名称不能为空")
                setHeader(name, value)
            }
        }
        val correlationData = CorrelationData(messageId)
        try {
            rabbitTemplate.send(
                destination.exchange,
                routingKey,
                Message(objectMapper.writeValueAsBytes(body), messageProperties),
                correlationData,
            )
            val confirm = correlationData.future.get(properties.publishConfirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!confirm.ack) throw MqPublishException("MQ 消息未被代理确认：${confirm.reason() ?: "未知原因"}")
            if (correlationData.returned != null) throw MqPublishException("MQ 消息无法路由到目标队列")
        } catch (exception: MqException) {
            throw exception
        } catch (exception: TimeoutException) {
            throw MqPublishException("等待 MQ 发布确认超时", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MqPublishException("等待 MQ 发布确认时被中断", exception)
        } catch (exception: ExecutionException) {
            throw MqPublishException("MQ 发布确认失败", exception.cause ?: exception)
        } catch (exception: AmqpException) {
            throw MqPublishException("MQ 消息发布失败", exception)
        }
        return MqPublishReceipt(messageId, destination.name, destination.exchange, routingKey, publishedAt)
    }

    /**
     * 将 RabbitMQ 原始消息解析为统一接收模型。
     *
     * 解析时优先使用 JSON 信封中的消息 ID、消息类型和关联 ID；对旧生产者则回退到
     * RabbitMQ message properties。时间字段必须是无时区 [java.time.LocalDateTime] 的
     * ISO-8601 表示，payload 必须存在但不限制具体业务结构。
     *
     * @param message RabbitMQ 消费容器交付的原始消息。
     * @return 已完成信封校验的统一消息对象。
     * @throws MqMessageFormatException JSON 无法解析、必填字段缺失或时间格式非法时抛出。
     */
    fun decode(message: Message): MqReceivedMessage {
        val body = try {
            objectMapper.readTree(message.body)
        } catch (exception: Exception) {
            throw MqMessageFormatException("MQ 消息不是有效 JSON", exception)
        }
        if (body == null || !body.isObject) throw MqMessageFormatException("MQ 消息信封必须为 JSON 对象")
        val messageId = body.path("message_id").stringValue()?.trim()?.takeIf { it.isNotEmpty() }
            ?: message.messageProperties.messageId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MqMessageFormatException("MQ 消息缺少 message_id")
        val messageType = body.path("message_type").stringValue()?.trim()?.takeIf { it.isNotEmpty() }
            ?: message.messageProperties.type?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MqMessageFormatException("MQ 消息缺少 message_type")
        val occurredAtText = body.path("occurred_at").stringValue()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MqMessageFormatException("MQ 消息缺少 occurred_at")
        val occurredAt = try {
            LocalDateTime.parse(occurredAtText, ISO_LOCAL_DATE_TIME)
        } catch (exception: Exception) {
            throw MqMessageFormatException("MQ 消息 occurred_at 不是 ISO-8601 本地时间", exception)
        }
        return MqReceivedMessage(
            messageId = messageId,
            messageType = messageType,
            correlationId = body.path("correlation_id").stringValue()?.trim()?.takeIf { it.isNotEmpty() }
                ?: message.messageProperties.correlationId,
            occurredAt = occurredAt,
            payload = body.get("payload") ?: throw MqMessageFormatException("MQ 消息缺少 payload"),
            headers = message.messageProperties.headers.mapNotNull { (name, value) -> value?.let { name to it } }.toMap(),
            queue = message.messageProperties.consumerQueue,
            exchange = message.messageProperties.receivedExchange,
            routingKey = message.messageProperties.receivedRoutingKey,
        )
    }
}
