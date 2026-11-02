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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import ru.overtime.app.R
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

/** Скругления по важности: 20 — часы и главная карточка, 16 — карточки, 12 — кнопки и поля, 10 — ячейки табеля. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Golos Text (ParaType) — весь интерфейс. Лицензия OFL 1.1, файлы в res/font. */
val Golos = FontFamily(
    Font(R.font.golos_regular, FontWeight.Normal),
    Font(R.font.golos_medium, FontWeight.Medium),
    Font(R.font.golos_semibold, FontWeight.SemiBold),
    Font(R.font.golos_semibold, FontWeight.Bold),
)

/** Unbounded — табельные часы, суммы и заголовки разделов. Лицензия OFL 1.1. */
val Unbounded = FontFamily(
    Font(R.font.unbounded_medium, FontWeight.Medium),
    Font(R.font.unbounded_semibold, FontWeight.SemiBold),
    Font(R.font.unbounded_semibold, FontWeight.Bold),
)

private val AppTypography: Typography
    get() {
        val base = Typography()
        // Цифры одинаковой ширины: суммы и часы стоят ровной колонкой и не «прыгают» при обновлении.
        val tnum = "tnum"
        fun TextStyle.display() = copy(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum)
        fun TextStyle.ui(w: FontWeight? = null) = copy(fontFamily = Golos, fontWeight = w ?: fontWeight, fontFeatureSettings = tnum)
        return base.copy(
            displayLarge = base.displayLarge.display(),
            displayMedium = base.displayMedium.display(),
            displaySmall = base.displaySmall.copy(fontSize = 34.sp, lineHeight = 40.sp).display(),
            headlineLarge = base.headlineLarge.display(),
            headlineMedium = base.headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp).display(),
            headlineSmall = base.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp).display(),
            titleLarge = base.titleLarge.ui(FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontSize = 16.sp).ui(FontWeight.SemiBold),
            titleSmall = base.titleSmall.ui(FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp).ui(),
            bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp).ui(),
            bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp).ui(),
            labelLarge = base.labelLarge.ui(FontWeight.SemiBold),
            labelMedium = base.labelMedium.ui(FontWeight.Medium),
            labelSmall = base.labelSmall.ui(FontWeight.Medium),
        )
    }

/**
 * «Проходная»: пихта (часы и «к выплате»), сигнальный жёлтый (идёт время, работа в выходной),
 * бланк (фон), графит (текст), охра (выходные и сверхурочные часы в тексте).
 * Старые имена (Green, Weekend…) оставлены, чтобы весь код подхватил новые цвета.
 */
object Brand {
    val Pikhta = Color(0xFF123B33)
    val ClockTile = Color(0xFF0B2A24)
    val Signal = Color(0xFFF2B705)
    val Blank = Color(0xFFEEF0EA)
    val Graphite = Color(0xFF1C2422)
    val Ochre = Color(0xFF7A5200)
    val Muted = Color(0xFF4E5A55)
    val OnPikhtaMuted = Color(0xFF9FB8AE)

    val Green = Pikhta
    val GreenDark = ClockTile
    val GreenContainer = Color(0xFFD7E6DF)
    val OnHeroMuted = OnPikhtaMuted
    /** Столбики работы в выходной — сигнальный жёлтый (только графика, текст на нём тёмный). */
    val Weekend = Signal
    val WeekendText = Ochre
    val WeekendTextDark = Color(0xFFF2C94C)
    val Warning = Color(0xFFFFF3C4)
    val OnWarning = Ochre
    val Running = Signal
    val AtWork = Color(0xFF3FA36C)
}

/** Цвета отметок табеля: отпуск, больничный, отгул. */
object AbsenceColors {
    val VacationBg = Color(0xFFE3EBF7)
    val VacationFg = Color(0xFF2B4C8C)
    val SickBg = Color(0xFFF7E1DA)
    val SickFg = Color(0xFF8C2F1B)
    val DayOffBorder = Color(0xFF4E5A55)
}

private val LightBrand = lightColorScheme(
    primary = Brand.Pikhta,
    onPrimary = Brand.Blank,
    primaryContainer = Brand.GreenContainer,
    onPrimaryContainer = Brand.Pikhta,
    secondary = Brand.Muted,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E9E2),
    onSecondaryContainer = Brand.Graphite,
    tertiary = Brand.Ochre,
    onTertiary = Color.White,
    tertiaryContainer = Brand.Warning,
    onTertiaryContainer = Brand.Ochre,
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF7E1DA),
    onErrorContainer = Color(0xFF5F1410),
    background = Brand.Blank,
    onBackground = Brand.Graphite,
    surface = Brand.Blank,
    onSurface = Brand.Graphite,
    surfaceVariant = Color(0xFFE6E9E2),
    onSurfaceVariant = Brand.Muted,
    outline = Color(0xFF9AA59F),
    outlineVariant = Color(0xFFD8DDD5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE6E9E2),
    surfaceContainerHighest = Color(0xFFDDE1DA),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFA8D5C2),
    onPrimary = Brand.ClockTile,
    primaryContainer = Brand.Pikhta,
    onPrimaryContainer = Color(0xFFD7E6DF),
    secondary = Color(0xFFB3C2BB),
    onSecondary = Color(0xFF1F2E29),
    secondaryContainer = Color(0xFF26312D),
    onSecondaryContainer = Color(0xFFDDE4E1),
    tertiary = Color(0xFFF2C94C),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF4A3A0A),
    onTertiaryContainer = Color(0xFFFFE8A3),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF26312D),
    onSurfaceVariant = Color(0xFFB0BDB8),
    outline = Color(0xFF7D8A85),
    outlineVariant = Color(0xFF2E3935),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF151D1A),
    surfaceContainer = Color(0xFF17201D),
    surfaceContainerHigh = Color(0xFF1F2925),
    surfaceContainerHighest = Color(0xFF28332F),
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

/** Главная карточка (часы, сумма): всегда пихта с текстом цвета бланка, в светлой и тёмной теме. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Brand.Pikhta,
            contentColor = Brand.Blank,
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
fun heroMuted(): Color = Brand.OnPikhtaMuted

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

val CARD_SHAPE = RoundedCornerShape(16.dp)

/** Карточка: белый фон на бланке, без тени, скругление 16 dp. */
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
