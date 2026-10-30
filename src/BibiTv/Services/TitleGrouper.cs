using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using BibiTv.Models;

namespace BibiTv.Services;

/// <summary>
/// Regroupe les doublons d'un catalogue (même œuvre en 4K, FHD, VOSTFR…) en une seule affiche.
/// Ex. « Verity (2026) 4K MULTI » et « Verity (2026) SUBT AR » → « Verity », 2 versions.
/// </summary>
public static partial class TitleGrouper
{
    [GeneratedRegex(@"^(?<base>.+?)\s*[\(\[](?<year>(19|20)\d{2})[\)\]]\s*(?<rest>.*)$")]
    private static partial Regex YearRegex();

    // Préfixe de langue type « FR - », « FR| », « [EN] ».
    [GeneratedRegex(@"^\s*(\[[A-Z]{2,5}\]|[A-Z]{2,5}\s*[|:\-–])\s*")]
    private static partial Regex PrefixRegex();

    private static readonly HashSet<string> Tags = new(StringComparer.OrdinalIgnoreCase)
    {
        "4K", "UHD", "FHD", "HD", "SD", "HDR", "HDR10", "DV", "HEVC", "H265", "X265", "1080P", "720P", "2160P", "3D",
        "MULTI", "VF", "VFF", "VFQ", "VFI", "VO", "VOST", "VOSTFR", "SUB", "SUBS", "SUBT", "SUBFR", "TRUEFRENCH", "FRENCH",
        "FR", "EN", "AR", "ES", "IT", "DE", "TR", "PT", "NL", "ENG", "ARA", "DUB", "DUBBED", "CAM", "TS",
    };

    public static (string baseTitle, string? year, string tag) Split(string title)
    {
        var t = PrefixRegex().Replace(title.Trim(), "");
        string? year = null;
        var rest = "";

        var m = YearRegex().Match(t);
        if (m.Success)
        {
            t = m.Groups["base"].Value.Trim();
            year = m.Groups["year"].Value;
            rest = m.Groups["rest"].Value;
        }

        // Retire les étiquettes de qualité/langue en fin de titre.
        var tokens = t.Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
        var trailing = new List<string>();
        while (tokens.Count > 1 && Tags.Contains(tokens[^1].Trim('(', ')', '[', ']', '-', '|')))
        {
            trailing.Insert(0, tokens[^1].Trim('(', ')', '[', ']'));
            tokens.RemoveAt(tokens.Count - 1);
        }
        var baseTitle = string.Join(' ', tokens).TrimEnd('-', '|', ':', ' ');

        var tag = string.Join(' ', new[] { string.Join(' ', trailing), rest.Trim('-', '|', ' ') }.Where(s => s.Length > 0));
        return (baseTitle.Length > 0 ? baseTitle : title.Trim(), year, tag);
    }

