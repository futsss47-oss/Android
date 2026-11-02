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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.domain.AdvanceSummary
import ru.overtime.domain.Calculator
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.ProductionCalendar
import ru.overtime.domain.Settings
import java.time.YearMonth
import kotlin.math.abs

/** Колонка «дн / ч» таблицы листка. */
private val QTY_COL = 48.dp

/** Колонка суммы таблицы листка. */
private val SUM_COL = 100.dp

/** Промежуток между колонками таблицы. */
private val COL_GAP = 8.dp

/** Строка листка: название, количество (дни или часы) и сумма. */
private data class SheetLine(val title: String, val qty: String?, val amount: Double)

/** Сумма в таблице листка без знака рубля: «21 333,33». */
private fun sheetAmount(v: Double): String = String.format(RU, "%,.2f", v)

/** Часы для колонки «дн / ч»: «6 ч», «6,5 ч». */
private fun hoursOf(minutes: Int): String = formatHours(minutes / 60.0)

/** Сумма из поля сверки: пробелы не важны, запятая и точка равноправны. */
private fun parsePaid(text: String): Double? =
    text.filterNot { it.isWhitespace() }
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }

/**
 * Вкладка «Листок»: расчёт месяца в виде настоящего расчётного листка (начислено, удержано,
 * выплачено), сумма к выплате, сверка с тем, что пришло на карту, и правка параметров месяца.
 */
@Composable
fun ListokScreen(vm: AppViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val calc by vm.monthCalc.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings.monthOverrides[month.toString()]
    var editing by rememberSaveable { mutableStateOf(false) }
    val summary = calc.summary

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonthHeader("Расчётный листок", month) { vm.month.value = it }
            Text(
                month.format(MONTH_FMT).replaceFirstChar { it.uppercase() } +
                    ", норма ${formatHours(summary.norm)}" +
                    (if (summary.normIsAuto) "" else " (вручную)") +
                    ", ставка ${formatMoney(summary.rate)} в час",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SheetCard(month, calc, settings)

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

        PayoutPlate(summary.net)

        ReconcileCard(month = month, expected = summary.net, savedPaid = current?.paid) { vm.savePaid(month, it) }

        FilledTonalButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Норма, дни и премия месяца")
        }

        Text(
            "Разовые начисления не учитываются",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (editing) {
        MonthParamsDialog(
            summary = summary,
            autoNorm = settings.calendarNormHours(month).toDouble(),
            defaultBonusPercent = settings.bonusPercent,
            onDismiss = { editing = false },
            onSave = { o ->
                // Сверку «сколько пришло на карту» диалог не правит: сохраняем прежнее значение.
                vm.saveMonthOverride(month, o.copy(paid = current?.paid))
                editing = false
            },
            paid = current?.paid,
        )
    }
}

/** Белая карточка-таблица: начислено, удержано, выплачено и раскрывашка «Как посчитан аванс». */
@Composable
internal fun SheetCard(month: YearMonth, calc: MonthCalc, settings: Settings) {
    val s = calc.summary
    val report = calc.report
    val leave = calc.leave
    val weekdays = report.days.filter { !it.dayOff }
    val weekends = report.days.filter { it.dayOff }

    val accrued = buildList {
        add(SheetLine("Оклад", "${s.workedDays} дн", s.oklad))
        add(
            SheetLine(
                "Сверхурочные",
                hoursOf(weekdays.sumOf { it.countedMinutes }),
                weekdays.sumOf { it.money },
            ),
        )
        add(
            SheetLine(
                "Работа в выходной",
                hoursOf(weekends.sumOf { it.countedMinutes }),
                weekends.sumOf { it.money },
            ),
        )
        if (s.night > 0) {
            add(SheetLine("Ночные +${formatPercent(settings.nightPercent)}", hoursOf(report.totalNightMinutes), s.night))
        }
        add(
            SheetLine(
                if (s.bonusIsManual) "Премия (из листка)" else "Премия ${formatPercent(s.bonusPercent)}",
                null,
                s.bonus,
            ),
        )
        add(SheetLine("Районный ${formatPercent(settings.districtPercent)}", null, s.district))
        if (s.vacationPay > 0) add(SheetLine("Отпускные", "${leave.vacationDays} дн", s.vacationPay))
        if (s.sickPay > 0) add(SheetLine("Больничный", "${leave.sickDays} дн", s.sickPay))
    }
    val next = month.plusMonths(1)
    val paidOut = listOf(
        SheetLine("Аванс, 26–28 ${monthNameGenitive(month)}", null, calc.advance.net),
        SheetLine("Остаток, 10–13 ${monthNameGenitive(next)}", null, calc.advance.rest),
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        SheetHeader("Начислено", withQty = true, top = 12.dp)
        SheetRows(accrued, withQty = true, dividerAfterLast = true)
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(COL_GAP),
        ) {
            Text(
                "Всего начислено",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                sheetAmount(s.gross),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(SUM_COL),
            )
        }

        SheetHeader("Удержано", withQty = false, top = 14.dp)
        SheetRows(
            listOf(SheetLine("НДФЛ ${formatPercent(settings.taxPercent)}", null, s.tax)),
            withQty = false,
            dividerAfterLast = false,
        )

        SheetHeader("Выплачено", withQty = false, top = 14.dp)
        SheetRows(paidOut, withQty = false, dividerAfterLast = false)
        AdvanceDetails(calc.advance, settings.districtPercent)
    }
}

