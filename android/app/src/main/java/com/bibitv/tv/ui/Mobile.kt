package com.bibitv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bibitv.tv.R
import com.bibitv.tv.data.Category
import com.bibitv.tv.data.DownloadManager
import com.bibitv.tv.data.MediaItem
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.Section
import com.bibitv.tv.data.TitleGrouper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Interface smartphone / tablette (tactile) : barre d'onglets en bas, écrans en une colonne.
 * Les données, le lecteur et la plupart des composants sont partagés avec la version TV.
 */

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = C.Text, unfocusedTextColor = C.Text,
        focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
        focusedBorderColor = Color.White, unfocusedBorderColor = Color.Transparent,
        cursorColor = Color.White, focusedPlaceholderColor = C.Text2, unfocusedPlaceholderColor = C.Text2,
        focusedLeadingIconColor = C.Text2, unfocusedLeadingIconColor = C.Text2,
        focusedTrailingIconColor = C.Text2, unfocusedTrailingIconColor = C.Text2,
    )

// =================== Structure ===================

@Composable
fun MobileMainScreen(vm: AppViewModel) {
    // Retour : Recherche ou onglet → Accueil ; depuis l'Accueil → quitter (comportement système)
    BackHandler(enabled = vm.mobileSearch || vm.tab != Tab.Home) {
        if (vm.mobileSearch) vm.mobileSearch = false else vm.tab = Tab.Home
    }
    val compact = LocalDims.current.compact

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        MobileTopBar(vm)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.mobileSearch -> MobileSearch(vm)
                vm.tab == Tab.Home -> HomeScreen(vm)
                else -> {
                    val section = when (vm.tab) {
                        Tab.Live -> Section.Live
                        Tab.Movies -> Section.Movies
                        else -> Section.Series
                    }
                    // Tablette : liste des catégories sur le côté, comme à la TV ; smartphone : catégories en haut.
                    if (compact) MobileCatalog(vm, section) else CatalogScreen(vm, section)
                }
            }
            UpdateBanner(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        }
        MobileNavBar(vm)
    }
}

@Composable
private fun MobileTopBar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
        Text("BibiTV", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        Spacer(Modifier.weight(1f))
        val active = DownloadManager.activeCount
        Box(contentAlignment = Alignment.TopEnd) {
            IconButton({ vm.showDownloads = true }) { Text("⬇", color = C.Text, fontSize = 20.sp) }
            if (active > 0) Text(
                active.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp, end = 6.dp).clip(RoundedCornerShape(8.dp)).background(C.Red).padding(horizontal = 5.dp),
            )
        }
        IconButton({ vm.showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Réglages", tint = C.Text) }
    }
}

@Composable
private fun MobileNavBar(vm: AppViewModel) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = Color(0xFF0A0A0C), selectedTextColor = C.Text, indicatorColor = Color.White,
        unselectedIconColor = C.Text2, unselectedTextColor = C.Text2,
    )
    NavigationBar(containerColor = C.Surface, tonalElevation = 0.dp) {
        fun go(t: Tab) { vm.mobileSearch = false; vm.tab = t }
        val entries: List<Triple<String, ImageVector, Tab?>> = listOf(
            Triple("Accueil", Icons.Filled.Home, Tab.Home),
            Triple("Direct", Icons.Filled.PlayArrow, Tab.Live),
            Triple("Films", Icons.Filled.Star, Tab.Movies),
            Triple("Séries", Icons.Filled.List, Tab.Series),
            Triple("Recherche", Icons.Filled.Search, null),
        )
        entries.forEach { (label, icon, tab) ->
            val selected = if (tab == null) vm.mobileSearch else !vm.mobileSearch && vm.tab == tab
            NavigationBarItem(
                selected = selected,
                onClick = { if (tab == null) vm.mobileSearch = true else go(tab) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                colors = itemColors,
            )
        }
    }
}

// =================== Catalogue (smartphone) ===================

