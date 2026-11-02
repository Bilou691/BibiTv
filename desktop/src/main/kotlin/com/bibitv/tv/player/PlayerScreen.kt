package com.bibitv.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibitv.tv.data.Os
import com.bibitv.tv.data.Platform
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.data.XtreamClient
import com.bibitv.tv.ui.AppViewModel
import com.bibitv.tv.ui.BackHandler
import com.bibitv.tv.ui.C
import com.bibitv.tv.ui.Focusable
import com.bibitv.tv.ui.PillButton
import com.bibitv.tv.ui.PlayerRequest
import com.bibitv.tv.ui.ProgressLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import javax.swing.SwingUtilities

/**
 * VLC (LibVLC) : embarqué avec l'app sous Windows, celui de VLC installé sur macOS / Linux.
 * Une seule instance pour toute l'application (son chargement est coûteux).
 */
object Vlc {
    private var factory: MediaPlayerFactory? = null
    var error: String? = null
        private set

    @Synchronized
    fun factory(): MediaPlayerFactory? {
        factory?.let { return it }
        return try {
            // Windows : VLC embarqué dans les ressources de l'app ; ailleurs : VLC installé sur le système.
            Platform.resourcesDir?.resolve("vlc")?.takeIf { it.isDirectory }?.let { dir ->
                System.setProperty("jna.library.path", dir.absolutePath)
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", dir.absolutePath)
                com.sun.jna.NativeLibrary.addSearchPath("libvlccore", dir.absolutePath)
            }
            NativeDiscovery().discover()
            MediaPlayerFactory(
                "--network-caching=3000",
                "--live-caching=3000",
                "--http-reconnect",
                "--http-user-agent=${XtreamClient.USER_AGENT}",
                "--audio-time-stretch",
                "--no-video-title-show",
            ).also { factory = it; error = null }
        } catch (e: Throwable) {
            error = when (Platform.os) {
                Os.Mac -> "VLC est nécessaire pour lire les vidéos sur Mac. Installez-le (gratuit) depuis videolan.org, dans le dossier Applications, puis relancez BibiTV."
                Os.Linux -> "VLC est nécessaire pour lire les vidéos. Installez-le avec : sudo apt install vlc — puis relancez BibiTV."
                Os.Windows -> "Le lecteur VLC intégré n'a pas pu être chargé (${e.message})."
            }
            null
        }
    }
}

/**
 * Image vidéo décodée par VLC, copiée dans une image Skia affichée par Compose.
 * Deux images en alternance : l'une est dessinée pendant que l'autre reçoit la suivante.
 */
private class VideoFrames {
    private val bitmaps = arrayOf(Bitmap(), Bitmap())
    private var current = 0
    private var bytes = ByteArray(0)
    var width = 0
        private set
    var height = 0
        private set
    var image by mutableStateOf<ImageBitmap?>(null)

    fun format(w: Int, h: Int) {
        width = w; height = h
        bytes = ByteArray(w * h * 4)
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        bitmaps.forEach { it.allocPixels(info) }
    }

    fun display(buffer: ByteBuffer) {
        if (width == 0) return
        buffer.rewind()
        buffer.get(bytes, 0, minOf(bytes.size, buffer.remaining()))
        val next = 1 - current
        bitmaps[next].installPixels(bytes)
        current = next
        val img = bitmaps[next].asComposeImageBitmap()
        SwingUtilities.invokeLater { image = img }
    }
}

