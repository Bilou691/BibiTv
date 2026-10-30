using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using BibiTv.Services;
using LibVLCSharp.Shared;

namespace BibiTv.Views;

public sealed record PlaylistEntry(string Title, string Subtitle, string Url)
{
    /// <summary>Position de reprise (ms).</summary>
    public long StartMs { get; init; }
    /// <summary>Données de l'appelant (historique), renvoyées dans les événements de progression.</summary>
    public object? Tag { get; init; }
}

public partial class PlayerWindow : Window
{
    // Une seule instance de LibVLC pour toute l'application (son chargement est coûteux).
    private static LibVLC? _sharedLibVlc;
    private static LibVLC LibVlc => _sharedLibVlc ??= new LibVLC(
        "--network-caching=2000",
        "--live-caching=2000",
        $"--http-user-agent={XtreamClient.PlayerUserAgent}",
        "--http-reconnect");

    private readonly List<PlaylistEntry> _playlist;
    private readonly bool _isLive;
    private int _index;
    private MediaPlayer? _mp;
    private bool _seeking;
    private bool _closing;
    private int _liveRetries;
    private readonly DispatcherTimer _hideTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private readonly DispatcherTimer _osdTimer = new() { Interval = TimeSpan.FromSeconds(1.2) };
    private Window? _overlayWindow;
    private Point _lastMouse;

    private readonly Func<int, Task<string?>>? _liveInfo;
    private readonly DispatcherTimer _progressTimer = new() { Interval = TimeSpan.FromSeconds(15) };
    private long _lastTime, _lastLength;
    private int _resumedIndex = -1;

    /// <summary>Une entrée commence à être lue.</summary>
    public event Action<PlaylistEntry>? EntryStarted;
    /// <summary>Progression d'une entrée (position, durée en ms) : périodiquement, au changement et à la fermeture.</summary>
    public event Action<PlaylistEntry, long, long>? Progress;

    public PlayerWindow(List<PlaylistEntry> playlist, int index, bool isLive, Func<int, Task<string?>>? liveInfo = null)
    {
        _liveInfo = liveInfo;
        InitializeComponent();
        _playlist = playlist;
        _index = Math.Clamp(index, 0, playlist.Count - 1);
        _isLive = isLive;

        SeekRow.Visibility = isLive ? Visibility.Collapsed : Visibility.Visible;
        LiveBadge.Visibility = isLive ? Visibility.Visible : Visibility.Collapsed;
        BackButton.Visibility = FwdButton.Visibility = isLive ? Visibility.Collapsed : Visibility.Visible;
        VolumeBar.Value = SettingsService.Current.Volume;

        _hideTimer.Tick += (_, _) => HideChrome();
        _progressTimer.Tick += (_, _) => ReportProgress();
        _osdTimer.Tick += (_, _) => { _osdTimer.Stop(); Fade(Osd, 0); };

        SourceInitialized += (_, _) => WindowHelper.UseDarkTitleBar(this);
        Loaded += OnLoaded;
        Closing += OnClosing;
        PreviewKeyDown += OnKey;
        Overlay.Loaded += (_, _) => HookOverlayWindow();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _mp = new MediaPlayer(LibVlc) { EnableHardwareDecoding = true };
        _mp.Playing += (_, _) => Ui(OnPlaying);
        _mp.Paused += (_, _) => Ui(() => PlayPauseButton.Content = "");
        _mp.Buffering += (_, a) => Ui(() => SetBuffering(a.Cache < 100f));
        _mp.TimeChanged += (_, a) => Ui(() => OnTime(a.Time));
        _mp.LengthChanged += (_, a) => Ui(() => { _lastLength = a.Length; DurationText.Text = Format(a.Length); });
        _mp.EndReached += (_, _) => Ui(OnEndReached);
        _mp.EncounteredError += (_, _) => Ui(() => ShowError("Le flux n'a pas pu être ouvert. Il est peut-être hors ligne ou le nombre de connexions de l'abonnement est atteint."));
        Video.MediaPlayer = _mp;

        PlayCurrent();
        ShowChrome();
    }

