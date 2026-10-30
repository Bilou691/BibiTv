using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Windows;

namespace BibiTv.Services;

public enum DownloadState { Queued, Running, Done, Failed, Canceled }

public sealed class DownloadItem : INotifyPropertyChanged
{
    public string Title { get; init; } = "";
    public string Url { get; init; } = "";
    public string FilePath { get; init; } = "";
    internal CancellationTokenSource Cts { get; set; } = new();

    private DownloadState _state;
    public DownloadState State
    {
        get => _state;
        set { _state = value; Notify(); Notify(nameof(IsActive)); Notify(nameof(IsDone)); Notify(nameof(CanRetry)); }
    }

    private double _progress;
    public double Progress { get => _progress; set { _progress = value; Notify(); } }

    private bool _indeterminate;
    public bool Indeterminate { get => _indeterminate; set { _indeterminate = value; Notify(); } }

    private string _status = "En attente";
    public string Status { get => _status; set { _status = value; Notify(); } }

    public bool IsActive => State is DownloadState.Queued or DownloadState.Running;
    public bool IsDone => State == DownloadState.Done;
    public bool CanRetry => State is DownloadState.Failed or DownloadState.Canceled;

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>
/// File de téléchargements traitée un par un : la plupart des abonnements Xtream
/// n'autorisent qu'une ou deux connexions simultanées.
/// </summary>
public sealed class DownloadManager
{
    public static DownloadManager Instance { get; } = new();

    private static readonly HttpClient Http = CreateHttp();
    private bool _running;

    public ObservableCollection<DownloadItem> Items { get; } = new();

    public event Action? Changed;

    public int ActiveCount => Items.Count(i => i.IsActive);

    public static string DefaultFolder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "BibiTV");

    public static string Folder =>
        string.IsNullOrWhiteSpace(SettingsService.Current.DownloadFolder) ? DefaultFolder : SettingsService.Current.DownloadFolder!;

