package us.theworkmans.proseplow.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E5A96),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF545F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    background = Color(0xFFFAF9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFAF9FC),
    onSurface = Color(0xFF1A1C1E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3C9FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF004882),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    background = Color(0xFF111316),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111316),
    onSurface = Color(0xFFE2E2E6),
)

/** App theme: follows the system dark/light setting; dynamic colour on Android 12+ (API 31). */
@Composable
fun ProsePlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
