package dev.normal99.cockpit.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.normal99.cockpit.claude.Tone

private val CockpitBlue = Color(0xFF0066CC)

@Composable
fun CockpitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF73BCF7))
        else -> lightColorScheme(primary = CockpitBlue)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Label colours in the spirit of PatternFly's (the web page's) green/red/orange/blue/purple labels. */
@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    fun pair(light: Long, lightFg: Long, darkBg: Long, darkFg: Long) =
        if (dark) Color(darkBg) to Color(darkFg) else Color(light) to Color(lightFg)
    return when (tone) {
        Tone.GREEN -> pair(0xFFE9F7DF, 0xFF204D00, 0xFF1E3A12, 0xFFAFDC8F)
        Tone.RED -> pair(0xFFFCE3E3, 0xFF7D1007, 0xFF4A1512, 0xFFF9B9B5)
        Tone.ORANGE -> pair(0xFFFFF0D9, 0xFF73480B, 0xFF4A3410, 0xFFF8C77E)
        Tone.BLUE -> pair(0xFFE0F0FF, 0xFF003D7A, 0xFF12304D, 0xFF9CCBF5)
        Tone.PURPLE -> pair(0xFFF2E9FB, 0xFF3C1F66, 0xFF2E2140, 0xFFD2B8F1)
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
