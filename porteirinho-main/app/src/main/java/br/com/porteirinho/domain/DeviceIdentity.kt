package br.com.porteirinho.domain

import android.content.Context
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    val publicId: String
        get() = preferences.getString(Key, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(Key, it).commit()
        }

    private companion object {
        const val Key = "public_id"
    }
}
