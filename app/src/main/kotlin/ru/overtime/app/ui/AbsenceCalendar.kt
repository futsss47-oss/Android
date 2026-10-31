package ru.overtime.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.overtime.domain.Absence
import ru.overtime.domain.DayKind
import ru.overtime.domain.Leave
import ru.overtime.domain.LeavePay
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.ProductionCalendar
import ru.overtime.domain.Settings
import ru.overtime.domain.SickLimit
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val KIND_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)

/** Что ставится нажатием на день. [absence] = null — «работал», стирает отметку. */
private enum class CalendarBrush(val label: String, val absence: Absence?) {
    VACATION("Отпуск", Absence.VACATION),
    SICK("Больн.", Absence.SICK),
    DAY_OFF("Отгул", Absence.DAY_OFF),
    WORKED("Работал", null),
}

/** Пунктирная окружность по краю элемента (отгул). */
private fun Modifier.dashedCircle(color: Color, width: Dp): Modifier = drawBehind {
    val w = width.toPx()
    drawCircle(
        color = color,
        radius = size.minDimension / 2 - w / 2,
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))),
    )
}

/** Цветной кружок-образец типа отметки. */
@Composable
private fun BrushSwatch(brush: CalendarBrush, size: Dp = 12.dp) {
    val base = Modifier.size(size).clip(CircleShape)
    when (brush) {
        CalendarBrush.VACATION -> Box(base.background(AbsenceColors.VacationBg))
        CalendarBrush.SICK -> Box(base.background(AbsenceColors.SickBg))
        CalendarBrush.DAY_OFF -> Box(
            base.background(MaterialTheme.colorScheme.surfaceContainer)
                .dashedCircle(AbsenceColors.DayOffBorder, 1.5.dp),
        )
        CalendarBrush.WORKED -> Box(base.background(MaterialTheme.colorScheme.primary))
    }
}

/**
 * Подстраница «Отпуск, больничный, отгул»: выбираете тип и нажимаете на дни месяца.
 * Долгое нажатие на день меняет его тип в производственном календаре.
 * Без ViewModel: изменения уходят в [onSetAbsence] и [onSetDayKind].
 */
