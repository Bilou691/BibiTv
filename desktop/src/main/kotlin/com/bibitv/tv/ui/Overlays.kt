package com.bibitv.tv.ui


import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bibitv.tv.data.DownloadItem
import com.bibitv.tv.data.DownloadManager
import com.bibitv.tv.data.DownloadState
import com.bibitv.tv.data.MediaItem
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.Recommendations
import com.bibitv.tv.data.Section
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.data.UpdateManager
import kotlinx.coroutines.launch

// =================== Fiche détail ===================

@Composable
fun DetailScreen(item: MediaItem, vm: AppViewModel) {
    BackHandler { vm.closeDetail() }
    val rev = ProfileStore.revision.intValue
    val v = vm.variant
    val movie = vm.movieInfo
    val series = vm.seriesInfo
    val labels = remember(item) { vm.versionLabels(item) }
    val playFocus = remember { FocusRequester() }
    var season by remember(series) { mutableIntStateOf(0) }

    val year = vm.yearText(movie?.releaseDate ?: series?.releaseDate) ?: item.year
    val meta = listOfNotNull(
        year, movie?.genre ?: series?.genre ?: item.genre, movie?.duration,
        series?.seasons?.size?.let { if (it == 1) "1 saison" else if (it > 1) "$it saisons" else null },
        MediaItem.formatRating(movie?.rating ?: item.rating),
    ).joinToString("   ·   ")
    val people = listOfNotNull(movie?.director?.let { "Réalisation : $it" }, (movie?.cast ?: series?.cast)?.let { "Avec : $it" }).joinToString("\n")
    val backdrop = movie?.backdrop ?: series?.backdrop

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        if (backdrop != null) AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.55f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF20A0A0C), 0.45f to Color(0xB30A0A0C), 1f to Color(0x1A0A0A0C))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to C.Bg)))

        LazyColumn(contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 40.dp, bottom = 60.dp)) {
            item {
                Row {
                    Poster(item.imageUrl, item.initial, Modifier.width(240.dp).aspectRatio(2f / 3f), cornerRadius = 16.dp)
                    Column(Modifier.padding(start = 44.dp).fillMaxWidth()) {
                        Text(item.title, color = C.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (meta.isNotEmpty()) Text(meta, color = C.Text2, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))

                        if (item.variants.size > 1) {
                            Text("VERSION", color = C.Text2, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(item.variants) { i, variant ->
                                    PillButton(labels.getOrElse(i) { "Version ${i + 1}" }, { vm.selectVariant(variant) }, selected = variant == v)
                                }
                            }
                        }

                        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val canPlay = v != null && (v.kind == Section.Movies || series?.seasons?.isNotEmpty() == true)
                            PillButton(if (canPlay) "▶  Lecture" else "Chargement…", { if (canPlay) vm.playDetail() }, Modifier.focusRequester(playFocus), primary = true)
                            @Suppress("UNUSED_VARIABLE") val r = rev
                            PillButton(if (ProfileStore.isFavorite(item)) "✓ Ma liste" else "+ Ma liste", { ProfileStore.toggleFavorite(item) })
                            val watched = ProfileStore.isWatched(item.key)
                            PillButton(if (watched) "✓ Vu" else "Marquer comme vu", { vm.toggleWatched(item) }, activeColor = if (watched) C.Blue else null)
                            val rating = ProfileStore.ratingOf(item.key)
                            PillButton("👍", { vm.rate(item, 1) }, activeColor = if (rating > 0) C.Green else null)
                            PillButton("👎", { vm.rate(item, -1) }, activeColor = if (rating < 0) C.Red else null)
                            if (item.kind == Section.Movies) PillButton("⬇  Télécharger", { vm.downloadMovie() })
                        }

                        val plot = movie?.plot ?: series?.plot ?: item.plot
                        if (!plot.isNullOrBlank()) Text(plot, color = Color(0xFFD1D1D6), fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 24.dp))
                        if (people.isNotEmpty()) Text(people, color = C.Text2, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 16.dp))
                        if (vm.detailLoading) Text("Chargement…", color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                        vm.detailError?.let { Text(it, color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp)) }
                    }
                }
            }

            // Saisons / épisodes
            if (series != null && series.seasons.isNotEmpty()) {
                item {
                    Row(Modifier.padding(top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(series.seasons) { i, s -> PillButton(s.label, { season = i }, selected = i == season, onFocus = { season = i }) }
                        }
                        PillButton("⬇  Télécharger la saison", { vm.downloadSeason(series.seasons[season.coerceIn(0, series.seasons.size - 1)].episodes) })
                    }
                }
                val eps = series.seasons[season.coerceIn(0, series.seasons.size - 1)].episodes
                items(eps, key = { it.id }) { ep ->
                    val watched = ProfileStore.isWatched(ProfileStore.episodeKey(ep.id))
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Focusable({ vm.playEpisode(ep) }, Modifier.width(220.dp).aspectRatio(16f / 9f)) {
                            Box(Modifier.fillMaxSize().background(C.Surface), contentAlignment = Alignment.Center) {
                                Text("▶", color = C.Text3, fontSize = 22.sp)
                                if (ep.image != null) AsyncImage(ep.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 22.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ep.label, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (watched) WatchedBadge(Modifier.padding(start = 10.dp))
                            }
                            ep.duration?.let { Text(it, color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                            ep.plot?.let { Text(it, color = Color(0xFFAEAEB2), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                        }
                        PillButton("⬇", { vm.downloadEpisode(ep) })
                    }
                }
            }
        }
    }
    LaunchedEffect(item) { runCatching { playFocus.requestFocus() } }
}

// =================== Personnaliser ===================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalizeScreen(vm: AppViewModel) {
    BackHandler { vm.showPersonalize = false }
    var kind by remember { mutableStateOf(Section.Movies) }
    val rev = ProfileStore.revision.intValue
    val catalog = vm.items[kind] ?: emptyList()
    val candidates = remember(kind, catalog) { Recommendations.candidates(catalog) }
    val p = ProfileStore.profile
    val n = p.likes.size + p.dislikes.size

    val compact = LocalDims.current.compact
    Column(Modifier.fillMaxSize().background(Color(0xFA0A0A0C)).padding(horizontal = if (compact) 16.dp else 56.dp, vertical = if (compact) 16.dp else 36.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(if (compact) Modifier.fillMaxWidth() else Modifier.weight(1f)) {
                Text("Vos goûts", color = C.Text, fontSize = if (compact) 28.sp else 34.sp, fontWeight = FontWeight.Bold)
                @Suppress("UNUSED_VARIABLE") val r = rev
                Text(
                    if (n == 0) "Notez quelques titres que vous avez vus : plus vous en notez, plus les suggestions « Pour toi » sont justes."
                    else "$n titre${if (n > 1) "s" else ""} noté${if (n > 1) "s" else ""}. Continuez pour affiner vos suggestions.",
                    color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
            PillButton("Films", { kind = Section.Movies }, selected = kind == Section.Movies)
            PillButton("Séries", { kind = Section.Series }, selected = kind == Section.Series)
            PillButton("Terminé", { vm.showPersonalize = false }, primary = true)
        }
        Text("👍 j'aime   ·   👎 je n'aime pas   ·   ✓ déjà vu", color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
        LazyVerticalGrid(GridCells.Adaptive(if (compact) 150.dp else 170.dp), contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(candidates, key = { it.key }) { m ->
                @Suppress("UNUSED_VARIABLE") val r = rev
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Poster(m.imageUrl, m.initial, Modifier.fillMaxWidth().aspectRatio(2f / 3f), cornerRadius = 12.dp)
                    Text(m.title, color = Color(0xFFE5E5EA), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                    val rating = ProfileStore.ratingOf(m.key)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PillButton("👍", { vm.rate(m, 1) }, Modifier.weight(1f), activeColor = if (rating > 0) C.Green else null, compact = true, fill = true)
                        PillButton("👎", { vm.rate(m, -1) }, Modifier.weight(1f), activeColor = if (rating < 0) C.Red else null, compact = true, fill = true)
                        PillButton("✓", { vm.toggleWatched(m) }, Modifier.weight(1f), activeColor = if (ProfileStore.isWatched(m.key)) C.Blue else null, compact = true, fill = true)
                    }
                }
            }
        }
    }
}

// =================== Téléchargements ===================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    BackHandler { vm.showDownloads = false }
    var dir by remember { mutableStateOf(SettingsStore.downloadDir) }
    val list = DownloadManager.items

    Row(Modifier.fillMaxSize().background(Color(0xFA0A0A0C)).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(Modifier.width(420.dp)) {
            Text("Téléchargements", color = C.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Dossier", color = C.Text2, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(16.dp)) {
                Text("📁  ${dir.absolutePath}", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val free = runCatching { (dir.takeIf { it.exists() } ?: dir.parentFile)?.usableSpace ?: -1L }.getOrDefault(-1L)
                if (free > 0) Text("${DownloadManager.size(free)} disponibles", color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Choisir un dossier…", {
                    chooseFolder(dir)?.let { SettingsStore.downloadDir = it; dir = it }
                })
                PillButton("Ouvrir le dossier", { dir.mkdirs(); com.bibitv.tv.data.Platform.open(dir) })
            }
            Text(
                "Films et épisodes se regardent ensuite sans connexion, depuis cet écran. " +
                    "Un téléchargement interrompu reprend là où il s'était arrêté.",
                color = C.Text3, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 16.dp),
            )
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Effacer les terminés", { DownloadManager.clearFinished() })
                PillButton("Fermer", { vm.showDownloads = false }, primary = true)
            }
        }
        Spacer(Modifier.width(40.dp))
        if (list.isEmpty()) {
            Text("Aucun téléchargement.\nUtilisez « Télécharger » sur la fiche d'un film ou d'un épisode.", color = C.Text2, fontSize = 16.sp,
                modifier = Modifier.padding(top = 80.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 60.dp)) {
                items(list) { d -> DownloadRow(d, vm) }
            }
        }
    }
}

