package com.dall06.karani.adapters.clients

import com.dall06.karani.domain.AttemptStatus
import com.dall06.karani.domain.Destination
import com.dall06.karani.domain.DestinationType
import com.dall06.karani.domain.WebhookEvent
import com.dall06.karani.domain.EventStatus
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpEventPublisherTest {

    @Test
    fun testHttpPublishFlows() = runBlocking {
        data class TestCase(
            val name: String,
            val mockResponseStatus: HttpStatusCode,
            val mockResponseHeaders: Headers = headersOf(),
            val mockResponseBody: String = "",
            val triggerNetworkException: Boolean = false,
            val destinationSettings: Map<String, String>,
            val expectedStatus: AttemptStatus,
            val expectedStatusCode: Int?,
            val expectedErrorMessageContains: String?
        )

        val event = WebhookEvent(
            id = "evt-1",
            endpointId = "ep-1",
            rawPayload = "{\"test\":true}",
            headers = emptyMap(),
            contentType = "application/json",
            status = EventStatus.PENDING,
            receivedAt = Instant.now()
        )

        val testCases = listOf(
            TestCase(
                name = "Successful Dispatch 200 OK",
                mockResponseStatus = HttpStatusCode.OK,
                destinationSettings = mapOf("url" to "https://api.test/webhook"),
                expectedStatus = AttemptStatus.SUCCESS,
                expectedStatusCode = 200,
                expectedErrorMessageContains = null
            ),
            TestCase(
                name = "Failed Dispatch 500 Internal Error",
                mockResponseStatus = HttpStatusCode.InternalServerError,
                destinationSettings = mapOf("url" to "https://api.test/webhook"),
                expectedStatus = AttemptStatus.FAILED,
                expectedStatusCode = 500,
                expectedErrorMessageContains = null
            ),
            TestCase(
                name = "Missing URL Setting",
                mockResponseStatus = HttpStatusCode.OK,
                destinationSettings = emptyMap(),
                expectedStatus = AttemptStatus.FAILED,
                expectedStatusCode = null,
                expectedErrorMessageContains = "Missing target URL"
            ),
            TestCase(
                name = "Network Connection Timeout/Exception",
                mockResponseStatus = HttpStatusCode.OK,
                triggerNetworkException = true,
                destinationSettings = mapOf("url" to "https://api.test/webhook"),
                expectedStatus = AttemptStatus.FAILED,
                expectedStatusCode = null,
                expectedErrorMessageContains = "Connection refused"
            )
        )

        for (tc in testCases) {
            val mockEngine = MockEngine { _ ->
                if (tc.triggerNetworkException) {
                    throw java.net.ConnectException("Connection refused")
                }
                respond(
                    content = tc.mockResponseBody,
                    status = tc.mockResponseStatus,
                    headers = tc.mockResponseHeaders
                )
            }
            val client = HttpClient(mockEngine)
            val publisher = HttpEventPublisher(client)

            val destination = Destination(
                id = "d-1",
                endpointId = "ep-1",
                name = "Mock Target",
                type = DestinationType.HTTP,
                enabled = true,
                settings = tc.destinationSettings
            )

            val result = publisher.publish(event, destination)

            assertEquals(tc.expectedStatus, result.status, "[${tc.name}] Status mismatch")
            assertEquals(tc.expectedStatusCode, result.responseStatusCode, "[${tc.name}] Response code mismatch")
            
            if (tc.expectedErrorMessageContains != null) {
                assertNotNull(result.errorMessage, "[${tc.name}] Expected error message")
                assertTrue(
                    result.errorMessage!!.contains(tc.expectedErrorMessageContains),
                    "[${tc.name}] Error message should contain: ${tc.expectedErrorMessageContains}"
                )
            }
            
            if (tc.expectedErrorMessageContains == null) {
                assertNull(result.errorMessage, "[${tc.name}] Did not expect error message")
            }
        }
    }
}
