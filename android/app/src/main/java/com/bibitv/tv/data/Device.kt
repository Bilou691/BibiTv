package com.bibitv.tv.data

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Type d'appareil, détecté au lancement : TV (télécommande, interface « 10-foot »)
 * ou mobile (smartphone / tablette, interface tactile). Un seul APK pour les deux.
 */
object Device {
    var isTv: Boolean = true
        private set

    fun init(context: Context) {
        val ui = context.getSystemService(UiModeManager::class.java)
        isTv = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** « cette TV » / « cet appareil », pour les textes affichés. */
    val label: String get() = if (isTv) "cette TV" else "cet appareil"
}
