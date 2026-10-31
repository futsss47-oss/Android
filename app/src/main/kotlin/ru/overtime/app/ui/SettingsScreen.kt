package ru.overtime.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.overtime.app.location.Perms
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

private fun nightSummary(s: Settings): String =
    if (!s.nightEnabled) {
        "Выключено"
    } else {
        "${s.nightStart.format(TIME_FMT)}–${s.nightEnd.format(TIME_FMT)} · +${formatPercent(s.nightPercent)}"
    }

private fun leaveSummary(s: Settings): String {
    val source = when {
        s.vacationEarnings12m == null && s.sickEarnings2y == null -> "оценка по приложению"
        s.vacationEarnings12m != null && s.sickEarnings2y != null -> "из бухгалтерии"
        else -> "частично из бухгалтерии"
    }
    return "Стаж: ${formatPercent(s.sickPercent)} · $source"
}

/**
 * Изменились ли настройки, которые сохраняет общая кнопка. Отметки дней, правки месяцев и календаря
 * меняются в других местах сразу, а «Оформление» применяется само, поэтому они не считаются.
 */
private fun hasUnsavedChanges(draft: Settings, saved: Settings): Boolean =
    draft.copy(
        monthOverrides = saved.monthOverrides,
        absences = saved.absences,
        calendarOverrides = saved.calendarOverrides,
        wallpaperColors = saved.wallpaperColors,
    ) != saved

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Подзаголовок внутри группы настроек. */
@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/**
 * Поле для необязательной суммы: пустое поле — null («оценить по приложению»).
 * Запятая и точка допустимы; некорректный ввод значение не меняет.
 */
