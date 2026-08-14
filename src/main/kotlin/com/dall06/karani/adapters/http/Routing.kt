package com.dall06.karani.adapters.http

import com.dall06.karani.adapters.database.sql.*
import com.dall06.karani.ports.api.IngestResult
import com.dall06.karani.ports.spi.ConfigurationRepository
import com.dall06.karani.ports.spi.EventRepository
import com.dall06.karani.usecases.EventDispatcherUseCaseImpl
import com.dall06.karani.usecases.WebhookIngestUseCaseImpl
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.toMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlin.time.Duration.Companion.seconds

fun Application.configureRouting() {
    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    val closeableResources = mutableListOf<Closeable>()

    val configType = environment.config.propertyOrNull("karani.database.config.type")?.getString()
        ?: environment.config.propertyOrNull("karani.database.config-type")?.getString()
        ?: "sqlite"
    var configUrl = environment.config.propertyOrNull("karani.database.config.sqlite.url")?.getString()
        ?: environment.config.propertyOrNull("karani.database.config-url")?.getString()
        ?: "jdbc:sqlite:karani-config.db"
    if (configType == "postgres") {
        configUrl = environment.config.propertyOrNull("karani.database.config.postgres.url")?.getString()
            ?: environment.config.propertyOrNull("karani.database.config-url")?.getString()
            ?: "jdbc:postgresql://localhost:5432/karani_config"
    }

    val eventsType = environment.config.propertyOrNull("karani.database.events.type")?.getString()
        ?: environment.config.propertyOrNull("karani.database.events-type")?.getString()
        ?: "sqlite"
    var eventsUrl = environment.config.propertyOrNull("karani.database.events.sqlite.url")?.getString()
        ?: environment.config.propertyOrNull("karani.database.events-url")?.getString()
        ?: "jdbc:sqlite:karani-events.db"
    if (eventsType == "postgres") {
        eventsUrl = environment.config.propertyOrNull("karani.database.events.postgres.url")?.getString()
            ?: environment.config.propertyOrNull("karani.database.events-url")?.getString()
            ?: "jdbc:postgresql://localhost:5432/karani_events"
    }
    if (eventsType == "mongodb") {
        eventsUrl = environment.config.propertyOrNull("karani.database.events.mongodb.url")?.getString()
            ?: environment.config.propertyOrNull("karani.database.events-url")?.getString()
            ?: "mongodb://localhost:27017/karani_events"
    }

    fun getConfigList(path: String): List<io.ktor.server.config.ApplicationConfig> {
        return try {
            environment.config.configList(path)
        } catch (e: Exception) {
            emptyList()
        }
    }

    var configRepo: ConfigurationRepository? = null

    var configDriver = "org.sqlite.JDBC"
    if (configType == "postgres") {
        configDriver = "org.postgresql.Driver"
    }
    val configDb = Database.connect(configUrl, driver = configDriver)
    transaction(configDb) {
        SchemaUtils.createMissingTablesAndColumns(EndpointsTable, IngressRulesTable, DestinationsTable)
    }
    configRepo = SqlConfigurationRepository(configDb)

    var eventRepo: com.dall06.karani.ports.spi.EventRepository? = null

    if (eventsType == "all") {
        val repos = mutableListOf<EventRepository>()
        
        val sqlites = getConfigList("karani.database.events.sqlite")
        sqlites.forEach { config ->
            try {
                val url = config.property("url").getString()
                val eventUrl = url.replace("config", "events")
                val db = Database.connect(eventUrl, driver = "org.sqlite.JDBC")
                transaction(db) { SchemaUtils.createMissingTablesAndColumns(WebhookEventsTable, DispatchAttemptsTable) }
                repos.add(SqlEventRepository(db))
            } catch (e: Exception) {
                log.warn("Failed to connect to events SQLite: ${e.message}")
            }
        }

        val postgresList = getConfigList("karani.database.events.postgres")
        postgresList.forEach { config ->
            try {
                val url = config.property("url").getString()
                val db = Database.connect(url, driver = "org.postgresql.Driver")
                transaction(db) { SchemaUtils.createMissingTablesAndColumns(WebhookEventsTable, DispatchAttemptsTable) }
                repos.add(SqlEventRepository(db))
            } catch (e: Exception) {
                log.warn("Failed to connect to events PostgreSQL: ${e.message}")
            }
        }

        val mongoList = getConfigList("karani.database.events.mongodb")
        mongoList.forEach { config ->
            try {
                val url = config.property("url").getString()
                val mongoClient = com.mongodb.kotlin.client.coroutine.MongoClient.create(url)
                closeableResources.add(mongoClient)
                repos.add(com.dall06.karani.adapters.database.mongo.MongoEventRepository(mongoClient.getDatabase("karani")))
            } catch (e: Exception) {
                log.warn("Failed to connect to events MongoDB: ${e.message}")
            }
        }

        if (repos.isNotEmpty()) {
            eventRepo = com.dall06.karani.adapters.database.composite.CompositeEventRepository(repos)
        }
    }

    if (eventsType == "mongodb" && eventRepo == null) {
        val mongoClient = com.mongodb.kotlin.client.coroutine.MongoClient.create(eventsUrl)
        closeableResources.add(mongoClient)
        val mongoDb = mongoClient.getDatabase("karani")
        eventRepo = com.dall06.karani.adapters.database.mongo.MongoEventRepository(mongoDb)
    }

    if (eventRepo == null) {
        var eventsDriver = "org.sqlite.JDBC"
        if (eventsType == "postgres") {
            eventsDriver = "org.postgresql.Driver"
        }
        val eventsDb = Database.connect(eventsUrl, driver = eventsDriver)
        transaction(eventsDb) {
            SchemaUtils.createMissingTablesAndColumns(WebhookEventsTable, DispatchAttemptsTable)
        }
        eventRepo = SqlEventRepository(eventsDb)
    }

    val securityValidators = listOf(
        com.dall06.karani.adapters.security.HmacSecurityValidator(),
        com.dall06.karani.adapters.security.EcdsaSecurityValidator()
    )
    val ingestUseCase = WebhookIngestUseCaseImpl(configRepo, eventRepo, securityValidators)
    
    install(WebSockets)

    val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
    closeableResources.add(httpClient)
    
    val httpPublisher = com.dall06.karani.adapters.clients.HttpEventPublisher(httpClient)
    val wsPublisher = com.dall06.karani.adapters.clients.WebSocketEventPublisher()
    val grpcPublisher = com.dall06.karani.adapters.clients.GrpcEventPublisher()
    
    val kafkaConfigs = getConfigList("karani.outputs.kafka")
    var kafkaProducer: org.apache.kafka.clients.producer.KafkaProducer<String, String>? = null
    if (kafkaConfigs.isNotEmpty()) {
        try {
            val config = kafkaConfigs.first()
            val bootstrap = config.propertyOrNull("bootstrap-servers")?.getString()
                ?: config.propertyOrNull("bootstrap-servers-default")?.getString()
                ?: "localhost:9092"
            val clientId = config.propertyOrNull("client-id")?.getString() ?: "karani-orchestrator"
            val acks = config.propertyOrNull("acks")?.getString() ?: "all"

            val props = java.util.Properties().apply {
                put("bootstrap.servers", bootstrap)
                put("client.id", clientId)
                put("acks", acks)
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
                put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
                put("max.block.ms", "2000")
            }
            val producer = org.apache.kafka.clients.producer.KafkaProducer<String, String>(props)
            kafkaProducer = producer
            closeableResources.add(producer)
        } catch (e: Exception) {
            log.warn("Failed to initialize Kafka Producer: ${e.message}")
        }
    }

    val publishers = mutableMapOf<com.dall06.karani.domain.DestinationType, com.dall06.karani.ports.spi.EventPublisher>()
    publishers[com.dall06.karani.domain.DestinationType.HTTP] = httpPublisher
    publishers[com.dall06.karani.domain.DestinationType.WEBSOCKET] = wsPublisher
    publishers[com.dall06.karani.domain.DestinationType.GRPC] = grpcPublisher
    if (kafkaProducer != null) {
        publishers[com.dall06.karani.domain.DestinationType.KAFKA] = com.dall06.karani.adapters.clients.KafkaEventPublisher(kafkaProducer)
    }

    val dispatcherUseCase = EventDispatcherUseCaseImpl(configRepo, eventRepo, publishers)

    val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    monitor.subscribe(ApplicationStopped) {
        try {
            backgroundScope.cancel()
        } catch (e: Exception) {
            log.warn("Failed to cancel background scope during shutdown: ${e.message}")
        }
        closeableResources.forEach { resource ->
            try {
                resource.close()
            } catch (e: Exception) {
                log.warn("Failed to close resource during shutdown: ${e.message}")
            }
        }
    }

    var apiKey = environment.config.propertyOrNull("karani.api.api-key")?.getString() ?: ""
    if (apiKey.isBlank()) {
        val isTest = try {
            Class.forName("io.ktor.server.testing.TestApplicationEngine")
            true
        } catch (e: Exception) {
            false
        }
        if (!isTest) {
            apiKey = "karani-admin-token-" + java.util.UUID.randomUUID().toString().replace("-", "")
            log.info("\n" +
                "********************************************************************************\n" +
                "[SECURITY WARNING] No API Key was configured.\n" +
                "A secure temporary API Key has been generated for this session:\n\n" +
                "  $apiKey\n\n" +
                "Please use this key in the 'X-API-Key' header to access administrative APIs.\n" +
                "********************************************************************************"
            )
        }
    }

    suspend fun io.ktor.server.application.ApplicationCall.isAuthorized(): Boolean {
        if (apiKey.isBlank()) return true
        val requestKey = request.headers["X-API-Key"]
        return requestKey == apiKey
    }

    routing {
        get("/") {
            call.respondText("Karani Webhook Orchestrator is running!")
        }

        val wsPath = environment.config.propertyOrNull("karani.outputs.websocket.path")?.getString() ?: "/ws/events"
        webSocket(wsPath) {
            val clientId = call.request.queryParameters["clientId"] ?: java.util.UUID.randomUUID().toString()
            com.dall06.karani.adapters.clients.WebSocketEventPublisher.sessions[clientId] = this
            try {
                for (frame in incoming) {
                    // Mantener viva la conexión e ignorar mensajes entrantes del cliente
                }
            } finally {
                val session = this
                com.dall06.karani.adapters.clients.WebSocketEventPublisher.sessions.compute(clientId) { _, current ->
                    var next = current
                    if (current == session) {
                        next = null
                    }
                    next
                }
            }
        }

        get("/api/v1/events") {
            if (!call.isAuthorized()) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@get
            }
            val apiEnabled = environment.config.propertyOrNull("karani.api.get-events.enabled")?.getString()?.toBoolean() ?: true
            if (!apiEnabled) {
                call.respondText("API disabled", status = HttpStatusCode.Forbidden)
                return@get
            }

            val defaultSize = environment.config.propertyOrNull("karani.api.get-events.default-page-size")?.getString()?.toInt() ?: 50
            val limit = call.parameters["limit"]?.toIntOrNull() ?: defaultSize

            val events = eventRepo.getEvents(limit)
            val jsonList = events.map { event ->
                """{"id":"${event.id}","endpointId":"${event.endpointId}","status":"${event.status}","receivedAt":"${event.receivedAt}"}"""
            }
            val json = "[${jsonList.joinToString(",")}]"
            call.respondText(json, ContentType.Application.Json)
        }

        get("/webhook/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            if (path.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Missing webhook path")
                return@get
            }

            val config = configRepo.getEndpointConfigByPath(path)
            if (config == null) {
                call.respondText("Webhook endpoint not found", status = HttpStatusCode.NotFound)
                return@get
            }
            if (!config.enabled) {
                call.respondText("""{"status":"ignored","reason":"Endpoint is disabled"}""", ContentType.Application.Json, HttpStatusCode.Accepted)
                return@get
            }
            if (config.rateLimitRpm > 0) {
                val allowed = com.dall06.karani.adapters.security.RateLimiter.isAllowed(config.id, config.rateLimitRpm)
                if (!allowed) {
                    call.respondText("Rate limit exceeded", status = HttpStatusCode.TooManyRequests)
                    return@get
                }
            }

            val queryParams = call.request.queryParameters.toMap().mapValues { it.value.first() }
            val combinedHeaders = call.request.headers.toMap().mapValues { it.value.first() }.toMutableMap()
            queryParams.forEach { (key, value) ->
                combinedHeaders["query:$key"] = value
            }

            val body = ""
            val contentType = "text/plain"

            when (val result = ingestUseCase.ingest(path, combinedHeaders, body, contentType)) {
                is IngestResult.EndpointNotFound -> {
                    call.respondText("Webhook endpoint not found", status = HttpStatusCode.NotFound)
                }
                is IngestResult.Declined -> {
                    val json = """{"status":"ignored","reason":"${result.reason.replace("\"", "\\\"")}"}"""
                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.Accepted)
                }
                is IngestResult.CustomResponded -> {
                    result.headers.forEach { (key, value) ->
                        call.response.header(key, value)
                    }
                    val resBody = result.body ?: ""
                    call.respondText(resBody, status = HttpStatusCode.fromValue(result.statusCode))
                }
                is IngestResult.Accepted -> {
                    val json = """{"status":"accepted","id":"${result.eventId}"}"""
                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.Accepted)

                    backgroundScope.launch {
                        try {
                            dispatcherUseCase.dispatch(result.event)
                        } catch (e: Exception) {
                            application.log.error("Failed to asynchronously dispatch event: ${result.eventId}", e)
                        }
                    }
                }
                is IngestResult.InvalidPayload -> {
                    call.respondText(result.reason, status = HttpStatusCode.BadRequest)
                }
            }
        }

        post("/webhook/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            if (path.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Missing webhook path")
                return@post
            }

            val config = configRepo.getEndpointConfigByPath(path)
            if (config == null) {
                call.respondText("Webhook endpoint not found", status = HttpStatusCode.NotFound)
                return@post
            }
            if (!config.enabled) {
                call.respondText("""{"status":"ignored","reason":"Endpoint is disabled"}""", ContentType.Application.Json, HttpStatusCode.Accepted)
                return@post
            }
            if (config.rateLimitRpm > 0) {
                val allowed = com.dall06.karani.adapters.security.RateLimiter.isAllowed(config.id, config.rateLimitRpm)
                if (!allowed) {
                    call.respondText("Rate limit exceeded", status = HttpStatusCode.TooManyRequests)
                    return@post
                }
            }
            if (config.maxBodySizeBytes > 0) {
                val contentLength = call.request.header(io.ktor.http.HttpHeaders.ContentLength)?.toLongOrNull() ?: 0L
                if (contentLength > config.maxBodySizeBytes) {
                    call.respondText("Payload size exceeds limit", status = HttpStatusCode.PayloadTooLarge)
                    return@post
                }
            }

            val queryParams = call.request.queryParameters.toMap().mapValues { it.value.first() }
            val combinedHeaders = call.request.headers.toMap().mapValues { it.value.first() }.toMutableMap()
            queryParams.forEach { (key, value) ->
                combinedHeaders["query:$key"] = value
            }

            val body = call.receiveText()
            if (config.maxBodySizeBytes > 0 && body.toByteArray(Charsets.UTF_8).size > config.maxBodySizeBytes) {
                call.respondText("Payload size exceeds limit", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val contentType = call.request.contentType().toString()

            when (val result = ingestUseCase.ingest(path, combinedHeaders, body, contentType)) {
                is IngestResult.EndpointNotFound -> {
                    call.respondText("Webhook endpoint not found", status = HttpStatusCode.NotFound)
                }
                is IngestResult.Declined -> {
                    val json = """{"status":"ignored","reason":"${result.reason.replace("\"", "\\\"")}"}"""
                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.Accepted)
                }
                is IngestResult.CustomResponded -> {
                    result.headers.forEach { (key, value) ->
                        call.response.header(key, value)
                    }
                    val resBody = result.body ?: ""
                    call.respondText(resBody, status = HttpStatusCode.fromValue(result.statusCode))
                }
                is IngestResult.Accepted -> {
                    val json = """{"status":"accepted","id":"${result.eventId}"}"""
                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.Accepted)

                    backgroundScope.launch {
                        try {
                            dispatcherUseCase.dispatch(result.event)
                        } catch (e: Exception) {
                            application.log.error("Failed to asynchronously dispatch event: ${result.eventId}", e)
                        }
                    }
                }
                is IngestResult.InvalidPayload -> {
                    call.respondText(result.reason, status = HttpStatusCode.BadRequest)
                }
            }
        }

        post("/api/v1/config/endpoints") {
            if (!call.isAuthorized()) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@post
            }
            try {
                val node = mapper.readTree(call.receiveText())
                val name = node.get("name")?.asText() ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing name")
                val path = node.get("path")?.asText() ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing path")
                val secret = node.get("secret")?.asText()
                val enabled = node.get("enabled")?.asBoolean() ?: true
                val persistEvents = node.get("persistEvents")?.asBoolean() ?: true
                val defaultActionStr = node.get("defaultAction")?.asText() ?: "ALLOW"
                val securityTypeStr = node.get("securityType")?.asText() ?: "NONE"
                val rateLimitRpm = node.get("rateLimitRpm")?.asInt() ?: 0
                val maxBodySizeBytes = node.get("maxBodySizeBytes")?.asLong() ?: 0L

                val config = com.dall06.karani.domain.EndpointConfiguration(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    path = path,
                    secret = secret,
                    enabled = enabled,
                    createdAt = java.time.Instant.now(),
                    persistEvents = persistEvents,
                    defaultAction = com.dall06.karani.domain.DefaultAction.valueOf(defaultActionStr),
                    securityType = com.dall06.karani.domain.SecurityType.valueOf(securityTypeStr),
                    rateLimitRpm = rateLimitRpm,
                    maxBodySizeBytes = maxBodySizeBytes
                )

                configRepo.saveEndpointConfig(config)
                call.respondText("""{"status":"created","id":"${config.id}"}""", ContentType.Application.Json, HttpStatusCode.Created)
            } catch (e: Exception) {
                call.respondText("""{"error":"${e.message?.replace("\"", "\\\"")}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        post("/api/v1/config/endpoints/{id}/rules") {
            if (!call.isAuthorized()) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@post
            }
            val endpointId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing endpoint ID")
            try {
                val node = mapper.readTree(call.receiveText())
                val priority = node.get("priority")?.asInt() ?: 1
                val source = node.get("source")?.asText() ?: "HEADER"
                val expression = node.get("expression")?.asText() ?: ""
                val operator = node.get("operator")?.asText() ?: "EQUALS"
                val expectedValue = node.get("expectedValue")?.asText()
                val action = node.get("action")?.asText() ?: "ACCEPT"

                var customResponse: com.dall06.karani.domain.CustomResponse? = null
                val respNode = node.get("customResponse")
                if (respNode != null) {
                    val statusCode = respNode.get("statusCode")?.asInt() ?: 200
                    val headersMap = mutableMapOf<String, String>()
                    respNode.get("headers")?.fields()?.forEach { (k, v) ->
                        headersMap[k] = v.asText()
                    }
                    val bodyTemplate = respNode.get("bodyTemplate")?.asText()
                    customResponse = com.dall06.karani.domain.CustomResponse(statusCode, headersMap, bodyTemplate)
                }

                val rule = com.dall06.karani.domain.IngressRule(
                    id = java.util.UUID.randomUUID().toString(),
                    endpointId = endpointId,
                    priority = priority,
                    source = com.dall06.karani.domain.RuleSource.valueOf(source),
                    expression = expression,
                    operator = com.dall06.karani.domain.Operator.valueOf(operator),
                    expectedValue = expectedValue,
                    action = com.dall06.karani.domain.RuleAction.valueOf(action),
                    customResponse = customResponse
                )

                configRepo.saveIngressRule(rule)
                call.respondText("""{"status":"created","id":"${rule.id}"}""", ContentType.Application.Json, HttpStatusCode.Created)
            } catch (e: Exception) {
                call.respondText("""{"error":"${e.message?.replace("\"", "\\\"")}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        post("/api/v1/config/endpoints/{id}/destinations") {
            if (!call.isAuthorized()) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@post
            }
            val endpointId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing endpoint ID")
            try {
                val node = mapper.readTree(call.receiveText())
                val name = node.get("name")?.asText() ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing name")
                val type = node.get("type")?.asText() ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing type")
                val enabled = node.get("enabled")?.asBoolean() ?: true

                val settingsMap = mutableMapOf<String, String>()
                node.get("settings")?.fields()?.forEach { (k, v) ->
                    settingsMap[k] = v.asText()
                }

                val routingCondition = node.get("routingCondition")?.asText()
                val transformationTemplate = node.get("transformationTemplate")?.asText()

                val destination = com.dall06.karani.domain.Destination(
                    id = java.util.UUID.randomUUID().toString(),
                    endpointId = endpointId,
                    name = name,
                    type = com.dall06.karani.domain.DestinationType.valueOf(type),
                    enabled = enabled,
                    settings = settingsMap,
                    routingCondition = routingCondition,
                    transformationTemplate = transformationTemplate
                )

                configRepo.saveDestination(destination)
                call.respondText("""{"status":"created","id":"${destination.id}"}""", ContentType.Application.Json, HttpStatusCode.Created)
            } catch (e: Exception) {
                call.respondText("""{"error":"${e.message?.replace("\"", "\\\"")}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        delete("/api/v1/config/endpoints/{id}") {
            if (!call.isAuthorized()) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@delete
            }
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing ID")
            try {
                val deleted = configRepo.deleteEndpointConfig(id)
                if (deleted) {
                    call.respondText("""{"status":"deleted"}""", ContentType.Application.Json, HttpStatusCode.OK)
                    return@delete
                }
                call.respond(HttpStatusCode.NotFound, "Endpoint config not found")
            } catch (e: Exception) {
                call.respondText("""{"error":"${e.message?.replace("\"", "\\\"")}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        put("/api/v1/config/endpoints/{id}") {
            if (!call.isAuthorized()) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@put
            }
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing ID")
            try {
                val node = mapper.readTree(call.receiveText())
                val name = node.get("name")?.asText() ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing name")
                val path = node.get("path")?.asText() ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing path")
                val secret = node.get("secret")?.asText()
                val enabled = node.get("enabled")?.asBoolean() ?: true
                val persistEvents = node.get("persistEvents")?.asBoolean() ?: true
                val defaultActionStr = node.get("defaultAction")?.asText() ?: "ALLOW"
                val securityTypeStr = node.get("securityType")?.asText() ?: "NONE"
                val rateLimitRpm = node.get("rateLimitRpm")?.asInt() ?: 0
                val maxBodySizeBytes = node.get("maxBodySizeBytes")?.asLong() ?: 0L

                val existing = configRepo.getEndpointConfigById(id) ?: return@put call.respond(HttpStatusCode.NotFound, "Endpoint config not found")

                val updatedConfig = com.dall06.karani.domain.EndpointConfiguration(
                    id = id,
                    name = name,
                    path = path,
                    secret = secret,
                    enabled = enabled,
                    createdAt = existing.createdAt,
                    persistEvents = persistEvents,
                    defaultAction = com.dall06.karani.domain.DefaultAction.valueOf(defaultActionStr),
                    securityType = com.dall06.karani.domain.SecurityType.valueOf(securityTypeStr),
                    rateLimitRpm = rateLimitRpm,
                    maxBodySizeBytes = maxBodySizeBytes
                )

                configRepo.updateEndpointConfig(updatedConfig)
                call.respondText("""{"status":"updated"}""", ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respondText("""{"error":"${e.message?.replace("\"", "\\\"")}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        get("/api/v1/events/{id}") {
            if (!call.isAuthorized()) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@get
            }
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing ID")
            try {
                val event = eventRepo.getEventById(id) ?: return@get call.respond(HttpStatusCode.NotFound, "Event not found")
                val attempts = eventRepo.getAttemptsForEvent(id)

                val attemptsJsonList = attempts.map { attempt ->
                    val errorMsgStr = attempt.errorMessage
                    var errorMsgVal = "null"
                    if (errorMsgStr != null) {
                        errorMsgVal = "\"${errorMsgStr.replace("\"", "\\\"")}\""
                    }
                    """{"id":"${attempt.id}","destinationId":"${attempt.destinationId}","status":"${attempt.status}","attemptNumber":${attempt.attemptNumber},"responseStatusCode":${attempt.responseStatusCode},"errorMessage":$errorMsgVal,"executedAt":"${attempt.executedAt}"}"""
                }
                val attemptsJson = "[${attemptsJsonList.joinToString(",")}]"

                val isJsonPayload = event.rawPayload.startsWith("{") || event.rawPayload.startsWith("[")
                var payloadVal = "\"${event.rawPayload.replace("\"", "\\\"")}\""
                if (isJsonPayload) {
                    payloadVal = event.rawPayload
                }
                val json = """{"id":"${event.id}","endpointId":"${event.endpointId}","status":"${event.status}","receivedAt":"${event.receivedAt}","contentType":"${event.contentType}","payload":$payloadVal,"attempts":$attemptsJson}"""
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respondText("""{"error":"${e.message?.replace("\"", "\\\"")}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
    }
}