@Composable
fun MobileCatalog(vm: AppViewModel, section: Section) {
    LaunchedEffect(section) { vm.loadSection(section) }
    val all = vm.items[section]
    val category = vm.catalogCategory[section] ?: Category.ALL_ID
    val query = vm.catalogQuery[section] ?: ""
    val rev = ProfileStore.revision.intValue
    val cats = remember(all, rev) { vm.categoryList(section).filter { !it.isHeader } }
    // Filtrage après une courte pause de frappe : fluide même avec un gros catalogue.
    var debounced by remember(section) { mutableStateOf(query) }
    LaunchedEffect(query) { delay(250); debounced = query }
    val list = remember(all, category, debounced, rev) { vm.filtered(section, category, debounced) }
    val gridState = vm.gridStates.getOrPut(section) { LazyGridState() }
    val scope = rememberCoroutineScope()

    if (all == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val err = vm.loadErrors[section]
            if (err != null) {
                Text("Impossible de charger le contenu.\n$err", color = C.Text2, fontSize = 16.sp)
                Spacer(Modifier.height(18.dp))
                PillButton("Réessayer", { vm.loadErrors.remove(section); vm.loadSection(section) }, primary = true)
            } else {
                androidx.compose.material3.CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        val label = when (section) {
            Section.Live -> "une chaîne"
            Section.Movies -> "un film"
            Section.Series -> "une série"
        }
        OutlinedTextField(
            query, { vm.catalogQuery[section] = it; scope.launch { gridState.scrollToItem(0) } },
            placeholder = { Text("Rechercher $label…") }, singleLine = true, colors = fieldColors,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ vm.catalogQuery[section] = "" }) { Icon(Icons.Filled.Clear, "Effacer") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cats, key = { it.id }) { c ->
                PillButton("${c.name}  ${c.count}", {
                    vm.catalogCategory[section] = c.id
                    scope.launch { gridState.scrollToItem(0) }
                }, selected = c.id == category && query.isBlank(), compact = true)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (list.size == 1) "1 élément" else "${list.size} éléments", color = C.Text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (section != Section.Live) {
                val hide = ProfileStore.profile.hideWatched
                PillButton(if (hide) "✓ Vus masqués" else "Masquer les vus", { ProfileStore.setHideWatched(!hide) }, selected = hide, compact = true)
            }
        }
        if (list.isEmpty()) {
            Text(if (query.isNotBlank()) "Aucun résultat." else "Cette catégorie est vide.", color = C.Text2, fontSize = 16.sp,
                modifier = Modifier.padding(24.dp))
        } else if (section == Section.Live) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), state = gridState, contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(list) { ch -> ChannelCard(ch.title, ch.imageUrl, { vm.playChannel(ch, list) }, { vm.itemMenu(ch) }, fill = true) }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(104.dp), state = gridState, contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(list, key = { it.key }) { m -> PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) }, fill = true) }
            }
        }
    }
}

// =================== Recherche globale ===================

@Composable
fun MobileSearch(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        listOf(Section.Movies, Section.Series, Section.Live).forEach { vm.loadSection(it) }
        runCatching { field.requestFocus() }
    }
    LaunchedEffect(query) { delay(300); debounced = query.trim() }
    val movies = vm.items[Section.Movies]
    val series = vm.items[Section.Series]
    val live = vm.items[Section.Live]
    val results = remember(debounced, movies, series, live) {
        if (debounced.length < 2) null
        else {
            val nq = TitleGrouper.normalize(debounced)
            fun find(l: List<MediaItem>?) = l.orEmpty().asSequence().filter { TitleGrouper.normalize(it.title).contains(nq) }.take(40).toList()
            Triple(find(movies), find(series), find(live))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Film, série, chaîne…") }, singleLine = true, colors = fieldColors,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Clear, "Effacer") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { debounced = query.trim() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(field),
            )
        }
        when {
            results == null -> item {
                Text("Tapez au moins deux lettres. La recherche ignore les accents et couvre films, séries et chaînes.",
                    color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            results.first.isEmpty() && results.second.isEmpty() && results.third.isEmpty() -> item {
                Text("Aucun résultat pour « $debounced ».", color = C.Text2, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            }
            else -> {
                item {
                    ShelfRow("Films", "${results.first.size} résultat${if (results.first.size > 1) "s" else ""}", results.first, key = { it.key }) { m ->
                        PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) })
                    }
                }
                item {
                    ShelfRow("Séries", "${results.second.size} résultat${if (results.second.size > 1) "s" else ""}", results.second, key = { it.key }) { m ->
                        PosterCard(m, { vm.open(m) }, { vm.itemMenu(m) })
                    }
                }
                item {
                    val chans = results.third
                    ShelfRow("Chaînes", "${chans.size} résultat${if (chans.size > 1) "s" else ""}", chans) { ch ->
                        ChannelCard(ch.title, ch.imageUrl, { vm.playChannel(ch, chans) }, { vm.itemMenu(ch) })
                    }
                }
            }
        }
    }
}

