package ru.patients.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.patients.app.PatientsApp
import ru.patients.core.Patient
import ru.patients.core.PatientType

@Composable
fun PatientScreen(id: String, nav: Nav) {
    val ctx = LocalContext.current
    val repo = PatientsApp.repo(ctx)
    val store by repo.state.collectAsState()
    val stored = store.patient(id)
    if (stored == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }

    // Черновик карточки: правки сохраняются через полсекунды после ввода и при уходе с экрана.
    var draft by remember(id) { mutableStateOf(stored) }
    var base by remember(id) { mutableStateOf(stored) }
    LaunchedEffect(stored) {
        // пришло обновление извне (синхронизация, перевод) — подхватываем, если нет несохранённых правок
        if (draft.copy(updatedAt = 0, type = base.type) == base.copy(updatedAt = 0)) draft = stored
        base = stored
    }
    LaunchedEffect(draft) {
        delay(500)
        repo.savePatient(draft)
    }
    val latest by rememberUpdatedState(draft)
    DisposableEffect(id) { onDispose { repo.savePatient(latest) } }

    var tab by rememberSaveable(id) { mutableIntStateOf(0) }
    var askDelete by remember { mutableStateOf(false) }
    var askTransfer by remember { mutableStateOf(false) }
    val isMain = stored.type == PatientType.MAIN
    val tabs = if (isMain) listOf("Учёт", "Лечение") else listOf("Явка", "Что сделать", "Результат")
    if (tab >= tabs.size) tab = 0

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        TopBar(
            title = draft.fio.ifBlank { "Без ФИО" },
            subtitle = if (isMain) "Основной пациент" else "Диагностический пациент",
            onBack = { nav.pop() },
        ) {
            if (!isMain) {
                IconButton(onClick = { askTransfer = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Перевести в основные", tint = Palette.Green)
                }
            }
            IconButton(onClick = { askDelete = true }) { Icon(Icons.Default.Delete, "Удалить", tint = Palette.InkSoft) }
        }
        PillTabs(tabs, tab) { tab = it }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(8.dp))
            val set: (Patient) -> Unit = { draft = it }
            when {
                isMain && tab == 0 -> MainAccountTab(draft, set)
                isMain -> TreatmentTab(draft, set)
                tab == 0 -> DiagVisitTab(draft, set)
                tab == 1 -> BigTextTab("Что нужно сделать", "Обследования, консультации, назначения…", draft.todo) { draft = draft.copy(todo = it) }
                else -> BigTextTab("Результат", "Итог обследования, заключение…", draft.result) { draft = draft.copy(result = it) }
            }
            if (tab == 0) {
                Spacer(Modifier.height(14.dp))
                RemindersSection(draft.id, store)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (askDelete) {
        ConfirmDialog(
            title = "Удалить пациента?",
            text = "${draft.fio.ifBlank { "Без ФИО" }} и все его напоминания будут удалены. Отменить это нельзя.",
            confirm = "Удалить",
            onConfirm = { repo.deletePatient(id) },
            onDismiss = { askDelete = false },
        )
    }
    if (askTransfer) {
        ConfirmDialog(
            title = "Перевести в основные?",
            text = "Пациент переедет в список «Основные пациенты». Данные первой явки, жалобы и результат сохранятся.",
            confirm = "Перевести",
            danger = false,
            onConfirm = {
                repo.savePatient(draft)
                repo.transferToMain(id)
                tab = 0
            },
            onDismiss = { askTransfer = false },
        )
    }
}

/** Скруглённые вкладки-«таблетки». */
@Composable
fun PillTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(Palette.GreenSoft, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        titles.forEachIndexed { i, t ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                shape = CircleShape,
                color = if (on) Color.White else Color.Transparent,
                shadowElevation = if (on) 2.dp else 0.dp,
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        t,
                        color = if (on) Palette.GreenDark else Palette.InkSoft,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalCard(p: Patient, set: (Patient) -> Unit) {
    SoftCard {
        SectionHeader("Данные пациента")
        Field(p.fio, { set(p.copy(fio = it)) }, "ФИО")
        DateField("Дата рождения", p.birthDate, { set(p.copy(birthDate = it)) }, defaultYearsBack = 40)
        Field(p.phone, { set(p.copy(phone = it)) }, "Телефон", keyboard = KeyboardType.Phone)
        Field(p.address, { set(p.copy(address = it)) }, "Адрес", singleLine = false)
        Field(p.cardNumber, { set(p.copy(cardNumber = it)) }, "Номер карты", capitalize = false)
    }
}

@Composable
private fun MainAccountTab(p: Patient, set: (Patient) -> Unit) {
    PersonalCard(p, set)
    Spacer(Modifier.height(14.dp))
    SoftCard {
        SectionHeader("Учёт")
        DateField("Дата взятия на учёт", p.registeredDate, { set(p.copy(registeredDate = it)) })
        Field(p.mainDiagnosis, { set(p.copy(mainDiagnosis = it)) }, "Основной диагноз", singleLine = false, minLines = 2)
        Spacer(Modifier.height(4.dp))
        SectionHeader("Сопутствующие диагнозы")
        p.comorbid.forEachIndexed { i, d ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    Field(d, { v -> set(p.copy(comorbid = p.comorbid.toMutableList().also { it[i] = v })) }, "Диагноз ${i + 1}", singleLine = false)
                }
                IconButton(onClick = { set(p.copy(comorbid = p.comorbid.filterIndexed { j, _ -> j != i })) }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Default.Close, "Убрать", tint = Palette.InkSoft)
                }
            }
        }
        SoftButton("Добавить диагноз", { set(p.copy(comorbid = p.comorbid + "")) }, icon = Icons.Default.Add)
    }
    val fromDiag = listOf(p.firstVisit != null, p.complaints.isNotBlank(), p.todo.isNotBlank(), p.result.isNotBlank()).any { it }
    if (fromDiag) {
        Spacer(Modifier.height(14.dp))
        SoftCard(color = Palette.GreenMist) {
            SectionHeader("Из диагностики")
            p.firstVisit?.let { InfoLine("Первая явка", dayText(it)) }
            if (p.complaints.isNotBlank()) InfoLine("Жалобы", p.complaints)
            if (p.todo.isNotBlank()) InfoLine("Что нужно было сделать", p.todo)
            if (p.result.isNotBlank()) InfoLine("Результат", p.result)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.InkSoft)
    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun TreatmentTab(p: Patient, set: (Patient) -> Unit) {
    SoftCard {
        SectionHeader("Режим лечения")
        DateField("Начало интенсивной фазы", p.intensiveStart, { set(p.copy(intensiveStart = it)) })
        DateField("Начало фазы продолжения", p.continuationStart, { set(p.copy(continuationStart = it)) })
        val a = p.intensiveStart
        val b = p.continuationStart
        if (a != null && b != null && b >= a) {
            val days = (b - a).toInt()
            Text(
                "Интенсивная фаза: $days ${ru.patients.core.Texts.plural(days, "день", "дня", "дней")}",
                color = Palette.InkSoft,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DiagVisitTab(p: Patient, set: (Patient) -> Unit) {
    PersonalCard(p, set)
    Spacer(Modifier.height(14.dp))
    SoftCard {
        SectionHeader("Первая явка")
        DateField("Дата первой явки", p.firstVisit, { set(p.copy(firstVisit = it)) })
        Field(p.complaints, { set(p.copy(complaints = it)) }, "Жалобы пациента", singleLine = false, minLines = 3)
    }
}

@Composable
private fun BigTextTab(title: String, hint: String, value: String, onChange: (String) -> Unit) {
    SoftCard {
        SectionHeader(title)
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(hint) },
            minLines = 12,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
