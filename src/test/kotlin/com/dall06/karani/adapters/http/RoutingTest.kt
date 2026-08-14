package com.dall06.karani.adapters.http

import com.dall06.karani.adapters.database.sql.*
import com.dall06.karani.domain.Operator
import com.dall06.karani.domain.RuleAction
import com.dall06.karani.domain.RuleSource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingTest {

    @BeforeTest
    fun setup() {
        java.io.File("test-routing.db").delete()
        Database.connect("jdbc:sqlite:test-routing.db", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                EndpointsTable,
                IngressRulesTable,
                DestinationsTable,
                WebhookEventsTable,
                DispatchAttemptsTable
            )
        }
    }

    @kotlin.test.AfterTest
    fun cleanup() {
        java.io.File("test-routing.db").delete()
    }

    @Test
    fun testRootEndpoint() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-routing.db",
                "karani.database.events-url" to "jdbc:sqlite:test-routing.db"
            )
        }
        application {
            configureRouting()
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Karani Webhook Orchestrator is running!", response.bodyAsText())
    }

    @Test
    fun testWebhookIngressFlow() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-routing.db",
                "karani.database.events-url" to "jdbc:sqlite:test-routing.db"
            )
        }
        application {
            configureRouting()
        }

        val targetEndpointId = UUID.randomUUID().toString()
        transaction {
            EndpointsTable.insert {
                it[id] = targetEndpointId
                it[name] = "Shopify Webhook"
                it[path] = "shopify/orders"
                it[secret] = null
                it[enabled] = true
                it[createdAt] = Instant.now()
            }

            IngressRulesTable.insert {
                it[id] = "rule-slack-handshake"
                it[endpointId] = targetEndpointId
                it[priority] = 1
                it[ruleSource] = RuleSource.BODY.name
                it[expression] = "$.challenge"
                it[operator] = Operator.EXISTS.name
                it[expectedValue] = null
                it[action] = RuleAction.RESPOND.name
                it[customResponseStatusCode] = 200
                it[customResponseBodyTemplate] = """{"challenge":"${'$'}{value}"}"""
            }
        }

        val acceptRes = client.post("/webhook/shopify/orders") {
            setBody("{\"event\":\"test\"}")
            header("Content-Type", "application/json")
        }
        assertEquals(HttpStatusCode.Accepted, acceptRes.status)
        assertTrue(acceptRes.bodyAsText().contains("accepted"))

        val handshakeRes = client.post("/webhook/shopify/orders") {
            setBody("{\"challenge\":\"handshake-token-123\"}")
            header("Content-Type", "application/json")
        }
        assertEquals(HttpStatusCode.OK, handshakeRes.status)
        assertEquals("{\"challenge\":\"handshake-token-123\"}", handshakeRes.bodyAsText())
    }

    @Test
    fun testGetEventsEndpoint() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-routing.db",
                "karani.database.events-url" to "jdbc:sqlite:test-routing.db",
                "karani.api.get-events.enabled" to "true",
                "karani.api.get-events.default-page-size" to "10"
            )
        }
        application {
            configureRouting()
        }

        val targetEndpointId = UUID.randomUUID().toString()
        transaction {
            EndpointsTable.insert {
                it[id] = targetEndpointId
                it[name] = "Shopify Webhook"
                it[path] = "shopify/orders"
                it[secret] = null
                it[enabled] = true
                it[createdAt] = Instant.now()
            }
        }

        client.post("/webhook/shopify/orders") {
            setBody("{\"event\":\"test\"}")
            header("Content-Type", "application/json")
        }

        val response = client.get("/api/v1/events?limit=5")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("PENDING"))
    }

    @Test
    fun testWebhookGetHandshakeFlow() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "karani.database.config-url" to "jdbc:sqlite:test-routing.db",
                "karani.database.events-url" to "jdbc:sqlite:test-routing.db"
            )
        }
        application {
            configureRouting()
        }

        val targetEndpointId = UUID.randomUUID().toString()
        transaction {
            EndpointsTable.insert {
                it[id] = targetEndpointId
                it[name] = "Facebook Webhook"
                it[path] = "facebook/callback"
                it[secret] = null
                it[enabled] = true
                it[createdAt] = Instant.now()
            }

            IngressRulesTable.insert {
                it[id] = "rule-facebook-handshake"
                it[endpointId] = targetEndpointId
                it[priority] = 1
                it[ruleSource] = RuleSource.HEADER.name
                it[expression] = "query:hub.challenge"
                it[operator] = Operator.EXISTS.name
                it[expectedValue] = null
                it[action] = RuleAction.RESPOND.name
                it[customResponseStatusCode] = 200
                it[customResponseBodyTemplate] = "${'$'}{value}"
            }
        }

        val getHandshakeRes = client.get("/webhook/facebook/callback?hub.challenge=facebook_challenge_token_456&hub.verify_token=my_secret_token")
        assertEquals(HttpStatusCode.OK, getHandshakeRes.status)
        assertEquals("facebook_challenge_token_456", getHandshakeRes.bodyAsText())
    }
}
