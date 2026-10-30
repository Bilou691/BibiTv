using System.Globalization;
using System.Net.Http;
using System.Text.Json;
using BibiTv.Models;

namespace BibiTv.Services;

/// <summary>
/// Client de l'API "player_api.php" des panels Xtream Codes.
/// Les réponses de ces panels sont très hétérogènes (nombres envoyés en texte et
/// inversement, tableaux vides à la place d'objets…), d'où un parsing défensif.
/// </summary>
public sealed class XtreamClient
{
    private static readonly HttpClient Http = CreateHttp();

    public string BaseUrl { get; }
    public string Username { get; }
    public string Password { get; }

    public XtreamClient(string baseUrl, string username, string password)
    {
        BaseUrl = NormalizeUrl(baseUrl);
        Username = username.Trim();
        Password = password.Trim();
    }

    private static HttpClient CreateHttp()
    {
        var handler = new HttpClientHandler { AutomaticDecompression = System.Net.DecompressionMethods.All };
        var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(60) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(PlayerUserAgent);
        return http;
    }

    public const string PlayerUserAgent = "VLC/3.0.21 LibVLC/3.0.21";

    public static string NormalizeUrl(string url)
    {
        url = url.Trim().TrimEnd('/');
        if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
            !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            url = "http://" + url;
        // On accepte aussi une URL collée avec "/player_api.php" ou "/get.php…"
        var idx = url.IndexOf("/player_api.php", StringComparison.OrdinalIgnoreCase);
        if (idx < 0) idx = url.IndexOf("/get.php", StringComparison.OrdinalIgnoreCase);
        return idx > 0 ? url[..idx] : url;
    }

    // ---------- URLs de lecture ----------

    public string LiveUrl(string streamId) => $"{BaseUrl}/live/{Esc(Username)}/{Esc(Password)}/{streamId}.ts";
    public string MovieUrl(string streamId, string? ext) => $"{BaseUrl}/movie/{Esc(Username)}/{Esc(Password)}/{streamId}.{Ext(ext)}";
    public string EpisodeUrl(string episodeId, string? ext) => $"{BaseUrl}/series/{Esc(Username)}/{Esc(Password)}/{episodeId}.{Ext(ext)}";

    private static string Esc(string s) => Uri.EscapeDataString(s);
    private static string Ext(string? ext) => string.IsNullOrWhiteSpace(ext) ? "mp4" : ext.Trim().TrimStart('.');

    // ---------- Appels API ----------

