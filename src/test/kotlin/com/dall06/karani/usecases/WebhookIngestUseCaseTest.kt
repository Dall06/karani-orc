package com.dall06.karani.usecases

import com.dall06.karani.domain.*
import com.dall06.karani.ports.api.IngestResult
import com.dall06.karani.ports.spi.ConfigurationRepository
import com.dall06.karani.ports.spi.EventRepository
import com.dall06.karani.adapters.evaluators.JsonBodyEvaluator
import com.dall06.karani.adapters.evaluators.RegexBodyEvaluator
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookIngestUseCaseTest {


    private class FakeConfigRepository : ConfigurationRepository {
        var endpoints = mutableMapOf<String, EndpointConfiguration>()
        var rules = mutableMapOf<String, List<IngressRule>>()
        var destinations = mutableMapOf<String, List<Destination>>()

        override suspend fun getEndpointConfigByPath(path: String): EndpointConfiguration? {
            return endpoints.values.find { it.path == path }
        }

        override suspend fun getEndpointConfigById(id: String): EndpointConfiguration? {
            return endpoints[id]
        }

        override suspend fun getIngressRulesForEndpoint(endpointId: String): List<IngressRule> {
            return rules[endpointId] ?: emptyList()
        }

        override suspend fun getDestinationsForEndpoint(endpointId: String): List<Destination> {
            return destinations[endpointId] ?: emptyList()
        }

        override suspend fun saveEndpointConfig(config: EndpointConfiguration): EndpointConfiguration = config
        override suspend fun saveIngressRule(rule: IngressRule): IngressRule = rule
        override suspend fun saveDestination(destination: Destination): Destination = destination
        override suspend fun deleteEndpointConfig(id: String): Boolean = true
        override suspend fun updateEndpointConfig(config: EndpointConfiguration): EndpointConfiguration = config
    }

    private class FakeEventRepository : EventRepository {
        val savedEvents = mutableListOf<WebhookEvent>()

        override suspend fun saveEvent(event: WebhookEvent): WebhookEvent {
            savedEvents.add(event)
            return event
        }

        override suspend fun getEventById(eventId: String): WebhookEvent? {
            return savedEvents.find { it.id == eventId }
        }

        override suspend fun getEvents(limit: Int): List<WebhookEvent> {
            return savedEvents.take(limit)
        }

        override suspend fun updateEventStatus(eventId: String, status: EventStatus) {
            val idx = savedEvents.indexOfFirst { it.id == eventId }
            if (idx != -1) {
                savedEvents[idx] = savedEvents[idx].copy(status = status)
            }
        }

        override suspend fun saveAttempt(attempt: DispatchAttempt): DispatchAttempt {
            return attempt
        }

        override suspend fun getAttemptsForEvent(eventId: String): List<DispatchAttempt> = emptyList()
    }

    @Test
    fun testIngestRulesEngine() = runBlocking {
        data class TestCase(
            val name: String,
            val path: String,
            val headers: Map<String, String>,
            val body: String,
            val contentType: String?,
            val endpointConfig: EndpointConfiguration?,
            val rules: List<IngressRule>,
            val expectedResult: IngestResult,
            val expectedEventStatus: EventStatus?
        )

        val endpointId = "ep-1"
        val activeEndpoint = EndpointConfiguration(endpointId, "Stripe", "/stripe", null, true, Instant.now())
        val disabledEndpoint = EndpointConfiguration(endpointId, "Stripe", "/stripe", null, false, Instant.now())

        val testCases = listOf(
            TestCase(
                name = "Endpoint Not Found",
                path = "/unknown",
                headers = emptyMap(),
                body = "{}",
                contentType = "application/json",
                endpointConfig = null,
                rules = emptyList(),
                expectedResult = IngestResult.EndpointNotFound,
                expectedEventStatus = null
            ),
            TestCase(
                name = "Endpoint Disabled",
                path = "/stripe",
                headers = emptyMap(),
                body = "{}",
                contentType = "application/json",
                endpointConfig = disabledEndpoint,
                rules = emptyList(),
                expectedResult = IngestResult.Declined("Endpoint is disabled"),
                expectedEventStatus = null
            ),
            TestCase(
                name = "Default Accept (No Rules)",
                path = "/stripe",
                headers = emptyMap(),
                body = "{}",
                contentType = "application/json",
                endpointConfig = activeEndpoint,
                rules = emptyList(),
                expectedResult = IngestResult.Accepted("", WebhookEvent("", "", "", emptyMap(), null, EventStatus.PENDING, Instant.now())),
                expectedEventStatus = EventStatus.PENDING
            ),
            TestCase(
                name = "Decline Rule - Header condition matches",
                path = "/stripe",
                headers = mapOf("X-Env" to "sandbox"),
                body = "{}",
                contentType = "application/json",
                endpointConfig = activeEndpoint,
                rules = listOf(
                    IngressRule("rule-1", endpointId, 1, RuleSource.HEADER, "X-Env", Operator.EQUALS, "sandbox", RuleAction.DECLINE, null)
                ),
                expectedResult = IngestResult.Declined("Event rejected by rule ID: rule-1"),
                expectedEventStatus = EventStatus.IGNORED
            ),
            TestCase(
                name = "Decline Rule - Header condition does not match (passes to accept)",
                path = "/stripe",
                headers = mapOf("X-Env" to "production"),
                body = "{}",
                contentType = "application/json",
                endpointConfig = activeEndpoint,
                rules = listOf(
                    IngressRule("rule-1", endpointId, 1, RuleSource.HEADER, "X-Env", Operator.EQUALS, "sandbox", RuleAction.DECLINE, null)
                ),
                expectedResult = IngestResult.Accepted("", WebhookEvent("", "", "", emptyMap(), null, EventStatus.PENDING, Instant.now())),
                expectedEventStatus = EventStatus.PENDING
            ),
            TestCase(
                name = "Respond Rule - Challenge Handshake JSON",
                path = "/stripe",
                headers = emptyMap(),
                body = """{"type": "url_verification", "challenge": "slack-token-123"}""",
                contentType = "application/json",
                endpointConfig = activeEndpoint,
                rules = listOf(
                    IngressRule(
                        id = "rule-slack",
                        endpointId = endpointId,
                        priority = 1,
                        source = RuleSource.BODY,
                        expression = "$.challenge",
                        operator = Operator.EXISTS,
                        expectedValue = null,
                        action = RuleAction.RESPOND,
                        customResponse = CustomResponse(
                            statusCode = 200,
                            headers = mapOf("Content-Type" to "application/json"),
                            bodyTemplate = """{"challenge": "${'$'}{value}"}"""
                        )
                    )
                ),
                expectedResult = IngestResult.CustomResponded(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """{"challenge": "slack-token-123"}"""
                ),
                expectedEventStatus = EventStatus.IGNORED
            ),
            TestCase(
                name = "Rule Order Priority - First rule matching wins",
                path = "/stripe",
                headers = mapOf("X-Test" to "yes"),
                body = "{}",
                contentType = "application/json",
                endpointConfig = activeEndpoint,
                rules = listOf(
                    IngressRule("rule-first", endpointId, 1, RuleSource.HEADER, "X-Test", Operator.EQUALS, "yes", RuleAction.ACCEPT, null),
                    IngressRule("rule-second", endpointId, 2, RuleSource.HEADER, "X-Test", Operator.EQUALS, "yes", RuleAction.DECLINE, null)
                ),
                expectedResult = IngestResult.Accepted("", WebhookEvent("", "", "", emptyMap(), null, EventStatus.PENDING, Instant.now())),
                expectedEventStatus = EventStatus.PENDING
            )
        )

        for (tc in testCases) {
            val configRepo = FakeConfigRepository()
            val eventRepo = FakeEventRepository()

            if (tc.endpointConfig != null) {
                configRepo.endpoints[tc.endpointConfig.id] = tc.endpointConfig
                configRepo.rules[tc.endpointConfig.id] = tc.rules
            }

            val useCase = WebhookIngestUseCaseImpl(configRepo, eventRepo)
            val result = useCase.ingest(tc.path, tc.headers, tc.body, tc.contentType)

            when (val expected = tc.expectedResult) {
                is IngestResult.Accepted -> {
                    assertTrue(result is IngestResult.Accepted, "[${tc.name}] Expected IngestResult.Accepted")
                }
                is IngestResult.Declined -> {
                    assertTrue(result is IngestResult.Declined, "[${tc.name}] Expected IngestResult.Declined")
                    assertEquals(expected.reason, (result as IngestResult.Declined).reason, "[${tc.name}] Reason mismatch")
                }
                is IngestResult.CustomResponded -> {
                    assertTrue(result is IngestResult.CustomResponded, "[${tc.name}] Expected IngestResult.CustomResponded")
                    val actualRes = result as IngestResult.CustomResponded
                    assertEquals(expected.statusCode, actualRes.statusCode, "[${tc.name}] Status code mismatch")
                    assertEquals(expected.headers, actualRes.headers, "[${tc.name}] Headers mismatch")
                    assertEquals(expected.body, actualRes.body, "[${tc.name}] Body mismatch")
                }
                else -> {
                    assertEquals(expected, result, "[${tc.name}] IngestResult mismatch")
                }
            }

            if (tc.expectedEventStatus != null) {
                assertEquals(1, eventRepo.savedEvents.size, "[${tc.name}] Should have saved exactly 1 event")
                assertEquals(tc.expectedEventStatus, eventRepo.savedEvents.first().status, "[${tc.name}] Saved event status mismatch")
            } else {
                assertEquals(0, eventRepo.savedEvents.size, "[${tc.name}] Should not have saved any events")
            }
        }
    }
}
