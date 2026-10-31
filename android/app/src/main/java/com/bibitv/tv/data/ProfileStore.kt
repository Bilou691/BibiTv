package com.bibitv.tv.data

import android.content.Context
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

/** Profil de visionnage (historique, « déjà vu », goûts, Ma liste), stocké dans profile.json. */
object ProfileStore {
    private const val MAX_HISTORY = 60
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var file: File
    private var appContext: Context? = null

    var profile = UserProfile()
        private set

    /** Incrémenté à chaque changement : lu par l'interface pour se rafraîchir. */
    val revision = mutableIntStateOf(0)

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, "profile.json")
        profile = try {
            if (file.exists()) json.decodeFromString(file.readText()) else UserProfile()
        } catch (e: Exception) {
            UserProfile()
        }
    }

    /** Le fournisseur de l'accueil peut être interrogé avant Application.onCreate. */
    @Synchronized
    fun ensureInit(context: Context) {
        if (!::file.isInitialized) init(context)
    }

    @Synchronized
    fun save() {
        revision.intValue++
        try {
            file.writeText(json.encodeToString(profile))
        } catch (_: Exception) {
        }
        appContext?.let { HomeProvider.notifyChanged(it, HomeProvider.resumeUri) }
    }

    fun episodeKey(episodeId: String) = "Ep:$episodeId"

    fun isWatched(key: String) = key in profile.watched
    fun setWatched(key: String, watched: Boolean) {
        if (watched) profile.watched.add(key) else profile.watched.remove(key)
        save()
    }

    /** 1 = j'aime, -1 = je n'aime pas, 0 = sans avis. */
    fun ratingOf(key: String) = when (key) {
        in profile.likes -> 1
        in profile.dislikes -> -1
        else -> 0
    }

    fun setRating(key: String, rating: Int) {
        profile.likes.remove(key)
        profile.dislikes.remove(key)
        if (rating > 0) profile.likes.add(key) else if (rating < 0) profile.dislikes.add(key)
        save()
    }

    fun isFavorite(item: MediaItem) = item.key in profile.favorites || item.variants.any { "${it.kind}:${it.id}" in profile.favorites }
    fun toggleFavorite(item: MediaItem) {
        if (isFavorite(item)) {
            profile.favorites.remove(item.key)
            item.variants.forEach { profile.favorites.remove("${it.kind}:${it.id}") }
        } else profile.favorites.add(item.key)
        save()
    }

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

    fun removeHistory(key: String) {
        profile.history.removeAll { it.key == key }
        save()
    }
}
