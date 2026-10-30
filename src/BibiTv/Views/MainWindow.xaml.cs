using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using BibiTv.Models;
using BibiTv.Services;

namespace BibiTv.Views;

public partial class MainWindow : Window
{
    private const string FavoritesId = "__fav__";

    private XtreamClient? _client;
    private Section _section = Section.Movies;
    private readonly Dictionary<Section, List<MediaItem>> _items = new();
    private readonly Dictionary<Section, List<Category>> _categories = new();
    private readonly DispatcherTimer _searchDebounce;
    private CancellationTokenSource? _detailCts;
    private MediaItem? _detailItem;   // fiche (regroupement de versions)
    private MediaItem? _variant;      // version sélectionnée
    private MovieInfo? _movieInfo;
    private SeriesInfo? _seriesInfo;
    private PlayerWindow? _player;
    private UpdateInfo? _update;
    private bool _onHome;
    private bool _homeBuilt;
    private MediaItem? _heroItem;
    private CancellationTokenSource? _homeCts;
    private readonly Dictionary<string, (DateTime fetched, List<EpgEntry> entries)> _epgCache = new();
    private readonly DispatcherTimer _epgDelay = new() { Interval = TimeSpan.FromMilliseconds(220) };
    private readonly DispatcherTimer _epgHide = new() { Interval = TimeSpan.FromMilliseconds(350) };
    private MediaItem? _epgItem;
    private readonly Dictionary<string, MediaItem> _byKey = new();
    private CancellationTokenSource? _updateCts;

    public MainWindow()
    {
        InitializeComponent();
        _searchDebounce = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        _searchDebounce.Tick += (_, _) => { _searchDebounce.Stop(); ApplyFilter(); };

        DownloadList.ItemsSource = DownloadManager.Instance.Items;
        DownloadManager.Instance.Changed += UpdateDownloadBadge;
        DownloadManager.Instance.Items.CollectionChanged += (_, _) => UpdateDownloadBadge();
        UpdateDownloadBadge();

        _epgDelay.Tick += async (_, _) => { _epgDelay.Stop(); await LoadEpgCardAsync(); };
        _epgHide.Tick += (_, _) => { _epgHide.Stop(); HideEpgCard(); };

        SourceInitialized += (_, _) => WindowHelper.UseDarkTitleBar(this);
        Loaded += OnLoaded;
        PreviewKeyDown += OnPreviewKeyDown;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        PlaySplash();
        VersionButton.Content = LoginVersionButton.Content = UpdateService.CurrentVersionText;
        _ = CheckUpdateAsync(silent: true);

        var s = SettingsService.Current;
        ServerBox.Text = s.ServerUrl;
        UserBox.Text = s.Username;
        var pwd = SettingsService.GetPassword();
        if (pwd != null) PassBox.Password = pwd;

        if (!string.IsNullOrWhiteSpace(s.ServerUrl) && !string.IsNullOrWhiteSpace(s.Username) && pwd != null)
            await LoginAsync(s.ServerUrl, s.Username, pwd);
        else
            ServerBox.Focus();
    }

