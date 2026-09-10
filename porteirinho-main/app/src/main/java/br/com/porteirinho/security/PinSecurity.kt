package br.com.porteirinho.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinSecurity {
    private const val LocalIterations = 120_000
    private const val KeyLengthBits = 256

    data class Hash(val saltBase64: String, val hashBase64: String)

    fun createHash(pin: CharArray): Hash {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return Hash(
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            hashBase64 = Base64.getEncoder().encodeToString(derive(pin, salt, LocalIterations)),
        )
    }

    fun verify(
        pin: CharArray,
        encodedSalt: String,
        encodedHash: String,
        encoding: String = "BASE64",
        iterations: Int = LocalIterations,
    ): Boolean {
        val salt = decode(encodedSalt, encoding)
        val expected = decode(encodedHash, encoding)
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun decode(value: String, encoding: String): ByteArray = when (encoding.uppercase()) {
        "HEX" -> value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        else -> Base64.getDecoder().decode(value)
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KeyLengthBits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            pin.fill('\u0000')
        }
    }
}
