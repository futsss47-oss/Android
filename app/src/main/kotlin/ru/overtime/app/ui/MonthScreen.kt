package ru.overtime.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.data.TimeConv
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.Report
import ru.overtime.domain.Settings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val ROW_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)

/** Страницы внутри вкладки «Месяц». */
private enum class MonthPage { MAIN, PAYSLIP, CALENDAR }

/** Минуты в 8-часовом дне: уровень пунктирной линии на графике. */
private const val EIGHT_HOURS = 8 * 60

/**
 * Вкладка «Месяц»: собирает данные из [AppViewModel] и показывает одну из трёх страниц —
 * главную, расчётный листок или календарь отсутствий. Системная «Назад» возвращает на главную.
 */
@Composable
fun MonthScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val month by vm.month.collectAsStateWithLifecycle()
    val calc by vm.monthCalc.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(MonthPage.MAIN) }
    var editing by remember { mutableStateOf<SessionEntity?>(null) }
    var noteFor by remember { mutableStateOf<SessionEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    BackHandler(enabled = page != MonthPage.MAIN) { page = MonthPage.MAIN }

    val monthSessions = remember(sessions, month) {
        sessions.filter { YearMonth.from(TimeConv.toLocal(it.startMillis)) == month }
    }

    when (page) {
        MonthPage.MAIN -> MonthMainPage(
            month = month,
            calc = calc,
            settings = settings,
            sessions = monthSessions,
            now = now,
            onMonthChange = { vm.month.value = it },
            onOpenPayslip = { page = MonthPage.PAYSLIP },
            onOpenCalendar = { page = MonthPage.CALENDAR },
            onAdd = { adding = true },
            onShare = { shareCsv(ctx, month.format(MONTH_FMT), calc, monthSessions) },
            onSessionClick = { s ->
                // у идущей записи меняется только заметка
                if (s.endMillis == null) {
                    noteFor = s
                } else {
                    editing = s
                }
            },
        )
        MonthPage.PAYSLIP -> PayslipPage(
            month = month,
            calc = calc,
            settings = settings,
            onBack = { page = MonthPage.MAIN },
            onSaveOverride = { o -> vm.saveMonthOverride(month, o) },
        )
        MonthPage.CALENDAR -> CalendarPage(
            month = month,
            calc = calc,
            settings = settings,
            onBack = { page = MonthPage.MAIN },
            onSetAbsence = { date, a -> vm.setAbsence(date, a) },
            onSetDayKind = { date, k -> vm.setDayKind(date, k) },
            today = now.toLocalDate(),
        )
    }

    if (adding) {
        SessionDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { start, end, note ->
                vm.addSession(start, end, note)
                adding = false
            },
            onDelete = null,
        )
    }
    editing?.let { s ->
        SessionDialog(
            initial = s,
            onDismiss = { editing = null },
            onSave = { start, end, note ->
                vm.updateSession(s, start, end, note)
                editing = null
            },
            onDelete = {
                vm.deleteSession(s.id)
                editing = null
            },
        )
    }
    noteFor?.let { s ->
        NoteDialog(
            initial = s.note,
            onDismiss = { noteFor = null },
            onSave = { note ->
                vm.setNote(s.id, note)
                noteFor = null
            },
        )
    }
}

/**
 * Главная страница вкладки «Месяц». Без ViewModel: данные и действия приходят параметрами.
 * [sessions] — записи только этого месяца.
 */
@Composable
fun MonthMainPage(
    month: YearMonth,
    calc: MonthCalc,
    settings: Settings,
    sessions: List<SessionEntity>,
    now: LocalDateTime,
    onMonthChange: (YearMonth) -> Unit,
    onOpenPayslip: () -> Unit,
    onOpenCalendar: () -> Unit,
    onAdd: () -> Unit,
    onShare: () -> Unit,
    onSessionClick: (SessionEntity) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { MonthHeader(month, onMonthChange) }
        item { MonthHeroCard(month, calc) }
        item { OvertimeChartCard(month, calc.report, settings, now.toLocalDate()) }
        item { MonthLinksCard(calc, onOpenPayslip, onOpenCalendar) }
        item { SessionsCard(sessions, now, onAdd, onShare, onSessionClick) }
    }
}

