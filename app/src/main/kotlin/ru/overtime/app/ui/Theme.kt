package ru.overtime.app.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/** Скругления: поля ввода 12 dp, карточки 24 dp, главные карточки и диалоги 28 dp, кнопки «таблеткой». */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppTypography: Typography
    get() {
        val base = Typography()
        // Цифры одинаковой ширины: суммы и часы стоят ровной колонкой и не «прыгают» при обновлении.
        val tnum = "tnum"
        return base.copy(
            displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
            displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum),
            bodyLarge = base.bodyLarge.copy(fontFeatureSettings = tnum),
            bodyMedium = base.bodyMedium.copy(fontFeatureSettings = tnum),
            bodySmall = base.bodySmall.copy(fontFeatureSettings = tnum),
            labelLarge = base.labelLarge.copy(fontFeatureSettings = tnum),
            labelMedium = base.labelMedium.copy(fontFeatureSettings = tnum),
            labelSmall = base.labelSmall.copy(fontFeatureSettings = tnum),
        )
    }

/**
 * Фирменные цвета: глубокий зелёный («деньги»), фон чуть зеленоватый серый, выходные оранжевые.
 * Цвета отметок календаря отличаются и оттенком, и светлотой.
 */
object Brand {
    val Green = Color(0xFF0F6E56)
    val GreenDark = Color(0xFF0A3D30)
    val GreenContainer = Color(0xFFDDF1E8)
    val OnHeroMuted = Color(0xFFCFEDE2)
    val Weekend = Color(0xFFE0782B)
    val WeekendText = Color(0xFFA34A0F)
    val WeekendTextDark = Color(0xFFFFB98A)
    val Warning = Color(0xFFFFF1D6)
    val OnWarning = Color(0xFF5C3D00)
    val Running = Color(0xFFE0782B)
    val AtWork = Color(0xFF1F9D55)
}

/** Цвета отметок календаря и их подписи. */
object AbsenceColors {
    val VacationBg = Color(0xFFD6E4F7)
    val VacationFg = Color(0xFF1D4F8C)
    val SickBg = Color(0xFFFBDDC4)
    val SickFg = Color(0xFF7A3B0C)
    val DayOffBorder = Color(0xFF7A8783)
}

private val LightBrand = lightColorScheme(
    primary = Brand.Green,
    onPrimary = Color.White,
    primaryContainer = Brand.GreenContainer,
    onPrimaryContainer = Brand.GreenDark,
    secondary = Color(0xFF4C6359),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EBE7),
    onSecondaryContainer = Color(0xFF12201C),
    tertiary = Brand.Weekend,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE6D4),
    onTertiaryContainer = Color(0xFF6B3A10),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFCE4E1),
    onErrorContainer = Color(0xFF5F1410),
    background = Color(0xFFF3F5F4),
    onBackground = Color(0xFF12201C),
    surface = Color(0xFFF3F5F4),
    onSurface = Color(0xFF12201C),
    surfaceVariant = Color(0xFFE6EBE9),
    onSurfaceVariant = Color(0xFF55655F),
    outline = Color(0xFF7A8783),
    outlineVariant = Color(0xFFDCE2DF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF3F5F4),
    surfaceContainerHighest = Color(0xFFE6EBE9),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFF7FD6B6),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F6E56),
    onPrimaryContainer = Color(0xFFDDF1E8),
    secondary = Color(0xFFB3CCC1),
    onSecondary = Color(0xFF1F352C),
    secondaryContainer = Color(0xFF2C3E37),
    onSecondaryContainer = Color(0xFFDCE9E3),
    tertiary = Color(0xFFFFB98A),
    onTertiary = Color(0xFF4F2500),
    tertiaryContainer = Color(0xFF6B3A10),
    onTertiaryContainer = Color(0xFFFFDCC5),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF2A322F),
    onSurfaceVariant = Color(0xFFB0BDB8),
    outline = Color(0xFF8A9893),
    outlineVariant = Color(0xFF3A4440),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF17201D),
    surfaceContainer = Color(0xFF1A2420),
    surfaceContainerHigh = Color(0xFF222C29),
    surfaceContainerHighest = Color(0xFF2C3733),
)

/** true, если сейчас тёмная тема: нужно для цветов, которых нет в схеме (выходные, отметки). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Тема приложения: фирменные цвета, светлая или тёмная по системе.
 * [dynamic] = true включает цвета из обоев (Material You, Android 12+), это настройка «Оформление».
 */
@Composable
fun AppTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> DarkBrand
        else -> LightBrand
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
    }
}

/** Цвет текста выходных и праздников в календаре. */
@Composable
fun weekendTextColor(): Color = if (LocalDarkTheme.current) Brand.WeekendTextDark else Brand.WeekendText

/** Главная карточка с суммой: залита основным цветом, текст белый. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/** Приглушённый текст на [HeroCard]. */
@Composable
fun heroMuted(): Color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)

/**
 * Сумма «рубли крупно, копейки мельче»: **65 635**,40 ₽.
 * [big] — стиль рублей; копейки и знак рубля — вдвое мельче и приглушённо.
 */
@Composable
fun MoneyText(
    value: Double,
    big: TextStyle,
    color: Color = LocalContentColor.current,
    mutedColor: Color = color.copy(alpha = 0.78f),
    modifier: Modifier = Modifier,
) {
    val text = formatMoney(value) // «65 635,40 ₽»
    val comma = text.lastIndexOf(',')
    val rub = if (comma > 0) text.substring(0, comma) else text.removeSuffix(" ₽")
    val rest = if (comma > 0) text.substring(comma) else " ₽"
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(rub) }
            withStyle(
                SpanStyle(
                    color = mutedColor,
                    fontSize = big.fontSize * 0.5f,
                    fontWeight = FontWeight.SemiBold,
                ),
            ) { append(rest) }
        },
        style = big.copy(letterSpacing = (-0.5).sp),
        maxLines = 1,
        modifier = modifier,
    )
}

/** Плашка-предупреждение (жёлтая строка) с действием справа. */
@Composable
fun WarningStrip(text: String, action: String? = null, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Brand.Warning,
        contentColor = Brand.OnWarning,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Строка-ссылка в карточке: заголовок, подпись и стрелка вправо. */
@Composable
fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Hint(subtitle)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

val CARD_SHAPE = RoundedCornerShape(24.dp)

/** Мягкая карточка: тональный фон без тени, скругление 24 dp. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Сворачиваемая группа настроек. */
@Composable
fun SettingsGroup(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                modifier = Modifier.rotate(angle),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/** Маленькая плашка-метка («авто», «вручную», «идёт»). */
@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(shape = CircleShape, color = container) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Цветная точка-индикатор состояния. */
@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

/** Подсказка мелким серым текстом. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
