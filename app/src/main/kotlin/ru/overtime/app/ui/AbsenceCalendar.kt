package ru.overtime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.overtime.domain.Absence
import ru.overtime.domain.AdvanceSummary
import ru.overtime.domain.Calculator
import ru.overtime.domain.MonthSummary
import ru.overtime.domain.Report
import ru.overtime.domain.Settings
import java.time.LocalDate
import java.time.YearMonth

@Composable
private fun absenceColor(a: Absence): Color = when (a) {
    Absence.VACATION -> MaterialTheme.colorScheme.tertiaryContainer
    Absence.SICK -> MaterialTheme.colorScheme.errorContainer
    Absence.DAY_OFF -> MaterialTheme.colorScheme.secondaryContainer
}

/**
 * Календарь месяца: выбираете тип (отпуск, больничный, отгул или «работал») и нажимаете на дни.
 * Отметки уменьшают отработанные дни, оклад, премию и аванс.
 */
@Composable
fun AbsenceCalendarCard(
    month: YearMonth,
    settings: Settings,
    report: Report,
    onSet: (LocalDate, Absence?) -> Unit,
) {
    // null в brush = «работал» (стереть отметку)
    var brush by rememberSaveable { mutableStateOf<Absence?>(Absence.VACATION) }
    var erase by rememberSaveable { mutableStateOf(false) }
    val overtimeDays = report.days.filter { it.countedMinutes > 0 }.map { it.date }.toSet()
    val today = LocalDate.now()

    SoftCard {
        Text("Отпуск, больничный, отгул", style = MaterialTheme.typography.titleMedium)
        Hint("Выберите тип и нажимайте на дни. Повторное нажатие снимает отметку.")
        // 4 варианта — сеткой 2×2 одинаковой ширины, чтобы влезали на любом экране
        val options: List<Absence?> = Absence.values().toList() + listOf(null)
        for (pair in options.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in pair) {
                    val selected = if (a == null) erase else !erase && brush == a
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (a == null) {
                                erase = true
                            } else {
                                brush = a
                                erase = false
                            }
                        },
                        label = {
                            Text(
                                a?.title ?: "Работал",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth()) {
            for (name in listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")) {
                Text(
                    name,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = offset + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val index = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (index in 1..month.lengthOfMonth()) {
                            val date = month.atDay(index)
                            val workday = settings.isCalendarWorkday(date)
                            val absence = settings.absences[date]
                            val bg = when {
                                absence != null && workday -> absenceColor(absence)
                                date == today -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(bg)
                                    .clickable(enabled = workday) {
                                        val target = if (erase) null else brush
                                        onSet(date, if (absence == target) null else target)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "$index",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (date == today) FontWeight.Bold else null,
                                        color = if (workday) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                        },
                                    )
                                    if (date in overtimeDays) {
                                        Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val absent = settings.absentWorkdaysIn(month, 1, 31)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            for (a in Absence.values()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(absenceColor(a), CircleShape))
                    Hint(a.title.lowercase())
                }
            }
        }
        Hint(
            "Красным — выходные и праздники по календарю РБ, точка — день со сверхурочной. " +
                "Отмечено рабочих дней: $absent, отработано ${settings.workedDaysFor(month)} из ${settings.normDays(month)}.",
        )
    }
}

/** Аванс за 1–15 число и остаток зарплаты. */
@Composable
fun AdvanceCard(advance: AdvanceSummary, summary: MonthSummary) {
    SoftCard {
        Text("Аванс и остаток", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Аванс", style = MaterialTheme.typography.labelLarge)
                Text(formatMoney(advance.net), style = MaterialTheme.typography.titleLarge)
                Hint("≈ 26–28 числа")
            }
            Column(Modifier.weight(1f)) {
                Text("Остаток", style = MaterialTheme.typography.labelLarge)
                Text(formatMoney(advance.rest), style = MaterialTheme.typography.titleLarge)
                Hint("≈ 10–13 след. мес.")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Hint(
            "Аванс: оклад за ${advance.workedFirstHalf} из ${advance.firstHalfDays} раб. дн. 1–${Calculator.ADVANCE_LAST_DAY} числа " +
                "(${formatMoney(advance.oklad)}) + выходные и сверхурочные до ${Calculator.ADVANCE_LAST_DAY}-го " +
                "(${formatMoney(advance.extra)}), районный ${formatMoney(advance.district)}, НДФЛ −${formatMoney(advance.tax)}. " +
                "Премия в аванс не входит, она приходит с остатком.",
        )
        Hint("Остаток = на руки за месяц ${formatMoney(summary.net)} − аванс.")
    }
}
