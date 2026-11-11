package com.bibitv.tv.ui


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

import com.bibitv.tv.data.Category
import com.bibitv.tv.data.DownloadManager
import com.bibitv.tv.data.EpgEntry
import com.bibitv.tv.data.MediaItem
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.Recommendations
import com.bibitv.tv.data.Section
import com.bibitv.tv.data.SortMode

@Composable
fun MainScreen(vm: AppViewModel) {
    // Retour depuis un onglet → Accueil ; depuis l'Accueil → quitter (comportement système)
    BackHandler(enabled = vm.tab != Tab.Home && vm.detail == null && vm.player == null) { vm.tab = Tab.Home }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15151C), C.Bg), endY = 900f))) {
        TopBar(vm)
        Box(Modifier.fillMaxSize()) {
            when (vm.tab) {
                Tab.Home -> HomeScreen(vm)
                Tab.Live -> CatalogScreen(vm, Section.Live)
                Tab.Movies -> CatalogScreen(vm, Section.Movies)
                Tab.Series -> CatalogScreen(vm, Section.Series)
            }
            EpgCard(vm, Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 30.dp))
            UpdateBanner(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
        }
    }
}

@Composable
private fun TopBar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(img("logo")), null, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)))
        Text("BibiTV", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        Spacer(Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xB31C1C1E)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tab.entries.forEach { t ->
                PillButton(t.label, { vm.tab = t }, selected = vm.tab == t, onFocus = { vm.focusTab(t) })
            }
        }
        Spacer(Modifier.weight(1f))
        val active = DownloadManager.activeCount
        PillButton(if (active > 0) "Téléchargements ($active)" else "Téléchargements", { vm.showDownloads = true })
        Spacer(Modifier.width(10.dp))
        PillButton("Réglages", { vm.showSettings = true })
    }
}

// =================== Accueil ===================

