package dev.geode.design.glass

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The glass tier for the person's own [reduceTransparency] and [batterySaving] (Visuals off) switches
 * and what Android reports now: battery saver, thermal pressure, the contrast level (API 34 and later),
 * high-contrast text (API 36 and later) and a low-RAM device. It follows Android's changes as they
 * happen ([GlassTiers.select]).
 */
@Composable
fun rememberGlassTier(
    reduceTransparency: Boolean,
    batterySaving: Boolean,
): GlassTier {
    val context = LocalContext.current.applicationContext
    var system by remember(context) { mutableStateOf(systemSignalsOf(context)) }
    DisposableEffect(context) {
        val watcher = SystemWatcher(context) { system = systemSignalsOf(context) }
        watcher.start()
        onDispose { watcher.stop() }
    }
    return GlassTiers.select(system.copy(reduceTransparency = reduceTransparency, batterySaving = batterySaving))
}

/** What Android reports now, read once. */
private fun systemSignalsOf(context: Context): GlassSignals {
    val power = context.getSystemService(PowerManager::class.java)
    val activity = context.getSystemService(ActivityManager::class.java)
    return GlassSignals(
        highContrastText = Build.VERSION.SDK_INT >= HIGH_CONTRAST_TEXT_API && highContrastText(context),
        contrast = if (Build.VERSION.SDK_INT >= CONTRAST_API) contrast(context) else 0f,
        powerSave = power?.isPowerSaveMode == true,
        thermalStatus = power?.currentThermalStatus ?: 0,
        lowRam = activity?.isLowRamDevice == true,
    )
}

@RequiresApi(HIGH_CONTRAST_TEXT_API)
private fun highContrastText(context: Context): Boolean =
    context.getSystemService(AccessibilityManager::class.java)?.isHighContrastTextEnabled == true

@RequiresApi(CONTRAST_API)
private fun contrast(context: Context): Float = context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f

/** Calls [onChange] whenever one of the signals the tier reads may have changed, while started. */
private class SystemWatcher(
    private val context: Context,
    private val onChange: () -> Unit,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) = onChange()
        }
    private val thermal = PowerManager.OnThermalStatusChangedListener { onChange() }
    private var contrastListener: Any? = null
    private var highContrastListener: Any? = null

    fun start() {
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= RECEIVER_FLAGS_API) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        power?.addThermalStatusListener(context.mainExecutor, thermal)
        if (Build.VERSION.SDK_INT >= CONTRAST_API) contrastListener = watchContrast()
        if (Build.VERSION.SDK_INT >= HIGH_CONTRAST_TEXT_API) highContrastListener = watchHighContrastText()
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        power?.removeThermalStatusListener(thermal)
        if (Build.VERSION.SDK_INT >= CONTRAST_API) unwatchContrast()
        if (Build.VERSION.SDK_INT >= HIGH_CONTRAST_TEXT_API) unwatchHighContrastText()
    }

    @RequiresApi(CONTRAST_API)
    private fun watchContrast(): Any {
        val listener = UiModeManager.ContrastChangeListener { onChange() }
        context.getSystemService(UiModeManager::class.java)?.addContrastChangeListener(context.mainExecutor, listener)
        return listener
    }

    @RequiresApi(CONTRAST_API)
    private fun unwatchContrast() {
        val listener = contrastListener as? UiModeManager.ContrastChangeListener ?: return
        context.getSystemService(UiModeManager::class.java)?.removeContrastChangeListener(listener)
    }

    @RequiresApi(HIGH_CONTRAST_TEXT_API)
    private fun watchHighContrastText(): Any {
        val listener = AccessibilityManager.HighContrastTextStateChangeListener { onChange() }
        context.getSystemService(AccessibilityManager::class.java)?.addHighContrastTextStateChangeListener(context.mainExecutor, listener)
        return listener
    }

    @RequiresApi(HIGH_CONTRAST_TEXT_API)
    private fun unwatchHighContrastText() {
        val listener = highContrastListener as? AccessibilityManager.HighContrastTextStateChangeListener ?: return
        context.getSystemService(AccessibilityManager::class.java)?.removeHighContrastTextStateChangeListener(listener)
    }
}

private const val RECEIVER_FLAGS_API = 33
private const val CONTRAST_API = 34
private const val HIGH_CONTRAST_TEXT_API = 36
