package com.bibitv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

data class UpdateInfo(val version: String, val tag: String, val url: String, val sha256: String?, val size: Long, val notes: String?)

/**
 * Mise à jour intégrée depuis la dernière release GitHub « v… » (commune macOS / Linux / Windows) :
 * téléchargement du paquet du système, contrôle SHA-256, puis installation.
 */
object UpdateManager {
    private const val REPO = "Bilou691/BibiTv"
    private val json = Json { ignoreUnknownKeys = true }

    val currentVersion: String get() = AppInfo.version

    /** Nom du fichier publié pour ce système. */
    val assetName: String = when (Platform.os) {
        Os.Mac -> "BibiTV-macos-arm64.dmg"
        Os.Linux -> "BibiTV-linux-amd64.deb"
        Os.Windows -> "BibiTv-win-x64.zip"
    }

    /** Lien fixe vers la dernière version pour ce système. */
    val latestLink: String get() = "https://github.com/$REPO/releases/latest/download/$assetName"

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        XtreamClient.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val o = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val tag = (o["tag_name"] as? JsonPrimitive)?.content ?: return@withContext null
            if (!tag.startsWith("v")) return@withContext null
            val version = tag.removePrefix("v")
            if (compare(version, currentVersion) <= 0) return@withContext null
            for (a in o["assets"]?.jsonArray ?: return@withContext null) {
                val asset = a.jsonObject
                if ((asset["name"] as? JsonPrimitive)?.content != assetName) continue
                val digest = (asset["digest"] as? JsonPrimitive)?.content
                return@withContext UpdateInfo(
                    version = version,
                    tag = tag,
                    url = (asset["browser_download_url"] as JsonPrimitive).content,
                    sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    size = (asset["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                    notes = (o["body"] as? JsonPrimitive)?.content,
                )
            }
            null
        }
    }

    /** Télécharge le paquet et le vérifie ; renvoie le fichier prêt à installer. */
    suspend fun download(update: UpdateInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(System.getProperty("java.io.tmpdir"), "bibitv-update").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } }
        val file = File(dir, assetName)
        val digest = MessageDigest.getInstance("SHA-256")
        XtreamClient.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            .newCall(Request.Builder().url(update.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: update.size
                file.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { progress(percent / 100f) }
                        }
                    }
                }
            }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (update.sha256 != null && !hash.equals(update.sha256, true)) {
            file.delete()
            throw IllegalStateException("Fichier corrompu (empreinte SHA-256 différente). Réessayez.")
        }
        file
    }

    /**
     * Installe la mise à jour. Renvoie un message à afficher, ou null si l'app va se fermer.
     * - Windows : remplacement des fichiers puis redémarrage automatique ;
     * - macOS : ouverture de l'image disque (glisser BibiTV dans Applications) ;
     * - Linux : ouverture du paquet dans le gestionnaire de logiciels.
     */
    fun install(file: File): String? = when (Platform.os) {
        Os.Windows -> installWindows(file)
        Os.Mac -> {
            Platform.open(file)
            "Glissez BibiTV dans le dossier Applications pour remplacer l'ancienne version, puis relancez-la."
        }
        Os.Linux -> {
            Platform.open(file)
            "Installez le paquet ouvert (ou : sudo apt install ${file.absolutePath}), puis relancez BibiTV."
        }
    }

    private fun installWindows(zip: File): String? {
        val exe = File(System.getProperty("jpackage.app-path") ?: return "Mise à jour automatique indisponible : ouvrez $latestLink")
        val appDir = exe.parentFile
        val work = zip.parentFile
        val extracted = File(work, "extrait").apply { mkdirs() }
        java.util.zip.ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val out = File(extracted, e.name)
                if (!out.canonicalPath.startsWith(extracted.canonicalPath)) continue
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
        if (!File(extracted, "BibiTv.exe").exists()) throw IllegalStateException("L'archive de mise à jour ne contient pas BibiTv.exe.")
        val pid = ProcessHandle.current().pid()
        val script = File(System.getProperty("java.io.tmpdir"), "BibiTvUpdate.cmd")
        script.writeText(
            listOf(
                "@echo off",
                "chcp 65001 >nul",
                ":wait",
                "tasklist /FI \"PID eq $pid\" 2>nul | find \"$pid\" >nul && (timeout /t 1 /nobreak >nul & goto wait)",
                "robocopy \"${extracted.absolutePath}\" \"${appDir.absolutePath}\" /E /R:10 /W:1 /NFL /NDL /NJH /NJS /NP >nul",
                "start \"\" \"${exe.absolutePath}\"",
                "rmdir /s /q \"${work.absolutePath}\"",
                "(goto) 2>nul & del \"%~f0\"",
            ).joinToString("\r\n"),
        )
        ProcessBuilder("cmd.exe", "/c", script.absolutePath).directory(script.parentFile).start()
        Runtime.getRuntime().halt(0)
        return null
    }

    private fun compare(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
