namespace BibiTv.Models;

public enum Section { Live, Movies, Series }

public sealed class Category
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public int Count { get; set; }

    public const string AllId = "__all__";
}

/// <summary>Élément affiché dans la grille (chaîne, film ou série).</summary>
public sealed class MediaItem
{
    public Section Kind { get; init; }
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public string? ImageUrl { get; init; }
    public string CategoryId { get; init; } = "";
    public string? Rating { get; init; }
    public string? Year { get; init; }
    public string? Extension { get; init; }
    public string? Plot { get; init; }
    public string? Genre { get; init; }
    public long Added { get; init; }

    /// <summary>Étiquette de version extraite du titre (« 4K MULTI », « SUBT AR »…).</summary>
    public string Tag { get; set; } = "";
    /// <summary>Nom affiché dans le sélecteur de version de la fiche.</summary>
    public string VersionLabel { get; set; } = "";
    /// <summary>Toutes les versions regroupées sous cette affiche (elle-même incluse).</summary>
    public List<MediaItem> Variants { get; set; } = new();
    public HashSet<string> CategoryIds { get; set; } = new();

    public string Initial => string.IsNullOrWhiteSpace(Title) ? "?" : Title.Trim()[..1].ToUpperInvariant();

    public string Subtitle => Kind == Section.Live
        ? ""
        : string.Join("  ·  ", new[] { Year, FormatRating(Rating), Variants.Count > 1 ? $"{Variants.Count} versions" : null }
            .Where(s => !string.IsNullOrWhiteSpace(s)));

    internal static string? FormatRating(string? rating)
    {
        if (double.TryParse(rating, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var r) && r > 0)
            return $"★ {r:0.#}";
        return null;
    }
}

public sealed class MovieInfo
{
    public string? Plot { get; init; }
    public string? Genre { get; init; }
    public string? Director { get; init; }
    public string? Cast { get; init; }
    public string? Duration { get; init; }
    public string? ReleaseDate { get; init; }
    public string? Backdrop { get; init; }
    public string? Rating { get; init; }
    public string? Extension { get; init; }
}

public sealed class Episode
{
    public string Id { get; init; } = "";
    public int Number { get; init; }
    public int Season { get; init; }
    public string Title { get; init; } = "";
    public string? Plot { get; init; }
    public string? Image { get; init; }
    public string? Duration { get; init; }
    public string Extension { get; init; } = "mp4";

    public string Label => $"{Number}. {Title}";
}

public sealed class Season
{
    public int Number { get; init; }
    public List<Episode> Episodes { get; init; } = new();
    public string Label => Number == 0 ? "Bonus" : $"Saison {Number}";
}

public sealed class SeriesInfo
{
    public string? Plot { get; init; }
    public string? Genre { get; init; }
    public string? Cast { get; init; }
    public string? ReleaseDate { get; init; }
    public string? Backdrop { get; init; }
    public List<Season> Seasons { get; init; } = new();
}

public sealed class AccountInfo
{
    public string Status { get; init; } = "";
    public DateTime? Expiration { get; init; }
    public string? MaxConnections { get; init; }
}