private fun formatName(f: Int) = when (f) {
    1 -> "Zoom"
    2 -> "Étiré"
    else -> "Original"
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(req: PlayerRequest, vm: AppViewModel, onClose: () -> Unit) {
    val factory = remember { Vlc.factory() }
    if (factory == null) {
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE61C1C1E)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Lecture impossible", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(Vlc.error ?: "VLC introuvable.", color = Color(0xFFAEAEB2), fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (Platform.os != Os.Windows) PillButton("Télécharger VLC", { Platform.browse("https://www.videolan.org/vlc/") }, primary = true)
                    PillButton("Fermer", onClose)
                }
            }
        }
        return
    }

    val frames = remember { VideoFrames() }
    val mp: EmbeddedMediaPlayer = remember {
        factory.mediaPlayers().newEmbeddedMediaPlayer().also { p ->
            val formatCallback = object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    frames.format(sourceWidth, sourceHeight)
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }

                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
            }
            val renderCallback = object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer) {}
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
                    frames.display(nativeBuffers[0])
                }
                override fun unlock(mediaPlayer: MediaPlayer) {}
            }
            p.videoSurface().set(factory.videoSurfaces().newVideoSurface(formatCallback, renderCallback, true))
        }
    }
    val scope = rememberCoroutineScope()

    var index by remember { mutableIntStateOf(req.index) }
    var time by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var seekable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var subtitle by remember { mutableStateOf("") }
    var osd by remember { mutableStateOf<String?>(null) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var format by remember { mutableIntStateOf(SettingsStore.videoFormat) }
    var volume by remember { mutableIntStateOf(SettingsStore.volume) }
    var trackMenu by remember { mutableStateOf<Pair<String, List<Pair<String, () -> Unit>>>?>(null) }
    val state = remember { object { var resumed = -1; var liveRetries = 0 } }
    val rootFocus = remember { FocusRequester() }

    fun entry() = req.playlist[index]

    fun report(finished: Boolean = false) {
        if (!req.isLive && length > 0) vm.onProgress(entry(), if (finished) length else time, length)
    }

    fun showOsd(text: String, durationMs: Long = 1200) {
        osd = text
        scope.launch { delay(durationMs); if (osd == text) osd = null }
    }

    fun playAt(i: Int) {
        index = i
        val e = req.playlist[i]
        time = 0; length = 0; error = null; buffering = true; playing = false; seekable = false
        subtitle = e.subtitle
        if (!e.url.startsWith("http") && !java.io.File(e.url).exists()) {
            buffering = false
            error = "Le fichier est introuvable. Le disque a peut-être été débranché, ou le fichier supprimé."
            return
        }
        mp.media().play(e.url, ":network-caching=3000")
        vm.onEntryStarted(e)
        val ch = e.channel
        if (req.isLive && ch != null) scope.launch { vm.nowPlaying(ch)?.let { if (index == i) subtitle = it } }
    }

    fun step(delta: Int) {
        val next = index + delta
        if (next < 0 || next >= req.playlist.size) return
        report()
        state.liveRetries = 0
        playAt(next)
        controls = true
    }

    fun seekBy(seconds: Int) {
        if (req.isLive || !mp.status().isSeekable) return
        val target = (mp.status().time() + seconds * 1000L).coerceIn(0L, maxOf(0L, mp.status().length() - 1000))
        mp.controls().setTime(target)
        time = target
        showOsd(if (seconds > 0) "+$seconds s" else "−${-seconds} s")
    }

    fun togglePause() {
        if (mp.status().isPlaying) { mp.controls().pause(); showOsd("Pause") } else mp.controls().play()
    }

    fun changeVolume(delta: Int) {
        volume = (volume + delta).coerceIn(0, 150)
        mp.audio().setVolume(volume)
        SettingsStore.volume = volume
        showOsd("Volume $volume %")
    }

    fun onEnd() {
        if (req.isLive) {
            if (state.liveRetries++ < 3) playAt(index) else error = "Le flux s'est interrompu."
        } else if (index < req.playlist.size - 1) {
            report(finished = true)
            playAt(index + 1)
        } else {
            report(finished = true)
            length = 0
            onClose()
        }
    }

    fun tracksMenu(audio: Boolean) {
        val tracks = if (audio) mp.audio().trackDescriptions() else mp.subpictures().trackDescriptions()
        val current = if (audio) mp.audio().track() else mp.subpictures().track()
        val actions = tracks.map { t ->
            val label = (if (t.id() == current) "✓  " else "     ") + when {
                t.id() < 0 -> "Désactivé"
                t.description().isNullOrBlank() -> "Piste ${t.id()}"
                else -> t.description()
            }
            label to { if (audio) mp.audio().setTrack(t.id()) else mp.subpictures().setTrack(t.id()); Unit }
        }
        trackMenu = (if (audio) "Langue audio" else "Sous-titres") to actions.ifEmpty { listOf((if (audio) "Aucune piste audio" else "Aucun sous-titre") to {}) }
    }

    DisposableEffect(Unit) {
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)
            override fun playing(mediaPlayer: MediaPlayer) = ui {
                playing = true; buffering = false; error = null; state.liveRetries = 0
                seekable = mp.status().isSeekable
                mp.audio().setVolume(volume)
                val e = entry()
                if (state.resumed != index && e.startMs > 5000 && mp.status().isSeekable) {
                    mp.controls().setTime(e.startMs)
                    showOsd("Reprise à ${fmt(e.startMs)}")
                }
                state.resumed = index
            }
            override fun paused(mediaPlayer: MediaPlayer) = ui { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui { buffering = newCache < 100f }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = ui { time = newTime; if (newTime > 0) buffering = false }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = ui { length = newLength }
            override fun finished(mediaPlayer: MediaPlayer) = ui { onEnd() }
            override fun error(mediaPlayer: MediaPlayer) = ui {
                buffering = false
                error = "Le flux n'a pas pu être ouvert. Il est peut-être hors ligne, ou le nombre de connexions de l'abonnement est atteint."
                controls = true
            }
        })
        playAt(req.index)
        onDispose {
            report()
            mp.controls().stop()
            mp.release()
        }
    }

    // Sauvegarde régulière de la position (reprise)
    LaunchedEffect(Unit) { while (true) { delay(15_000); report() } }
    // Masquage automatique des contrôles (souris immobile)
    LaunchedEffect(controls, lastInput, playing) {
        if (controls && playing && trackMenu == null && error == null) {
            delay(3500)
            controls = false
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    BackHandler {
        when {
            trackMenu != null -> trackMenu = null
            else -> onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                when (ev.key) {
                    Key.Spacebar, Key.MediaPlayPause, Key.K -> { togglePause(); true }
                    Key.DirectionLeft, Key.J -> { if (req.isLive) step(-1) else seekBy(-10); true }
                    Key.DirectionRight, Key.L -> { if (req.isLive) step(1) else seekBy(30); true }
                    Key.DirectionUp -> { changeVolume(5); true }
                    Key.DirectionDown -> { changeVolume(-5); true }
                    Key.PageUp, Key.MediaPrevious -> { step(-1); true }
                    Key.PageDown, Key.MediaNext -> { step(1); true }
                    Key.F, Key.Enter -> { vm.toggleFullscreen(); true }
                    Key.M -> { mp.audio().isMute = !mp.audio().isMute; showOsd(if (mp.audio().isMute) "Son coupé" else "Son rétabli"); true }
                    else -> false
                }
            }
            .focusable()
            .onPointerEvent(PointerEventType.Move) { lastInput = System.currentTimeMillis(); controls = true }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { lastInput = System.currentTimeMillis(); controls = !controls; runCatching { rootFocus.requestFocus() } },
                    onDoubleTap = { vm.toggleFullscreen() },
                )
            },
    ) {
        // Image vidéo, mise à l'échelle selon le format choisi
        frames.image?.let { img ->
            Canvas(Modifier.fillMaxSize()) {
                val iw = img.width.toFloat(); val ih = img.height.toFloat()
                val (dw, dh) = when (format) {
                    2 -> size.width to size.height
                    1 -> { val s = maxOf(size.width / iw, size.height / ih); iw * s to ih * s }
                    else -> { val s = minOf(size.width / iw, size.height / ih); iw * s to ih * s }
                }
                drawImage(
                    img,
                    dstOffset = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                    dstSize = IntSize(dw.toInt(), dh.toInt()),
                )
            }
        }

        if (buffering && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(14.dp))
                Text("Chargement…", color = Color(0xCCFFFFFF), fontSize = 15.sp)
            }
        }

        osd?.let {
            Text(it, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 80.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0x99000000)).padding(horizontal = 20.dp, vertical = 10.dp))
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 520.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE61C1C1E)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Lecture impossible", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(msg, color = Color(0xFFAEAEB2), fontSize = 15.sp, modifier = Modifier.padding(vertical = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Réessayer", { state.liveRetries = 0; playAt(index) }, primary = true)
                    PillButton("Fermer", onClose)
                }
            }
        }

        AnimatedVisibility(controls && error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().align(Alignment.TopStart)
                        .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                        .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 60.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PillButton("←  Retour", onClose, Modifier.padding(end = 16.dp), compact = true)
                        Column {
                            Text(entry().title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subtitle, color = Color(0xCCFFFFFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                        .padding(start = 32.dp, end = 32.dp, top = 70.dp, bottom = 24.dp),
                ) {
                    if (req.isLive) {
                        Text("●  EN DIRECT", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                    } else if (seekable || length > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
                            Text(fmt(time), color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.width(70.dp))
                            // Barre de progression cliquable : clic = aller à ce moment
                            Box(Modifier.weight(1f).height(18.dp).pointerInput(length) {
                                detectTapGestures { pos ->
                                    if (length > 0 && mp.status().isSeekable) {
                                        val t = (pos.x / size.width * length).toLong().coerceIn(0, length - 1000)
                                        mp.controls().setTime(t); time = t
                                    }
                                }
                            }, contentAlignment = Alignment.Center) {
                                ProgressLine(if (length > 0) time.toFloat() / length else 0f, Modifier.fillMaxWidth())
                            }
                            Text(fmt(length), color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.width(80.dp).padding(start = 12.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (req.playlist.size > 1) PillButton("⏮", { step(-1) }, compact = true)
                        if (!req.isLive) PillButton("−10 s", { seekBy(-10) }, compact = true)
                        PillButton(if (playing) "❚❚  Pause" else "▶  Lecture", { togglePause() }, primary = true, compact = true)
                        if (!req.isLive) PillButton("+30 s", { seekBy(30) }, compact = true)
                        if (req.playlist.size > 1) PillButton("⏭", { step(1) }, compact = true)
                        Spacer(Modifier.weight(1f))
                        PillButton("🔉", { changeVolume(-10) }, compact = true)
                        PillButton("🔊", { changeVolume(10) }, compact = true)
                        PillButton(formatName(format), {
                            format = (format + 1) % 3; SettingsStore.videoFormat = format; showOsd(formatName(format))
                        }, compact = true)
                        PillButton("Audio", { tracksMenu(true) }, compact = true)
                        PillButton("Sous-titres", { tracksMenu(false) }, compact = true)
                        PillButton("⛶", { vm.toggleFullscreen() }, compact = true)
                    }
                }
            }
        }

        trackMenu?.let { (title, actions) ->
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.padding(end = 32.dp, start = 16.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(18.dp)) {
                    Text(title, color = C.Text2, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp, start = 6.dp))
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        itemsIndexed(actions) { _, (label, action) ->
                            Focusable({ action(); trackMenu = null }, Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp), focusedScale = 1.02f, borderWidth = 0.dp) { f ->
                                Text(label, color = if (f) Color(0xFF0A0A0C) else C.Text, fontSize = 16.sp,
                                    modifier = Modifier.fillMaxWidth().background(if (f) Color.White else Color.Transparent).padding(horizontal = 14.dp, vertical = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
