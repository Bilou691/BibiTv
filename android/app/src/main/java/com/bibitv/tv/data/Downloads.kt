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
import kotlinx.serialization.encodeToString
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

/** Téléchargement enregistré dans downloads.json (URL sans le mot de passe). */
@kotlinx.serialization.Serializable
private data class SavedDownload(
    val title: String,
    val url: String,
    val relativePath: String,
    val targetId: String,
    val state: DownloadState,
    val location: String? = null,
)

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

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private lateinit var store: android.util.AtomicFile

    /**
     * Recharge la liste des téléchargements : un téléchargement interrompu (app fermée, TV éteinte)
     * reste dans la liste et reprend là où il s'était arrêté avec « Réessayer ».
     */
    fun init(context: Context) {
        app = context.applicationContext
        store = android.util.AtomicFile(File(app.filesDir, "downloads.json"))
        val saved = try {
            json.decodeFromString<List<SavedDownload>>(String(store.readFully(), Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
        for (d in saved) {
            val url = XtreamClient.resolve(d.url, SettingsStore.username, SettingsStore.password ?: "")
            val item = DownloadItem(d.title, url, d.relativePath, optionFromId(d.targetId))
            item.location = d.location
            when (d.state) {
                DownloadState.Done -> { item.state = DownloadState.Done; item.progress = 1f; item.status = "Terminé" }
                DownloadState.Canceled -> { item.state = DownloadState.Canceled; item.status = "Annulé" }
                else -> { item.state = DownloadState.Failed; item.status = "Interrompu — « Réessayer » pour reprendre" }
            }
            items += item
        }
    }

    private fun persist() {
        if (!::store.isInitialized) return
        val user = SettingsStore.username
        val pwd = SettingsStore.password ?: ""
        val list = items.map {
            SavedDownload(it.title, XtreamClient.templatize(it.url, user, pwd), it.relativePath, it.target.id, it.state, it.location)
        }
        val out = try { store.startWrite() } catch (_: Exception) { return }
        try {
            out.write(json.encodeToString(list).toByteArray(Charsets.UTF_8))
            store.finishWrite(out)
        } catch (_: Exception) {
            store.failWrite(out)
        }
    }

    /** Emplacement d'un téléchargement enregistré, même si le support est débranché. */
    private fun optionFromId(id: String): StorageOption = storageOptions().firstOrNull { it.id == id } ?: when {
        id.startsWith("vol:") -> StorageOption(id, "Support débranché", File(id.removePrefix("vol:")), null, -1, true)
        id.startsWith("tree:") -> StorageOption(id, "Dossier choisi", null, Uri.parse(id.removePrefix("tree:")), -1, true)
        else -> StorageOption(id, "Inconnu", null, null, -1, true)
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
        persist()
        process()
        return item
    }

    fun cancel(item: DownloadItem) {
        if (!item.isActive) return
        // L'état passe à « Annulé » avant l'arrêt : le fichier partiel est alors supprimé (pas de reprise).
        item.state = DownloadState.Canceled
        item.status = "Annulé"
        item.job?.cancel()
        if (item.job == null) deletePart(item)
        persist()
    }

    fun retry(item: DownloadItem) {
        if (item.isActive) return
        item.progress = 0f
        item.status = "En attente"
        item.state = DownloadState.Queued
        persist()
        process()
    }

    fun clearFinished() {
        items.removeAll { !it.isActive }
        persist()
    }

    val activeCount: Int get() = items.count { it.isActive }

    private fun process() {
        // Le service n'est lancé que s'il y a vraiment un fichier à télécharger : l'arrêter
        // aussitôt après l'avoir démarré ferait planter l'app sur Android 9+.
        if (running || items.none { it.state == DownloadState.Queued }) return
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
        persist()
        try {
            withContext(Dispatchers.IO) {
                // Reprise : si un fichier partiel existe (support local), on demande la suite.
                val part = item.target.path?.let { File(File(it, item.relativePath).path + ".part") }
                val already = part?.takeIf { it.exists() }?.length() ?: 0L
                val builder = Request.Builder().url(item.url)
                if (already > 0) builder.header("Range", "bytes=$already-")
                XtreamClient.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(builder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("Réponse vide")
                        val resumed = already > 0 && resp.code == 206
                        val start = if (resumed) already else 0L
                        val total = body.contentLength().let { if (it > 0) it + start else -1L }
                        val (out, finalize) = openOutput(item.target, item.relativePath, append = resumed)
                        var ok = false
                        try {
                            out.use { o ->
                                val src = body.byteStream()
                                val buf = ByteArray(1 shl 16)
                                var done = start
                                var lastBytes = start
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
                            val location = finalize(ok, item.state != DownloadState.Canceled)
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
            item.status = "Échec : ${e.message ?: "erreur réseau"} — « Réessayer » pour reprendre"
        }
        persist()
    }

    private fun deletePart(item: DownloadItem) {
        item.target.path?.let { runCatching { File(File(it, item.relativePath).path + ".part").delete() } }
    }

    /**
     * Ouvre le fichier de destination (« .part » puis renommé) ; renvoie la fonction de finalisation
     * (réussite, garder le fichier partiel pour une reprise).
     */
    private fun openOutput(target: StorageOption, relativePath: String, append: Boolean): Pair<OutputStream, (Boolean, Boolean) -> String?> {
        if (target.path != null) {
            val file = File(target.path, relativePath)
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            return java.io.FileOutputStream(part, append) to { ok: Boolean, keepPart: Boolean ->
                if (ok) { file.delete(); part.renameTo(file); file.absolutePath }
                else { if (!keepPart) part.delete(); null }
            }
        }
        // Dossier choisi via le sélecteur système (Storage Access Framework) : pas de reprise possible.
        var dir = DocumentFile.fromTreeUri(app, target.treeUri ?: throw IllegalStateException("Dossier inaccessible"))
            ?: throw IllegalStateException("Dossier inaccessible")
        val parts = relativePath.split('/')
        for (name in parts.dropLast(1)) dir = dir.findFile(name) ?: dir.createDirectory(name) ?: throw IllegalStateException("Dossier inaccessible")
        val name = parts.last()
        dir.findFile(name)?.delete()
        val doc = dir.createFile("application/octet-stream", name) ?: throw IllegalStateException("Écriture impossible")
        val out = app.contentResolver.openOutputStream(doc.uri) ?: throw IllegalStateException("Écriture impossible")
        return out to { ok: Boolean, _: Boolean -> if (ok) doc.uri.toString() else { doc.delete(); null } }
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

    /**
     * Android 15 limite les services « synchronisation » à 6 h par jour : le système prévient ici.
     * On s'arrête proprement (sinon l'app est fermée de force) ; le téléchargement en cours pourra reprendre.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }
}
