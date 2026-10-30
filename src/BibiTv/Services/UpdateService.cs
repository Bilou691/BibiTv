using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BibiTv.Services;

public sealed record UpdateInfo(Version Version, string Tag, string DownloadUrl, string? Sha256, string? Notes, long Size);

/// <summary>
/// Mise à jour automatique depuis les releases GitHub du dépôt.
/// Le zip est téléchargé, vérifié (SHA-256), extrait, puis un petit script remplace
/// les fichiers une fois l'application fermée et la relance.
/// </summary>
public static class UpdateService
{
    private const string Repo = "Bilou691/BibiTv";
    private const string AssetName = "BibiTv-win-x64.zip";

    private static readonly HttpClient Http = CreateHttp();

    public static Version CurrentVersion
    {
        get
        {
            var v = Assembly.GetExecutingAssembly().GetName().Version ?? new Version(1, 0, 0);
            return new Version(v.Major, v.Minor, Math.Max(0, v.Build));
        }
    }

    public static string CurrentVersionText => $"v{CurrentVersion}";

    private static HttpClient CreateHttp()
    {
        var http = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        http.DefaultRequestHeaders.UserAgent.ParseAdd($"BibiTV/{CurrentVersion}");
        return http;
    }

    /// <summary>Renvoie la mise à jour disponible, ou null si l'application est à jour.</summary>
    public static async Task<UpdateInfo?> CheckAsync(CancellationToken ct = default)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(20));

        using var req = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{Repo}/releases/latest");
        req.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var resp = await Http.SendAsync(req, timeout.Token);
        if (!resp.IsSuccessStatusCode) return null;

        using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync(timeout.Token));
        var root = doc.RootElement;
        var tag = root.GetProperty("tag_name").GetString() ?? "";
        if (!Version.TryParse(tag.TrimStart('v', 'V'), out var latest)) return null;
        latest = new Version(latest.Major, latest.Minor, Math.Max(0, latest.Build));
        if (latest <= CurrentVersion) return null;

        foreach (var asset in root.GetProperty("assets").EnumerateArray())
        {
            if (asset.GetProperty("name").GetString() != AssetName) continue;
            var digest = asset.TryGetProperty("digest", out var d) ? d.GetString() : null;
            var sha = digest != null && digest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase) ? digest[7..] : null;
            var notes = root.TryGetProperty("body", out var b) ? b.GetString() : null;
            return new UpdateInfo(latest, tag, asset.GetProperty("browser_download_url").GetString()!, sha, notes,
                asset.TryGetProperty("size", out var s) ? s.GetInt64() : 0);
        }
        return null;
    }

    /// <summary>
    /// Télécharge et prépare la mise à jour, puis lance le script de remplacement.
    /// L'appelant doit fermer l'application juste après.
    /// </summary>
    public static async Task DownloadAndPrepareAsync(UpdateInfo update, IProgress<(long done, long total)> progress, CancellationToken ct)
    {
        var appDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        var exe = Environment.ProcessPath ?? Path.Combine(appDir, "BibiTv.exe");
        EnsureWritable(appDir);

        var work = Path.Combine(Path.GetTempPath(), "BibiTvUpdate");
        if (Directory.Exists(work)) Directory.Delete(work, recursive: true);
        Directory.CreateDirectory(work);
        var zip = Path.Combine(work, AssetName);
        var extracted = Path.Combine(work, "new");

        // 1. Téléchargement
        using (var resp = await Http.GetAsync(update.DownloadUrl, HttpCompletionOption.ResponseHeadersRead, ct))
        {
            resp.EnsureSuccessStatusCode();
            var total = resp.Content.Headers.ContentLength ?? update.Size;
            await using var src = await resp.Content.ReadAsStreamAsync(ct);
            await using var dst = File.Create(zip);
            var buffer = new byte[1 << 16];
            long done = 0;
            int read;
            while ((read = await src.ReadAsync(buffer, ct)) > 0)
            {
                await dst.WriteAsync(buffer.AsMemory(0, read), ct);
                done += read;
                progress.Report((done, total));
            }
        }

        // 2. Vérification de l'intégrité
        if (update.Sha256 != null)
        {
            await using var fs = File.OpenRead(zip);
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(fs, ct));
            if (!hash.Equals(update.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Le fichier téléchargé est corrompu (empreinte SHA-256 différente). Réessayez.");
        }

        // 3. Extraction
        await Task.Run(() => ZipFile.ExtractToDirectory(zip, extracted), ct);
        if (!File.Exists(Path.Combine(extracted, "BibiTv.exe")))
            throw new InvalidOperationException("L'archive de mise à jour ne contient pas BibiTv.exe.");

        // 4. Script : attend la fermeture de l'app, copie les fichiers, relance.
        var pid = Environment.ProcessId;
        var script = Path.Combine(Path.GetTempPath(), "BibiTvUpdate.cmd");
        var sb = new StringBuilder();
        sb.AppendLine("@echo off");
        sb.AppendLine("chcp 65001 >nul");
        sb.AppendLine(":wait");
        sb.AppendLine($"tasklist /FI \"PID eq {pid}\" 2>nul | find \"{pid}\" >nul && (timeout /t 1 /nobreak >nul & goto wait)");
        sb.AppendLine($"robocopy \"{extracted}\" \"{appDir}\" /E /R:10 /W:1 /NFL /NDL /NJH /NJS /NP >nul");
        sb.AppendLine($"start \"\" \"{exe}\"");
        sb.AppendLine($"rmdir /s /q \"{work}\"");
        sb.AppendLine("(goto) 2>nul & del \"%~f0\"");
        await File.WriteAllTextAsync(script, sb.ToString(), new UTF8Encoding(false), ct);

        Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = Path.GetTempPath(),
        });
    }

    private static void EnsureWritable(string dir)
    {
        try
        {
            var probe = Path.Combine(dir, ".write-test");
            File.WriteAllText(probe, "");
            File.Delete(probe);
        }
        catch
        {
            throw new InvalidOperationException(
                $"Impossible d'écrire dans « {dir} ». Déplacez le dossier BibiTV ailleurs (ex. Documents) ou relancez en administrateur.");
        }
    }
}
