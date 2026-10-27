package com.assetboard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The refresh token for one profile, encrypted with a key the Android Keystore keeps. The access
 * token never reaches disk. When the Keystore is unavailable (some test runners, a broken
 * provider) the token lives in memory only: syncing works until the process ends, and then the
 * user signs in again, rather than the token being written in the clear.
 */
class SessionStore(context: Context, private val profileId: String) {
    private val prefs = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)
    private val key = "refresh_$profileId"

    fun save(refreshToken: String) {
        // Shared across instances: a profile switch builds a new store before the Keystore has
        // necessarily written anything, and a test runner may have no Keystore at all.
        memory[profileId] = refreshToken
        // commit, not apply: this token is the only way back into the account after a process
        // death, and losing it just makes the user sign in again.
        TokenCipher.encrypt(refreshToken)?.let { prefs.edit(commit = true) { putString(key, it) } }
    }

    fun load(): String? {
        memory[profileId]?.let { return it }
        val stored = prefs.getString(key, null) ?: return null
        val plain = TokenCipher.decrypt(stored) ?: return null
        memory[profileId] = plain
        return plain
    }

    fun has(): Boolean = load() != null

    fun clear() {
        memory.remove(profileId)
        prefs.edit { remove(key) }
    }

    companion object {
        private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}

private object TokenCipher {
    private const val ALIAS = "assetboard.session.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    fun encrypt(plain: String): String? = runCatching {
        val key = key() ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(cipher.iv + body)
    }.getOrNull()

    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        if (bytes.size <= IV_BYTES) return null
        val key = key() ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}