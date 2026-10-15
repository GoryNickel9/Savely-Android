package cc.savely.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.savely.android.R

private fun hsl(hue: Float, saturation: Float, lightness: Float) = Color.hsl(hue, saturation / 100, lightness / 100)

private val DarkColors = darkColorScheme(
    primary = hsl(142f, 70f, 50f), onPrimary = Color(0xFF071F12),
    primaryContainer = hsl(142f, 45f, 15f), onPrimaryContainer = hsl(142f, 70f, 80f),
    secondary = hsl(262f, 80f, 60f), onSecondary = Color.White,
    secondaryContainer = hsl(222f, 30f, 16f), onSecondaryContainer = Color.White,
    tertiary = hsl(38f, 92f, 55f), onTertiary = Color(0xFF241600),
    background = hsl(222f, 47f, 6f), onBackground = Color.White,
    surface = hsl(222f, 40f, 10f), onSurface = Color.White,
    surfaceVariant = hsl(222f, 30f, 16f), onSurfaceVariant = hsl(220f, 10f, 75f),
    surfaceContainerLowest = hsl(222f, 47f, 6f), surfaceContainerLow = hsl(222f, 40f, 10f),
    surfaceContainer = hsl(222f, 40f, 10f), surfaceContainerHigh = hsl(222f, 40f, 10f),
    surfaceContainerHighest = hsl(222f, 40f, 10f), surfaceBright = hsl(222f, 30f, 16f),
    surfaceDim = hsl(222f, 47f, 6f), outline = hsl(220f, 10f, 65f), outlineVariant = hsl(222f, 25f, 22f),
    error = hsl(0f, 65f, 55f), onError = Color.Black,
    errorContainer = hsl(0f, 60f, 18f), onErrorContainer = hsl(0f, 80f, 85f),
    inverseSurface = hsl(220f, 20f, 97f), inverseOnSurface = hsl(220f, 25f, 10f),
)
private val LightColors = lightColorScheme(
    primary = hsl(142f, 70f, 45f), onPrimary = Color(0xFF071F12),
    primaryContainer = hsl(142f, 50f, 90f), onPrimaryContainer = Color(0xFF071F12),
    secondary = hsl(262f, 80f, 55f), onSecondary = Color.White,
    secondaryContainer = hsl(220f, 15f, 92f), onSecondaryContainer = hsl(220f, 25f, 10f),
    tertiary = hsl(38f, 92f, 50f), onTertiary = Color(0xFF241600),
    background = hsl(220f, 20f, 97f), onBackground = hsl(220f, 25f, 10f),
    surface = Color.White, onSurface = hsl(220f, 25f, 10f),
    surfaceVariant = hsl(220f, 15f, 92f), onSurfaceVariant = hsl(220f, 10f, 40f),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color.White,
    surfaceBright = Color.White, surfaceDim = hsl(220f, 20f, 97f),
    outline = hsl(220f, 10f, 45f), outlineVariant = hsl(220f, 15f, 88f),
    error = hsl(0f, 72f, 45f), onError = Color.White,
    errorContainer = hsl(0f, 80f, 95f), onErrorContainer = hsl(0f, 72f, 30f),
    inverseSurface = hsl(222f, 40f, 10f), inverseOnSurface = Color.White,
)

@OptIn(ExperimentalTextApi::class)
private fun family(resource: Int) = FontFamily(listOf(400, 500, 600, 700).map { weight ->
    Font(resource, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

private val Inter = family(R.font.inter)
private val SpaceGrotesk = family(R.font.space_grotesk)
private val defaults = Typography()
private val SavelyTypography = Typography(
    displayLarge = defaults.displayLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
    displayMedium = defaults.displayMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
    displaySmall = defaults.displaySmall.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
    headlineLarge = defaults.headlineLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
    headlineMedium = defaults.headlineMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
    titleLarge = defaults.titleLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
    titleMedium = defaults.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    titleSmall = defaults.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = Inter, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = Inter, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = defaults.bodySmall.copy(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = defaults.labelLarge.copy(fontFamily = Inter),
    labelMedium = defaults.labelMedium.copy(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = defaults.labelSmall.copy(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
)
private val SavelyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp),
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
    // The web's green stays intact; dark button text replaces its low-contrast white labels.
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors,
        typography = SavelyTypography, shapes = SavelyShapes, content = content)
}
