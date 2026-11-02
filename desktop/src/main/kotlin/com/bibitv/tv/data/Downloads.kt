package com.bibitv.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.util.Locale

enum class DownloadState { Queued, Running, Done, Failed, Canceled }

class DownloadItem(val title: String, val url: String, val relativePath: String, val dir: File) {
    var state by mutableStateOf(DownloadState.Queued)
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("En attente")
    var location by mutableStateOf<String?>(null)
    internal var job: Job? = null

    val isActive get() = state == DownloadState.Queued || state == DownloadState.Running
    val file: File get() = File(dir, relativePath)
}

@Serializable
private data class SavedDownload(
    val title: String,
    val url: String,
    val relativePath: String,
    val dir: String,
    val state: DownloadState,
    val location: String? = null,
)

/**
 * File de téléchargements traitée un par un (la plupart des abonnements n'autorisent qu'une
 * connexion), conservée entre les lancements, avec reprise des fichiers partiels.
 */
object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private val json = Json { ignoreUnknownKeys = true }
    private val store = AtomicFile(File(Platform.dataDir, "downloads.json"))

    val items = mutableStateListOf<DownloadItem>()

    fun init() {
        val saved = try {
            if (store.baseFile.exists()) json.decodeFromString<List<SavedDownload>>(store.readText()) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (d in saved) {
            val url = XtreamClient.resolve(d.url, SettingsStore.username, SettingsStore.password ?: "")
            val item = DownloadItem(d.title, url, d.relativePath, File(d.dir))
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
        val user = SettingsStore.username
        val pwd = SettingsStore.password ?: ""
        val list = items.map {
            SavedDownload(it.title, XtreamClient.templatize(it.url, user, pwd), it.relativePath, it.dir.absolutePath, it.state, it.location)
        }
        runCatching { store.writeText(json.encodeToString(list)) }
    }

    val activeCount: Int get() = items.count { it.isActive }

    fun enqueue(title: String, url: String, relativePath: String): DownloadItem {
        val dir = SettingsStore.downloadDir
        items.firstOrNull { it.relativePath == relativePath && it.dir == dir }?.let {
            if (it.state == DownloadState.Failed || it.state == DownloadState.Canceled) retry(it)
            return it
        }
        val item = DownloadItem(title, url, relativePath, dir)
        if (item.file.exists()) {
            item.state = DownloadState.Done
            item.progress = 1f
            item.status = "Déjà téléchargé"
            item.location = item.file.absolutePath
        }
        items.add(0, item)
        persist()
        process()
        return item
    }

    fun cancel(item: DownloadItem) {
        if (!item.isActive) return
        item.state = DownloadState.Canceled
        item.status = "Annulé"
        item.job?.cancel()
        if (item.job == null) runCatching { part(item).delete() }
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

    private fun part(item: DownloadItem) = File(item.file.path + ".part")

    private fun process() {
        if (running || items.none { it.state == DownloadState.Queued }) return
        running = true
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
            }
        }
    }

    private suspend fun download(item: DownloadItem) {
        item.state = DownloadState.Running
        item.status = "Connexion…"
        persist()
        try {
            withContext(Dispatchers.IO) {
                val file = item.file
                file.parentFile?.mkdirs()
                val part = part(item)
                val already = if (part.exists()) part.length() else 0L
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
                        var ok = false
                        try {
                            java.io.FileOutputStream(part, resumed).use { o ->
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
                            if (ok) {
                                file.delete(); part.renameTo(file)
                                withContext(Dispatchers.Main) { item.location = file.absolutePath }
                            } else if (item.state == DownloadState.Canceled) part.delete()
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

    /** Nom de fichier sans caractères interdits (Windows, clés USB FAT32/exFAT compris). */
    fun safeName(name: String): String {
        val clean = name.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Regex("""\s+"""), " ").trim().trimEnd('.')
        return when {
            clean.isEmpty() -> "Sans titre"
            clean.length > 120 -> clean.take(120).trim()
            else -> clean
        }
    }
}
