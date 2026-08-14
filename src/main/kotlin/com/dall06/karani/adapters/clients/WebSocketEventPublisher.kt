package com.dall06.karani.adapters.clients

import com.dall06.karani.domain.AttemptStatus
import com.dall06.karani.domain.Destination
import com.dall06.karani.domain.DispatchAttempt
import com.dall06.karani.domain.WebhookEvent
import com.dall06.karani.ports.spi.EventPublisher
import io.ktor.websocket.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class WebSocketEventPublisher : EventPublisher {

    companion object {
        val sessions = ConcurrentHashMap<String, DefaultWebSocketSession>()
    }

    override suspend fun publish(event: WebhookEvent, destination: Destination): DispatchAttempt {
        val targetClientId = destination.settings["clientId"]
        
        val count = AtomicInteger(0)
        val errorsList = CopyOnWriteArrayList<String>()

        if (targetClientId != null) {
            val session = sessions[targetClientId]
            if (session != null) {
                try {
                    session.send(Frame.Text(event.rawPayload))
                    count.incrementAndGet()
                } catch (e: Exception) {
                    errorsList.add("Client $targetClientId failed: ${e.message}")
                    sessions.remove(targetClientId)
                }
            }
        }

        if (targetClientId == null) {
            val sessionsCopy = sessions.toMap()
            coroutineScope {
                sessionsCopy.forEach { (clientId, session) ->
                    launch {
                        try {
                            session.send(Frame.Text(event.rawPayload))
                            count.incrementAndGet()
                        } catch (e: Exception) {
                            errorsList.add("Client $clientId failed: ${e.message}")
                            sessions.remove(clientId)
                        }
                    }
                }
            }
        }

        var status = AttemptStatus.SUCCESS
        var errorMessage: String? = null
        if (count.get() == 0) {
            status = AttemptStatus.FAILED
            errorMessage = "No active WebSocket clients connected. ${errorsList.joinToString("; ")}".trim()
        }

        return DispatchAttempt(
            id = UUID.randomUUID().toString(),
            eventId = event.id,
            destinationId = destination.id,
            status = status,
            attemptNumber = 1,
            responseStatusCode = null,
            errorMessage = errorMessage,
            executedAt = Instant.now()
        )
    }
}
