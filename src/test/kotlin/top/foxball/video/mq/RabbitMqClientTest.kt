package top.foxball.video.mq

import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import tools.jackson.databind.json.JsonMapper
import top.foxball.video.config.MqProperties
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RabbitMqClientTest {
    @Test
    fun `publishes a JSON envelope to the resolved exchange and waits for confirmation`() {
        val properties = properties()
        val template = CapturingRabbitTemplate()
        val client = RabbitMqClient(template, MqDestinationResolver(), JsonMapper.builder().build(), properties)

        val receipt = client.publish(
            MqPublishCommand(
                destination = "task-dispatch",
                messageType = "script.analysis.submitted",
                payload = mapOf("task_id" to 42, "priority" to "normal"),
                correlationId = "task-42",
                headers = mapOf("x-project-id" to "7"),
            ),
        )

        assertEquals("video.task.dispatch", template.capturedExchange)
        assertEquals("task.dispatch", template.capturedRoutingKey)
        assertEquals("task-dispatch", receipt.destination)
        assertEquals(receipt.messageId, template.message.messageProperties.messageId)
        assertEquals("script.analysis.submitted", template.message.messageProperties.type)
        assertEquals("task-42", template.message.messageProperties.correlationId)
        assertEquals("7", template.message.messageProperties.getHeader<String>("x-project-id"))

        val decoded = client.decode(template.message)
        assertEquals(receipt.messageId, decoded.messageId)
        assertEquals("script.analysis.submitted", decoded.messageType)
        assertEquals("task-42", decoded.correlationId)
        assertEquals(42, decoded.payload.path("task_id").intValue())
    }

    @Test
    fun `reuses existing exchanges and queues on startup`() {
        val destinations = MqTopologyDefinitions.destinations
        val operator = RecordingTopologyOperator(
            existingExchanges = destinations.flatMap { listOf(it.exchange, it.deadLetterExchange) }.toMutableSet(),
            existingQueues = destinations.flatMap { listOf(it.queue, it.deadLetterQueue) }.toMutableSet(),
        )

        RabbitMqTopologyInitializer(operator, MqDestinationResolver()).ensureTopologies()

        assertEquals(destinations.flatMap { listOf(it.exchange, it.deadLetterExchange) }.toSet(), operator.checkedExchanges.toSet())
        assertEquals(destinations.flatMap { listOf(it.queue, it.deadLetterQueue) }.toSet(), operator.checkedQueues.toSet())
        assertTrue(operator.declaredExchanges.isEmpty())
        assertTrue(operator.declaredQueues.isEmpty())
        assertEquals(destinations.size * 2, operator.bindings.size)
    }

    @Test
    fun `rejects unconfigured destinations`() {
        val exception = assertFailsWith<MqConfigurationException> {
            MqDestinationResolver().resolve("unknown")
        }

        assertTrue(exception.message!!.contains("unknown"))
    }

    @Test
    fun `creates missing exchanges and queues on startup`() {
        val operator = RecordingTopologyOperator()

        RabbitMqTopologyInitializer(operator, MqDestinationResolver()).ensureTopologies()

        val destinations = MqTopologyDefinitions.destinations
        assertEquals(destinations.size * 2, operator.declaredExchanges.size)
        assertEquals(destinations.size * 2, operator.declaredQueues.size)
        assertEquals(destinations.size * 2, operator.bindings.size)
        assertEquals(
            "video.task.dispatch.dlx",
            operator.declaredQueues.getValue("video.task.dispatch")["x-dead-letter-exchange"],
        )
    }

    private fun properties() = MqProperties(
        publishConfirmTimeout = Duration.ofSeconds(1),
    )

    private class RecordingTopologyOperator(
        private val existingExchanges: MutableSet<String> = mutableSetOf(),
        private val existingQueues: MutableSet<String> = mutableSetOf(),
    ) : MqTopologyOperator {
        val checkedExchanges = mutableListOf<String>()
        val checkedQueues = mutableListOf<String>()
        val declaredExchanges = mutableMapOf<String, MqExchangeType>()
        val declaredQueues = mutableMapOf<String, Map<String, Any>>()
        val bindings = mutableListOf<Triple<String, String, String>>()

        override fun exchangeExists(name: String): Boolean {
            checkedExchanges += name
            return name in existingExchanges
        }

        override fun queueExists(name: String): Boolean {
            checkedQueues += name
            return name in existingQueues
        }

        override fun declareExchange(name: String, type: MqExchangeType) {
            declaredExchanges[name] = type
            existingExchanges += name
        }

        override fun declareQueue(name: String, arguments: Map<String, Any>) {
            declaredQueues[name] = arguments
            existingQueues += name
        }

        override fun bindQueue(queue: String, exchange: String, routingKey: String) {
            bindings += Triple(queue, exchange, routingKey)
        }
    }

    private class CapturingRabbitTemplate : RabbitTemplate() {
        lateinit var capturedExchange: String
        lateinit var capturedRoutingKey: String
        lateinit var message: Message

        override fun send(exchange: String?, routingKey: String?, message: Message, correlationData: CorrelationData?) {
            this.capturedExchange = requireNotNull(exchange)
            this.capturedRoutingKey = requireNotNull(routingKey)
            this.message = message
            requireNotNull(correlationData).future.complete(CorrelationData.Confirm(true, null))
        }
    }
}
