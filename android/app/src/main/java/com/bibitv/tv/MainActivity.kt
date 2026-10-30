package com.bibitv.tv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.bibitv.tv.data.DownloadManager
import com.bibitv.tv.data.ProfileStore
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.ui.AppRoot
import com.bibitv.tv.ui.AppViewModel

class BibiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        ProfileStore.init(this)
        DownloadManager.init(this)
    }
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot(vm) }
    }
}
