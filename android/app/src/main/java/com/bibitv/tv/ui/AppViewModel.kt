package com.bibitv.tv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bibitv.tv.data.Category
import com.bibitv.tv.data.ChannelBrands
import com.bibitv.tv.data.DownloadManager
import com.bibitv.tv.data.EpgEntry
import com.bibitv.tv.data.Episode
import com.bibitv.tv.data.HistoryEntry
import com.bibitv.tv.data.MediaItem
import com.bibitv.tv.data.MovieInfo
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.RankedItem
import com.bibitv.tv.data.Recommendations
import com.bibitv.tv.data.Section
import com.bibitv.tv.data.SortMode
import com.bibitv.tv.data.sortedByMode
import com.bibitv.tv.data.SeriesInfo
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.data.TitleGrouper
import com.bibitv.tv.data.TopService
import com.bibitv.tv.data.UpdateInfo
import com.bibitv.tv.data.UpdateManager
import com.bibitv.tv.data.XtreamClient
import com.bibitv.tv.data.yearOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { Home("Accueil"), Live("En direct"), Movies("Films"), Series("Séries") }

/** Élément de la liste de lecture du lecteur. */
data class PlaylistEntry(
    val title: String,
    val subtitle: String,
    val url: String,
    val startMs: Long = 0,
    val history: HistoryEntry? = null,
    val channel: MediaItem? = null,
)

data class PlayerRequest(val playlist: List<PlaylistEntry>, val index: Int, val isLive: Boolean)

data class MenuRequest(val title: String, val actions: List<Pair<String, () -> Unit>>)

/** Rangées de l'accueil qui dépendent du profil (goûts, favoris, vus) : calculées hors du fil principal. */
data class HomeRows(
    val revision: Int,
    val forMovies: List<MediaItem>,
    val tastesMovies: List<String>,
    val forSeries: List<MediaItem>,
    val tastesSeries: List<String>,
    val myList: List<MediaItem>,
    val favChannels: List<MediaItem>,
    val recentMovies: List<MediaItem>,
    val recentSeries: List<MediaItem>,
)

