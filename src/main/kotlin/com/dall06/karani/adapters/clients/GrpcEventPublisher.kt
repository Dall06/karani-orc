package com.dall06.karani.adapters.clients

import com.dall06.karani.domain.AttemptStatus
import com.dall06.karani.domain.Destination
import com.dall06.karani.domain.DispatchAttempt
import com.dall06.karani.domain.WebhookEvent
import com.dall06.karani.ports.spi.EventPublisher
import java.time.Instant
import java.util.UUID

class GrpcEventPublisher : EventPublisher {

    override suspend fun publish(event: WebhookEvent, destination: Destination): DispatchAttempt {
        val host = destination.settings["host"] ?: "localhost"
        val port = destination.settings["port"]?.toIntOrNull() ?: 50051

        // En un entorno de producción, aquí se inicializaría el canal gRPC:
        // io.grpc.ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
        // Y se llamaría al Stub correspondiente. Simulamos despacho exitoso de alta velocidad.
        
        return DispatchAttempt(
            id = UUID.randomUUID().toString(),
            eventId = event.id,
            destinationId = destination.id,
            status = AttemptStatus.SUCCESS,
            attemptNumber = 1,
            responseStatusCode = null,
            errorMessage = null,
            executedAt = Instant.now()
        )
    }
}
