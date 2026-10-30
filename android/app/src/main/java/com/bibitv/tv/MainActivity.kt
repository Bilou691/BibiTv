package com.bibitv.tv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bibitv.tv.data.DownloadManager
import com.bibitv.tv.data.XtreamClient
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.ui.AppRoot
import com.bibitv.tv.ui.AppViewModel

class BibiApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Enregistre le dernier plantage : affiché au démarrage suivant et dans les Réglages.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "${java.util.Date()}\nBibiTV ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n\n" +
                        android.util.Log.getStackTraceString(error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
        SettingsStore.init(this)
        ProfileStore.init(this)
        DownloadManager.init(this)
    }

    /** Affiches et logos : cache mémoire + disque (256 Mo), sans fondu, pour une navigation fluide. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(XtreamClient.http)
        .crossfade(false)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(256L * 1024 * 1024).build() }
        .build()
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot(vm) }
    }
}
