package com.dall06.karani.usecases

import com.dall06.karani.domain.*
import com.dall06.karani.ports.api.IngestResult
import com.dall06.karani.ports.api.WebhookIngestUseCase
import com.dall06.karani.adapters.evaluators.BodyEvaluatorTool
import com.dall06.karani.ports.spi.ConfigurationRepository
import com.dall06.karani.ports.spi.EventRepository
import com.dall06.karani.ports.spi.WebhookSecurityValidator
import java.time.Instant
import java.util.UUID

class WebhookIngestUseCaseImpl(
    private val configRepository: ConfigurationRepository,
    private val eventRepository: EventRepository,
    private val securityValidators: List<WebhookSecurityValidator> = emptyList()
) : WebhookIngestUseCase {

    override suspend fun ingest(
        path: String,
        headers: Map<String, String>,
        body: String,
        contentType: String?
    ): IngestResult {
        val config = configRepository.getEndpointConfigByPath(path)
            ?: return IngestResult.EndpointNotFound

        if (!config.enabled) {
            return IngestResult.Declined("Endpoint is disabled")
        }

        if (config.securityType != SecurityType.NONE) {
            val validator = securityValidators.find { it.type == config.securityType }
            if (validator == null || !validator.validate(body, headers, config)) {
                return IngestResult.InvalidPayload("Security validation failed (Type: ${config.securityType})")
            }
        }

        val rules = configRepository.getIngressRulesForEndpoint(config.id)
            .sortedBy { it.priority }

        for (rule in rules) {
            val actualValue = when (rule.source) {
                RuleSource.HEADER -> headers[rule.expression]
                RuleSource.BODY -> evaluateBodyValue(body, contentType, rule.expression)
            }

            if (evaluateCondition(actualValue, rule.operator, rule.expectedValue)) {
                when (rule.action) {
                    RuleAction.DECLINE -> {
                        processEventWithStatus(config, body, headers, contentType, EventStatus.IGNORED)
                        return IngestResult.Declined("Event rejected by rule ID: ${rule.id}")
                    }
                    RuleAction.RESPOND -> {
                        val response = rule.customResponse
                            ?: CustomResponse(200, emptyMap(), null)
                        
                        val resolvedBody = response.bodyTemplate?.let { template ->
                            resolveTemplate(template, actualValue)
                        }

                        processEventWithStatus(config, body, headers, contentType, EventStatus.IGNORED)
                        return IngestResult.CustomResponded(
                            statusCode = response.statusCode,
                            headers = response.headers,
                            body = resolvedBody
                        )
                    }
                    RuleAction.ACCEPT -> {
                        val savedEvent = processEventWithStatus(config, body, headers, contentType, EventStatus.PENDING)
                        return IngestResult.Accepted(savedEvent.id, savedEvent)
                    }
                }
            }
        }

        if (config.defaultAction == DefaultAction.DENY) {
            processEventWithStatus(config, body, headers, contentType, EventStatus.IGNORED)
            return IngestResult.Declined("Event rejected by default ACL policy (DENY)")
        }

        val savedEvent = processEventWithStatus(config, body, headers, contentType, EventStatus.PENDING)
        return IngestResult.Accepted(savedEvent.id, savedEvent)
    }

    private fun evaluateBodyValue(body: String, contentType: String?, expression: String): String? {
        return BodyEvaluatorTool.evaluate(body, contentType, expression)
    }

    private fun evaluateCondition(actualValue: String?, operator: Operator, expectedValue: String?): Boolean {
        return when (operator) {
            Operator.EQUALS -> actualValue == expectedValue
            Operator.NOT_EQUALS -> actualValue != expectedValue
            Operator.CONTAINS -> actualValue != null && expectedValue != null && actualValue.contains(expectedValue)
            Operator.EXISTS -> actualValue != null
            Operator.NOT_EXISTS -> actualValue == null
            Operator.REGEX -> actualValue != null && expectedValue != null && expectedValue.toRegex().containsMatchIn(actualValue)
        }
    }

    private fun resolveTemplate(template: String, matchedValue: String?): String {
        if (matchedValue == null) return template
        return template
            .replace("\${value}", matchedValue)
            .replace("{value}", matchedValue)
    }

    private suspend fun processEventWithStatus(
        config: EndpointConfiguration,
        body: String,
        headers: Map<String, String>,
        contentType: String?,
        status: EventStatus
    ): WebhookEvent {
        val event = WebhookEvent(
            id = UUID.randomUUID().toString(),
            endpointId = config.id,
            rawPayload = body,
            headers = headers,
            contentType = contentType,
            status = status,
            receivedAt = Instant.now()
        )
        if (config.persistEvents) {
            return eventRepository.saveEvent(event)
        }
        return event
    }
}