@Composable
internal fun HomeScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.buildHome() }
    val home = vm.home ?: return
    val rev = ProfileStore.revision.intValue
    val movies = vm.items[Section.Movies] ?: emptyList()
    val series = vm.items[Section.Series] ?: emptyList()
    val hide = ProfileStore.profile.hideWatched
    val history = remember(rev) { ProfileStore.profile.history.toList() }
    val forMovies = remember(rev, movies) { Recommendations.suggest(movies) }
    val forSeries = remember(rev, series) { Recommendations.suggest(series) }
    val myList = remember(rev, movies, series) { (movies + series).filter { ProfileStore.isFavorite(it) } }
    val recentMovies = remember(rev, movies) { movies.filter { !hide || !ProfileStore.isWatched(it.key) }.sortedByDescending { it.added }.take(24) }
    val recentSeries = remember(rev, series) { series.filter { !hide || !ProfileStore.isWatched(it.key) }.sortedByDescending { it.added }.take(24) }
    val live = vm.items[Section.Live] ?: emptyList()
    val topChannelItems = home.topChannels.map { it.item }
    val gutter = LocalDims.current.gutter

    LazyColumn(state = vm.homeState, contentPadding = PaddingValues(bottom = 60.dp)) {
        item { Hero(vm, home.hero) }
        item {
            val vod = history.filter { it.kind != Section.Live }.take(20)
            ShelfRow("Reprendre", "Films et séries regardés récemment", vod, key = { it.key }) { h ->
                HistoryCard(h, { vm.resume(h, vod) }, { vm.historyMenu(h) })
            }
        }
        item {
            ShelfRow("Top 10 des films de la semaine",
                if (home.moviesFromTmdb) "Tendances de la semaine (TMDB), disponibles dans votre catalogue" else "Nouveautés de la semaine les mieux notées",
                home.topMovies, key = { it.item.key }) { r -> RankedPoster(r, { vm.open(r.item) }, { vm.itemMenu(r.item) }) }
        }
        item {
            ShelfRow("Top 10 des séries de la semaine",
                if (home.seriesFromTmdb) "Tendances de la semaine (TMDB), disponibles dans votre catalogue" else "Nouveautés de la semaine les mieux notées",
                home.topSeries, key = { it.item.key }) { r -> RankedPoster(r, { vm.open(r.item) }, { vm.itemMenu(r.item) }) }
        }
        item {
            if (forMovies.isEmpty() && forSeries.isEmpty()) {
                Row(
                    Modifier.padding(start = gutter, end = gutter, top = 30.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0x330A84FF), Color(0x33BF5AF2)))).padding(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Pour toi", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Dites-nous ce que vous aimez : BibiTV vous proposera des films et des séries qui vous ressemblent.",
                            color = Color(0xFFD1D1D6), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    PillButton("Personnaliser", { vm.showPersonalize = true }, primary = true)
                }
            }
        }
        item {
            val tastes = Recommendations.topTastes(movies, vm.categoryNames(Section.Movies))
            ShelfRow("Pour toi · Films", if (tastes.isEmpty()) "D'après vos goûts" else "Parce que vous aimez : ${tastes.joinToString(", ")}",
                forMovies, key = { it.key }, action = { PillButton("Personnaliser", { vm.showPersonalize = true }) }) { m ->
                PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) })
            }
        }
        item {
            val tastes = Recommendations.topTastes(series, vm.categoryNames(Section.Series))
            ShelfRow("Pour toi · Séries", if (tastes.isEmpty()) "D'après vos goûts" else "Parce que vous aimez : ${tastes.joinToString(", ")}",
                forSeries, key = { it.key }, action = { PillButton("Personnaliser", { vm.showPersonalize = true }) }) { m ->
                PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) })
            }
        }
        item {
            ShelfRow("Top 10 des chaînes TV en France", "Les chaînes les plus regardées en France (audiences Médiamétrie)",
                home.topChannels, key = { it.item.key }) { r ->
                RankedChannel(r, { vm.playChannel(r.item, topChannelItems) }, { vm.focusChannel(r.item) })
            }
        }
        item {
            val chans = history.filter { it.kind == Section.Live }.take(15)
            ShelfRow("Chaînes regardées récemment", "Pour zapper rapidement", chans) { h ->
                val ch = live.firstOrNull { it.id == h.itemId }
                ChannelCard(h.title, h.imageUrl, { vm.resume(h, chans) }, { vm.historyMenu(h) }, onFocus = { ch?.let { vm.focusChannel(it) } })
            }
        }
        item {
            ShelfRow("Ma liste", if (myList.size == 1) "1 titre" else "${myList.size} titres", myList, key = { it.key }) { m ->
                PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) })
            }
        }
        item {
            ShelfRow("Nouveaux films", "Derniers ajouts au catalogue", recentMovies, key = { it.key }) { m ->
                PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) })
            }
        }
        item {
            ShelfRow("Nouvelles séries", "Dernières séries ajoutées ou mises à jour", recentSeries, key = { it.key }) { m ->
                PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) })
            }
        }
        if (home.errors.isNotEmpty()) item {
            Text("Une partie du catalogue n'a pas pu être chargée : ${home.errors.joinToString(" / ")}", color = C.Text2,
                fontSize = 15.sp, modifier = Modifier.padding(gutter))
        }
    }
}

@Composable
private fun Hero(vm: AppViewModel, item: MediaItem?) {
    item ?: return
    if (LocalDims.current.compact) return CompactHero(vm, item)
    val info = vm.heroInfo
    Box(Modifier.padding(horizontal = LocalDims.current.gutter).fillMaxWidth().height(380.dp).clip(RoundedCornerShape(24.dp)).background(C.Surface)) {
        AsyncImage(model = info?.backdrop ?: item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF00A0A0C), 0.45f to Color(0x990A0A0C), 0.85f to Color.Transparent)))
        Column(Modifier.align(Alignment.BottomStart).padding(40.dp).widthIn(max = 620.dp)) {
            Text("N°1 DES FILMS CETTE SEMAINE", color = C.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(item.title, color = C.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            val meta = listOfNotNull(vm.yearText(info?.releaseDate) ?: item.year, info?.genre, info?.duration, MediaItem.formatRating(info?.rating ?: item.rating))
            Text(meta.joinToString("   ·   "), color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            (info?.plot ?: item.plot)?.let {
                Text(it, color = Color(0xFFD1D1D6), fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("▶  Lecture", {
                    val v = item.variants.firstOrNull() ?: item
                    vm.playMovie(item, v, v.extension, v.tag)
                }, primary = true)
                PillButton("Plus d'infos", { vm.open(item) })
            }
        }
    }
}

/** À la une sur smartphone : image 16:9 pleine largeur, texte et boutons dessous. */
@Composable
private fun CompactHero(vm: AppViewModel, item: MediaItem) {
    val info = vm.heroInfo
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(model = info?.backdrop ?: item.imageUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to C.Surface)))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Text("N°1 DES FILMS CETTE SEMAINE", color = C.Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(item.title, color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            val meta = listOfNotNull(vm.yearText(info?.releaseDate) ?: item.year, info?.genre, MediaItem.formatRating(info?.rating ?: item.rating))
            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = C.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("▶  Lecture", {
                    val v = item.variants.firstOrNull() ?: item
                    vm.playMovie(item, v, v.extension, v.tag)
                }, Modifier.weight(1f), primary = true, fill = true)
                PillButton("Plus d'infos", { vm.open(item) }, Modifier.weight(1f), fill = true)
            }
        }
    }
}

