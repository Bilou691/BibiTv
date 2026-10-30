package com.bibitv.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Réglages de l'application. Le mot de passe Xtream est chiffré avec une clé
 * du Keystore Android (jamais lisible hors de cet appareil).
 */
object SettingsStore {
    private const val KEY_ALIAS = "bibitv_credentials"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v).apply()

    var username: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v).apply()

    var volume: Int
        get() = prefs.getInt("volume", 100)
        set(v) = prefs.edit().putInt("volume", v).apply()

    /** Ajuste la fréquence d'affichage de la TV à celle de la vidéo (24p fluide). */
    var matchFrameRate: Boolean
        get() = prefs.getBoolean("match_fps", true)
        set(v) = prefs.edit().putBoolean("match_fps", v).apply()

    /**
     * Rendu vidéo direct : l'image décodée par la TV est envoyée telle quelle à l'écran
     * (HDR conservé, noirs profonds). Désactivable si une vidéo ne s'affiche pas.
     */
    var directRendering: Boolean
        get() = prefs.getBoolean("direct_rendering", true)
        set(v) = prefs.edit().putBoolean("direct_rendering", v).apply()

    /** Format d'image : 0 = original (bandes noires possibles), 1 = zoom (remplit l'écran), 2 = étiré. */
    var videoFormat: Int
        get() = prefs.getInt("video_format", 0)
        set(v) = prefs.edit().putInt("video_format", v).apply()

    /** Destination des téléchargements : chemin d'un volume (clé USB…) ou URI d'un dossier choisi. */
    var downloadTarget: String?
        get() = prefs.getString("download_target", null)
        set(v) = prefs.edit().putString("download_target", v).apply()

    var password: String?
        get() {
            val data = prefs.getString("pwd", null) ?: return null
            return try {
                val raw = Base64.decode(data, Base64.NO_WRAP)
                val iv = raw.copyOfRange(0, 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
        set(v) {
            if (v == null) {
                prefs.edit().remove("pwd").apply()
                return
            }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val enc = cipher.iv + cipher.doFinal(v.toByteArray(Charsets.UTF_8))
                prefs.edit().putString("pwd", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
            } catch (e: Exception) {
                prefs.edit().remove("pwd").apply()
            }
        }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }
}
