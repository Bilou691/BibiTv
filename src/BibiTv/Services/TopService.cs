using System.Globalization;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using BibiTv.Models;

namespace BibiTv.Services;

public sealed record RankedItem(int Rank, MediaItem Item)
{
    public string RankText => Rank.ToString(CultureInfo.InvariantCulture);
}

/// <summary>
/// Top 10 de la semaine.
/// - Avec une clé TMDB : vraies tendances de la semaine, retrouvées dans le catalogue.
/// - Sans clé : nouveautés de la semaine les mieux notées du catalogue.
/// </summary>
public static class TopService
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    public static async Task<(List<RankedItem> items, bool fromTmdb)> TopAsync(Section kind, IReadOnlyList<MediaItem> catalog, CancellationToken ct = default)
    {
        var key = TmdbKey;
        if (!string.IsNullOrWhiteSpace(key))
        {
            try
            {
                var trending = await TmdbTrendingAsync(kind, key.Trim(), ct);
                var matched = Match(trending, catalog);
                if (matched.Count >= 3) return (Rank(matched), true);
            }
            catch (Exception) when (!ct.IsCancellationRequested)
            {
                // Clé invalide ou TMDB injoignable : on retombe sur le classement du catalogue.
            }
        }
        return (Rank(FromCatalog(catalog)), false);
    }

    /// <summary>
    /// Clé TMDB : celle des réglages si présente, sinon celle intégrée à la compilation
    /// (secret GitHub TMDB_KEY, jamais écrit dans le code source).
    /// </summary>
    public static string? TmdbKey
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(SettingsService.Current.TmdbKey)) return SettingsService.Current.TmdbKey;
            return typeof(TopService).Assembly
                .GetCustomAttributes(typeof(System.Reflection.AssemblyMetadataAttribute), false)
                .OfType<System.Reflection.AssemblyMetadataAttribute>()
                .FirstOrDefault(a => a.Key == "TmdbKey")?.Value;
        }
    }

    private static List<RankedItem> Rank(IEnumerable<MediaItem> items) =>
        items.Take(10).Select((m, i) => new RankedItem(i + 1, m)).ToList();

    /// <summary>Ajouts les plus récents (7 jours, élargi si besoin), triés par note.</summary>
    public static List<MediaItem> FromCatalog(IReadOnlyList<MediaItem> catalog)
    {
        var withPoster = catalog.Where(i => i.ImageUrl != null).ToList();
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        foreach (var days in new[] { 7, 14, 30, 90 })
        {
            var since = now - days * 86400L;
            var recent = withPoster.Where(i => i.Added >= since).ToList();
            if (recent.Count >= 10)
                return recent.OrderByDescending(Score).ThenByDescending(i => i.Added).ToList();
        }
        return withPoster.OrderByDescending(i => i.Added).Take(60).OrderByDescending(Score).ToList();
    }

    private static double Score(MediaItem i)
    {
        var rating = double.TryParse(i.Rating, NumberStyles.Float, CultureInfo.InvariantCulture, out var r) ? r : 0;
        return rating + Math.Min(i.Variants.Count, 4) * 0.15; // plusieurs versions = titre très demandé
    }

    // ---------- TMDB ----------

    private sealed record TmdbTitle(string Id, string Title, string? OriginalTitle, string? Year);

    private static async Task<List<TmdbTitle>> TmdbTrendingAsync(Section kind, string key, CancellationToken ct)
    {
        var type = kind == Section.Series ? "tv" : "movie";
        var list = new List<TmdbTitle>();
        foreach (var page in new[] { 1, 2 })
        {
            var url = $"https://api.themoviedb.org/3/trending/{type}/week?language=fr-FR&page={page}";
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            // Jeton v4 (« eyJ… ») en en-tête, clé v3 en paramètre.
            if (key.StartsWith("eyJ", StringComparison.Ordinal))
                req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
            else
                req.RequestUri = new Uri(url + "&api_key=" + Uri.EscapeDataString(key));

            using var resp = await Http.SendAsync(req, ct);
            resp.EnsureSuccessStatusCode();
            using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync(ct));
            foreach (var r in doc.RootElement.GetProperty("results").EnumerateArray())
            {
                string? S(string n) => r.TryGetProperty(n, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
                var date = S("release_date") ?? S("first_air_date");
                list.Add(new TmdbTitle(
                    r.GetProperty("id").GetRawText(),
                    S("title") ?? S("name") ?? "",
                    S("original_title") ?? S("original_name"),
                    date != null && date.Length >= 4 ? date[..4] : null));
            }
        }
        return list;
    }

    private static List<MediaItem> Match(List<TmdbTitle> trending, IReadOnlyList<MediaItem> catalog)
    {
        var byTmdb = new Dictionary<string, MediaItem>();
        var byTitle = new Dictionary<string, List<MediaItem>>();
        foreach (var item in catalog)
        {
            if (!string.IsNullOrEmpty(item.TmdbId)) byTmdb.TryAdd(item.TmdbId, item);
            var k = Normalize(item.Title);
            if (!byTitle.TryGetValue(k, out var l)) byTitle[k] = l = new();
            l.Add(item);
        }

        var result = new List<MediaItem>();
        foreach (var t in trending)
        {
            MediaItem? found = byTmdb.GetValueOrDefault(t.Id);
            if (found == null)
            {
                foreach (var title in new[] { t.Title, t.OriginalTitle })
                {
                    if (string.IsNullOrWhiteSpace(title) || !byTitle.TryGetValue(Normalize(title), out var cands)) continue;
                    found = cands.FirstOrDefault(c => t.Year == null || c.Year == null || c.Year == t.Year) ?? cands[0];
                    break;
                }
            }
            if (found != null && !result.Contains(found)) result.Add(found);
            if (result.Count == 10) break;
        }
        return result;
    }

    private static string Normalize(string s)
    {
        var sb = new StringBuilder(s.Length);
        foreach (var c in s.Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.NonSpacingMark) continue;
            if (char.IsLetterOrDigit(c)) sb.Append(char.ToLowerInvariant(c));
        }
        return sb.ToString();
    }
}
