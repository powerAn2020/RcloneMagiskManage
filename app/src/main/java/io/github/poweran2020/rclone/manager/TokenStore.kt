package io.github.poweran2020.rclone.manager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the Gateway bearer token encrypted by an Android Keystore key. */
class TokenStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("gateway-token", Context.MODE_PRIVATE)
    private val alias = "rclone-manager-gateway-token"
    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun read(): String {
        val raw = prefs.getString("value", null) ?: return ""
        return runCatching { Cipher.getInstance("AES/GCM/NoPadding").let { c -> c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP))); String(c.doFinal(Base64.decode(raw, Base64.NO_WRAP)), StandardCharsets.UTF_8) } }.getOrDefault("")
    }
    fun write(value: String) {
        if (value.isBlank()) { prefs.edit().clear().apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("value", Base64.encodeToString(c.doFinal(value.toByteArray()), Base64.NO_WRAP)).putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).apply()
    }
}