/** Заголовок раздела таблицы мелким серым и жирная линия под ним. */
@Composable
private fun SheetHeader(title: String, withQty: Boolean, top: Dp) {
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(top = top, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(COL_GAP),
    ) {
        Text(title, style = style, color = color, modifier = Modifier.weight(1f))
        if (withQty) {
            Text("дн / ч", style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(QTY_COL))
        }
        Text("сумма", style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(SUM_COL))
    }
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface)
}

/** Строки раздела, разделённые тонкой линией. */
@Composable
private fun SheetRows(lines: List<SheetLine>, withQty: Boolean, dividerAfterLast: Boolean) {
    lines.forEachIndexed { i, line ->
        SheetRow(line, withQty)
        if (i < lines.lastIndex || dividerAfterLast) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SheetRow(line: SheetLine, withQty: Boolean) {
    val style = MaterialTheme.typography.bodyMedium
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(COL_GAP),
        verticalAlignment = Alignment.Top,
    ) {
        Text(line.title, style = style, modifier = Modifier.weight(1f))
        if (withQty) {
            Text(
                line.qty.orEmpty(),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(QTY_COL),
            )
        }
        Text(
            sheetAmount(line.amount),
            style = style,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(SUM_COL),
        )
    }
}

/** Раскрывашка под «Выплачено»: из чего сложился аванс. */
@Composable
private fun AdvanceDetails(advance: AdvanceSummary, districtPercent: Double) {
    var open by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "advanceArrow")
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { open = !open }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Как посчитан аванс",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (open) "Свернуть" else "Развернуть",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).rotate(angle),
        )
    }
    AnimatedVisibility(visible = open) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val last = Calculator.ADVANCE_LAST_DAY
            DetailRow(
                "Оклад за ${advance.workedFirstHalf} из ${advance.firstHalfDays} дн (1–$last)",
                sheetAmount(advance.oklad),
            )
            DetailRow("Выходные и сверхурочные до $last-го", sheetAmount(advance.extra))
            DetailRow("Районный ${formatPercent(districtPercent)}", sheetAmount(advance.district))
            DetailRow("НДФЛ", "−" + sheetAmount(advance.tax))
            DetailRow("Аванс", sheetAmount(advance.net), strong = true)
            Hint("Премия в аванс не входит, она приходит с остатком.")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, strong: Boolean = false) {
    val color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val weight = if (strong) FontWeight.SemiBold else null
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(COL_GAP),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = weight,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = weight,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(SUM_COL),
        )
    }
}

/** Плашка «К выплате» цвета пихты: подпись слева, сумма справа. */
@Composable
internal fun PayoutPlate(net: Double) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(Brand.Pikhta)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "К выплате",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.Blank,
            modifier = Modifier.alignByBaseline().padding(end = 12.dp),
        )
        MoneyText(
            value = net,
            big = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp),
            color = Brand.Blank,
            mutedColor = Brand.OnPikhtaMuted,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/**
 * Сверка: сколько пришло на карту за месяц против расчёта.
 * Каждое корректное значение сразу уходит в [onPaid], пустое поле — null.
 */
@Composable
internal fun ReconcileCard(month: YearMonth, expected: Double, savedPaid: Double?, onPaid: (Double?) -> Unit) {
    var text by rememberSaveable(month) { mutableStateOf(savedPaid?.let { sheetAmount(it) } ?: "") }
    val parsed = parsePaid(text)
    val bad = text.isNotBlank() && parsed == null

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Сколько пришло на карту", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                if (input.isBlank()) onPaid(null) else parsePaid(input)?.let(onPaid)
            },
            placeholder = { Text("например, 64 395,40") },
            suffix = { Text("₽") },
            isError = bad,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            bad -> Text(
                "Не получилось прочитать сумму. Впишите число, например 64 395,40.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            parsed == null -> Hint("Впишите сумму из банка, и мы сравним её с расчётом.")
            else -> {
                val d = parsed - expected
                when {
                    abs(d) < 1.0 -> DiffPlate("Совпадает с расчётом.", Brand.GreenContainer, Brand.Pikhta)
                    d < 0 -> DiffPlate(
                        "Недоплата ${formatMoney(-d)}. Проверьте часы в табеле и премию за месяц.",
                        Brand.Warning,
                        Brand.Ochre,
                    )
                    else -> DiffPlate(
                        "Пришло больше на ${formatMoney(d)}. Возможно, разовая выплата.",
                        Brand.GreenContainer,
                        Brand.Pikhta,
                    )
                }
            }
        }
        Text(
            "Сравниваем аванс и остаток за этот месяц вместе",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Плашка результата сверки. */
@Composable
private fun DiffPlate(text: String, container: Color, content: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        fontWeight = FontWeight.Medium,
        color = content,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
