package com.dall06.karani.adapters.security

import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object SecretResolver {

    private val masterKey: String? by lazy {
        System.getenv("KARANI_MASTER_KEY")
    }

    fun resolve(secretConfig: String): String {
        if (secretConfig.startsWith("file://")) {
            val filePath = secretConfig.removePrefix("file://")
            return File(filePath).readText(Charsets.UTF_8).trim()
        }
        
        if (secretConfig.startsWith("env://")) {
            val envName = secretConfig.removePrefix("env://")
            return System.getenv(envName) ?: throw IllegalArgumentException("Environment variable $envName not found")
        }
        
        if (secretConfig.startsWith("enc:")) {
            val cipherText = secretConfig.removePrefix("enc:")
            return decryptAes(cipherText)
        }
        
        return secretConfig
    }

    private fun decryptAes(cipherText: String): String {
        val key = masterKey ?: throw IllegalStateException("KARANI_MASTER_KEY env variable is required to decrypt database secrets")
        val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(16)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        val decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(cipherText))
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
