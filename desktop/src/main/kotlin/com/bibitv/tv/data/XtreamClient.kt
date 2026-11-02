package com.bibitv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Client de l'API « player_api.php » des panels Xtream Codes.
 * Les réponses sont hétérogènes (nombres en texte, tableaux vides à la place d'objets…),
 * d'où un parsing défensif.
 */
class XtreamClient(baseUrl: String, username: String, password: String) {

    val baseUrl: String = normalizeUrl(baseUrl)
    val username: String = username.trim()
    val password: String = password.trim()

    companion object {
        const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun escape(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Remplace les identifiants d'une URL de lecture par des marqueurs (pour l'enregistrer sans le mot de passe). */
        fun templatize(url: String, username: String, password: String): String =
            url.replace("/${escape(username.trim())}/${escape(password.trim())}/", "/{u}/{p}/")

        /** Remet les identifiants actuels dans une URL enregistrée avec [templatize]. */
        fun resolve(template: String, username: String, password: String): String =
            template.replace("/{u}/{p}/", "/${escape(username.trim())}/${escape(password.trim())}/")

        fun normalizeUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
            var idx = u.indexOf("/player_api.php", ignoreCase = true)
            if (idx < 0) idx = u.indexOf("/get.php", ignoreCase = true)
            return if (idx > 0) u.substring(0, idx) else u
        }
    }

    private fun esc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun ext(e: String?) = if (e.isNullOrBlank()) "mp4" else e.trim().trimStart('.')

    // ---------- URLs de lecture ----------

    fun liveUrl(streamId: String) = "$baseUrl/live/${esc(username)}/${esc(password)}/$streamId.ts"
    fun movieUrl(streamId: String, extension: String?) = "$baseUrl/movie/${esc(username)}/${esc(password)}/$streamId.${ext(extension)}"
    fun episodeUrl(episodeId: String, extension: String?) = "$baseUrl/series/${esc(username)}/${esc(password)}/$episodeId.${ext(extension)}"

    // ---------- Appels API ----------

    private suspend fun get(action: String?, extra: String = ""): JsonElement = withContext(Dispatchers.IO) {
        var url = "$baseUrl/player_api.php?username=${esc(username)}&password=${esc(password)}"
        if (action != null) url += "&action=$action"
        url += extra
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val body = it.body?.string() ?: throw IOException("Réponse vide")
            json.parseToJsonElement(body)
        }
    }

    suspend fun authenticate(): AccountInfo {
        val root = try {
            get(null)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw XtreamException("Le serveur n'a pas renvoyé une réponse Xtream Codes valide.")
        } catch (e: java.net.SocketTimeoutException) {
            throw XtreamException("Le serveur ne répond pas (délai dépassé).")
        } catch (e: IOException) {
            throw XtreamException("Impossible de joindre le serveur (${e.message}).")
        }
        val ui = (root as? JsonObject)?.get("user_info") as? JsonObject
            ?: throw XtreamException("Identifiants refusés par le serveur.")
        if (str(ui, "auth") != "1") throw XtreamException("Identifiant ou mot de passe incorrect.")
        val status = str(ui, "status") ?: ""
        if (!status.equals("Active", true)) throw XtreamException("Abonnement non actif (statut : $status).")
        val exp = str(ui, "exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
        return AccountInfo(status, exp, str(ui, "max_connections"))
    }

    suspend fun categories(section: Section): List<Category> {
        val action = when (section) {
            Section.Live -> "get_live_categories"
            Section.Movies -> "get_vod_categories"
            Section.Series -> "get_series_categories"
        }
        return array(get(action)).mapNotNull { e ->
            val id = str(e, "category_id") ?: return@mapNotNull null
            Category(id, clean(str(e, "category_name")) ?: id)
        }
    }

    /**
     * Catalogue complet d'une section, lu en flux : la réponse (parfois plus de 100 000 titres)
     * n'est jamais gardée entière en mémoire, ce qui évite les plantages sur les box modestes.
     */
    suspend fun items(section: Section): List<MediaItem> = withContext(Dispatchers.IO) {
        val action = when (section) {
            Section.Live -> "get_live_streams"
            Section.Movies -> "get_vod_streams"
            Section.Series -> "get_series"
        }
        val url = "$baseUrl/player_api.php?username=${esc(username)}&password=${esc(password)}&action=$action"
        val out = ArrayList<MediaItem>()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Réponse vide")
            val reader = com.google.gson.stream.JsonReader(body.charStream())
            reader.isLenient = true
            fun handle(o: Map<String, String>) = toItem(section, o)?.let { out += it }
            when (reader.peek()) {
                com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) handle(readFlat(reader)) else reader.skipValue()
                    }
                    reader.endArray()
                }
                // Certains panels renvoient un objet { "1": {...}, "2": {...} } au lieu d'un tableau.
                com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName()
                        if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) handle(readFlat(reader)) else reader.skipValue()
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        out.trimToSize()
        out
    }

    /** Lit un objet JSON en ne gardant que ses valeurs simples (texte, nombre, booléen). */
    private fun readFlat(reader: com.google.gson.stream.JsonReader): Map<String, String> {
        val map = HashMap<String, String>(32)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                com.google.gson.stream.JsonToken.STRING, com.google.gson.stream.JsonToken.NUMBER -> {
                    val v = reader.nextString().trim()
                    if (v.isNotEmpty()) map[name] = v
                }
                com.google.gson.stream.JsonToken.BOOLEAN -> map[name] = if (reader.nextBoolean()) "1" else "0"
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return map
    }

    private fun toItem(section: Section, e: Map<String, String>): MediaItem? {
        val item = when (section) {
            Section.Live -> MediaItem(
                kind = Section.Live,
                id = e["stream_id"] ?: "",
                title = clean(e["name"]) ?: "",
                imageUrl = img(e["stream_icon"]),
                categoryId = e["category_id"] ?: "",
                added = e["added"]?.toLongOrNull() ?: 0,
            )
            Section.Movies -> MediaItem(
                kind = Section.Movies,
                id = e["stream_id"] ?: "",
                title = clean(e["name"]) ?: "",
                imageUrl = img(e["stream_icon"]),
                categoryId = e["category_id"] ?: "",
                rating = e["rating"],
                year = yearOf(e["year"] ?: e["release_date"]),
                extension = e["container_extension"],
                added = e["added"]?.toLongOrNull() ?: 0,
                tmdbId = e["tmdb"] ?: e["tmdb_id"],
            )
            Section.Series -> MediaItem(
                kind = Section.Series,
                id = e["series_id"] ?: "",
                title = clean(e["name"]) ?: "",
                imageUrl = img(e["cover"]),
                categoryId = e["category_id"] ?: "",
                rating = e["rating"],
                year = yearOf(e["year"] ?: e["releaseDate"] ?: e["release_date"]),
                plot = e["plot"],
                genre = e["genre"],
                added = e["last_modified"]?.toLongOrNull() ?: 0,
                tmdbId = e["tmdb"] ?: e["tmdb_id"],
            )
        }
        return item.takeIf { it.id.isNotEmpty() }
    }

    suspend fun movieInfo(vodId: String): MovieInfo {
        val root = get("get_vod_info", "&vod_id=$vodId")
        val info = obj(root, "info")
        val movie = obj(root, "movie_data")
        return MovieInfo(
            plot = str(info, "plot") ?: str(info, "description"),
            genre = str(info, "genre"),
            director = str(info, "director"),
            cast = str(info, "cast") ?: str(info, "actors"),
            duration = str(info, "duration"),
            releaseDate = str(info, "releasedate") ?: str(info, "release_date"),
            backdrop = firstBackdrop(info),
            rating = str(info, "rating"),
            extension = str(movie, "container_extension"),
        )
    }

    suspend fun seriesInfo(seriesId: String): SeriesInfo {
        val root = get("get_series_info", "&series_id=$seriesId")
        val info = obj(root, "info")
        val seasons = sortedMapOf<Int, MutableList<Episode>>()
        val eps = (root as? JsonObject)?.get("episodes")
        val groups: List<JsonElement> = when (eps) {
            is JsonObject -> eps.values.toList()
            is JsonArray -> eps.toList()
            else -> emptyList()
        }
        for (group in groups) for (ep in array(group)) {
            val epInfo = obj(ep, "info")
            val season = str(ep, "season")?.toIntOrNull() ?: 0
            val episode = Episode(
                id = str(ep, "id") ?: continue,
                number = str(ep, "episode_num")?.toIntOrNull() ?: 0,
                season = season,
                title = clean(str(ep, "title")) ?: "",
                plot = str(epInfo, "plot"),
                image = img(str(epInfo, "movie_image")),
                duration = str(epInfo, "duration"),
                extension = str(ep, "container_extension") ?: "mp4",
            )
            seasons.getOrPut(season) { mutableListOf() }.add(episode)
        }
        val list = seasons.map { (n, e) -> Season(n, e.sortedBy { it.number }) }
            .sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }
        return SeriesInfo(
            plot = str(info, "plot"),
            genre = str(info, "genre"),
            cast = str(info, "cast"),
            releaseDate = str(info, "releaseDate") ?: str(info, "release_date"),
            backdrop = firstBackdrop(info),
            seasons = list,
        )
    }

    /** Programmes en cours et à venir (titres encodés en Base64 par l'API). */
    suspend fun shortEpg(streamId: String, limit: Int = 4): List<EpgEntry> {
        val root = get("get_short_epg", "&stream_id=$streamId&limit=$limit")
        val listings = (root as? JsonObject)?.get("epg_listings") ?: return emptyList()
        val now = System.currentTimeMillis()
        return array(listings).mapNotNull { e ->
            val start = str(e, "start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val stop = str(e, "stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            if (stop <= start) return@mapNotNull null
            EpgEntry(
                title = clean(decodeB64(str(e, "title"))) ?: "",
                description = clean(decodeB64(str(e, "description"))),
                start = start * 1000,
                end = stop * 1000,
            )
        }.filter { it.end > now && it.title.isNotEmpty() }.sortedBy { it.start }
    }

    // ---------- Helpers JSON ----------

    private fun array(e: JsonElement?): List<JsonElement> = (e as? JsonArray)?.toList() ?: emptyList()

    private fun obj(e: JsonElement?, name: String): JsonObject? = ((e as? JsonObject)?.get(name)) as? JsonObject

    private fun str(e: JsonElement?, name: String): String? {
        val v = (e as? JsonObject)?.get(name) ?: return null
        if (v is JsonNull || v !is JsonPrimitive) return null
        val s = v.content.trim()
        return when {
            s.isEmpty() -> null
            !v.isString && s == "true" -> "1"
            !v.isString && s == "false" -> "0"
            else -> s
        }
    }

    private fun firstBackdrop(info: JsonObject?): String? {
        when (val b = info?.get("backdrop_path")) {
            is JsonArray -> b.forEach { x -> (x as? JsonPrimitive)?.content?.let(::img)?.let { return it } }
            is JsonPrimitive -> img(b.content)?.let { return it }
            else -> {}
        }
        return img(str(info, "cover_big") ?: str(info, "movie_image") ?: str(info, "cover"))
    }

    private fun img(url: String?): String? {
        val u = url?.trim() ?: return null
        return if (u.startsWith("http://", true) || u.startsWith("https://", true)) u else null
    }

    private fun clean(s: String?) = s?.replace("\r", " ")?.replace("\n", " ")?.trim()?.ifEmpty { null }

    private fun decodeB64(s: String?): String? {
        if (s.isNullOrEmpty() || s.length % 4 != 0) return s
        return try {
            val bytes = java.util.Base64.getMimeDecoder().decode(s)
            // Décodage strict : un texte en clair qui ressemble à du Base64 (« Info ») donnerait du charabia.
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            if (decoded.any { it.isISOControl() && it !in "\n\r\t" }) s else decoded
        } catch (e: IllegalArgumentException) {
            s
        } catch (e: CharacterCodingException) {
            s
        }
    }
}
