package com.dall06.karani.adapters.database.mongo

import com.dall06.karani.domain.*
import com.dall06.karani.ports.spi.EventRepository
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.bson.Document

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.collect

class MongoEventRepository(private val database: MongoDatabase) : EventRepository {

    private val eventCollection = database.getCollection<Document>("webhook_events")
    private val attemptCollection = database.getCollection<Document>("dispatch_attempts")

    override suspend fun saveEvent(event: WebhookEvent): WebhookEvent {
        val doc = Document()
            .append("_id", event.id)
            .append("endpointId", event.endpointId)
            .append("rawPayload", event.rawPayload)
            .append("headers", Document(event.headers))
            .append("contentType", event.contentType)
            .append("status", event.status.name)
            .append("receivedAt", event.receivedAt.toString())
        
        eventCollection.insertOne(doc)
        return event
    }

    override suspend fun getEventById(eventId: String): WebhookEvent? {
        val doc = eventCollection.find(Filters.eq("_id", eventId)).firstOrNull()
        if (doc == null) return null
        
        val headersDoc = doc["headers"] as? Document ?: Document()
        val headers = headersDoc.entries.associate { it.key to it.value.toString() }
        
        return WebhookEvent(
            id = doc.getString("_id"),
            endpointId = doc.getString("endpointId"),
            rawPayload = doc.getString("rawPayload"),
            headers = headers,
            contentType = doc.getString("contentType"),
            status = EventStatus.valueOf(doc.getString("status")),
            receivedAt = java.time.Instant.parse(doc.getString("receivedAt"))
        )
    }

    override suspend fun getEvents(limit: Int): List<WebhookEvent> {
        val list = mutableListOf<WebhookEvent>()
        eventCollection.find()
            .sort(Document("receivedAt", -1))
            .limit(limit)
            .collect { doc ->
                val headersDoc = doc["headers"] as? Document ?: Document()
                val headers = headersDoc.entries.associate { it.key to it.value.toString() }
                list.add(
                    WebhookEvent(
                        id = doc.getString("_id"),
                        endpointId = doc.getString("endpointId"),
                        rawPayload = doc.getString("rawPayload"),
                        headers = headers,
                        contentType = doc.getString("contentType"),
                        status = EventStatus.valueOf(doc.getString("status")),
                        receivedAt = java.time.Instant.parse(doc.getString("receivedAt"))
                    )
                )
            }
        return list
    }

    override suspend fun updateEventStatus(eventId: String, status: EventStatus) {
        eventCollection.updateOne(
            Filters.eq("_id", eventId),
            Updates.set("status", status.name)
        )
    }

    override suspend fun saveAttempt(attempt: DispatchAttempt): DispatchAttempt {
        val doc = Document()
            .append("_id", attempt.id)
            .append("eventId", attempt.eventId)
            .append("destinationId", attempt.destinationId)
            .append("status", attempt.status.name)
            .append("attemptNumber", attempt.attemptNumber)
            .append("responseStatusCode", attempt.responseStatusCode)
            .append("errorMessage", attempt.errorMessage)
            .append("executedAt", attempt.executedAt.toString())
        
        attemptCollection.insertOne(doc)
        return attempt
    }

    override suspend fun getAttemptsForEvent(eventId: String): List<DispatchAttempt> {
        val list = mutableListOf<DispatchAttempt>()
        attemptCollection.find(Filters.eq("eventId", eventId))
            .collect { doc ->
                list.add(
                    DispatchAttempt(
                        id = doc.getString("_id"),
                        eventId = doc.getString("eventId"),
                        destinationId = doc.getString("destinationId"),
                        status = AttemptStatus.valueOf(doc.getString("status")),
                        attemptNumber = doc.getInteger("attemptNumber") ?: 1,
                        responseStatusCode = doc.getInteger("responseStatusCode"),
                        errorMessage = doc.getString("errorMessage"),
                        executedAt = java.time.Instant.parse(doc.getString("executedAt"))
                    )
                )
            }
        return list
    }
}
