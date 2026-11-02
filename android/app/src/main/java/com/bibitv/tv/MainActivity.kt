package com.bibitv.tv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bibitv.tv.data.Device
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
        Device.init(this)
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
        if (Device.isTv) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            // Smartphone / tablette : barres système visibles, rotation libre, contenu bord à bord.
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            enableEdgeToEdge()
        }
        vm.requestNotificationPermission = {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
        intent?.data?.let { vm.handleLink(it) }
        setContent { AppRoot(vm) }
    }

    /** Smartphone / tablette : la vidéo continue dans une petite fenêtre quand on quitte l'app. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!Device.isTv && vm.player != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    /** App quittée (touche Accueil, fenêtre PiP fermée…) : la lecture se met en pause. */
    override fun onStop() {
        super.onStop()
        val pip = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!pip) vm.onBackground()
    }

    override fun onResume() {
        super.onResume()
        vm.onAppResumed()
    }

    /** Lien direct reçu alors que BibiTV est déjà ouverte (activité singleTask). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.let { vm.handleLink(it) }
    }
}
