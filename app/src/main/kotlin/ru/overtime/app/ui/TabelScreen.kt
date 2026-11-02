package ru.overtime.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.data.TimeConv
import ru.overtime.domain.Absence
import ru.overtime.domain.DayKind
import ru.overtime.domain.Leave
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.ProductionCalendar
import ru.overtime.domain.Settings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** «октябрь» — для заголовка табеля. */
private val TABEL_MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL", RU)

/** «13 октября» — для подписей дней и диалога типа дня. */
private val TABEL_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)

/** Короткие названия месяцев для списка записей: «30 окт, 17:00». */
private val TABEL_MONTH_ABBR = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** Скругление ячейки табеля. */
private val CELL_SHAPE = RoundedCornerShape(10.dp)

/** Скругление кнопок (кисти, стрелки месяца). */
private val BUTTON_SHAPE = RoundedCornerShape(12.dp)

/** Колонок в сетке табеля, как в форме Т-13: дни идут подряд, без привязки к дням недели. */
private const val TABEL_COLUMNS = 8

/** Отметка дня в табеле (буквенные коды формы Т-13). */
private enum class TabelCode(val code: String, val title: String) {
    YA("Я", "явка"),
    V("В", "выходной"),
    RV("РВ", "работа в выходной"),
    OT("ОТ", "отпуск"),
    B("Б", "больничный"),
    OG("ОГ", "отгул"),
}

/** Кисть: что ставится нажатием на день. [absence] = null — «снять», стирает отметку. */
private enum class TabelBrush(val code: String, val label: String, val absence: Absence?) {
    VACATION("ОТ", "отпуск", Absence.VACATION),
    SICK("Б", "больничный", Absence.SICK),
    DAY_OFF("ОГ", "отгул", Absence.DAY_OFF),
    CLEAR("Я", "снять", null),
}

/**
 * Один день табеля.
 * [auto] — отпуск или больничный, в который день вошёл сам (выходные внутри отпуска), без отметки.
 * [normHours] — часы явки по календарю (8 или 7), [overtimeMinutes] — сверхурочные в рабочий день,
 * [weekendMinutes] — отработано в выходной.
 */
private data class TabelCell(
    val date: LocalDate,
    val code: TabelCode,
    val auto: Boolean,
    val override: Boolean,
    val normHours: Int,
    val overtimeMinutes: Int,
    val weekendMinutes: Int,
)

/** Итоги табеля за месяц. */
private data class TabelTotals(
    val attendanceDays: Int,
    val attendanceHours: Int,
    val overtimeMinutes: Int,
    val weekendMinutes: Int,
    val vacationDays: Int,
    val sickDays: Int,
    val dayOffDays: Int,
)

/**
 * Вкладка «Табель»: шапка с месяцем, кисти отметок, сетка дней как в форме Т-13,
 * итоги и записи месяца. Данные — из [AppViewModel].
 */
