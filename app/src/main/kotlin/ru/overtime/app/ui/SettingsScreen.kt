package ru.overtime.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.overtime.domain.Calculator
import ru.overtime.domain.DayRule
import ru.overtime.domain.Formula
import ru.overtime.domain.OvertimeMode
import ru.overtime.domain.ProductionCalendar
import ru.overtime.domain.RoundMode
import ru.overtime.domain.Settings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private fun Settings.withRule(day: DayOfWeek, rule: DayRule): Settings = copy(days = days + (day to rule))

private val DAY_LABELS = mapOf(
    DayOfWeek.MONDAY to "Пн",
    DayOfWeek.TUESDAY to "Вт",
    DayOfWeek.WEDNESDAY to "Ср",
    DayOfWeek.THURSDAY to "Чт",
    DayOfWeek.FRIDAY to "Пт",
    DayOfWeek.SATURDAY to "Сб",
    DayOfWeek.SUNDAY to "Вс",
)

private fun scheduleSummary(s: Settings): String {
    val work = DayOfWeek.values().filter { s.rule(it).workday }
    val days = when {
        work.isEmpty() -> "нет рабочих дней"
        work == DayOfWeek.values().take(5) -> "Пн–Пт"
        else -> work.joinToString(", ") { DAY_LABELS.getValue(it) }
    }
    val first = work.firstOrNull()?.let { s.rule(it) }
    val hours = first?.let { " ${it.start.format(TIME_FMT)}–${it.end.format(TIME_FMT)}" } ?: ""
    val lunch = if (s.lunchEnabled) " · обед ${s.lunchStart.format(TIME_FMT)}–${s.lunchEnd.format(TIME_FMT)}" else " · без обеда"
    return days + hours + lunch
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by vm.settings.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(saved) }
    var message by remember { mutableStateOf<String?>(null) }
    var showMap by remember { mutableStateOf(false) }

    if (showMap) {
        MapPickerDialog(
            initialLat = draft.workLat,
            initialLon = draft.workLon,
            enterRadiusM = draft.enterRadiusM,
            exitRadiusM = draft.exitRadiusM,
            locate = { vm.currentLocation()?.let { it.latitude to it.longitude } },
            onDismiss = { showMap = false },
            onPick = { lat, lon ->
                draft = draft.copy(workLat = lat, workLon = lon)
                message = "Место работы выбрано на карте. Нажмите «Сохранить»."
                showMap = false
            },
        )
    }

    val formulaError = Formula.validate(draft.formula)
    val weekendFormulaError = Formula.validate(draft.weekendFormula)
    val errors = draft.validationErrors()
    val canSave = formulaError == null && weekendFormulaError == null && errors.isEmpty()
    val changed = draft != saved
    val thisMonth = YearMonth.now()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)

        // ---- место работы ----
        SettingsGroup(
            title = "Место работы",
            summary = if (draft.hasWorkPlace) {
                "Задано · зона ${draft.enterRadiusM} / ${draft.exitRadiusM} м · опрос раз в ${draft.pollIntervalMinutes} мин"
            } else {
                "Не задано"
            },
            initiallyExpanded = !saved.hasWorkPlace,
        ) {
            Text(
                if (draft.hasWorkPlace) {
                    "Координаты: " + String.format(RU, "%.5f, %.5f", draft.workLat, draft.workLon)
                } else {
                    "Место не задано: выберите на карте или нажмите кнопку, находясь на работе."
                },
            )
            FilledTonalButton(onClick = { showMap = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Place, contentDescription = null)
                Text("  Выбрать на карте или ввести координаты")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val loc = vm.currentLocation()
                        if (loc == null) {
                            message = "Не удалось определить место. Проверьте разрешение на геолокацию и включённый GPS."
                        } else {
                            draft = draft.copy(workLat = loc.latitude, workLon = loc.longitude)
                            message = "Место работы взято из текущей позиции (точность ${loc.accuracy.toInt()} м). Нажмите «Сохранить»."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null)
                Text("  Назначить здесь и сейчас")
            }
            IntField("Радиус «я на работе»", draft.enterRadiusM, "м") { draft = draft.copy(enterRadiusM = it) }
            IntField("Радиус «я ушёл»", draft.exitRadiusM, "м") { draft = draft.copy(exitRadiusM = it) }
            IntField("Игнорировать замеры хуже", draft.maxAccuracyM, "м") { draft = draft.copy(maxAccuracyM = it) }
            IntField("Опрос геопозиции раз в", draft.pollIntervalMinutes, "мин") {
                draft = draft.copy(pollIntervalMinutes = it)
            }
        }

        // ---- график и обед ----
        SettingsGroup(title = "График и обед", summary = scheduleSummary(draft)) {
            for (day in DayOfWeek.values()) {
                val rule = draft.rule(day)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(DAY_LABELS.getValue(day), Modifier.width(36.dp))
                    Switch(
                        checked = rule.workday,
                        onCheckedChange = { draft = draft.withRule(day, rule.copy(workday = it)) },
                    )
                    if (rule.workday) {
                        TextButton(onClick = {
                            pickTime(ctx, rule.start) { draft = draft.withRule(day, rule.copy(start = it)) }
                        }) { Text(rule.start.format(TIME_FMT)) }
                        Text("–")
                        TextButton(onClick = {
                            pickTime(ctx, rule.end) { draft = draft.withRule(day, rule.copy(end = it)) }
                        }) { Text(rule.end.format(TIME_FMT)) }
                    } else {
                        Text("  выходной", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Hint("Праздники и переносы берутся из календаря Башкортостана, отмечать их не нужно.")
            SwitchRow("Вычитать обед", draft.lunchEnabled) { draft = draft.copy(lunchEnabled = it) }
            if (draft.lunchEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Обед с", Modifier.weight(1f))
                    TextButton(onClick = {
                        pickTime(ctx, draft.lunchStart) { draft = draft.copy(lunchStart = it) }
                    }) { Text(draft.lunchStart.format(TIME_FMT)) }
                    Text("до")
                    TextButton(onClick = {
                        pickTime(ctx, draft.lunchEnd) { draft = draft.copy(lunchEnd = it) }
                    }) { Text(draft.lunchEnd.format(TIME_FMT)) }
                }
            }
            Hint(
                "Обед вычитается в выходные и при сверхурочной вне смены, только та часть, когда вы были на работе. " +
                    "Например, в выходной с 8:00 до 17:00 будет 8 часов.",
            )
        }

        // ---- подсчёт ----
        SettingsGroup(
            title = "Подсчёт сверхурочной",
            summary = (if (draft.mode == OvertimeMode.AFTER_SHIFT) "После смены" else "До и после смены") +
                " · шаг ${draft.roundStepMinutes} мин · минимум ${draft.minOvertimeMinutes} мин",
        ) {
            Text("Что считать сверхурочной", style = MaterialTheme.typography.titleSmall)
            RadioRow(draft.mode == OvertimeMode.AFTER_SHIFT, "Только после конца смены") {
                draft = draft.copy(mode = OvertimeMode.AFTER_SHIFT)
            }
            RadioRow(draft.mode == OvertimeMode.BEFORE_AND_AFTER, "До начала и после конца смены") {
                draft = draft.copy(mode = OvertimeMode.BEFORE_AND_AFTER)
            }
            SwitchRow("В выходные всё время сверхурочное", draft.weekendAllOvertime) {
                draft = draft.copy(weekendAllOvertime = it)
            }
            Text("Округление", style = MaterialTheme.typography.titleSmall)
            IntField("Шаг округления", draft.roundStepMinutes, "мин") { draft = draft.copy(roundStepMinutes = it) }
            RadioRow(draft.roundMode == RoundMode.NEAREST, "До ближайшего") {
                draft = draft.copy(roundMode = RoundMode.NEAREST)
            }
            RadioRow(draft.roundMode == RoundMode.DOWN, "Вниз") { draft = draft.copy(roundMode = RoundMode.DOWN) }
            RadioRow(draft.roundMode == RoundMode.UP, "Вверх") { draft = draft.copy(roundMode = RoundMode.UP) }
            Hint("В расчётных листках часы кратны получасу: шаг 30 минут даст самое близкое совпадение.")
            Text("Пороги", style = MaterialTheme.typography.titleSmall)
            IntField("Минимум за день, меньше не считать", draft.minOvertimeMinutes, "мин") {
                draft = draft.copy(minOvertimeMinutes = it)
            }
            IntField("Допуск после конца смены", draft.graceAfterShiftMinutes, "мин") {
                draft = draft.copy(graceAfterShiftMinutes = it)
            }
            IntField("Склеивать, если вернулись в течение", draft.mergeGapMinutes, "мин") {
                draft = draft.copy(mergeGapMinutes = it)
            }
            Hint(
                "Допуск отрезает первые минуты после смены. Минимум отбрасывает дни с совсем короткой сверхурочной. " +
                    "Если вышли (например, в магазин) и вернулись быстрее времени склейки, сверхурочная продолжается.",
            )
        }

        // ---- оплата ----
        SettingsGroup(
            title = "Оплата",
            summary = "Оклад ${formatRub(draft.salary)} · премия ${formatPercent(draft.bonusPercent)} · " +
                "районный ${formatPercent(draft.districtPercent)}",
        ) {
            DecimalField("Оклад в месяц", draft.salary, "₽") { draft = draft.copy(salary = it) }
            DecimalField("Коэффициент премии", draft.bonusPercent, "%") { draft = draft.copy(bonusPercent = it) }
            Hint(
                "Премия = коэффициент × (оклад за отработанные дни + выходные в одинарном размере + сверхурочные). " +
                    "Посчитать по листку: премия / (оклад + выходные + сверхурочные) × 100%. Для месяца можно вписать свой " +
                    "коэффициент или точную сумму из листка на вкладке «Месяц».",
            )
            DecimalField("Районный коэффициент", draft.districtPercent, "%") { draft = draft.copy(districtPercent = it) }
            DecimalField("НДФЛ", draft.taxPercent, "%") { draft = draft.copy(taxPercent = it) }
            Hint(
                "Часовая ставка считается сама: оклад / норма часов месяца. " +
                    "Сейчас (${thisMonth.format(MONTH_FMT)}): норма ${formatHours(draft.normFor(thisMonth))}, ставка " +
                    formatMoney(draft.hourlyRate(thisMonth)) + " в час.",
            )
            Hint(
                "Норма берётся из производственного календаря Республики Башкортостан. Норму и премию конкретного " +
                    "месяца можно поправить на вкладке «Месяц».",
            )
            ProductionCalendar.note(thisMonth.year)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // ---- формулы ----
        val defaultFormulas = draft.formula == Settings.DEFAULT_FORMULA &&
            draft.weekendFormula == Settings.DEFAULT_WEEKEND_FORMULA
        SettingsGroup(
            title = "Формулы оплаты",
            summary = if (defaultFormulas) "Будни: 2 ч ×1,5, дальше ×2 · выходные ×2" else "Свои формулы",
        ) {
            OutlinedTextField(
                value = draft.formula,
                onValueChange = { draft = draft.copy(formula = it) },
                label = { Text("Формула для рабочих дней") },
                isError = formulaError != null,
                supportingText = { Text(formulaError ?: "Формула корректна") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.weekendFormula,
                onValueChange = { draft = draft.copy(weekendFormula = it) },
                label = { Text("Формула для выходных и праздников") },
                isError = weekendFormulaError != null,
                supportingText = { Text(weekendFormulaError ?: "Формула корректна") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                draft = draft.copy(formula = Settings.DEFAULT_FORMULA, weekendFormula = Settings.DEFAULT_WEEKEND_FORMULA)
            }) { Text("Вернуть формулы по умолчанию") }
            if (formulaError == null && weekendFormulaError == null) {
                val days = (1..thisMonth.lengthOfMonth()).map { thisMonth.atDay(it) }
                val workDay = days.firstOrNull { draft.ruleFor(it).workday }
                val offDay = days.firstOrNull { !draft.ruleFor(it).workday }
                val weekday = workDay?.let { tryMoney(it, 150, draft) } ?: "нет рабочих дней"
                val weekend = offDay?.let { tryMoney(it, 480, draft) } ?: "нет выходных"
                Hint(
                    "Проверка (до районного коэффициента): 2 ч 30 мин сверхурочных в будний день = $weekday, " +
                        "8 ч в выходной = $weekend",
                )
            }
            Text("Переменные", style = MaterialTheme.typography.titleSmall)
            for ((name, description) in Formula.VARIABLES) Hint("$name: $description")
            Hint(
                "Операции: + - * / % ^, сравнения < > <= >= == !=, логика && || !. " +
                    "Функции: min, max, abs, round, floor, ceil, sqrt, pow, if(условие, да, нет). " +
                    "Дробные числа через точку. Формула считается за каждый день, районный коэффициент добавляется сам.",
            )
        }

        // ---- уведомления ----
        SettingsGroup(
            title = "Напоминания",
            summary = if (draft.monthReminder) "В конце месяца: включено" else "Выключены",
        ) {
            SwitchRow("Напоминать в последний день месяца", draft.monthReminder) {
                draft = draft.copy(monthReminder = it)
            }
            Hint(
                "В 18:00 последнего дня месяца придёт уведомление с итогом: сверхурочные и ожидаемая сумма на руки. " +
                    "Когда получите расчётный листок, впишите фактическую премию на вкладке «Месяц».",
            )
        }

        for (e in errors) Text(e, color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                vm.saveSettings(draft)
                message = "Сохранено"
            },
            enabled = canSave && changed,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) { Text(if (changed) "Сохранить" else "Сохранено") }
    }
}

private fun tryMoney(date: LocalDate, minutes: Int, s: Settings): String = try {
    formatMoney(Calculator.money(date, minutes, s))
} catch (e: Exception) {
    "ошибка"
}
