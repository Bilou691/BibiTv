package com.bibitv.tv.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** Emplacement de stockage proposé pour les téléchargements (mémoire interne, clé USB, disque…). */
data class StorageOption(
    val id: String,
    val label: String,
    val path: File?,
    val treeUri: Uri?,
    val freeBytes: Long,
    val removable: Boolean,
)

enum class DownloadState { Queued, Running, Done, Failed, Canceled }

class DownloadItem(val title: String, val url: String, val relativePath: String, val target: StorageOption) {
    var state by mutableStateOf(DownloadState.Queued)
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("En attente")
    /** Emplacement final : chemin de fichier ou URI de document. */
    var location by mutableStateOf<String?>(null)
    internal var job: Job? = null

    val isActive get() = state == DownloadState.Queued || state == DownloadState.Running
}

/**
 * File de téléchargements traitée un par un (la plupart des abonnements n'autorisent
 * qu'une connexion). Les fichiers peuvent être écrits sur une clé USB ou un disque externe
 * pour ne pas remplir la mémoire de la TV.
 */
object DownloadManager {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false

    val items = mutableStateListOf<DownloadItem>()

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ---------- Emplacements ----------

    /** Volumes disponibles : mémoire interne + chaque clé USB / carte SD montée, + dossier choisi. */
    fun storageOptions(): List<StorageOption> {
        val sm = app.getSystemService(StorageManager::class.java)
        val list = mutableListOf<StorageOption>()
        val dirs = app.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
        dirs.forEachIndexed { i, dir ->
            if (dir == null) return@forEachIndexed
            val state = Environment.getExternalStorageState(dir)
            if (state != Environment.MEDIA_MOUNTED) return@forEachIndexed
            val removable = i > 0 || Environment.isExternalStorageRemovable(dir)
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) sm?.getStorageVolume(dir)?.getDescription(app) else null
            val label = when {
                !removable -> "Mémoire de la TV"
                desc != null -> desc
                else -> "Stockage externe ${i}"
            }
            list += StorageOption("vol:${dir.absolutePath}", label, dir, null, free(dir), removable)
        }
        SettingsStore.downloadTarget?.takeIf { it.startsWith("tree:") }?.let { t ->
            val uri = Uri.parse(t.removePrefix("tree:"))
            val doc = DocumentFile.fromTreeUri(app, uri)
            if (doc != null && doc.canWrite()) list += StorageOption(t, "Dossier : ${doc.name ?: "choisi"}", null, uri, -1, true)
        }
        return list
    }

    fun currentTarget(): StorageOption {
        val options = storageOptions()
        val saved = SettingsStore.downloadTarget
        return options.firstOrNull { it.id == saved }
            ?: options.firstOrNull { it.removable } // une clé USB est préférée à la mémoire de la TV
            ?: options.first()
    }

    fun setTarget(option: StorageOption) {
        SettingsStore.downloadTarget = option.id
    }

    /** Dossier choisi via le sélecteur système (si la TV en propose un). */
    fun setTreeTarget(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        SettingsStore.downloadTarget = "tree:$uri"
    }

    private fun free(dir: File) = try { StatFs(dir.path).availableBytes } catch (_: Exception) { -1L }

    // ---------- File d'attente ----------

    fun enqueue(title: String, url: String, relativePath: String): DownloadItem {
        val target = currentTarget()
        items.firstOrNull { it.relativePath == relativePath && it.target.id == target.id }?.let {
            if (it.state == DownloadState.Failed || it.state == DownloadState.Canceled) retry(it)
            return it
        }
        val item = DownloadItem(title, url, relativePath, target)
        if (exists(target, relativePath)) {
            item.state = DownloadState.Done
            item.progress = 1f
            item.status = "Déjà téléchargé"
            item.location = target.path?.let { File(it, relativePath).absolutePath }
        }
        items.add(0, item)
        process()
        return item
    }

    fun cancel(item: DownloadItem) {
        if (!item.isActive) return
        item.job?.cancel()
        item.state = DownloadState.Canceled
        item.status = "Annulé"
    }

    fun retry(item: DownloadItem) {
        if (item.isActive) return
        item.progress = 0f
        item.status = "En attente"
        item.state = DownloadState.Queued
        process()
    }

    fun clearFinished() {
        items.removeAll { !it.isActive }
    }

    val activeCount: Int get() = items.count { it.isActive }

    private fun process() {
        if (running) return
        running = true
        startService()
        scope.launch {
            try {
                while (true) {
                    val next = items.lastOrNull { it.state == DownloadState.Queued } ?: break
                    val job = launch { download(next) }
                    next.job = job
                    job.join()
                }
            } finally {
                running = false
                stopService()
            }
        }
    }

    private suspend fun download(item: DownloadItem) {
        item.state = DownloadState.Running
        item.status = "Connexion…"
        try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(item.url).build()
                XtreamClient.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("Réponse vide")
                        val total = body.contentLength()
                        val (out, finalize) = openOutput(item.target, item.relativePath)
                        var ok = false
                        try {
                            out.use { o ->
                                val src = body.byteStream()
                                val buf = ByteArray(1 shl 16)
                                var done = 0L
                                var lastBytes = 0L
                                var lastTick = System.currentTimeMillis()
                                while (true) {
                                    ensureActive()
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                    done += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastTick >= 700) {
                                        val speed = (done - lastBytes) * 1000.0 / (now - lastTick)
                                        lastTick = now; lastBytes = done
                                        val status = if (total > 0) "${size(done)} sur ${size(total)}  ·  ${size(speed.toLong())}/s${eta(total - done, speed)}"
                                        else "${size(done)}  ·  ${size(speed.toLong())}/s"
                                        val p = if (total > 0) done.toFloat() / total else 0f
                                        withContext(Dispatchers.Main) { item.status = status; item.progress = p }
                                    }
                                }
                            }
                            ok = true
                        } finally {
                            val location = finalize(ok)
                            if (ok) withContext(Dispatchers.Main) { item.location = location }
                        }
                    }
            }
            item.progress = 1f
            item.status = "Terminé"
            item.state = DownloadState.Done
        } catch (e: kotlinx.coroutines.CancellationException) {
            item.state = DownloadState.Canceled
            item.status = "Annulé"
        } catch (e: Exception) {
            item.state = DownloadState.Failed
            item.status = "Échec : ${e.message ?: "erreur réseau"}"
        }
    }

    /** Ouvre le fichier de destination (« .part » puis renommé) ; renvoie la fonction de finalisation. */
    private fun openOutput(target: StorageOption, relativePath: String): Pair<OutputStream, (Boolean) -> String?> {
        if (target.path != null) {
            val file = File(target.path, relativePath)
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            return part.outputStream() to { ok: Boolean ->
                if (ok) { file.delete(); part.renameTo(file); file.absolutePath } else { part.delete(); null }
            }
        }
        // Dossier choisi via le sélecteur système (Storage Access Framework)
        var dir = DocumentFile.fromTreeUri(app, target.treeUri!!) ?: throw IllegalStateException("Dossier inaccessible")
        val parts = relativePath.split('/')
        for (name in parts.dropLast(1)) dir = dir.findFile(name) ?: dir.createDirectory(name) ?: throw IllegalStateException("Dossier inaccessible")
        val name = parts.last()
        dir.findFile(name)?.delete()
        val doc = dir.createFile("application/octet-stream", name) ?: throw IllegalStateException("Écriture impossible")
        val out = app.contentResolver.openOutputStream(doc.uri) ?: throw IllegalStateException("Écriture impossible")
        return out to { ok: Boolean -> if (ok) doc.uri.toString() else { doc.delete(); null } }
    }

    private fun exists(target: StorageOption, relativePath: String): Boolean = try {
        if (target.path != null) File(target.path, relativePath).exists()
        else {
            var dir = DocumentFile.fromTreeUri(app, target.treeUri!!)
            val parts = relativePath.split('/')
            for (name in parts.dropLast(1)) dir = dir?.findFile(name)
            dir?.findFile(parts.last()) != null
        }
    } catch (_: Exception) {
        false
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.FRANCE, "%.1f Mo", bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} Ko"
    }

    private fun eta(remaining: Long, speed: Double): String {
        if (speed < 1) return ""
        val s = (remaining / speed).toLong()
        return if (s >= 3600) "  ·  ${s / 3600} h ${"%02d".format((s % 3600) / 60)} restantes" else "  ·  ${maxOf(1, s / 60)} min restantes"
    }

    /** Nom de fichier sans caractères interdits (FAT32/exFAT des clés USB compris). */
    fun safeName(name: String): String {
        val clean = name.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Regex("""\s+"""), " ").trim().trimEnd('.')
        return when {
            clean.isEmpty() -> "Sans titre"
            clean.length > 120 -> clean.take(120).trim()
            else -> clean
        }
    }

    private fun startService() {
        try {
            val intent = Intent(app, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun stopService() {
        try { app.stopService(Intent(app, DownloadService::class.java)) } catch (_: Exception) {}
    }
}

/** Service au premier plan : garde l'application active pendant les téléchargements. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("downloads", "Téléchargements", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "downloads")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("BibiTV")
            .setContentText("Téléchargement en cours…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        return START_NOT_STICKY
    }
}