data class HomeData(
    val hero: MediaItem?,
    val topMovies: List<RankedItem>,
    val moviesFromTmdb: Boolean,
    val topSeries: List<RankedItem>,
    val seriesFromTmdb: Boolean,
    val topChannels: List<RankedItem>,
    val errors: List<String>,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    // ---------- État général ----------
    var showSplash by mutableStateOf(true)
    var client by mutableStateOf<XtreamClient?>(null)
        private set
    var loginError by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var accountExpiry by mutableStateOf<Long?>(null)
    var tab by mutableStateOf(Tab.Home)

    val items = mutableStateMapOf<Section, List<MediaItem>>()
    /** Films / séries triés par date d'ajout (« Récemment ajoutés »), calculé une fois au chargement. */
    private val byAdded = HashMap<Section, List<MediaItem>>()
    val categories = mutableStateMapOf<Section, List<Category>>()
    val loadErrors = mutableStateMapOf<Section, String>()
    private val byKey = HashMap<String, MediaItem>()
    /** Heure du dernier chargement du catalogue (synchronisation automatique). */
    var catalogLoadedAt by mutableStateOf(0L)
        private set

    // ---------- Mobile ----------
    /** Lecture en petite fenêtre (smartphone / tablette) : contrôles masqués. */
    var inPip by mutableStateOf(false)
    /** Incrémenté quand l'app passe en arrière-plan : le lecteur se met en pause. */
    var pauseSignal by mutableStateOf(0)
        private set
    /** Onglet « Recherche » de la barre du bas (smartphone / tablette). */
    var mobileSearch by mutableStateOf(false)
    /** Demande l'autorisation d'afficher la notification de téléchargement (Android 13+). */
    var requestNotificationPermission: (() -> Unit)? = null

    fun onBackground() {
        if (player != null) pauseSignal++
    }

    // ---------- Écrans superposés ----------
    var detail by mutableStateOf<MediaItem?>(null)
    var player by mutableStateOf<PlayerRequest?>(null)
    var showDownloads by mutableStateOf(false)
    var showPersonalize by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var menu by mutableStateOf<MenuRequest?>(null)

    // ---------- Position dans les listes (conservée quand on revient d'une fiche) ----------
    val catalogCategory = mutableStateMapOf<Section, String>()
    val catalogQuery = mutableStateMapOf<Section, String>()
    val catalogSort = mutableStateMapOf<Section, SortMode>()
    val gridStates = HashMap<Section, androidx.compose.foundation.lazy.grid.LazyGridState>()
    val homeState = androidx.compose.foundation.lazy.LazyListState()

    // ---------- Accueil ----------
    var home by mutableStateOf<HomeData?>(null)
    var heroInfo by mutableStateOf<MovieInfo?>(null)
    var homeRows by mutableStateOf<HomeRows?>(null)
        private set
    private var rowsJob: Job? = null

    /**
     * Recalcule les rangées « Pour toi », « Ma liste », « Favoris », « Nouveaux… ». Avant, ces tris et
     * filtres sur tout le catalogue (jusqu'à 100 000 titres) se faisaient dans la composition, sur le
     * fil principal, à chaque changement du profil (un favori ajouté, un film marqué vu) : l'accueil
     * se figeait un instant sur les box modestes.
     */
    fun refreshHomeRows(revision: Int) {
        if (homeRows?.revision == revision && rowsJob?.isActive != true) return
        rowsJob?.cancel()
        val movies = items[Section.Movies] ?: emptyList()
        val series = items[Section.Series] ?: emptyList()
        val live = items[Section.Live] ?: emptyList()
        val movieCats = categoryNames(Section.Movies)
        val seriesCats = categoryNames(Section.Series)
        rowsJob = viewModelScope.launch {
            val rows = withContext(Dispatchers.Default) {
                val p = ProfileStore.snapshot()
                fun recent(list: List<MediaItem>) = list.asSequence().filter { !p.hideWatched || it.key !in p.watched }
                    .sortedByDescending { it.added }.take(24).toList()
                HomeRows(
                    revision,
                    Recommendations.suggest(movies, p = p), Recommendations.topTastes(movies, movieCats, p = p),
                    Recommendations.suggest(series, p = p), Recommendations.topTastes(series, seriesCats, p = p),
                    (movies + series).filter { ProfileStore.isFavorite(it, p) },
                    live.filter { ProfileStore.isFavorite(it, p) },
                    recent(movies), recent(series),
                )
            }
            homeRows = rows
        }
    }

    /** Bouton « Lecture » du titre à la une : premier élément focalisé au démarrage. */
    val heroFocus = androidx.compose.ui.focus.FocusRequester()
    var homeFocused = false

    // ---------- Fiche détail ----------
    var variant by mutableStateOf<MediaItem?>(null)
    var movieInfo by mutableStateOf<MovieInfo?>(null)
    var seriesInfo by mutableStateOf<SeriesInfo?>(null)
    /** Saison affichée sur la fiche : celle du dernier épisode regardé, conservée au retour du lecteur. */
    var detailSeason by mutableStateOf(0)
    /** Défilement de la fiche, conservé au retour du lecteur (l'épisode quitté est encore visible et reprend le focus). */
    var detailListState = androidx.compose.foundation.lazy.LazyListState()
        private set
    var detailLoading by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)
    private var detailJob: Job? = null

    // ---------- Programme TV ----------
    var epgChannel by mutableStateOf<MediaItem?>(null)
    var epgEntries by mutableStateOf<List<EpgEntry>?>(null)
    private val epgCache = HashMap<String, Pair<Long, List<EpgEntry>>>()
    private var epgJob: Job? = null

    // ---------- Mise à jour ----------
    var update by mutableStateOf<UpdateInfo?>(null)
    var updateProgress by mutableStateOf<Float?>(null)
    var updateMessage by mutableStateOf<String?>(null)
    /** Mise à jour impossible depuis l'app (signature différente) : explication persistante. */
    var updateBlocked by mutableStateOf<String?>(null)

    /** Mise à jour trouvée au démarrage : proposée avant de charger la playlist. */
    var updatePrompt by mutableStateOf(false)
    /** Dernier plantage enregistré (texte), affiché dans les Réglages. */
    var lastCrash by mutableStateOf<String?>(null)
    private var started = false

    /**
     * Démarrage : la connexion et le chargement de la playlist partent tout de suite ; la recherche de
     * mise à jour tourne en parallèle (elle n'ajoute plus son délai, jusqu'à 8 s, avant l'accueil).
     * Si une mise à jour existe, elle est proposée par-dessus, avant d'entrer dans l'app.
     */
    fun startup() {
        if (started) return
        started = true
        val crashFile = java.io.File(getApplication<Application>().filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            lastCrash = runCatching { crashFile.readText() }.getOrNull()
            crashFile.delete()
            updateMessage = "BibiTV s'est fermé de façon inattendue la dernière fois (détails dans Réglages)."
        }
        if (!autoLogin()) busy = null
        viewModelScope.launch {
            update = kotlinx.coroutines.withTimeoutOrNull(8000) { runCatching { UpdateManager.check() }.getOrNull() }
            if (update != null) updatePrompt = true
        }
    }

    fun skipUpdate() {
        updatePrompt = false
        if (client == null && busy == null) autoLogin()
    }

    // =================== Connexion ===================

    /** Connexion avec les identifiants enregistrés ; faux s'il n'y en a pas. */
    fun autoLogin(): Boolean {
        val pwd = SettingsStore.password
        if (SettingsStore.serverUrl.isBlank() || SettingsStore.username.isBlank() || pwd == null) return false
        login(SettingsStore.serverUrl, SettingsStore.username, pwd)
        return true
    }

    fun login(url: String, user: String, pwd: String) {
        if (url.isBlank() || user.isBlank() || pwd.isEmpty()) {
            loginError = "Merci de remplir les trois champs."
            return
        }
        loginError = null
        busy = "Connexion…"
        viewModelScope.launch {
            try {
                val c = XtreamClient(url, user, pwd)
                val account = c.authenticate()
                SettingsStore.serverUrl = c.baseUrl
                SettingsStore.username = c.username
                SettingsStore.password = c.password
                accountExpiry = account.expiration
                client = c
                tab = Tab.Home
                busy = null
                buildHome()
            } catch (e: Exception) {
                busy = null
                loginError = e.message ?: "Connexion impossible."
            }
        }
    }

    fun logout() {
        player = null
        detail = null
        client = null
        items.clear(); categories.clear(); byKey.clear(); byAdded.clear(); loadErrors.clear()
        home = null
        homeRows = null
        SettingsStore.password = null
        showSettings = false
    }

    private var tabJob: Job? = null

    /** Changement d'onglet au focus, après un court délai (évite de charger chaque onglet survolé). */
    fun focusTab(t: Tab) {
        tabJob?.cancel()
        if (t == tab) return
        tabJob = viewModelScope.launch { delay(400); tab = t }
    }

    // =================== Catalogue ===================

    /** Chargements en cours : un onglet survolé pendant la préparation de l'accueil ne relance pas le téléchargement. */
    private val loading = HashMap<Section, kotlinx.coroutines.Deferred<Boolean>>()

    suspend fun ensureLoaded(section: Section): Boolean {
        if (items.containsKey(section)) return true
        val c = client ?: return false
        val job = loading[section]?.takeIf { it.isActive } ?: viewModelScope.async {
            try {
                val fetched = fetchSection(c, section)
                if (c != client) return@async false
                commitSection(section, fetched)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                loadErrors[section] = "Catalogue trop volumineux pour la mémoire de cet appareil."
                false
            } catch (e: Exception) {
                loadErrors[section] = e.message ?: "Erreur de chargement"
                false
            } finally {
                loading.remove(section)
            }
        }.also { loading[section] = it }
        return job.await()
    }

    /** Télécharge et regroupe une section, sans toucher à ce qui est affiché. */
    private class Fetched(val categories: List<Category>, val items: List<MediaItem>, val byAdded: List<MediaItem>)

    private suspend fun fetchSection(c: XtreamClient, section: Section): Fetched {
        val cats = viewModelScope.async { c.categories(section) }
        val list = viewModelScope.async { c.items(section) }
        val catList = cats.await()
        val raw = list.await()
        val grouped = withContext(Dispatchers.Default) {
            // Catégories françaises : leur titre est préféré quand une œuvre existe en plusieurs langues.
            val french = catList.filter {
                Regex("""(^|[^A-Za-z])(FR|VF|VFF|VFQ|FRENCH|FRANCE|FRANÇAIS)([^A-Za-z]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
            }.map { it.id }.toSet()
            val g = TitleGrouper.group(raw, merge = section != Section.Live) { it.categoryId in french }
            if (section == Section.Live) ChannelBrands.tag(g)
            g
        }
        val sorted = if (section == Section.Live) grouped else withContext(Dispatchers.Default) { grouped.sortedByDescending { it.added } }
        return Fetched(catList, grouped, sorted)
    }

    private fun commitSection(section: Section, f: Fetched) {
        val catList = f.categories
        val grouped = f.items
        byKey.values.removeAll { it.kind == section }
        grouped.forEach { byKey[it.key] = it }
        categories[section] = catList
        byAdded[section] = f.byAdded
        items[section] = grouped
        if (section == Section.Live) ProfileStore.seedLiveFavorites(grouped)
        loadErrors.remove(section)
        catalogLoadedAt = System.currentTimeMillis()
    }

    fun loadSection(section: Section) {
        if (items.containsKey(section)) return
        viewModelScope.launch {
            busy = when (section) {
                Section.Live -> "Chargement des chaînes…"
                Section.Movies -> "Chargement des films…"
                Section.Series -> "Chargement des séries…"
            }
            ensureLoaded(section)
            busy = null
        }
    }

    /** Liste des catégories affichées : Ma liste, Tout, Marques (TV), puis catégories non vides. */
    fun categoryList(section: Section): List<Category> {
        val all = items[section] ?: return emptyList()
        val counts = HashMap<String, Int>()
        all.forEach { i -> i.categoryIds.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val list = mutableListOf<Category>()
        val fav = all.count { ProfileStore.isFavorite(it) }
        if (fav > 0) list += Category(Category.FAVORITES_ID, if (section == Section.Live) "Favoris" else "Ma liste", fav)
        list += Category(Category.ALL_ID, if (section == Section.Live) "Toutes les chaînes" else "Récemment ajoutés", all.size)
        if (section == Section.Live) {
            val brands = ChannelBrands.names.mapNotNull { b -> counts[ChannelBrands.PREFIX + b]?.let { Category(ChannelBrands.PREFIX + b, b, it) } }
            if (brands.isNotEmpty()) {
                list += Category("hdr:brands", "MARQUES", isHeader = true)
                list += brands
                list += Category("hdr:cats", "CATÉGORIES", isHeader = true)
            }
        }
        categories[section]?.forEach { c -> counts[c.id]?.let { list += c.copy(count = it) } }
        return list
    }

    fun filtered(section: Section, categoryId: String, query: String, sort: SortMode = SortMode.Default): List<MediaItem> {
        val all = items[section] ?: return emptyList()
        val q = query.trim()
        var result: List<MediaItem> = when {
            q.isNotEmpty() -> {
                val nq = TitleGrouper.normalize(q)
                all.filter { TitleGrouper.normalize(it.title).contains(nq) }
            }
            categoryId == Category.ALL_ID -> if (section == Section.Live) all else byAdded[section] ?: all.sortedByDescending { it.added }
            categoryId == Category.FAVORITES_ID -> all.filter { ProfileStore.isFavorite(it) }
            else -> all.filter { categoryId in it.categoryIds }
        }
        if (ProfileStore.profile.hideWatched && q.isEmpty() && section != Section.Live)
            result = result.filter { !ProfileStore.isWatched(it.key) }
        return result.sortedByMode(sort)
    }

    // =================== Accueil ===================

    fun buildHome(force: Boolean = false, silent: Boolean = false) {
        if (home != null && !force) return
        viewModelScope.launch {
            if (!silent) busy = "Préparation de l'accueil…"
            val errors = mutableListOf<String>()
            for (s in listOf(Section.Movies, Section.Series, Section.Live)) {
                if (!ensureLoaded(s)) loadErrors[s]?.let { errors += it }
            }
            busy = null
            val movies = items[Section.Movies] ?: emptyList()
            val series = items[Section.Series] ?: emptyList()
            val live = items[Section.Live] ?: emptyList()
            val (topMovies, mTmdb) = TopService.top(Section.Movies, movies)
            val (topSeries, sTmdb) = TopService.top(Section.Series, series)
            val liveCats = (categories[Section.Live] ?: emptyList()).associate { it.id to it.name }
            val topChannels = ChannelBrands.topChannels(live, liveCats).mapIndexed { i, c -> RankedItem(i + 1, c) }
            val hero = topMovies.firstOrNull()?.item
            home = HomeData(hero, topMovies, mTmdb, topSeries, sTmdb, topChannels, errors.distinct())
            heroInfo = null
            processLink()
            // Chaîne « BibiTV » sur l'accueil de la TV : Top 10 films puis suggestions
            val ctx = getApplication<Application>()
            val channelItems = topMovies.map { it.item } + Recommendations.suggest(movies) + topSeries.map { it.item }
            launch(Dispatchers.IO) {
                com.bibitv.tv.data.TvHome.publishChannel(ctx, channelItems)
                com.bibitv.tv.data.HomeProvider.saveSuggestions(ctx, channelItems)
                com.bibitv.tv.data.HomeProvider.saveTop(ctx, topMovies, topSeries)
            }
            if (hero != null) {
                try {
                    heroInfo = client?.movieInfo(hero.variants.firstOrNull()?.id ?: hero.id)
                } catch (_: Exception) {
                }
            }
        }
    }

    // =================== Synchronisation des playlists ===================

    var syncing by mutableStateOf(false)
        private set

    /** Au-delà de ce délai, le catalogue est rechargé discrètement quand on revient sur l'app. */
    private val autoSyncAfterMs = 12 * 3600_000L

    /**
     * Recharge chaînes, films et séries depuis le serveur, puis reconstruit l'accueil.
     * Le catalogue affiché reste utilisable pendant le chargement ; il est remplacé à la fin
     * (positions dans les listes et fiche ouverte conservées).
     */
    fun syncPlaylists(silent: Boolean = false) {
        val c = client ?: return
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            if (!silent) busy = "Synchronisation des playlists…"
            val errors = mutableListOf<String>()
            try {
                for (s in listOf(Section.Movies, Section.Series, Section.Live)) {
                    try {
                        val fetched = fetchSection(c, s)
                        if (c != client) return@launch
                        commitSection(s, fetched)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        errors += "${sectionName(s)} : mémoire insuffisante"
                    } catch (e: Exception) {
                        errors += "${sectionName(s)} : ${e.message ?: "erreur"}"
                    }
                }
                epgCache.clear()
            } finally {
                busy = null
                syncing = false
            }
            buildHome(force = true, silent = true)
            if (errors.isNotEmpty()) updateMessage = "Synchronisation incomplète — ${errors.joinToString(" · ")}"
            else if (!silent) updateMessage = "Playlists synchronisées : ${count(Section.Movies)} films · ${count(Section.Series)} séries · ${count(Section.Live)} chaînes"
        }
    }

    private fun count(s: Section) = items[s]?.size ?: 0
    private fun sectionName(s: Section) = when (s) {
        Section.Live -> "Chaînes"
        Section.Movies -> "Films"
        Section.Series -> "Séries"
    }

    /** Retour sur l'app (TV rallumée, retour depuis une autre app) : synchro discrète si le catalogue date. */
    fun onAppResumed() {
        if (client == null || home == null || player != null || syncing) return
        if (catalogLoadedAt > 0 && System.currentTimeMillis() - catalogLoadedAt > autoSyncAfterMs) syncPlaylists(silent = true)
    }

    /** « il y a 3 h » : ancienneté du catalogue, affichée dans les Réglages. */
    fun catalogAge(): String {
        if (catalogLoadedAt <= 0) return "jamais"
        val min = (System.currentTimeMillis() - catalogLoadedAt) / 60_000
        return when {
            min < 1 -> "à l'instant"
            min < 60 -> "il y a $min min"
            min < 48 * 60 -> "il y a ${min / 60} h"
            else -> "il y a ${min / 1440} jours"
        }
    }

    fun categoryNames(section: Section) = (categories[section] ?: emptyList()).associate { it.id to it.name }

    // =================== Ouverture / lecture ===================

    fun open(item: MediaItem, context: List<MediaItem>? = null) {
        if (item.kind == Section.Live) playChannel(item, context ?: listOf(item)) else showDetail(item)
    }

    fun playChannel(item: MediaItem, channels: List<MediaItem>) {
        val c = client ?: return
        val list = if (item in channels) channels else listOf(item)
        val playlist = list.map { ch ->
            PlaylistEntry(ch.title, "En direct", c.liveUrl(ch.id), channel = ch,
                history = HistoryEntry(ch.key, Section.Live, ch.id, ch.title, ch.imageUrl))
        }
        player = PlayerRequest(playlist, list.indexOf(item).coerceAtLeast(0), isLive = true)
    }

    fun playMovie(group: MediaItem, v: MediaItem, ext: String?, subtitle: String) {
        val c = client ?: return
        val previous = ProfileStore.profile.history.firstOrNull { it.key == group.key && it.itemId == v.id }
        player = PlayerRequest(
            listOf(
                PlaylistEntry(
                    group.title, subtitle, c.movieUrl(v.id, ext),
                    startMs = if (previous?.hasProgress == true) previous.positionMs else 0,
                    history = HistoryEntry(group.key, Section.Movies, v.id, group.title, group.imageUrl, ext),
                ),
            ),
            0, isLive = false,
        )
    }

    fun playSeries(series: MediaItem, seriesVariantId: String, info: SeriesInfo, episode: Episode) {
        val c = client ?: return
        if (info == seriesInfo) detailSeason = info.seasons.indexOfFirst { it.number == episode.season }.coerceAtLeast(0)
        val previous = ProfileStore.profile.history.firstOrNull { it.key == series.key }
        val all = info.seasons.flatMap { it.episodes }
        val playlist = all.map { ep ->
            PlaylistEntry(
                series.title,
                "Saison ${ep.season}, épisode ${ep.number}" + if (ep.title.isBlank()) "" else "  ·  ${ep.title}",
                c.episodeUrl(ep.id, ep.extension),
                startMs = if (previous?.hasProgress == true && previous.episodeId == ep.id) previous.positionMs else 0,
                history = HistoryEntry(series.key, Section.Series, seriesVariantId, series.title, series.imageUrl,
                    ep.extension, ep.id, "S${ep.season} · É${ep.number}"),
            )
        }
        player = PlayerRequest(playlist, all.indexOf(episode).coerceAtLeast(0), isLive = false)
    }

    fun playFile(title: String, location: String) {
        player = PlayerRequest(listOf(PlaylistEntry(title, "Fichier téléchargé", location)), 0, isLive = false)
    }

    /** Reprend un élément de l'historique (film, épisode ou chaîne). */
    fun resume(h: HistoryEntry, siblings: List<HistoryEntry>) {
        val c = client ?: return
        when (h.kind) {
            Section.Live -> {
                val chans = siblings.filter { it.kind == Section.Live }.map { e ->
                    byKey[e.key] ?: MediaItem(Section.Live, e.itemId, e.title, e.imageUrl, "")
                }
                val target = chans.firstOrNull { it.id == h.itemId } ?: return
                playChannel(target, chans)
            }
            Section.Movies -> {
                val group = byKey[h.key] ?: MediaItem(Section.Movies, h.itemId, h.title, h.imageUrl, "").also { it.variants = listOf(it) }
                val v = group.variants.firstOrNull { it.id == h.itemId } ?: group.variants.firstOrNull() ?: group
                playMovie(group, v, h.extension ?: v.extension, v.tag)
            }
            Section.Series -> viewModelScope.launch {
                busy = "Reprise de la série…"
                try {
                    val info = c.seriesInfo(h.itemId)
                    val series = byKey[h.key] ?: MediaItem(Section.Series, h.itemId, h.title, h.imageUrl, "")
                    val all = info.seasons.flatMap { it.episodes }
                    var i = all.indexOfFirst { it.id == h.episodeId }
                    // Épisode terminé : on passe au suivant.
                    if (i >= 0 && !h.hasProgress && ProfileStore.isWatched(ProfileStore.episodeKey(all[i].id)) && i < all.size - 1) i++
                    if (all.isNotEmpty()) playSeries(series, h.itemId, info, all[i.coerceAtLeast(0)])
                } catch (e: Exception) {
                    updateMessage = "Impossible de reprendre : ${e.message}"
                } finally {
                    busy = null
                }
            }
        }
    }

    /** Appelé par le lecteur quand une entrée démarre. */
    fun onEntryStarted(e: PlaylistEntry) {
        e.history?.let {
            val entry = it.copy()
            ProfileStore.touch(entry)
            publishWatchNext(entry, finished = false)
        }
    }

    private fun publishWatchNext(h: HistoryEntry, finished: Boolean) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { com.bibitv.tv.data.TvHome.publishWatchNext(ctx, h, finished) }
    }

    // =================== Liens directs (accueil de la TV) ===================

    private var pendingLink: android.net.Uri? = null

    /** bibitv://fiche?key=… ouvre une fiche, bibitv://reprendre?key=… reprend la lecture. */
    fun handleLink(uri: android.net.Uri) {
        if (uri.scheme != com.bibitv.tv.data.TvHome.SCHEME) return
        pendingLink = uri
        if (home != null) processLink()
    }

    private fun processLink() {
        val uri = pendingLink ?: return
        pendingLink = null
        val key = uri.getQueryParameter("key")
        when (uri.host) {
            "reprendre" -> {
                val h = ProfileStore.profile.history.firstOrNull { it.key == key }
                if (h != null) resume(h, ProfileStore.profile.history.toList())
                else byKey[key]?.let { open(it) }
            }
            "fiche" -> byKey[key]?.let { player = null; open(it) }
            else -> { player = null; detail = null; tab = Tab.Home }
        }
    }

    /** Progression d'une entrée (VOD) : historique + « déjà vu » au-delà de 92 %. */
    fun onProgress(e: PlaylistEntry, position: Long, length: Long) {
        val h = e.history ?: return
        if (length <= 0 || h.kind == Section.Live) return
        val finished = position >= length * 0.92
        val entry = h.copy(positionMs = if (finished) 0 else position, lengthMs = length)
        if (finished) {
            val watchKey = h.episodeId?.let { ProfileStore.episodeKey(it) } ?: h.key
            ProfileStore.markWatched(watchKey)
        }
        ProfileStore.touch(entry)
        publishWatchNext(entry, finished)
    }

    // =================== Fiche détail ===================

    fun showDetail(item: MediaItem) {
        detailListState = androidx.compose.foundation.lazy.LazyListState()
        detail = item
        selectVariant(item.variants.firstOrNull() ?: item)
    }

    fun closeDetail() {
        detailJob?.cancel()
        detail = null
        variant = null
        movieInfo = null
        seriesInfo = null
        // La tuile qui avait ouvert la fiche reprend le focus.
        FocusMemory.restore()
    }

    /** Fermeture du lecteur : l'élément qui l'avait lancé (affiche, chaîne, épisode) reprend le focus. */
    fun closePlayer() {
        player = null
        FocusMemory.restore()
    }

    fun versionLabels(item: MediaItem): List<String> {
        val names = categoryNames(item.kind)
        val labels = item.variants.mapIndexed { i, v ->
            v.tag.ifEmpty { names[v.categoryId] ?: "Version ${i + 1}" }
        }.toMutableList()
        labels.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { dup ->
            var k = 0
            for (i in labels.indices) if (labels[i] == dup) labels[i] = "$dup (${++k})"
        }
        return labels
    }

    fun selectVariant(v: MediaItem) {
        val c = client ?: return
        detailJob?.cancel()
        variant = v
        movieInfo = null
        seriesInfo = null
        detailError = null
        detailLoading = true
        detailJob = viewModelScope.launch {
            try {
                if (v.kind == Section.Movies) movieInfo = c.movieInfo(v.id)
                else {
                    val info = c.seriesInfo(v.id)
                    val h = detail?.let { d -> ProfileStore.profile.history.firstOrNull { it.key == d.key } }
                    detailSeason = info.seasons.indexOfFirst { s -> s.episodes.any { it.id == h?.episodeId } }.coerceAtLeast(0)
                    seriesInfo = info
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                detailError = "Informations indisponibles (${e.message})."
            } finally {
                detailLoading = false
            }
        }
    }

    fun playDetail() {
        val item = detail ?: return
        val v = variant ?: return
        if (v.kind == Section.Movies) {
            playMovie(item, v, v.extension ?: movieInfo?.extension, v.tag)
        } else {
            val info = seriesInfo ?: return
            val all = info.seasons.flatMap { it.episodes }
            val h = ProfileStore.profile.history.firstOrNull { it.key == item.key }
            val ep = all.firstOrNull { it.id == h?.episodeId }
                ?: all.firstOrNull { !ProfileStore.isWatched(ProfileStore.episodeKey(it.id)) }
                ?: all.firstOrNull() ?: return
            playSeries(item, v.id, info, ep)
        }
    }

    fun playEpisode(ep: Episode) {
        val item = detail ?: return
        val v = variant ?: return
        val info = seriesInfo ?: return
        playSeries(item, v.id, info, ep)
    }

    // =================== Téléchargements ===================

    fun downloadMovie() {
        val c = client ?: return
        val item = detail ?: return
        val v = variant ?: return
        val ext = v.extension ?: movieInfo?.extension ?: "mp4"
        val name = DownloadManager.safeName(item.title + (item.year?.let { " ($it)" } ?: "") + (if (v.tag.isNotEmpty()) " ${v.tag}" else ""))
        requestNotificationPermission?.invoke()
        DownloadManager.enqueue(name, c.movieUrl(v.id, ext), "Films/$name.$ext")
        updateMessage = "Ajouté aux téléchargements : $name"
    }

    fun downloadEpisode(ep: Episode, notify: Boolean = true) {
        val c = client ?: return
        val item = detail ?: return
        val series = DownloadManager.safeName(item.title)
        val se = "S%02dE%02d".format(ep.season, ep.number)
        val name = DownloadManager.safeName(
            "${item.title} $se" + if (ep.title.isBlank() || ep.title.contains(item.title)) "" else " - ${ep.title}",
        )
        val ext = ep.extension.ifBlank { "mp4" }
        val seasonDir = "Saison " + "%02d".format(ep.season)
        if (notify) requestNotificationPermission?.invoke()
        DownloadManager.enqueue(name, c.episodeUrl(ep.id, ext), "Séries/$series/$seasonDir/$name.$ext")
        if (notify) updateMessage = "Ajouté aux téléchargements : $name"
    }

    fun downloadSeason(eps: List<Episode>) {
        requestNotificationPermission?.invoke()
        eps.forEach { downloadEpisode(it, notify = false) }
        updateMessage = "${eps.size} épisodes ajoutés aux téléchargements"
    }

    // =================== Profil ===================

    fun toggleWatched(item: MediaItem) = ProfileStore.setWatched(item.key, !ProfileStore.isWatched(item.key))
    fun rate(item: MediaItem, rating: Int) =
        ProfileStore.setRating(item.key, if (ProfileStore.ratingOf(item.key) == rating) 0 else rating)

    fun itemMenu(item: MediaItem) {
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            (if (ProfileStore.isFavorite(item)) (if (item.kind == Section.Live) "Retirer des Favoris" else "Retirer de Ma liste") else (if (item.kind == Section.Live) "Ajouter aux Favoris" else "Ajouter à Ma liste")) to { ProfileStore.toggleFavorite(item) },
        )
        if (item.kind != Section.Live) {
            val watched = ProfileStore.isWatched(item.key)
            actions += (if (watched) "Marquer comme non vu" else "Marquer comme vu") to { toggleWatched(item) }
            val r = ProfileStore.ratingOf(item.key)
            actions += (if (r > 0) "Retirer « J'aime »" else "J'aime") to { rate(item, 1) }
            actions += (if (r < 0) "Retirer « Je n'aime pas »" else "Je n'aime pas") to { rate(item, -1) }
        }
        menu = MenuRequest(item.title, actions)
    }

    fun historyMenu(h: HistoryEntry) {
        menu = MenuRequest(h.title, listOf("Retirer de l'historique" to {
            ProfileStore.removeHistory(h.key)
            val ctx = getApplication<Application>()
            viewModelScope.launch(Dispatchers.IO) { com.bibitv.tv.data.TvHome.removeWatchNext(ctx, h.key) }
            Unit
        }))
    }

    // =================== Programme TV ===================

    fun focusChannel(ch: MediaItem) {
        if (epgChannel == ch) return
        epgChannel = ch
        val cached = epgCache[ch.id]
        if (cached != null && System.currentTimeMillis() - cached.first < 5 * 60_000) {
            epgEntries = cached.second
            return
        }
        epgEntries = null
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            delay(250) // évite une requête par chaîne survolée en passant
            val list = epg(ch)
            if (epgChannel == ch) epgEntries = list
        }
    }

    fun clearEpg() {
        epgJob?.cancel()
        epgChannel = null
        epgEntries = null
    }

    suspend fun epg(ch: MediaItem): List<EpgEntry> {
        epgCache[ch.id]?.let { if (System.currentTimeMillis() - it.first < 5 * 60_000) return it.second.filter { e -> e.end > System.currentTimeMillis() } }
        return try {
            val list = client?.shortEpg(ch.id) ?: emptyList()
            epgCache[ch.id] = System.currentTimeMillis() to list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Texte affiché sous le nom de la chaîne dans le lecteur. */
    suspend fun nowPlaying(ch: MediaItem): String? =
        epg(ch).firstOrNull { it.isNow }?.let { "En direct  ·  ${it.timeRange}  ·  ${it.title}" }

    // =================== Mise à jour ===================

    suspend fun checkUpdate(silent: Boolean) {
        try {
            if (!silent) updateMessage = "Recherche de mise à jour…"
            update = UpdateManager.check()
            if (!silent && update == null) updateMessage = "BibiTV est à jour (v${UpdateManager.currentVersion})"
            else if (!silent) updateMessage = null
        } catch (e: Exception) {
            if (!silent) updateMessage = "Vérification impossible : ${e.message}"
        }
    }

    fun startUpdate() {
        val u = update ?: return
        val ctx = getApplication<Application>()
        if (!UpdateManager.canInstall(ctx)) {
            updateMessage = "Autorisez BibiTV à installer des applications, puis relancez la mise à jour."
            UpdateManager.openInstallPermission(ctx)
            return
        }
        viewModelScope.launch {
            try {
                updateProgress = 0f
                val apk = UpdateManager.download(ctx, u) { updateProgress = it }
                updateProgress = null
                if (UpdateManager.signatureMismatch(ctx, apk)) {
                    updateBlocked = "Cette BibiTV a été installée avec une ancienne signature : Android refusera la mise à jour. " +
                        "Désinstallez BibiTV, puis réinstallez-la avec Downloader : github.com/Bilou691/BibiTv/releases/download/android/bibitv.apk"
                    return@launch
                }
                // Android ferme BibiTV pendant l'installation, puis la nouvelle version se lance.
                updateMessage = "Installation… BibiTV va se fermer puis redémarrer à jour."
                delay(600)
                UpdateManager.install(ctx, apk)
            } catch (e: Exception) {
                updateProgress = null
                updateMessage = "La mise à jour a échoué : ${e.message}"
            }
        }
    }

    fun yearText(s: String?) = yearOf(s)
}
