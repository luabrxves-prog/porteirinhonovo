package br.com.porteirinho.sync

import android.content.Context
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.security.SecureStore

class BackendDeviceCredentials(context: Context) {
    private val secureStore = SecureStore(context)
    private val identity = DeviceIdentity(context)

    data class Credentials(val deviceId: String, val secret: String)

    fun get(): Credentials? {
        val deviceId = secureStore.get(KeyDeviceId)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
        val secret = secureStore.get(KeyDeviceSecret)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
            ?: secureStore.get(LegacyTokenKey)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
        if (deviceId != null && secret != null) return Credentials(deviceId, secret)

        // Compatibility for installations where the persisted installation UUID was also the backend device id.
        val installationId = identity.publicId
        return secret?.let { Credentials(installationId, it) }
    }

    fun save(deviceId: String, secret: String) {
        secureStore.put(KeyDeviceId, deviceId.toByteArray(Charsets.UTF_8))
        secureStore.put(KeyDeviceSecret, secret.toByteArray(Charsets.UTF_8))
    }

    fun clear() {
        secureStore.put(KeyDeviceId, ByteArray(0))
        secureStore.put(KeyDeviceSecret, ByteArray(0))
    }

    companion object {
        const val KeyDeviceId = "backend_device_id"
        const val KeyDeviceSecret = "backend_device_secret"
        const val LegacyTokenKey = "device_api_token"
    }
}
