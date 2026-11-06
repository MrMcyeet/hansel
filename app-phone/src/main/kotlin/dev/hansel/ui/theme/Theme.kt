package dev.hansel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Dark-first, restrained accents (DESIGN §10): teal for the active route, amber for locks, red for hazards/avoid. */
object HanselColors {
    val Ink = Color(0xFF0E1116)
    val Panel = Color(0xFF161B22)
    val PanelHi = Color(0xFF1F262F)
    val Line = Color(0xFF2A323D)
    val Text = Color(0xFFE8ECF1)
    val Muted = Color(0xFF8B96A5)
    val Teal = Color(0xFF3DDCB4)
    val TealDeep = Color(0xFF0B3B33)
    val Amber = Color(0xFFF5B547)
    val Red = Color(0xFFE5484D)
    val Alt = Color(0xFF7C8797)
}

private val scheme = darkColorScheme(
    primary = HanselColors.Teal,
    onPrimary = HanselColors.Ink,
    secondary = HanselColors.Amber,
    onSecondary = HanselColors.Ink,
    background = HanselColors.Ink,
    onBackground = HanselColors.Text,
    surface = HanselColors.Panel,
    onSurface = HanselColors.Text,
    surfaceVariant = HanselColors.PanelHi,
    onSurfaceVariant = HanselColors.Muted,
    outline = HanselColors.Line,
    error = HanselColors.Red,
)

private val type = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold, color = HanselColors.Text),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 15.sp),
    labelSmall = Typography().labelSmall.copy(color = HanselColors.Muted),
)

private val shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp))

@Composable
fun HanselTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}
