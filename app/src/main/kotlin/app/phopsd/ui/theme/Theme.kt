package app.phopsd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from docs/UI_DESIGN.md, tuned against design-reference.png. */
object PhoColors {
    val AppBackground = Color(0xFF0C111A)
    val Header = Color(0xFF151D2A)
    val Menu = Color(0xFF111A26)
    val Rail = Color(0xFF121B28)
    val Dock = Color(0xFF151F2C)
    val Raised = Color(0xFF1E2A3A)
    val RaisedHover = Color(0xFF243245)
    val Accent = Color(0xFF087CFF)
    val AccentHover = Color(0xFF2196FF)
    val AccentSoft = Color(0x29087CFF)
    val TextPrimary = Color(0xFFF4F7FC)
    val TextSecondary = Color(0xFF9DAABD)
    val TextDisabled = Color(0xFF5D697B)
    val Divider = Color(0xFF2A3544)
    val Pasteboard = Color(0xFF090D14)
    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFB547)
    val Success = Color(0xFF3CCB7F)

    /** Transparency checkerboard: conventional light neutrals so alpha reads unambiguously. */
    val CheckerLight = Color(0xFFEDEDED)
    val CheckerDark = Color(0xFFC9C9C9)
}

object PhoDimens {
    val HeaderHeight = 52.dp
    val MenuBarHeight = 30.dp
    val QuickStripHeight = 44.dp
    val RailWidth = 72.dp
    val StatusBarHeight = 24.dp
    val DockMinWidth = 264.dp
    val DockMaxWidth = 340.dp
    val Corner = 8.dp
    val SmallCorner = 6.dp
    /** Minimum interactive size; visuals may be smaller but hit areas are not. */
    val MinTouch = 40.dp
}

object PhoType {
    private val family = FontFamily.Default
    val Title = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = PhoColors.TextPrimary)
    val Body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = PhoColors.TextPrimary)
    val BodyMedium = Body.copy(fontWeight = FontWeight.Medium)
    val Label = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = PhoColors.TextSecondary)
    val Caption = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = PhoColors.TextSecondary)
    val Value = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = PhoColors.TextPrimary)
    val Badge = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = PhoColors.TextSecondary)
}

private val colorScheme = darkColorScheme(
    primary = PhoColors.Accent,
    onPrimary = Color.White,
    secondary = PhoColors.AccentHover,
    background = PhoColors.AppBackground,
    onBackground = PhoColors.TextPrimary,
    surface = PhoColors.Raised,
    onSurface = PhoColors.TextPrimary,
    surfaceVariant = PhoColors.Dock,
    onSurfaceVariant = PhoColors.TextSecondary,
    surfaceContainer = PhoColors.Raised,
    outline = PhoColors.Divider,
    error = PhoColors.Danger,
)

private val typography = Typography(
    bodyLarge = PhoType.Body.copy(fontSize = 14.sp),
    bodyMedium = PhoType.Body,
    bodySmall = PhoType.Caption,
    labelLarge = PhoType.BodyMedium,
    labelMedium = PhoType.Label,
    titleMedium = PhoType.Title,
)

@Composable
fun PhoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}