    /// <summary>
    /// Le contenu du VideoView vit dans une fenêtre superposée distincte :
    /// on y branche aussi le clavier pour que les raccourcis marchent partout.
    /// </summary>
    private void HookOverlayWindow()
    {
        var w = GetWindow(Overlay);
        if (w == null || w == this || w == _overlayWindow) return;
        _overlayWindow = w;
        w.PreviewKeyDown += OnKey;
    }

    // ===================== Lecture =====================

    private void PlayCurrent()
    {
        if (_mp == null || _closing) return;
        var entry = _playlist[_index];
        TitleText.Text = entry.Title;
        SubtitleText.Text = entry.Subtitle;
        Title = $"{entry.Title} — BibiTV";
        ErrorPanel.Visibility = Visibility.Collapsed;
        SetBuffering(true);
        SeekBar.Value = 0;
        TimeText.Text = DurationText.Text = "0:00";
        PrevButton.IsEnabled = _index > 0;
        NextButton.IsEnabled = _index < _playlist.Count - 1;

        _lastTime = _lastLength = 0;
        using var media = new Media(LibVlc, new Uri(entry.Url));
        _mp.Play(media);
        _ = ShowLiveInfoAsync(_index);
        EntryStarted?.Invoke(entry);
        if (!_isLive) _progressTimer.Start();
    }

    /// <summary>Affiche le programme en cours sous le nom de la chaîne.</summary>
    private async Task ShowLiveInfoAsync(int index)
    {
        if (_liveInfo == null) return;
        try
        {
            var text = await _liveInfo(index);
            if (text != null && index == _index && !_closing) SubtitleText.Text = text;
        }
        catch
        {
            // Programme indisponible : on garde « En direct ».
        }
    }

    private void OnPlaying()
    {
        if (_mp == null) return;
        _liveRetries = 0;
        PlayPauseButton.Content = "";
        ErrorPanel.Visibility = Visibility.Collapsed;
        _mp.Volume = (int)VolumeBar.Value;
        SetBuffering(false);
        if (!_isLive) SeekRow.Visibility = _mp.IsSeekable ? Visibility.Visible : Visibility.Collapsed;

        // Reprise là où on s'était arrêté
        var entry = _playlist[_index];
        if (_resumedIndex != _index && entry.StartMs > 5000 && _mp.IsSeekable)
        {
            _resumedIndex = _index;
            _mp.Time = entry.StartMs;
            ShowOsd($"Reprise à {Format(entry.StartMs)}");
        }
        _resumedIndex = _index;
    }

    private void ReportProgress(bool finished = false)
    {
        if (_isLive || _lastLength <= 0) return;
        Progress?.Invoke(_playlist[_index], finished ? _lastLength : _lastTime, _lastLength);
    }

    private void OnTime(long time)
    {
        if (_mp == null) return;
        if (time > 0) SetBuffering(false);
        if (time > 0) _lastTime = time;
        if (_seeking) return;
        TimeText.Text = Format(time);
        SeekBar.Value = _mp.Position * 1000;
    }

    private void OnEndReached()
    {
        if (_closing) return;
        if (_isLive)
        {
            // Un flux en direct qui "se termine" est en général une coupure : on relance.
            if (_liveRetries++ < 3) PlayCurrent();
            else ShowError("Le flux s'est interrompu.");
        }
        else if (_index < _playlist.Count - 1)
        {
            ReportProgress(finished: true);
            _index++; // épisode suivant
            PlayCurrent();
        }
        else
        {
            ReportProgress(finished: true);
            _lastLength = 0; // déjà signalé
            Close();
        }
    }

    private void ShowError(string message)
    {
        if (_closing) return;
        SetBuffering(false);
        ErrorText.Text = message;
        ErrorPanel.Visibility = Visibility.Visible;
        ShowChrome();
    }

    private void SetBuffering(bool on) =>
        BufferingPanel.Visibility = on && ErrorPanel.Visibility != Visibility.Visible ? Visibility.Visible : Visibility.Collapsed;

    private void Retry_Click(object sender, RoutedEventArgs e) { _liveRetries = 0; PlayCurrent(); }

    private void TogglePause()
    {
        if (_mp == null) return;
        if (_mp.IsPlaying) { _mp.Pause(); ShowOsd("Pause"); }
        else if (_mp.State == VLCState.Paused) _mp.Play();
        else PlayCurrent();
    }