@Composable
fun TabelScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val month by vm.month.collectAsStateWithLifecycle()
    val calc by vm.monthCalc.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<SessionEntity?>(null) }
    var noteFor by remember { mutableStateOf<SessionEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    val monthSessions = remember(sessions, month) {
        sessions.filter { YearMonth.from(TimeConv.toLocal(it.startMillis)) == month }
    }

    TabelPage(
        month = month,
        calc = calc,
        settings = settings,
        sessions = monthSessions,
        now = now,
        onMonthChange = { vm.month.value = it },
        onSetAbsence = { date, a -> vm.setAbsence(date, a) },
        onSetDayKind = { date, k -> vm.setDayKind(date, k) },
        onAdd = { adding = true },
        onShare = { shareCsv(ctx, month.format(MONTH_FMT), calc, monthSessions) },
        onSessionClick = { s ->
            // у идущей записи меняется только заметка
            if (s.endMillis == null) noteFor = s else editing = s
        },
    )

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
 * Страница «Табель» без ViewModel: данные и действия приходят параметрами.
 * [sessions] — записи только этого месяца. Выбранная кисть и диалог типа дня живут здесь.
 */
@Composable
fun TabelPage(
    month: YearMonth,
    calc: MonthCalc,
    settings: Settings,
    sessions: List<SessionEntity>,
    now: LocalDateTime,
    onMonthChange: (YearMonth) -> Unit,
    onSetAbsence: (LocalDate, Absence?) -> Unit,
    onSetDayKind: (LocalDate, DayKind?) -> Unit,
    onAdd: () -> Unit,
    onShare: () -> Unit,
    onSessionClick: (SessionEntity) -> Unit,
) {
    var brush by rememberSaveable { mutableStateOf(TabelBrush.VACATION) }
    var kindFor by remember { mutableStateOf<LocalDate?>(null) }
    val today = now.toLocalDate()
    val cells = remember(month, calc, settings) { tabelCells(month, calc, settings) }
    val totals = remember(month, calc, settings, cells) { tabelTotals(month, calc, settings, cells) }

    val title = buildString {
        append("Табель, ")
        append(month.atDay(1).format(TABEL_MONTH_FMT))
        if (month.year != today.year) append(" ${month.year}")
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { MonthHeader(title, month, onMonthChange) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Выберите отметку и нажимайте на дни",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BrushRow(brush) { brush = it }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabelGridCard(
                    cells = cells,
                    today = today,
                    onClick = { date -> applyBrush(date, brush, settings, onSetAbsence) },
                    onLongClick = { kindFor = it },
                )
                Box(Modifier.padding(horizontal = 4.dp)) {
                    Hint("Долгое нажатие на день — сделать его праздником, рабочим или сокращённым")
                }
            }
        }
        item { TotalsCard(totals, calc) }
        item { SessionsCard(sessions, now, onAdd, onShare, onSessionClick) }
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

/** Шапка раздела: ‹ заголовок ›. Кнопки листают месяц. Общая для «Табеля» и «Листка». */
@Composable
fun MonthHeader(title: String, month: YearMonth, onMonthChange: (YearMonth) -> Unit) {
    val prev = month.minusMonths(1)
    val next = month.plusMonths(1)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderButton(
            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            "Предыдущий месяц, " + prev.atDay(1).format(TABEL_MONTH_FMT),
        ) { onMonthChange(prev) }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        HeaderButton(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            "Следующий месяц, " + next.atDay(1).format(TABEL_MONTH_FMT),
        ) { onMonthChange(next) }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(BUTTON_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick, role = Role.Button),
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

/** Пунктирная рамка по краю элемента со скруглением [radius] (отгул). */
private fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius((radius.toPx() - w / 2).coerceAtLeast(0f)),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** Маленький треугольник в правом верхнем углу: своя правка календаря. */
private fun Modifier.cornerMark(color: Color): Modifier = drawBehind {
    val inset = 3.dp.toPx()
    val side = 8.dp.toPx()
    val path = Path().apply {
        moveTo(size.width - inset - side, inset)
        lineTo(size.width - inset, inset)
        lineTo(size.width - inset, inset + side)
        close()
    }
    drawPath(path, color)
}

/** Четыре кисти сеткой 4×1: код крупно, подпись мелко; выбранная обведена пихтой. */
@Composable
private fun BrushRow(selected: TabelBrush, onSelect: (TabelBrush) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in TabelBrush.values()) {
            val isSelected = b == selected
            val (bg, fg) = when (b) {
                TabelBrush.VACATION -> AbsenceColors.VacationBg to AbsenceColors.VacationFg
                TabelBrush.SICK -> AbsenceColors.SickBg to AbsenceColors.SickFg
                TabelBrush.DAY_OFF -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
                TabelBrush.CLEAR -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
            }
            Column(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(BUTTON_SHAPE)
                    .background(bg)
                    .then(
                        when {
                            isSelected -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, BUTTON_SHAPE)
                            b == TabelBrush.DAY_OFF -> Modifier.dashedBorder(AbsenceColors.DayOffBorder, 1.5.dp, 12.dp)
                            else -> Modifier
                        },
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(b) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Text(
                    b.code,
                    fontFamily = Unbounded,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = fg,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(
                    b.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/**
 * Нажатие на день: отпуск и больничный ставятся на любой день, отгул — только на рабочий,
 * «снять» стирает отметку. Повторное нажатие той же кистью снимает отметку.
 */
private fun applyBrush(
    date: LocalDate,
    brush: TabelBrush,
    settings: Settings,
    onSetAbsence: (LocalDate, Absence?) -> Unit,
) {
    val marked = settings.absences[date]
    val absence = brush.absence
    when {
        absence == null -> if (marked != null) onSetAbsence(date, null)
        absence == Absence.DAY_OFF && !settings.isCalendarWorkday(date) -> Unit
        else -> onSetAbsence(date, if (marked == absence) null else absence)
    }
}

/** Отметки всех дней месяца по календарю, отметкам отсутствий и сверхурочным. */
private fun tabelCells(month: YearMonth, calc: MonthCalc, settings: Settings): List<TabelCell> {
    val byDate = calc.report.days.associateBy { it.date }
    return (1..month.lengthOfMonth()).map { n ->
        val date = month.atDay(n)
        val marked = settings.absences[date]
        val day = byDate[date]
        val counted = day?.countedMinutes ?: 0
        val hours = settings.dayHours(date)
        val vacation = marked == Absence.VACATION ||
            (marked == null && Leave.isLeaveDay(date, settings, Absence.VACATION))
        val sick = marked == Absence.SICK ||
            (marked == null && Leave.isLeaveDay(date, settings, Absence.SICK))
        val code = when {
            marked == Absence.DAY_OFF -> TabelCode.OG
            vacation -> TabelCode.OT
            sick -> TabelCode.B
            day != null && day.dayOff && counted > 0 -> TabelCode.RV
            hours <= 0 -> TabelCode.V
            else -> TabelCode.YA
        }
        TabelCell(
            date = date,
            code = code,
            auto = marked == null && (code == TabelCode.OT || code == TabelCode.B),
            override = date in settings.calendarOverrides,
            normHours = if (code == TabelCode.YA) hours else 0,
            overtimeMinutes = if (code == TabelCode.YA && day != null && !day.dayOff) counted else 0,
            weekendMinutes = if (code == TabelCode.RV) counted else 0,
        )
    }
}

private fun tabelTotals(month: YearMonth, calc: MonthCalc, settings: Settings, cells: List<TabelCell>): TabelTotals {
    val days = calc.report.days
    return TabelTotals(
        attendanceDays = cells.count { it.code == TabelCode.YA },
        attendanceHours = cells.sumOf { it.normHours },
        overtimeMinutes = days.filter { !it.dayOff }.sumOf { it.countedMinutes },
        weekendMinutes = days.filter { it.dayOff }.sumOf { it.countedMinutes },
        vacationDays = Leave.leaveDates(month, settings, Absence.VACATION).size,
        sickDays = Leave.leaveDates(month, settings, Absence.SICK).size,
        dayOffDays = cells.count { it.code == TabelCode.OG },
    )
}

/** Часы из минут без лишних нулей: «8», «2,5», «1,3». */
private fun hoursLabel(minutes: Int): String {
    val h = Math.round(minutes / 6.0) / 10.0
    return if (h == Math.floor(h)) h.toLong().toString() else String.format(RU, "%.1f", h)
}

/** Сетка табеля: белая карточка, 8 колонок, дни подряд. */
@Composable
private fun TabelGridCard(
    cells: List<TabelCell>,
    today: LocalDate,
    onClick: (LocalDate) -> Unit,
    onLongClick: (LocalDate) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (row in cells.chunked(TABEL_COLUMNS)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (cell in row) {
                        TabelDay(
                            cell = cell,
                            isToday = cell.date == today,
                            onClick = { onClick(cell.date) },
                            onLongClick = { onLongClick(cell.date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(TABEL_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Ячейка дня: номер сверху, код по центру, часы снизу. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabelDay(
    cell: TabelCell,
    isToday: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val ochre = weekendTextColor()
    val alpha = if (cell.auto) 0.55f else 1f
    val bg = when (cell.code) {
        TabelCode.YA, TabelCode.OG -> scheme.surfaceContainer
        TabelCode.V -> Color.Transparent
        TabelCode.RV -> Brand.Signal
        TabelCode.OT -> AbsenceColors.VacationBg.copy(alpha = alpha)
        TabelCode.B -> AbsenceColors.SickBg.copy(alpha = alpha)
    }
    val ink = when (cell.code) {
        TabelCode.YA -> scheme.onSurface
        TabelCode.V -> ochre
        TabelCode.RV -> Brand.Graphite
        TabelCode.OT -> AbsenceColors.VacationFg
        TabelCode.B -> AbsenceColors.SickFg
        TabelCode.OG -> scheme.onSurfaceVariant
    }
    val hoursColor = if (cell.code == TabelCode.RV) Brand.Graphite else scheme.onSurfaceVariant
    val hours = buildAnnotatedString {
        when (cell.code) {
            TabelCode.YA -> {
                append("${cell.normHours}")
                if (cell.overtimeMinutes > 0) {
                    withStyle(SpanStyle(color = ochre)) { append("+" + hoursLabel(cell.overtimeMinutes)) }
                }
            }
            TabelCode.RV -> append(hoursLabel(cell.weekendMinutes))
            else -> Unit
        }
    }
    val description = buildString {
        append(cell.date.format(TABEL_DAY_FMT))
        append(", ")
        append(cell.code.title)
        if (cell.overtimeMinutes > 0) append(", сверхурочные ${hoursLabel(cell.overtimeMinutes)} ч")
        if (cell.code == TabelCode.RV) append(" ${hoursLabel(cell.weekendMinutes)} ч")
        if (cell.override) append(", своя правка календаря")
        if (isToday) append(", сегодня")
    }
    val frame = when {
        isToday -> Modifier.border(2.dp, scheme.primary, CELL_SHAPE)
        cell.code == TabelCode.YA -> Modifier.border(1.dp, scheme.outlineVariant, CELL_SHAPE)
        else -> Modifier
    }

    Column(
        modifier
            .height(60.dp)
            .clip(CELL_SHAPE)
            .background(bg)
            .then(if (cell.code == TabelCode.OG) Modifier.dashedBorder(AbsenceColors.DayOffBorder, 1.5.dp, 10.dp) else Modifier)
            .then(frame)
            .then(if (cell.override) Modifier.cornerMark(scheme.outline) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Тип дня",
            )
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "${cell.date.dayOfMonth}",
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(
            cell.code.code,
            fontFamily = Unbounded,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (cell.code.code.length > 1) 12.sp else 14.sp,
            lineHeight = 16.sp,
            color = ink,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(
            hours,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = hoursColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** Итоги табеля таблицей строк; отпускные и больничный — оценкой, если есть. */
@Composable
private fun TotalsCard(totals: TabelTotals, calc: MonthCalc) {
    val leave = calc.leave
    val rows = buildList {
        add(Triple("Явки", "${totals.attendanceDays} дн, ${totals.attendanceHours} ч", false))
        add(Triple("Сверхурочные", "${hoursLabel(totals.overtimeMinutes)} ч", true))
        add(Triple("Работа в выходной", "${hoursLabel(totals.weekendMinutes)} ч", true))
        add(
            Triple(
                "Отпуск, больничный, отгул",
                "${totals.vacationDays}, ${totals.sickDays}, ${totals.dayOffDays} дн",
                false,
            ),
        )
        if (leave.vacationDays > 0) add(Triple("Отпускные", "≈ " + formatMoney(leave.vacationPay), true))
        if (leave.sickDays > 0) add(Triple("Больничный", "≈ " + formatMoney(leave.sickPay), true))
    }
    val estimated = (leave.vacationDays > 0 && leave.vacationEstimated) || (leave.sickDays > 0 && leave.sickEstimated)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            rows.forEachIndexed { i, (label, value, bold) ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (estimated) {
                Box(Modifier.padding(bottom = 10.dp)) {
                    Hint("Оценка по среднему; точные начисления можно вписать в Настройках")
                }
            }
        }
    }
}

/** Записи месяца: в шапке «Добавить» и «Поделиться», ниже список с отметками и заметками. */
@Composable
fun SessionsCard(
    sessions: List<SessionEntity>,
    now: LocalDateTime,
    onAdd: () -> Unit,
    onShare: () -> Unit,
    onSessionClick: (SessionEntity) -> Unit,
) {
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Записи", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить", maxLines = 1)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, contentDescription = "Поделиться таблицей")
            }
        }
        if (sessions.isEmpty()) Hint("Записей за этот месяц нет")
        for (s in sessions) SessionRow(s, now) { onSessionClick(s) }
    }
}

/** «30 окт, 17:00». */
private fun shortDateTime(t: LocalDateTime): String =
    "${t.dayOfMonth} ${TABEL_MONTH_ABBR[t.monthValue - 1]}, ${t.format(TIME_FMT)}"

/** Длительность «1:30». */
private fun clockDuration(minutes: Int): String = String.format(RU, "%d:%02d", minutes / 60, minutes % 60)

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
    val endText = when {
        end == null -> "сейчас"
        end.toLocalDate() == start.toLocalDate() -> end.format(TIME_FMT)
        else -> shortDateTime(end)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(BUTTON_SHAPE)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${shortDateTime(start)} – $endText",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Pill(badge, color)
            Spacer(Modifier.width(8.dp))
            Text(clockDuration(minutes), style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
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

/** Новая запись или правка готовой: начало, конец, заметка; у готовой — «Удалить». */
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

/** Диалог своей правки типа дня (долгое нажатие на день табеля). */
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
        title = { Text("Тип дня ${date.format(TABEL_DAY_FMT)}") },
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

/** Текст заметки для CSV: без разделителей колонок и переносов строк. */
private fun csvText(s: String): String = s.replace(';', ',').replace('\n', ' ').replace('\r', ' ').trim()

/** «Поделиться таблицей»: CSV по дням и итоги листка. */
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