// =================== Catalogue ===================

@Composable
internal fun CatalogScreen(vm: AppViewModel, section: Section) {
    LaunchedEffect(section) { vm.loadSection(section); vm.clearEpg() }
    val all = vm.items[section]
    val category = vm.catalogCategory[section] ?: Category.ALL_ID
    val query = vm.catalogQuery[section] ?: ""
    val rev = ProfileStore.revision.intValue
    val cats = remember(all, rev) { vm.categoryList(section) }
    val sort = vm.catalogSort[section] ?: SortMode.Default
    val list = remember(all, category, query, rev, sort) { vm.filtered(section, category, query, sort) }
    val gridState = vm.gridStates.getOrPut(section) { LazyGridState() }
    val scope = rememberCoroutineScope()
    fun select(id: String) {
        if (id == category && query.isEmpty()) return
        vm.catalogCategory[section] = id
        vm.catalogQuery[section] = ""
        scope.launch { gridState.scrollToItem(0) }
    }
    // Au focus, la catégorie n'est appliquée que si la sélection reste posée (défilement rapide fluide).
    var pending by remember(section) { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        select(id)
    }

    if (all == null) {
        vm.loadErrors[section]?.let { err ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Impossible de charger le contenu.\n$err", color = C.Text2, fontSize = 17.sp)
                Spacer(Modifier.height(18.dp))
                PillButton("Réessayer", { vm.loadErrors.remove(section); vm.loadSection(section) }, primary = true)
            }
        }
        return
    }

    Row(Modifier.fillMaxSize()) {
        // Catégories (et marques pour la TV) : la sélection suit le focus, comme sur Apple TV.
        LazyColumn(Modifier.width(290.dp).fillMaxHeight(), contentPadding = PaddingValues(start = 36.dp, end = 12.dp, bottom = 40.dp)) {
            items(cats, key = { it.id }) { c ->
                if (c.isHeader) {
                    Text(c.name, color = C.Text3, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp))
                } else {
                    val selected = c.id == category
                    Focusable({ select(c.id) }, Modifier.fillMaxWidth().padding(vertical = 2.dp), onFocus = { pending = c.id },
                        shape = RoundedCornerShape(10.dp), focusedScale = 1.03f, borderWidth = 0.dp) { f ->
                        Row(
                            Modifier.fillMaxWidth().background(if (f) Color.White else if (selected) C.Surface2 else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name, color = if (f) Color(0xFF0A0A0C) else if (selected) C.Text else C.Text2, fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Text(c.count.toString(), color = if (f) Color(0xFF3A3A3C) else C.Text3, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 40.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val title = if (query.isNotBlank()) "« ${query.trim()} »" else cats.firstOrNull { it.id == category }?.name ?: ""
                Text(title, color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text(if (list.size == 1) "1 élément" else "${list.size} éléments", color = C.Text2, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                Spacer(Modifier.weight(1f))
                // Bouton plutôt qu'un champ : le clavier ne s'ouvre que sur appui (pas au passage des flèches).
                var searchOpen by remember { mutableStateOf(false) }
                PillButton(if (query.isBlank()) "🔍  Rechercher" else "🔍  « ${query.trim()} »", { searchOpen = true })
                if (query.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    PillButton("✕", { vm.catalogQuery[section] = ""; scope.launch { gridState.scrollToItem(0) } })
                }
                if (searchOpen) SearchDialog(query, onDismiss = { searchOpen = false }) { q ->
                    searchOpen = false
                    vm.catalogQuery[section] = q
                    scope.launch { gridState.scrollToItem(0) }
                }
                Spacer(Modifier.width(12.dp))
                val sortModes = SortMode.modesFor(section)
                PillButton("⇅  Tri : ${sort.label}", {
                    vm.catalogSort[section] = sortModes[(sortModes.indexOf(sort) + 1) % sortModes.size]
                    scope.launch { gridState.scrollToItem(0) }
                }, selected = sort != SortMode.Default)
                if (section != Section.Live) {
                    Spacer(Modifier.width(12.dp))
                    val hide = ProfileStore.profile.hideWatched
                    PillButton(if (hide) "✓ Vus masqués" else "Masquer les vus", { ProfileStore.setHideWatched(!hide) }, selected = hide)
                }
            }

            if (list.isEmpty()) {
                Text(if (query.isNotBlank()) "Aucun résultat." else "Cette catégorie est vide.", color = C.Text2, fontSize = 17.sp,
                    modifier = Modifier.padding(40.dp))
            } else if (section == Section.Live) {
                LazyVerticalGrid(GridCells.Adaptive(210.dp), state = gridState, contentPadding = PaddingValues(start = 16.dp, end = 40.dp, top = 12.dp, bottom = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(list) { ch ->
                        ChannelCard(ch.title, ch.imageUrl, { vm.playChannel(ch, list) }, { vm.itemMenu(ch) }, onFocus = { vm.focusChannel(ch) }, width = 210.dp)
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(160.dp), state = gridState, contentPadding = PaddingValues(start = 16.dp, end = 40.dp, top = 12.dp, bottom = 60.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(list, key = { it.key }) { m -> PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) }, width = 160.dp) }
                }
            }
        }
    }
}

// =================== Programme TV ===================

@Composable
private fun EpgCard(vm: AppViewModel, modifier: Modifier) {
    val ch = vm.epgChannel ?: return
    if (vm.tab != Tab.Live && vm.tab != Tab.Home) return
    val entries = vm.epgEntries
    val now = entries?.firstOrNull { it.isNow }
    val next = entries?.firstOrNull { it.start >= (now?.end ?: System.currentTimeMillis()) && it != now }
    Row(modifier.width(460.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF51C1C1E)).padding(18.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
            if (ch.imageUrl != null) AsyncImage(ch.imageUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(ch.title.uppercase(), color = C.Text2, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                entries == null -> Text("Chargement du programme…", color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                now == null && next == null -> Text("Pas de programme disponible pour cette chaîne.", color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                else -> {
                    now?.let { NowBlock(it) }
                    next?.let {
                        Row(Modifier.padding(top = 12.dp)) {
                            Text("ENSUITE  ${EpgEntry.hhmm(it.start)}", color = C.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(it.title, color = Color(0xFFD1D1D6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowBlock(e: EpgEntry) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EN COURS", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(C.Red).padding(horizontal = 6.dp, vertical = 1.dp))
            val left = ((e.end - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
            val leftText = if (left >= 60) "${left / 60} h ${"%02d".format(left % 60)}" else "$left min"
            Text("${e.timeRange}  ·  encore $leftText", color = C.Text2, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
        Text(e.title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        ProgressLine(e.progress, Modifier.fillMaxWidth().padding(top = 10.dp))
        e.description?.let {
            Text(it, color = Color(0xFFAEAEB2), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// =================== Mise à jour ===================

@Composable
internal fun UpdateBanner(vm: AppViewModel, modifier: Modifier) {
    val u = vm.update ?: return
    var dismissed by remember(u.version) { mutableStateOf(false) }
    if (dismissed) return
    Row(
        modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xF21C1C1E))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("BibiTV ${u.version} est disponible", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val p = vm.updateProgress
            vm.updateBlocked?.let { Text(it, color = C.Yellow, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
            if (p != null) {
                Text("Téléchargement… ${(p * 100).toInt()} %", color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                ProgressLine(p, Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                Text("Vous avez la version ${com.bibitv.tv.data.UpdateManager.currentVersion}.", color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (vm.updateProgress == null) {
            PillButton("Plus tard", { dismissed = true })
            Spacer(Modifier.width(10.dp))
            PillButton("Mettre à jour", { vm.startUpdate() }, primary = true)
        }
    }
}

/** Fenêtre de recherche (fenêtre séparée : le focus ne peut pas s'échapper vers la grille). */
@Composable
private fun SearchDialog(initial: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(26.dp)) {
            Text("Rechercher", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                text, { text = it }, placeholder = { Text("Titre d'un film, d'une série, d'une chaîne…") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().focusRequester(field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch(text.trim()) }, onDone = { onSearch(text.trim()) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedContainerColor = C.Surface2, unfocusedContainerColor = C.Surface2,
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color.Transparent, cursorColor = Color.White,
                    focusedPlaceholderColor = C.Text2, unfocusedPlaceholderColor = C.Text2,
                ),
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Rechercher", { onSearch(text.trim()) }, primary = true)
                PillButton("Effacer", { onSearch("") })
                PillButton("Annuler", onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}
