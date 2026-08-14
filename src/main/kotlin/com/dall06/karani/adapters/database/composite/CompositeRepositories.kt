package com.dall06.karani.adapters.database.composite

import com.dall06.karani.domain.*
import com.dall06.karani.ports.spi.ConfigurationRepository
import com.dall06.karani.ports.spi.EventRepository
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

class CompositeConfigurationRepository(
    private val repositories: List<ConfigurationRepository>
) : ConfigurationRepository {

    override suspend fun getEndpointConfigByPath(path: String): EndpointConfiguration? {
        for (repo in repositories) {
            try {
                val config = repo.getEndpointConfigByPath(path)
                if (config != null) return config
            } catch (e: Exception) {
                // Continuar silenciosamente buscando en la siguiente réplica
            }
        }
        return null
    }

    override suspend fun getEndpointConfigById(id: String): EndpointConfiguration? {
        for (repo in repositories) {
            try {
                val config = repo.getEndpointConfigById(id)
                if (config != null) return config
            } catch (e: Exception) {
                // Continuar silenciosamente buscando en la siguiente réplica
            }
        }
        return null
    }

    override suspend fun getIngressRulesForEndpoint(endpointId: String): List<IngressRule> {
        for (repo in repositories) {
            try {
                val rules = repo.getIngressRulesForEndpoint(endpointId)
                if (rules.isNotEmpty()) return rules
            } catch (e: Exception) {
                // Continuar silenciosamente buscando en la siguiente réplica
            }
        }
        return emptyList()
    }

    override suspend fun getDestinationsForEndpoint(endpointId: String): List<Destination> {
        for (repo in repositories) {
            try {
                val dests = repo.getDestinationsForEndpoint(endpointId)
                if (dests.isNotEmpty()) return dests
            } catch (e: Exception) {
                // Continuar silenciosamente buscando en la siguiente réplica
            }
        }
        return emptyList()
    }

    override suspend fun saveEndpointConfig(config: EndpointConfiguration): EndpointConfiguration {
        kotlinx.coroutines.supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        repo.saveEndpointConfig(config)
                    } catch (e: Exception) {
                        // Continuar silenciosamente en otras réplicas
                    }
                }
            }.joinAll()
        }
        return config
    }

    override suspend fun saveIngressRule(rule: IngressRule): IngressRule {
        kotlinx.coroutines.supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        repo.saveIngressRule(rule)
                    } catch (e: Exception) {
                        // Continuar silenciosamente en otras réplicas
                    }
                }
            }.joinAll()
        }
        return rule
    }

    override suspend fun saveDestination(destination: Destination): Destination {
        kotlinx.coroutines.supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        repo.saveDestination(destination)
                    } catch (e: Exception) {
                        // Continuar silenciosamente en otras réplicas
                    }
                }
            }.joinAll()
        }
        return destination
    }

    override suspend fun deleteEndpointConfig(id: String): Boolean {
        var deleted = false
        kotlinx.coroutines.supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        val res = repo.deleteEndpointConfig(id)
                        if (res) deleted = true
                    } catch (e: Exception) {
                        // Continuar
                    }
                }
            }.joinAll()
        }
        return deleted
    }

    override suspend fun updateEndpointConfig(config: EndpointConfiguration): EndpointConfiguration {
        kotlinx.coroutines.supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        repo.updateEndpointConfig(config)
                    } catch (e: Exception) {
                        // Continuar
                    }
                }
            }.joinAll()
        }
        return config
    }
}

class CompositeEventRepository(
    private val repositories: List<EventRepository>
) : EventRepository {

    override suspend fun saveEvent(event: WebhookEvent): WebhookEvent {
        supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        repo.saveEvent(event)
                    } catch (e: Exception) {
                        // Continuar para evitar cancelar escrituras en bases de datos sanas
                    }
                }
            }.joinAll()
        }
        return event
    }

    override suspend fun getEventById(eventId: String): WebhookEvent? {
        for (repo in repositories) {
            try {
                val event = repo.getEventById(eventId)
                if (event != null) return event
            } catch (e: Exception) {
                // Continuar silenciosamente buscando en la siguiente réplica
            }
        }
        return null
    }

    override suspend fun getEvents(limit: Int): List<WebhookEvent> {
        for (repo in repositories) {
            try {
                val list = repo.getEvents(limit)
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                // Continuar silenciosamente buscando en la siguiente réplica
            }
        }
        return emptyList()
    }

    override suspend fun updateEventStatus(eventId: String, status: EventStatus) {
        supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        repo.updateEventStatus(eventId, status)
                    } catch (e: Exception) {
                        // Continuar silenciosamente buscando en la siguiente réplica
                    }
                }
            }.joinAll()
        }
    }

    override suspend fun saveAttempt(attempt: DispatchAttempt): DispatchAttempt {
        supervisorScope {
            repositories.map { repo ->
                launch {
                    try {
                        repo.saveAttempt(attempt)
                    } catch (e: Exception) {
                        // Continuar silenciosamente buscando en la siguiente réplica
                    }
                }
            }.joinAll()
        }
        return attempt
    }

    override suspend fun getAttemptsForEvent(eventId: String): List<DispatchAttempt> {
        for (repo in repositories) {
            try {
                val attempts = repo.getAttemptsForEvent(eventId)
                if (attempts.isNotEmpty()) return attempts
            } catch (e: Exception) {
                // Continuar
            }
        }
        return emptyList()
    }
}
