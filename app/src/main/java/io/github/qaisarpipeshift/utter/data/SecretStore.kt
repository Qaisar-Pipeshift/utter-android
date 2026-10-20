package io.github.qaisarpipeshift.utter.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.qaisarpipeshift.utter.diag.Diag
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The server API key, encrypted with a non-exportable AndroidKeystore key (AES-256-GCM), docs/spec.md §10.
 *
 * Only ciphertext and its IV are ever written to SharedPreferences. If the Keystore entry is gone (restore,
 * lock-screen reset, OEM bug) decryption fails closed: [load] returns null and records an event kind only.
 *
 * Why a memory cache: every take reads the config on the main thread, and a Keystore decrypt is slow enough to show
 * as a stutter on the first chunk. The plaintext never outlives the process, is invalidated by [save] and [clear],
 * and is never logged.
 *
 * A decrypt failure is deliberately not cached: a transient Keystore error (an OEM hiccup, a locked profile) would
 * otherwise keep the user locked out until they re-entered the key. Only a definite "no key stored" is remembered.
 */
class SecretStore(context: Context, private val diag: Diag = Diag()) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The decrypted key, or null when there is none; [loaded] tells "not decrypted yet" apart from "no key". */
    @Volatile
    private var cached: String? = null

    /** True only for a definite answer: a key was kept, or no key is stored. A failure leaves it false to retry. */
    @Volatile
    private var loaded = false

    fun save(key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        cached = key
        loaded = true
    }

    fun load(): String? {
        cached?.let { return it }
        if (loaded) return null
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: run {
            loaded = true
            return null
        }
        val iv = prefs.getString(KEY_IV, null) ?: run {
            loaded = true
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val key = String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
            cached = key
            loaded = true
            key
        } catch (failure: Exception) {
            // Deliberately not cached: the next call retries the Keystore instead of locking the user out.
            diag.event("secret_unreadable", failure.javaClass.simpleName)
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
        cached = null
        loaded = true
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) return existing.secretKey

        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "utter_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val PREFS = "secret"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_IV = "api_key_iv"
    }
}
