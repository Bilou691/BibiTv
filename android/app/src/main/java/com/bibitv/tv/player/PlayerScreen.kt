package com.bibitv.tv.player

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.data.XtreamClient
import com.bibitv.tv.ui.AppViewModel
import com.bibitv.tv.ui.C
import com.bibitv.tv.ui.PillButton
import com.bibitv.tv.ui.PlayerRequest
import com.bibitv.tv.ui.ProgressLine
import com.bibitv.tv.ui.Focusable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** Une seule instance de LibVLC pour toute l'application (son chargement est coûteux). */
object Vlc {
    @Volatile private var lib: LibVLC? = null
    private var libDirect: Boolean? = null

    fun get(context: Context): LibVLC = synchronized(this) {
        val direct = SettingsStore.directRendering
        lib?.takeIf { libDirect == direct } ?: create(context, direct).also {
            lib?.release()
            lib = it
            libDirect = direct
        }
    }

    private fun create(context: Context, direct: Boolean): LibVLC = LibVLC(
            context.applicationContext,
            ArrayList(arrayListOf<String>().apply {
                // Sortie directe vers le circuit vidéo de la TV : HDR10 et noirs conservés
                if (direct) add("--vout=android_display,none")
            } + arrayListOf(
                // Tampon réseau confortable pour les flux 4K (débits élevés, réseau Wi-Fi des TV)
                "--network-caching=3000",
                "--live-caching=3000",
                "--http-reconnect",
                "--http-user-agent=${XtreamClient.USER_AGENT}",
                "--audio-time-stretch",
            )),
        )
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

/**
 * Ajuste la fréquence de l'écran à celle de la vidéo (24p → 24 Hz, 25p → 50 Hz…) sans changer
 * de résolution : supprime les saccades des panoramiques sur les TV 4K.
 */
private fun matchDisplayMode(activity: Activity, fps: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || fps < 10f) return
    @Suppress("DEPRECATION") val display = activity.windowManager.defaultDisplay ?: return
    val current = display.mode
    val best = display.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        .map { m ->
            val ratio = m.refreshRate / fps
            val mult = ratio.roundToInt()
            Triple(m, mult, abs(ratio - mult))
        }
        .filter { it.second >= 1 && it.third < 0.01f }
        .minByOrNull { it.second }
        ?.first ?: return
    if (best.modeId == current.modeId) return
    val lp = activity.window.attributes
    lp.preferredDisplayModeId = best.modeId
    activity.window.attributes = lp
}

private fun resetDisplayMode(activity: Activity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val lp = activity.window.attributes
    if (lp.preferredDisplayModeId != 0) {
        lp.preferredDisplayModeId = 0
        activity.window.attributes = lp
    }
}

