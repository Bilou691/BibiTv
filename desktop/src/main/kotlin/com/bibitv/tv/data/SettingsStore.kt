package com.bibitv.tv.data

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Réglages de l'application (settings.properties dans le dossier de données).
 * Le mot de passe Xtream est chiffré (AES-GCM) avec une clé propre à l'ordinateur,
 * elle-même protégée par le système : DPAPI sous Windows, trousseau sous macOS,
 * fichier lisible par l'utilisateur seul sous Linux.
 */
object SettingsStore {
    private val file = AtomicFile(File(Platform.dataDir, "settings.properties"))
    private val props = Properties()

    fun init() {
        runCatching { file.baseFile.inputStream().use { props.load(it) } }
    }

    private fun get(k: String): String? = props.getProperty(k)
    private fun put(k: String, v: String?) {
        if (v == null) props.remove(k) else props.setProperty(k, v)
        val sb = java.io.StringWriter()
        props.store(sb, "BibiTV")
        runCatching { file.writeText(sb.toString()) }
    }

    var serverUrl: String
        get() = get("server") ?: ""
        set(v) = put("server", v)

    var username: String
        get() = get("user") ?: ""
        set(v) = put("user", v)

    var volume: Int
        get() = get("volume")?.toIntOrNull() ?: 100
        set(v) = put("volume", v.toString())

    /** Format d'image : 0 = original, 1 = zoom (remplit), 2 = étiré. */
    var videoFormat: Int
        get() = get("video_format")?.toIntOrNull() ?: 0
        set(v) = put("video_format", v.toString())

    /** Dossier des téléchargements. */
    var downloadDir: File
        get() = get("download_dir")?.let(::File) ?: Platform.defaultDownloadDir
        set(v) = put("download_dir", v.absolutePath)

    var password: String?
        get() {
            val data = get("pwd") ?: return null
            return try {
                val raw = Base64.getDecoder().decode(data)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
        set(v) {
            if (v == null) return put("pwd", null)
            try {
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, iv))
                put("pwd", Base64.getEncoder().encodeToString(iv + cipher.doFinal(v.toByteArray(Charsets.UTF_8))))
            } catch (_: Exception) {
                put("pwd", null)
            }
        }

    // ---------- Clé de chiffrement, protégée par le système ----------

    private var cachedKey: ByteArray? = null

    @Synchronized
    private fun key(): ByteArray {
        cachedKey?.let { return it }
        val k = loadKey() ?: ByteArray(32).also { SecureRandom().nextBytes(it); saveKey(it) }
        cachedKey = k
        return k
    }

    private val keyFile get() = File(Platform.dataDir, "key.bin")

    private fun loadKey(): ByteArray? = runCatching {
        when (Platform.os) {
            Os.Windows -> com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(keyFile.readBytes())
            Os.Mac -> {
                val p = ProcessBuilder("security", "find-generic-password", "-s", "BibiTV", "-a", "cle", "-w").start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out.isNotEmpty()) Base64.getDecoder().decode(out) else null
            }
            Os.Linux -> keyFile.takeIf { it.exists() }?.readBytes()
        }
    }.getOrNull()?.takeIf { it.size == 32 }

    private fun saveKey(k: ByteArray) {
        runCatching {
            when (Platform.os) {
                Os.Windows -> keyFile.writeBytes(com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(k))
                Os.Mac -> ProcessBuilder(
                    "security", "add-generic-password", "-U", "-s", "BibiTV", "-a", "cle", "-w", Base64.getEncoder().encodeToString(k),
                ).start().waitFor()
                Os.Linux -> {
                    keyFile.writeBytes(k)
                    keyFile.setReadable(false, false); keyFile.setReadable(true, true)
                    keyFile.setWritable(false, false); keyFile.setWritable(true, true)
                }
            }
        }
    }
}
