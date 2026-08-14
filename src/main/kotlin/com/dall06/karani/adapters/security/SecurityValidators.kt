package com.dall06.karani.adapters.security

import com.dall06.karani.domain.EndpointConfiguration
import com.dall06.karani.domain.SecurityType
import com.dall06.karani.ports.spi.WebhookSecurityValidator
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacSecurityValidator : WebhookSecurityValidator {
    override val type = SecurityType.HMAC_SHA256

    override fun validate(body: String, headers: Map<String, String>, config: EndpointConfiguration): Boolean {
        val secretConfig = config.secret ?: return false
        val secret = SecretResolver.resolve(secretConfig)
        
        val signature = headers["x-hub-signature-256"] 
            ?: headers["X-Hub-Signature-256"]
            ?: headers["stripe-signature"]
            ?: headers["Stripe-Signature"]
            ?: return false

        val cleanSignature = signature.removePrefix("sha256=")

        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKeySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKeySpec)
            val hashBytes = mac.doFinal(body.toByteArray(Charsets.UTF_8))
            val computedHash = hashBytes.joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(computedHash.toByteArray(), cleanSignature.toByteArray())
        } catch (e: Exception) {
            false
        }
    }
}

class EcdsaSecurityValidator : WebhookSecurityValidator {
    override val type = SecurityType.ECDSA_SHA256

    override fun validate(body: String, headers: Map<String, String>, config: EndpointConfiguration): Boolean {
        val secretConfig = config.secret ?: return false
        val publicKeyPem = SecretResolver.resolve(secretConfig)
        
        val signatureHeaderName = headers["signature-header"] ?: "Digital-Signature"
        val rawSignature = headers[signatureHeaderName] ?: headers["digital-signature"] ?: return false

        return try {
            val publicKeyBytes = Base64.getDecoder().decode(
                publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\\s".toRegex(), "")
            )
            
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)
            
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(body.toByteArray(Charsets.UTF_8))
            
            val signatureBytes = Base64.getDecoder().decode(rawSignature)
            verifier.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
}
