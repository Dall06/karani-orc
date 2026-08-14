package com.dall06.karani.adapters.http

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationRoutingTest {

    @Test
    fun testDynamicConfigRegisterAndWebhookExecutionFlow() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-config-routing.db",
                "karani.database.events-url" to "jdbc:sqlite:test-config-routing.db"
            )
        }
        application {
            configureRouting()
        }

        val uniquePath = "dynamic/stripe-${java.util.UUID.randomUUID()}"
        val endpointJson = """
            {
              "name": "Dynamic Stripe",
              "path": "$uniquePath",
              "enabled": true,
              "persistEvents": true,
              "defaultAction": "DENY"
            }
        """.trimIndent()

        val endpointRes = client.post("/api/v1/config/endpoints") {
            setBody(endpointJson)
        }
        assertEquals(HttpStatusCode.Created, endpointRes.status)
        
        val endpointResBody = endpointRes.bodyAsText()
        assertTrue(endpointResBody.contains("created"))
        
        val endpointId = endpointResBody.split("\"id\":\"")[1].split("\"")[0]

        val ruleJson = """
            {
              "priority": 1,
              "source": "HEADER",
              "expression": "query:challenge",
              "operator": "EXISTS",
              "action": "RESPOND",
              "customResponse": {
                "statusCode": 200,
                "headers": {
                  "Content-Type": "text/plain"
                },
                "bodyTemplate": "${'$'}{value}"
              }
            }
        """.trimIndent()

        val ruleRes = client.post("/api/v1/config/endpoints/$endpointId/rules") {
            setBody(ruleJson)
        }
        assertEquals(HttpStatusCode.Created, ruleRes.status)

        val destJson = """
            {
              "name": "Stripe Forwarder",
              "type": "HTTP",
              "enabled": true,
              "settings": {
                "url": "http://localhost:8080/callback"
              },
              "routingCondition": "$.event == \"payment.success\"",
              "transformationTemplate": "{\"tx\": \"${'$'}{$.id}\"}"
            }
        """.trimIndent()

        val destRes = client.post("/api/v1/config/endpoints/$endpointId/destinations") {
            setBody(destJson)
        }
        assertEquals(HttpStatusCode.Created, destRes.status)

        val handshakeRes = client.get("/webhook/$uniquePath?challenge=st-challenge-999")
        assertEquals(HttpStatusCode.OK, handshakeRes.status)
        assertEquals("st-challenge-999", handshakeRes.bodyAsText())

        val normalRes = client.post("/webhook/$uniquePath") {
            setBody("{\"event\":\"some.other.event\"}")
            header("Content-Type", "application/json")
        }
        assertEquals(HttpStatusCode.Accepted, normalRes.status)
        assertTrue(normalRes.bodyAsText().contains("ignored"))
    }

    @Test
    fun testApiKeyAuthentication() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.api.api-key" to "secure-token-123",
                "karani.database.config-url" to "jdbc:sqlite:test-auth.db",
                "karani.database.events-url" to "jdbc:sqlite:test-auth.db"
            )
        }
        application {
            configureRouting()
        }

        val resNoKey = client.get("/api/v1/events")
        assertEquals(HttpStatusCode.Unauthorized, resNoKey.status)

        val resWrongKey = client.get("/api/v1/events") {
            header("X-API-Key", "wrong-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, resWrongKey.status)

        val resCorrectKey = client.get("/api/v1/events") {
            header("X-API-Key", "secure-token-123")
        }
        assertEquals(HttpStatusCode.OK, resCorrectKey.status)
    }

    @Test
    fun testEndpointCrudAndEventAuditDetailFlow() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-crud-audit.db",
                "karani.database.events-url" to "jdbc:sqlite:test-crud-audit.db"
            )
        }
        application {
            configureRouting()
        }

        val createJson = """
            {
              "name": "To Be Updated",
              "path": "temp/path",
              "enabled": true,
              "persistEvents": true,
              "defaultAction": "ALLOW"
            }
        """.trimIndent()

        val createRes = client.post("/api/v1/config/endpoints") {
            setBody(createJson)
        }
        assertEquals(HttpStatusCode.Created, createRes.status)
        val id = createRes.bodyAsText().split("\"id\":\"")[1].split("\"")[0]

        val updateJson = """
            {
              "name": "Updated Endpoint",
              "path": "new/path",
              "enabled": false,
              "persistEvents": false,
              "defaultAction": "DENY",
              "securityType": "NONE"
            }
        """.trimIndent()

        val updateRes = client.put("/api/v1/config/endpoints/$id") {
            setBody(updateJson)
        }
        assertEquals(HttpStatusCode.OK, updateRes.status)

        val deleteRes = client.delete("/api/v1/config/endpoints/$id")
        assertEquals(HttpStatusCode.OK, deleteRes.status)
    }

    @Test
    fun testRateLimiterAndBodySizeLimits() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-limits.db",
                "karani.database.events-url" to "jdbc:sqlite:test-limits.db"
            )
        }
        application {
            configureRouting()
        }

        val uniquePath = "dynamic/limits-${java.util.UUID.randomUUID()}"
        val endpointJson = """
            {
              "name": "Limited Stripe",
              "path": "$uniquePath",
              "enabled": true,
              "persistEvents": true,
              "defaultAction": "ALLOW",
              "rateLimitRpm": 3,
              "maxBodySizeBytes": 15
            }
        """.trimIndent()

        val endpointRes = client.post("/api/v1/config/endpoints") {
            setBody(endpointJson)
        }
        assertEquals(HttpStatusCode.Created, endpointRes.status)

        val largeRes = client.post("/webhook/$uniquePath") {
            setBody("this body is 20 bytes long")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, largeRes.status)

        val smallRes1 = client.post("/webhook/$uniquePath") {
            setBody("small body")
        }
        assertEquals(HttpStatusCode.Accepted, smallRes1.status)

        val smallRes2 = client.post("/webhook/$uniquePath") {
            setBody("small body")
        }
        assertEquals(HttpStatusCode.Accepted, smallRes2.status)

        val smallRes3 = client.post("/webhook/$uniquePath") {
            setBody("small body")
        }
        assertEquals(HttpStatusCode.TooManyRequests, smallRes3.status)
    }
}
