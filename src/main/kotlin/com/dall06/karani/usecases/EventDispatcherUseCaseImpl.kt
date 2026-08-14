package com.dall06.karani.usecases

import com.dall06.karani.domain.*
import com.dall06.karani.ports.api.EventDispatcherUseCase
import com.dall06.karani.ports.spi.ConfigurationRepository
import com.dall06.karani.ports.spi.EventPublisher
import com.dall06.karani.ports.spi.EventRepository
import com.dall06.karani.adapters.evaluators.BodyEvaluatorTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.UUID

class EventDispatcherUseCaseImpl(
    private val configRepository: ConfigurationRepository,
    private val eventRepository: EventRepository,
    private val publishers: Map<DestinationType, EventPublisher>
) : EventDispatcherUseCase {

    override suspend fun dispatch(event: WebhookEvent) {
        val config = configRepository.getEndpointConfigById(event.endpointId) ?: return
        val persist = config.persistEvents

        val destinations = configRepository.getDestinationsForEndpoint(event.endpointId)
            .filter { it.enabled }
            .filter { destination ->
                val cond = destination.routingCondition
                if (cond == null) return@filter true
                val extracted = BodyEvaluatorTool.evaluate(event.rawPayload, event.contentType, cond)
                extracted != null && extracted.lowercase() != "false"
            }

        if (persist) {
            eventRepository.updateEventStatus(event.id, EventStatus.PROCESSING)
        }

        val attempts = coroutineScope {
            destinations.map { destination ->
                async {
                    val publisher = publishers[destination.type]
                    val maxRetries = destination.settings["retryCount"]?.toIntOrNull() ?: 0
                    val initialDelayMs = destination.settings["retryIntervalMs"]?.toLongOrNull() ?: 1000L
                    val multiplier = destination.settings["retryBackoffMultiplier"]?.toDoubleOrNull() ?: 2.0

                    var currentDelayMs = initialDelayMs
                    var attemptNumber = 1
                    var success = false
                    var finalAttempt = DispatchAttempt(
                        id = UUID.randomUUID().toString(),
                        eventId = event.id,
                        destinationId = destination.id,
                        status = AttemptStatus.FAILED,
                        attemptNumber = 1,
                        responseStatusCode = null,
                        errorMessage = "No publisher registered for type: ${destination.type}",
                        executedAt = Instant.now()
                    )

                    if (publisher == null) {
                        if (persist) {
                            eventRepository.saveAttempt(finalAttempt)
                        }
                        success = true
                    }

                    while (attemptNumber <= (maxRetries + 1) && !success) {
                        var attempt = finalAttempt.copy(attemptNumber = attemptNumber)
                        try {
                            var eventToPublish = event
                            val template = destination.transformationTemplate
                            if (template != null) {
                                val transformedPayload = transformPayload(event.rawPayload, event.contentType, template)
                                eventToPublish = event.copy(rawPayload = transformedPayload)
                            }
                            attempt = publisher!!.publish(eventToPublish, destination).copy(attemptNumber = attemptNumber)
                        } catch (e: Exception) {
                            attempt = DispatchAttempt(
                                id = UUID.randomUUID().toString(),
                                eventId = event.id,
                                destinationId = destination.id,
                                status = AttemptStatus.FAILED,
                                attemptNumber = attemptNumber,
                                responseStatusCode = null,
                                errorMessage = e.message ?: "Execution failed inside publisher",
                                executedAt = java.time.Instant.now()
                            )
                        }

                        if (persist) {
                            eventRepository.saveAttempt(attempt)
                        }
                        finalAttempt = attempt

                        if (attempt.status == AttemptStatus.SUCCESS) {
                            success = true
                        }

                        if (!success && attemptNumber <= maxRetries) {
                            kotlinx.coroutines.delay(currentDelayMs)
                            currentDelayMs = (currentDelayMs * multiplier).toLong()
                        }
                        attemptNumber++
                    }

                    finalAttempt
                }
            }.awaitAll()
        }

        if (persist) {
            val hasFailed = attempts.any { it.status == AttemptStatus.FAILED }
            val hasSuccess = attempts.any { it.status == AttemptStatus.SUCCESS }

            var finalStatus = EventStatus.FAILED
            if (attempts.isEmpty()) {
                finalStatus = EventStatus.IGNORED
            }
            if (hasSuccess && !hasFailed) {
                finalStatus = EventStatus.SUCCESS
            }

            eventRepository.updateEventStatus(event.id, finalStatus)
        }
    }

    private fun transformPayload(rawPayload: String, contentType: String?, template: String): String {
        var result = template
        val regex = "\\$\\{([^}]+)}".toRegex()
        val matches = regex.findAll(template)
        matches.forEach { match ->
            val expression = match.groups[1]?.value ?: ""
            val evaluated = BodyEvaluatorTool.evaluate(rawPayload, contentType, expression) ?: ""
            result = result.replace(match.value, evaluated)
        }
        return result
    }
}
