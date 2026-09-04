package top.foxball.video.ai

import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class AiOperation { MODEL_LIST, CHAT, IMAGE, VIDEO }

/** 提供商级有界并发和轻量断路器，避免单一上游故障耗尽应用工作线程。 */
@Component
class AiInvocationGuard {
    private val semaphores = ConcurrentHashMap<GateKey, Semaphore>()
    private val circuits = ConcurrentHashMap<String, CircuitState>()

    /**
     * 为单值响应式调用套用断路器和信号量并发闸门。
     * 调用开始前检查断路器状态及并发额度，结束时无论成功失败都释放许可。
     */
    fun <T : Any> protect(
        connection: AiProviderConnection,
        operation: AiOperation,
        invocation: () -> Mono<T>,
    ): Mono<T> = Mono.defer {
        val circuitKey = connectionKey(connection)
        val circuit = circuits.computeIfAbsent(circuitKey) { CircuitState() }
        if (!circuit.tryAcquire()) return@defer Mono.error(AiCircuitOpenException())

        val gateKey = GateKey(circuitKey, operation)
        val semaphore = semaphores.computeIfAbsent(gateKey) {
            Semaphore(limitFor(connection.profile, operation))
        }
        if (!semaphore.tryAcquire()) return@defer Mono.error(AiRateLimitException(null))

        invocation()
            .doOnSuccess { circuit.recordSuccess() }
            .doOnError { exception -> circuit.recordFailure(exception) }
            .doFinally { semaphore.release() }
    }

    /** 对流式响应式调用执行与 [protect] 相同的断路器和并发限制。 */
    fun <T : Any> protectFlux(
        connection: AiProviderConnection,
        operation: AiOperation,
        invocation: () -> reactor.core.publisher.Flux<T>,
    ): reactor.core.publisher.Flux<T> = reactor.core.publisher.Flux.defer {
        val circuitKey = connectionKey(connection)
        val circuit = circuits.computeIfAbsent(circuitKey) { CircuitState() }
        if (!circuit.tryAcquire()) return@defer reactor.core.publisher.Flux.error(AiCircuitOpenException())

        val gateKey = GateKey(circuitKey, operation)
        val semaphore = semaphores.computeIfAbsent(gateKey) {
            Semaphore(limitFor(connection.profile, operation))
        }
        if (!semaphore.tryAcquire()) return@defer reactor.core.publisher.Flux.error(AiRateLimitException(null))

        invocation()
            .doOnComplete { circuit.recordSuccess() }
            .doOnError { exception -> circuit.recordFailure(exception) }
            .doFinally { semaphore.release() }
    }

    /** 根据操作类型读取连接档案中的最大并发请求数。 */
    private fun limitFor(profile: AiConnectionProfile, operation: AiOperation): Int = when (operation) {
        AiOperation.MODEL_LIST -> profile.maxInFlightModelListRequests
        AiOperation.CHAT -> profile.maxInFlightChatRequests
        AiOperation.IMAGE -> profile.maxInFlightImageRequests
        AiOperation.VIDEO -> profile.maxInFlightImageRequests
    }

    /** 生成用于共享断路器和信号量的连接标识，不包含 API 密钥。 */
    private fun connectionKey(connection: AiProviderConnection): String =
        "${connection.providerConfigId ?: connection.baseUri}|${connection.profile.name}"

    private data class GateKey(val connection: String, val operation: AiOperation)

    private class CircuitState {
        private val consecutiveFailures = AtomicInteger()
        private val openUntilEpochMillis = AtomicLong(0)
        private val halfOpenProbeInFlight = AtomicBoolean(false)

        /** 尝试进入断路器；半开状态只允许一个探测请求通过。 */
        fun tryAcquire(): Boolean {
            val openUntil = openUntilEpochMillis.get()
            if (openUntil == 0L) return true
            if (Instant.now().toEpochMilli() < openUntil) return false
            return halfOpenProbeInFlight.compareAndSet(false, true)
        }

        /** 记录成功调用并关闭断路器、清零连续失败计数。 */
        fun recordSuccess() {
            consecutiveFailures.set(0)
            openUntilEpochMillis.set(0)
            halfOpenProbeInFlight.set(false)
        }

        /** 仅针对传输、代理及 5xx 上游错误累计失败并在阈值后打开断路器。 */
        fun recordFailure(exception: Throwable) {
            halfOpenProbeInFlight.set(false)
            if (exception !is AiTransportException && exception !is AiProxyException && (exception !is AiUpstreamException || exception.statusCode < 500)) {
                return
            }
            if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
                openUntilEpochMillis.set(Instant.now().plusMillis(OPEN_DURATION_MILLIS).toEpochMilli())
            }
        }

        private companion object {
            const val FAILURE_THRESHOLD = 5
            const val OPEN_DURATION_MILLIS = 30_000L
        }
    }
}