    private void SeekBy(int seconds)
    {
        if (_mp == null || _isLive || !_mp.IsSeekable) return;
        var target = Math.Clamp(_mp.Time + seconds * 1000L, 0, Math.Max(0, _mp.Length - 1000));
        _mp.Time = target;
        ShowOsd(seconds > 0 ? $"+{seconds} s" : $"−{-seconds} s");
    }

    private void Step(int delta)
    {
        var next = _index + delta;
        if (next < 0 || next >= _playlist.Count) return;
        ReportProgress();
        _index = next;
        _liveRetries = 0;
        PlayCurrent();
        ShowChrome();
    }

    private void ChangeVolume(int delta)
    {
        VolumeBar.Value = Math.Clamp(VolumeBar.Value + delta, 0, 100);
        ShowOsd($"Volume {(int)VolumeBar.Value} %");
    }

    // ===================== Boutons =====================

    private void PlayPause_Click(object sender, RoutedEventArgs e) => TogglePause();
    private void Back_Click(object sender, RoutedEventArgs e) => SeekBy(-10);
    private void Fwd_Click(object sender, RoutedEventArgs e) => SeekBy(30);
    private void Prev_Click(object sender, RoutedEventArgs e) => Step(-1);
    private void Next_Click(object sender, RoutedEventArgs e) => Step(1);
    private void Close_Click(object sender, RoutedEventArgs e) => Close();
    private void Fullscreen_Click(object sender, RoutedEventArgs e) => ToggleFullscreen();

    private void Mute_Click(object sender, RoutedEventArgs e)
    {
        if (_mp == null) return;
        _mp.Mute = !_mp.Mute;
        MuteButton.Content = _mp.Mute ? "" : "";
        ShowOsd(_mp.Mute ? "Son coupé" : "Son activé");
    }

