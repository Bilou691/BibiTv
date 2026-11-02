package com.bibitv.tv.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Système d'exploitation de l'ordinateur. */
enum class Os { Mac, Windows, Linux }

object Platform {
    val os: Os = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("mac") || it.contains("darwin") -> Os.Mac
            it.contains("win") -> Os.Windows
            else -> Os.Linux
        }
    }

    val label: String get() = when (os) {
        Os.Mac -> "ce Mac"
        Os.Windows -> "ce PC"
        Os.Linux -> "cet ordinateur"
    }

    /** Dossier des données de l'app (réglages, profil, téléchargements en cours). */
    val dataDir: File by lazy {
        val home = System.getProperty("user.home")
        val dir = when (os) {
            Os.Windows -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "BibiTV")
            Os.Mac -> File(home, "Library/Application Support/BibiTV")
            Os.Linux -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "bibitv")
        }
        dir.mkdirs()
        dir
    }

    /** Dossier par défaut des téléchargements (Films / Vidéos de l'utilisateur). */
    val defaultDownloadDir: File by lazy {
        val home = System.getProperty("user.home")
        val videos = when (os) {
            Os.Mac -> File(home, "Movies")
            else -> File(home, "Videos").takeIf { it.isDirectory } ?: File(home, "Vidéos").takeIf { it.isDirectory } ?: File(home, "Videos")
        }
        File(videos, "BibiTV")
    }

    /** Dossier des ressources propres au système (VLC embarqué sous Windows), fourni par le lanceur. */
    val resourcesDir: File? get() = System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }

    /** Ouvre un fichier ou un dossier avec l'application du système. */
    fun open(file: File) {
        runCatching {
            when (os) {
                Os.Mac -> ProcessBuilder("open", file.absolutePath).start()
                Os.Windows -> ProcessBuilder("explorer", file.absolutePath).start()
                Os.Linux -> ProcessBuilder("xdg-open", file.absolutePath).start()
            }
        }
    }

    fun browse(url: String) {
        runCatching {
            when (os) {
                Os.Mac -> ProcessBuilder("open", url).start()
                Os.Windows -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                Os.Linux -> ProcessBuilder("xdg-open", url).start()
            }
        }
    }
}

/** Version et clé TMDB, écrites à la compilation. */
object AppInfo {
    private val props = Properties().apply {
        AppInfo::class.java.getResourceAsStream("/bibitv-build.properties")?.use { load(it) }
    }
    val version: String = props.getProperty("version") ?: "0.0.0"
    val tmdbKey: String = props.getProperty("tmdb") ?: ""
}

/**
 * Écriture atomique d'un fichier (fichier temporaire puis remplacement) : une coupure pendant
 * l'enregistrement ne laisse jamais un fichier à moitié écrit.
 */
class AtomicFile(val baseFile: File) {
    fun readText(): String = baseFile.readText(Charsets.UTF_8)

    fun writeText(text: String) {
        baseFile.parentFile?.mkdirs()
        val tmp = File(baseFile.path + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
