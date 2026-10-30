using BibiTv.Models;

namespace BibiTv.Services;

/// <summary>
/// Suggestions « Pour toi » : chaque film/série est décrit par ses catégories et ses genres.
/// Les titres aimés renforcent leurs traits, ceux non aimés les pénalisent, ceux regardés
/// comptent un peu. On propose ensuite les titres non vus qui partagent le plus de traits positifs.
/// </summary>
public static class RecommendationService
{
    private static readonly char[] GenreSeparators = { ',', '/', '&', '|', ';' };

    public static IEnumerable<string> Features(MediaItem item)
    {
        foreach (var c in item.CategoryIds)
            if (!c.StartsWith(ChannelBrands.Prefix, StringComparison.Ordinal)) yield return "c:" + c;
        if (!string.IsNullOrWhiteSpace(item.Genre))
            foreach (var g in item.Genre.Split(GenreSeparators, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                yield return "g:" + g.ToLowerInvariant();
    }

    /// <summary>Poids de chaque trait d'après le profil (catalogue d'une section).</summary>
    public static Dictionary<string, double> BuildWeights(IReadOnlyList<MediaItem> catalog)
    {
        var p = ProfileService.Current;
        var history = p.History.Select(h => h.Key).ToHashSet();
        var weights = new Dictionary<string, double>();
        foreach (var item in catalog)
        {
            double w = p.Likes.Contains(item.Key) ? 2
                : p.Dislikes.Contains(item.Key) ? -2
                : p.Watched.Contains(item.Key) || history.Contains(item.Key) ? 0.75
                : 0;
            if (w == 0) continue;
            foreach (var f in Features(item))
                weights[f] = weights.GetValueOrDefault(f) + w;
        }
        return weights;
    }

    public static bool HasProfile(IReadOnlyList<MediaItem> catalog) => BuildWeights(catalog).Values.Any(v => v > 0);

    public static List<MediaItem> Suggest(IReadOnlyList<MediaItem> catalog, int count = 24)
    {
        var weights = BuildWeights(catalog);
        if (!weights.Values.Any(v => v > 0)) return new();

        var p = ProfileService.Current;
        var history = p.History.Select(h => h.Key).ToHashSet();
        return catalog
            .Where(i => i.ImageUrl != null && !p.Likes.Contains(i.Key) && !p.Dislikes.Contains(i.Key)
                        && !p.Watched.Contains(i.Key) && !history.Contains(i.Key))
            .Select(i =>
            {
                var feats = Features(i).ToList();
                if (feats.Count == 0) return (item: i, score: 0.0);
                var s = feats.Sum(f => weights.GetValueOrDefault(f)) / Math.Sqrt(feats.Count);
                if (s <= 0) return (item: i, score: 0.0); // aucun point commun avec les goûts
                var rating = double.TryParse(i.Rating, System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out var r) ? r : 0;
                return (item: i, score: s + rating * 0.08);
            })
            .Where(x => x.score > 0)
            .OrderByDescending(x => x.score)
            .ThenByDescending(x => x.item.Added)
            .Take(count)
            .Select(x => x.item)
            .ToList();
    }

    /// <summary>Noms des traits préférés (« Drame, Thriller… ») pour expliquer les suggestions.</summary>
    public static List<string> TopTastes(IReadOnlyList<MediaItem> catalog, IReadOnlyDictionary<string, string> categoryNames, int count = 3) =>
        BuildWeights(catalog).Where(kv => kv.Value > 0).OrderByDescending(kv => kv.Value)
            .Select(kv => kv.Key.StartsWith("c:") ? categoryNames.GetValueOrDefault(kv.Key[2..]) : Capitalize(kv.Key[2..]))
            .Where(n => !string.IsNullOrWhiteSpace(n))
            .Select(n => CleanName(n!))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(count)
            .ToList();

    /// <summary>Titres proposés à la notation dans « Personnaliser » : déjà notés d'abord, puis populaires.</summary>
    public static List<MediaItem> Candidates(IReadOnlyList<MediaItem> catalog, int count = 60)
    {
        var p = ProfileService.Current;
        var rated = catalog.Where(i => p.Likes.Contains(i.Key) || p.Dislikes.Contains(i.Key)).ToList();
        var popular = catalog
            .Where(i => i.ImageUrl != null && !rated.Contains(i))
            .Select(i => (item: i, r: double.TryParse(i.Rating, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var r) ? r : 0))
            .Where(x => x.r >= 6)
            .OrderByDescending(x => x.r + Math.Min(x.item.Variants.Count, 4) * 0.3 + (x.item.Added > 0 ? Math.Log10(x.item.Added) * 0.01 : 0))
            .Select(x => x.item)
            .Take(400)
            .ToList();
        // Varie les genres : un titre par catégorie à tour de rôle.
        var mixed = popular.GroupBy(i => i.CategoryId).SelectMany(g => g.Select((item, idx) => (item, idx)))
            .OrderBy(x => x.idx).Select(x => x.item);
        return rated.Concat(mixed).Take(count).ToList();
    }

    private static string Capitalize(string s) => s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..];

    // « FR · Drame » → « Drame »
    private static string CleanName(string s)
    {
        var idx = s.LastIndexOfAny(new[] { '·', '|', ':', '-' });
        var t = idx >= 0 && idx < s.Length - 2 ? s[(idx + 1)..].Trim() : s.Trim();
        return t.Length > 0 ? t : s;
    }
}
