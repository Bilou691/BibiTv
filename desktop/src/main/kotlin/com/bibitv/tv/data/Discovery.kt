package com.bibitv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

data class RankedItem(val rank: Int, val item: MediaItem)

/**
 * Top 10 de la semaine :
 * - avec la clé TMDB (intégrée à la compilation) : vraies tendances, retrouvées dans le catalogue ;
 * - sinon : nouveautés de la semaine les mieux notées.
 */
object TopService {
    private val json = Json { ignoreUnknownKeys = true }

    /** Image large (paysage) et résumé TMDB des titres du Top, par clé de fiche. */
    data class Artwork(val backdrop: String?, val overview: String?)
    val artwork = java.util.concurrent.ConcurrentHashMap<String, Artwork>()

    suspend fun top(kind: Section, catalog: List<MediaItem>): Pair<List<RankedItem>, Boolean> {
        val key = AppInfo.tmdbKey
        if (key.isNotBlank()) {
            try {
                val matched = match(trending(kind, key), catalog)
                if (matched.size >= 3) return rank(matched) to true
            } catch (_: Exception) {
                // TMDB injoignable : classement du catalogue.
            }
        }
        return rank(fromCatalog(catalog)) to false
    }

    private fun rank(items: List<MediaItem>) = items.take(10).mapIndexed { i, m -> RankedItem(i + 1, m) }

    fun fromCatalog(catalog: List<MediaItem>): List<MediaItem> {
        val withPoster = catalog.filter { it.imageUrl != null }
        val now = System.currentTimeMillis() / 1000
        for (days in listOf(7, 14, 30, 90)) {
            val since = now - days * 86400L
            val recent = withPoster.filter { it.added >= since }
            if (recent.size >= 10) return recent.sortedWith(compareByDescending<MediaItem> { score(it) }.thenByDescending { it.added })
        }
        return withPoster.sortedByDescending { it.added }.take(60).sortedByDescending { score(it) }
    }

    private fun score(i: MediaItem) = (i.rating?.toDoubleOrNull() ?: 0.0) + min(i.variants.size, 4) * 0.15

    private data class Trend(
        val id: String, val title: String, val original: String?, val year: String?,
        val backdrop: String?, val overview: String?,
    )

