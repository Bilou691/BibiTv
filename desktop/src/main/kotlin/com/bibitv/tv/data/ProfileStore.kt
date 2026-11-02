package com.bibitv.tv.data

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Élément de l'historique « Reprendre » / « Chaînes regardées récemment ». */
@Serializable
data class HistoryEntry(
    /** Clé de la fiche (film, série ou chaîne) : une seule entrée par fiche. */
    val key: String,
    val kind: Section,
    /** Identifiant Xtream de la version lue (film, série ou chaîne). */
    val itemId: String,
    val title: String,
    val imageUrl: String? = null,
    val extension: String? = null,
    val episodeId: String? = null,
    val episodeLabel: String? = null,
    var positionMs: Long = 0,
    var lengthMs: Long = 0,
    var watchedAt: Long = 0,
) {
    val progress: Float get() = if (lengthMs > 0) (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    val hasProgress: Boolean get() = progress in 0.01f..0.92f

    val subtitle: String
        get() {
            val left = if (lengthMs > 0 && hasProgress) {
                val min = (lengthMs - positionMs) / 60000
                if (min >= 60) "reste ${min / 60} h ${"%02d".format(min % 60)}" else "reste ${maxOf(1, min)} min"
            } else null
            return listOfNotNull(episodeLabel, left).joinToString("  ·  ")
        }
}

@Serializable
data class UserProfile(
    val history: MutableList<HistoryEntry> = mutableListOf(),
    val watched: MutableSet<String> = mutableSetOf(),
    val likes: MutableSet<String> = mutableSetOf(),
    val dislikes: MutableSet<String> = mutableSetOf(),
    val favorites: MutableSet<String> = mutableSetOf(),
    var hideWatched: Boolean = false,
)

/**
 * Profil de visionnage (historique, « déjà vu », goûts, Ma liste), stocké dans profile.json.
 *
 * Écriture atomique (fichier temporaire + copie de secours) : une coupure de courant pendant
 * l'enregistrement ne peut plus effacer le profil. Toutes les modifications passent par ce
 * verrou, car l'écran d'accueil de la TV lit l'historique depuis un autre fil d'exécution.
 */
object ProfileStore {
    private const val MAX_HISTORY = 60
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var file: AtomicFile

    var profile = UserProfile()
        private set

    /** Incrémenté à chaque changement : lu par l'interface pour se rafraîchir. */
    val revision = mutableIntStateOf(0)

    @Synchronized
    fun init() {
        if (::file.isInitialized) return
        file = AtomicFile(File(Platform.dataDir, "profile.json"))
        profile = try {
            if (file.baseFile.exists()) json.decodeFromString(file.readText()) else UserProfile()
        } catch (e: Exception) {
            // Fichier illisible : on le garde de côté plutôt que de l'écraser au prochain enregistrement.
            runCatching { file.baseFile.copyTo(File(Platform.dataDir, "profile.corrompu.json"), overwrite = true) }
            UserProfile()
        }
    }

    @Synchronized
    fun save() {
        runCatching { file.writeText(json.encodeToString(profile)) }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) revision.intValue++
        else javax.swing.SwingUtilities.invokeLater { revision.intValue++ }
    }

    /** Copie de l'historique, sûre depuis n'importe quel fil d'exécution. */
    @Synchronized
    fun historySnapshot(): List<HistoryEntry> = profile.history.toList()

    fun episodeKey(episodeId: String) = "Ep:$episodeId"

    fun isWatched(key: String) = key in profile.watched

    @Synchronized
    fun setWatched(key: String, watched: Boolean) {
        if (watched) profile.watched.add(key) else profile.watched.remove(key)
        save()
    }

    /** Marque comme vu sans enregistrer (l'appelant enregistre juste après). */
    @Synchronized
    fun markWatched(key: String) {
        profile.watched.add(key)
    }

    /** 1 = j'aime, -1 = je n'aime pas, 0 = sans avis. */
    fun ratingOf(key: String) = when (key) {
        in profile.likes -> 1
        in profile.dislikes -> -1
        else -> 0
    }

    @Synchronized
    fun setRating(key: String, rating: Int) {
        profile.likes.remove(key)
        profile.dislikes.remove(key)
        if (rating > 0) profile.likes.add(key) else if (rating < 0) profile.dislikes.add(key)
        save()
    }

    fun isFavorite(item: MediaItem) = item.key in profile.favorites || item.variants.any { "${it.kind}:${it.id}" in profile.favorites }

    @Synchronized
    fun toggleFavorite(item: MediaItem) {
        if (isFavorite(item)) {
            profile.favorites.remove(item.key)
            item.variants.forEach { profile.favorites.remove("${it.kind}:${it.id}") }
        } else profile.favorites.add(item.key)
        save()
    }

    @Synchronized
    fun setHideWatched(hide: Boolean) {
        profile.hideWatched = hide
        save()
    }

    /** Ajoute ou met à jour une entrée d'historique et la place en tête. */
    @Synchronized
    fun touch(entry: HistoryEntry) {
        entry.watchedAt = System.currentTimeMillis()
        profile.history.removeAll { it.key == entry.key }
        profile.history.add(0, entry)
        while (profile.history.size > MAX_HISTORY) profile.history.removeAt(profile.history.size - 1)
        save()
    }

    @Synchronized
    fun removeHistory(key: String) {
        profile.history.removeAll { it.key == key }
        save()
    }
}
