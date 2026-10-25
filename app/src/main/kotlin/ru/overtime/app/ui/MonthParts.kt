package ru.overtime.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import ru.overtime.domain.MonthSummary
import ru.overtime.domain.ProductionCalendar
import ru.overtime.domain.Report

fun formatPercent(v: Double): String =
    if (v == Math.floor(v)) "${v.toLong()}%" else String.format(RU, "%.1f%%", v)

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
            HorizontalDivider()
            SummaryLine("Оклад", formatMoney(summary.salary))
            SummaryLine(if (summary.bonusIsAuto) "Премия" else "Премия (вручную)", formatMoney(summary.bonus))
            SummaryLine(
                "Выходные и сверхурочные, ${formatMinutes(report.totalMinutes)}",
                formatMoney(summary.extra),
            )
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
            Text(
                "Оклад и премия плановые: больничные, отпуска и разовые начисления приложение не учитывает.",
                style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(onClick = onEdit) { Text("Изменить норму или премию месяца") }
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

/** Правка нормы часов и премии одного месяца. Пустое поле = автоматически. */
@Composable
fun MonthParamsDialog(
    summary: MonthSummary,
    autoNorm: Double,
    defaultBonus: Double,
    onDismiss: () -> Unit,
    onSave: (norm: Double?, bonus: Double?) -> Unit,
) {
    var normText by remember { mutableStateOf(if (summary.normIsAuto) "" else plainNumber(summary.norm)) }
    var bonusText by remember { mutableStateOf(if (summary.bonusIsAuto) "" else plainNumber(summary.bonus)) }

    val norm = if (normText.isBlank()) null else parseNumber(normText)
    val bonus = if (bonusText.isBlank()) null else parseNumber(bonusText)
    val normBad = normText.isNotBlank() && (norm == null || norm <= 0.0)
    val bonusBad = bonusText.isNotBlank() && (bonus == null || bonus < 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(summary.month.format(MONTH_FMT).replaceFirstChar { it.uppercase() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = normText,
                    onValueChange = { normText = it },
                    label = { Text("Норма часов") },
                    placeholder = { Text("авто: ${formatHours(autoNorm)}") },
                    suffix = { Text("ч") },
                    isError = normBad,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bonusText,
                    onValueChange = { bonusText = it },
                    label = { Text("Премия") },
                    placeholder = { Text("из настроек: ${formatMoney(defaultBonus)}") },
                    suffix = { Text("₽") },
                    isError = bonusBad,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Пустое поле: норма берётся из календаря РБ, премия из настроек. " +
                        "Норму можно сверить с расчётным листком (колонка «Часы» у оплаты по окладу).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(norm, bonus) }, enabled = !normBad && !bonusBad) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null, null) }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
