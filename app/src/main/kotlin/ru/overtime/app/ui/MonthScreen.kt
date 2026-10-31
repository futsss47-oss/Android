package ru.overtime.app.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.data.TimeConv
import ru.overtime.domain.Calculator
import ru.overtime.domain.Report
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)
private val ROW_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)

@Composable
fun MonthScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val month by vm.month.collectAsStateWithLifecycle()
    val report by vm.monthReport.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<SessionEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingMonth by remember { mutableStateOf(false) }

    val summary = remember(month, report, settings) { Calculator.monthSummary(month, report, settings) }
    val monthSessions = sessions.filter { YearMonth.from(TimeConv.toLocal(it.startMillis)) == month }
    val monthTitle = month.format(MONTH_FMT)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { vm.month.value = month.minusMonths(1) }) {
                    Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = "Предыдущий месяц")
                }
                Text(
                    monthTitle.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(onClick = { vm.month.value = month.plusMonths(1) }) {
                    Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = "Следующий месяц")
                }
            }
        }

        item {
            SoftCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Text("На руки", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    formatMoney(summary.net),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Начислено ${formatMoney(summary.gross)} · НДФЛ ${formatMoney(summary.tax)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        item {
            SoftCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Сверхурочные и выходные", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(formatMinutes(report.totalMinutes), style = MaterialTheme.typography.titleMedium)
                }
                DayBarsChart(month, report)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(MaterialTheme.colorScheme.primary)
                        Hint("будни")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(MaterialTheme.colorScheme.tertiary)
                        Hint("выходные и праздники")
                    }
                }
            }
        }

        item {
            MonthSummaryCard(summary, report, settings.districtPercent, settings.taxPercent) { editingMonth = true }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { adding = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Добавить запись")
                }
                OutlinedButton(onClick = { shareCsv(ctx, monthTitle, report, summary) }) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Поделиться")
                }
            }
        }

        item {
            SoftCard {
                Text("По дням", style = MaterialTheme.typography.titleMedium)
                if (report.days.isEmpty()) Hint("За этот месяц сверхурочной нет")
                for (day in report.days) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${day.date.format(DAY_FMT)}, ${dayShort(day.date)}")
                            if (day.rawMinutes != day.countedMinutes) Hint("факт ${formatMinutes(day.rawMinutes)}")
                        }
                        if (day.dayOff) {
                            Pill("выходной", MaterialTheme.colorScheme.tertiaryContainer)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatMinutes(day.countedMinutes), style = MaterialTheme.typography.titleSmall)
                            Text("≈ " + formatMoney(day.net), style = MaterialTheme.typography.bodyMedium)
                            Hint("начисл. ${formatMoney(day.withBonus)}")
                        }
                    }
                }
            }
        }

        item {
            SoftCard {
                Text("Записи", style = MaterialTheme.typography.titleMedium)
                if (monthSessions.isEmpty()) Hint("Записей нет. Добавить вручную можно кнопкой выше.")
                for (s in monthSessions) {
                    val start = TimeConv.toLocal(s.startMillis)
                    val end = s.endMillis?.let { TimeConv.toLocal(it) }
                    val minutes = Duration.between(start, end ?: now).toMinutes().toInt().coerceAtLeast(0)
                    val (badge, color) = when {
                        end == null -> "идёт" to MaterialTheme.colorScheme.tertiaryContainer
                        s.manual -> "вручную" to MaterialTheme.colorScheme.secondaryContainer
                        s.edited -> "исправлена" to MaterialTheme.colorScheme.secondaryContainer
                        else -> "авто" to MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = end != null) { editing = s }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${start.format(ROW_FMT)} – ${end?.format(TIME_FMT) ?: "…"}")
                            Spacer(Modifier.height(4.dp))
                            Pill(badge, color)
                        }
                        Text(formatMinutes(minutes), style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (monthSessions.isNotEmpty()) Hint("Нажмите на запись, чтобы исправить или удалить её.")
            }
        }
    }

    if (editingMonth) {
        MonthParamsDialog(
            summary = summary,
            autoNorm = ru.overtime.domain.ProductionCalendar.normHours(month).toDouble(),
            defaultBonusPercent = settings.bonusPercent,
            onDismiss = { editingMonth = false },
            onSave = { o ->
                vm.saveMonthOverride(month, o)
                editingMonth = false
            },
        )
    }
    if (adding) {
        SessionDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { start, end ->
                vm.addSession(start, end)
                adding = false
            },
            onDelete = null,
        )
    }
    editing?.let { s ->
        SessionDialog(
            initial = s,
            onDismiss = { editing = null },
            onSave = { start, end ->
                vm.updateSession(s, start, end)
                editing = null
            },
            onDelete = {
                vm.deleteSession(s.id)
                editing = null
            },
        )
    }
}

/** Столбики сверхурочных по дням месяца. */
@Composable
private fun DayBarsChart(month: YearMonth, report: Report) {
    val days = month.lengthOfMonth()
    val byDay = report.days.associateBy { it.date.dayOfMonth }
    val maxMinutes = (report.days.maxOfOrNull { it.countedMinutes } ?: 0).coerceAtLeast(60)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (days - 1)) / days
        val radius = CornerRadius(barW / 2, barW / 2)
        for (d in 1..days) {
            val x = (d - 1) * (barW + gap)
            drawRoundRect(track, topLeft = Offset(x, 0f), size = Size(barW, size.height), cornerRadius = radius)
            val r = byDay[d] ?: continue
            if (r.countedMinutes <= 0) continue
            val h = (size.height * r.countedMinutes / maxMinutes).coerceAtLeast(barW)
            drawRoundRect(
                if (r.dayOff) tertiary else primary,
                topLeft = Offset(x, size.height - h),
                size = Size(barW, h),
                cornerRadius = radius,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (label in listOf(1, 10, 20, days)) {
            Text("$label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Hint("Самый длинный день: ${formatMinutes(report.days.maxOfOrNull { it.countedMinutes } ?: 0)}")
}

@Composable
private fun SessionDialog(
    initial: SessionEntity?,
    onDismiss: () -> Unit,
    onSave: (LocalDateTime, LocalDateTime) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val defaultEnd = LocalDateTime.now().withSecond(0).withNano(0)
    var start by remember {
        mutableStateOf(initial?.let { TimeConv.toLocal(it.startMillis) } ?: defaultEnd.minusHours(1))
    }
    var end by remember {
        mutableStateOf(initial?.endMillis?.let { TimeConv.toLocal(it) } ?: defaultEnd)
    }
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
            TextButton(onClick = { onSave(start, end) }, enabled = valid) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

private fun shareCsv(
    ctx: android.content.Context,
    title: String,
    report: ru.overtime.domain.Report,
    summary: ru.overtime.domain.MonthSummary,
) {
    fun n(v: Double) = String.format(RU, "%.2f", v)
    val csv = buildString {
        appendLine("Дата;Минут;Часов;Выходной;По формуле, руб;Премия, руб;Начислено, руб;На руки, руб")
        for (d in report.days) {
            appendLine(
                "${d.date};${d.countedMinutes};${n(d.countedMinutes / 60.0)};" +
                    (if (d.dayOff) "да" else "нет") + ";${n(d.money)};${n(d.bonus)};${n(d.withBonus)};${n(d.net)}",
            )
        }
        appendLine(
            "Итого;${report.totalMinutes};${n(report.totalMinutes / 60.0)};;${n(report.totalMoney)};" +
                "${n(report.days.sumOf { it.bonus })};${n(report.totalWithBonus)};${n(report.totalNet)}",
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
        appendLine("Районный коэффициент;${n(summary.district)}")
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
