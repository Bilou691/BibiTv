package com.bibitv.tv.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Accès en lecture aux rangées de l'accueil BibiTV pour un écran d'accueil tiers
 * (lanceur perso de la TV), là où l'accueil Google TV n'affiche pas les apps non partenaires.
 *
 * content://com.bibitv.tv.home/reprendre : films / séries en cours
 * content://com.bibitv.tv.home/pour_toi  : Top 10 et suggestions
 *
 * Colonnes : key, title, subtitle, image_url, link (bibitv://…), progress (0–1000).
 * Seuls les lanceurs de [ALLOWED] peuvent lire (l'historique de visionnage reste privé).
 */
class HomeProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        if (callingPackage !in ALLOWED) throw SecurityException("Accès réservé à l'écran d'accueil")
        ProfileStore.ensureInit(ctx)
        val cursor = MatrixCursor(COLUMNS)
        when (uri.lastPathSegment) {
            PATH_RESUME -> ProfileStore.profile.history
                .filter { it.kind != Section.Live && (it.hasProgress || it.episodeId != null) }
                .take(MAX_ROWS)
                .forEach { h ->
                    cursor.addRow(arrayOf(h.key, h.title, h.subtitle, h.imageUrl, TvHome.resumeLink(h.key).toString(), (h.progress * 1000).toInt()))
                }
            PATH_FOR_YOU -> readSuggestions(ctx).take(MAX_ROWS).forEach { s ->
                cursor.addRow(arrayOf(s.key, s.title, s.subtitle, s.imageUrl, TvHome.detailLink(s.key).toString(), 0))
            }
            else -> return null
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    @Serializable
    data class Suggestion(val key: String, val title: String, val subtitle: String, val imageUrl: String?)

    companion object {
        const val AUTHORITY = "com.bibitv.tv.home"
        private const val PATH_RESUME = "reprendre"
        private const val PATH_FOR_YOU = "pour_toi"
        private const val MAX_ROWS = 20
        private val ALLOWED = setOf("com.perso.tvhome")
        private val COLUMNS = arrayOf("key", "title", "subtitle", "image_url", "link", "progress")
        private val json = Json { ignoreUnknownKeys = true }

        val resumeUri: Uri = Uri.parse("content://$AUTHORITY/$PATH_RESUME")
        val forYouUri: Uri = Uri.parse("content://$AUTHORITY/$PATH_FOR_YOU")

        private fun file(ctx: Context) = File(ctx.filesDir, "home_suggestions.json")

        /** Enregistre la rangée « Pour toi » (appelé à chaque construction de l'accueil). */
        fun saveSuggestions(ctx: Context, items: List<MediaItem>) {
            val list = items.distinctBy { it.key }.take(MAX_ROWS)
                .map { Suggestion(it.key, it.title, it.subtitle, it.imageUrl) }
            runCatching { file(ctx).writeText(json.encodeToString(list)) }
            notifyChanged(ctx, forYouUri)
        }

        private fun readSuggestions(ctx: Context): List<Suggestion> = runCatching {
            json.decodeFromString<List<Suggestion>>(file(ctx).readText())
        }.getOrDefault(emptyList())

        fun notifyChanged(ctx: Context, uri: Uri) {
            runCatching { ctx.contentResolver.notifyChange(uri, null) }
        }
    }
}
