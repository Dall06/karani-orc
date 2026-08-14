package com.dall06.karani.adapters.clients

import com.dall06.karani.domain.AttemptStatus
import com.dall06.karani.domain.Destination
import com.dall06.karani.domain.DestinationType
import com.dall06.karani.domain.WebhookEvent
import com.dall06.karani.domain.EventStatus
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaEventPublisherTest {

    @Test
    fun testKafkaPublishSuccessful() {
        val mockProducer = MockProducer(true, StringSerializer(), StringSerializer())
        val publisher = KafkaEventPublisher(mockProducer)

        val event = WebhookEvent(
            id = "evt-kafka-1",
            endpointId = "ep-1",
            rawPayload = "{\"event\":\"order_created\"}",
            headers = emptyMap(),
            contentType = "application/json",
            status = EventStatus.PENDING,
            receivedAt = Instant.now()
        )

        val destination = Destination(
            id = "dest-k-1",
            endpointId = "ep-1",
            name = "Kafka Target",
            type = DestinationType.KAFKA,
            enabled = true,
            settings = mapOf("topic" to "orders-topic")
        )

        val attempt = kotlinx.coroutines.runBlocking {
            publisher.publish(event, destination)
        }

        assertEquals(AttemptStatus.SUCCESS, attempt.status)
        assertEquals(1, mockProducer.history().size)
        
        val record = mockProducer.history()[0]
        assertEquals("orders-topic", record.topic())
        assertEquals("evt-kafka-1", record.key())
        assertEquals("{\"event\":\"order_created\"}", record.value())
    }

    @Test
    fun testKafkaPublishMissingTopic() {
        val mockProducer = MockProducer(true, StringSerializer(), StringSerializer())
        val publisher = KafkaEventPublisher(mockProducer)

        val event = WebhookEvent(
            id = "evt-kafka-1",
            endpointId = "ep-1",
            rawPayload = "{\"event\":\"order_created\"}",
            headers = emptyMap(),
            contentType = "application/json",
            status = EventStatus.PENDING,
            receivedAt = Instant.now()
        )

        val destination = Destination(
            id = "dest-k-1",
            endpointId = "ep-1",
            name = "Kafka Target",
            type = DestinationType.KAFKA,
            enabled = true,
            settings = emptyMap()
        )

        val attempt = kotlinx.coroutines.runBlocking {
            publisher.publish(event, destination)
        }

        assertEquals(AttemptStatus.FAILED, attempt.status)
        assertTrue(attempt.errorMessage!!.contains("Missing target 'topic'"))
    }
}
