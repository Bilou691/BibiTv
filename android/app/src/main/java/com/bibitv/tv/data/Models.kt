package com.bibitv.tv.data

import java.util.Locale

enum class Section { Live, Movies, Series }

data class Category(
    val id: String,
    val name: String,
    var count: Int = 0,
    /** Intertitre non sélectionnable (« MARQUES », « CATÉGORIES »). */
    val isHeader: Boolean = false,
) {
    companion object {
        const val ALL_ID = "__all__"
        const val FAVORITES_ID = "__fav__"
    }
}

/** Fiche affichée (chaîne, film ou série). Un film/série regroupe toutes ses versions. */
class MediaItem(
    val kind: Section,
    val id: String,
    val title: String,
    val imageUrl: String?,
    val categoryId: String,
    val rating: String? = null,
    val year: String? = null,
    val extension: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val added: Long = 0,
    val tmdbId: String? = null,
) {
    /** Étiquette de version extraite du titre (« 4K MULTI », « SUBT AR »…). */
    var tag: String = ""
    var versionLabel: String = ""
    var variants: List<MediaItem> = emptyList()
    var categoryIds: MutableSet<String> = mutableSetOf()
    /** Identité stable (titre normalisé + année), commune à toutes les versions. */
    var groupKey: String = ""

    val key: String get() = "$kind:${groupKey.ifEmpty { id }}"

    val initial: String get() = title.trim().take(1).uppercase(Locale.ROOT).ifEmpty { "?" }

    val subtitle: String
        get() = if (kind == Section.Live) "" else listOfNotNull(
            year,
            formatRating(rating),
            if (variants.size > 1) "${variants.size} versions" else null,
        ).joinToString("  ·  ")

    companion object {
        fun formatRating(rating: String?): String? {
            val r = rating?.toDoubleOrNull() ?: return null
            return if (r > 0) "★ " + String.format(Locale.FRANCE, "%.1f", r).removeSuffix(",0") else null
        }
    }
}

data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val duration: String?,
    val releaseDate: String?,
    val backdrop: String?,
    val rating: String?,
    val extension: String?,
)

data class Episode(
    val id: String,
    val number: Int,
    val season: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val duration: String?,
    val extension: String,
) {
    val label: String get() = "$number. $title"
}

data class Season(val number: Int, val episodes: List<Episode>) {
    val label: String get() = if (number == 0) "Bonus" else "Saison $number"
}

data class SeriesInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val releaseDate: String?,
    val backdrop: String?,
    val seasons: List<Season>,
)

/** Programme TV (guide EPG). */
data class EpgEntry(val title: String, val description: String?, val start: Long, val end: Long) {
    val isNow: Boolean get() = System.currentTimeMillis() in start until end
    val progress: Float
        get() = if (end <= start) 0f else ((System.currentTimeMillis() - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    val timeRange: String get() = "${hhmm(start)} – ${hhmm(end)}"

    companion object {
        fun hhmm(ms: Long): String = java.text.SimpleDateFormat("HH:mm", Locale.FRANCE).format(java.util.Date(ms))
    }
}

data class AccountInfo(val status: String, val expiration: Long?, val maxConnections: String?)

class XtreamException(message: String) : Exception(message)

fun yearOf(s: String?): String? {
    if (s == null || s.length < 4) return null
    val y = s.take(4).toIntOrNull() ?: return null
    return if (y > 1900) y.toString() else null
}
