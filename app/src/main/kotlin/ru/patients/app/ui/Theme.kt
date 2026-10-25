package ru.patients.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Green = Color(0xFF2E8B57)
    val GreenDark = Color(0xFF1F6B42)
    val GreenSoft = Color(0xFFE3F4E8)
    val GreenMist = Color(0xFFF0F8F2)
    val Mint = Color(0xFFCDEBD7)
    val Bg = Color(0xFFF5FAF6)
    val Ink = Color(0xFF17251C)
    val InkSoft = Color(0xFF5C6E62)
    val Line = Color(0xFFDCE9E0)
    val Red = Color(0xFFC8463C)
    val RedSoft = Color(0xFFFBE7E4)
    val Amber = Color(0xFFB7791F)
    val AmberSoft = Color(0xFFFFF3DC)
}

private val scheme = lightColorScheme(
    primary = Palette.Green,
    onPrimary = Color.White,
    primaryContainer = Palette.GreenSoft,
    onPrimaryContainer = Palette.GreenDark,
    secondary = Palette.GreenDark,
    onSecondary = Color.White,
    secondaryContainer = Palette.Mint,
    onSecondaryContainer = Palette.GreenDark,
    tertiary = Palette.Amber,
    background = Palette.Bg,
    onBackground = Palette.Ink,
    surface = Color.White,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.GreenMist,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Palette.GreenMist,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Palette.GreenMist,
    outline = Color(0xFFB9CFC0),
    outlineVariant = Palette.Line,
    error = Palette.Red,
    errorContainer = Palette.RedSoft,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val base = Typography()
private val typography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
)

val SectionTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, color = Palette.InkSoft)

@Composable
fun PatientsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
}
