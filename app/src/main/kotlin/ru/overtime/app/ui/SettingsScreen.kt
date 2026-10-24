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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import ru.overtime.domain.RoundMode
import ru.overtime.domain.Settings
import java.time.DayOfWeek
import java.time.LocalDate

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

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by vm.settings.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(saved) }
    var message by remember { mutableStateOf<String?>(null) }

    val formulaError = Formula.validate(draft.formula)
    val weekendFormulaError = Formula.validate(draft.weekendFormula)
    val errors = draft.validationErrors()
    val canSave = formulaError == null && weekendFormulaError == null && errors.isEmpty()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)

        // ---- место работы ----
        Section("Место работы")
        Text(
            if (draft.hasWorkPlace) {
                "Координаты: " + String.format(RU, "%.5f, %.5f", draft.workLat, draft.workLon)
            } else {
                "Не задано. Придите на работу и нажмите кнопку."
            },
        )
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
        ) { Text("Назначить место работы здесь и сейчас") }
        IntField("Радиус «я на работе»", draft.enterRadiusM, "м") { draft = draft.copy(enterRadiusM = it) }
        IntField("Радиус «я ушёл»", draft.exitRadiusM, "м") { draft = draft.copy(exitRadiusM = it) }
        IntField("Игнорировать замеры хуже", draft.maxAccuracyM, "м") { draft = draft.copy(maxAccuracyM = it) }
        IntField("Опрос геопозиции раз в", draft.pollIntervalMinutes, "мин") {
            draft = draft.copy(pollIntervalMinutes = it)
        }

        HorizontalDivider()

        // ---- график ----
        Section("График работы")
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

        Section("Что считать сверхурочной")
        RadioRow(draft.mode == OvertimeMode.AFTER_SHIFT, "Только после конца смены") {
            draft = draft.copy(mode = OvertimeMode.AFTER_SHIFT)
        }
        RadioRow(draft.mode == OvertimeMode.BEFORE_AND_AFTER, "До начала и после конца смены") {
            draft = draft.copy(mode = OvertimeMode.BEFORE_AND_AFTER)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = draft.weekendAllOvertime,
                onCheckedChange = { draft = draft.copy(weekendAllOvertime = it) },
            )
            Text("  В выходные всё время сверхурочное")
        }

        HorizontalDivider()

        // ---- округление и пороги ----
        Section("Округление и пороги")
        IntField("Шаг округления", draft.roundStepMinutes, "мин") { draft = draft.copy(roundStepMinutes = it) }
        RadioRow(draft.roundMode == RoundMode.NEAREST, "До ближайшего") {
            draft = draft.copy(roundMode = RoundMode.NEAREST)
        }
        RadioRow(draft.roundMode == RoundMode.DOWN, "Вниз") { draft = draft.copy(roundMode = RoundMode.DOWN) }
        RadioRow(draft.roundMode == RoundMode.UP, "Вверх") { draft = draft.copy(roundMode = RoundMode.UP) }
        IntField("Минимум за день, меньше не считать", draft.minOvertimeMinutes, "мин") {
            draft = draft.copy(minOvertimeMinutes = it)
        }
        IntField("Допуск после конца смены", draft.graceAfterShiftMinutes, "мин") {
            draft = draft.copy(graceAfterShiftMinutes = it)
        }
        IntField("Склеивать, если вернулись в течение", draft.mergeGapMinutes, "мин") {
            draft = draft.copy(mergeGapMinutes = it)
        }
        Text(
            "Если вышли (например, в магазин) и вернулись быстрее этого времени, сверхурочная продолжается, " +
                "а время отсутствия засчитывается.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        // ---- деньги ----
        Section("Оплата")
        DecimalField("Ставка в час", draft.rate, "₽") { draft = draft.copy(rate = it) }
        DecimalField("Оклад в месяц", draft.salary, "₽") { draft = draft.copy(salary = it) }
        DecimalField("Норма часов в месяце", draft.normHours, "ч") { draft = draft.copy(normHours = it) }
        OutlinedTextField(
            value = draft.formula,
            onValueChange = { draft = draft.copy(formula = it) },
            label = { Text("Формула для рабочих дней") },
            isError = formulaError != null,
            supportingText = { Text(formulaError ?: "Формула корректна") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { draft = draft.copy(formula = Settings.DEFAULT_FORMULA) }) {
            Text("Вернуть формулу рабочих дней по умолчанию")
        }
        OutlinedTextField(
            value = draft.weekendFormula,
            onValueChange = { draft = draft.copy(weekendFormula = it) },
            label = { Text("Формула для выходных") },
            isError = weekendFormulaError != null,
            supportingText = { Text(weekendFormulaError ?: "Формула корректна") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { draft = draft.copy(weekendFormula = Settings.DEFAULT_WEEKEND_FORMULA) }) {
            Text("Вернуть формулу выходных по умолчанию")
        }
        if (formulaError == null && weekendFormulaError == null) {
            val weekday = tryMoney(LocalDate.of(2026, 10, 26), 150, draft)
            val weekend = tryMoney(LocalDate.of(2026, 10, 31), 150, draft)
            Text(
                "Проверка: 2 ч 30 мин в будний день = $weekday, в выходной = $weekend",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Выходным считается день, который вы отметили выходным в графике. Для него берётся формула выходных.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Переменные в формуле:", style = MaterialTheme.typography.titleSmall)
        for ((name, description) in Formula.VARIABLES) {
            Text("$name: $description", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Операции: + - * / % ^ и сравнения < > <= >= == !=, логика && || !. " +
                "Функции: min, max, abs, round, floor, ceil, sqrt, pow, if(условие, да, нет). " +
                "Дробные числа пишите через точку.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Формула считается отдельно за каждый день, итог за месяц складывается из дней.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        for (e in errors) Text(e, color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                vm.saveSettings(draft)
                message = "Сохранено"
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

private fun tryMoney(date: LocalDate, minutes: Int, s: Settings): String = try {
    formatMoney(Calculator.money(date, minutes, s))
} catch (e: Exception) {
    "ошибка"
}
