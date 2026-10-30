using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BibiTv.Services;

public sealed class AppSettings
{
    public string ServerUrl { get; set; } = "";
    public string Username { get; set; } = "";
    /// <summary>Mot de passe chiffré avec DPAPI (lisible uniquement par l'utilisateur Windows courant).</summary>
    public string? ProtectedPassword { get; set; }
    public int Volume { get; set; } = 80;
    public List<string> Favorites { get; set; } = new();
    public string? DownloadFolder { get; set; }
}

/// <summary>Sauvegarde des réglages dans %APPDATA%\BibiTv\settings.json.</summary>
public static class SettingsService
{
    private static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BibiTv");
    private static readonly string FilePath = Path.Combine(Dir, "settings.json");
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("BibiTv.v1");

    public static AppSettings Current { get; private set; } = Load();

    private static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch
        {
            // Fichier corrompu : on repart de zéro.
        }
        return new AppSettings();
    }

    public static void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(Current, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // Pas bloquant pour l'utilisateur.
        }
    }

    public static string? GetPassword()
    {
        if (string.IsNullOrEmpty(Current.ProtectedPassword)) return null;
        try
        {
            var data = ProtectedData.Unprotect(Convert.FromBase64String(Current.ProtectedPassword), Entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(data);
        }
        catch
        {
            return null;
        }
    }

    public static void SetCredentials(string url, string user, string? password)
    {
        Current.ServerUrl = url;
        Current.Username = user;
        Current.ProtectedPassword = password == null
            ? null
            : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(password), Entropy, DataProtectionScope.CurrentUser));
        Save();
    }
}
