package com.dall06.karani.ports.api

import com.dall06.karani.domain.*

interface WebhookIngestUseCase {
    suspend fun ingest(
        path: String,
        headers: Map<String, String>,
        body: String,
        contentType: String?
    ): IngestResult
}

sealed interface IngestResult {
    data class Accepted(val eventId: String, val event: WebhookEvent) : IngestResult
    data class Declined(val reason: String) : IngestResult
    data class CustomResponded(val statusCode: Int, val headers: Map<String, String>, val body: String?) : IngestResult
    object EndpointNotFound : IngestResult
    data class InvalidPayload(val reason: String) : IngestResult
}
interface EventDispatcherUseCase {
    suspend fun dispatch(event: WebhookEvent)
}
