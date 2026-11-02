@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.bibitv.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bibitv.tv.data.HistoryEntry
import com.bibitv.tv.data.MediaItem
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.RankedItem

// ===== Palette (inspirée de tvOS, mode sombre) =====
/**
 * Dimensions de l'interface selon l'écran : TV (vue de loin), tablette, smartphone.
 * Les composants partagés (rangées, affiches…) les lisent pour s'adapter.
 */
data class Dims(
    val compact: Boolean,
    val gutter: Dp,
    val gap: Dp,
    val shelfTitle: TextUnit,
    val poster: Dp,
    val channel: Dp,
    val rankWidth: Dp,
    val rankFont: TextUnit,
)

val TvDims = Dims(compact = false, gutter = 48.dp, gap = 22.dp, shelfTitle = 22.sp, poster = 150.dp, channel = 200.dp, rankWidth = 230.dp, rankFont = 150.sp)
val TabletDims = Dims(compact = false, gutter = 28.dp, gap = 16.dp, shelfTitle = 20.sp, poster = 132.dp, channel = 180.dp, rankWidth = 196.dp, rankFont = 120.sp)
val PhoneDims = Dims(compact = true, gutter = 16.dp, gap = 12.dp, shelfTitle = 18.sp, poster = 108.dp, channel = 150.dp, rankWidth = 158.dp, rankFont = 96.sp)

val LocalDims = staticCompositionLocalOf { TvDims }

object C {
    val Bg = Color(0xFF0A0A0C)
    val Surface = Color(0xFF1C1C1E)
    val Surface2 = Color(0xFF2C2C2E)
    val Surface3 = Color(0xFF3A3A3C)
    val Text = Color(0xFFF5F5F7)
    val Text2 = Color(0xFF8E8E93)
    val Text3 = Color(0xFF5A5A5F)
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Yellow = Color(0xFFFFD60A)
}

/** Élément focalisable à la télécommande : léger zoom + contour blanc quand il a le focus. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.08f,
    borderWidth: Dp = 3.dp,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "scale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clip(shape)
            .border(if (focused) borderWidth else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) { content(focused) }
}

/** Bouton en pilule (onglets, actions). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primary: Boolean = false,
    activeColor: Color? = null,
    onFocus: (() -> Unit)? = null,
    compact: Boolean = false,
    fill: Boolean = false,
) {
    Focusable(onClick, modifier, onFocus = onFocus, shape = RoundedCornerShape(24.dp), focusedScale = 1.05f, borderWidth = 0.dp) { focused ->
        val bg = when {
            focused -> Color.White
            activeColor != null -> activeColor
            primary || selected -> Color(0xFFE5E5EA)
            else -> Color(0x26FFFFFF)
        }
        val fg = if (focused || ((primary || selected) && activeColor == null)) Color(0xFF0A0A0C) else C.Text
        Box((if (fill) Modifier.fillMaxWidth() else Modifier).background(bg).padding(horizontal = if (compact) 16.dp else 22.dp, vertical = if (compact) 8.dp else 10.dp), contentAlignment = Alignment.Center) {
            Text(text, color = fg, fontSize = if (compact) 15.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(C.Blue.copy(alpha = 0.9f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text("✓ VU", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Poster(url: String?, initial: String, modifier: Modifier = Modifier, cornerRadius: Dp = 14.dp) {
    Box(modifier.clip(RoundedCornerShape(cornerRadius)).background(C.Surface), contentAlignment = Alignment.Center) {
        Text(initial, color = C.Text3, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Affiche film / série (2:3). */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    width: Dp = Dp.Unspecified,
    fill: Boolean = false,
) {
    @Suppress("UNUSED_VARIABLE") val rev = ProfileStore.revision.intValue // rafraîchit la pastille « VU »
    val w = if (width == Dp.Unspecified) LocalDims.current.poster else width
    Column(if (fill) Modifier.fillMaxWidth() else Modifier.width(w)) {
        Focusable(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), onLongClick, onFocus) {
            Poster(item.imageUrl, item.initial, Modifier.fillMaxSize())
            if (ProfileStore.isWatched(item.key)) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Text(item.title, color = Color(0xFFE5E5EA), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp))
        if (item.subtitle.isNotEmpty()) Text(item.subtitle, color = C.Text2, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Tuile de chaîne (16:9 avec logo). */
@Composable
fun ChannelCard(
    title: String,
    logo: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    width: Dp = Dp.Unspecified,
    fill: Boolean = false,
) {
    val w = if (width == Dp.Unspecified) LocalDims.current.channel else width
    Column(if (fill) Modifier.fillMaxWidth() else Modifier.width(w)) {
        Focusable(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onLongClick, onFocus) {
            Box(Modifier.fillMaxSize().background(C.Surface), contentAlignment = Alignment.Center) {
                if (logo == null) Text(title.take(1).uppercase(), color = C.Text3, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                else AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(horizontal = w * 0.13f, vertical = w * 0.08f))
            }
        }
        Text(title, color = Color(0xFFE5E5EA), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp))
    }
}

/** Grand numéro du Top 10 + affiche. */
@Composable
fun RankedPoster(r: RankedItem, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val d = LocalDims.current
    Box(Modifier.width(d.rankWidth)) {
        Text(
            r.rank.toString(), fontSize = d.rankFont, fontWeight = FontWeight.Black, lineHeight = d.rankFont,
            style = androidx.compose.ui.text.TextStyle(brush = Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x14FFFFFF)))),
            modifier = Modifier.align(Alignment.TopStart).padding(top = d.poster * 0.27f),
        )
        Box(Modifier.align(Alignment.TopEnd)) { PosterCard(r.item, onClick, onLongClick) }
    }
}

