package ru.overtime.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.overtime.app.data.TimeConv
import ru.overtime.app.location.Perms
import ru.overtime.domain.Absence
import ru.overtime.domain.Leave
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.Settings
import ru.overtime.domain.TrackerState
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)

/** Название месяца в именительном падеже, со строчной: «октябрь». */
private val MONTH_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL", RU)

/** Предупреждать, если ручной таймер идёт дольше стольких часов. */
private const val LONG_MANUAL_HOURS = 6

private const val UI_PREFS = "overtime_ui"
private const val KEY_SAMSUNG_TIP = "samsung_tip_seen"

/** Размеры перекидной плитки часов по макету; на узких экранах плитки уменьшаются пропорционально. */
private val TILE_WIDTH = 62.dp
private const val TILE_RATIO = 84f / 62f
private const val DIGIT_RATIO = 48f / 62f
private val TILE_GAP = 6.dp
private val COLON_WIDTH = 14.dp
private val SECONDS_WIDTH = 28.dp

/** Длительность смены цифры. */
private const val FLIP_MS = 220

/** Часы с точностью до десятой: «14 ч», «14,3 ч». */
private fun hoursShort(minutes: Int): String = formatHours(Math.round(minutes / 6.0) / 10.0)

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

private fun pad2(n: Long): String = n.toString().padStart(2, '0')

/** Включены ли системные анимации (в «Специальных возможностях» их можно отключить). */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember {
        val scale = try {
            AndroidSettings.Global.getFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (_: Exception) {
            1f
        }
        scale != 0f
    }
}

/**
 * Вкладка «Проходная»: табельные часы, сумма к выплате за месяц и мини-табель.
 * [onOpenListok] открывает вкладку «Листок», [onOpenTabel] — «Табель».
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenListok: () -> Unit = {},
    onOpenTabel: () -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tracker by vm.tracker.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val calc by vm.homeCalc.collectAsStateWithLifecycle()

    val today = now.toLocalDate()
    val manualOpen = sessions.firstOrNull { it.manual && it.endMillis == null }
    val manualStart = manualOpen?.let { TimeConv.toLocal(it.startMillis) }
    val manualHours = manualStart?.let { Duration.between(it, now).toHours() } ?: 0L
    val rule = settings.ruleFor(today)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                now.format(HEADER_DATE).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            Text(
                if (rule.workday) "смена до ${rule.end.format(TIME_FMT)}" else "сегодня выходной",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }

        PermissionsCard(onPermissionsChanged = { vm.refreshGeofence() })

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeClock(
                settings = settings,
                tracker = tracker,
                today = today,
                manualStart = manualStart,
                todayNet = calc.report.days.firstOrNull { it.date == today }?.net,
                onStart = { vm.manualStart() },
                onStop = { vm.manualStop() },
            )
            calendarHint(settings, today)?.let {
                Box(Modifier.padding(horizontal = 2.dp)) { Hint(it) }
            }
        }

        if (manualStart != null && manualHours >= LONG_MANUAL_HOURS) {
            WarningStrip("Таймер идёт уже $manualHours ч, не забыли остановить?")
        }

        PayoutBlock(calc = calc, onOpen = onOpenListok)

        MiniTabel(calc = calc, settings = settings, today = today, onOpen = onOpenTabel)

        if (settings.salary <= 0.0) {
            SoftCard {
                Text("Заполните оплату", style = MaterialTheme.typography.titleMedium)
                Hint("Впишите оклад в «Настройки → Оплата», иначе суммы не посчитаются.")
            }
        }
    }
}

/**
 * Табельные часы: лампа и состояние, перекидные цифры ЧЧ:ММ, подсказка и большая кнопка.
 * Пока идёт сверхурочная, секунды тикают по собственному таймеру экрана.
 */