@Composable
fun PlayerScreen(req: PlayerRequest, vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lib = remember { Vlc.get(context) }
    val mp = remember { MediaPlayer(lib) }
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
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var format by remember { mutableIntStateOf(SettingsStore.videoFormat) }
    var trackMenu by remember { mutableStateOf<Pair<String, List<Pair<String, () -> Unit>>>?>(null) }
    val state = remember { object { var resumed = -1; var liveRetries = 0; var pfd: ParcelFileDescriptor? = null } }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

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
        state.pfd?.close(); state.pfd = null
        val media = when {
            e.url.startsWith("content://") -> {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(e.url), "r")
                state.pfd = pfd
                Media(lib, pfd!!.fileDescriptor)
            }
            e.url.startsWith("/") -> Media(lib, e.url)
            else -> Media(lib, Uri.parse(e.url))
        }
        // Décodage matériel (MediaCodec) : indispensable pour la 4K HEVC fluide.
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=3000")
        mp.media = media
        media.release()
        mp.play()
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
        if (req.isLive || !mp.isSeekable) return
        val target = (mp.time + seconds * 1000L).coerceIn(0L, maxOf(0L, mp.length - 1000))
        mp.setTime(target)
        time = target
        showOsd(if (seconds > 0) "+$seconds s" else "−${-seconds} s")
    }

    fun togglePause() {
        if (mp.isPlaying) { mp.pause(); showOsd("Pause") } else mp.play()
    }

    fun onEnd() {
        if (req.isLive) {
            // Un direct qui « se termine » est en général une coupure : on relance.
            if (state.liveRetries++ < 3) playAt(index) else error = "Le flux s'est interrompu."
        } else if (index < req.playlist.size - 1) {
            report(finished = true)
            playAt(index + 1) // épisode suivant
        } else {
            report(finished = true)
            length = 0
            onClose()
        }
    }

    fun applyFormat(f: Int) {
        mp.setVideoScale(when (f) {
            1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN // zoom : remplit l'écran (rogne les bords)
            2 -> MediaPlayer.ScaleType.SURFACE_FILL       // étiré
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        })
    }

    fun cycleFormat() {
        format = (format + 1) % 3
        SettingsStore.videoFormat = format
        applyFormat(format)
        showOsd(formatName(format))
    }

    fun tracksMenu(audio: Boolean) {
        val tracks = (if (audio) mp.audioTracks else mp.spuTracks) ?: emptyArray()
        val current = if (audio) mp.audioTrack else mp.spuTrack
        val actions = tracks.map { t ->
            val label = (if (t.id == current) "✓  " else "     ") + when {
                t.id < 0 -> "Désactivé"
                t.name.isNullOrBlank() -> "Piste ${t.id}"
                else -> t.name
            }
            label to { if (audio) mp.setAudioTrack(t.id) else mp.setSpuTrack(t.id); Unit }
        }
        trackMenu = (if (audio) "Langue audio" else "Sous-titres") to actions.ifEmpty { listOf((if (audio) "Aucune piste audio" else "Aucun sous-titre") to {}) }
    }

    DisposableEffect(Unit) {
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true; buffering = false; error = null; state.liveRetries = 0
                    seekable = mp.isSeekable
                    val e = entry()
                    if (state.resumed != index && e.startMs > 5000 && mp.isSeekable) {
                        mp.setTime(e.startMs)
                        showOsd("Reprise à ${fmt(e.startMs)}")
                    }
                    state.resumed = index
                    if (SettingsStore.matchFrameRate && activity != null) scope.launch {
                        delay(800)
                        val vt = mp.currentVideoTrack
                        if (vt != null && vt.frameRateDen > 0) matchDisplayMode(activity, vt.frameRateNum.toFloat() / vt.frameRateDen)
                    }
                }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
                MediaPlayer.Event.TimeChanged -> { time = ev.timeChanged; if (time > 0) buffering = false }
                MediaPlayer.Event.LengthChanged -> length = ev.lengthChanged
                MediaPlayer.Event.EndReached -> scope.launch { onEnd() }
                MediaPlayer.Event.EncounteredError -> {
                    buffering = false
                    error = "Le flux n'a pas pu être ouvert. Il est peut-être hors ligne, ou le nombre de connexions de l'abonnement est atteint."
                    controls = true
                }
                else -> {}
            }
        }
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFormat(format)
        playAt(req.index)
        onDispose {
            report()
            mp.setEventListener(null)
            mp.stop()
            mp.detachViews()
            mp.release()
            state.pfd?.close()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.let { resetDisplayMode(it) }
        }
    }

    // Sauvegarde régulière de la position (reprise)
    LaunchedEffect(Unit) { while (true) { delay(15_000); report() } }

    // Masquage automatique des contrôles
    LaunchedEffect(controls, lastKey, playing) {
        if (controls && playing && trackMenu == null && error == null) {
            delay(5000)
            controls = false
        }
    }
    LaunchedEffect(controls) {
        runCatching { if (controls) playFocus.requestFocus() else rootFocus.requestFocus() }
    }

    BackHandler {
        when {
            trackMenu != null -> trackMenu = null
            controls && error == null -> controls = false
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
                lastKey = System.currentTimeMillis()
                when (ev.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { togglePause(); true }
                    Key.MediaFastForward -> { seekBy(30); true }
                    Key.MediaRewind -> { seekBy(-10); true }
                    Key.ChannelUp, Key.MediaNext, Key.PageDown -> { step(1); true }
                    Key.ChannelDown, Key.MediaPrevious, Key.PageUp -> { step(-1); true }
                    else -> if (!controls && trackMenu == null) {
                        when (ev.key) {
                            Key.DirectionLeft -> { if (req.isLive) controls = true else seekBy(-10); true }
                            Key.DirectionRight -> { if (req.isLive) controls = true else seekBy(30); true }
                            Key.DirectionUp -> { if (req.isLive) step(-1) else controls = true; true }
                            Key.DirectionDown -> { if (req.isLive) step(1) else controls = true; true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (!req.isLive) togglePause(); controls = true; true }
                            Key.Menu -> { controls = true; true }
                            else -> false
                        }
                    } else false
                }
            }
            .focusable(),
    ) {
        // Rendu vidéo direct (SurfaceView) : aucune copie par l'interface, idéal en 4K.
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also {
                    it.setBackgroundColor(android.graphics.Color.BLACK)
                    mp.attachViews(it, null, true, false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(14.dp))
                Text("Chargement…", color = Color(0xCCFFFFFF), fontSize = 15.sp)
            }
        }

        osd?.let {
            Text(it, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).width(520.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE61C1C1E)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Lecture impossible", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(msg, color = Color(0xFFAEAEB2), fontSize = 15.sp, modifier = Modifier.padding(vertical = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Réessayer", { state.liveRetries = 0; playAt(index) }, Modifier.focusRequester(playFocus), primary = true)
                    PillButton("Fermer", onClose)
                }
            }
        }

        AnimatedVisibility(controls && error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Haut : titre + programme
                Column(
                    Modifier.fillMaxWidth().align(Alignment.TopStart)
                        .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                        .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 60.dp),
                ) {
                    Text(entry().title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = Color(0xCCFFFFFF), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                // Bas : progression + boutons
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                        .padding(start = 48.dp, end = 48.dp, top = 70.dp, bottom = 34.dp),
                ) {
                    if (req.isLive) {
                        Text("●  EN DIRECT", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                    } else if (seekable || length > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
                            Text(fmt(time), color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.width(70.dp))
                            ProgressLine(if (length > 0) time.toFloat() / length else 0f, Modifier.weight(1f))
                            Text(fmt(length), color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.width(70.dp).padding(start = 12.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (req.playlist.size > 1) PillButton(if (req.isLive) "◀  Chaîne préc." else "◀  Précédent", { step(-1) })
                        if (!req.isLive) PillButton("−10 s", { seekBy(-10) })
                        PillButton(if (playing) "❚❚  Pause" else "▶  Lecture", { togglePause() }, Modifier.focusRequester(playFocus), primary = true)
                        if (!req.isLive) PillButton("+30 s", { seekBy(30) })
                        if (req.playlist.size > 1) PillButton(if (req.isLive) "Chaîne suiv.  ▶" else "Suivant  ▶", { step(1) })
                        Spacer(Modifier.weight(1f))
                        PillButton("Infos", {
                            val vt = mp.currentVideoTrack
                            showOsd(
                                if (vt == null) "Aucune piste vidéo"
                                else "${vt.width}×${vt.height}" +
                                    (if (vt.frameRateDen > 0) "  ·  %.3f i/s".format(vt.frameRateNum.toFloat() / vt.frameRateDen) else "") +
                                    "  ·  ${vt.codec ?: "?"}" +
                                    (if (vt.width >= 3800) "  ·  4K" else ""),
                                4000,
                            )
                        })
                        PillButton("Format : ${formatName(format)}", { cycleFormat() })
                        PillButton("Audio", { tracksMenu(true) })
                        PillButton("Sous-titres", { tracksMenu(false) })
                    }
                }
            }
        }

        trackMenu?.let { (title, actions) ->
            val first = remember(title) { FocusRequester() }
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.width(420.dp).padding(end = 48.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(18.dp)) {
                    Text(title, color = C.Text2, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp, start = 6.dp))
                    LazyColumn(Modifier.height(380.dp)) {
                        itemsIndexed(actions) { i, (label, action) ->
                            Focusable({ action(); trackMenu = null }, (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp), focusedScale = 1.02f, borderWidth = 0.dp) { f ->
                                Text(label, color = if (f) Color(0xFF0A0A0C) else C.Text, fontSize = 16.sp,
                                    modifier = Modifier.fillMaxWidth().background(if (f) Color.White else Color.Transparent).padding(horizontal = 14.dp, vertical = 12.dp))
                            }
                        }
                    }
                }
            }
            LaunchedEffect(title) { runCatching { first.requestFocus() } }
        }
    }
}