/** Sélecteur de dossier du système. */
private fun chooseFolder(current: java.io.File): java.io.File? {
    val chooser = javax.swing.JFileChooser(current.takeIf { it.exists() } ?: current.parentFile)
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = "Dossier des téléchargements"
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

@Composable
private fun DownloadRow(d: DownloadItem, vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.status, color = C.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            ProgressLine(d.progress, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(14.dp))
        when (d.state) {
            DownloadState.Done -> d.location?.let { loc -> PillButton("▶  Lire", { vm.showDownloads = false; vm.playFile(d.title, loc) }) }
            DownloadState.Failed, DownloadState.Canceled -> PillButton("Réessayer", { DownloadManager.retry(d) })
            else -> PillButton("Annuler", { DownloadManager.cancel(d) })
        }
    }
}

// =================== Réglages ===================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    BackHandler { vm.showSettings = false }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        val compact = LocalDims.current.compact
        Column(
            Modifier.padding(vertical = 24.dp, horizontal = if (compact) 12.dp else 0.dp).widthIn(max = 620.dp).fillMaxWidth()
                .clip(RoundedCornerShape(22.dp)).background(C.Surface)
                .verticalScroll(rememberScrollState()).padding(if (compact) 20.dp else 32.dp),
        ) {
            Text("Réglages", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            vm.accountExpiry?.let {
                val d = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(it))
                Text("Abonnement valide jusqu'au $d", color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Text("Serveur : ${SettingsStore.serverUrl}", color = C.Text3, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

            Text("PLAYLISTS", color = C.Text2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Synchroniser les playlists", color = C.Text, fontSize = 16.sp)
                    Text(
                        (if (vm.syncing) "Synchronisation en cours…" else "Dernière synchronisation : ${vm.catalogAge()}") +
                            ". Automatique au retour sur l'app après 12 h.",
                        color = C.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, end = 12.dp),
                    )
                }
                PillButton(if (vm.syncing) "En cours…" else "Synchroniser", { if (!vm.syncing) { vm.showSettings = false; vm.syncPlaylists() } })
            }

            Text("APPLICATION", color = C.Text2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Version ${UpdateManager.currentVersion}", color = C.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                PillButton("Rechercher une mise à jour", { scope.launch { vm.checkUpdate(silent = false) } })
            }

            vm.lastCrash?.let { crash ->
                Text("DERNIER PLANTAGE", color = C.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 6.dp))
                Text(crash.lines().take(9).joinToString("\n"), color = C.Text2, fontSize = 11.sp, lineHeight = 15.sp)
                PillButton("Effacer", { vm.lastCrash = null }, Modifier.padding(top = 8.dp))
            }

            FlowRow(Modifier.padding(top = 30.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Personnaliser mes goûts", { vm.showSettings = false; vm.showPersonalize = true })
                PillButton("Se déconnecter", { vm.logout() })
                PillButton("Fermer", { vm.showSettings = false }, primary = true)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
