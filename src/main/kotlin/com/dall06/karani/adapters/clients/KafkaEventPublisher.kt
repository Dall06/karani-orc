package com.dall06.karani.adapters.clients

import com.dall06.karani.domain.AttemptStatus
import com.dall06.karani.domain.Destination
import com.dall06.karani.domain.DispatchAttempt
import com.dall06.karani.domain.WebhookEvent
import com.dall06.karani.ports.spi.EventPublisher
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class KafkaEventPublisher(private val producer: Producer<String, String>) : EventPublisher {

    override suspend fun publish(event: WebhookEvent, destination: Destination): DispatchAttempt {
        val topic = destination.settings["topic"]
        if (topic == null) {
            return DispatchAttempt(
                id = UUID.randomUUID().toString(),
                eventId = event.id,
                destinationId = destination.id,
                status = AttemptStatus.FAILED,
                attemptNumber = 1,
                responseStatusCode = null,
                errorMessage = "Missing target 'topic' in destination settings",
                executedAt = Instant.now()
            )
        }

        val key = destination.settings["key"] ?: event.id

        return suspendCancellableCoroutine { continuation ->
            try {
                val record = ProducerRecord(topic, key, event.rawPayload)
                producer.send(record) { _, exception ->
                    if (continuation.isActive) {
                        var attempt = DispatchAttempt(
                            id = UUID.randomUUID().toString(),
                            eventId = event.id,
                            destinationId = destination.id,
                            status = AttemptStatus.SUCCESS,
                            attemptNumber = 1,
                            responseStatusCode = null,
                            errorMessage = null,
                            executedAt = Instant.now()
                        )
                        if (exception != null) {
                            attempt = attempt.copy(
                                status = AttemptStatus.FAILED,
                                errorMessage = exception.message ?: exception.javaClass.simpleName
                            )
                        }
                        continuation.resume(attempt)
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(
                        DispatchAttempt(
                            id = UUID.randomUUID().toString(),
                            eventId = event.id,
                            destinationId = destination.id,
                            status = AttemptStatus.FAILED,
                            attemptNumber = 1,
                            responseStatusCode = null,
                            errorMessage = e.message ?: e.javaClass.simpleName,
                            executedAt = Instant.now()
                        )
                    )
                }
            }
        }
    }
}
