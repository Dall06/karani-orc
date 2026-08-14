package com.dall06.karani.adapters.database.sql

import com.dall06.karani.domain.*
import com.dall06.karani.ports.spi.ConfigurationRepository
import com.dall06.karani.ports.spi.EventRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class SqlConfigurationRepository(private val database: Database) : ConfigurationRepository {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    override suspend fun getEndpointConfigByPath(path: String): EndpointConfiguration? = dbQuery {
        EndpointsTable.selectAll().where { EndpointsTable.path eq path }
            .map { rowToEndpoint(it) }
            .singleOrNull()
    }

    override suspend fun getEndpointConfigById(id: String): EndpointConfiguration? = dbQuery {
        EndpointsTable.selectAll().where { EndpointsTable.id eq id }
            .map { rowToEndpoint(it) }
            .singleOrNull()
    }

    override suspend fun getIngressRulesForEndpoint(endpointId: String): List<IngressRule> = dbQuery {
        IngressRulesTable.selectAll().where { IngressRulesTable.endpointId eq endpointId }
            .map { rowToIngressRule(it) }
    }

    override suspend fun getDestinationsForEndpoint(endpointId: String): List<Destination> = dbQuery {
        DestinationsTable.selectAll().where { DestinationsTable.endpointId eq endpointId }
            .map { rowToDestination(it) }
    }

    private fun rowToEndpoint(row: ResultRow) = EndpointConfiguration(
        id = row[EndpointsTable.id],
        name = row[EndpointsTable.name],
        path = row[EndpointsTable.path],
        secret = row[EndpointsTable.secret],
        enabled = row[EndpointsTable.enabled],
        createdAt = row[EndpointsTable.createdAt],
        persistEvents = row[EndpointsTable.persistEvents],
        defaultAction = DefaultAction.valueOf(row[EndpointsTable.defaultAction]),
        securityType = SecurityType.valueOf(row[EndpointsTable.securityType]),
        rateLimitRpm = row[EndpointsTable.rateLimitRpm],
        maxBodySizeBytes = row[EndpointsTable.maxBodySizeBytes]
    )

    private fun rowToIngressRule(row: ResultRow): IngressRule {
        val hasCustomResponse = row[IngressRulesTable.customResponseStatusCode] != null
        var customResponse: CustomResponse? = null
        if (hasCustomResponse) {
            customResponse = CustomResponse(
                statusCode = row[IngressRulesTable.customResponseStatusCode]!!,
                headers = JsonHelper.deserialize(row[IngressRulesTable.customResponseHeadersJson]),
                bodyTemplate = row[IngressRulesTable.customResponseBodyTemplate]
            )
        }

        return IngressRule(
            id = row[IngressRulesTable.id],
            endpointId = row[IngressRulesTable.endpointId],
            priority = row[IngressRulesTable.priority],
            source = RuleSource.valueOf(row[IngressRulesTable.ruleSource]),
            expression = row[IngressRulesTable.expression],
            operator = Operator.valueOf(row[IngressRulesTable.operator]),
            expectedValue = row[IngressRulesTable.expectedValue],
            action = RuleAction.valueOf(row[IngressRulesTable.action]),
            customResponse = customResponse
        )
    }

    private fun rowToDestination(row: ResultRow) = Destination(
        id = row[DestinationsTable.id],
        endpointId = row[DestinationsTable.endpointId],
        name = row[DestinationsTable.name],
        type = DestinationType.valueOf(row[DestinationsTable.type]),
        enabled = row[DestinationsTable.enabled],
        settings = JsonHelper.deserialize(row[DestinationsTable.settingsJson]),
        routingCondition = row[DestinationsTable.routingCondition],
        transformationTemplate = row[DestinationsTable.transformationTemplate]
    )

    override suspend fun saveEndpointConfig(config: EndpointConfiguration): EndpointConfiguration = dbQuery {
        EndpointsTable.insert {
            it[id] = config.id
            it[name] = config.name
            it[path] = config.path
            it[secret] = config.secret
            it[enabled] = config.enabled
            it[createdAt] = config.createdAt
            it[persistEvents] = config.persistEvents
            it[defaultAction] = config.defaultAction.name
            it[securityType] = config.securityType.name
            it[rateLimitRpm] = config.rateLimitRpm
            it[maxBodySizeBytes] = config.maxBodySizeBytes
        }
        config
    }

    override suspend fun saveIngressRule(rule: IngressRule): IngressRule = dbQuery {
        IngressRulesTable.insert {
            it[id] = rule.id
            it[endpointId] = rule.endpointId
            it[priority] = rule.priority
            it[ruleSource] = rule.source.name
            it[expression] = rule.expression
            it[operator] = rule.operator.name
            it[expectedValue] = rule.expectedValue
            it[action] = rule.action.name
            
            val response = rule.customResponse
            if (response != null) {
                it[customResponseStatusCode] = response.statusCode
                it[customResponseHeadersJson] = JsonHelper.serialize(response.headers)
                it[customResponseBodyTemplate] = response.bodyTemplate
            }
        }
        rule
    }

    override suspend fun saveDestination(destination: Destination): Destination = dbQuery {
        DestinationsTable.insert {
            it[id] = destination.id
            it[endpointId] = destination.endpointId
            it[name] = destination.name
            it[type] = destination.type.name
            it[enabled] = destination.enabled
            it[settingsJson] = JsonHelper.serialize(destination.settings)
            it[routingCondition] = destination.routingCondition
            it[transformationTemplate] = destination.transformationTemplate
        }
        destination
    }

    override suspend fun deleteEndpointConfig(id: String): Boolean = dbQuery {
        IngressRulesTable.deleteWhere { IngressRulesTable.endpointId eq id }
        DestinationsTable.deleteWhere { DestinationsTable.endpointId eq id }
        val rows = EndpointsTable.deleteWhere { EndpointsTable.id eq id }
        rows > 0
    }

    override suspend fun updateEndpointConfig(config: EndpointConfiguration): EndpointConfiguration = dbQuery {
        EndpointsTable.update({ EndpointsTable.id eq config.id }) {
            it[name] = config.name
            it[path] = config.path
            it[secret] = config.secret
            it[enabled] = config.enabled
            it[persistEvents] = config.persistEvents
            it[defaultAction] = config.defaultAction.name
            it[securityType] = config.securityType.name
            it[rateLimitRpm] = config.rateLimitRpm
            it[maxBodySizeBytes] = config.maxBodySizeBytes
        }
        config
    }
}