@Composable
private fun TimeClock(
    settings: Settings,
    tracker: TrackerState,
    today: LocalDate,
    manualStart: LocalDateTime?,
    todayNet: Double?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val animate = rememberAnimationsEnabled()
    val runningSince = listOfNotNull(tracker.openStart, manualStart).minOrNull()
    val running = runningSince != null

    // Свой таймер раз в секунду — только пока идёт сверхурочная, иначе экран не перерисовывается зря.
    var tick by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(runningSince) {
        if (runningSince != null) {
            while (true) {
                tick = LocalDateTime.now()
                delay(1000L)
            }
        }
    }
    val elapsed = if (runningSince != null) Duration.between(runningSince, tick).seconds.coerceAtLeast(0L) else 0L
    val hours = (elapsed / 3600).coerceAtMost(99L)
    val minutes = (elapsed % 3600) / 60
    val seconds = elapsed % 60

    val rule = settings.ruleFor(today)
    val presence = tracker.presenceSince
    val lamp = when {
        running -> Brand.Signal
        presence != null -> Brand.AtWork
        else -> Brand.OnPikhtaMuted
    }
    val title = when {
        running -> "Идёт сверхурочная"
        presence != null -> "На работе с ${presence.format(TIME_FMT)}"
        !settings.hasWorkPlace -> "Место работы не задано"
        else -> "Вы не на работе"
    }
    val hint = when {
        runningSince != null -> {
            val money = if (todayNet != null && todayNet > 0.5) ", это примерно +${formatRub(todayNet)} на руки" else ""
            val end = if (manualStart != null) {
                "Нажмите «Закончить», когда уйдёте."
            } else {
                "Остановится сама, когда выйдете с проходной."
            }
            "С ${runningSince.format(TIME_FMT)}$money. $end"
        }
        presence != null && rule.workday ->
            "Сверхурочная начнётся сама в ${rule.end.format(TIME_FMT)}. Если геолокация подвела, отметьтесь вручную."
        presence != null -> "Сегодня выходной: всё время на работе — сверхурочное."
        !settings.hasWorkPlace -> "Укажите его в «Настройки → Место работы», тогда учёт пойдёт сам."
        else -> "Учёт включится сам, когда придёте на работу."
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brand.Pikhta)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Lamp(color = lamp, pulse = running && animate)
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Brand.Blank,
            )
        }

        FlipDigits(
            hours = if (running) hours else 0L,
            minutes = if (running) minutes else 0L,
            seconds = if (running) seconds else null,
            animate = animate,
            description = if (running) "Сверхурочная идёт $hours ч $minutes мин" else "Сверхурочная не идёт",
        )

        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = Brand.OnPikhtaMuted,
        )

        if (manualStart == null) {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Signal, contentColor = Brand.Graphite),
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Brand.Graphite,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Отметиться вручную", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Blank, contentColor = Brand.Graphite),
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brand.Graphite),
                )
                Spacer(Modifier.width(10.dp))
                Text("Закончить сверхурочную", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** «Лампа» состояния, 10 dp. При [pulse] вокруг неё мягко расходится кольцо. */
@Composable
private fun Lamp(color: Color, pulse: Boolean) {
    val halo = if (pulse) {
        val transition = rememberInfiniteTransition(label = "lamp")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "lampHalo",
        )
        progress
    } else {
        null
    }
    Box(
        Modifier
            .size(10.dp)
            .drawBehind {
                if (halo != null) {
                    drawCircle(
                        color = color.copy(alpha = 0.55f * (1f - halo)),
                        radius = size.minDimension / 2f + 7.dp.toPx() * halo,
                    )
                }
                drawCircle(color = color)
            },
    )
}

/**
 * Перекидные цифры ЧЧ:ММ и секунды справа ([seconds] = null — секунды не показываются).
 * Плитки уменьшаются, если не помещаются по ширине (экран 360 dp и крупный шрифт).
 */
