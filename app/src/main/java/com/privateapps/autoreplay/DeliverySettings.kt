package com.privateapps.autoreplay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class DeliveryMode { PHONE, UNLOCK, API }
data class DeliveryConfig(val mode: DeliveryMode = DeliveryMode.PHONE, val phoneId: String = "",
    val template: String = "", val language: String = "he", val version: String = "v27.0", val parameters: String = "")

object DeliveryValidation {
    fun api(c: DeliveryConfig) = c.phoneId.matches(Regex("[0-9]{5,30}")) &&
        c.template.matches(Regex("[a-z0-9_]{1,512}")) && c.language.matches(Regex("[a-z]{2,3}(_[A-Z]{2})?")) &&
        c.version.matches(Regex("v[0-9]{2}\\.0")) && c.parameters.length <= 2000
    fun pin(value: String) = value.matches(Regex("[0-9]{4,12}"))
}

/** Device secrets only; no plaintext fallback, backups, logs or saved-instance-state. */
class DeviceSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("device_secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("autoreplay.device.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("autoreplay.device.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun get(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun has(name: String) = prefs.contains(name)
    fun clear() { check(prefs.edit().clear().commit()) }
}

class DeliverySettings(context: Context) {
    private val prefs = context.getSharedPreferences("delivery", Context.MODE_PRIVATE)
    fun read() = DeliveryConfig(runCatching { DeliveryMode.valueOf(prefs.getString("mode", "PHONE")!!) }.getOrDefault(DeliveryMode.PHONE),
        prefs.getString("phoneId", "")!!, prefs.getString("template", "")!!, prefs.getString("language", "he")!!,
        prefs.getString("version", "v27.0")!!, prefs.getString("parameters", "")!!)
    fun save(config: DeliveryConfig) {
        require(config.mode != DeliveryMode.API || DeliveryValidation.api(config))
        check(prefs.edit().putString("mode", config.mode.name).putString("phoneId", config.phoneId)
            .putString("template", config.template).putString("language", config.language).putString("version", config.version)
            .putString("parameters", config.parameters).commit())
    }
    // Persist before the first keypress. A failure or process death requires explicit re-arming.
    fun arm() { check(prefs.edit().putBoolean("blocked",false).putBoolean("ownsUnlock",false).commit()) }
    fun blocked() = prefs.getBoolean("blocked",true)
    @Synchronized fun beginAttempt(): Boolean {
        if(blocked()) return false
        return prefs.edit().putBoolean("blocked",true).putBoolean("ownsUnlock",true).commit()
    }
    fun ownsUnlock() = prefs.getBoolean("ownsUnlock", false)
    fun finished(success: Boolean) { check(prefs.edit().putBoolean("ownsUnlock",false).putBoolean("blocked",!success).commit()) }
}
