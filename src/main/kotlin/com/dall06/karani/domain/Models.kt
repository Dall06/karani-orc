package com.dall06.karani.domain

import java.time.Instant

enum class DefaultAction {
    ALLOW,
    DENY
}

enum class SecurityType {
    NONE,
    HMAC_SHA256,
    ECDSA_SHA256,
    CUSTOM
}

data class EndpointConfiguration(
    val id: String,
    val name: String,
    val path: String,
    val secret: String?,
    val enabled: Boolean,
    val createdAt: Instant,
    val persistEvents: Boolean = true,
    val defaultAction: DefaultAction = DefaultAction.ALLOW,
    val securityType: SecurityType = SecurityType.NONE,
    val rateLimitRpm: Int = 0,
    val maxBodySizeBytes: Long = 0
)

enum class Operator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    EXISTS,
    NOT_EXISTS,
    REGEX
}

enum class RuleAction {
    ACCEPT,
    DECLINE,
    RESPOND
}

data class CustomResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val bodyTemplate: String?
)

enum class RuleSource {
    HEADER,
    BODY
}

data class IngressRule(
    val id: String,
    val endpointId: String,
    val priority: Int,
    val source: RuleSource,
    val expression: String, // Nombre del header o expresión de búsqueda (JSONPath/XPath/Regex) en el body
    val operator: Operator,
    val expectedValue: String?,
    val action: RuleAction,
    val customResponse: CustomResponse?
)

enum class DestinationType {
    HTTP,
    KAFKA,
    WEBSOCKET,
    GRPC
}

data class Destination(
    val id: String,
    val endpointId: String,
    val name: String,
    val type: DestinationType,
    val enabled: Boolean,
    val settings: Map<String, String>, // E.g. url, topic, headers
    val routingCondition: String? = null,
    val transformationTemplate: String? = null
)

enum class EventStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    IGNORED
}

data class WebhookEvent(
    val id: String,
    val endpointId: String,
    val rawPayload: String,
    val headers: Map<String, String>,
    val contentType: String?,
    val status: EventStatus,
    val receivedAt: Instant
)

enum class AttemptStatus {
    SUCCESS,
    FAILED
}

data class DispatchAttempt(
    val id: String,
    val eventId: String,
    val destinationId: String,
    val status: AttemptStatus,
    val attemptNumber: Int,
    val responseStatusCode: Int?,
    val errorMessage: String?,
    val executedAt: Instant
)
