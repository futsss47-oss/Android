package ru.overtime.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.overtime.domain.MonthOverride
import ru.overtime.domain.MonthSummary
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

/**
 * Правка отработанных дней, нормы и премии одного месяца. Пустое поле = автоматически.
 * [paid] — сумма сверки «сколько пришло на карту»: диалог её не правит, но кладёт в новый
 * [MonthOverride] при «Сохранить» и «Сбросить», чтобы она не потерялась.
 */
@Composable
fun MonthParamsDialog(
    summary: MonthSummary,
    autoNorm: Double,
    defaultBonusPercent: Double,
    onDismiss: () -> Unit,
    onSave: (MonthOverride) -> Unit,
    paid: Double? = null,
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
                onClick = {
                    onSave(
                        MonthOverride(norm = norm, bonus = bonus, bonusPercent = percent, workedDays = days, paid = paid),
                    )
                },
                enabled = ok,
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(MonthOverride(paid = paid)) }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
