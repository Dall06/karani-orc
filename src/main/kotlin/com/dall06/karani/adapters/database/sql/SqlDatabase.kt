package com.dall06.karani.adapters.database.sql

import com.dall06.karani.domain.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp

object EndpointsTable : Table("endpoint_configurations") {
    val id = varchar("id", 50)
    val name = varchar("name", 100)
    val path = varchar("path", 255).uniqueIndex()
    val secret = varchar("secret", 255).nullable()
    val enabled = bool("enabled")
    val createdAt = timestamp("created_at")
    val persistEvents = bool("persist_events").default(true)
    val defaultAction = varchar("default_action", 20).default(DefaultAction.ALLOW.name)
    val securityType = varchar("security_type", 30).default(SecurityType.NONE.name)
    val rateLimitRpm = integer("rate_limit_rpm").default(0)
    val maxBodySizeBytes = long("max_body_size_bytes").default(0L)
    override val primaryKey = PrimaryKey(id)
}

object IngressRulesTable : Table("ingress_rules") {
    val id = varchar("id", 50)
    val endpointId = varchar("endpoint_id", 50).references(EndpointsTable.id)
    val priority = integer("priority")
    val ruleSource = varchar("source", 20) // HEADER, BODY
    val expression = text("expression")
    val operator = varchar("operator", 20)
    val expectedValue = text("expected_value").nullable()
    val action = varchar("action", 20)
    
    // Custom response details
    val customResponseStatusCode = integer("custom_response_status_code").nullable()
    val customResponseHeadersJson = text("custom_response_headers_json").nullable()
    val customResponseBodyTemplate = text("custom_response_body_template").nullable()
    override val primaryKey = PrimaryKey(id)
}

object DestinationsTable : Table("destinations") {
    val id = varchar("id", 50)
    val endpointId = varchar("endpoint_id", 50).references(EndpointsTable.id)
    val name = varchar("name", 100)
    val type = varchar("type", 20)
    val enabled = bool("enabled")
    val settingsJson = text("settings_json")
    val routingCondition = text("routing_condition").nullable()
    val transformationTemplate = text("transformation_template").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WebhookEventsTable : Table("webhook_events") {
    val id = varchar("id", 50)
    val endpointId = varchar("endpoint_id", 50).references(EndpointsTable.id, onDelete = ReferenceOption.CASCADE)
    val rawPayload = text("raw_payload")
    val headersJson = text("headers_json")
    val contentType = varchar("content_type", 100).nullable()
    val status = varchar("status", 20)
    val receivedAt = timestamp("received_at")
    override val primaryKey = PrimaryKey(id)
}

object DispatchAttemptsTable : Table("dispatch_attempts") {
    val id = varchar("id", 50)
    val eventId = varchar("event_id", 50).references(WebhookEventsTable.id, onDelete = ReferenceOption.CASCADE)
    val destinationId = varchar("destination_id", 50)
    val status = varchar("status", 20)
    val attemptNumber = integer("attempt_number")
    val responseStatusCode = integer("response_status_code").nullable()
    val errorMessage = text("error_message").nullable()
    val executedAt = timestamp("executed_at")
    override val primaryKey = PrimaryKey(id)
}

object JsonHelper {
    private val mapper = jacksonObjectMapper()

    fun serialize(map: Map<String, String>?): String {
        if (map == null) return "{}"
        return mapper.writeValueAsString(map)
    }

    fun deserialize(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            mapper.readValue(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
