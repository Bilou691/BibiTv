using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using BibiTv.Models;

namespace BibiTv.Services;

/// <summary>
/// Regroupe les chaînes par marque (Canal+, beIN Sports, RMC Sport…) pour retrouver
/// facilement les matchs, et désigne les chaînes les plus regardées en France.
/// </summary>
public static class ChannelBrands
{
    public const string Prefix = "brand:";

    private sealed record Brand(string Name, Regex Pattern);

    // Ordre d'affichage : le sport d'abord.
    private static readonly Brand[] Brands =
    {
        new("Canal+", R(@"(^|\s)(canal\s*\+|canal plus|c\+)")),
        new("beIN Sports", R(@"\bbein\b|\bbein\s*sport")),
        new("RMC Sport", R(@"\brmc\s*sport")),
        new("Eurosport", R(@"\beurosport")),
        new("DAZN", R(@"\bdazn\b")),
        new("Ligue 1+", R(@"\bligue\s*1\s*\+")),
        new("L'Équipe", R(@"\bl\s?equipe\b")),
        new("Prime Video", R(@"\bprime\s*video\b|\bamazon\b")),
        new("Sky", R(@"\bsky\b")),
        new("RMC", R(@"\brmc\s*(story|decouverte|life)\b")),
        new("TF1", R(@"\btf1\b|\btmc\b|\btfx\b|\blci\b")),
        new("France Télévisions", R(@"\bfrance\s*[2345]\b|\bfrance\s*info|\bfranceinfo")),
        new("M6", R(@"\bm6\b|\bw9\b|\b6ter\b|\bgulli\b|\bparis premiere\b|\bteva\b")),
        new("Infos", R(@"\bbfm\s*tv\b|\bbfmtv\b|\bcnews\b|\blci\b|\bfranceinfo\b|\bfrance\s*info\b|\beuronews\b")),
        new("Ciné+ / OCS", R(@"\bcine\s*\+|\bcine plus\b|\bocs\b")),
        new("Disney", R(@"\bdisney")),
    };

    private static Regex R(string pattern) => new(pattern, RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public static IEnumerable<string> Names => Brands.Select(b => b.Name);

    /// <summary>Ajoute les identifiants de marque aux catégories de chaque chaîne.</summary>
    public static void Tag(IEnumerable<MediaItem> channels)
    {
        foreach (var ch in channels)
        {
            var n = Normalize(ch.Title, keepPlus: true);
            foreach (var b in Brands)
                if (b.Pattern.IsMatch(n)) ch.CategoryIds.Add(Prefix + b.Name);
        }
    }

    // ---------- Top 10 des chaînes en France ----------

    // Chaînes les plus regardées en France (parts d'audience Médiamétrie, ordre approximatif).
    private static readonly (string Label, string[] Aliases)[] TopFrance =
    {
        ("TF1", new[] { "tf1" }),
        ("France 2", new[] { "france2" }),
        ("France 3", new[] { "france3" }),
        ("M6", new[] { "m6" }),
        ("France 5", new[] { "france5" }),
        ("CNews", new[] { "cnews" }),
        ("Arte", new[] { "arte" }),
        ("TMC", new[] { "tmc" }),
        ("BFMTV", new[] { "bfmtv", "bfm" }),
        ("W9", new[] { "w9" }),
    };

    private static readonly HashSet<string> QualityTags = new(StringComparer.OrdinalIgnoreCase)
    {
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "raw", "backup", "fr", "tnt", "50fps", "1080p", "720p", "multi",
    };

    public static List<MediaItem> TopChannels(IReadOnlyList<MediaItem> channels, IReadOnlyDictionary<string, string> categoryNames)
    {
        // Index : nom compact (sans préfixe ni étiquette de qualité) → chaînes candidates.
        var index = new Dictionary<string, List<MediaItem>>();
        foreach (var ch in channels)
        {
            if (ch.Title.Contains("+1")) continue; // chaînes décalées
            var key = CompactName(ch.Title);
            if (key.Length == 0) continue;
            if (!index.TryGetValue(key, out var list)) index[key] = list = new();
            list.Add(ch);
        }

        var result = new List<MediaItem>();
        foreach (var (_, aliases) in TopFrance)
        {
            var candidates = aliases.SelectMany(a => index.TryGetValue(a, out var l) ? l : Enumerable.Empty<MediaItem>()).ToList();
            if (candidates.Count == 0) continue;
            result.Add(candidates.OrderByDescending(c => Score(c, categoryNames)).First());
        }
        return result;
    }

    private static int Score(MediaItem ch, IReadOnlyDictionary<string, string> categoryNames)
    {
        var score = 0;
        var cat = categoryNames.TryGetValue(ch.CategoryId, out var c) ? Normalize(c, false) : "";
        if (Regex.IsMatch(cat, @"\b(fr|france|french)\b")) score += 10;
        var t = ch.Title.ToUpperInvariant();
        if (t.Contains("FHD") || t.Contains("1080")) score += 3;
        else if (t.Contains("HD")) score += 2;
        if (t.Contains("4K") || t.Contains("UHD")) score += 1; // souvent réservé aux événements
        if (ch.ImageUrl != null) score += 1;
        return score;
    }

    private static string CompactName(string title)
    {
        // Retire un préfixe « FR | », « FR: », « [FR] »…
        var t = Regex.Replace(title, @"^\s*(\[[^\]]{1,6}\]|[A-Za-z]{2,4}\s*[|:\-–])\s*", "");
        var words = Normalize(t, keepPlus: false).Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Where(w => !QualityTags.Contains(w));
        return string.Concat(words);
    }

    private static string Normalize(string s, bool keepPlus)
    {
        var sb = new StringBuilder(s.Length);
        foreach (var ch in s.Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark) continue;
            if (char.IsLetterOrDigit(ch) || (keepPlus && ch == '+')) sb.Append(char.ToLowerInvariant(ch));
            else sb.Append(' ');
        }
        return Regex.Replace(sb.ToString(), @"\s+", " ").Trim();
    }
}