@Composable
private fun NullableDecimalField(
    label: String,
    value: Double?,
    suffix: String? = null,
    placeholder: String = "оценить по приложению",
    onChange: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(value?.let { plainNumber(it) } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            if (input.isBlank()) {
                onChange(null)
            } else {
                input.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()?.let { onChange(it) }
            }
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun plainNumber(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

/** Строка «С 22:00 до 06:00» с выбором времени по нажатию. */
@Composable
private fun TimeRangeRow(
    label: String,
    start: java.time.LocalTime,
    end: java.time.LocalTime,
    onStart: (java.time.LocalTime) -> Unit,
    onEnd: (java.time.LocalTime) -> Unit,
) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { pickTime(ctx, start, onStart) }) { Text(start.format(TIME_FMT)) }
        Text("до")
        TextButton(onClick = { pickTime(ctx, end, onEnd) }) { Text(end.format(TIME_FMT)) }
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
    var confirmReset by remember { mutableStateOf(false) }

    // Разрешения могут поменяться в системных настройках: перепроверяем при возврате на экран.
    var permTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++ }
    val permsOk = remember(permTick) {
        Perms.hasFine(ctx) && Perms.hasBackground(ctx) && Perms.hasNotifications(ctx)
    }

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

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить свои правки?") },
            text = {
                Text("Все дни, которые вы меняли вручную, снова будут как в производственном календаре Башкортостана.")
            },
            confirmButton = {
                TextButton(onClick = {
                    for (date in vm.settings.value.calendarOverrides.keys.toList()) vm.setDayKind(date, null)
                    draft = draft.copy(calendarOverrides = emptyMap())
                    message = "Свои правки календаря сброшены"
                    confirmReset = false
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Отмена") }
            },
        )
    }

    val formulaError = Formula.validate(draft.formula)
    val weekendFormulaError = Formula.validate(draft.weekendFormula)
    val errors = draft.validationErrors()
    val canSave = formulaError == null && weekendFormulaError == null && errors.isEmpty()
    val changed = hasUnsavedChanges(draft, saved)
    val thisMonth = YearMonth.now()

    val save: () -> Unit = {
        if (canSave) {
            vm.saveSettings(draft)
            message = "Сохранено"
        } else {
            message = "Исправьте ошибки в настройках, затем сохраните."
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (changed) {
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                WarningStrip("Есть несохранённые изменения", action = "Сохранить", onClick = save)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

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
                    Text("  Выбрать на карте")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val loc = vm.currentLocation()
                            if (loc == null) {
                                message = "Не удалось определить место. Проверьте разрешение на геолокацию и включённый GPS."
                            } else {
                                draft = draft.copy(workLat = loc.latitude, workLon = loc.longitude)
                                message = "Место работы взято из текущей позиции (точность ${loc.accuracy.toInt()} м). " +
                                    "Нажмите «Сохранить»."
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
                SwitchRow("Вычитать обед", draft.lunchEnabled) { draft = draft.copy(lunchEnabled = it) }
                if (draft.lunchEnabled) {
                    TimeRangeRow(
                        label = "Обед с",
                        start = draft.lunchStart,
                        end = draft.lunchEnd,
                        onStart = { draft = draft.copy(lunchStart = it) },
                        onEnd = { draft = draft.copy(lunchEnd = it) },
                    )
                }
                Hint("Обед вычитается в выходные и из сверхурочной вне смены: выходной с 8:00 до 17:00 — это 8 часов.")
            }

            // ---- подсчёт ----
            SettingsGroup(
                title = "Подсчёт сверхурочной",
                summary = (if (draft.mode == OvertimeMode.AFTER_SHIFT) "После смены" else "До и после смены") +
                    " · шаг ${draft.roundStepMinutes} мин · минимум ${draft.minOvertimeMinutes} мин",
            ) {
                GroupLabel("Что считать сверхурочной")
                RadioRow(draft.mode == OvertimeMode.AFTER_SHIFT, "Только после конца смены") {
                    draft = draft.copy(mode = OvertimeMode.AFTER_SHIFT)
                }
                RadioRow(draft.mode == OvertimeMode.BEFORE_AND_AFTER, "До начала и после конца смены") {
                    draft = draft.copy(mode = OvertimeMode.BEFORE_AND_AFTER)
                }
                SwitchRow("В выходные всё время сверхурочное", draft.weekendAllOvertime) {
                    draft = draft.copy(weekendAllOvertime = it)
                }
                GroupLabel("Округление")
                IntField("Шаг округления", draft.roundStepMinutes, "мин") { draft = draft.copy(roundStepMinutes = it) }
                RadioRow(draft.roundMode == RoundMode.NEAREST, "До ближайшего") {
                    draft = draft.copy(roundMode = RoundMode.NEAREST)
                }
                RadioRow(draft.roundMode == RoundMode.DOWN, "Вниз") { draft = draft.copy(roundMode = RoundMode.DOWN) }
                RadioRow(draft.roundMode == RoundMode.UP, "Вверх") { draft = draft.copy(roundMode = RoundMode.UP) }
                Hint("В расчётных листках часы кратны получасу — шаг 30 минут даст самое близкое совпадение.")
                GroupLabel("Пороги")
                IntField("Минимум за день, меньше не считать", draft.minOvertimeMinutes, "мин") {
                    draft = draft.copy(minOvertimeMinutes = it)
                }
                IntField("Допуск после конца смены", draft.graceAfterShiftMinutes, "мин") {
                    draft = draft.copy(graceAfterShiftMinutes = it)
                }
                IntField("Склеивать, если вернулись в течение", draft.mergeGapMinutes, "мин") {
                    draft = draft.copy(mergeGapMinutes = it)
                }
                Hint("Если вышли ненадолго (например, в магазин) и вернулись быстрее времени склейки, сверхурочная продолжается.")
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
                        "Для отдельного месяца коэффициент или точную сумму можно вписать на вкладке «Месяц».",
                )
                DecimalField("Районный коэффициент", draft.districtPercent, "%") { draft = draft.copy(districtPercent = it) }
                DecimalField("НДФЛ", draft.taxPercent, "%") { draft = draft.copy(taxPercent = it) }
                Hint(
                    "Часовая ставка = оклад / норма часов. ${thisMonth.format(MONTH_FMT).replaceFirstChar { it.uppercase() }}: " +
                        "норма ${formatHours(draft.normFor(thisMonth))}, ставка ${formatMoney(draft.hourlyRate(thisMonth))} в час.",
                )
            }

            // ---- ночные часы ----
            SettingsGroup(title = "Ночные часы", summary = nightSummary(draft)) {
                SwitchRow("Доплачивать за ночные часы", draft.nightEnabled) { draft = draft.copy(nightEnabled = it) }
                if (draft.nightEnabled) {
                    TimeRangeRow(
                        label = "Ночь с",
                        start = draft.nightStart,
                        end = draft.nightEnd,
                        onStart = { draft = draft.copy(nightStart = it) },
                        onEnd = { draft = draft.copy(nightEnd = it) },
                    )
                    DecimalField("Доплата", draft.nightPercent, "%") { draft = draft.copy(nightPercent = it) }
                }
                Hint(
                    "По ст. 154 ТК РФ — не меньше 20% часовой ставки за каждый час с 22:00 до 6:00. " +
                        "Доплата считается на сверхурочные и работу в выходные, которые попали на ночь; " +
                        "районный коэффициент начисляется сверху.",
                )
            }

            // ---- отпускные и больничные ----
            SettingsGroup(title = "Отпускные и больничные", summary = leaveSummary(draft)) {
                GroupLabel("Больничный по стажу")
                RadioRow(draft.sickPercent == 60.0, "Стаж меньше 5 лет — 60%") { draft = draft.copy(sickPercent = 60.0) }
                RadioRow(draft.sickPercent == 80.0, "5–8 лет — 80%") { draft = draft.copy(sickPercent = 80.0) }
                RadioRow(draft.sickPercent == 100.0, "8 лет и больше — 100%") { draft = draft.copy(sickPercent = 100.0) }
                GroupLabel("Начисления для среднего заработка")
                NullableDecimalField("Начисления за 12 месяцев до отпуска", draft.vacationEarnings12m, "₽") {
                    draft = draft.copy(vacationEarnings12m = it)
                }
                NullableDecimalField("Начисления за 2 прошлых года", draft.sickEarnings2y, "₽") {
                    draft = draft.copy(sickEarnings2y = it)
                }
                Hint(
                    "Если поля пустые, приложение оценит средний заработок по своим расчётам (оклад, премия, сверхурочные). " +
                        "Для месяцев до установки приложения берётся оклад с премией. " +
                        "Точные суммы есть в справке о доходах или у бухгалтера.",
                )
                Hint(
                    "Отпуск и больничный отмечаются на вкладке «Месяц» → «Отпуск, больничный, отгул». " +
                        "Выходные внутри отпуска отмечать не нужно.",
                )
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
                        "Проверка (до районного коэффициента): 2 ч 30 мин в будний день = $weekday, " +
                            "8 ч в выходной = $weekend",
                    )
                }
                GroupLabel("Переменные")
                for ((name, description) in Formula.VARIABLES) Hint("$name: $description")
                Hint(
                    "Операции: + - * / % ^, сравнения < > <= >= == !=, логика && || !. " +
                        "Функции: min, max, abs, round, floor, ceil, sqrt, pow, if(условие, да, нет). " +
                        "Дробные числа через точку. Районный коэффициент добавляется сам.",
                )
            }

            // ---- календарь ----
            val overridesCount = saved.calendarOverrides.size
            SettingsGroup(title = "Календарь", summary = "Башкортостан · своих правок: $overridesCount") {
                Hint(
                    "Праздники и переносы РБ учитываются сами. Если правительство объявило новый выходной " +
                        "(например, даты байрамов на 2027 год), откройте «Месяц» → «Отпуск, больничный, отгул» " +
                        "и долго нажмите на день.",
                )
                listOfNotNull(
                    ProductionCalendar.note(thisMonth.year),
                    ProductionCalendar.note(thisMonth.year + 1),
                ).distinct().forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (overridesCount > 0) {
                    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Сбросить свои правки")
                    }
                }
            }

            // ---- уведомления ----
            SettingsGroup(
                title = "Напоминания",
                summary = if (draft.monthReminder) "В конце месяца: включено" else "Выключены",
            ) {
                SwitchRow("Напоминать в последний день месяца", draft.monthReminder) {
                    draft = draft.copy(monthReminder = it)
                }
                Hint("В 18:00 последнего дня месяца придёт итог: сверхурочные и ожидаемая сумма на руки.")
            }

            // ---- быстрый запуск ----
            SettingsGroup(title = "Быстрый запуск", summary = "Плитка в шторке и виджеты") {
                Hint(
                    "Плитка: опустите шторку → карандаш (Изменить) → перетащите «Сверхурочная». " +
                        "Нажатие запускает и останавливает ручную сверхурочную.",
                )
                Hint(
                    "Виджеты: долгое нажатие на рабочем столе → Виджеты → «Сверхурочные». " +
                        "Есть размеры 1×1 (кнопка), 2×1–4×1 (полоса), 3×2 (месяц), 4×2 (подробно).",
                )
            }

            // ---- фоновая работа ----
            SettingsGroup(
                title = "Фоновая работа",
                summary = if (permsOk) "Разрешения выданы" else "Нужны разрешения",
            ) {
                BackgroundWorkTips()
            }

            // ---- оформление (применяется сразу) ----
            val dynamicSupported = Build.VERSION.SDK_INT >= 31
            SettingsGroup(
                title = "Оформление",
                summary = if (saved.wallpaperColors && dynamicSupported) "Цвета из обоев" else "Фирменные цвета",
            ) {
                SwitchRow(
                    "Цвета из обоев (Android 12+)",
                    checked = saved.wallpaperColors && dynamicSupported,
                    enabled = dynamicSupported,
                ) {
                    vm.setWallpaperColors(it)
                    draft = draft.copy(wallpaperColors = it)
                }
                if (!dynamicSupported) {
                    Hint("На этой версии Android цвета из обоев недоступны.")
                } else {
                    Hint("Применяется сразу, сохранять не нужно.")
                }
            }

            for (e in errors) Text(e, color = MaterialTheme.colorScheme.error)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = save,
                enabled = canSave && changed,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (changed) "Сохранить" else "Сохранено") }
        }
    }
}

private fun tryMoney(date: LocalDate, minutes: Int, s: Settings): String = try {
    formatMoney(Calculator.money(date, minutes, s))
} catch (e: Exception) {
    "ошибка"
}
