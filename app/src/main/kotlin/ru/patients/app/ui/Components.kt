package ru.patients.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val RU: Locale = Locale.forLanguageTag("ru-RU")
val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
val LONG_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, EEEE", RU)

fun dayText(epochDay: Long?): String = epochDay?.let { DATE_FMT.format(LocalDate.ofEpochDay(it)) } ?: ""
fun toLdt(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
fun toMs(ldt: LocalDateTime): Long = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun dateTimeText(ms: Long): String = DATE_TIME_FMT.format(toLdt(ms))

/** «Сегодня, 14:30», «Завтра, 09:00» или полная дата. */
fun friendlyDateTime(ms: Long): String {
    val t = toLdt(ms)
    val today = LocalDate.now()
    val prefix = when (t.toLocalDate()) {
        today -> "Сегодня"
        today.plusDays(1) -> "Завтра"
        today.minusDays(1) -> "Вчера"
        else -> DATE_FMT.format(t)
    }
    return "$prefix, ${TIME_FMT.format(t)}"
}

fun pickDate(ctx: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

fun pickTime(ctx: Context, initial: LocalDateTime, onPicked: (Int, Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPicked(h, m) }, initial.hour, initial.minute, true).show()
}

/** Шапка экрана: круглая кнопка «назад», заголовок, действия справа. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Palette.Ink, bg: Color = Color.White) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp)) }
    }
}

/** Белая карточка с мягкой рамкой — основной строительный блок экранов. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val colors = CardDefaults.cardColors(containerColor = color)
    val border = BorderStroke(1.dp, Palette.Line)
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(18.dp), content = content) }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, border = border, content = inner)
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, border = border, content = inner)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(RU), style = SectionTitle, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun fieldColors(unfocused: Color = Palette.GreenMist) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = unfocused,
    disabledContainerColor = Palette.GreenMist,
    focusedBorderColor = Palette.Green,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    disabledTextColor = Palette.Ink,
    disabledLabelColor = Palette.InkSoft,
    disabledTrailingIconColor = Palette.InkSoft,
)

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalize: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(16.dp),
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (capitalize && keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

/** Поле даты: нажатие открывает календарь, крестик очищает. */
@Composable
fun DateField(label: String, epochDay: Long?, onChange: (Long?) -> Unit, defaultYearsBack: Long = 0) {
    val ctx = LocalContext.current
    val open = {
        val init = epochDay?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now().minusYears(defaultYearsBack)
        pickDate(ctx, init) { onChange(it.toEpochDay()) }
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = dayText(epochDay),
            onValueChange = {},
            label = { Text(label) },
            placeholder = { Text("Не указано") },
            enabled = false,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors(),
            trailingIcon = { Icon(Icons.Default.DateRange, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // прозрачный слой ловит нажатие (отключённое поле нажатия не принимает)
        Box(
            Modifier
                .matchParentSize()
                .padding(end = if (epochDay != null) 56.dp else 0.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(remember { MutableInteractionSource() }, null) { open() },
        )
        if (epochDay != null) {
            IconButton(
                onClick = { onChange(null) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 6.dp),
            ) { Icon(Icons.Default.Clear, "Очистить", tint = Palette.InkSoft) }
        }
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Green),
        modifier = modifier.height(52.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.GreenSoft, contentColor = Palette.GreenDark),
        modifier = modifier.height(48.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/** Маленькая «таблетка» статуса. */
@Composable
fun Chip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.White,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if (danger) Palette.Red else Palette.Green),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Palette.InkSoft) } },
    )
}

@Composable
fun EmptyState(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.GreenSoft), contentAlignment = Alignment.Center) {
            Text("✚", color = Palette.Green, style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
