package com.bibitv.tv.data

import java.text.Normalizer

/**
 * Regroupe les doublons du catalogue (même œuvre en 4K, FHD, VOSTFR, autre langue…) en une seule fiche.
 * Identique à la version Windows.
 */
object TitleGrouper {

    private val yearRegex = Regex("""^(?<base>.+?)\s*[(\[](?<year>(19|20)\d{2})[)\]]\s*(?<rest>.*)$""")
    private val prefixRegex = Regex("""^\s*(\[[A-Z]{2,5}]|[A-Z]{2,5}\s*[|:\-–])\s*""")

    private val tags = setOf(
        "4K", "UHD", "FHD", "HD", "SD", "HDR", "HDR10", "DV", "HEVC", "H265", "X265", "1080P", "720P", "2160P", "3D",
        "MULTI", "VF", "VFF", "VFQ", "VFI", "VO", "VOST", "VOSTFR", "SUB", "SUBS", "SUBT", "SUBFR", "TRUEFRENCH", "FRENCH",
        "FR", "EN", "AR", "ES", "IT", "DE", "TR", "PT", "NL", "ENG", "ARA", "DUB", "DUBBED", "CAM", "TS",
    )

    data class Split(val baseTitle: String, val year: String?, val tag: String)

    fun split(title: String): Split {
        var t = prefixRegex.replace(title.trim(), "")
        var year: String? = null
        var rest = ""
        yearRegex.find(t)?.let { m ->
            t = m.groups["base"]!!.value.trim()
            year = m.groups["year"]!!.value
            rest = m.groups["rest"]!!.value
        }
        val tokens = t.split(' ').filter { it.isNotEmpty() }.toMutableList()
        val trailing = mutableListOf<String>()
        while (tokens.size > 1 && tokens.last().trim('(', ')', '[', ']', '-', '|').uppercase() in tags) {
            trailing.add(0, tokens.last().trim('(', ')', '[', ']'))
            tokens.removeAt(tokens.size - 1)
        }
        val base = tokens.joinToString(" ").trimEnd('-', '|', ':', ' ')
        val tag = listOf(trailing.joinToString(" "), rest.trim('-', '|', ' ')).filter { it.isNotEmpty() }.joinToString(" ")
        return Split(base.ifEmpty { title.trim() }, year, tag)
    }

    fun group(items: List<MediaItem>, merge: Boolean, isFrench: ((MediaItem) -> Boolean)? = null): List<MediaItem> {
        if (!merge) {
            items.forEach {
                it.groupKey = it.id
                it.variants = listOf(it)
                it.categoryIds = mutableSetOf(it.categoryId)
            }
            return items
        }

        // 1. Titre normalisé + année
        data class Entry(val item: MediaItem, val baseTitle: String, val year: String?)
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        for (item in items) {
            val s = split(item.title)
            item.tag = s.tag
            val key = normalize(s.baseTitle) + "|" + (s.year ?: item.year ?: "")
            groups.getOrPut(key) { mutableListOf() }.add(Entry(item, s.baseTitle, s.year ?: item.year))
        }
        val order = groups.keys.toList()
        val rank = order.withIndex().associate { it.value to it.index }

        // 2. Titres traduits : même identifiant TMDB ou même affiche (hors images génériques).
        val parent = HashMap<String, String>(order.size * 2).apply { order.forEach { put(it, it) } }
        fun find(k: String): String {
            var x = k
            while (parent[x] != x) {
                val p = parent[x]!!
                parent[x] = parent[p]!!
                x = parent[x]!!
            }
            return x
        }
        fun union(a: String, b: String) {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            if (rank[ra]!! <= rank[rb]!!) parent[rb] = ra else parent[ra] = rb
        }
        fun yearOfKey(k: String) = k.substringAfterLast('|').ifEmpty { null }
        fun sameYear(a: String, b: String): Boolean {
            val ya = yearOfKey(a) ?: return true
            val yb = yearOfKey(b) ?: return true
            return ya == yb
        }

        val imageUse = items.mapNotNull { it.imageUrl }.groupingBy { it }.eachCount()
        val byTmdb = HashMap<String, String>()
        val byImage = HashMap<String, String>()
        for (key in order) for (e in groups[key]!!) {
            val tmdb = e.item.tmdbId
            if (!tmdb.isNullOrEmpty() && tmdb != "0") {
                val other = byTmdb[tmdb]
                if (other != null) union(other, key) else byTmdb[tmdb] = key
            }
            val img = e.item.imageUrl
            if (img != null && (imageUse[img] ?: 0) <= 6) {
                val other = byImage[img]
                if (other != null && sameYear(other, key)) union(other, key) else byImage.putIfAbsent(img, key)
            }
        }

        val merged = LinkedHashMap<String, MutableList<Entry>>()
        for (key in order) merged.getOrPut(find(key)) { mutableListOf() }.addAll(groups[key]!!)

        // 3. Une fiche par groupe
        return merged.map { (key, list) ->
            val variants = list.map { it.item }.sortedByDescending { qualityRank(it.tag) }
            val best = variants.firstOrNull { it.imageUrl != null } ?: variants[0]
            val display = isFrench?.let { f -> list.firstOrNull { f(it.item) } } ?: list[0]
            val image = isFrench?.let { f -> variants.firstOrNull { it.imageUrl != null && f(it) }?.imageUrl } ?: best.imageUrl
            MediaItem(
                kind = best.kind,
                id = best.id,
                title = display.baseTitle,
                imageUrl = image,
                categoryId = best.categoryId,
                rating = variants.map { it.rating }.firstOrNull { MediaItem.formatRating(it) != null },
                year = display.year ?: list.firstNotNullOfOrNull { it.year },
                extension = best.extension,
                plot = variants.firstNotNullOfOrNull { it.plot?.takeIf { p -> p.isNotBlank() } },
                genre = variants.firstNotNullOfOrNull { it.genre?.takeIf { g -> g.isNotBlank() } },
                added = variants.maxOf { it.added },
                tmdbId = variants.firstNotNullOfOrNull { it.tmdbId?.takeIf { t -> t.isNotEmpty() && t != "0" } },
            ).apply {
                this.variants = variants
                groupKey = key
                categoryIds = variants.map { it.categoryId }.toMutableSet()
            }
        }
    }

    private fun qualityRank(tag: String): Int {
        val t = tag.uppercase()
        var rank = when {
            "4K" in t || "UHD" in t || "2160" in t -> 40
            "FHD" in t || "1080" in t -> 30
            "HD" in t || "720" in t -> 20
            else -> 10
        }
        if ("MULTI" in t) rank += 3 else if ("VF" in t || "FRENCH" in t) rank += 2
        if ("SUB" in t || "VOST" in t) rank -= 1
        return rank
    }

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in Normalizer.normalize(s, Normalizer.Form.NFD)) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
            else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().trim()
    }
}
