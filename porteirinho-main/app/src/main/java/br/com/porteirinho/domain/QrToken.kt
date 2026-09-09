package br.com.porteirinho.domain

import java.security.MessageDigest

data class QrToken(val credentialId: String, val rawValue: String) {
    val hash: String get() = sha256(rawValue)

    companion object {
        private const val Prefix = "porteirinho:v1:"

        fun parse(raw: String): QrToken? {
            if (!raw.startsWith(Prefix)) return null
            val parts = raw.split(':', limit = 4)
            if (parts.size != 4 || parts[2].isBlank() || parts[3].length < 8) return null
            return QrToken(credentialId = parts[2], rawValue = raw)
        }

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
