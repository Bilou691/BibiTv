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

    /// <summary>Construit les fiches regroupées. Les chaînes TV ne sont jamais fusionnées.</summary>
    public static List<MediaItem> Group(List<MediaItem> items, bool merge)
    {
        if (!merge)
        {
            foreach (var i in items)
            {
                i.Variants = new List<MediaItem> { i };
                i.CategoryIds = new HashSet<string> { i.CategoryId };
            }
            return items;
        }

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

        var result = new List<MediaItem>(order.Count);
        foreach (var key in order)
        {
            var list = groups[key];
            var variants = list.Select(x => x.item).OrderByDescending(v => QualityRank(v.Tag)).ToList();
            var best = variants.FirstOrDefault(v => v.ImageUrl != null) ?? variants[0];
            result.Add(new MediaItem
            {
                Kind = best.Kind,
                Id = best.Id,
                Title = list[0].baseTitle,
                ImageUrl = best.ImageUrl,
                CategoryId = best.CategoryId,
                Rating = variants.Select(v => v.Rating).FirstOrDefault(r => MediaItem.FormatRating(r) != null),
                Year = list[0].year,
                Extension = best.Extension,
                Plot = variants.Select(v => v.Plot).FirstOrDefault(p => !string.IsNullOrWhiteSpace(p)),
                Genre = variants.Select(v => v.Genre).FirstOrDefault(g => !string.IsNullOrWhiteSpace(g)),
                Added = variants.Max(v => v.Added),
                TmdbId = variants.Select(v => v.TmdbId).FirstOrDefault(t => !string.IsNullOrEmpty(t) && t != "0"),
                Variants = variants,
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