    /// <summary>
    /// Construit les fiches regroupées. Les chaînes TV ne sont jamais fusionnées.
    /// <paramref name="isFrench"/> sert à choisir le titre affiché quand une œuvre existe en plusieurs langues.
    /// </summary>
    public static List<MediaItem> Group(List<MediaItem> items, bool merge, Func<MediaItem, bool>? isFrench = null)
    {
        if (!merge)
        {
            foreach (var i in items)
            {
                i.GroupKey = i.Id;
                i.Variants = new List<MediaItem> { i };
                i.CategoryIds = new HashSet<string> { i.CategoryId };
            }
            return items;
        }

        // 1. Regroupement par titre normalisé + année
        var groups = new Dictionary<string, List<(MediaItem item, string baseTitle, string? year)>>();
        var order = new List<string>();
        foreach (var item in items)
        {
            var (baseTitle, year, tag) = Split(item.Title);
            item.Tag = tag;
            var key = Normalize(baseTitle) + "|" + (year ?? item.Year ?? "");
            if (!groups.TryGetValue(key, out var list))
            {
                groups[key] = list = new();
                order.Add(key);
            }
            list.Add((item, baseTitle, year ?? item.Year));
        }

        // 2. Fusion des titres traduits (« The Birthday Gift » / « Das Geschenk ») :
        //    même identifiant TMDB, ou même affiche (si elle n'est pas une image générique).
        var parent = order.ToDictionary(k => k, k => k);
        var rank = order.Select((k, i) => (k, i)).ToDictionary(x => x.k, x => x.i);
        string Find(string k) { while (parent[k] != k) k = parent[k] = parent[parent[k]]; return k; }
        void Union(string a, string b)
        {
            var (ra, rb) = (Find(a), Find(b));
            if (ra == rb) return;
            // La racine reste le groupe vu en premier (clé stable pour l'historique).
            if (rank[ra] <= rank[rb]) parent[rb] = ra; else parent[ra] = rb;
        }
        static string? YearOf(string key) => key[(key.LastIndexOf('|') + 1)..] is { Length: > 0 } y ? y : null;
        static bool SameYear(string a, string b) => YearOf(a) is not { } ya || YearOf(b) is not { } yb || ya == yb;

        var byTmdb = new Dictionary<string, string>();
        var imageUse = items.Where(i => i.ImageUrl != null).GroupBy(i => i.ImageUrl!).ToDictionary(g => g.Key, g => g.Count());
        var byImage = new Dictionary<string, string>();
        foreach (var key in order)
        {
            foreach (var (item, _, _) in groups[key])
            {
                if (!string.IsNullOrEmpty(item.TmdbId) && item.TmdbId != "0")
                {
                    if (byTmdb.TryGetValue(item.TmdbId, out var other)) Union(other, key);
                    else byTmdb[item.TmdbId] = key;
                }
                if (item.ImageUrl != null && imageUse[item.ImageUrl] <= 6)
                {
                    if (byImage.TryGetValue(item.ImageUrl, out var other) && SameYear(other, key)) Union(other, key);
                    else byImage.TryAdd(item.ImageUrl, key);
                }
            }
        }

        var merged = new Dictionary<string, List<(MediaItem item, string baseTitle, string? year)>>();
        var mergedOrder = new List<string>();
        foreach (var key in order)
        {
            var root = Find(key);
            if (!merged.TryGetValue(root, out var list))
            {
                merged[root] = list = new();
                mergedOrder.Add(root);
            }
            list.AddRange(groups[key]);
        }

        // 3. Une fiche par groupe
        var result = new List<MediaItem>(mergedOrder.Count);
        foreach (var key in mergedOrder)
        {
            var list = merged[key];
            var variants = list.Select(x => x.item).OrderByDescending(v => QualityRank(v.Tag)).ToList();
            var best = variants.FirstOrDefault(v => v.ImageUrl != null) ?? variants[0];
            var display = (isFrench != null ? list.FirstOrDefault(x => isFrench(x.item)) : default);
            if (display.item == null) display = list[0];
            result.Add(new MediaItem
            {
                Kind = best.Kind,
                Id = best.Id,
                Title = display.baseTitle,
                ImageUrl = (isFrench != null ? variants.FirstOrDefault(v => v.ImageUrl != null && isFrench(v))?.ImageUrl : null) ?? best.ImageUrl,
                CategoryId = best.CategoryId,
                Rating = variants.Select(v => v.Rating).FirstOrDefault(r => MediaItem.FormatRating(r) != null),
                Year = display.year ?? list.Select(x => x.year).FirstOrDefault(y => y != null),
                Extension = best.Extension,
                Plot = variants.Select(v => v.Plot).FirstOrDefault(p => !string.IsNullOrWhiteSpace(p)),
                Genre = variants.Select(v => v.Genre).FirstOrDefault(g => !string.IsNullOrWhiteSpace(g)),
                Added = variants.Max(v => v.Added),
                TmdbId = variants.Select(v => v.TmdbId).FirstOrDefault(t => !string.IsNullOrEmpty(t) && t != "0"),
                Variants = variants,
                GroupKey = key,
                CategoryIds = variants.Select(v => v.CategoryId).ToHashSet(),
            });
        }
        return result;
    }

    private static int QualityRank(string tag)
    {
        var t = tag.ToUpperInvariant();
        var rank = t.Contains("4K") || t.Contains("UHD") || t.Contains("2160") ? 40
            : t.Contains("FHD") || t.Contains("1080") ? 30
            : t.Contains("HD") || t.Contains("720") ? 20
            : 10;
        if (t.Contains("MULTI")) rank += 3;
        else if (t.Contains("VF") || t.Contains("FRENCH")) rank += 2;
        if (t.Contains("SUB") || t.Contains("VOST")) rank -= 1;
        return rank;
    }

    private static string Normalize(string s)
    {
        var sb = new StringBuilder(s.Length);
        foreach (var c in s.Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.NonSpacingMark) continue;
            if (char.IsLetterOrDigit(c)) sb.Append(char.ToLowerInvariant(c));
            else if (sb.Length > 0 && sb[^1] != ' ') sb.Append(' ');
        }
        return sb.ToString().Trim();
    }
}
