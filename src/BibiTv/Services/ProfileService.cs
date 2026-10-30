using System.IO;
using System.Text.Json;
using BibiTv.Models;

namespace BibiTv.Services;

/// <summary>Élément de l'historique « Reprendre » / « Récemment regardés ».</summary>
public sealed class HistoryEntry
{
    /// <summary>Clé de la fiche (film, série ou chaîne) : une seule entrée par fiche.</summary>
    public string Key { get; set; } = "";
    public Section Kind { get; set; }
    /// <summary>Identifiant Xtream de la version lue (film, série ou chaîne).</summary>
    public string ItemId { get; set; } = "";
    public string Title { get; set; } = "";
    public string? ImageUrl { get; set; }
    public string? Extension { get; set; }
    public string? EpisodeId { get; set; }
    public string? EpisodeLabel { get; set; }
    public long PositionMs { get; set; }
    public long LengthMs { get; set; }
    public DateTime WatchedAt { get; set; }

    public double Progress => LengthMs > 0 ? Math.Clamp(PositionMs * 100.0 / LengthMs, 0, 100) : 0;
    public bool HasProgress => Progress is > 1 and < 92;

    public string Subtitle
    {
        get
        {
            var remaining = LengthMs > 0 && HasProgress ? TimeSpan.FromMilliseconds(LengthMs - PositionMs) : (TimeSpan?)null;
            var left = remaining is { } r ? (r.TotalHours >= 1 ? $"reste {(int)r.TotalHours} h {r.Minutes:00}" : $"reste {Math.Max(1, (int)r.TotalMinutes)} min") : null;
            return string.Join("  ·  ", new[] { EpisodeLabel, left }.Where(s => !string.IsNullOrWhiteSpace(s)));
        }
    }
}

public sealed class UserProfile
{
    public List<HistoryEntry> History { get; set; } = new();
    /// <summary>Clés des films/séries/épisodes déjà vus.</summary>
    public HashSet<string> Watched { get; set; } = new();
    public HashSet<string> Likes { get; set; } = new();
    public HashSet<string> Dislikes { get; set; } = new();
    public bool HideWatched { get; set; }
}

/// <summary>Profil de visionnage (historique, « déjà vu », goûts), stocké dans %APPDATA%\BibiTv\profile.json.</summary>
public static class ProfileService
{
    private const int MaxHistory = 60;
    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BibiTv", "profile.json");

    public static UserProfile Current { get; } = Load();

    private static UserProfile Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<UserProfile>(File.ReadAllText(FilePath)) ?? new UserProfile();
        }
        catch
        {
            // Fichier corrompu : profil vierge.
        }
        return new UserProfile();
    }

    public static void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(Current));
        }
        catch
        {
            // Non bloquant.
        }
    }

    public static string EpisodeKey(string episodeId) => $"Ep:{episodeId}";

    public static bool IsWatched(string key) => Current.Watched.Contains(key);

    public static void SetWatched(string key, bool watched)
    {
        if (watched) Current.Watched.Add(key); else Current.Watched.Remove(key);
        Save();
    }

    /// <summary>1 = j'aime, -1 = je n'aime pas, 0 = sans avis.</summary>
    public static int RatingOf(string key) => Current.Likes.Contains(key) ? 1 : Current.Dislikes.Contains(key) ? -1 : 0;

    public static void SetRating(string key, int rating)
    {
        Current.Likes.Remove(key);
        Current.Dislikes.Remove(key);
        if (rating > 0) Current.Likes.Add(key);
        else if (rating < 0) Current.Dislikes.Add(key);
        Save();
    }

    /// <summary>Ajoute ou met à jour une entrée d'historique et la place en tête.</summary>
    public static void Touch(HistoryEntry entry)
    {
        entry.WatchedAt = DateTime.Now;
        Current.History.RemoveAll(h => h.Key == entry.Key);
        Current.History.Insert(0, entry);
        if (Current.History.Count > MaxHistory) Current.History.RemoveRange(MaxHistory, Current.History.Count - MaxHistory);
        Save();
    }

    public static void RemoveHistory(string key)
    {
        Current.History.RemoveAll(h => h.Key == key);
        Save();
    }
}
