package ru.overtime.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.overtime.domain.MonthOverride
import ru.overtime.domain.MonthSummary
import ru.overtime.domain.ProductionCalendar
import ru.overtime.domain.Report

fun formatPercent(v: Double): String = when {
    v == Math.floor(v) -> "${v.toLong()}%"
    else -> String.format(RU, "%.2f%%", v).replace(",00%", "%")
}

/** Расчёт месяца в виде расчётного листка. */
@Composable
fun MonthSummaryCard(
    summary: MonthSummary,
    report: Report,
    districtPercent: Double,
    taxPercent: Double,
    onEdit: () -> Unit,
) {
    SoftCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Расчёт как в листке", style = MaterialTheme.typography.titleMedium)
            SummaryLine(
                "Норма часов",
                formatHours(summary.norm) + if (summary.normIsAuto) " (календарь РБ)" else " (вручную)",
            )
            SummaryLine("Ставка в час", formatMoney(summary.rate))
            SummaryLine(
                "Отработано дней",
                "${summary.workedDays} из ${summary.normDays}" + if (summary.workedDaysIsAuto) "" else " (вручную)",
            )
            HorizontalDivider()
            SummaryLine(
                if (summary.workedDays < summary.normDays) "Оклад за отработанные дни" else "Оклад",
                formatMoney(summary.oklad),
            )
            SummaryLine(
                "Выходные и сверхурочные, ${formatMinutes(report.totalMinutes)}",
                formatMoney(summary.extra),
            )
            SummaryLine(
                if (summary.bonusIsManual) {
                    "Премия (из листка)"
                } else {
                    "Премия ${formatPercent(summary.bonusPercent)}" + if (summary.bonusPercentIsAuto) "" else " (вручную)"
                },
                formatMoney(summary.bonus),
            )
            if (!summary.bonusIsManual) {
                Hint("от ${formatMoney(summary.bonusBase)}: оклад за отработанное + выходные одинарно + сверхурочные")
            }
            SummaryLine("Районный коэффициент ${formatPercent(districtPercent)}", formatMoney(summary.district))
            SummaryLine("Начислено", formatMoney(summary.gross), bold = true)
            SummaryLine("НДФЛ ${formatPercent(taxPercent)}", "−" + formatMoney(summary.tax))
            SummaryLine("На руки", formatMoney(summary.net), bold = true)
            report.formulaError?.let {
                Text("Ошибка в формуле: $it", color = MaterialTheme.colorScheme.error)
            }
            ProductionCalendar.note(summary.month.year)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Hint("Больничные, отпускные и разовые начисления не учитываются.")
            FilledTonalButton(onClick = onEdit) { Text("Дни, норма и премия месяца") }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = weight, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(value, fontWeight = weight)
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
                    daysText, { daysText = it }, "Отработано дней", "все: ${summary.normDays}", "дн.", daysBad,
                    decimal = false,
                )
                Hint("Если были больничный, отпуск или отгул. Оклад и премия уменьшатся пропорционально.")
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
