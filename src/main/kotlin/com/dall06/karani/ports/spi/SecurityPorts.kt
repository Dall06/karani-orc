package com.dall06.karani.ports.spi

import com.dall06.karani.domain.EndpointConfiguration
import com.dall06.karani.domain.SecurityType

interface WebhookSecurityValidator {
    val type: SecurityType
    fun validate(
        body: String,
        headers: Map<String, String>,
        config: EndpointConfiguration
    ): Boolean
}
