package ru.patients.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.patients.app.PatientsApp
import ru.patients.core.Patient
import ru.patients.core.PatientType
import ru.patients.core.Reminder
import ru.patients.core.Store
import ru.patients.core.Texts

fun typeTitle(t: PatientType) = if (t == PatientType.MAIN) "Основные пациенты" else "Диагностические пациенты"

@Composable
fun PatientListScreen(type: PatientType?, nav: Nav) {
    val ctx = LocalContext.current
    val repo = PatientsApp.repo(ctx)
    val store by repo.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Patient?>(null) }

    val all = if (type == null) store.patients.filter { !it.deleted } else store.activePatients(type)
    val list = all.filter { Texts.matches(it.fio, query) }.sortedBy { it.fio.lowercase() }
    val now = System.currentTimeMillis()

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = type?.let(::typeTitle) ?: "Поиск пациента",
                subtitle = "${all.size} ${Texts.plural(all.size, "пациент", "пациента", "пациентов")}",
                onBack = { nav.pop() },
            )
            SearchField(query, { query = it }, autoFocus = type == null)
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 110.dp),
                modifier = Modifier.fillMaxSize().imePadding(),
            ) {
                if (list.isEmpty()) {
                    item {
                        if (all.isEmpty()) EmptyState("Пока никого нет", "Нажмите «Добавить», чтобы внести первого пациента")
                        else EmptyState("Никого не нашли", "Проверьте написание ФИО")
                    }
                }
                items(list, key = { it.id }) { p ->
                    PatientRow(
                        p = p,
                        store = store,
                        now = now,
                        showType = type == null,
                        onClick = { nav.push(Screen.PatientCard(p.id)) },
                        onDelete = { toDelete = p },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (type != null) {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Добавить") },
                shape = CircleShape,
                containerColor = Palette.Green,
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(20.dp),
            )
        }
    }

    if (adding && type != null) {
        AddPatientDialog(
            onAdd = { fio ->
                val p = repo.addPatient(type, fio)
                adding = false
                nav.push(Screen.PatientCard(p.id))
            },
            onDismiss = { adding = false },
        )
    }
    toDelete?.let { p ->
        ConfirmDialog(
            title = "Удалить пациента?",
            text = "${p.fio.ifBlank { "Без ФИО" }} и все его напоминания будут удалены. Отменить это нельзя.",
            confirm = "Удалить",
            onConfirm = { repo.deletePatient(p.id) },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, autoFocus: Boolean) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Поиск по ФИО") },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Palette.InkSoft) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "Очистить") }
        },
        singleLine = true,
        shape = CircleShape,
        colors = fieldColors(Color.White),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(focus),
    )
}

/** Ближайшее невыполненное напоминание пациента. */
fun nextReminder(store: Store, patientId: String): Reminder? =
    store.remindersOf(patientId).filter { !it.done }.minByOrNull { it.atMillis }

@Composable
private fun PatientRow(p: Patient, store: Store, now: Long, showType: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val next = nextReminder(store, p.id)
    SoftCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(Palette.GreenSoft), contentAlignment = Alignment.Center) {
                Text(initials(p.fio), color = Palette.GreenDark, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.fio.ifBlank { "Без ФИО" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    showType -> typeTitle(p.type)
                    p.type == PatientType.MAIN -> p.mainDiagnosis.ifBlank { p.registeredDate?.let { "На учёте с ${dayText(it)}" } ?: "Диагноз не указан" }
                    else -> p.firstVisit?.let { "Первая явка ${dayText(it)}" } ?: p.complaints.ifBlank { "Первая явка не указана" }
                }
                Text(sub, color = Palette.InkSoft, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (next != null) {
                    Spacer(Modifier.height(6.dp))
                    val late = next.atMillis <= now
                    Chip(
                        "${next.title.ifBlank { "Напоминание" }} · ${friendlyDateTime(next.atMillis)}",
                        if (late) Palette.Red else Palette.GreenDark,
                        if (late) Palette.RedSoft else Palette.GreenMist,
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить", tint = Palette.InkSoft) }
        }
    }
}

fun initials(fio: String): String {
    val parts = fio.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return parts.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}

@Composable
private fun AddPatientDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var fio by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.White,
        title = { Text("Новый пациент") },
        text = {
            OutlinedTextField(
                value = fio,
                onValueChange = { fio = it },
                label = { Text("ФИО") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            Button(
                onClick = { onAdd(fio) },
                enabled = fio.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Green),
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Palette.InkSoft) } },
    )
}
