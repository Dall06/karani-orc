package com.dall06.karani.adapters.clients

import com.dall06.karani.adapters.http.configureRouting
import com.dall06.karani.domain.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSocketEventPublisherTest {

    @Test
    fun testWebSocketBroadcastingFlow() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-routing.db",
                "karani.database.events-url" to "jdbc:sqlite:test-routing.db",
                "karani.outputs.websocket.path" to "/ws/events"
            )
        }
        application {
            configureRouting()
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        client.webSocket("/ws/events?clientId=test-client-1") {
            kotlinx.coroutines.delay(200)
            val event = WebhookEvent(
                id = "evt-1",
                endpointId = "ep-1",
                rawPayload = "{\"hello\":\"websocket\"}",
                headers = emptyMap(),
                contentType = "application/json",
                status = EventStatus.PENDING,
                receivedAt = Instant.now()
            )

            val destination = Destination(
                id = "d-1",
                endpointId = "ep-1",
                name = "WS Broadcast",
                type = DestinationType.WEBSOCKET,
                enabled = true,
                settings = emptyMap()
            )

            val publisher = WebSocketEventPublisher()
            val attempt = publisher.publish(event, destination)

            assertEquals(AttemptStatus.SUCCESS, attempt.status)

            val frame = incoming.receive() as Frame.Text
            assertEquals("{\"hello\":\"websocket\"}", frame.readText())
        }
    }
}
