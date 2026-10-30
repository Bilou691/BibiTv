using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
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
    private MediaItem? _detailItem;
    private MovieInfo? _movieInfo;
    private SeriesInfo? _seriesInfo;
    private PlayerWindow? _player;

    public MainWindow()
    {
        InitializeComponent();
        _searchDebounce = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        _searchDebounce.Tick += (_, _) => { _searchDebounce.Stop(); ApplyFilter(); };

        SourceInitialized += (_, _) => WindowHelper.UseDarkTitleBar(this);
        Loaded += OnLoaded;
        PreviewKeyDown += OnPreviewKeyDown;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
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

            if (NavMovies.IsChecked == true) await LoadSectionAsync(Section.Movies);
            else NavMovies.IsChecked = true;
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
        _categories.Clear();
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
        var section = sender == NavLive ? Section.Live : sender == NavSeries ? Section.Series : Section.Movies;
        await LoadSectionAsync(section);
    }

    private async Task LoadSectionAsync(Section section)
    {
        if (_client == null) return;
        _section = section;
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
                var client = _client;
                var catsTask = client.GetCategoriesAsync(section);
                var itemsTask = client.GetItemsAsync(section);
                await Task.WhenAll(catsTask, itemsTask);
                if (client != _client) return; // déconnecté entre-temps
                _items[section] = itemsTask.Result;
                _categories[section] = catsTask.Result;
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

        if (_section != section) return; // l'utilisateur a changé d'onglet pendant le chargement
        RefreshCategories(selectFirst: true);
    }

    private void RefreshCategories(bool selectFirst)
    {
        var items = _items[_section];
        var counts = items.GroupBy(i => i.CategoryId).ToDictionary(g => g.Key, g => g.Count());
        var favCount = items.Count(IsFavorite);

        var list = new List<Category>();
        if (favCount > 0) list.Add(new Category { Id = FavoritesId, Name = "Ma liste", Count = favCount });
        list.Add(new Category
        {
            Id = Category.AllId,
            Name = _section == Section.Live ? "Toutes les chaînes" : "Récemment ajoutés",
            Count = items.Count,
        });
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
        if (CategoryList.SelectedItem == null) return;
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
                var id => all.Where(i => i.CategoryId == id),
            };
            HeaderTitle.Text = cat?.Name ?? "";
        }

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
        if ((sender as ListBoxItem)?.DataContext is MediaItem item) Open(item);
    }

    private void TileList_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && (sender as ListBox)?.SelectedItem is MediaItem item)
        {
            Open(item);
            e.Handled = true;
        }
    }

    private void Tile_RightClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: MediaItem item } container) return;
        var fav = IsFavorite(item);
        var menuItem = new MenuItem { Header = fav ? "Retirer de Ma liste" : "Ajouter à Ma liste" };
        menuItem.Click += (_, _) => ToggleFavorite(item);
        var menu = new ContextMenu { PlacementTarget = container };
        menu.Items.Add(menuItem);
        menu.IsOpen = true;
        e.Handled = true;
    }

    private void Open(MediaItem item)
    {
        if (item.Kind == Section.Live)
        {
            // Lecture directe, avec zapping possible dans la liste affichée.
            var channels = (LiveList.ItemsSource as IEnumerable<MediaItem>)?.ToList() ?? new List<MediaItem> { item };
            var playlist = channels.Select(c => new PlaylistEntry(c.Title, "En direct", _client!.LiveUrl(c.Id))).ToList();
            StartPlayer(playlist, Math.Max(0, channels.IndexOf(item)), isLive: true);
        }
        else
        {
            _ = ShowDetailAsync(item);
        }
    }

    // ===================== Fiche détail =====================

    private async Task ShowDetailAsync(MediaItem item)
    {
        if (_client == null) return;
        _detailCts?.Cancel();
        var cts = _detailCts = new CancellationTokenSource();

        _detailItem = item;
        _movieInfo = null;
        _seriesInfo = null;

        DetailTitle.Text = item.Title;
        DetailMeta.Text = BuildMeta(item.Year, item.Genre, null, item.Rating);
        DetailPlot.Text = item.Plot ?? "";
        DetailPeople.Text = "";
        DetailPoster.ImageSource = LoadImage(item.ImageUrl, 500);
        DetailBackdrop.Source = null;
        PlayLabel.Text = "Lecture";
        PlayButton.IsEnabled = item.Kind == Section.Movies;
        SeriesPanel.Visibility = Visibility.Collapsed;
        SeasonList.ItemsSource = null;
        EpisodeList.ItemsSource = null;
        UpdateFavButton();
        DetailLoading.Visibility = Visibility.Visible;
        DetailView.Visibility = Visibility.Visible;
        PlayButton.Focus();

        try
        {
            if (item.Kind == Section.Movies)
            {
                var info = await _client.GetMovieInfoAsync(item.Id, cts.Token);
                if (cts.IsCancellationRequested) return;
                _movieInfo = info;
                DetailMeta.Text = BuildMeta(Year(info.ReleaseDate) ?? item.Year, info.Genre, info.Duration, info.Rating ?? item.Rating);
                if (!string.IsNullOrWhiteSpace(info.Plot)) DetailPlot.Text = info.Plot;
                DetailPeople.Text = BuildPeople(info.Director, info.Cast);
                DetailBackdrop.Source = LoadImage(info.Backdrop, 1600);
            }
            else
            {
                var info = await _client.GetSeriesInfoAsync(item.Id, cts.Token);
                if (cts.IsCancellationRequested) return;
                _seriesInfo = info;
                DetailMeta.Text = BuildMeta(Year(info.ReleaseDate) ?? item.Year, info.Genre ?? item.Genre,
                    info.Seasons.Count switch { 0 => null, 1 => "1 saison", var n => $"{n} saisons" }, item.Rating);
                if (!string.IsNullOrWhiteSpace(info.Plot)) DetailPlot.Text = info.Plot;
                DetailPeople.Text = BuildPeople(null, info.Cast);
                DetailBackdrop.Source = LoadImage(info.Backdrop, 1600);

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
                    DetailPeople.Text += (DetailPeople.Text.Length > 0 ? "\n\n" : "") + "Aucun épisode disponible.";
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
        if (_client == null || _detailItem == null) return;
        if (_detailItem.Kind == Section.Movies)
        {
            var ext = _movieInfo?.Extension ?? _detailItem.Extension;
            StartPlayer(new List<PlaylistEntry> { new(_detailItem.Title, DetailMeta.Text, _client.MovieUrl(_detailItem.Id, ext)) }, 0, isLive: false);
        }
        else if (_seriesInfo?.Seasons.FirstOrDefault()?.Episodes.FirstOrDefault() is { } first)
        {
            PlayEpisode(first);
        }
    }

    /// <summary>Lance un épisode ; la suite de la série s'enchaîne automatiquement.</summary>
    private void PlayEpisode(Episode episode)
    {
        if (_client == null || _seriesInfo == null || _detailItem == null) return;
        var all = _seriesInfo.Seasons.SelectMany(s => s.Episodes).ToList();
        var playlist = all.Select(ep => new PlaylistEntry(
            _detailItem.Title,
            $"Saison {ep.Season}, épisode {ep.Number}" + (string.IsNullOrWhiteSpace(ep.Title) ? "" : $"  ·  {ep.Title}"),
            _client.EpisodeUrl(ep.Id, ep.Extension))).ToList();
        StartPlayer(playlist, Math.Max(0, all.IndexOf(episode)), isLive: false);
    }

    private void DetailBack_Click(object sender, RoutedEventArgs e) => CloseDetail();

    private void CloseDetail()
    {
        _detailCts?.Cancel();
        DetailView.Visibility = Visibility.Collapsed;
        DetailBackdrop.Source = null;
        DetailPoster.ImageSource = null;
        _detailItem = null;
    }

    // ===================== Favoris =====================

    private static string FavKey(MediaItem item) => $"{item.Kind}:{item.Id}";

    private static bool IsFavorite(MediaItem item) => SettingsService.Current.Favorites.Contains(FavKey(item));

    private void ToggleFavorite(MediaItem item)
    {
        var favs = SettingsService.Current.Favorites;
        if (!favs.Remove(FavKey(item))) favs.Add(FavKey(item));
        SettingsService.Save();
        if (item.Kind == _section) RefreshCategories(selectFirst: false);
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

    private void StartPlayer(List<PlaylistEntry> playlist, int index, bool isLive)
    {
        if (playlist.Count == 0) return;
        _player?.Close();
        _player = new PlayerWindow(playlist, index, isLive) { Owner = this };
        _player.Closed += (s, _) => { if (ReferenceEquals(_player, s)) _player = null; Activate(); };
        _player.Show();
    }

    // ===================== Clavier =====================

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && DetailView.Visibility == Visibility.Visible)
        {
            CloseDetail();
            e.Handled = true;
        }
        else if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control && BrowseView.Visibility == Visibility.Visible)
        {
            CloseDetail();
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
