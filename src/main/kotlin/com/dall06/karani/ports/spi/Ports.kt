package com.dall06.karani.ports.spi

import com.dall06.karani.domain.*

interface ConfigurationRepository {
    suspend fun getEndpointConfigByPath(path: String): EndpointConfiguration?
    suspend fun getEndpointConfigById(id: String): EndpointConfiguration?
    suspend fun getIngressRulesForEndpoint(endpointId: String): List<IngressRule>
    suspend fun getDestinationsForEndpoint(endpointId: String): List<Destination>
    suspend fun saveEndpointConfig(config: EndpointConfiguration): EndpointConfiguration
    suspend fun saveIngressRule(rule: IngressRule): IngressRule
    suspend fun saveDestination(destination: Destination): Destination
    suspend fun deleteEndpointConfig(id: String): Boolean
    suspend fun updateEndpointConfig(config: EndpointConfiguration): EndpointConfiguration
}

interface EventRepository {
    suspend fun saveEvent(event: WebhookEvent): WebhookEvent
    suspend fun getEventById(eventId: String): WebhookEvent?
    suspend fun getEvents(limit: Int): List<WebhookEvent>
    suspend fun updateEventStatus(eventId: String, status: EventStatus)
    suspend fun saveAttempt(attempt: DispatchAttempt): DispatchAttempt
    suspend fun getAttemptsForEvent(eventId: String): List<DispatchAttempt>
}

interface EventPublisher {
    suspend fun publish(event: WebhookEvent, destination: Destination): DispatchAttempt
}

interface BodyEvaluator {
    fun evaluate(rawBody: String, expression: String): String?
    fun supports(contentType: String?): Boolean
}
