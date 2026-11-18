package ai.verispec.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Holds the screen on at minimum brightness while composed, for the ON_DIMMED
 * screen policy, and restores the window when it leaves composition. Only works
 * while VeriSpec is in the foreground; the power timeline records whether the
 * screen actually stayed on.
 */
@Composable
fun KeepScreenOnDimmed(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val window = view.context.findActivity()?.window
        if (!active || window == null) return@DisposableEffect onDispose { }
        val previousBrightness = window.attributes.screenBrightness
        val previousKeepOn = view.keepScreenOn
        view.keepScreenOn = true
        window.attributes = window.attributes.apply { screenBrightness = MIN_BRIGHTNESS }
        onDispose {
            view.keepScreenOn = previousKeepOn
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
        }
    }
}

// Not 0: on some devices a zero override turns the backlight fully off, which is the OFF policy, not this one.
private const val MIN_BRIGHTNESS = 0.01f

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