@Composable
fun RankedChannel(r: RankedItem, onClick: () -> Unit, onFocus: () -> Unit) {
    val d = LocalDims.current
    Box(Modifier.width(d.channel * 1.35f)) {
        Text(
            r.rank.toString(), fontSize = d.rankFont * 0.73f, fontWeight = FontWeight.Black, lineHeight = d.rankFont * 0.73f,
            style = androidx.compose.ui.text.TextStyle(brush = Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x14FFFFFF)))),
            modifier = Modifier.align(Alignment.TopStart),
        )
        Box(Modifier.align(Alignment.TopEnd)) { ChannelCard(r.item.title, r.item.imageUrl, onClick, onFocus = onFocus) }
    }
}

/** Reprendre : affiche + barre de progression. */
@Composable
fun HistoryCard(h: HistoryEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.width(LocalDims.current.poster)) {
        Focusable(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), onLongClick) {
            Poster(h.imageUrl, h.title.take(1).uppercase(), Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))))
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xB3000000)).align(Alignment.Center), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 16.sp)
            }
            if (h.hasProgress) ProgressLine(h.progress, Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth())
        }
        Text(h.title, color = Color(0xFFE5E5EA), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
        if (h.subtitle.isNotEmpty()) Text(h.subtitle, color = C.Text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier, color: Color = Color.White) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(4.dp).background(color))
    }
}

/** Rangée de l'accueil : titre, sous-titre et liste horizontale. */
@Composable
fun <T> ShelfRow(title: String, subtitle: String?, items: List<T>, key: ((T) -> Any)? = null, action: (@Composable () -> Unit)? = null, content: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    val d = LocalDims.current
    Column(Modifier.padding(top = if (d.compact) 20.dp else 30.dp)) {
        Row(Modifier.padding(horizontal = d.gutter), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Text, fontSize = d.shelfTitle, fontWeight = FontWeight.Bold)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            }
            action?.invoke()
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = d.gutter, vertical = if (d.compact) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(d.gap),
        ) {
            if (key != null) items(items, key = key) { content(it) } else items(items) { content(it) }
        }
    }
}

@Composable
fun Spinner(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0xCC0A0A0C)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(18.dp))
            Text(text, color = C.Text2, fontSize = 15.sp)
        }
    }
}

/** Menu d'actions (appui long sur OK). */
@Composable
fun ActionMenu(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = C.Text2, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp, start = 6.dp))
            actions.forEachIndexed { i, (label, action) ->
                val m = if (i == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth()
                Focusable({ action(); onDismiss() }, m, shape = RoundedCornerShape(10.dp), focusedScale = 1.02f, borderWidth = 0.dp) { f ->
                    Text(label, color = if (f) Color(0xFF0A0A0C) else C.Text, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().background(if (f) Color.White else Color.Transparent).padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Chemin d'une image embarquée (dossier resources) à partir de son nom Android. */
fun img(name: String): String = if (name == "splash_background") "splash_background.jpg" else "$name.png"