    private suspend fun trending(kind: Section, key: String): List<Trend> = withContext(Dispatchers.IO) {
        val type = if (kind == Section.Series) "tv" else "movie"
        val result = mutableListOf<Trend>()
        for (page in 1..2) {
            var url = "https://api.themoviedb.org/3/trending/$type/week?language=fr-FR&page=$page"
            val builder = Request.Builder()
            // Jeton v4 (« eyJ… ») en en-tête, clé v3 en paramètre.
            if (key.startsWith("eyJ")) builder.header("Authorization", "Bearer $key") else url += "&api_key=$key"
            XtreamClient.http.newCall(builder.url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("TMDB ${resp.code}")
                val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
                for (r in root["results"]!!.jsonArray) {
                    val o = r.jsonObject
                    fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val date = s("release_date") ?: s("first_air_date")
                    result += Trend(
                        o["id"].toString(), s("title") ?: s("name") ?: "", s("original_title") ?: s("original_name"), yearOf(date),
                        s("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" }, s("overview")?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        result
    }

    private fun match(trending: List<Trend>, catalog: List<MediaItem>): List<MediaItem> {
        val byTmdb = HashMap<String, MediaItem>()
        val byTitle = HashMap<String, MutableList<MediaItem>>()
        for (item in catalog) {
            item.tmdbId?.let { byTmdb.putIfAbsent(it, item) }
            byTitle.getOrPut(compact(item.title)) { mutableListOf() }.add(item)
        }
        val out = mutableListOf<MediaItem>()
        for (t in trending) {
            var found = byTmdb[t.id]
            if (found == null) {
                for (title in listOfNotNull(t.title, t.original)) {
                    val cands = byTitle[compact(title)] ?: continue
                    found = cands.firstOrNull { t.year == null || it.year == null || it.year == t.year } ?: cands[0]
                    break
                }
            }
            if (found != null && found !in out) {
                out += found
                artwork[found.key] = Artwork(t.backdrop, t.overview)
            }
            if (out.size == 10) break
        }
        return out
    }

    private fun compact(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() && it.isLetterOrDigit() }
        .lowercase()
}

/**
 * Suggestions « Pour toi » : chaque titre est décrit par ses catégories et genres ;
 * les titres aimés renforcent ces traits, ceux non aimés les pénalisent, ceux regardés comptent un peu.
 */
object Recommendations {
    private val separators = charArrayOf(',', '/', '&', '|', ';')

    fun features(item: MediaItem): List<String> {
        val f = item.categoryIds.filter { !it.startsWith(ChannelBrands.PREFIX) }.map { "c:$it" }.toMutableList()
        item.genre?.split(*separators)?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.forEach { f += "g:$it" }
        return f
    }

    fun weights(catalog: List<MediaItem>): Map<String, Double> {
        val p = ProfileStore.profile
        val history = p.history.map { it.key }.toSet()
        val w = HashMap<String, Double>()
        for (item in catalog) {
            val v = when {
                item.key in p.likes -> 2.0
                item.key in p.dislikes -> -2.0
                item.key in p.watched || item.key in history -> 0.75
                else -> 0.0
            }
            if (v == 0.0) continue
            for (f in features(item)) w[f] = (w[f] ?: 0.0) + v
        }
        return w
    }

    fun suggest(catalog: List<MediaItem>, count: Int = 24): List<MediaItem> {
        val w = weights(catalog)
        if (w.values.none { it > 0 }) return emptyList()
        val p = ProfileStore.profile
        val history = p.history.map { it.key }.toSet()
        return catalog.asSequence()
            .filter { it.imageUrl != null && it.key !in p.likes && it.key !in p.dislikes && it.key !in p.watched && it.key !in history }
            .map { item ->
                val feats = features(item)
                if (feats.isEmpty()) return@map item to 0.0
                val s = feats.sumOf { w[it] ?: 0.0 } / sqrt(feats.size.toDouble())
                if (s <= 0) item to 0.0 else item to s + (item.rating?.toDoubleOrNull() ?: 0.0) * 0.08
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MediaItem, Double>> { it.second }.thenByDescending { it.first.added })
            .take(count)
            .map { it.first }
            .toList()
    }

    fun topTastes(catalog: List<MediaItem>, categoryNames: Map<String, String>, count: Int = 3): List<String> =
        weights(catalog).filter { it.value > 0 }.entries.sortedByDescending { it.value }
            .mapNotNull { (k, _) -> if (k.startsWith("c:")) categoryNames[k.substring(2)] else k.substring(2).replaceFirstChar { it.uppercase() } }
            .map { cleanName(it) }
            .distinctBy { it.lowercase() }
            .take(count)

    /** Titres proposés à la notation : déjà notés d'abord, puis populaires de genres variés. */
    fun candidates(catalog: List<MediaItem>, count: Int = 60): List<MediaItem> {
        val p = ProfileStore.profile
        val rated = catalog.filter { it.key in p.likes || it.key in p.dislikes }
        val ratedSet = rated.toSet()
        val popular = catalog.asSequence()
            .filter { it.imageUrl != null && it !in ratedSet }
            .map { it to (it.rating?.toDoubleOrNull() ?: 0.0) }
            .filter { it.second >= 6 }
            .sortedByDescending { it.second + min(it.first.variants.size, 4) * 0.3 + (if (it.first.added > 0) ln(it.first.added.toDouble()) * 0.01 else 0.0) }
            .map { it.first }
            .take(400)
            .toList()
        val mixed = popular.groupBy { it.categoryId }.values
            .flatMap { g -> g.mapIndexed { idx, item -> idx to item } }
            .sortedBy { it.first }
            .map { it.second }
        return (rated + mixed).take(count)
    }

    private fun cleanName(s: String): String {
        val idx = s.indexOfLast { it in "·|:-" }
        val t = if (idx >= 0 && idx < s.length - 2) s.substring(idx + 1).trim() else s.trim()
        return t.ifEmpty { s }
    }
}

@Suppress("unused")
private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.content