// =================== Fiche détail (smartphone) ===================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileDetailScreen(item: MediaItem, vm: AppViewModel) {
    BackHandler { vm.closeDetail() }
    val rev = ProfileStore.revision.intValue
    val v = vm.variant
    val movie = vm.movieInfo
    val series = vm.seriesInfo
    val labels = remember(item) { vm.versionLabels(item) }
    var season by remember(series) { mutableIntStateOf(vm.detailSeason) }

    val year = vm.yearText(movie?.releaseDate ?: series?.releaseDate) ?: item.year
    val meta = listOfNotNull(
        year, movie?.genre ?: series?.genre ?: item.genre, movie?.duration,
        series?.seasons?.size?.let { if (it == 1) "1 saison" else if (it > 1) "$it saisons" else null },
        MediaItem.formatRating(movie?.rating ?: item.rating),
    ).joinToString("  ·  ")
    val people = listOfNotNull(movie?.director?.let { "Réalisation : $it" }, (movie?.cast ?: series?.cast)?.let { "Avec : $it" }).joinToString("\n")
    val backdrop = movie?.backdrop ?: series?.backdrop

    LazyColumn(Modifier.fillMaxSize().background(C.Bg), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(backdrop ?: item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color(0x66000000), 0.6f to Color.Transparent, 1f to C.Bg)))
                IconButton({ vm.closeDetail() }, Modifier.padding(4.dp)) { Icon(Icons.Filled.ArrowBack, "Retour", tint = Color.White) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp)) {
                Poster(item.imageUrl, item.initial, Modifier.width(104.dp).aspectRatio(2f / 3f), cornerRadius = 12.dp)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(item.title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (meta.isNotEmpty()) Text(meta, color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        if (item.variants.size > 1) item {
            Text("VERSION", color = C.Text2, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(item.variants) { i, variant ->
                    PillButton(labels.getOrElse(i) { "Version ${i + 1}" }, { vm.selectVariant(variant) }, selected = variant == v, compact = true)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                val canPlay = v != null && (v.kind == Section.Movies || series?.seasons?.isNotEmpty() == true)
                PillButton(if (canPlay) "▶  Lecture" else "Chargement…", { if (canPlay) vm.playDetail() }, Modifier.fillMaxWidth(), primary = true, fill = true)
                @Suppress("UNUSED_VARIABLE") val r = rev
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (ProfileStore.isFavorite(item)) "✓ Ma liste" else "+ Ma liste", { ProfileStore.toggleFavorite(item) }, compact = true)
                    val watched = ProfileStore.isWatched(item.key)
                    PillButton(if (watched) "✓ Vu" else "Marquer vu", { vm.toggleWatched(item) }, activeColor = if (watched) C.Blue else null, compact = true)
                    val rating = ProfileStore.ratingOf(item.key)
                    PillButton("👍", { vm.rate(item, 1) }, activeColor = if (rating > 0) C.Green else null, compact = true)
                    PillButton("👎", { vm.rate(item, -1) }, activeColor = if (rating < 0) C.Red else null, compact = true)
                    if (item.kind == Section.Movies) PillButton("⬇  Télécharger", { vm.downloadMovie() }, compact = true)
                }
                val plot = movie?.plot ?: series?.plot ?: item.plot
                if (!plot.isNullOrBlank()) Text(plot, color = Color(0xFFD1D1D6), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 18.dp))
                if (people.isNotEmpty()) Text(people, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
                if (vm.detailLoading) Text("Chargement…", color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                vm.detailError?.let { Text(it, color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
        }

        // Saisons / épisodes
        if (series != null && series.seasons.isNotEmpty()) {
            val current = series.seasons[season.coerceIn(0, series.seasons.size - 1)]
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(series.seasons) { i, s -> PillButton(s.label, { season = i }, selected = i == season, compact = true) }
                }
                PillButton("⬇  Télécharger la saison", { vm.downloadSeason(current.episodes) }, Modifier.padding(start = 16.dp, top = 10.dp), compact = true)
            }
            items(current.episodes, key = { it.id }) { ep ->
                val watched = ProfileStore.isWatched(ProfileStore.episodeKey(ep.id))
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Focusable({ vm.playEpisode(ep) }, Modifier.width(128.dp).aspectRatio(16f / 9f), shape = RoundedCornerShape(10.dp), focusedScale = 1f) {
                        Box(Modifier.fillMaxSize().background(C.Surface), contentAlignment = Alignment.Center) {
                            if (ep.image != null) AsyncImage(ep.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text("▶", color = Color.White, fontSize = 18.sp)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ep.label, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            if (watched) WatchedBadge(Modifier.padding(start = 6.dp))
                        }
                        ep.duration?.let { Text(it, color = C.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                        ep.plot?.let { Text(it, color = Color(0xFFAEAEB2), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    IconButton({ vm.downloadEpisode(ep) }) { Text("⬇", color = C.Text2, fontSize = 18.sp) }
                }
            }
        }
    }
}
