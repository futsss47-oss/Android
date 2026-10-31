package ru.overtime.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.overtime.domain.AdvanceSummary
import ru.overtime.domain.Calculator
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.MonthOverride
import ru.overtime.domain.MonthSummary
import ru.overtime.domain.ProductionCalendar
import ru.overtime.domain.Settings
import java.time.YearMonth
import java.time.format.DateTimeFormatter

fun formatPercent(v: Double): String = when {
    v == Math.floor(v) -> "${v.toLong()}%"
    else -> String.format(RU, "%.2f%%", v).replace(",00%", "%")
}

/** Название месяца в именительном падеже со строчной: «октябрь». */
private val MONTH_STANDALONE: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL", RU)

/** Название месяца в родительном падеже: «октября». */
private val MONTH_GENITIVE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM", RU)

private val MONTH_ABBR = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** Короткое название месяца для дат: «окт», «ноя». */
fun monthShortName(m: YearMonth): String = MONTH_ABBR[m.monthValue - 1]

/** «октябрь» — для заголовков подстраниц. */
fun monthNameLower(m: YearMonth): String = m.atDay(1).format(MONTH_STANDALONE)

/** «октября» — для дат выплат. */
fun monthNameGenitive(m: YearMonth): String = m.atDay(1).format(MONTH_GENITIVE)

/** Согласование с числом: 1 день, 2 дня, 5 дней. */
fun monthPlural(n: Int, one: String, few: String, many: String): String {
    val n100 = n % 100
    val n10 = n % 10
    return when {
        n100 in 11..14 -> many
        n10 == 1 -> one
        n10 in 2..4 -> few
        else -> many
    }
}

/** Родительный падеж после «из»: «из 21 дня», «из 22 дней». */
fun daysAfterOf(n: Int): String = if (n % 10 == 1 && n % 100 != 11) "дня" else "дней"

/** Шапка подстраницы вкладки «Месяц»: стрелка назад и заголовок. */
@Composable
fun MonthPageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Подстраница «Расчёт как в листке»: норма, ставка, строки начислений, НДФЛ, на руки и аванс.
 * Без ViewModel: всё приходит параметрами, правка месяца уходит в [onSaveOverride].
 */