    // ===================== Connexion =====================

    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(ServerBox.Text) || string.IsNullOrWhiteSpace(UserBox.Text) || PassBox.Password.Length == 0)
        {
            LoginError.Text = "Merci de remplir les trois champs.";
            return;
        }
        await LoginAsync(ServerBox.Text, UserBox.Text, PassBox.Password);
    }

    private async Task LoginAsync(string url, string user, string password)
    {
        LoginError.Text = "";
        LoginButton.IsEnabled = false;
        ShowLoading("Connexion…");
        try
        {
            var client = new XtreamClient(url, user, password);
            var account = await client.AuthenticateAsync();
            _client = client;
            SettingsService.SetCredentials(client.BaseUrl, client.Username, client.Password);

            Title = account.Expiration is { } exp ? $"BibiTV  ·  abonnement valide jusqu'au {exp:dd/MM/yyyy}" : "BibiTV";
            LoginView.Visibility = Visibility.Collapsed;
            BrowseView.Visibility = Visibility.Visible;
            HideLoading();

            _homeBuilt = false;
            if (NavHome.IsChecked == true) await ShowHomeAsync();
            else NavHome.IsChecked = true;
        }
        catch (Exception ex)
        {
            HideLoading();
            LoginView.Visibility = Visibility.Visible;
            BrowseView.Visibility = Visibility.Collapsed;
            LoginError.Text = ex.Message;
        }
        finally
        {
            LoginButton.IsEnabled = true;
        }
    }

    private void Logout_Click(object sender, RoutedEventArgs e)
    {
        _player?.Close();
        _client = null;
        _items.Clear();
        _byKey.Clear();
        _categories.Clear();
        _homeBuilt = false;
        _homeCts?.Cancel();
        SettingsService.SetCredentials(SettingsService.Current.ServerUrl, SettingsService.Current.Username, null);
        PassBox.Password = "";
        CloseDetail();
        PosterList.ItemsSource = null;
        LiveList.ItemsSource = null;
        CategoryList.ItemsSource = null;
        BrowseView.Visibility = Visibility.Collapsed;
        LoginView.Visibility = Visibility.Visible;
        Title = "BibiTV";
        PassBox.Focus();
    }

    // ===================== Sections & catégories =====================

    private async void Nav_Checked(object sender, RoutedEventArgs e)
    {
        if (_client == null) return;
        if (sender == NavHome)
        {
            await ShowHomeAsync();
            return;
        }
        var section = sender == NavLive ? Section.Live : sender == NavSeries ? Section.Series : Section.Movies;
        await LoadSectionAsync(section);
    }

    /// <summary>Charge (une seule fois) le catalogue d'une section.</summary>
    private async Task<bool> EnsureLoadedAsync(Section section)
    {
        if (_items.ContainsKey(section)) return true;
        var client = _client;
        if (client == null) return false;
        var catsTask = client.GetCategoriesAsync(section);
        var itemsTask = client.GetItemsAsync(section);
        await Task.WhenAll(catsTask, itemsTask);
        if (client != _client || _items.ContainsKey(section)) return _items.ContainsKey(section);

        // Les doublons (4K / FHD / VOSTFR…) sont fusionnés en une seule affiche.
        var items = TitleGrouper.Group(itemsTask.Result, merge: section != Section.Live);
        if (section == Section.Live) ChannelBrands.Tag(items); // Canal+, beIN, RMC Sport…
        _items[section] = items;
        foreach (var i in items) _byKey[i.Key] = i;
        _categories[section] = catsTask.Result;
        return true;
    }

    private void ShowView(bool home)
    {
        HideEpgCard();
        _onHome = home;
        HomeView.Visibility = home ? Visibility.Visible : Visibility.Collapsed;
        CatalogView.Visibility = home ? Visibility.Collapsed : Visibility.Visible;
        SearchContainer.Visibility = home ? Visibility.Collapsed : Visibility.Visible;
    }

    private async Task LoadSectionAsync(Section section)
    {
        if (_client == null) return;
        _section = section;
        ShowView(home: false);
        HideWatchedToggle.Visibility = section == Section.Live ? Visibility.Collapsed : Visibility.Visible;
        HideWatchedToggle.IsChecked = ProfileService.Current.HideWatched;
        SearchBox.Text = "";
        PosterList.Visibility = section == Section.Live ? Visibility.Collapsed : Visibility.Visible;
        LiveList.Visibility = section == Section.Live ? Visibility.Visible : Visibility.Collapsed;
        EmptyState.Visibility = Visibility.Collapsed;

        if (!_items.ContainsKey(section))
        {
            ShowLoading(section switch
            {
                Section.Live => "Chargement des chaînes…",
                Section.Movies => "Chargement des films…",
                _ => "Chargement des séries…",
            });
            try
            {
                if (!await EnsureLoadedAsync(section)) return; // déconnecté entre-temps
            }
            catch (Exception ex)
            {
                HideLoading();
                if (_section != section) return;
                CategoryList.ItemsSource = null;
                ActiveList.ItemsSource = null;
                HeaderTitle.Text = "";
                HeaderCount.Text = "";
                ShowEmpty($"Impossible de charger le contenu.\n{ex.Message}", retry: true);
                return;
            }
            HideLoading();
        }

        if (_section != section || _onHome) return; // l'utilisateur a changé d'onglet pendant le chargement
        RefreshCategories(selectFirst: true);
    }

    private void RefreshCategories(bool selectFirst)
    {
        var items = _items[_section];
        var counts = items.SelectMany(i => i.CategoryIds).GroupBy(id => id).ToDictionary(g => g.Key, g => g.Count());
        var favCount = items.Count(IsFavorite);

        var list = new List<Category>();
        if (favCount > 0) list.Add(new Category { Id = FavoritesId, Name = "Ma liste", Count = favCount });
        list.Add(new Category
        {
            Id = Category.AllId,
            Name = _section == Section.Live ? "Toutes les chaînes" : "Récemment ajoutés",
            Count = items.Count,
        });
        if (_section == Section.Live)
        {
            var brands = ChannelBrands.Names
                .Select(b => new Category { Id = ChannelBrands.Prefix + b, Name = b })
                .Where(b => counts.ContainsKey(b.Id)).ToList();
            if (brands.Count > 0)
            {
                list.Add(new Category { Id = "hdr:brands", Name = "MARQUES", IsHeader = true });
                foreach (var b in brands) { b.Count = counts[b.Id]; list.Add(b); }
                list.Add(new Category { Id = "hdr:cats", Name = "CATÉGORIES", IsHeader = true });
            }
        }
        foreach (var c in _categories[_section])
        {
            if (!counts.TryGetValue(c.Id, out var n)) continue; // on masque les catégories vides
            c.Count = n;
            list.Add(c);
        }

        var previous = (CategoryList.SelectedItem as Category)?.Id;
        CategoryList.ItemsSource = list;
        CategoryList.SelectedItem = selectFirst
            ? list.FirstOrDefault(c => c.Id == Category.AllId)
            : list.FirstOrDefault(c => c.Id == previous) ?? list.FirstOrDefault(c => c.Id == Category.AllId);
    }

    private void Category_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (CategoryList.SelectedItem is not Category { IsHeader: false }) return;
        if (SearchBox.Text.Length > 0)
        {
            SearchBox.Text = ""; // déclenche ApplyFilter via la recherche
            return;
        }
        ApplyFilter();
    }

    private ListBox ActiveList => _section == Section.Live ? LiveList : PosterList;

    private void ApplyFilter()
    {
        if (!_items.TryGetValue(_section, out var all)) return;
        var query = SearchBox.Text.Trim();
        IEnumerable<MediaItem> result;

        if (query.Length > 0)
        {
            var cmp = CultureInfo.InvariantCulture.CompareInfo;
            const CompareOptions opts = CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace;
            result = all.Where(i => cmp.IndexOf(i.Title, query, opts) >= 0);
            HeaderTitle.Text = $"« {query} »";
        }
        else
        {
            var cat = CategoryList.SelectedItem as Category;
            result = cat?.Id switch
            {
                null or Category.AllId => _section == Section.Live ? all : all.OrderByDescending(i => i.Added),
                FavoritesId => all.Where(IsFavorite),
                var id => all.Where(i => i.CategoryIds.Contains(id)),
            };
            HeaderTitle.Text = cat?.Name ?? "";
        }

        if (ProfileService.Current.HideWatched && query.Length == 0 && _section != Section.Live)
            result = result.Where(i => !i.IsWatched);
        var list = result.ToList();
        HeaderCount.Text = list.Count switch
        {
            0 => "",
            1 => "1 élément",
            var n => $"{n:N0} éléments",
        };

        var lb = ActiveList;
        lb.ItemsSource = list;
        if (list.Count > 0) lb.ScrollIntoView(list[0]);

        if (list.Count == 0) ShowEmpty(query.Length > 0 ? "Aucun résultat." : "Cette catégorie est vide.", retry: false);
        else EmptyState.Visibility = Visibility.Collapsed;
    }

    private void Search_TextChanged(object sender, TextChangedEventArgs e)
    {
        SearchPlaceholder.Visibility = SearchBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        _searchDebounce.Stop();
        _searchDebounce.Start();
    }

    private async void Retry_Click(object sender, RoutedEventArgs e) => await LoadSectionAsync(_section);

    // ===================== Clic sur une tuile =====================

    private void Tile_Click(object sender, MouseButtonEventArgs e)
    {
        if ((sender as ListBoxItem)?.DataContext is HistoryEntry h)
        {
            _ = ResumeAsync(h, ItemsControl.ItemsControlFromItemContainer((ListBoxItem)sender));
            return;
        }
        if (Unwrap((sender as ListBoxItem)?.DataContext) is { } item)
            Open(item, ItemsControl.ItemsControlFromItemContainer((ListBoxItem)sender));
    }

    private static MediaItem? Unwrap(object? o) => o switch
    {
        MediaItem m => m,
        RankedItem r => r.Item,
        _ => null,
    };

    private void TileList_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && Unwrap((sender as ListBox)?.SelectedItem) is { } item)
        {
            Open(item, sender as ItemsControl);
            e.Handled = true;
        }
    }

    private void Tile_RightClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem container) return;
        var menu = new ContextMenu { PlacementTarget = container };
        void Add(string header, Action action)
        {
            var mi = new MenuItem { Header = header };
            mi.Click += (_, _) => action();
            menu.Items.Add(mi);
        }

        if (container.DataContext is HistoryEntry h)
        {
            Add("Retirer de l'historique", () => { ProfileService.RemoveHistory(h.Key); RefreshPersonalRows(); });
        }
        else if (Unwrap(container.DataContext) is { } item)
        {
            Add(IsFavorite(item) ? "Retirer de Ma liste" : "Ajouter à Ma liste", () => ToggleFavorite(item));
            if (item.Kind != Section.Live)
            {
                Add(item.IsWatched ? "Marquer comme non vu" : "Marquer comme vu", () => SetWatched(item, !item.IsWatched));
                Add(item.IsLiked ? "Retirer « J'aime »" : "J'aime", () => SetRating(item, item.IsLiked ? 0 : 1));
                Add(item.IsDisliked ? "Retirer « Je n'aime pas »" : "Je n'aime pas", () => SetRating(item, item.IsDisliked ? 0 : -1));
            }
        }
        else return;
        menu.IsOpen = true;
        e.Handled = true;
    }

    private void Open(MediaItem item, ItemsControl? source = null)
    {
        HideEpgCard();
        if (item.Kind == Section.Live)
        {
            // Lecture directe, avec zapping possible dans la liste d'où vient la chaîne.
            var channels = source?.ItemsSource?.Cast<object>().Select(Unwrap).OfType<MediaItem>().Where(m => m.Kind == Section.Live).ToList();
            if (channels == null || !channels.Contains(item)) channels = new List<MediaItem> { item };
            var playlist = channels.Select(c => new PlaylistEntry(c.Title, "En direct", _client!.LiveUrl(c.Id))
            {
                Tag = new HistoryEntry { Key = c.Key, Kind = Section.Live, ItemId = c.Id, Title = c.Title, ImageUrl = c.ImageUrl },
            }).ToList();
            StartPlayer(playlist, Math.Max(0, channels.IndexOf(item)), isLive: true,
                liveInfo: async i => (await GetEpgAsync(channels[i])).FirstOrDefault(x => x.IsNow) is { } now
                    ? $"En direct  ·  {now.TimeRange}  ·  {now.Title}" : null);
        }
        else
        {
            _ = ShowDetailAsync(item);
        }
    }

    // ===================== Fiche détail =====================

    private Task ShowDetailAsync(MediaItem item)
    {
        _detailItem = item;
        _variant = null;

        DetailTitle.Text = item.Title;
        DetailMeta.Text = BuildMeta(item.Year, item.Genre, null, item.Rating);
        DetailPlot.Text = item.Plot ?? "";
        DetailPeople.Text = "";
        DetailPoster.ImageSource = LoadImage(item.ImageUrl, 500);
        DetailBackdrop.Source = null;
        DownloadButton.Visibility = item.Kind == Section.Movies ? Visibility.Visible : Visibility.Collapsed;
        UpdateFavButton();
        UpdateProfileButtons();
        DetailView.Visibility = Visibility.Visible;
        DetailScroll.ScrollToTop();

        // Sélecteur de version (qualité / langue)
        var catNames = _categories.TryGetValue(item.Kind, out var cats) ? cats.ToDictionary(c => c.Id, c => c.Name) : new();
        var n = 0;
        foreach (var v in item.Variants)
        {
            n++;
            v.VersionLabel = v.Tag.Length > 0 ? v.Tag
                : catNames.TryGetValue(v.CategoryId, out var cn) ? cn
                : $"Version {n}";
        }
        // Si deux versions ont le même nom, on les numérote.
        foreach (var dup in item.Variants.GroupBy(v => v.VersionLabel).Where(g => g.Count() > 1))
        {
            var k = 0;
            foreach (var v in dup) v.VersionLabel = $"{v.VersionLabel} ({++k})";
        }

        VersionPanel.Visibility = item.Variants.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        VersionList.ItemsSource = item.Variants;
        VersionList.SelectedIndex = -1;
        VersionList.SelectedIndex = 0; // déclenche le chargement de la version
        PlayButton.Focus();
        return Task.CompletedTask;
    }

    private async void Version_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (VersionList.SelectedItem is MediaItem v) await LoadVariantAsync(v);
    }

    private async Task LoadVariantAsync(MediaItem variant)
    {
        if (_client == null || _detailItem == null) return;
        _detailCts?.Cancel();
        var cts = _detailCts = new CancellationTokenSource();
        var item = _detailItem;

        _variant = variant;
        _movieInfo = null;
        _seriesInfo = null;
        PlayLabel.Text = "Lecture";
        PlayButton.IsEnabled = variant.Kind == Section.Movies;
        SeriesPanel.Visibility = Visibility.Collapsed;
        SeasonList.ItemsSource = null;
        EpisodeList.ItemsSource = null;
        DetailLoading.Visibility = Visibility.Visible;

        try
        {
            if (variant.Kind == Section.Movies)
            {
                var info = await _client.GetMovieInfoAsync(variant.Id, cts.Token);
                if (cts.IsCancellationRequested) return;
                _movieInfo = info;
                DetailMeta.Text = BuildMeta(Year(info.ReleaseDate) ?? item.Year, info.Genre, info.Duration, info.Rating ?? item.Rating);
                if (!string.IsNullOrWhiteSpace(info.Plot)) DetailPlot.Text = info.Plot;
                DetailPeople.Text = BuildPeople(info.Director, info.Cast);
                if (LoadImage(info.Backdrop, 1600) is { } bd) DetailBackdrop.Source = bd;
            }
            else
            {
                var info = await _client.GetSeriesInfoAsync(variant.Id, cts.Token);
                if (cts.IsCancellationRequested) return;
                _seriesInfo = info;
                DetailMeta.Text = BuildMeta(Year(info.ReleaseDate) ?? item.Year, info.Genre ?? item.Genre,
                    info.Seasons.Count switch { 0 => null, 1 => "1 saison", var c => $"{c} saisons" }, item.Rating);
                if (!string.IsNullOrWhiteSpace(info.Plot)) DetailPlot.Text = info.Plot;
                DetailPeople.Text = BuildPeople(null, info.Cast);
                if (LoadImage(info.Backdrop, 1600) is { } bd) DetailBackdrop.Source = bd;

                if (info.Seasons.Count > 0)
                {
                    SeriesPanel.Visibility = Visibility.Visible;
                    SeasonList.ItemsSource = info.Seasons;
                    SeasonList.SelectedIndex = 0;
                    PlayButton.IsEnabled = true;
                    PlayLabel.Text = $"Lecture  S{info.Seasons[0].Number} É{info.Seasons[0].Episodes[0].Number}";
                }
                else
                {
                    DetailPeople.Text += (DetailPeople.Text.Length > 0 ? "\n\n" : "") + "Aucun épisode disponible pour cette version.";
                }
            }
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception ex)
        {
            if (cts.IsCancellationRequested) return;
            DetailPeople.Text = $"Informations indisponibles ({ex.Message}).";
        }
        finally
        {
            if (!cts.IsCancellationRequested) DetailLoading.Visibility = Visibility.Collapsed;
        }
    }

    private void Season_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        EpisodeList.ItemsSource = (SeasonList.SelectedItem as Season)?.Episodes;

    private void Episode_Click(object sender, MouseButtonEventArgs e)
    {
        if ((sender as ListBoxItem)?.DataContext is Episode ep) PlayEpisode(ep);
    }

    private void Play_Click(object sender, RoutedEventArgs e)
    {
        if (_client == null || _detailItem == null || _variant == null) return;
        if (_variant.Kind == Section.Movies)
        {
            var subtitle = string.Join("   ·   ", new[] { _variant.VersionLabel, DetailMeta.Text }.Where(x => !string.IsNullOrWhiteSpace(x)));
            PlayMovie(_detailItem, _variant, _variant.Extension ?? _movieInfo?.Extension, subtitle);
        }
        else if (_seriesInfo != null)
        {
            // Reprend la série où on en était, sinon premier épisode.
            var all = _seriesInfo.Seasons.SelectMany(x => x.Episodes).ToList();
            var h = ProfileService.Current.History.FirstOrDefault(x => x.Key == _detailItem.Key);
            var ep = all.FirstOrDefault(x => x.Id == h?.EpisodeId) ?? all.FirstOrDefault(x => !x.IsWatched) ?? all.FirstOrDefault();
            if (ep != null) PlayEpisode(ep);
        }
    }

    /// <summary>Lance un film en reprenant la position mémorisée.</summary>
    private void PlayMovie(MediaItem group, MediaItem variant, string? ext, string subtitle)
    {
        if (_client == null) return;
        var previous = ProfileService.Current.History.FirstOrDefault(x => x.Key == group.Key && x.ItemId == variant.Id);
        StartPlayer(new List<PlaylistEntry>
        {
            new(group.Title, subtitle, _client.MovieUrl(variant.Id, ext))
            {
                StartMs = previous is { HasProgress: true } ? previous.PositionMs : 0,
                Tag = new HistoryEntry { Key = group.Key, Kind = Section.Movies, ItemId = variant.Id, Title = group.Title, ImageUrl = group.ImageUrl, Extension = ext },
            },
        }, 0, isLive: false);
    }

    /// <summary>Lance un épisode ; la suite de la série s'enchaîne automatiquement.</summary>
    private void PlayEpisode(Episode episode)
    {
        if (_seriesInfo == null || _detailItem == null || _variant == null) return;
        PlaySeries(_detailItem, _variant.Id, _seriesInfo, episode);
    }

    private void PlaySeries(MediaItem series, string seriesVariantId, SeriesInfo info, Episode episode)
    {
        if (_client == null) return;
        var previous = ProfileService.Current.History.FirstOrDefault(x => x.Key == series.Key);
        var all = info.Seasons.SelectMany(x => x.Episodes).ToList();
        var playlist = all.Select(ep => new PlaylistEntry(
            series.Title,
            $"Saison {ep.Season}, épisode {ep.Number}" + (string.IsNullOrWhiteSpace(ep.Title) ? "" : $"  ·  {ep.Title}"),
            _client.EpisodeUrl(ep.Id, ep.Extension))
        {
            StartMs = previous is { HasProgress: true } && previous.EpisodeId == ep.Id ? previous.PositionMs : 0,
            Tag = new HistoryEntry
            {
                Key = series.Key, Kind = Section.Series, ItemId = seriesVariantId, Title = series.Title, ImageUrl = series.ImageUrl,
                EpisodeId = ep.Id, Extension = ep.Extension, EpisodeLabel = $"S{ep.Season} · É{ep.Number}",
            },
        }).ToList();
        StartPlayer(playlist, Math.Max(0, all.IndexOf(episode)), isLive: false);
    }

    private void DetailBack_Click(object sender, RoutedEventArgs e) => CloseDetail();

    private void CloseDetail()
    {
        _detailCts?.Cancel();
        DetailView.Visibility = Visibility.Collapsed;
        DetailBackdrop.Source = null;
        DetailPoster.ImageSource = null;
        VersionList.ItemsSource = null;
        _detailItem = null;
        _variant = null;
    }

    // ===================== Téléchargements =====================

    private void DownloadMovie_Click(object sender, RoutedEventArgs e)
    {
        if (_client == null || _detailItem == null || _variant == null || _variant.Kind != Section.Movies) return;
        var ext = _variant.Extension ?? _movieInfo?.Extension ?? "mp4";
        var name = DownloadManager.SafeName(_detailItem.Title + (_detailItem.Year is { } y ? $" ({y})" : "")
                                            + (_variant.Tag.Length > 0 ? $" {_variant.Tag}" : ""));
        DownloadManager.Instance.Enqueue(name, _client.MovieUrl(_variant.Id, ext), Path.Combine("Films", $"{name}.{ext}"));
        ShowDownloads();
    }

    private void DownloadEpisode_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is Episode ep)
        {
            EnqueueEpisode(ep);
            ShowDownloads();
        }
    }

    private void DownloadSeason_Click(object sender, RoutedEventArgs e)
    {
        if (SeasonList.SelectedItem is not Season season) return;
        foreach (var ep in season.Episodes) EnqueueEpisode(ep);
        ShowDownloads();
    }

    private void EnqueueEpisode(Episode ep)
    {
        if (_client == null || _detailItem == null) return;
        var series = DownloadManager.SafeName(_detailItem.Title);
        var name = DownloadManager.SafeName($"{_detailItem.Title} S{ep.Season:00}E{ep.Number:00}"
                                            + (string.IsNullOrWhiteSpace(ep.Title) || ep.Title.Contains(_detailItem.Title) ? "" : $" - {ep.Title}"));
        var ext = string.IsNullOrWhiteSpace(ep.Extension) ? "mp4" : ep.Extension;
        DownloadManager.Instance.Enqueue(name, _client.EpisodeUrl(ep.Id, ext),
            Path.Combine("Séries", series, $"Saison {ep.Season:00}", $"{name}.{ext}"));
    }

    private void Downloads_Click(object sender, RoutedEventArgs e) => ShowDownloads();

    private void ShowDownloads()
    {
        DownloadFolderText.Text = DownloadManager.Folder;
        DownloadsView.Visibility = Visibility.Visible;
    }

    private void DownloadsClose_Click(object sender, RoutedEventArgs e) => DownloadsView.Visibility = Visibility.Collapsed;

    private void UpdateDownloadBadge()
    {
        var active = DownloadManager.Instance.ActiveCount;
        DownloadBadge.Visibility = active > 0 ? Visibility.Visible : Visibility.Collapsed;
        DownloadBadgeText.Text = active.ToString();
        DownloadsEmpty.Visibility = DownloadManager.Instance.Items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static DownloadItem? DownloadOf(object sender) => (sender as FrameworkElement)?.DataContext as DownloadItem;

    private void DownloadCancel_Click(object sender, RoutedEventArgs e)
    {
        if (DownloadOf(sender) is { } d) DownloadManager.Instance.Cancel(d);
    }

    private void DownloadRetry_Click(object sender, RoutedEventArgs e)
    {
        if (DownloadOf(sender) is { } d) DownloadManager.Instance.Retry(d);
    }

    private void DownloadPlay_Click(object sender, RoutedEventArgs e)
    {
        if (DownloadOf(sender) is not { } d || !File.Exists(d.FilePath)) return;
        DownloadsView.Visibility = Visibility.Collapsed;
        StartPlayer(new List<PlaylistEntry> { new(d.Title, "Fichier téléchargé", new Uri(d.FilePath).AbsoluteUri) }, 0, isLive: false);
    }

    private void DownloadOpenFolder_Click(object sender, RoutedEventArgs e)
    {
        if (DownloadOf(sender) is { } d && File.Exists(d.FilePath))
            Process.Start("explorer.exe", $"/select,\"{d.FilePath}\"");
    }

    private void DownloadsOpenRoot_Click(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(DownloadManager.Folder);
        Process.Start("explorer.exe", $"\"{DownloadManager.Folder}\"");
    }

    private void DownloadsChangeFolder_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "Dossier de téléchargement",
            InitialDirectory = Directory.Exists(DownloadManager.Folder) ? DownloadManager.Folder : null,
        };
        if (dlg.ShowDialog(this) != true) return;
        SettingsService.Current.DownloadFolder = dlg.FolderName;
        SettingsService.Save();
        DownloadFolderText.Text = DownloadManager.Folder;
    }

    private void DownloadsClear_Click(object sender, RoutedEventArgs e) => DownloadManager.Instance.ClearFinished();

    // ===================== Mise à jour =====================

    private async void CheckUpdate_Click(object sender, RoutedEventArgs e) => await CheckUpdateAsync(silent: false);

    private async Task CheckUpdateAsync(bool silent)
    {
        if (_updateCts != null) return; // téléchargement en cours
        try
        {
            if (!silent) ShowUpdateBanner("Recherche de mise à jour…", "", canUpdate: false, canClose: false);
            _update = await UpdateService.CheckAsync();
            if (_update != null)
            {
                var notes = _update.Notes?.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.Length > 0) ?? "";
                ShowUpdateBanner($"BibiTV {_update.Tag} est disponible",
                    $"Vous avez la version {UpdateService.CurrentVersionText}.  {DownloadManager.Size(_update.Size)}. {notes}",
                    canUpdate: true, canClose: true);
            }
            else if (!silent)
            {
                ShowUpdateBanner("BibiTV est à jour", $"Version {UpdateService.CurrentVersionText}", canUpdate: false, canClose: true);
            }
        }
        catch (Exception ex)
        {
            if (!silent) ShowUpdateBanner("Vérification impossible", ex.Message, canUpdate: false, canClose: true);
        }
    }

    private void ShowUpdateBanner(string title, string text, bool canUpdate, bool canClose)
    {
        UpdateTitle.Text = title;
        UpdateText.Text = text;
        UpdateProgress.Visibility = Visibility.Collapsed;
        UpdateNowButton.Visibility = canUpdate ? Visibility.Visible : Visibility.Collapsed;
        UpdateNowButton.IsEnabled = true;
        UpdateLaterButton.Content = canUpdate ? "Plus tard" : "OK";
        UpdateLaterButton.Visibility = canClose ? Visibility.Visible : Visibility.Collapsed;
        UpdateBanner.Visibility = Visibility.Visible;
    }

    private void UpdateLater_Click(object sender, RoutedEventArgs e)
    {
        _updateCts?.Cancel();
        UpdateBanner.Visibility = Visibility.Collapsed;
    }

    private async void UpdateNow_Click(object sender, RoutedEventArgs e)
    {
        if (_update == null || _updateCts != null) return;
        if (DownloadManager.Instance.ActiveCount > 0 &&
            MessageBox.Show(this, "Des téléchargements sont en cours et seront interrompus. Continuer la mise à jour ?",
                "BibiTV", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
            return;

        var cts = _updateCts = new CancellationTokenSource();
        UpdateNowButton.IsEnabled = false;
        UpdateLaterButton.Content = "Annuler";
        UpdateTitle.Text = $"Téléchargement de BibiTV {_update.Tag}…";
        UpdateProgress.Visibility = Visibility.Visible;
        UpdateProgress.Value = 0;

        var progress = new Progress<(long done, long total)>(p =>
        {
            if (p.total > 0) UpdateProgress.Value = p.done * 100.0 / p.total;
            UpdateText.Text = p.total > 0 ? $"{DownloadManager.Size(p.done)} sur {DownloadManager.Size(p.total)}" : DownloadManager.Size(p.done);
        });

        try
        {
            await UpdateService.DownloadAndPrepareAsync(_update, progress, cts.Token);
            UpdateTitle.Text = "Installation…";
            UpdateText.Text = "BibiTV va redémarrer automatiquement.";
            _player?.Close();
            await Task.Delay(600);
            Application.Current.Shutdown();
        }
        catch (Exception ex)
        {
            _updateCts = null;
            if (cts.IsCancellationRequested)
                UpdateBanner.Visibility = Visibility.Collapsed;
            else
                ShowUpdateBanner("La mise à jour a échoué", ex.Message, canUpdate: true, canClose: true);
        }
    }

    // ===================== Accueil =====================

    private async Task ShowHomeAsync()
    {
        if (_client == null) return;
        ShowView(home: true);
        if (_homeBuilt) return;

        _homeCts?.Cancel();
        var cts = _homeCts = new CancellationTokenSource();
        ShowLoading("Préparation de l'accueil…");
        var errors = new List<string>();
        foreach (var section in new[] { Section.Movies, Section.Series, Section.Live })
        {
            try { await EnsureLoadedAsync(section); }
            catch (Exception ex) { errors.Add(ex.Message); }
            if (cts.IsCancellationRequested) return;
        }
        HideLoading();
        if (!_onHome || _client == null) return;

        var movies = _items.GetValueOrDefault(Section.Movies) ?? new();
        var series = _items.GetValueOrDefault(Section.Series) ?? new();
        var live = _items.GetValueOrDefault(Section.Live) ?? new();

        var (topMovies, moviesTmdb) = await TopService.TopAsync(Section.Movies, movies, cts.Token);
        var (topSeries, seriesTmdb) = await TopService.TopAsync(Section.Series, series, cts.Token);
        if (cts.IsCancellationRequested) return;

        SetRow(TopMoviesRow, TopMoviesScroll, TopMoviesList, topMovies);
        TopMoviesSource.Text = moviesTmdb ? "Tendances de la semaine (TMDB), disponibles dans votre catalogue" : "Nouveautés de la semaine les mieux notées";
        SetRow(TopSeriesRow, TopSeriesScroll, TopSeriesList, topSeries);
        TopSeriesSource.Text = seriesTmdb ? "Tendances de la semaine (TMDB), disponibles dans votre catalogue" : "Nouveautés de la semaine les mieux notées";

        var catNames = (_categories.GetValueOrDefault(Section.Live) ?? new()).ToDictionary(c => c.Id, c => c.Name);
        var topChannels = ChannelBrands.TopChannels(live, catNames).Select((c, i) => new RankedItem(i + 1, c)).ToList();
        SetRow(TopChannelsRow, TopChannelsScroll, TopChannelsList, topChannels);
        TopChannelsSource.Text = "Les chaînes les plus regardées en France (audiences Médiamétrie)";

        RefreshPersonalRows();

        HomeEmpty.Text = errors.Count > 0 ? "Une partie du catalogue n'a pas pu être chargée : " + string.Join(" / ", errors.Distinct()) : "";
        HomeEmpty.Visibility = errors.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        _homeBuilt = errors.Count == 0;

        await ShowHeroAsync(topMovies.FirstOrDefault()?.Item, cts.Token);
    }

    private void RefreshMyListRow()
    {
        var favs = (_items.GetValueOrDefault(Section.Movies) ?? new()).Concat(_items.GetValueOrDefault(Section.Series) ?? new())
            .Where(IsFavorite).ToList();
        SetRow(MyListRow, MyListScroll, MyListList, favs);
        MyListSource.Text = favs.Count == 1 ? "1 titre" : $"{favs.Count} titres";
    }

    private static void SetRow<T>(FrameworkElement header, ScrollViewer scroll, ItemsControl list, List<T> items)
    {
        list.ItemsSource = items;
        var vis = items.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        header.Visibility = scroll.Visibility = vis;
        scroll.ScrollToHorizontalOffset(0);
    }

    private async Task ShowHeroAsync(MediaItem? item, CancellationToken ct)
    {
        _heroItem = item;
        if (item == null || _client == null)
        {
            Hero.Visibility = Visibility.Collapsed;
            return;
        }
        HeroKicker.Text = "N°1 DES FILMS CETTE SEMAINE";
        HeroTitle.Text = item.Title;
        HeroMeta.Text = BuildMeta(item.Year, item.Genre, null, item.Rating);
        HeroPlot.Text = item.Plot ?? "";
        HeroImage.ImageSource = LoadImage(item.ImageUrl, 1200);
        Hero.Visibility = Visibility.Visible;
        try
        {
            var info = await _client.GetMovieInfoAsync(item.Variants.FirstOrDefault()?.Id ?? item.Id, ct);
            if (ct.IsCancellationRequested || _heroItem != item) return;
            HeroMeta.Text = BuildMeta(Year(info.ReleaseDate) ?? item.Year, info.Genre, info.Duration, info.Rating ?? item.Rating);
            if (!string.IsNullOrWhiteSpace(info.Plot)) HeroPlot.Text = info.Plot;
            if (LoadImage(info.Backdrop, 1600) is { } bd) HeroImage.ImageSource = bd;
        }
        catch
        {
            // Le bandeau garde l'affiche si les détails ne répondent pas.
        }
    }

    private void HeroPlay_Click(object sender, RoutedEventArgs e)
    {
        if (_client == null || _heroItem == null) return;
        var v = _heroItem.Variants.FirstOrDefault() ?? _heroItem;
        PlayMovie(_heroItem, v, v.Extension, v.Tag);
    }

    private void HeroInfo_Click(object sender, RoutedEventArgs e)
    {
        if (_heroItem != null) Open(_heroItem);
    }

    private void RowLeft_Click(object sender, RoutedEventArgs e) => ScrollRow(sender, -1);
    private void RowRight_Click(object sender, RoutedEventArgs e) => ScrollRow(sender, 1);

    private static void ScrollRow(object sender, int direction)
    {
        if ((sender as FrameworkElement)?.Tag is ScrollViewer sv)
            sv.ScrollToHorizontalOffset(sv.HorizontalOffset + direction * sv.ViewportWidth * 0.8);
    }

    /// <summary>La molette fait défiler la page (Maj + molette : la rangée).</summary>
    private void Row_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (sender is not ScrollViewer sv) return;
        e.Handled = true;
        if (Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))
            sv.ScrollToHorizontalOffset(sv.HorizontalOffset - e.Delta);
        else
            HomeView.ScrollToVerticalOffset(HomeView.VerticalOffset - e.Delta);
    }

    // ===================== Jingle d'ouverture (silencieux) =====================

    private void PlaySplash()
    {
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        DoubleAnimation A(double from, double to, double begin, double dur, IEasingFunction? e = null) =>
            new(from, to, TimeSpan.FromSeconds(dur)) { BeginTime = TimeSpan.FromSeconds(begin), EasingFunction = e ?? ease };

        SplashLogo.BeginAnimation(OpacityProperty, A(0, 1, 0.15, 0.7));
        var pop = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.35 };
        SplashScale.BeginAnimation(ScaleTransform.ScaleXProperty, A(0.82, 1, 0.15, 0.95, pop));
        SplashScale.BeginAnimation(ScaleTransform.ScaleYProperty, A(0.82, 1, 0.15, 0.95, pop));

        var glow = new DoubleAnimationUsingKeyFrames();
        glow.KeyFrames.Add(new EasingDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromSeconds(0.2))));
        glow.KeyFrames.Add(new EasingDoubleKeyFrame(0.9, KeyTime.FromTimeSpan(TimeSpan.FromSeconds(1.0)), ease));
        glow.KeyFrames.Add(new EasingDoubleKeyFrame(0.45, KeyTime.FromTimeSpan(TimeSpan.FromSeconds(1.8)), ease));
        SplashGlow.BeginAnimation(System.Windows.Media.Effects.DropShadowEffect.OpacityProperty, glow);

        var sineInOut = new SineEase { EasingMode = EasingMode.EaseInOut };
        SplashSweep.BeginAnimation(TranslateTransform.XProperty, A(-1.2, 1.2, 0.85, 0.9, sineInOut));
        SplashSweep.BeginAnimation(TranslateTransform.YProperty, A(-1.2, 1.2, 0.85, 0.9, sineInOut));

        SplashWord.BeginAnimation(OpacityProperty, A(0, 1, 1.0, 0.6));
        SplashWordShift.BeginAnimation(TranslateTransform.YProperty, A(14, 0, 1.0, 0.7));

        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.7) };
        timer.Tick += (_, _) => { timer.Stop(); HideSplash(); };
        timer.Start();
    }

    private void Splash_Skip(object sender, MouseButtonEventArgs e) => HideSplash();

    private void HideSplash()
    {
        if (SplashView.Visibility != Visibility.Visible || !SplashView.IsHitTestVisible) return;
        SplashView.IsHitTestVisible = false;
        var fade = new DoubleAnimation(0, TimeSpan.FromSeconds(0.5)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn } };
        fade.Completed += (_, _) => SplashView.Visibility = Visibility.Collapsed;
        SplashView.BeginAnimation(OpacityProperty, fade);
    }

    // ===================== Programme TV (EPG) =====================

    private void Tile_MouseEnter(object sender, MouseEventArgs e)
    {
        if (Unwrap((sender as FrameworkElement)?.DataContext) is { Kind: Section.Live } item) ShowEpgFor(item);
    }

    private void Tile_MouseLeave(object sender, MouseEventArgs e)
    {
        // Si une chaîne est sélectionnée au clavier, on revient sur elle ; sinon on masque.
        _epgHide.Stop();
        _epgHide.Start();
    }

    private void Tile_Selected(object sender, RoutedEventArgs e)
    {
        if (sender is ListBoxItem { IsKeyboardFocusWithin: true } li && Unwrap(li.DataContext) is { Kind: Section.Live } item)
            ShowEpgFor(item);
    }

    private void ShowEpgFor(MediaItem channel)
    {
        _epgHide.Stop();
        if (_epgItem == channel && EpgCard.Visibility == Visibility.Visible) return;
        _epgItem = channel;

        EpgChannel.Text = channel.Title.ToUpperInvariant();
        EpgLogo.Source = LoadImage(channel.ImageUrl, 160);
        if (_epgCache.TryGetValue(channel.Id, out var c) && DateTime.Now - c.fetched < TimeSpan.FromMinutes(5))
            FillEpgCard(c.entries);
        else
        {
            EpgNowPanel.Visibility = EpgNextPanel.Visibility = Visibility.Collapsed;
            EpgStatus.Text = "Chargement du programme…";
            EpgStatus.Visibility = Visibility.Visible;
            _epgDelay.Stop();
            _epgDelay.Start(); // évite une requête par chaîne survolée en passant
        }

        if (EpgCard.Visibility != Visibility.Visible)
        {
            EpgCard.Visibility = Visibility.Visible;
            EpgCard.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromSeconds(0.18)));
        }
    }

    private void HideEpgCard()
    {
        _epgDelay.Stop();
        _epgHide.Stop();
        _epgItem = null;
        if (EpgCard.Visibility != Visibility.Visible) return;
        var fade = new DoubleAnimation(0, TimeSpan.FromSeconds(0.15));
        fade.Completed += (_, _) => { if (_epgItem == null) EpgCard.Visibility = Visibility.Collapsed; };
        EpgCard.BeginAnimation(OpacityProperty, fade);
    }

    private async Task LoadEpgCardAsync()
    {
        var channel = _epgItem;
        if (channel == null) return;
        var entries = await GetEpgAsync(channel);
        if (_epgItem == channel) FillEpgCard(entries);
    }

    private async Task<List<EpgEntry>> GetEpgAsync(MediaItem channel)
    {
        if (_epgCache.TryGetValue(channel.Id, out var c) && DateTime.Now - c.fetched < TimeSpan.FromMinutes(5))
            return c.entries.Where(x => x.End > DateTime.Now).ToList();
        if (_client == null) return new();
        try
        {
            var entries = await _client.GetShortEpgAsync(channel.Id);
            _epgCache[channel.Id] = (DateTime.Now, entries);
            return entries;
        }
        catch
        {
            return new();
        }
    }

    private void FillEpgCard(List<EpgEntry> entries)
    {
        var now = entries.FirstOrDefault(x => x.IsNow);
        var next = entries.FirstOrDefault(x => x.Start >= (now?.End ?? DateTime.Now) && x != now);

        EpgNowPanel.Visibility = now != null ? Visibility.Visible : Visibility.Collapsed;
        if (now != null)
        {
            EpgNowTime.Text = $"{now.TimeRange}  ·  encore {Remaining(now.End)}";
            EpgNowTitle.Text = now.Title;
            EpgProgress.Value = now.Progress;
            EpgNowDesc.Text = now.Description ?? "";
            EpgNowDesc.Visibility = string.IsNullOrWhiteSpace(now.Description) ? Visibility.Collapsed : Visibility.Visible;
        }

        EpgNextPanel.Visibility = next != null ? Visibility.Visible : Visibility.Collapsed;
        if (next != null)
        {
            EpgNextTime.Text = $"ENSUITE  {next.Start:HH:mm}";
            EpgNextTitle.Text = next.Title;
        }

        EpgStatus.Visibility = now == null && next == null ? Visibility.Visible : Visibility.Collapsed;
        EpgStatus.Text = "Pas de programme disponible pour cette chaîne.";
    }

    private static string Remaining(DateTime end)
    {
        var t = end - DateTime.Now;
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours} h {t.Minutes:00}" : $"{Math.Max(1, (int)Math.Ceiling(t.TotalMinutes))} min";
    }

    // ===================== Historique, « déjà vu », goûts =====================

    private void OnEntryStarted(PlaylistEntry entry)
    {
        if (entry.Tag is not HistoryEntry h) return;
        ProfileService.Touch(h);
        if (_homeBuilt) RefreshPersonalRows();
    }

    private void OnPlaybackProgress(PlaylistEntry entry, long position, long length)
    {
        if (entry.Tag is not HistoryEntry h || length <= 0) return;
        var finished = position >= length * 0.92;
        h.PositionMs = finished ? 0 : position;
        h.LengthMs = length;

        if (finished)
        {
            var watchKey = h.EpisodeId != null ? ProfileService.EpisodeKey(h.EpisodeId) : h.Key;
            ProfileService.Current.Watched.Add(watchKey);
            if (h.EpisodeId == null && _byKey.TryGetValue(h.Key, out var item)) item.NotifyProfileChanged();
        }
        ProfileService.Touch(h);
        EpisodeList.Items.Refresh();
        if (_homeBuilt) RefreshPersonalRows();
    }

    /// <summary>Reprend un élément de l'historique (film, épisode ou chaîne).</summary>
    private async Task ResumeAsync(HistoryEntry h, ItemsControl? source)
    {
        if (_client == null) return;
        try
        {
            switch (h.Kind)
            {
                case Section.Live:
                    var channels = (source?.ItemsSource as IEnumerable<HistoryEntry>)?.ToList() ?? new() { h };
                    var playlist = channels.Select(c => new PlaylistEntry(c.Title, "En direct", _client.LiveUrl(c.ItemId))
                    {
                        Tag = new HistoryEntry { Key = c.Key, Kind = Section.Live, ItemId = c.ItemId, Title = c.Title, ImageUrl = c.ImageUrl },
                    }).ToList();
                    var live = channels.Select(c => _byKey.GetValueOrDefault(c.Key) ?? new MediaItem { Kind = Section.Live, Id = c.ItemId, Title = c.Title }).ToList();
                    StartPlayer(playlist, Math.Max(0, channels.IndexOf(h)), isLive: true,
                        liveInfo: async i => (await GetEpgAsync(live[i])).FirstOrDefault(x => x.IsNow) is { } now
                            ? $"En direct  ·  {now.TimeRange}  ·  {now.Title}" : null);
                    break;

                case Section.Movies:
                    var group = _byKey.GetValueOrDefault(h.Key)
                                ?? new MediaItem { Kind = Section.Movies, Id = h.ItemId, Title = h.Title, ImageUrl = h.ImageUrl };
                    var variant = group.Variants.FirstOrDefault(v => v.Id == h.ItemId) ?? group.Variants.FirstOrDefault() ?? group;
                    PlayMovie(group, variant, h.Extension ?? variant.Extension, variant.Tag);
                    break;

                case Section.Series:
                    ShowLoading("Reprise de la série…");
                    var info = await _client.GetSeriesInfoAsync(h.ItemId);
                    HideLoading();
                    var series = _byKey.GetValueOrDefault(h.Key)
                                 ?? new MediaItem { Kind = Section.Series, Id = h.ItemId, Title = h.Title, ImageUrl = h.ImageUrl };
                    var all = info.Seasons.SelectMany(x => x.Episodes).ToList();
                    var current = all.FindIndex(x => x.Id == h.EpisodeId);
                    // Épisode terminé : on passe au suivant.
                    if (current >= 0 && !h.HasProgress && all[current].IsWatched && current < all.Count - 1) current++;
                    if (all.Count > 0) PlaySeries(series, h.ItemId, info, all[Math.Max(0, current)]);
                    break;
            }
        }
        catch (Exception ex)
        {
            HideLoading();
            MessageBox.Show(this, $"Impossible de reprendre la lecture : {ex.Message}", "BibiTV", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void SetWatched(MediaItem item, bool watched)
    {
        ProfileService.SetWatched(item.Key, watched);
        item.NotifyProfileChanged();
        if (_detailItem == item) UpdateProfileButtons();
        if (ProfileService.Current.HideWatched && !_onHome) ApplyFilter();
        if (_homeBuilt) RefreshPersonalRows();
    }

    private void SetRating(MediaItem item, int rating)
    {
        ProfileService.SetRating(item.Key, rating);
        item.NotifyProfileChanged();
        if (_detailItem == item) UpdateProfileButtons();
        if (_homeBuilt && PersonalizeView.Visibility != Visibility.Visible) RefreshPersonalRows();
    }

    private void DetailWatched_Click(object sender, RoutedEventArgs e)
    {
        if (_detailItem != null) SetWatched(_detailItem, !_detailItem.IsWatched);
    }

    private void DetailLike_Click(object sender, RoutedEventArgs e)
    {
        if (_detailItem != null) SetRating(_detailItem, _detailItem.IsLiked ? 0 : 1);
    }

    private void DetailDislike_Click(object sender, RoutedEventArgs e)
    {
        if (_detailItem != null) SetRating(_detailItem, _detailItem.IsDisliked ? 0 : -1);
    }

    private void UpdateProfileButtons()
    {
        if (_detailItem == null) return;
        WatchedButton.Content = _detailItem.IsWatched ? "✓  Vu" : "Marquer comme vu";
        StyleChoice(WatchedButton, _detailItem.IsWatched, "#0A84FF");
        StyleChoice(LikeButton, _detailItem.IsLiked, "#30D158");
        StyleChoice(DislikeButton, _detailItem.IsDisliked, "#FF453A");
    }

    private static void StyleChoice(Button b, bool active, string color)
    {
        if (active) b.Background = (Brush)new BrushConverter().ConvertFromString(color)!;
        else b.ClearValue(BackgroundProperty);
    }

    private void HideWatched_Changed(object sender, RoutedEventArgs e)
    {
        var hide = HideWatchedToggle.IsChecked == true;
        if (ProfileService.Current.HideWatched == hide) return;
        ProfileService.Current.HideWatched = hide;
        ProfileService.Save();
        ApplyFilter();
        if (_homeBuilt) RefreshPersonalRows();
    }

    /// <summary>Rangées de l'accueil qui dépendent du profil : Reprendre, chaînes récentes, Ma liste, Pour toi.</summary>
    private void RefreshPersonalRows()
    {
        var history = ProfileService.Current.History;
        SetRow(ContinueRow, ContinueScroll, ContinueList, history.Where(h => h.Kind != Section.Live).Take(20).ToList());
        ContinueSource.Text = "Films et séries regardés récemment";
        SetRow(RecentChannelsRow, RecentChannelsScroll, RecentChannelsList, history.Where(h => h.Kind == Section.Live).Take(15).ToList());
        RecentChannelsSource.Text = "Pour zapper rapidement";

        RefreshMyListRow();

        var hide = ProfileService.Current.HideWatched;
        var movies = _items.GetValueOrDefault(Section.Movies) ?? new();
        var series = _items.GetValueOrDefault(Section.Series) ?? new();
        var movieCats = (_categories.GetValueOrDefault(Section.Movies) ?? new()).ToDictionary(c => c.Id, c => c.Name);
        var seriesCats = (_categories.GetValueOrDefault(Section.Series) ?? new()).ToDictionary(c => c.Id, c => c.Name);

        var forMovies = RecommendationService.Suggest(movies);
        var forSeries = RecommendationService.Suggest(series);
        SetRow(ForYouMoviesRow, ForYouMoviesScroll, ForYouMoviesList, forMovies);
        SetRow(ForYouSeriesRow, ForYouSeriesScroll, ForYouSeriesList, forSeries);
        ForYouMoviesSource.Text = Tastes(RecommendationService.TopTastes(movies, movieCats));
        ForYouSeriesSource.Text = Tastes(RecommendationService.TopTastes(series, seriesCats));
        ForYouCta.Visibility = forMovies.Count == 0 && forSeries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        SetRow(RecentMoviesRow, RecentMoviesScroll, RecentMoviesList,
            movies.Where(m => !hide || !m.IsWatched).OrderByDescending(m => m.Added).Take(24).ToList());
        RecentMoviesSource.Text = "Derniers ajouts au catalogue";
        SetRow(RecentSeriesRow, RecentSeriesScroll, RecentSeriesList,
            series.Where(m => !hide || !m.IsWatched).OrderByDescending(m => m.Added).Take(24).ToList());
        RecentSeriesSource.Text = "Dernières séries ajoutées ou mises à jour";
    }

    private static string Tastes(List<string> tastes) =>
        tastes.Count > 0 ? "Parce que vous aimez : " + string.Join(", ", tastes) : "D'après vos goûts";

    // ----- Écran « Personnaliser » -----

    private void Personalize_Click(object sender, RoutedEventArgs e)
    {
        PersonalizeView.Visibility = Visibility.Visible;
        FillPersonalize();
    }

    private void PersoTab_Checked(object sender, RoutedEventArgs e)
    {
        if (PersonalizeView?.Visibility == Visibility.Visible) FillPersonalize();
    }

    private void FillPersonalize()
    {
        var kind = PersoSeries.IsChecked == true ? Section.Series : Section.Movies;
        var catalog = _items.GetValueOrDefault(kind) ?? new();
        PersoList.ItemsSource = RecommendationService.Candidates(catalog);
        UpdatePersoSubtitle();
    }

    private void UpdatePersoSubtitle()
    {
        var p = ProfileService.Current;
        var n = p.Likes.Count + p.Dislikes.Count;
        PersoSubtitle.Text = (n == 0
            ? "Notez quelques titres que vous avez vus : plus vous en notez, plus les suggestions « Pour toi » sont justes."
            : $"{n} titre{(n > 1 ? "s" : "")} noté{(n > 1 ? "s" : "")}. Continuez pour affiner vos suggestions.");
    }

    private void RateLike_Click(object sender, RoutedEventArgs e) => Rate(sender, 1);
    private void RateDislike_Click(object sender, RoutedEventArgs e) => Rate(sender, -1);

    private void Rate(object sender, int rating)
    {
        if ((sender as FrameworkElement)?.DataContext is not MediaItem item) return;
        SetRating(item, item.UserRating == rating ? 0 : rating);
        UpdatePersoSubtitle();
    }

    private void RateWatched_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is MediaItem item) SetWatched(item, !item.IsWatched);
    }

    private void PersonalizeClose_Click(object sender, RoutedEventArgs e) => ClosePersonalize();

    private void ClosePersonalize()
    {
        PersonalizeView.Visibility = Visibility.Collapsed;
        PersoList.ItemsSource = null;
        if (_homeBuilt) RefreshPersonalRows();
    }

    // ===================== Favoris =====================

    private static string FavKey(MediaItem item) => $"{item.Kind}:{item.Id}";

    /// <summary>Une fiche est en favori si l'une de ses versions l'est.</summary>
    private static bool IsFavorite(MediaItem item)
    {
        var favs = SettingsService.Current.Favorites;
        return item.Variants.Count == 0 ? favs.Contains(FavKey(item)) : item.Variants.Any(v => favs.Contains(FavKey(v)));
    }

    private void ToggleFavorite(MediaItem item)
    {
        var favs = SettingsService.Current.Favorites;
        if (IsFavorite(item))
            foreach (var v in item.Variants.Append(item)) favs.Remove(FavKey(v));
        else
            favs.Add(FavKey(item));
        SettingsService.Save();
        if (item.Kind == _section && _items.ContainsKey(_section)) RefreshCategories(selectFirst: false);
        if (_homeBuilt) RefreshPersonalRows();
        UpdateFavButton();
    }

    private void Fav_Click(object sender, RoutedEventArgs e)
    {
        if (_detailItem != null) ToggleFavorite(_detailItem);
    }

    private void UpdateFavButton()
    {
        if (_detailItem != null)
            FavButton.Content = IsFavorite(_detailItem) ? "✓  Dans Ma liste" : "+  Ma liste";
    }

    // ===================== Lecteur =====================

    private void StartPlayer(List<PlaylistEntry> playlist, int index, bool isLive, Func<int, Task<string?>>? liveInfo = null)
    {
        if (playlist.Count == 0) return;
        HideEpgCard();
        _player?.Close();
        _player = new PlayerWindow(playlist, index, isLive, liveInfo) { Owner = this };
        _player.EntryStarted += OnEntryStarted;
        _player.Progress += OnPlaybackProgress;
        _player.Closed += (s, _) => { if (ReferenceEquals(_player, s)) _player = null; Activate(); };
        _player.Show();
    }

    // ===================== Clavier =====================

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && PersonalizeView.Visibility == Visibility.Visible)
        {
            ClosePersonalize();
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && DownloadsView.Visibility == Visibility.Visible)
        {
            DownloadsView.Visibility = Visibility.Collapsed;
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && DetailView.Visibility == Visibility.Visible)
        {
            CloseDetail();
            e.Handled = true;
        }
        else if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control && BrowseView.Visibility == Visibility.Visible)
        {
            CloseDetail();
            if (_onHome) NavMovies.IsChecked = true;
            SearchBox.Focus();
            SearchBox.SelectAll();
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && SearchBox.IsKeyboardFocused && SearchBox.Text.Length > 0)
        {
            SearchBox.Text = "";
            e.Handled = true;
        }
    }

    // ===================== Utilitaires UI =====================

    private void ShowLoading(string text)
    {
        LoadingText.Text = text;
        LoadingView.Visibility = Visibility.Visible;
    }

    private void HideLoading() => LoadingView.Visibility = Visibility.Collapsed;

    private void ShowEmpty(string text, bool retry)
    {
        EmptyText.Text = text;
        RetryButton.Visibility = retry ? Visibility.Visible : Visibility.Collapsed;
        EmptyState.Visibility = Visibility.Visible;
    }

    private static BitmapImage? LoadImage(string? url, int width) =>
        new ImageUrlConverter { DecodeWidth = width }.Convert(url, typeof(BitmapImage), null, CultureInfo.InvariantCulture) as BitmapImage;

    private static string BuildMeta(string? year, string? genre, string? duration, string? rating) =>
        string.Join("   ·   ", new[] { year, genre, duration, MediaItem.FormatRating(rating) }.Where(s => !string.IsNullOrWhiteSpace(s)));

    private static string BuildPeople(string? director, string? cast)
    {
        var parts = new List<string>();
        if (!string.IsNullOrWhiteSpace(director)) parts.Add($"Réalisation : {director}");
        if (!string.IsNullOrWhiteSpace(cast)) parts.Add($"Avec : {cast}");
        return string.Join("\n", parts);
    }

    private static string? Year(string? s) =>
        s != null && s.Length >= 4 && int.TryParse(s[..4], out var y) && y > 1900 ? y.ToString() : null;
}
