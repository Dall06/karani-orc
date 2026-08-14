package com.dall06.karani.usecases

import com.dall06.karani.domain.*
import com.dall06.karani.ports.spi.ConfigurationRepository
import com.dall06.karani.ports.spi.EventPublisher
import com.dall06.karani.ports.spi.EventRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class EventDispatcherUseCaseTest {

    private class FakeConfigRepository : ConfigurationRepository {
        var destinations = mutableListOf<Destination>()
        val endpoints = mutableListOf<EndpointConfiguration>()

        override suspend fun getEndpointConfigByPath(path: String): EndpointConfiguration? = null
        override suspend fun getEndpointConfigById(id: String): EndpointConfiguration? = endpoints.find { it.id == id }
        override suspend fun getIngressRulesForEndpoint(endpointId: String): List<IngressRule> = emptyList()
        override suspend fun getDestinationsForEndpoint(endpointId: String): List<Destination> = destinations
        override suspend fun saveEndpointConfig(config: EndpointConfiguration): EndpointConfiguration = config
        override suspend fun saveIngressRule(rule: IngressRule): IngressRule = rule
        override suspend fun saveDestination(destination: Destination): Destination = destination
        override suspend fun deleteEndpointConfig(id: String): Boolean = true
        override suspend fun updateEndpointConfig(config: EndpointConfiguration): EndpointConfiguration = config
    }

    private class FakeEventRepository : EventRepository {
        val savedEvents = mutableListOf<WebhookEvent>()
        val savedAttempts = mutableListOf<DispatchAttempt>()
        val statuses = mutableMapOf<String, EventStatus>()

        override suspend fun saveEvent(event: WebhookEvent): WebhookEvent {
            savedEvents.add(event)
            statuses[event.id] = event.status
            return event
        }

        override suspend fun getEventById(eventId: String): WebhookEvent? {
            return savedEvents.find { it.id == eventId }
        }

        override suspend fun getEvents(limit: Int): List<WebhookEvent> {
            return savedEvents.take(limit)
        }

        override suspend fun updateEventStatus(eventId: String, status: EventStatus) {
            statuses[eventId] = status
        }

        override suspend fun getAttemptsForEvent(eventId: String): List<DispatchAttempt> {
            return savedAttempts.filter { it.eventId == eventId }
        }

        override suspend fun saveAttempt(attempt: DispatchAttempt): DispatchAttempt {
            savedAttempts.add(attempt)
            return attempt
        }
    }

    private class FakeEventPublisher(
        private val shouldSucceed: Boolean,
        private val delayMs: Long = 0
    ) : EventPublisher {
        override suspend fun publish(event: WebhookEvent, destination: Destination): DispatchAttempt {
            if (delayMs > 0) {
                delay(delayMs)
            }
            return if (shouldSucceed) {
                DispatchAttempt(
                    id = UUID.randomUUID().toString(),
                    eventId = event.id,
                    destinationId = destination.id,
                    status = AttemptStatus.SUCCESS,
                    attemptNumber = 1,
                    responseStatusCode = 200,
                    errorMessage = null,
                    executedAt = Instant.now()
                )
            } else {
                DispatchAttempt(
                    id = UUID.randomUUID().toString(),
                    eventId = event.id,
                    destinationId = destination.id,
                    status = AttemptStatus.FAILED,
                    attemptNumber = 1,
                    responseStatusCode = 500,
                    errorMessage = "Server error",
                    executedAt = Instant.now()
                )
            }
        }
    }

    @Test
    fun testEventDispatchFanOut() = runBlocking {
        data class TestCase(
            val name: String,
            val destinations: List<Destination>,
            val publishers: Map<DestinationType, EventPublisher>,
            val expectedEventStatus: EventStatus,
            val expectedAttemptsCount: Int,
            val expectedSuccessCount: Int
        )

        val endpointId = "ep-1"
        val event = WebhookEvent(
            id = "evt-123",
            endpointId = endpointId,
            rawPayload = "{}",
            headers = emptyMap(),
            contentType = "application/json",
            status = EventStatus.PENDING,
            receivedAt = Instant.now()
        )

        val testCases = listOf(
            TestCase(
                name = "Single Destination Success",
                destinations = listOf(
                    Destination("d-1", endpointId, "HTTP Target", DestinationType.HTTP, true, emptyMap())
                ),
                publishers = mapOf(DestinationType.HTTP to FakeEventPublisher(true)),
                expectedEventStatus = EventStatus.SUCCESS,
                expectedAttemptsCount = 1,
                expectedSuccessCount = 1
            ),
            TestCase(
                name = "Single Destination Failure",
                destinations = listOf(
                    Destination("d-1", endpointId, "HTTP Target", DestinationType.HTTP, true, emptyMap())
                ),
                publishers = mapOf(DestinationType.HTTP to FakeEventPublisher(false)),
                expectedEventStatus = EventStatus.FAILED,
                expectedAttemptsCount = 1,
                expectedSuccessCount = 0
            ),
            TestCase(
                name = "Multiple Destinations - Mixed Success & Failure",
                destinations = listOf(
                    Destination("d-1", endpointId, "HTTP Target", DestinationType.HTTP, true, emptyMap()),
                    Destination("d-2", endpointId, "Kafka Target", DestinationType.KAFKA, true, emptyMap())
                ),
                publishers = mapOf(
                    DestinationType.HTTP to FakeEventPublisher(true),
                    DestinationType.KAFKA to FakeEventPublisher(false)
                ),
                expectedEventStatus = EventStatus.FAILED,
                expectedAttemptsCount = 2,
                expectedSuccessCount = 1
            ),
            TestCase(
                name = "Disabled Destination Ignored",
                destinations = listOf(
                    Destination("d-1", endpointId, "HTTP Target 1", DestinationType.HTTP, true, emptyMap()),
                    Destination("d-2", endpointId, "HTTP Target 2", DestinationType.HTTP, false, emptyMap())
                ),
                publishers = mapOf(DestinationType.HTTP to FakeEventPublisher(true)),
                expectedEventStatus = EventStatus.SUCCESS,
                expectedAttemptsCount = 1,
                expectedSuccessCount = 1
            ),
            TestCase(
                name = "No Registered Publisher - Fails attempt",
                destinations = listOf(
                    Destination("d-1", endpointId, "GRPC Target", DestinationType.GRPC, true, emptyMap())
                ),
                publishers = emptyMap(),
                expectedEventStatus = EventStatus.FAILED,
                expectedAttemptsCount = 1,
                expectedSuccessCount = 0
            )
        )

        for (tc in testCases) {
            val configRepo = FakeConfigRepository()
            configRepo.destinations.addAll(tc.destinations)
            configRepo.endpoints.add(EndpointConfiguration(endpointId, "Test", "test/path", null, true, Instant.now(), true, DefaultAction.ALLOW, SecurityType.NONE))

            val eventRepo = FakeEventRepository()
            eventRepo.saveEvent(event)

            val dispatcher = EventDispatcherUseCaseImpl(configRepo, eventRepo, tc.publishers)
            dispatcher.dispatch(event)

            assertEquals(tc.expectedEventStatus, eventRepo.statuses[event.id], "[${tc.name}] Final status mismatch")
            assertEquals(tc.expectedAttemptsCount, eventRepo.savedAttempts.size, "[${tc.name}] Attempts count mismatch")
            val successCount = eventRepo.savedAttempts.count { it.status == AttemptStatus.SUCCESS }
            assertEquals(tc.expectedSuccessCount, successCount, "[${tc.name}] Success attempts count mismatch")
        }
    }

    @Test
    fun testRetryBackoffPolicy() = runBlocking {
        val endpointId = "endpoint-retry"
        val event = WebhookEvent("evt-123", endpointId, "{}", emptyMap(), "application/json", EventStatus.PENDING, Instant.now())

        val configRepo = FakeConfigRepository()
        configRepo.destinations.add(
            Destination(
                id = "dest-retry",
                endpointId = endpointId,
                name = "Failing Dest",
                type = DestinationType.HTTP,
                enabled = true,
                settings = mapOf(
                    "retryCount" to "2",
                    "retryIntervalMs" to "5",
                    "retryBackoffMultiplier" to "1.5"
                )
            )
        )
        configRepo.endpoints.add(
            EndpointConfiguration(
                endpointId, "Retry Test", "retry/path", null, true, Instant.now(), true, DefaultAction.ALLOW, SecurityType.NONE
            )
        )

        val eventRepo = FakeEventRepository()
        eventRepo.saveEvent(event)

        val failingPublisher = object : com.dall06.karani.ports.spi.EventPublisher {
            override suspend fun publish(event: WebhookEvent, destination: Destination): DispatchAttempt {
                throw RuntimeException("Connection timed out")
            }
        }

        val dispatcher = EventDispatcherUseCaseImpl(configRepo, eventRepo, mapOf(DestinationType.HTTP to failingPublisher))
        dispatcher.dispatch(event)

        assertEquals(3, eventRepo.savedAttempts.size)
        assertEquals(AttemptStatus.FAILED, eventRepo.savedAttempts[0].status)
        assertEquals(1, eventRepo.savedAttempts[0].attemptNumber)
        assertEquals(AttemptStatus.FAILED, eventRepo.savedAttempts[1].status)
        assertEquals(2, eventRepo.savedAttempts[1].attemptNumber)
        assertEquals(AttemptStatus.FAILED, eventRepo.savedAttempts[2].status)
        assertEquals(3, eventRepo.savedAttempts[2].attemptNumber)
        assertEquals(EventStatus.FAILED, eventRepo.statuses[event.id])
    }
}
