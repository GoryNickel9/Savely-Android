package cc.savely.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFF52DD93), onPrimary = Color(0xFF00391E),
    secondary = Color(0xFFC4B5FD), onSecondary = Color(0xFF322052),
    background = Color(0xFF081020), onBackground = Color(0xFFEDF2FA),
    surface = Color(0xFF101C30), onSurface = Color(0xFFEDF2FA),
    surfaceVariant = Color(0xFF1E2C43), onSurfaceVariant = Color(0xFFBAC5D6),
    outline = Color(0xFF8895A8), error = Color(0xFFFFB4AB),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF006C3A), onPrimary = Color.White,
    secondary = Color(0xFF65419C), onSecondary = Color.White,
    background = Color(0xFFF4F6FA), onBackground = Color(0xFF172234),
    surface = Color.White, onSurface = Color(0xFF172234),
    surfaceVariant = Color(0xFFE5EBF2), onSurfaceVariant = Color(0xFF455165),
    outline = Color(0xFF68768A),
)

@Composable
fun SavelyTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val activity = LocalActivity.current
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
