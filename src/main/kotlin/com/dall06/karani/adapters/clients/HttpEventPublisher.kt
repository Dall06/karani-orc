package com.dall06.karani.adapters.clients

import com.dall06.karani.domain.AttemptStatus
import com.dall06.karani.domain.Destination
import com.dall06.karani.domain.DispatchAttempt
import com.dall06.karani.domain.WebhookEvent
import com.dall06.karani.ports.spi.EventPublisher
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.discardRemaining
import io.ktor.http.*
import java.time.Instant
import java.util.UUID

class HttpEventPublisher(private val httpClient: HttpClient) : EventPublisher {

    override suspend fun publish(event: WebhookEvent, destination: Destination): DispatchAttempt {
        var url = destination.settings["url"]
        if (url == null) {
            return DispatchAttempt(
                id = UUID.randomUUID().toString(),
                eventId = event.id,
                destinationId = destination.id,
                status = AttemptStatus.FAILED,
                attemptNumber = 1,
                responseStatusCode = null,
                errorMessage = "Missing target URL in destination settings",
                executedAt = Instant.now()
            )
        }

        val queryParams = event.headers.filterKeys { it.startsWith("query:") }
        if (queryParams.isNotEmpty()) {
            val queryString = queryParams.map { (key, value) ->
                val paramName = key.removePrefix("query:")
                "${java.net.URLEncoder.encode(paramName, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
            }.joinToString("&")
            
            var separator = "?"
            if (url.contains("?")) {
                separator = "&"
            }
            url = "$url$separator$queryString"
        }

        val timeoutMs = destination.settings["timeoutMs"]?.toLongOrNull() ?: 5000L

        return try {
            val response = httpClient.post(url) {
                timeout {
                    requestTimeoutMillis = timeoutMs
                    connectTimeoutMillis = timeoutMs
                }

                setBody(event.rawPayload)
                
                if (event.contentType != null) {
                    contentType(ContentType.parse(event.contentType))
                }

                destination.settings.filterKeys { it.startsWith("header:") }.forEach { (key, value) ->
                    val headerName = key.removePrefix("header:")
                    header(headerName, value)
                }
            }

            var status = AttemptStatus.FAILED
            val statusCode = response.status.value
            if (statusCode in 200..299) {
                status = AttemptStatus.SUCCESS
            }
            try {
                response.discardRemaining()
            } catch (e: Exception) {
                // Si falla descartando porque ya está cerrada o por error de red, ignorar
            }

            DispatchAttempt(
                id = UUID.randomUUID().toString(),
                eventId = event.id,
                destinationId = destination.id,
                status = status,
                attemptNumber = 1,
                responseStatusCode = statusCode,
                errorMessage = null,
                executedAt = Instant.now()
            )
        } catch (e: Exception) {
            DispatchAttempt(
                id = UUID.randomUUID().toString(),
                eventId = event.id,
                destinationId = destination.id,
                status = AttemptStatus.FAILED,
                attemptNumber = 1,
                responseStatusCode = null,
                errorMessage = e.message ?: e.javaClass.simpleName,
                executedAt = Instant.now()
            )
        }
    }
}
