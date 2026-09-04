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

    private fun limitFor(profile: AiConnectionProfile, operation: AiOperation): Int = when (operation) {
        AiOperation.MODEL_LIST -> profile.maxInFlightModelListRequests
        AiOperation.CHAT -> profile.maxInFlightChatRequests
        AiOperation.IMAGE -> profile.maxInFlightImageRequests
        AiOperation.VIDEO -> profile.maxInFlightImageRequests
    }

    private fun connectionKey(connection: AiProviderConnection): String =
        "${connection.providerConfigId ?: connection.baseUri}|${connection.profile.name}"

    private data class GateKey(val connection: String, val operation: AiOperation)

    private class CircuitState {
        private val consecutiveFailures = AtomicInteger()
        private val openUntilEpochMillis = AtomicLong(0)
        private val halfOpenProbeInFlight = AtomicBoolean(false)

        fun tryAcquire(): Boolean {
            val openUntil = openUntilEpochMillis.get()
            if (openUntil == 0L) return true
            if (Instant.now().toEpochMilli() < openUntil) return false
            return halfOpenProbeInFlight.compareAndSet(false, true)
        }

        fun recordSuccess() {
            consecutiveFailures.set(0)
            openUntilEpochMillis.set(0)
            halfOpenProbeInFlight.set(false)
        }

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
