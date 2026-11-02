package com.bibitv.tv

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.bibitv.tv.data.AppInfo
import com.bibitv.tv.data.DownloadManager
import com.bibitv.tv.data.Platform
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.ui.AppRoot
import com.bibitv.tv.ui.AppViewModel
import com.bibitv.tv.ui.Back
import okio.Path.Companion.toOkioPath
import java.io.File

fun main() {
    // Enregistre le dernier plantage : affiché au démarrage suivant et dans les Réglages.
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            File(Platform.dataDir, "last_crash.txt").writeText(
                "${java.util.Date()}\nBibiTV ${AppInfo.version} · ${System.getProperty("os.name")} ${System.getProperty("os.version")} · Java ${System.getProperty("java.version")}\n\n" +
                    error.stackTraceToString(),
            )
        }
        previous?.uncaughtException(thread, error)
    }
    SettingsStore.init()
    ProfileStore.init()
    DownloadManager.init()

    application {
        val vm = remember { AppViewModel() }
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp), position = WindowPosition.Aligned(Alignment.Center))
        LaunchedEffect(vm.fullscreen) {
            state.placement = if (vm.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "BibiTV",
            icon = painterResource("logo.png"),
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    // Échap : quitte d'abord le plein écran, puis revient à l'écran précédent.
                    if (vm.fullscreen) { vm.fullscreen = false; true } else Back.dispatch()
                } else false
            },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(960, 600) }
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.25).build() }
                    .diskCache { DiskCache.Builder().directory(File(Platform.dataDir, "images").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
                    .build()
            }
            AppRoot(vm)
        }
    }
}
