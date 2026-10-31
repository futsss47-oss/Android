package ru.overtime.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.app.data.TimeConv
import ru.overtime.app.location.Perms
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.Settings
import ru.overtime.domain.TrackerState
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)
private val MONTH_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL", RU)

/** Короткие названия месяцев в родительном падеже: «26–28 окт». */
private val MONTH_SHORT = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** Предупреждать, если ручной таймер идёт дольше стольких часов. */
private const val LONG_MANUAL_HOURS = 6

private const val UI_PREFS = "overtime_ui"
private const val KEY_SAMSUNG_TIP = "samsung_tip_seen"

private fun timer(minutes: Int): String = String.format(RU, "%d:%02d", minutes / 60, minutes % 60)

/** Часы с точностью до десятой: «14 ч», «14,3 ч». */
private fun hoursShort(minutes: Int): String = formatHours(Math.round(minutes / 6.0) / 10.0)

@Composable
fun HomeScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tracker by vm.tracker.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val calc by vm.homeCalc.collectAsStateWithLifecycle()

    val today = now.toLocalDate()
    val manualOpen = sessions.firstOrNull { it.manual && it.endMillis == null }
    val manualStart = manualOpen?.let { TimeConv.toLocal(it.startMillis) }
    val manualHours = manualStart?.let { Duration.between(it, now).toHours() } ?: 0L

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                now.format(HEADER_DATE).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Сверхурочные", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        PermissionsCard(onPermissionsChanged = { vm.refreshGeofence() })

        MonthHero(calc = calc, today = today)

        if (manualStart != null && manualHours >= LONG_MANUAL_HOURS) {
            WarningStrip("Таймер идёт уже $manualHours ч — не забыли остановить?")
        }

        ShiftCard(
            settings = settings,
            tracker = tracker,
            now = now,
            manualStart = manualStart,
            todayNet = calc.report.days.firstOrNull { it.date == today }?.net,
            onStart = { vm.manualStart() },
            onStop = { vm.manualStop() },
        )

        if (settings.salary <= 0.0) {
            SoftCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("Заполните оплату", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Впишите оклад в «Настройки → Оплата», иначе суммы не посчитаются.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Зелёная карточка: сколько придёт на руки за месяц, сверхурочные и ближайшая выплата. */
@Composable
private fun MonthHero(calc: MonthCalc, today: LocalDate) {
    val report = calc.report
    val summary = calc.summary
    val month = summary.month
    val muted = heroMuted()
    HeroCard {
        Text(
            "Ожидается на руки за ${month.format(MONTH_NAME)}",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        MoneyText(
            value = summary.net,
            big = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 50.sp),
            color = MaterialTheme.colorScheme.onPrimary,
            mutedColor = muted,
        )
        Text(
            "Начислено ${formatMoney(summary.gross)} · НДФЛ ${formatRub(summary.tax)}",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        val leaveParts = buildList {
            if (calc.leave.vacationPay > 0.0) add("вкл. отпускные ${formatRub(calc.leave.vacationPay)}")
            if (calc.leave.sickPay > 0.0) add("вкл. больничный ${formatRub(calc.leave.sickPay)}")
        }
        if (!calc.leave.isEmpty && leaveParts.isNotEmpty()) {
            Text(leaveParts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroCell(
                label = "Сверхурочные",
                value = "${hoursShort(report.totalMinutes)} · +${formatRub(report.totalNet)}",
                modifier = Modifier.weight(1f),
            )
            val sameMonth = YearMonth.from(today) == month
            if (!sameMonth || today.dayOfMonth <= 15) {
                HeroCell(
                    label = "Аванс 26–28 ${MONTH_SHORT[month.monthValue - 1]}",
                    value = formatRub(calc.advance.net),
                    modifier = Modifier.weight(1f),
                )
            } else {
                val next = month.plusMonths(1)
                HeroCell(
                    label = "Остаток 10–13 ${MONTH_SHORT[next.monthValue - 1]}",
                    value = formatRub(calc.advance.rest),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        report.formulaError?.let {
            Text(
                "Ошибка в формуле: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.errorContainer,
            )
        }
    }
}

@Composable
private fun HeroCell(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = heroMuted(), maxLines = 1)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Белая карточка состояния смены: идёт сверхурочная, на работе или нет. */
@Composable
private fun ShiftCard(
    settings: Settings,
    tracker: TrackerState,
    now: LocalDateTime,
    manualStart: LocalDateTime?,
    todayNet: Double?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val today = now.toLocalDate()
    val runningSince = listOfNotNull(tracker.openStart, manualStart).minOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                runningSince != null -> {
                    StatusTitle(
                        dot = Brand.Running,
                        title = "Идёт сверхурочная",
                        pill = if (manualStart != null) "вручную" else "авто",
                        pillColor = scheme.tertiaryContainer,
                    )
                    val minutes = Duration.between(runningSince, now).toMinutes().toInt().coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            timer(minutes),
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, lineHeight = 52.sp),
                            modifier = Modifier.alignByBaseline(),
                        )
                        val money = if (todayNet != null && todayNet > 0.0) " · ≈ +${formatRub(todayNet)}" else ""
                        Text(
                            "с ${runningSince.format(TIME_FMT)}$money",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                    if (manualStart != null) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.onSurface,
                                contentColor = scheme.surface,
                            ),
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(LocalContentColor.current),
                            )
                            Text("Остановить", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Hint("Закончится сама, когда уйдёте с работы.")
                    }
                }

                tracker.presenceSince != null -> {
                    val since = tracker.presenceSince!!
                    StatusTitle(
                        dot = Brand.AtWork,
                        title = "На работе с ${since.format(TIME_FMT)}",
                        pill = "авто · GPS",
                        pillColor = scheme.primaryContainer,
                    )
                    val rule = settings.ruleFor(today)
                    if (rule.workday) {
                        val shiftStart = today.atTime(rule.start)
                        val shiftEnd = today.atTime(rule.end)
                        val from = if (since.isAfter(shiftStart)) since else shiftStart
                        val total = Duration.between(from, shiftEnd).toMinutes()
                        val done = Duration.between(from, now).toMinutes()
                        val progress = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ShiftProgress(progress)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    from.format(TIME_FMT),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant,
                                )
                                Text(
                                    "Конец смены ${rule.end.format(TIME_FMT)} · дальше пойдёт сверхурочная",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        Hint("Сегодня выходной: всё время — сверхурочная.")
                    }
                }

                !settings.hasWorkPlace -> {
                    StatusTitle(dot = scheme.outline, title = "Место работы не задано")
                    Hint("Укажите его в «Настройки → Место работы», тогда учёт пойдёт сам.")
                }

                else -> {
                    StatusTitle(dot = scheme.outline, title = "Вы не на работе")
                    Hint("Учёт включится сам, когда придёте на работу.")
                }
            }

            if (manualStart == null) {
                FilledTonalButton(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        "Начать сверхурочную вручную",
                        modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            calendarHint(settings, today)?.let { Hint(it) }
        }
    }
}

/** Подсказка по производственному календарю на сегодня/завтра, если есть что сказать. */
private fun calendarHint(settings: Settings, today: LocalDate): String? {
    val tomorrow = today.plusDays(1)
    val tomorrowWeekday = tomorrow.dayOfWeek != DayOfWeek.SATURDAY && tomorrow.dayOfWeek != DayOfWeek.SUNDAY
    if (settings.isHoliday(tomorrow) && settings.dayHours(tomorrow) == 0 && tomorrowWeekday) {
        return "Завтра праздник: работа в этот день — ×2"
    }
    if (settings.dayHours(today) == 7) {
        val rule = settings.ruleFor(today)
        if (rule.workday) return "Сегодня сокращённый день: сверхурочная с ${rule.end.format(TIME_FMT)}"
    }
    return null
}

@Composable
private fun StatusTitle(dot: Color, title: String, pill: String? = null, pillColor: Color = Color.Unspecified) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(dot)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (pill != null) Pill(pill, pillColor)
    }
}

/** Полоса прогресса смены: трек и заполненная часть со скруглёнными краями. */
@Composable
private fun ShiftProgress(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
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