@Composable
private fun FlipDigits(hours: Long, minutes: Long, seconds: Long?, animate: Boolean, description: String) {
    val hh = pad2(hours)
    val mm = pad2(minutes)
    val density = LocalDensity.current
    val secondsWidth = SECONDS_WIDTH * density.fontScale.coerceIn(1f, 2f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val fixed = COLON_WIDTH + secondsWidth + TILE_GAP * 5
        val tileWidth: Dp = ((maxWidth - fixed) / 4).coerceIn(32.dp, TILE_WIDTH)
        val tileHeight = tileWidth * TILE_RATIO
        // Цифры привязаны к размеру плитки, а не к настройке шрифта: иначе крупный шрифт вылезет за плитку.
        val digitSize = with(density) { (tileWidth * DIGIT_RATIO).toSp() }
        val digitStyle = TextStyle(
            fontFamily = Unbounded,
            fontWeight = FontWeight.SemiBold,
            fontSize = digitSize,
            lineHeight = digitSize,
            fontFeatureSettings = "tnum",
            color = Brand.Blank,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            FlipTile(hh[0], tileWidth, tileHeight, digitStyle, animate)
            FlipTile(hh[1], tileWidth, tileHeight, digitStyle, animate)
            Column(
                Modifier.width(COLON_WIDTH),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tileHeight / 6),
            ) {
                Box(Modifier.size(7.dp).background(Brand.OnPikhtaMuted, CircleShape))
                Box(Modifier.size(7.dp).background(Brand.OnPikhtaMuted, CircleShape))
            }
            FlipTile(mm[0], tileWidth, tileHeight, digitStyle, animate)
            FlipTile(mm[1], tileWidth, tileHeight, digitStyle, animate)
            Box(Modifier.width(secondsWidth).height(tileHeight), contentAlignment = Alignment.BottomStart) {
                if (seconds != null) {
                    Text(
                        pad2(seconds),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Brand.OnPikhtaMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Одна плитка: тёмный фон, цифра и линия-разрез посередине. Новая цифра въезжает снизу. */
@Composable
private fun FlipTile(digit: Char, width: Dp, height: Dp, style: TextStyle, animate: Boolean) {
    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(10.dp))
            .background(Brand.ClockTile),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = digit,
            transitionSpec = {
                if (animate) {
                    (slideInVertically(tween(FLIP_MS)) { it } + fadeIn(tween(FLIP_MS))) togetherWith
                        (slideOutVertically(tween(FLIP_MS)) { -it } + fadeOut(tween(FLIP_MS)))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            contentAlignment = Alignment.Center,
            label = "digit",
        ) { d ->
            Text(d.toString(), style = style, maxLines = 1, softWrap = false)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Brand.Pikhta),
        )
    }
}

/** «К выплате за октябрь»: сумма на руки и часть за сверхурочные; нажатие открывает «Листок». */
@Composable
private fun PayoutBlock(calc: MonthCalc, onOpen: () -> Unit) {
    val summary = calc.summary
    val report = calc.report
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Открыть листок", role = Role.Button, onClick = onOpen)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("К выплате за ${summary.month.format(MONTH_NAME)}", style = small, color = scheme.onSurfaceVariant)
        MoneyText(
            value = summary.net,
            big = MaterialTheme.typography.displaySmall,
            color = scheme.onSurface,
            mutedColor = scheme.onSurfaceVariant,
        )
        if (report.totalNet > 0.5) {
            Text(
                "из них за сверхурочные и выходные ${formatRub(report.totalNet)}",
                style = small,
                color = scheme.onSurfaceVariant,
            )
        }
        val leaveParts = buildList {
            if (calc.leave.vacationPay > 0.5) add("отпускные ${formatRub(calc.leave.vacationPay)}")
            if (calc.leave.sickPay > 0.5) add("больничный ${formatRub(calc.leave.sickPay)}")
        }
        if (leaveParts.isNotEmpty()) {
            Text("включая ${leaveParts.joinToString(", ")}", style = small, color = scheme.onSurfaceVariant)
        }
        report.formulaError?.let {
            Text("Ошибка в формуле: $it", style = small, color = scheme.error)
        }
    }
}

/** Отметка дня на полоске мини-табеля. */
private enum class StripMark { FUTURE, PRESENT, WEEKEND, WEEKEND_WORK, OVERTIME, VACATION, SICK, DAY_OFF }

/** Мини-табель: полоска по дням месяца; нажатие открывает «Табель». */
@Composable
private fun MiniTabel(calc: MonthCalc, settings: Settings, today: LocalDate, onOpen: () -> Unit) {
    val month = calc.summary.month
    val worked = calc.report.days.filter { it.countedMinutes > 0 }.associateBy { it.date }
    val marks = (1..month.lengthOfMonth()).map { day ->
        val date = month.atDay(day)
        val result = worked[date]
        when {
            date.isAfter(today) -> StripMark.FUTURE
            result != null && result.dayOff -> StripMark.WEEKEND_WORK
            result != null -> StripMark.OVERTIME
            Leave.isLeaveDay(date, settings, Absence.SICK) -> StripMark.SICK
            Leave.isLeaveDay(date, settings, Absence.VACATION) -> StripMark.VACATION
            settings.absences[date] == Absence.DAY_OFF -> StripMark.DAY_OFF
            settings.isCalendarWorkday(date) && settings.absences[date] == null -> StripMark.PRESENT
            else -> StripMark.WEEKEND
        }
    }
    // Явка — отработанный по календарю день (со сверхурочной или без), работа в выходной считается отдельно.
    val presence = marks.count { it == StripMark.PRESENT || it == StripMark.OVERTIME }
    val overtime = hoursShort(calc.report.totalMinutes)
    val presenceText = "$presence ${plural(presence, "явка", "явки", "явок")}"
    val monthName = month.format(MONTH_NAME)

    val dark = LocalDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val presentColor = if (dark) Color(0xFF3A4A44) else Color(0xFFC9D6CF)
    val weekendColor = if (dark) Color(0xFF2A3531) else Color(0xFFE2E5DE)
    val vacationColor = if (dark) Color(0xFF3B5078) else Color(0xFFBFD1EC)
    val sickColor = if (dark) Color(0xFF6E3D31) else Color(0xFFF2C4B6)
    val overtimeColor = if (dark) Color(0xFFA8D5C2) else Brand.Pikhta
    val barShape = RoundedCornerShape(3.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = "Табель за $monthName: $presenceText, $overtime сверхурочных"
                role = Role.Button
                onClick(label = "Открыть табель") {
                    onOpen()
                    true
                }
            }
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Табель",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$presenceText, $overtime сверх",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = scheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val dayOffBorder = scheme.onSurfaceVariant
            for (mark in marks) {
                val base = Modifier.weight(1f).height(22.dp)
                val bar = when (mark) {
                    StripMark.FUTURE -> base.border(1.dp, scheme.outlineVariant, barShape)
                    StripMark.DAY_OFF -> base
                        .clip(barShape)
                        .background(scheme.surfaceContainer)
                        .drawBehind {
                            val w = 1.5.dp.toPx()
                            drawRoundRect(
                                color = dayOffBorder,
                                topLeft = Offset(w / 2f, w / 2f),
                                size = Size(size.width - w, size.height - w),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                                style = Stroke(
                                    width = w,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                                ),
                            )
                        }
                    else -> {
                        val color = when (mark) {
                            StripMark.PRESENT -> presentColor
                            StripMark.WEEKEND_WORK -> Brand.Signal
                            StripMark.OVERTIME -> overtimeColor
                            StripMark.VACATION -> vacationColor
                            StripMark.SICK -> sickColor
                            else -> weekendColor
                        }
                        base.clip(barShape).background(color)
                    }
                }
                Box(bar)
            }
        }
    }
}

