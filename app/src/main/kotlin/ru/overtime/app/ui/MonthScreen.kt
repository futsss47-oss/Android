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

    var editing by remember { mutableStateOf<SessionEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Итого: ${formatMinutes(report.totalMinutes)}", style = MaterialTheme.typography.titleMedium)
                Text("К оплате: ${formatMoney(report.totalMoney)}", style = MaterialTheme.typography.titleMedium)
                report.formulaError?.let {
                    Text("Ошибка в формуле: $it", color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareCsv(ctx, monthTitle, report) }) { Text("Поделиться таблицей") }
                    Button(onClick = { adding = true }) { Text("Добавить запись") }
                }
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
                    Text(formatMoney(day.money), style = MaterialTheme.typography.bodySmall)
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

private fun shareCsv(ctx: android.content.Context, title: String, report: ru.overtime.domain.Report) {
    val csv = buildString {
        appendLine("Дата;Минут;Часов;Сумма, руб")
        for (d in report.days) {
            appendLine(
                "${d.date};${d.countedMinutes};" +
                    String.format(RU, "%.2f", d.countedMinutes / 60.0) + ";" +
                    String.format(RU, "%.2f", d.money),
            )
        }
        appendLine("Итого;${report.totalMinutes};" +
            String.format(RU, "%.2f", report.totalMinutes / 60.0) + ";" +
            String.format(RU, "%.2f", report.totalMoney))
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Сверхурочные, $title")
        .putExtra(Intent.EXTRA_TEXT, csv)
    ctx.startActivity(Intent.createChooser(send, "Поделиться таблицей"))
}
