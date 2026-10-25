package ru.patients.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.patients.app.PatientsApp
import ru.patients.core.Reminder
import java.time.LocalDate
import java.time.LocalTime

/** Все сроки по всем пациентам: просроченные, сегодня, ближайшие 30 дней. */
@Composable
fun TodayScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = PatientsApp.repo(ctx)
    val store by repo.state.collectAsState()
    val now = System.currentTimeMillis()
    val endToday = toMs(LocalDate.now().atTime(LocalTime.MAX))
    val horizon = now + 30L * 86_400_000L
    val active = store.visibleReminders().filter { !it.done }.sortedBy { it.atMillis }
    val overdue = active.filter { it.atMillis <= now }
    val today = active.filter { it.atMillis in (now + 1)..endToday }
    val soon = active.filter { it.atMillis in (endToday + 1)..horizon }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        TopBar("Сроки и напоминания", onBack = { nav.pop() }, subtitle = "Все пациенты")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            if (active.isEmpty()) item { EmptyState("Сроков нет", "Напоминания добавляются в карточке пациента, на первой вкладке") }
            fun section(title: String, list: List<Reminder>) {
                if (list.isEmpty()) return
                item(key = "h_$title") {
                    Spacer(Modifier.height(10.dp))
                    SectionHeader("$title · ${list.size}")
                }
                items(list, key = { it.id }) { r ->
                    val p = store.patient(r.patientId)
                    ReminderRow(
                        r = r,
                        now = now,
                        patientName = p?.fio?.ifBlank { "Без ФИО" } ?: "",
                        onToggle = { repo.markDone(r.id) },
                        onClick = { nav.push(Screen.PatientCard(r.patientId)) },
                        onDelete = null,
                    )
                }
            }
            section("Просрочено", overdue)
            section("Сегодня", today)
            section("Ближайшие 30 дней", soon)
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
