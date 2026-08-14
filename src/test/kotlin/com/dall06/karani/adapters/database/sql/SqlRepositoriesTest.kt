package com.dall06.karani.adapters.database.sql

import com.dall06.karani.domain.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqlRepositoriesTest {

    private lateinit var db: Database
    private lateinit var configRepo: SqlConfigurationRepository
    private lateinit var eventRepo: SqlEventRepository

    @BeforeTest
    fun setup() {
        db = Database.connect("jdbc:sqlite:test.db", driver = "org.sqlite.JDBC")
        configRepo = SqlConfigurationRepository(db)
        eventRepo = SqlEventRepository(db)
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
        java.io.File("test.db").delete()
    }

    @Test
    fun testEndpointAndRulesPersistence() = runBlocking {
        val targetEndpointId = UUID.randomUUID().toString()
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        transaction {
            EndpointsTable.insert {
                it[id] = targetEndpointId
                it[name] = "Test API"
                it[path] = "/test-api"
                it[secret] = "super-secret"
                it[enabled] = true
                it[createdAt] = now
            }

            IngressRulesTable.insert {
                it[id] = "rule-1"
                it[endpointId] = targetEndpointId
                it[priority] = 10
                it[ruleSource] = RuleSource.HEADER.name
                it[expression] = "X-Test"
                it[operator] = Operator.EQUALS.name
                it[expectedValue] = "hello"
                it[action] = RuleAction.ACCEPT.name
            }
        }

        val fetchedConfig = configRepo.getEndpointConfigByPath("/test-api")
        assertNotNull(fetchedConfig)
        assertEquals("Test API", fetchedConfig.name)
        assertEquals("super-secret", fetchedConfig.secret)
        assertEquals(now, fetchedConfig.createdAt)

        val fetchedRules = configRepo.getIngressRulesForEndpoint(targetEndpointId)
        assertEquals(1, fetchedRules.size)
        val rule = fetchedRules.first()
        assertEquals(10, rule.priority)
        assertEquals(RuleSource.HEADER, rule.source)
        assertEquals("X-Test", rule.expression)
        assertEquals(Operator.EQUALS, rule.operator)
        assertEquals("hello", rule.expectedValue)
        assertEquals(RuleAction.ACCEPT, rule.action)
    }

    @Test
    fun testEventAndAttemptPersistence() = runBlocking {
        val endpointId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        transaction {
            EndpointsTable.insert {
                it[id] = endpointId
                it[name] = "Test API"
                it[path] = "/test-api"
                it[secret] = null
                it[enabled] = true
                it[createdAt] = now
            }
        }

        val event = WebhookEvent(
            id = eventId,
            endpointId = endpointId,
            rawPayload = """{"foo": "bar"}""",
            headers = mapOf("Content-Type" to "application/json", "X-Key" to "val"),
            contentType = "application/json",
            status = EventStatus.PENDING,
            receivedAt = now
        )

        eventRepo.saveEvent(event)

        val savedStatus = transaction {
            WebhookEventsTable.selectAll().where { WebhookEventsTable.id eq eventId }
                .map { row ->
                    assertEquals(eventId, row[WebhookEventsTable.id])
                    assertEquals("""{"foo": "bar"}""", row[WebhookEventsTable.rawPayload])
                    val headers = JsonHelper.deserialize(row[WebhookEventsTable.headersJson])
                    assertEquals("application/json", headers["Content-Type"])
                    assertEquals("val", headers["X-Key"])
                    row[WebhookEventsTable.status]
                }.firstOrNull()
        }
        assertEquals(EventStatus.PENDING.name, savedStatus)

        eventRepo.updateEventStatus(eventId, EventStatus.SUCCESS)
        val updatedStatus = transaction {
            WebhookEventsTable.selectAll().where { WebhookEventsTable.id eq eventId }
                .map { row -> row[WebhookEventsTable.status] }.firstOrNull()
        }
        assertEquals(EventStatus.SUCCESS.name, updatedStatus)
    }
}