/** Шапка: ‹ Октябрь 2026 ›. */
@Composable
fun MonthHeader(month: YearMonth, onMonthChange: (YearMonth) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Предыдущий месяц") {
            onMonthChange(month.minusMonths(1))
        }
        Text(
            month.format(MONTH_FMT).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        RoundIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Следующий месяц") {
            onMonthChange(month.plusMonths(1))
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Зелёная карточка «На руки» с авансом и остатком. */
@Composable
fun MonthHeroCard(month: YearMonth, calc: MonthCalc) {
    val next = month.plusMonths(1)
    HeroCard {
        Text("На руки", style = MaterialTheme.typography.bodyMedium, color = heroMuted())
        MoneyText(
            calc.summary.net,
            big = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimary,
            mutedColor = heroMuted(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroTile("Аванс · 26–28 ${monthShortName(month)}", formatRub(calc.advance.net), Modifier.weight(1f))
            HeroTile("Остаток · 10–13 ${monthShortName(next)}", formatRub(calc.advance.rest), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = heroMuted(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Цвет столбиков выходных: оранжевый, в тёмной теме — третичный цвет схемы. */
@Composable
private fun weekendBarColor(): Color =
    if (LocalDarkTheme.current) MaterialTheme.colorScheme.tertiary else Brand.Weekend

/**
 * График «Сверхурочные и выходные»: столбики по дням, пунктир на уровне 8 ч,
 * нажатие на столбик выбирает день, под графиком — строка выбранного дня.
 */
@Composable
fun OvertimeChartCard(
    month: YearMonth,
    report: Report,
    settings: Settings,
    today: LocalDate = LocalDate.now(),
) {
    val days = month.lengthOfMonth()
    val byDay = report.days.filter { it.countedMinutes > 0 }.associateBy { it.date.dayOfMonth }
    val maxMinutes = byDay.values.maxOfOrNull { it.countedMinutes } ?: 0
    val maxDay = byDay.values.maxByOrNull { it.countedMinutes }?.date?.dayOfMonth
    var picked by remember(month) { mutableStateOf<Int?>(null) }
    val selected = (picked ?: maxDay ?: if (YearMonth.from(today) == month) today.dayOfMonth else 1).coerceIn(1, days)

    val weekdayColor = MaterialTheme.colorScheme.primary
    val weekendColor = weekendBarColor()
    val stubColor = MaterialTheme.colorScheme.surfaceVariant
    val ringColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val baseColor = MaterialTheme.colorScheme.outlineVariant
    // Масштаб: до 8 ч, если дни короче, иначе до самого длинного дня.
    val scale = maxOf(EIGHT_HOURS, maxMinutes)
    val chartHeight = 120.dp
    val topSpace = 20.dp
    val lineY = chartHeight - (chartHeight - topSpace) * (EIGHT_HOURS.toFloat() / scale)

    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Сверхурочные и выходные", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formatMinutes(report.totalMinutes), style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(chartHeight)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(month) {
                        detectTapGestures { pos ->
                            val i = (pos.x / size.width * days).toInt().coerceIn(0, days - 1)
                            picked = i + 1
                        }
                    },
            ) {
                val gap = 3.dp.toPx()
                val barW = ((size.width - gap * (days - 1)) / days).coerceAtLeast(1f)
                val usable = size.height - topSpace.toPx()
                val radius = minOf(4.dp.toPx(), barW / 2)
                val bottom = size.height

                drawLine(
                    color = baseColor,
                    start = Offset(0f, bottom - 0.5f),
                    end = Offset(size.width, bottom - 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
                val y8 = bottom - usable * EIGHT_HOURS / scale
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y8),
                    end = Offset(size.width, y8),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )

                for (d in 1..days) {
                    val x = (d - 1) * (barW + gap)
                    val day = byDay[d]
                    val isSelected = d == selected
                    if (day == null) {
                        // день без сверхурочной — короткая черта
                        val h = 3.dp.toPx()
                        drawRoundRect(
                            color = if (isSelected) ringColor.copy(alpha = 0.5f) else stubColor,
                            topLeft = Offset(x, bottom - h),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(minOf(radius, h / 2)),
                        )
                    } else {
                        val h = (usable * day.countedMinutes / scale).coerceAtLeast(4.dp.toPx())
                        val top = bottom - h
                        drawRoundRect(
                            color = if (day.dayOff) weekendColor else weekdayColor,
                            topLeft = Offset(x, top),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(radius),
                        )
                        if (isSelected) {
                            // выбранный столбик чуть темнее и обведён
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.15f),
                                topLeft = Offset(x, top),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(radius),
                            )
                            val pad = 2.dp.toPx()
                            drawRoundRect(
                                color = ringColor,
                                topLeft = Offset(x - pad, top - pad),
                                size = Size(barW + 2 * pad, h + pad),
                                cornerRadius = CornerRadius(radius + pad),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
            Text(
                "8 ч",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = lineY - 16.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (label in listOf(1, 10, 20, days)) {
                Text("$label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SelectedDayRow(month.atDay(selected), byDay[selected], settings, weekdayColor, weekendColor)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendItem(weekdayColor, "будни")
            LegendItem(weekendColor, "выходные и праздники")
        }
    }
}

@Composable
private fun SelectedDayRow(
    date: LocalDate,
    day: ru.overtime.domain.DayResult?,
    settings: Settings,
    weekdayColor: Color,
    weekendColor: Color,
) {
    val absence = settings.absences[date]
    val detail = when {
        day != null -> "${formatMinutes(day.countedMinutes)} · +${formatRub(day.net)}"
        absence != null -> absence.title.lowercase()
        !settings.isCalendarWorkday(date) -> "выходной"
        else -> "без сверхурочных"
    }
    val dot = when {
        day == null -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        day.dayOff -> weekendColor
        else -> weekdayColor
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(dot, RoundedCornerShape(3.dp)))
        Text(
            "${date.dayOfMonth} ${monthShortName(YearMonth.from(date))}, ${dayShort(date)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Hint(label)
    }
}

/** Карточка-ссылки на расчётный листок и календарь отсутствий. */
@Composable
fun MonthLinksCard(calc: MonthCalc, onOpenPayslip: () -> Unit, onOpenCalendar: () -> Unit) {
    val summary = calc.summary
    val calendarSubtitle = buildString {
        append("Отработано ${summary.workedDays} из ${summary.normDays} ${daysAfterOf(summary.normDays)}")
        if (summary.vacationPay > 0) append(", отпускные ≈ ${formatRub(summary.vacationPay)}")
        if (summary.sickPay > 0) append(", больничный ≈ ${formatRub(summary.sickPay)}")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        NavRow(
            "Расчёт как в листке",
            "Начислено ${formatRub(summary.gross)} · НДФЛ ${formatRub(summary.tax)}",
            onOpenPayslip,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        NavRow("Отпуск, больничный, отгул", calendarSubtitle, onOpenCalendar)
    }
}

/** Записи месяца: добавить, поделиться таблицей, список с отметками и заметками. */
@Composable
fun SessionsCard(
    sessions: List<SessionEntity>,
    now: LocalDateTime,
    onAdd: () -> Unit,
    onShare: () -> Unit,
    onSessionClick: (SessionEntity) -> Unit,
) {
    SoftCard {
        Text("Записи", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onAdd, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить", maxLines = 1)
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Поделиться", maxLines = 1)
            }
        }
        if (sessions.isEmpty()) Hint("Записей за этот месяц нет")
        for (s in sessions) SessionRow(s, now) { onSessionClick(s) }
    }
}

@Composable
private fun SessionRow(s: SessionEntity, now: LocalDateTime, onClick: () -> Unit) {
    val start = TimeConv.toLocal(s.startMillis)
    val end = s.endMillis?.let { TimeConv.toLocal(it) }
    val minutes = Duration.between(start, end ?: now).toMinutes().toInt().coerceAtLeast(0)
    val (badge, color) = when {
        end == null -> "идёт" to MaterialTheme.colorScheme.tertiaryContainer
        s.manual -> "вручную" to MaterialTheme.colorScheme.secondaryContainer
        s.edited -> "исправлена" to MaterialTheme.colorScheme.secondaryContainer
        else -> "авто" to MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${start.format(ROW_FMT)} – ${end?.format(TIME_FMT) ?: "…"}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Pill(badge, color)
            Spacer(Modifier.width(8.dp))
            Text(formatMinutes(minutes), style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
        }
        val note = s.note
        if (!note.isNullOrBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Поле заметки к записи. */
@Composable
private fun NoteField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Заметка") },
        placeholder = { Text("Кто попросил, что делали") },
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SessionDialog(
    initial: SessionEntity?,
    onDismiss: () -> Unit,
    onSave: (LocalDateTime, LocalDateTime, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val defaultEnd = remember { LocalDateTime.now().withSecond(0).withNano(0) }
    var start by remember {
        mutableStateOf(initial?.let { TimeConv.toLocal(it.startMillis) } ?: defaultEnd.minusHours(1))
    }
    var end by remember {
        mutableStateOf(initial?.endMillis?.let { TimeConv.toLocal(it) } ?: defaultEnd)
    }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    val valid = end.isAfter(start)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая запись" else "Правка записи") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickDateTime(ctx, start) { start = it } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Начало: ${start.format(DATE_TIME_FMT)}") }
                OutlinedButton(
                    onClick = { pickDateTime(ctx, end) { end = it } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Конец: ${end.format(DATE_TIME_FMT)}") }
                NoteField(note) { note = it }
                if (!valid) {
                    Text("Конец должен быть позже начала", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "Длительность: ${formatMinutes(Duration.between(start, end).toMinutes().toInt())}. " +
                            "Записи, созданные или исправленные вручную, считаются как есть, без обрезки по графику.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(start, end, note) }, enabled = valid) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** Заметка к идущей записи: время поправить можно после её окончания. */
@Composable
private fun NoteDialog(initial: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заметка к записи") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Запись ещё идёт — время можно поправить после её окончания.")
                NoteField(note) { note = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Текст заметки для CSV: без разделителей колонок и переносов строк. */
private fun csvText(s: String): String = s.replace(';', ',').replace('\n', ' ').replace('\r', ' ').trim()

private fun shareCsv(ctx: Context, title: String, calc: MonthCalc, sessions: List<SessionEntity>) {
    val report = calc.report
    val summary = calc.summary
    fun n(v: Double) = String.format(RU, "%.2f", v)
    // заметки записей, начавшихся в этот день
    val notesByDay: Map<LocalDate, String> = sessions
        .filter { !it.note.isNullOrBlank() }
        .groupBy { TimeConv.toLocal(it.startMillis).toLocalDate() }
        .mapValues { (_, list) -> list.sortedBy { it.startMillis }.joinToString(" / ") { csvText(it.note.orEmpty()) } }
    val csv = buildString {
        appendLine(
            "Дата;Минут;Часов;Выходной;Ночные, мин;По формуле, руб;Премия, руб;Начислено, руб;На руки, руб;Заметки",
        )
        for (d in report.days) {
            appendLine(
                "${d.date};${d.countedMinutes};${n(d.countedMinutes / 60.0)};" +
                    (if (d.dayOff) "да" else "нет") + ";${d.nightMinutes};${n(d.money)};${n(d.bonus)};" +
                    "${n(d.withBonus)};${n(d.net)};${notesByDay[d.date].orEmpty()}",
            )
        }
        appendLine(
            "Итого;${report.totalMinutes};${n(report.totalMinutes / 60.0)};;${report.totalNightMinutes};" +
                "${n(report.totalMoney)};${n(report.days.sumOf { it.bonus })};${n(report.totalWithBonus)};" +
                "${n(report.totalNet)};",
        )
        appendLine()
        appendLine("Норма часов;${n(summary.norm)}")
        appendLine("Ставка в час;${n(summary.rate)}")
        appendLine("Отработано дней;${summary.workedDays} из ${summary.normDays}")
        appendLine("Оклад за отработанное;${n(summary.oklad)}")
        appendLine("Коэффициент премии, %;${n(summary.bonusPercent)}")
        appendLine("База премии;${n(summary.bonusBase)}")
        appendLine("Премия;${n(summary.bonus)}")
        appendLine("Выходные и сверхурочные;${n(summary.extra)}")
        if (summary.night > 0) appendLine("Ночные;${n(summary.night)}")
        appendLine("Районный коэффициент;${n(summary.district)}")
        if (summary.vacationPay > 0) appendLine("Отпускные;${n(summary.vacationPay)}")
        if (summary.sickPay > 0) appendLine("Больничный;${n(summary.sickPay)}")
        appendLine("Начислено;${n(summary.gross)}")
        appendLine("НДФЛ;${n(summary.tax)}")
        appendLine("На руки;${n(summary.net)}")
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Сверхурочные, $title")
        .putExtra(Intent.EXTRA_TEXT, csv)
    ctx.startActivity(Intent.createChooser(send, "Поделиться таблицей"))
}
