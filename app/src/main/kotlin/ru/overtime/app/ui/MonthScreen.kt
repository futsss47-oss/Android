package ru.overtime.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.data.TimeConv
import ru.overtime.domain.Calculator
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

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
    val monthTitle = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU))

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { vm.month.value = month.minusMonths(1) }) { Text("◀") }
                Text(monthTitle, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { vm.month.value = month.plusMonths(1) }) { Text("▶") }
            }
        }
        item {
            MonthSummaryCard(summary, report, settings.districtPercent, settings.taxPercent) { editingMonth = true }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareCsv(ctx, monthTitle, report, summary) }) { Text("Поделиться таблицей") }
                Button(onClick = { adding = true }) { Text("Добавить запись") }
            }
        }

        item { Text("По дням", style = MaterialTheme.typography.titleMedium) }
        if (report.days.isEmpty()) {
            item { Text("За этот месяц сверхурочной нет") }
        }
        items(report.days, key = { it.date.toString() }) { day ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("${day.date.dayOfMonth} ${dayShort(day.date)}${if (day.dayOff) ", выходной" else ""}")
                    if (day.rawMinutes != day.countedMinutes) {
                        Text(
                            "факт ${formatMinutes(day.rawMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMinutes(day.countedMinutes))
                    Text(formatMoney(day.gross), style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }

        item { Text("Записи", style = MaterialTheme.typography.titleMedium) }
        items(monthSessions, key = { it.id }) { s ->
            val start = TimeConv.toLocal(s.startMillis)
            val end = s.endMillis?.let { TimeConv.toLocal(it) }
            val minutes = Duration.between(start, end ?: now).toMinutes().toInt().coerceAtLeast(0)
            val badge = when {
                end == null -> "идёт"
                s.manual -> "вручную"
                s.edited -> "исправлена"
                else -> "авто"
            }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = end != null) { editing = s },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "${start.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))} – " +
                            (end?.format(TIME_FMT) ?: "…"),
                    )
                    Text(badge, style = MaterialTheme.typography.bodySmall)
                }
                Text(formatMinutes(minutes))
            }
            HorizontalDivider()
        }
    }

    if (editingMonth) {
        MonthParamsDialog(
            summary = summary,
            autoNorm = ru.overtime.domain.ProductionCalendar.normHours(month).toDouble(),
            defaultBonus = settings.bonus,
            onDismiss = { editingMonth = false },
            onSave = { norm, bonus ->
                vm.saveMonthOverride(month, norm, bonus)
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
        appendLine("Дата;Минут;Часов;Выходной;По формуле, руб;С районным, руб")
        for (d in report.days) {
            appendLine(
                "${d.date};${d.countedMinutes};${n(d.countedMinutes / 60.0)};" +
                    (if (d.dayOff) "да" else "нет") + ";${n(d.money)};${n(d.gross)}",
            )
        }
        appendLine("Итого;${report.totalMinutes};${n(report.totalMinutes / 60.0)};;${n(report.totalMoney)};${n(report.totalGross)}")
        appendLine()
        appendLine("Норма часов;${n(summary.norm)}")
        appendLine("Ставка в час;${n(summary.rate)}")
        appendLine("Оклад;${n(summary.salary)}")
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
