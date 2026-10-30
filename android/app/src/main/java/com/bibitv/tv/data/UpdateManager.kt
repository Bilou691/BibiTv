package com.bibitv.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bibitv.tv.BuildConfig
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
 * Mise à jour intégrée depuis les releases GitHub « android-v… » du dépôt :
 * téléchargement de l'APK, contrôle SHA-256, puis installation par le système.
 */
object UpdateManager {
    private const val REPO = "Bilou691/BibiTv"
    private const val ASSET = "BibiTV-androidtv.apk"
    private val json = Json { ignoreUnknownKeys = true }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases?per_page=30")
            .header("Accept", "application/vnd.github+json").build()
        XtreamClient.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val releases = json.parseToJsonElement(resp.body!!.string()).jsonArray
            for (r in releases) {
                val o = r.jsonObject
                val tag = (o["tag_name"] as? JsonPrimitive)?.content ?: continue
                if (!tag.startsWith("android-v")) continue
                if ((o["draft"] as? JsonPrimitive)?.content == "true") continue
                val version = tag.removePrefix("android-v")
                if (compare(version, currentVersion) <= 0) return@withContext null
                for (a in o["assets"]?.jsonArray ?: return@withContext null) {
                    val asset = a.jsonObject
                    if ((asset["name"] as? JsonPrimitive)?.content != ASSET) continue
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
                return@withContext null
            }
            null
        }
    }

    /** Télécharge l'APK et le vérifie ; renvoie le fichier prêt à installer. */
    suspend fun download(context: Context, update: UpdateInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, ASSET)
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

    /** L'installation d'APK doit être autorisée pour BibiTV (Android 8+). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /**
     * Vrai si l'APK téléchargé n'est pas signé avec la même clé que l'application installée :
     * Android refuserait alors la mise à jour (« L'application n'a pas pu être installée »).
     */
    @Suppress("DEPRECATION")
    fun signatureMismatch(context: Context, apk: File): Boolean = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else android.content.pm.PackageManager.GET_SIGNATURES
        fun digests(pi: android.content.pm.PackageInfo?): Set<String>? {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi?.signingInfo?.apkContentsSigners else pi?.signatures
            if (sigs.isNullOrEmpty()) return null
            return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        val update = digests(pm.getPackageArchiveInfo(apk.path, flags))
        val installed = digests(pm.getPackageInfo(context.packageName, flags))
        update != null && installed != null && update != installed
    } catch (_: Exception) {
        false
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
