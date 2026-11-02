package com.bibitv.tv.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.AtomicFile
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
 * content://com.bibitv.tv.home/top       : Top 10 films et séries de la semaine, en alternance
 *
 * Colonnes : key, title, subtitle, image_url, link (bibitv://…), progress (0–1000).
 * « top » ajoute : rank, kind (Films / Séries), backdrop_url (image paysage), plot.
 * Seuls les lanceurs de [ALLOWED] peuvent lire (l'historique de visionnage reste privé).
 * Le nom du paquet ne suffit pas (n'importe quelle app peut le reprendre) : l'empreinte de la
 * signature du lanceur est mémorisée à son premier accès, puis exigée à chaque lecture.
 */
class HomeProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        if (!isTrustedCaller(ctx, callingPackage)) throw SecurityException("Accès réservé à l'écran d'accueil")
        ProfileStore.ensureInit(ctx)
        val cursor = MatrixCursor(COLUMNS)
        when (uri.lastPathSegment) {
            PATH_RESUME -> ProfileStore.historySnapshot()
                .filter { it.kind != Section.Live && (it.hasProgress || it.episodeId != null) }
                .take(MAX_ROWS)
                .forEach { h ->
                    cursor.addRow(arrayOf(h.key, h.title, h.subtitle, h.imageUrl, TvHome.resumeLink(h.key).toString(), (h.progress * 1000).toInt()))
                }
            PATH_FOR_YOU -> readSuggestions(ctx).take(MAX_ROWS).forEach { s ->
                cursor.addRow(arrayOf(s.key, s.title, s.subtitle, s.imageUrl, TvHome.detailLink(s.key).toString(), 0))
            }
            PATH_TOP -> {
                val top = MatrixCursor(TOP_COLUMNS)
                readTop(ctx).forEach { t ->
                    top.addRow(arrayOf(t.key, t.title, t.subtitle, t.imageUrl, TvHome.detailLink(t.key).toString(), 0, t.rank, t.kind, t.backdrop, t.plot))
                }
                top.setNotificationUri(ctx.contentResolver, uri)
                return top
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

    @Serializable
    data class TopEntry(
        val key: String, val title: String, val subtitle: String, val imageUrl: String?,
        val rank: Int, val kind: String, val backdrop: String?, val plot: String?,
    )

    companion object {
        const val AUTHORITY = "com.bibitv.tv.home"
        private const val PATH_RESUME = "reprendre"
        private const val PATH_FOR_YOU = "pour_toi"
        private const val PATH_TOP = "top"
        private const val MAX_ROWS = 20
        private val ALLOWED = setOf("com.perso.tvhome")
        private val COLUMNS = arrayOf("key", "title", "subtitle", "image_url", "link", "progress")
        private val TOP_COLUMNS = COLUMNS + arrayOf("rank", "kind", "backdrop_url", "plot")
        private val json = Json { ignoreUnknownKeys = true }

        val resumeUri: Uri = Uri.parse("content://$AUTHORITY/$PATH_RESUME")
        val forYouUri: Uri = Uri.parse("content://$AUTHORITY/$PATH_FOR_YOU")
        val topUri: Uri = Uri.parse("content://$AUTHORITY/$PATH_TOP")

        private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "home_suggestions.json"))

        private fun write(f: AtomicFile, text: String) {
            val out = try { f.startWrite() } catch (_: Exception) { return }
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                f.finishWrite(out)
            } catch (_: Exception) {
                f.failWrite(out)
            }
        }

        private fun read(f: AtomicFile): String = String(f.readFully(), Charsets.UTF_8)

        // ---------- Lanceur autorisé (empreinte de signature mémorisée au premier accès) ----------

        private fun pins(ctx: Context) = ctx.getSharedPreferences("home_provider", Context.MODE_PRIVATE)

        @Suppress("DEPRECATION")
        private fun signature(ctx: Context, pkg: String): String? = try {
            val pm = ctx.packageManager
            val sigs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            else pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES).signatures
            sigs?.map { s -> java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
                ?.sorted()?.joinToString(",")
        } catch (_: Exception) {
            null
        }

        @Synchronized
        private fun isTrustedCaller(ctx: Context, pkg: String?): Boolean {
            if (pkg == null || pkg !in ALLOWED) return false
            val actual = signature(ctx, pkg) ?: return false
            val prefs = pins(ctx)
            val pinned = prefs.getString(pkg, null)
            if (pinned == null) {
                prefs.edit().putString(pkg, actual).apply()
                return true
            }
            return pinned == actual
        }

        /** Empreinte mémorisée du lanceur autorisé (affichée dans les Réglages), ou null. */
        fun trustedLauncher(ctx: Context): String? = pins(ctx).all.entries.firstOrNull()?.let { (k, v) -> "$k  ·  ${(v as? String)?.take(12)}…" }

        /** Oublie l'empreinte : à faire si le lanceur perso a été réinstallé avec une autre signature. */
        fun resetTrustedLauncher(ctx: Context) {
            pins(ctx).edit().clear().apply()
        }

        /** Enregistre la rangée « Pour toi » (appelé à chaque construction de l'accueil). */
        fun saveSuggestions(ctx: Context, items: List<MediaItem>) {
            val list = items.distinctBy { it.key }.take(MAX_ROWS)
                .map { Suggestion(it.key, it.title, it.subtitle, it.imageUrl) }
            write(file(ctx), json.encodeToString(list))
            notifyChanged(ctx, forYouUri)
        }

        private fun topFile(ctx: Context) = AtomicFile(File(ctx.filesDir, "home_top.json"))

        /** Enregistre le Top 10 films / séries (alternés) avec images larges et résumés TMDB. */
        fun saveTop(ctx: Context, movies: List<RankedItem>, series: List<RankedItem>) {
            fun entry(r: RankedItem, kind: String): TopEntry {
                val art = TopService.artwork[r.item.key]
                return TopEntry(
                    r.item.key, r.item.title, r.item.subtitle, r.item.imageUrl, r.rank, kind,
                    art?.backdrop, art?.overview ?: r.item.plot,
                )
            }
            val list = mutableListOf<TopEntry>()
            for (i in 0 until maxOf(movies.size, series.size)) {
                movies.getOrNull(i)?.let { list += entry(it, "Films") }
                series.getOrNull(i)?.let { list += entry(it, "Séries") }
            }
            write(topFile(ctx), json.encodeToString(list))
            notifyChanged(ctx, topUri)
        }

        private fun readTop(ctx: Context): List<TopEntry> = runCatching {
            json.decodeFromString<List<TopEntry>>(read(topFile(ctx)))
        }.getOrDefault(emptyList())

        private fun readSuggestions(ctx: Context): List<Suggestion> = runCatching {
            json.decodeFromString<List<Suggestion>>(read(file(ctx)))
        }.getOrDefault(emptyList())

        fun notifyChanged(ctx: Context, uri: Uri) {
            runCatching { ctx.contentResolver.notifyChange(uri, null) }
        }
    }
}