    private static HttpClient CreateHttp()
    {
        var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true }) { Timeout = Timeout.InfiniteTimeSpan };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(XtreamClient.PlayerUserAgent);
        return http;
    }

    /// <summary>Ajoute un fichier à la file. <paramref name="relativePath"/> est relatif au dossier de téléchargement.</summary>
    public DownloadItem Enqueue(string title, string url, string relativePath)
    {
        var path = Path.Combine(Folder, relativePath);
        var existing = Items.FirstOrDefault(i => i.FilePath.Equals(path, StringComparison.OrdinalIgnoreCase));
        if (existing != null)
        {
            if (existing.CanRetry) Retry(existing);
            return existing;
        }

        var item = new DownloadItem { Title = title, Url = url, FilePath = path };
        if (File.Exists(path))
        {
            item.State = DownloadState.Done;
            item.Progress = 100;
            item.Status = "Déjà téléchargé";
        }
        Items.Insert(0, item);
        Changed?.Invoke();
        _ = ProcessQueueAsync();
        return item;
    }

    public void Cancel(DownloadItem item)
    {
        if (!item.IsActive) return;
        item.Cts.Cancel();
        if (item.State == DownloadState.Queued)
        {
            item.State = DownloadState.Canceled;
            item.Status = "Annulé";
            Changed?.Invoke();
        }
    }

    public void Retry(DownloadItem item)
    {
        if (!item.CanRetry) return;
        item.Cts = new CancellationTokenSource();
        item.Progress = 0;
        item.Status = "En attente";
        item.State = DownloadState.Queued;
        Changed?.Invoke();
        _ = ProcessQueueAsync();
    }

    public void ClearFinished()
    {
        foreach (var i in Items.Where(i => !i.IsActive).ToList()) Items.Remove(i);
        Changed?.Invoke();
    }

    private async Task ProcessQueueAsync()
    {
        if (_running) return;
        _running = true;
        try
        {
            while (Items.LastOrDefault(i => i.State == DownloadState.Queued) is { } next) // le plus ancien d'abord
            {
                await DownloadAsync(next);
                Changed?.Invoke();
            }
        }
        finally
        {
            _running = false;
        }
    }

    private static async Task DownloadAsync(DownloadItem item)
    {
        item.State = DownloadState.Running;
        item.Status = "Connexion…";
        var part = item.FilePath + ".part";
        var ct = item.Cts.Token;
        var dispatcher = Application.Current.Dispatcher;

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(item.FilePath)!);
            await Task.Run(async () =>
            {
                using var resp = await Http.GetAsync(item.Url, HttpCompletionOption.ResponseHeadersRead, ct);
                resp.EnsureSuccessStatusCode();
                var total = resp.Content.Headers.ContentLength ?? 0;
                dispatcher.Invoke(() => item.Indeterminate = total <= 0);

                await using var src = await resp.Content.ReadAsStreamAsync(ct);
                await using var dst = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, true);
                var buffer = new byte[1 << 16];
                long done = 0, lastBytes = 0;
                var sw = System.Diagnostics.Stopwatch.StartNew();
                var lastTick = 0L;

                // Délai d'inactivité : si plus rien n'arrive pendant 60 s, on abandonne.
                using var idle = CancellationTokenSource.CreateLinkedTokenSource(ct);
                int read;
                while (true)
                {
                    idle.CancelAfter(TimeSpan.FromSeconds(60));
                    read = await src.ReadAsync(buffer, idle.Token);
                    if (read == 0) break;
                    await dst.WriteAsync(buffer.AsMemory(0, read), ct);
                    done += read;

                    var now = sw.ElapsedMilliseconds;
                    if (now - lastTick >= 500)
                    {
                        var speed = (done - lastBytes) * 1000.0 / Math.Max(1, now - lastTick);
                        lastTick = now;
                        lastBytes = done;
                        var status = total > 0
                            ? $"{Size(done)} sur {Size(total)}  ·  {Size((long)speed)}/s" + Eta(total - done, speed)
                            : $"{Size(done)}  ·  {Size((long)speed)}/s";
                        var progress = total > 0 ? done * 100.0 / total : 0;
                        _ = dispatcher.BeginInvoke(() => { item.Status = status; item.Progress = progress; });
                    }
                }
            }, ct);

            File.Move(part, item.FilePath, overwrite: true);
            item.Indeterminate = false;
            item.Progress = 100;
            item.Status = "Terminé";
            item.State = DownloadState.Done;
        }
        catch (Exception ex)
        {
            TryDelete(part);
            item.Indeterminate = false;
            if (item.Cts.IsCancellationRequested)
            {
                item.Status = "Annulé";
                item.State = DownloadState.Canceled;
            }
            else
            {
                item.Status = ex is OperationCanceledException ? "Échec : le serveur ne répond plus" : $"Échec : {ex.Message}";
                item.State = DownloadState.Failed;
            }
        }
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { /* fichier verrouillé : tant pis */ }
    }

    private static string Eta(long remaining, double speed)
    {
        if (speed < 1) return "";
        var t = TimeSpan.FromSeconds(remaining / speed);
        return t.TotalHours >= 1 ? $"  ·  {(int)t.TotalHours} h {t.Minutes:00} restantes" : $"  ·  {Math.Max(1, t.Minutes)} min restantes";
    }

    public static string Size(long bytes) => bytes switch
    {
        >= 1L << 30 => $"{bytes / (double)(1L << 30):0.0} Go",
        >= 1L << 20 => $"{bytes / (double)(1L << 20):0.0} Mo",
        _ => $"{bytes / 1024.0:0} Ko",
    };

    /// <summary>Retire les caractères interdits dans un nom de fichier Windows.</summary>
    public static string SafeName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var clean = new string(name.Select(c => invalid.Contains(c) ? ' ' : c).ToArray()).Trim().TrimEnd('.');
        while (clean.Contains("  ")) clean = clean.Replace("  ", " ");
        return clean.Length == 0 ? "Sans titre" : clean.Length > 120 ? clean[..120].Trim() : clean;
    }
}