class SqlEventRepository(private val database: Database) : EventRepository {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    override suspend fun saveEvent(event: WebhookEvent): WebhookEvent = dbQuery {
        WebhookEventsTable.insert {
            it[id] = event.id
            it[endpointId] = event.endpointId
            it[rawPayload] = event.rawPayload
            it[headersJson] = JsonHelper.serialize(event.headers)
            it[contentType] = event.contentType
            it[status] = event.status.name
            it[receivedAt] = event.receivedAt
        }
        event
    }
    override suspend fun getEventById(eventId: String): WebhookEvent? = dbQuery {
        WebhookEventsTable.selectAll().where { WebhookEventsTable.id eq eventId }
            .map { row ->
                WebhookEvent(
                    id = row[WebhookEventsTable.id],
                    endpointId = row[WebhookEventsTable.endpointId],
                    rawPayload = row[WebhookEventsTable.rawPayload],
                    headers = JsonHelper.deserialize(row[WebhookEventsTable.headersJson]),
                    contentType = row[WebhookEventsTable.contentType],
                    status = EventStatus.valueOf(row[WebhookEventsTable.status]),
                    receivedAt = row[WebhookEventsTable.receivedAt]
                )
            }
            .singleOrNull()
    }
    override suspend fun getEvents(limit: Int): List<WebhookEvent> = dbQuery {
        WebhookEventsTable.selectAll()
            .orderBy(WebhookEventsTable.receivedAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                WebhookEvent(
                    id = row[WebhookEventsTable.id],
                    endpointId = row[WebhookEventsTable.endpointId],
                    rawPayload = row[WebhookEventsTable.rawPayload],
                    headers = JsonHelper.deserialize(row[WebhookEventsTable.headersJson]),
                    contentType = row[WebhookEventsTable.contentType],
                    status = EventStatus.valueOf(row[WebhookEventsTable.status]),
                    receivedAt = row[WebhookEventsTable.receivedAt]
                )
            }
    }
    override suspend fun updateEventStatus(eventId: String, status: EventStatus): Unit = dbQuery {
        WebhookEventsTable.update({ WebhookEventsTable.id eq eventId }) {
            it[WebhookEventsTable.status] = status.name
        }
    }

    override suspend fun saveAttempt(attempt: DispatchAttempt): DispatchAttempt = dbQuery {
        DispatchAttemptsTable.insert {
            it[id] = attempt.id
            it[eventId] = attempt.eventId
            it[destinationId] = attempt.destinationId
            it[status] = attempt.status.name
            it[attemptNumber] = attempt.attemptNumber
            it[responseStatusCode] = attempt.responseStatusCode
            it[errorMessage] = attempt.errorMessage
            it[executedAt] = attempt.executedAt
        }
        attempt
    }

    override suspend fun getAttemptsForEvent(eventId: String): List<DispatchAttempt> = dbQuery {
        DispatchAttemptsTable.selectAll().where { DispatchAttemptsTable.eventId eq eventId }
            .map { rowToAttempt(it) }
    }

    private fun rowToAttempt(row: ResultRow) = DispatchAttempt(
        id = row[DispatchAttemptsTable.id],
        eventId = row[DispatchAttemptsTable.eventId],
        destinationId = row[DispatchAttemptsTable.destinationId],
        status = AttemptStatus.valueOf(row[DispatchAttemptsTable.status]),
        attemptNumber = row[DispatchAttemptsTable.attemptNumber],
        responseStatusCode = row[DispatchAttemptsTable.responseStatusCode],
        errorMessage = row[DispatchAttemptsTable.errorMessage],
        executedAt = row[DispatchAttemptsTable.executedAt]
    )
}
