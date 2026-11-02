package com.bibitv.tv.data

import java.text.Normalizer

/** Chaînes par marque (Canal+, beIN Sports, RMC Sport…) et Top 10 des chaînes les plus regardées en France. */
object ChannelBrands {
    const val PREFIX = "brand:"

    private class Brand(val name: String, val pattern: Regex)

    private val brands = listOf(
        Brand("Canal+", Regex("""(^|\s)(canal\s*\+|canal plus|c\+)""")),
        Brand("beIN Sports", Regex("""\bbein\b|\bbein\s*sport""")),
        Brand("RMC Sport", Regex("""\brmc\s*sport""")),
        Brand("Eurosport", Regex("""\beurosport""")),
        Brand("DAZN", Regex("""\bdazn\b""")),
        Brand("Ligue 1+", Regex("""\bligue\s*1\s*\+""")),
        Brand("L'Équipe", Regex("""\bl\s?equipe\b""")),
        Brand("Prime Video", Regex("""\bprime\s*video\b|\bamazon\b""")),
        Brand("Sky", Regex("""\bsky\b""")),
        Brand("RMC", Regex("""\brmc\s*(story|decouverte|life)\b""")),
        Brand("TF1", Regex("""\btf1\b|\btmc\b|\btfx\b|\blci\b""")),
        Brand("France Télévisions", Regex("""\bfrance\s*[2345]\b|\bfrance\s*info|\bfranceinfo""")),
        Brand("M6", Regex("""\bm6\b|\bw9\b|\b6ter\b|\bgulli\b|\bparis premiere\b|\bteva\b""")),
        Brand("Infos", Regex("""\bbfm\s*tv\b|\bbfmtv\b|\bcnews\b|\blci\b|\bfranceinfo\b|\bfrance\s*info\b|\beuronews\b""")),
        Brand("Ciné+ / OCS", Regex("""\bcine\s*\+|\bcine plus\b|\bocs\b""")),
        Brand("Disney", Regex("""\bdisney""")),
    )

    val names: List<String> get() = brands.map { it.name }

    fun tag(channels: List<MediaItem>) {
        for (ch in channels) {
            val n = normalize(ch.title, keepPlus = true)
            for (b in brands) if (b.pattern.containsMatchIn(n)) ch.categoryIds.add(PREFIX + b.name)
        }
    }

    // Parts d'audience Médiamétrie (ordre approximatif)
    private val topFrance = listOf(
        listOf("tf1"), listOf("france2"), listOf("france3"), listOf("m6"), listOf("france5"),
        listOf("cnews"), listOf("arte"), listOf("tmc"), listOf("bfmtv", "bfm"), listOf("w9"),
    )

    private val qualityTags = setOf("hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "raw", "backup", "fr", "tnt", "50fps", "1080p", "720p", "multi")

    fun topChannels(channels: List<MediaItem>, categoryNames: Map<String, String>): List<MediaItem> {
        val index = HashMap<String, MutableList<MediaItem>>()
        for (ch in channels) {
            if ("+1" in ch.title) continue
            val key = compactName(ch.title)
            if (key.isNotEmpty()) index.getOrPut(key) { mutableListOf() }.add(ch)
        }
        return topFrance.mapNotNull { aliases ->
            aliases.flatMap { index[it] ?: emptyList() }.maxByOrNull { score(it, categoryNames) }
        }
    }

    private fun score(ch: MediaItem, categoryNames: Map<String, String>): Int {
        var s = 0
        val cat = normalize(categoryNames[ch.categoryId] ?: "", false)
        if (Regex("""\b(fr|france|french)\b""").containsMatchIn(cat)) s += 10
        val t = ch.title.uppercase()
        if ("FHD" in t || "1080" in t) s += 3 else if ("HD" in t) s += 2
        if ("4K" in t || "UHD" in t) s += 1
        if (ch.imageUrl != null) s += 1
        return s
    }

    private fun compactName(title: String): String {
        val t = Regex("""^\s*(\[[^]]{1,6}]|[A-Za-z]{2,4}\s*[|:\-–])\s*""").replace(title, "")
        return normalize(t, false).split(' ').filter { it.isNotEmpty() && it !in qualityTags }.joinToString("")
    }

    private fun normalize(s: String, keepPlus: Boolean): String {
        val sb = StringBuilder(s.length)
        for (ch in Normalizer.normalize(s, Normalizer.Form.NFD)) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch.isLetterOrDigit() || (keepPlus && ch == '+')) sb.append(ch.lowercaseChar()) else sb.append(' ')
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }
}
