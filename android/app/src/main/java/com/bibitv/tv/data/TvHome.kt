package com.bibitv.tv.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.bibitv.tv.R

/**
 * Intégration à l'écran d'accueil de la TV :
 * - rangée système « Continuer à regarder » (Watch Next) ;
 * - chaîne « BibiTV » (Top 10 de la semaine, Pour toi) sur l'accueil Android TV.
 * Chaque élément ouvre BibiTV via un lien direct (bibitv://…).
 */
// RestrictedApi : lint se trompe sur les constructeurs de tvprovider, c'est l'usage documenté par Google.
@android.annotation.SuppressLint("RestrictedApi")
object TvHome {
    const val SCHEME = "bibitv"

    fun detailLink(key: String): Uri = Uri.parse("$SCHEME://fiche?key=${Uri.encode(key)}")
    fun resumeLink(key: String): Uri = Uri.parse("$SCHEME://reprendre?key=${Uri.encode(key)}")
    private val homeLink: Uri = Uri.parse("$SCHEME://accueil")

    /** Rangées de l'accueil Android TV : sans objet sur smartphone / tablette. */
    private fun supported() = Device.isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun prefs(context: Context) = context.getSharedPreferences("tvhome", Context.MODE_PRIVATE)

    // ---------- Continuer à regarder ----------

    /** Ajoute ou met à jour la fiche dans « Continuer à regarder ». */
    fun publishWatchNext(context: Context, h: HistoryEntry, finished: Boolean) {
        if (!supported() || h.kind == Section.Live) return
        try {
            val ids = prefs(context)
            val idKey = "wn:${h.key}"
            // Film terminé : il sort de la rangée.
            if (finished && h.episodeId == null) {
                removeWatchNext(context, h.key)
                return
            }
            val builder = WatchNextProgram.Builder()
                .setWatchNextType(
                    if (finished) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                    else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
                )
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setType(if (h.kind == Section.Series) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
                .setTitle(h.title)
                .setIntentUri(resumeLink(h.key))
                .setInternalProviderId(h.key)
                .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
            if (h.episodeLabel != null) builder.setEpisodeTitle(if (finished) "Épisode suivant" else h.episodeLabel)
            h.imageUrl?.let { builder.setPosterArtUri(Uri.parse(it)) }
            if (!finished && h.lengthMs > 0) {
                builder.setLastPlaybackPositionMillis(h.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                builder.setDurationMillis(h.lengthMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            val values = builder.build().toContentValues()
            val existing = ids.getLong(idKey, -1)
            val updated = existing >= 0 &&
                context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null) > 0
            if (!updated) {
                val uri = context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                uri?.lastPathSegment?.toLongOrNull()?.let { ids.edit().putLong(idKey, it).apply() }
            }
        } catch (_: Exception) {
            // Accueil sans prise en charge de Watch Next : rien à faire.
        }
    }

    fun removeWatchNext(context: Context, key: String) {
        if (!supported()) return
        try {
            val ids = prefs(context)
            val id = ids.getLong("wn:$key", -1)
            if (id >= 0) context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
            ids.edit().remove("wn:$key").apply()
        } catch (_: Exception) {
        }
    }

    // ---------- Chaîne BibiTV sur l'accueil ----------

    /** Publie (ou remplace) les programmes de la chaîne « BibiTV ». */
    fun publishChannel(context: Context, items: List<MediaItem>) {
        if (!supported() || items.isEmpty()) return
        try {
            val helper = PreviewChannelHelper(context)
            val p = prefs(context)
            var channelId = p.getLong("channel", -1)
            if (channelId < 0 || helper.getPreviewChannel(channelId) == null) {
                val channel = PreviewChannel.Builder()
                    .setDisplayName("BibiTV")
                    .setDescription("Top 10 de la semaine et suggestions")
                    .setAppLinkIntentUri(homeLink)
                    .setLogo(BitmapFactory.decodeResource(context.resources, R.drawable.logo))
                    .build()
                channelId = helper.publishDefaultChannel(channel)
                p.edit().putLong("channel", channelId).apply()
            }
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
            items.distinctBy { it.key }.take(20).forEachIndexed { i, m ->
                val b = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(if (m.kind == Section.Series) TvContractCompat.PreviewProgramColumns.TYPE_TV_SERIES else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
                    .setTitle(m.title)
                    .setDescription(listOfNotNull(m.year, MediaItem.formatRating(m.rating)).joinToString("  ·  "))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                    .setIntentUri(detailLink(m.key))
                    .setInternalProviderId(m.key)
                    .setWeight(1000 - i)
                m.imageUrl?.let { b.setPosterArtUri(Uri.parse(it)) }
                helper.publishPreviewProgram(b.build())
            }
        } catch (_: Exception) {
            // Google TV n'affiche pas les chaînes d'applications : sans conséquence.
        }
    }
}