/** Подсказка по производственному календарю на сегодня/завтра, если есть что сказать. */
private fun calendarHint(settings: Settings, today: LocalDate): String? {
    val tomorrow = today.plusDays(1)
    val tomorrowWeekday = tomorrow.dayOfWeek != DayOfWeek.SATURDAY && tomorrow.dayOfWeek != DayOfWeek.SUNDAY
    if (settings.isHoliday(tomorrow) && settings.dayHours(tomorrow) == 0 && tomorrowWeekday) {
        return "Завтра праздник: работа ×2"
    }
    if (settings.dayHours(today) == 7) {
        val rule = settings.ruleFor(today)
        if (rule.workday) return "Сегодня сокращённый день: сверхурочная с ${rule.end.format(TIME_FMT)}"
    }
    return null
}

private fun openAppDetails(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ctx.packageName, null)),
        )
    } catch (_: Exception) {
        // На некоторых прошивках экрана может не быть — просто ничего не делаем.
    }
}

/**
 * Подсказки, как не дать системе усыпить фоновую работу (Samsung и другие).
 * Используется в «Настройках».
 */
@Composable
fun BackgroundWorkTips() {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "Samsung: «О приложении → Батарея» → «Без ограничений». " +
                "В «Батарея → Ограничения в фоновом режиме» отключите усыпление неиспользуемых приложений.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openAppDetails(ctx) }) { Text("О приложении") }
            OutlinedButton(onClick = {
                try {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    openAppDetails(ctx)
                }
            }) { Text("Батарея") }
        }
    }
}

/**
 * Разрешения: если чего-то не хватает — красная карточка с кнопками.
 * Если всё выдано — на телефонах Samsung один раз показывает жёлтую подсказку про батарею.
 */
@Composable
fun PermissionsCard(onPermissionsChanged: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val fine = remember(tick) { Perms.hasFine(ctx) }
    val background = remember(tick) { Perms.hasBackground(ctx) }
    val notifications = remember(tick) { Perms.hasNotifications(ctx) }

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        onPermissionsChanged()
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        onPermissionsChanged()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
    }

    if (fine && background && notifications) {
        SamsungTip()
        return
    }

    SoftCard(container = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "Нужны разрешения",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            if (fine && !background) {
                "Выберите «Разрешать всегда», иначе учёт не работает, когда приложение закрыто."
            } else {
                "Без них приложение не заметит, что вы на работе."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (!fine) {
            Button(
                onClick = {
                    fineLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Точная геолокация") }
        } else if (!background) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 29) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Геолокация «Всегда»") }
        }
        if (!notifications && Build.VERSION.SDK_INT >= 33) {
            FilledTonalButton(
                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Уведомления") }
        }
    }
}

/** Жёлтая строка про батарею Samsung: показывается, пока её не открыли. */
@Composable
private fun SamsungTip() {
    if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(prefs.getBoolean(KEY_SAMSUNG_TIP, false)) }
    if (seen) return
    WarningStrip("Samsung может усыплять фоновую работу", "Настроить") {
        prefs.edit().putBoolean(KEY_SAMSUNG_TIP, true).apply()
        seen = true
        openAppDetails(ctx)
    }
}
