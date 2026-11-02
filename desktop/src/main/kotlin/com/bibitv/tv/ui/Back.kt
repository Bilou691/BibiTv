package com.bibitv.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * « Retour » sur ordinateur : touche Échap (ou bouton Retour de la souris).
 * Même usage que BackHandler d'Android : le dernier écran affiché qui l'a déclaré le reçoit.
 */
object Back {
    private val handlers = mutableListOf<Entry>()

    class Entry(var enabled: () -> Boolean, var action: () -> Unit)

    internal fun add(e: Entry) { handlers += e }
    internal fun remove(e: Entry) { handlers -= e }

    /** Appelé par la fenêtre ; renvoie vrai si un écran a traité le retour. */
    fun dispatch(): Boolean {
        val h = handlers.lastOrNull { it.enabled() } ?: return false
        h.action()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val isEnabled = rememberUpdatedState(enabled)
    DisposableEffect(Unit) {
        val e = Back.Entry({ isEnabled.value }, { current.value() })
        Back.add(e)
        onDispose { Back.remove(e) }
    }
}