    private void Volume_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_mp != null) _mp.Volume = (int)e.NewValue;
    }

    private void Audio_Click(object sender, RoutedEventArgs e)
    {
        if (_mp == null) return;
        ShowTrackMenu(AudioButton, "Aucune piste audio", _mp.AudioTrackDescription, _mp.AudioTrack, id => _mp.SetAudioTrack(id));
    }

    private void Subs_Click(object sender, RoutedEventArgs e)
    {
        if (_mp == null) return;
        ShowTrackMenu(SubsButton, "Aucun sous-titre", _mp.SpuDescription, _mp.Spu, id => _mp.SetSpu(id));
    }

    private void ShowTrackMenu(Button target, string emptyText, LibVLCSharp.Shared.Structures.TrackDescription[] tracks, int current, Action<int> select)
    {
        var menu = new ContextMenu { PlacementTarget = target, Placement = System.Windows.Controls.Primitives.PlacementMode.Top };
        if (tracks.Length == 0)
            menu.Items.Add(new MenuItem { Header = emptyText, IsEnabled = false });
        foreach (var t in tracks)
        {
            var id = t.Id;
            var item = new MenuItem
            {
                Header = id < 0 ? "Désactivé" : (string.IsNullOrWhiteSpace(t.Name) ? $"Piste {id}" : t.Name),
                IsCheckable = true,
                IsChecked = id == current,
            };
            item.Click += (_, _) => select(id);
            menu.Items.Add(item);
        }
        menu.Closed += (_, _) => _hideTimer.Start();
        _hideTimer.Stop();
        menu.IsOpen = true;
    }

    // ===================== Barre de progression =====================

    private void SeekBar_MouseDown(object sender, MouseButtonEventArgs e) => _seeking = true;

    private void SeekBar_MouseUp(object sender, MouseButtonEventArgs e)
    {
        // Laisse le Slider appliquer d'abord la position cliquée.
        Dispatcher.BeginInvoke(() =>
        {
            if (_mp != null && _mp.IsSeekable) _mp.Position = (float)(SeekBar.Value / 1000.0);
            _seeking = false;
        }, DispatcherPriority.Input);
    }

    private void SeekBar_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_seeking && _mp != null && _mp.Length > 0)
            TimeText.Text = Format((long)(_mp.Length * e.NewValue / 1000.0));
    }

    // ===================== Souris / clavier =====================

    private void Overlay_MouseMove(object sender, MouseEventArgs e)
    {
        var p = e.GetPosition(Overlay);
        if ((p - _lastMouse).Length < 2) return; // ignore les micro-mouvements
        _lastMouse = p;
        ShowChrome();
    }

    private void Overlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is not (Grid or Border or TextBlock { IsHitTestVisible: false })) return;
        if (e.ClickCount == 2) ToggleFullscreen();
        ShowChrome();
    }

    private void Overlay_MouseWheel(object sender, MouseWheelEventArgs e) => ChangeVolume(e.Delta > 0 ? 5 : -5);

    private void OnKey(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Escape:
                if (WindowStyle != WindowStyle.None) ToggleFullscreen(); else Close();
                break;
            case Key.Space:
            case Key.K:
            case Key.MediaPlayPause:
                TogglePause();
                break;
            case Key.Left: SeekBy(-10); break;
            case Key.Right: SeekBy(30); break;
            case Key.Up: ChangeVolume(5); break;
            case Key.Down: ChangeVolume(-5); break;
            case Key.PageUp:
            case Key.MediaPreviousTrack:
                Step(-1);
                break;
            case Key.PageDown:
            case Key.MediaNextTrack:
                Step(1);
                break;
            case Key.F:
            case Key.F11:
                ToggleFullscreen();
                break;
            case Key.M:
                Mute_Click(this, new RoutedEventArgs());
                break;
            default:
                return;
        }
        ShowChrome();
        e.Handled = true;
    }

    private void ToggleFullscreen()
    {
        if (WindowStyle == WindowStyle.None)
        {
            WindowStyle = WindowStyle.SingleBorderWindow;
            WindowState = WindowState.Normal;
            FullscreenButton.Content = "";
        }
        else
        {
            WindowState = WindowState.Normal; // nécessaire pour recouvrir la barre des tâches
            WindowStyle = WindowStyle.None;
            WindowState = WindowState.Maximized;
            FullscreenButton.Content = "";
        }
    }

    // ===================== Interface auto-masquée =====================

    private void ShowChrome()
    {
        Overlay.Cursor = null;
        Fade(Chrome, 1);
        _hideTimer.Stop();
        _hideTimer.Start();
    }

    private void HideChrome()
    {
        _hideTimer.Stop();
        if (_mp == null || !_mp.IsPlaying || Chrome.IsMouseOver && IsPointerOverControls()) { _hideTimer.Start(); return; }
        Fade(Chrome, 0);
        Overlay.Cursor = Cursors.None;
    }

    private bool IsPointerOverControls()
    {
        var y = Mouse.GetPosition(Overlay).Y;
        return y < 140 || y > Overlay.ActualHeight - 200;
    }

    private void ShowOsd(string text)
    {
        Osd.Text = text;
        Fade(Osd, 1, 0.1);
        _osdTimer.Stop();
        _osdTimer.Start();
    }

    private static void Fade(UIElement element, double to, double seconds = 0.3)
    {
        element.IsHitTestVisible = to > 0 && element is not TextBlock;
        element.BeginAnimation(OpacityProperty, new DoubleAnimation(to, TimeSpan.FromSeconds(seconds)));
    }

    // ===================== Fermeture =====================

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (!_closing) ReportProgress();
        _progressTimer.Stop();
        _closing = true;
        _hideTimer.Stop();
        _osdTimer.Stop();
        SettingsService.Current.Volume = (int)VolumeBar.Value;
        SettingsService.Save();

        var mp = _mp;
        _mp = null;
        if (mp == null) return;
        Video.MediaPlayer = null;
        // Stop() peut bloquer quelques secondes sur un flux réseau : jamais sur le thread UI.
        Task.Run(() =>
        {
            mp.Stop();
            mp.Dispose();
        });
    }

    // ===================== Utilitaires =====================

    /// <summary>Les événements LibVLC arrivent sur ses propres threads : on repasse sur le thread UI.</summary>
    private void Ui(Action action) => Dispatcher.BeginInvoke(() =>
    {
        if (!_closing) action();
    });

    private static string Format(long ms)
    {
        if (ms <= 0) return "0:00";
        var t = TimeSpan.FromMilliseconds(ms);
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }
}