@Composable
fun CalendarPage(
    month: YearMonth,
    calc: MonthCalc,
    settings: Settings,
    onBack: () -> Unit,
    onSetAbsence: (LocalDate, Absence?) -> Unit,
    onSetDayKind: (LocalDate, DayKind?) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    var brush by rememberSaveable { mutableStateOf(CalendarBrush.VACATION) }
    var kindFor by remember { mutableStateOf<LocalDate?>(null) }

    Column(Modifier.fillMaxSize()) {
        MonthPageHeader("Отсутствия · ${monthNameLower(month)}", onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Что отмечаем — потом нажимайте на дни")
                BrushSelector(brush) { brush = it }
            }
            MonthGridCard(
                month = month,
                calc = calc,
                settings = settings,
                today = today,
                onClick = { date -> applyBrush(date, brush, settings, onSetAbsence) },
                onLongClick = { kindFor = it },
            )
            Column(
                Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CalendarLegend()
                Hint("Долгое нажатие на день — сделать его праздником, рабочим или сокращённым")
            }
            CalendarTotalsCard(month, settings)
            if (calc.leave.vacationDays > 0 || calc.leave.sickDays > 0) {
                LeavePayCard(calc.leave, settings)
            }
            val summary = calc.summary
            Text(
                "Отработано ${summary.workedDays} из ${summary.normDays} " +
                    if (summary.normDays % 10 == 1 && summary.normDays % 100 != 11) "рабочего дня" else "рабочих дней",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    kindFor?.let { date ->
        DayKindDialog(
            date = date,
            current = settings.calendarOverrides[date],
            onDismiss = { kindFor = null },
            onPick = { kind ->
                onSetDayKind(date, kind)
                kindFor = null
            },
        )
    }
}

/**
 * Нажатие на день: отпуск и больничный ставятся на любой день, отгул — только на рабочий,
 * «работал» стирает отметку. Повторное нажатие тем же типом снимает отметку.
 */
private fun applyBrush(
    date: LocalDate,
    brush: CalendarBrush,
    settings: Settings,
    onSetAbsence: (LocalDate, Absence?) -> Unit,
) {
    val marked = settings.absences[date]
    when (brush) {
        CalendarBrush.WORKED -> if (marked != null) onSetAbsence(date, null)
        CalendarBrush.DAY_OFF -> if (settings.isCalendarWorkday(date)) {
            onSetAbsence(date, if (marked == Absence.DAY_OFF) null else Absence.DAY_OFF)
        }
        else -> {
            val a = brush.absence ?: return
            onSetAbsence(date, if (marked == a) null else a)
        }
    }
}

/** Сегментный переключатель из четырёх типов в серой капсуле. */
@Composable
private fun BrushSelector(selected: CalendarBrush, onSelect: (CalendarBrush) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (b in CalendarBrush.values()) {
            val isSelected = b == selected
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(1.dp, shape)
                                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                        } else {
                            Modifier
                        },
                    )
                    .clip(shape)
                    .clickable { onSelect(b) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                BrushSwatch(b, 10.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    b.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Сетка месяца Пн..Вс с отметками, точками сверхурочной и своими правками календаря. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthGridCard(
    month: YearMonth,
    calc: MonthCalc,
    settings: Settings,
    today: LocalDate,
    onClick: (LocalDate) -> Unit,
    onLongClick: (LocalDate) -> Unit,
) {
    val overtimeDays = calc.report.days.filter { it.countedMinutes > 0 }.map { it.date }.toSet()
    val weekendText = weekendTextColor()
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { i, name ->
                    Text(
                        name,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (i >= 5) weekendText else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val offset = month.atDay(1).dayOfWeek.value - 1
            val length = month.lengthOfMonth()
            val rows = (offset + length + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val index = r * 7 + c - offset + 1
                        Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.TopCenter) {
                            if (index in 1..length) {
                                val date = month.atDay(index)
                                val marked = settings.absences[date]
                                val vacation = marked == Absence.VACATION ||
                                    (marked == null && Leave.isLeaveDay(date, settings, Absence.VACATION))
                                val sick = marked == Absence.SICK ||
                                    (marked == null && Leave.isLeaveDay(date, settings, Absence.SICK))
                                val auto = marked == null && (vacation || sick)
                                val alpha = if (auto) 0.5f else 1f
                                val workday = settings.isCalendarWorkday(date)
                                val fill = when {
                                    vacation -> AbsenceColors.VacationBg.copy(alpha = alpha)
                                    sick -> AbsenceColors.SickBg.copy(alpha = alpha)
                                    else -> Color.Transparent
                                }
                                val textColor = when {
                                    vacation -> AbsenceColors.VacationFg
                                    sick -> AbsenceColors.SickFg
                                    !workday -> weekendText
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                val weight = when {
                                    date == today -> FontWeight.Bold
                                    marked != null || !workday -> FontWeight.SemiBold
                                    else -> FontWeight.Medium
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Box(Modifier.size(40.dp)) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                                .background(fill)
                                                .then(
                                                    if (marked == Absence.DAY_OFF) {
                                                        Modifier.dashedCircle(AbsenceColors.DayOffBorder, 1.5.dp)
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                                .combinedClickable(
                                                    onClick = { onClick(date) },
                                                    onLongClick = { onLongClick(date) },
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "$index",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                                fontWeight = weight,
                                                color = textColor,
                                            )
                                        }
                                        if (date in settings.calendarOverrides) {
                                            Box(
                                                Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(6.dp)
                                                    .background(outline, CircleShape),
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .background(
                                                if (date in overtimeDays) primary else Color.Transparent,
                                                CircleShape,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Пояснение значков календаря. */
@Composable
private fun CalendarLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Hint("сверхурочная")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "12",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = weekendTextColor(),
            )
            Hint("выходной")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            Hint("своя правка")
        }
    }
}

/** Итоги месяца: отпуск, больничный, отгул и свои правки календаря (если есть). */
@Composable
fun CalendarTotalsCard(month: YearMonth, settings: Settings) {
    val vacation = Leave.leaveDates(month, settings, Absence.VACATION).size
    val sick = Leave.leaveDates(month, settings, Absence.SICK).size
    val dayOff = settings.absences.count { (d, a) -> a == Absence.DAY_OFF && YearMonth.from(d) == month }
    val overrides = settings.calendarOverrides.keys.count { YearMonth.from(it) == month }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Списком в одну колонку: на узком экране подписи не обрезаются.
            TotalItem({ BrushSwatch(CalendarBrush.VACATION) }, "Отпуск", "$vacation дн", Modifier.fillMaxWidth())
            TotalItem({ BrushSwatch(CalendarBrush.SICK) }, "Больничный", "$sick дн", Modifier.fillMaxWidth())
            TotalItem({ BrushSwatch(CalendarBrush.DAY_OFF) }, "Отгул", "$dayOff дн", Modifier.fillMaxWidth())
            if (overrides > 0) {
                TotalItem(
                    {
                        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
                        }
                    },
                    "Свои правки календаря",
                    "$overrides",
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TotalItem(swatch: @Composable () -> Unit, label: String, value: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        swatch()
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Оценка отпускных и больничного по среднему заработку. */
@Composable
fun LeavePayCard(leave: LeavePay, settings: Settings) {
    SoftCard {
        Text("Отпускные и больничные", style = MaterialTheme.typography.titleMedium)
        if (leave.vacationDays > 0) {
            LeaveAmountRow("Отпускные", leave.vacationPay)
            Hint(
                "${leave.vacationDays} " +
                    monthPlural(leave.vacationDays, "календарный день", "календарных дня", "календарных дней") +
                    " × средний дневной ${formatMoney(leave.vacationAvgDaily)}",
            )
            if (leave.vacationEstimated) {
                Hint("Оценка по приложению — точную сумму начислений за 12 мес. можно вписать в Настройках")
            }
        }
        if (leave.sickDays > 0) {
            LeaveAmountRow("Больничный", leave.sickPay)
            val limit = when (leave.sickLimit) {
                SickLimit.MIN_MROT -> ", по минимуму (МРОТ)"
                SickLimit.MAX_BASE -> ", по максимуму"
                SickLimit.NONE -> ""
            }
            Hint(
                "${leave.sickDays} дн × ${formatMoney(leave.sickDaily)} в день, " +
                    "${formatPercent(settings.sickPercent)} по стажу$limit",
            )
            if (leave.sickEstimated) {
                Hint("Оценка по приложению — точную сумму начислений за 2 года можно вписать в Настройках")
            }
        }
        Hint("Отпускные и больничные уже включены в «На руки».")
    }
}

@Composable
private fun LeaveAmountRow(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(
            "≈ " + formatMoney(amount),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Диалог своей правки типа дня. */
@Composable
private fun DayKindDialog(
    date: LocalDate,
    current: DayKind?,
    onDismiss: () -> Unit,
    onPick: (DayKind?) -> Unit,
) {
    val calendarKind = when (ProductionCalendar.hours(date)) {
        0 -> if (ProductionCalendar.isHoliday(date)) "праздник" else "выходной"
        7 -> "сокращённый"
        else -> "рабочий"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Тип дня ${date.format(KIND_DAY_FMT)}") },
        text = {
            Column {
                RadioRow(current == null, "Как в календаре РБ (сейчас: $calendarKind)") { onPick(null) }
                for (k in DayKind.values()) {
                    RadioRow(current == k, k.title) { onPick(k) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}
