package ai.verispec.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Deliberately plain operator console theme. Content over chrome. */
private val Light = lightColorScheme(
    primary = Color(0xFF12333B),
    secondary = Color(0xFF3B6B5B),
    tertiary = Color(0xFF8A5A00),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FD3C7),
    secondary = Color(0xFFB0CFC2),
    tertiary = Color(0xFFF2C46B),
    error = Color(0xFFF2B8B5),
)

/** Semantic colours for coverage/thermal status; gaps must be as loud as coverage. */
object StatusColors {
    val satisfied = Color(0xFF2E7D32)
    val partial = Color(0xFFF9A825)
    val notCovered = Color(0xFFC62828)
    val neutral = Color(0xFF616161)
}

@Composable
fun VerispecTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
