package ru.patients.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ru.patients.app.PatientsApp
import ru.patients.core.Reminder
import ru.patients.core.RepeatUnit
import ru.patients.core.Store
import ru.patients.core.Texts
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

@Composable
fun RemindersSection(patientId: String, store: Store) {
    val ctx = LocalContext.current
    val repo = PatientsApp.repo(ctx)
    val all = store.remindersOf(patientId)
    val active = all.filter { !it.done }.sortedBy { it.atMillis }
    val done = all.filter { it.done }.sortedByDescending { it.doneAt ?: it.atMillis }
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var showDone by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Reminder?>(null) }
    val now = System.currentTimeMillis()

    SoftCard {
        SectionHeader("Напоминания") {
            Surface(
                onClick = {
                    val at = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0))
                    editing = Reminder(id = UUID.randomUUID().toString(), patientId = patientId, atMillis = toMs(at))
                },
                shape = CircleShape,
                color = Palette.Green,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Добавить", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (active.isEmpty()) {
            Text(
                "Нет активных напоминаний. Укажите дату и время, и в этот момент на телефон придёт уведомление с ФИО пациента.",
                color = Palette.InkSoft,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        active.forEach { r ->
            ReminderRow(
                r = r,
                now = now,
                patientName = null,
                onToggle = { repo.markDone(r.id) },
                onClick = { editing = r },
                onDelete = { toDelete = r },
            )
        }
        if (done.isNotEmpty()) {
            TextButton(onClick = { showDone = !showDone }) {
                Text(if (showDone) "Скрыть выполненные" else "Выполненные (${done.size})", color = Palette.Green)
            }
            if (showDone) done.forEach { r ->
                ReminderRow(r, now, null, onToggle = { repo.markNotDone(r.id) }, onClick = { editing = r }, onDelete = { toDelete = r })
            }
        }
    }

    editing?.let { r ->
        ReminderEditor(
            initial = r,
            onSave = { repo.saveReminder(it); editing = null },
            onDismiss = { editing = null },
        )
    }
    toDelete?.let { r ->
        ConfirmDialog(
            title = "Удалить напоминание?",
            text = "«${r.title.ifBlank { "Напоминание" }}» на ${dateTimeText(r.atMillis)}",
            confirm = "Удалить",
            onConfirm = { repo.deleteReminder(r.id) },
            onDismiss = { toDelete = null },
        )
    }
}

/** Строка напоминания. patientName показывается на экране «Сроки». */
@Composable
fun ReminderRow(
    r: Reminder,
    now: Long,
    patientName: String?,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val late = !r.done && r.atMillis <= now
    val accent = when {
        r.done -> Palette.InkSoft
        late -> Palette.Red
        else -> Palette.Green
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (late) Palette.RedSoft.copy(alpha = 0.55f) else Palette.GreenMist,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onToggle,
                shape = CircleShape,
                color = if (r.done) Palette.Green else Color.White,
                border = BorderStroke(2.dp, if (r.done) Palette.Green else accent.copy(alpha = 0.6f)),
                modifier = Modifier.padding(6.dp).size(30.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (r.done) Icon(Icons.Default.Check, "Вернуть", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                if (patientName != null) {
                    Text(patientName, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    r.title.ifBlank { "Напоминание" },
                    style = if (patientName != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                    color = if (r.done) Palette.InkSoft else Palette.Ink,
                )
                Text(
                    (if (late) "Просрочено · " else "") + friendlyDateTime(r.atMillis),
                    color = accent,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val extra = listOfNotNull(
                    if (r.repeatUnit != RepeatUnit.NONE) Texts.repeat(r.repeatUnit, r.repeatEvery) else null,
                    if (r.advanceMinutes > 0) Texts.advance(r.advanceMinutes) else null,
                )
                if (extra.isNotEmpty()) Text(extra.joinToString(" · "), color = Palette.InkSoft, style = MaterialTheme.typography.bodySmall)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить", tint = Palette.InkSoft) }
            }
        }
    }
}

private val QUICK_TITLES = listOf("Мокрота", "Рентген", "КТ", "ОАК", "Биохимия", "Повторный приём")
private val ADVANCE = listOf(0 to "Нет", 60 to "За 1 ч", 180 to "За 3 ч", 1440 to "За 1 день", 2880 to "За 2 дня", 10080 to "За неделю")
private val UNITS = listOf(RepeatUnit.NONE to "Нет", RepeatUnit.DAYS to "Дни", RepeatUnit.WEEKS to "Недели", RepeatUnit.MONTHS to "Месяцы")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderEditor(initial: Reminder, onSave: (Reminder) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var r by remember { mutableStateOf(initial) }
    var everyText by remember { mutableStateOf(initial.repeatEvery.toString()) }
    val at = toLdt(r.atMillis)
    val inPast = r.atMillis <= System.currentTimeMillis() && !r.done

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.White,
        title = { Text(if (initial.updatedAt == 0L) "Новое напоминание" else "Напоминание") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Field(r.title, { r = r.copy(title = it) }, "Что сделать (например, мокрота)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_TITLES.forEach { t -> SelectPill(t, r.title == t) { r = r.copy(title = t) } }
                }
                Spacer(Modifier.height(16.dp))
                Text("Когда", style = SectionTitle)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(DATE_FMT.format(at), {
                        pickDate(ctx, at.toLocalDate()) { d -> r = r.copy(atMillis = toMs(LocalDateTime.of(d, at.toLocalTime()))) }
                    }, Modifier.weight(1.3f), icon = Icons.Default.DateRange)
                    SoftButton(TIME_FMT.format(at), {
                        pickTime(ctx, at) { h, m -> r = r.copy(atMillis = toMs(at.withHour(h).withMinute(m).withSecond(0).withNano(0))) }
                    }, Modifier.weight(1f), icon = Icons.Default.Notifications)
                }
                if (inPast) {
                    Text("Это время уже прошло — уведомление не придёт", color = Palette.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Повтор", style = SectionTitle)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UNITS.forEach { (u, t) -> SelectPill(t, r.repeatUnit == u) { r = r.copy(repeatUnit = u) } }
                }
                if (r.repeatUnit != RepeatUnit.NONE) {
                    Spacer(Modifier.height(8.dp))
                    Field(
                        everyText,
                        { v ->
                            everyText = v.filter(Char::isDigit).take(3)
                            r = r.copy(repeatEvery = everyText.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                        },
                        "Каждые сколько (${UNITS.first { it.first == r.repeatUnit }.second.lowercase()})",
                        keyboard = KeyboardType.Number,
                    )
                    Text(
                        Texts.repeat(r.repeatUnit, r.repeatEvery) + ". После отметки «выполнено» срок перенесётся на следующий.",
                        color = Palette.InkSoft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Предупредить заранее", style = SectionTitle)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ADVANCE.forEach { (m, t) -> SelectPill(t, r.advanceMinutes == m) { r = r.copy(advanceMinutes = m) } }
                }
                if (r.done) {
                    Spacer(Modifier.height(16.dp))
                    SoftButton("Сделать снова активным", { r = r.copy(done = false) }, icon = Icons.Default.Refresh)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(r.copy(title = r.title.trim())) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Green),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Palette.InkSoft) } },
    )
}

@Composable
fun SelectPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Palette.Green else Palette.GreenMist,
        border = if (selected) null else BorderStroke(1.dp, Palette.Line),
    ) {
        Text(
            text,
            color = if (selected) Color.White else Palette.Ink,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