    private async Task<JsonElement> GetAsync(string? action, string extra = "", CancellationToken ct = default)
    {
        var url = $"{BaseUrl}/player_api.php?username={Esc(Username)}&password={Esc(Password)}";
        if (action != null) url += $"&action={action}";
        url += extra;

        // HttpClient.Timeout ne couvre pas la lecture du corps : on borne l'appel complet.
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromMinutes(2));
        try
        {
            using var resp = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
            resp.EnsureSuccessStatusCode();
            await using var stream = await resp.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: timeout.Token).ConfigureAwait(false);
            return doc.RootElement.Clone();
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new XtreamException("Le serveur met trop de temps à répondre. Réessayez dans un instant.");
        }
    }

    public async Task<AccountInfo> AuthenticateAsync(CancellationToken ct = default)
    {
        JsonElement root;
        try
        {
            root = await GetAsync(null, ct: ct).ConfigureAwait(false);
        }
        catch (JsonException)
        {
            throw new XtreamException("Le serveur n'a pas renvoyé une réponse Xtream Codes valide.");
        }
        catch (HttpRequestException ex)
        {
            throw new XtreamException($"Impossible de joindre le serveur ({ex.Message}).");
        }
        catch (TaskCanceledException)
        {
            throw new XtreamException("Le serveur ne répond pas (délai dépassé).");
        }

        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("user_info", out var ui) || ui.ValueKind != JsonValueKind.Object)
            throw new XtreamException("Identifiants refusés par le serveur.");
        if (Str(ui, "auth") != "1")
            throw new XtreamException("Identifiant ou mot de passe incorrect.");

        var status = Str(ui, "status") ?? "";
        if (!status.Equals("Active", StringComparison.OrdinalIgnoreCase))
            throw new XtreamException($"Abonnement non actif (statut : {status}).");

        DateTime? exp = null;
        if (long.TryParse(Str(ui, "exp_date"), out var ts) && ts > 0)
            exp = DateTimeOffset.FromUnixTimeSeconds(ts).LocalDateTime;

        return new AccountInfo { Status = status, Expiration = exp, MaxConnections = Str(ui, "max_connections") };
    }

    public async Task<List<Category>> GetCategoriesAsync(Section section, CancellationToken ct = default)
    {
        var action = section switch
        {
            Section.Live => "get_live_categories",
            Section.Movies => "get_vod_categories",
            _ => "get_series_categories",
        };
        var root = await GetAsync(action, ct: ct).ConfigureAwait(false);
        var list = new List<Category>();
        foreach (var e in Array(root))
        {
            var id = Str(e, "category_id");
            if (id == null) continue;
            list.Add(new Category { Id = id, Name = Clean(Str(e, "category_name")) ?? id });
        }
        return list;
    }

    public async Task<List<MediaItem>> GetItemsAsync(Section section, CancellationToken ct = default)
    {
        var action = section switch
        {
            Section.Live => "get_live_streams",
            Section.Movies => "get_vod_streams",
            _ => "get_series",
        };
        var root = await GetAsync(action, ct: ct).ConfigureAwait(false);
        var list = new List<MediaItem>();
        foreach (var e in Array(root))
        {
            var item = section switch
            {
                Section.Live => new MediaItem
                {
                    Kind = Section.Live,
                    Id = Str(e, "stream_id") ?? "",
                    Title = Clean(Str(e, "name")) ?? "",
                    ImageUrl = Img(Str(e, "stream_icon")),
                    CategoryId = Str(e, "category_id") ?? "",
                    Added = Long(Str(e, "added")),
                },
                Section.Movies => new MediaItem
                {
                    Kind = Section.Movies,
                    Id = Str(e, "stream_id") ?? "",
                    Title = Clean(Str(e, "name")) ?? "",
                    ImageUrl = Img(Str(e, "stream_icon")),
                    CategoryId = Str(e, "category_id") ?? "",
                    Rating = Str(e, "rating"),
                    Year = Year(Str(e, "year") ?? Str(e, "release_date")),
                    Extension = Str(e, "container_extension"),
                    Added = Long(Str(e, "added")),
                    TmdbId = Str(e, "tmdb") ?? Str(e, "tmdb_id"),
                },
                _ => new MediaItem
                {
                    Kind = Section.Series,
                    Id = Str(e, "series_id") ?? "",
                    Title = Clean(Str(e, "name")) ?? "",
                    ImageUrl = Img(Str(e, "cover")),
                    CategoryId = Str(e, "category_id") ?? "",
                    Rating = Str(e, "rating"),
                    Year = Year(Str(e, "year") ?? Str(e, "releaseDate") ?? Str(e, "release_date")),
                    Plot = Str(e, "plot"),
                    Genre = Str(e, "genre"),
                    Added = Long(Str(e, "last_modified")),
                    TmdbId = Str(e, "tmdb") ?? Str(e, "tmdb_id"),
                },
            };
            if (item.Id.Length > 0) list.Add(item);
        }
        return list;
    }

    public async Task<MovieInfo> GetMovieInfoAsync(string vodId, CancellationToken ct = default)
    {
        var root = await GetAsync("get_vod_info", $"&vod_id={vodId}", ct).ConfigureAwait(false);
        var info = Obj(root, "info");
        var movie = Obj(root, "movie_data");
        return new MovieInfo
        {
            Plot = Str(info, "plot") ?? Str(info, "description"),
            Genre = Str(info, "genre"),
            Director = Str(info, "director"),
            Cast = Str(info, "cast") ?? Str(info, "actors"),
            Duration = Str(info, "duration"),
            ReleaseDate = Str(info, "releasedate") ?? Str(info, "release_date"),
            Rating = Str(info, "rating"),
            Backdrop = FirstBackdrop(info),
            Extension = Str(movie, "container_extension"),
        };
    }

    public async Task<SeriesInfo> GetSeriesInfoAsync(string seriesId, CancellationToken ct = default)
    {
        var root = await GetAsync("get_series_info", $"&series_id={seriesId}", ct).ConfigureAwait(false);
        var info = Obj(root, "info");

        var seasons = new Dictionary<int, Season>();
        if (root.ValueKind == JsonValueKind.Object && root.TryGetProperty("episodes", out var eps))
        {
            // "episodes" est normalement un objet { "1": [...], "2": [...] } mais parfois un tableau.
            IEnumerable<JsonElement> groups = eps.ValueKind switch
            {
                JsonValueKind.Object => eps.EnumerateObject().Select(p => p.Value),
                JsonValueKind.Array => eps.EnumerateArray(),
                _ => Enumerable.Empty<JsonElement>(),
            };
            foreach (var group in groups)
            {
                foreach (var ep in Array(group))
                {
                    var epInfo = Obj(ep, "info");
                    var seasonNum = (int)Long(Str(ep, "season"));
                    var episode = new Episode
                    {
                        Id = Str(ep, "id") ?? "",
                        Number = (int)Long(Str(ep, "episode_num")),
                        Season = seasonNum,
                        Title = Clean(Str(ep, "title")) ?? "",
                        Extension = Str(ep, "container_extension") ?? "mp4",
                        Plot = Str(epInfo, "plot"),
                        Image = Img(Str(epInfo, "movie_image")),
                        Duration = Str(epInfo, "duration"),
                    };
                    if (episode.Id.Length == 0) continue;
                    if (!seasons.TryGetValue(seasonNum, out var season))
                        seasons[seasonNum] = season = new Season { Number = seasonNum };
                    season.Episodes.Add(episode);
                }
            }
        }

        foreach (var s in seasons.Values)
            s.Episodes.Sort((a, b) => a.Number.CompareTo(b.Number));

        return new SeriesInfo
        {
            Plot = Str(info, "plot"),
            Genre = Str(info, "genre"),
            Cast = Str(info, "cast"),
            ReleaseDate = Str(info, "releaseDate") ?? Str(info, "release_date"),
            Backdrop = FirstBackdrop(info),
            Seasons = seasons.Values.OrderBy(s => s.Number == 0 ? int.MaxValue : s.Number).ToList(),
        };
    }

    /// <summary>Programmes en cours et à venir d'une chaîne (titres encodés en Base64 par l'API).</summary>
    public async Task<List<EpgEntry>> GetShortEpgAsync(string streamId, int limit = 4, CancellationToken ct = default)
    {
        var root = await GetAsync("get_short_epg", $"&stream_id={streamId}&limit={limit}", ct).ConfigureAwait(false);
        var list = new List<EpgEntry>();
        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("epg_listings", out var listings)) return list;
        foreach (var e in Array(listings))
        {
            var start = Long(Str(e, "start_timestamp"));
            var stop = Long(Str(e, "stop_timestamp"));
            if (start <= 0 || stop <= start) continue;
            list.Add(new EpgEntry
            {
                Title = Clean(DecodeB64(Str(e, "title"))) ?? "",
                Description = Clean(DecodeB64(Str(e, "description"))),
                Start = DateTimeOffset.FromUnixTimeSeconds(start).LocalDateTime,
                End = DateTimeOffset.FromUnixTimeSeconds(stop).LocalDateTime,
            });
        }
        var now = DateTime.Now;
        return list.Where(x => x.End > now && x.Title.Length > 0).OrderBy(x => x.Start).ToList();
    }

    private static string? DecodeB64(string? s)
    {
        if (string.IsNullOrEmpty(s) || s.Length % 4 != 0) return s;
        try
        {
            // Décodage strict : un texte en clair qui ressemble à du Base64 (« Info ») donnerait du charabia.
            var decoded = new System.Text.UTF8Encoding(false, true).GetString(Convert.FromBase64String(s));
            return decoded.Any(c => char.IsControl(c) && c is not ('\n' or '\r' or '\t')) ? s : decoded;
        }
        catch (Exception ex) when (ex is FormatException or ArgumentException)
        {
            return s; // déjà en clair
        }
    }

    // ---------- Helpers JSON ----------

    private static IEnumerable<JsonElement> Array(JsonElement e) =>
        e.ValueKind == JsonValueKind.Array ? e.EnumerateArray() : Enumerable.Empty<JsonElement>();

    private static JsonElement Obj(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Object ? v : default;

    private static string? Str(JsonElement e, string name)
    {
        if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty(name, out var v)) return null;
        var s = v.ValueKind switch
        {
            JsonValueKind.String => v.GetString(),
            JsonValueKind.Number => v.GetRawText(),
            JsonValueKind.True => "1",
            JsonValueKind.False => "0",
            _ => null,
        };
        return string.IsNullOrWhiteSpace(s) ? null : s.Trim();
    }

    private static string? FirstBackdrop(JsonElement info)
    {
        if (info.ValueKind == JsonValueKind.Object && info.TryGetProperty("backdrop_path", out var b))
        {
            if (b.ValueKind == JsonValueKind.Array)
                foreach (var x in b.EnumerateArray())
                    if (x.ValueKind == JsonValueKind.String && Img(x.GetString()) is { } u) return u;
            if (b.ValueKind == JsonValueKind.String) return Img(b.GetString());
        }
        return Img(Str(info, "cover_big") ?? Str(info, "movie_image") ?? Str(info, "cover"));
    }

    private static string? Img(string? url) =>
        url != null && Uri.TryCreate(url.Trim(), UriKind.Absolute, out var u) && (u.Scheme == "http" || u.Scheme == "https")
            ? u.AbsoluteUri : null;

    private static long Long(string? s) =>
        long.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) ? v : 0;

    private static string? Year(string? s) =>
        s != null && s.Length >= 4 && int.TryParse(s[..4], out var y) && y > 1900 ? y.ToString() : null;

    private static string? Clean(string? s) => s?.Replace("\r", " ").Replace("\n", " ").Trim();
}

public sealed class XtreamException(string message) : Exception(message);