@Composable
fun PayslipPage(
    month: YearMonth,
    calc: MonthCalc,
    settings: Settings,
    onBack: () -> Unit,
    onSaveOverride: (MonthOverride) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        MonthPageHeader("Расчёт за ${monthNameLower(month)}", onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PayslipTiles(calc.summary)
            PayslipLinesCard(calc, settings)
            AdvanceDetailsCard(month, calc.advance)
            calc.report.formulaError?.let {
                Text(
                    "Ошибка в формуле: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ProductionCalendar.note(month.year)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FilledTonalButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Дни, норма и премия месяца")
            }
            Text(
                "Разовые начисления не учитываются",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (editing) {
        MonthParamsDialog(
            summary = calc.summary,
            autoNorm = settings.calendarNormHours(month).toDouble(),
            defaultBonusPercent = settings.bonusPercent,
            onDismiss = { editing = false },
            onSave = { o ->
                onSaveOverride(o)
                editing = false
            },
        )
    }
}

/** Три плитки: норма часов, ставка в час, отработано дней. */
@Composable
fun PayslipTiles(summary: MonthSummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PayslipTile(
            "Норма",
            formatHours(summary.norm),
            if (summary.normIsAuto) null else "вручную",
            Modifier.weight(1f),
        )
        PayslipTile("Ставка", formatMoney(summary.rate), null, Modifier.weight(1f))
        PayslipTile(
            "Отработано",
            "${summary.workedDays} из ${summary.normDays}",
            if (summary.workedDaysIsAuto) null else "вручную",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun PayslipTile(label: String, value: String, note: String?, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Строка листка: название и подпись слева, сумма справа. */
private data class PayLine(val title: String, val subtitle: String?, val amount: Double)

/** Белая карточка строк листка: начисления, серый блок «Начислено / НДФЛ» и «На руки». */
@Composable
fun PayslipLinesCard(calc: MonthCalc, settings: Settings) {
    val summary = calc.summary
    val report = calc.report
    val leave = calc.leave
    val lines = buildList {
        add(
            PayLine(
                "Оклад",
                if (summary.workedDays < summary.normDays) {
                    "за ${summary.workedDays} " +
                        monthPlural(summary.workedDays, "отработанный день", "отработанных дня", "отработанных дней")
                } else {
                    "полный месяц"
                },
                summary.oklad,
            ),
        )
        add(PayLine("Выходные и сверхурочные", formatMinutes(report.totalMinutes), summary.extra))
        if (summary.night > 0) {
            add(
                PayLine(
                    "Ночные +${formatPercent(settings.nightPercent)}",
                    formatMinutes(report.totalNightMinutes),
                    summary.night,
                ),
            )
        }
        add(
            PayLine(
                if (summary.bonusIsManual) "Премия (из листка)" else "Премия ${formatPercent(summary.bonusPercent)}",
                if (summary.bonusIsManual) null else "от ${formatMoney(summary.bonusBase)}",
                summary.bonus,
            ),
        )
        add(PayLine("Районный коэффициент ${formatPercent(settings.districtPercent)}", null, summary.district))
        if (summary.vacationPay > 0) {
            add(
                PayLine(
                    "Отпускные",
                    "${leave.vacationDays} дн × средний ${formatMoney(leave.vacationAvgDaily)}",
                    summary.vacationPay,
                ),
            )
        }
        if (summary.sickPay > 0) {
            add(
                PayLine(
                    "Больничный",
                    "${leave.sickDays} дн × ${formatMoney(leave.sickDaily)} в день",
                    summary.sickPay,
                ),
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp)) {
            lines.forEachIndexed { i, line ->
                PayslipLineRow(line)
                if (i < lines.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AmountRow("Начислено", formatMoney(summary.gross), bold = true)
                AmountRow("НДФЛ ${formatPercent(settings.taxPercent)}", "−" + formatMoney(summary.tax), bold = false)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "На руки",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    formatMoney(summary.net),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PayslipLineRow(line: PayLine) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp))
            if (line.subtitle != null) {
                Text(
                    line.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            formatMoney(line.amount),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun AmountRow(label: String, value: String, bold: Boolean) {
    val weight = if (bold) FontWeight.SemiBold else null
    val color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            fontWeight = weight,
            color = color,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            fontWeight = weight,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Аванс и остаток с раскрывающимся «Как посчитано». */
@Composable
fun AdvanceDetailsCard(month: YearMonth, advance: AdvanceSummary, initiallyOpen: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "advanceArrow")
    val next = month.plusMonths(1)
    SoftCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AdvanceColumn("Аванс", advance.net, "≈ 26–28 ${monthNameGenitive(month)}", Modifier.weight(1f))
            AdvanceColumn("Остаток", advance.rest, "≈ 10–13 ${monthNameGenitive(next)}", Modifier.weight(1f))
        }
        Row(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { open = !open }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Как посчитано",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (open) "Свернуть" else "Развернуть",
                modifier = Modifier.size(20.dp).rotate(angle),
            )
        }
        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val last = Calculator.ADVANCE_LAST_DAY
                DetailRow(
                    "Оклад за ${advance.workedFirstHalf} из ${advance.firstHalfDays} дн (1–$last)",
                    formatMoney(advance.oklad),
                )
                DetailRow("Выходные и сверхурочные до $last-го", formatMoney(advance.extra))
                DetailRow("Районный", formatMoney(advance.district))
                DetailRow("НДФЛ", "−" + formatMoney(advance.tax))
                Text(
                    "Премия в аванс не входит — она приходит с остатком. Остаток = на руки − аванс.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AdvanceColumn(label: String, amount: Double, note: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            formatMoney(amount),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun parseNumber(text: String): Double? =
    text.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()

private fun plainNumber(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else v.toString()

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    suffix: String,
    isError: Boolean,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        suffix = { Text(suffix) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Правка отработанных дней, нормы и премии одного месяца. Пустое поле = автоматически. */
@Composable
fun MonthParamsDialog(
    summary: MonthSummary,
    autoNorm: Double,
    defaultBonusPercent: Double,
    onDismiss: () -> Unit,
    onSave: (MonthOverride) -> Unit,
) {
    var daysText by remember { mutableStateOf(if (summary.workedDaysIsAuto) "" else summary.workedDays.toString()) }
    var normText by remember { mutableStateOf(if (summary.normIsAuto) "" else plainNumber(summary.norm)) }
    var percentText by remember {
        mutableStateOf(if (summary.bonusPercentIsAuto) "" else plainNumber(summary.bonusPercent))
    }
    var bonusText by remember { mutableStateOf(if (summary.bonusIsManual) plainNumber(summary.bonus) else "") }

    val days = if (daysText.isBlank()) null else daysText.trim().toIntOrNull()
    val norm = if (normText.isBlank()) null else parseNumber(normText)
    val percent = if (percentText.isBlank()) null else parseNumber(percentText)
    val bonus = if (bonusText.isBlank()) null else parseNumber(bonusText)
    val daysBad = daysText.isNotBlank() && (days == null || days < 0 || days > summary.normDays)
    val normBad = normText.isNotBlank() && (norm == null || norm <= 0.0)
    val percentBad = percentText.isNotBlank() && (percent == null || percent < 0.0)
    val bonusBad = bonusText.isNotBlank() && (bonus == null || bonus < 0.0)
    val ok = !daysBad && !normBad && !percentBad && !bonusBad

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(summary.month.format(MONTH_FMT).replaceFirstChar { it.uppercase() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    daysText, { daysText = it }, "Отработано дней", "по календарю", "дн.", daysBad,
                    decimal = false,
                )
                Hint(
                    "Лучше отмечать отпуск и больничный в календаре «Отпуск, больничный, отгул»: тогда точно " +
                        "посчитается и аванс. Это поле нужно, только если хотите вписать число дней вручную.",
                )
                NumberField(normText, { normText = it }, "Норма часов", "авто: ${formatHours(autoNorm)}", "ч", normBad)
                NumberField(
                    percentText, { percentText = it }, "Коэффициент премии", "общий: ${formatPercent(defaultBonusPercent)}",
                    "%", percentBad,
                )
                NumberField(bonusText, { bonusText = it }, "Премия точной суммой", "из листка", "₽", bonusBad)
                Hint(
                    "Пустое поле: дни и норма из календаря РБ, премия по коэффициенту. " +
                        "Если вписать сумму премии из расчётного листка, коэффициент не используется.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(MonthOverride(norm = norm, bonus = bonus, bonusPercent = percent, workedDays = days)) },
                enabled = ok,
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(MonthOverride()) }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
