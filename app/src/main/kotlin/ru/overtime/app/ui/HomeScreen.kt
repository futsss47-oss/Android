package ru.overtime.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.app.data.TimeConv
import ru.overtime.app.location.Perms
import ru.overtime.domain.Calculator
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val GREEN = Color(0xFF2E7D32)
private val AMBER = Color(0xFFFFB300)
private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)

private fun timer(minutes: Int): String = String.format(RU, "%d:%02d", minutes / 60, minutes % 60)

@Composable
fun HomeScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tracker by vm.tracker.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val report by vm.homeReport.collectAsStateWithLifecycle()

    val thisMonth = YearMonth.from(now)
    val summary = remember(report, settings, thisMonth) { Calculator.monthSummary(thisMonth, report, settings) }

    val manualOpen = sessions.firstOrNull { it.manual && it.endMillis == null }
    val manualStart = manualOpen?.let { TimeConv.toLocal(it.startMillis) }
    val runningSince = listOfNotNull(tracker.openStart, manualStart).minOrNull()
    val today = report.days.firstOrNull { it.date == now.toLocalDate() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("Сверхурочные", style = MaterialTheme.typography.headlineMedium)
            Hint(now.format(HEADER_DATE).replaceFirstChar { it.uppercase() })
        }

        PermissionsCard(onPermissionsChanged = { vm.refreshGeofence() })

        StatusCard(
            hasWorkPlace = settings.hasWorkPlace,
            presenceSince = tracker.presenceSince,
            runningSince = runningSince,
            manual = manualOpen != null && tracker.openStart == null,
            now = now,
        )

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BigActionButton(
                running = manualOpen != null,
                onStart = { vm.manualStart() },
                onStop = { vm.manualStop() },
            )
            Hint(
                if (manualOpen != null) {
                    "Ручной режим: нажмите, когда закончите"
                } else {
                    "Ручной запуск, если геолокация подвела"
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                title = "Сегодня",
                value = today?.let { formatMinutes(it.countedMinutes) } ?: "0 мин",
                sub = today?.let { "+" + formatMoney(it.gross) } ?: "сверхурочной нет",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                title = "За месяц",
                value = formatMinutes(report.totalMinutes),
                sub = "+" + formatMoney(report.totalGross),
                modifier = Modifier.weight(1f),
            )
        }

        SoftCard(container = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                "Ожидается на руки за ${thisMonth.format(MONTH_FMT)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                formatMoney(summary.net),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Начислено ${formatMoney(summary.gross)}: оклад, премия, районный коэффициент, выходные и сверхурочные",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            report.formulaError?.let {
                Text("Ошибка в формуле: $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusCard(
    hasWorkPlace: Boolean,
    presenceSince: LocalDateTime?,
    runningSince: LocalDateTime?,
    manual: Boolean,
    now: LocalDateTime,
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color
    val dot: Color
    val title: String
    when {
        runningSince != null -> {
            container = scheme.tertiaryContainer
            dot = AMBER
            title = if (manual) "Идёт сверхурочная (вручную)" else "Идёт сверхурочная"
        }
        !hasWorkPlace -> {
            container = scheme.surfaceContainer
            dot = scheme.outline
            title = "Место работы не задано"
        }
        presenceSince != null -> {
            container = scheme.primaryContainer
            dot = GREEN
            title = "Вы на работе"
        }
        else -> {
            container = scheme.surfaceContainer
            dot = scheme.outline
            title = "Вы не на работе"
        }
    }
    SoftCard(container = container) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(dot)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        when {
            runningSince != null -> {
                val minutes = Duration.between(runningSince, now).toMinutes().toInt().coerceAtLeast(0)
                Text(timer(minutes), style = MaterialTheme.typography.displayMedium)
                Hint("с ${runningSince.format(TIME_FMT)}")
            }
            !hasWorkPlace -> Hint("Откройте «Настройки» → «Место работы»: можно выбрать на карте.")
            presenceSince != null -> Hint("с ${presenceSince.format(TIME_FMT)}. Сверхурочная начнётся сама после конца смены.")
            else -> Hint("Учёт включится сам, когда вы придёте на работу.")
        }
    }
}

@Composable
private fun BigActionButton(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = if (running) onStop else onStart,
        shape = CircleShape,
        modifier = Modifier.size(148.dp),
        contentPadding = PaddingValues(0.dp),
        colors = if (running) {
            ButtonDefaults.buttonColors(containerColor = scheme.tertiary, contentColor = scheme.onTertiary)
        } else {
            ButtonDefaults.buttonColors()
        },
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (running) Icons.Rounded.Check else Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Text(
                if (running) "Закончить" else "Начать",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, sub: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
            Hint(sub)
        }
    }
}

/** Разрешения и подсказка по батарее Samsung. Когда всё выдано, сворачивается в одну строку. */
@Composable
fun PermissionsCard(onPermissionsChanged: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val fine = remember(tick) { Perms.hasFine(ctx) }
    val background = remember(tick) { Perms.hasBackground(ctx) }
    val notifications = remember(tick) { Perms.hasNotifications(ctx) }

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        onPermissionsChanged()
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        onPermissionsChanged()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
    }

    val samsungTip: @Composable () -> Unit = {
        Hint(
            "Samsung: «О приложении → Батарея» → «Без ограничений». " +
                "В «Батарея → Ограничения в фоновом режиме» отключите усыпление неиспользуемых приложений.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", ctx.packageName, null)),
                )
            }) { Text("О приложении") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("Батарея") }
        }
    }

    if (fine && background && notifications) {
        SettingsGroup(title = "Фоновая работа", summary = "Разрешения выданы. Проверьте батарею Samsung") {
            samsungTip()
        }
        return
    }

    SoftCard(container = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "Нужно выдать разрешения",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (!fine) {
            Button(
                onClick = {
                    fineLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("1. Доступ к точной геолокации") }
        } else if (!background) {
            Text(
                "В настройках выберите «Разрешать всегда», иначе учёт не работает, когда приложение закрыто.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 29) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("2. Геолокация «Всегда»") }
        }
        if (!notifications && Build.VERSION.SDK_INT >= 33) {
            FilledTonalButton(
                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("3. Уведомления (фоновая работа и напоминания)") }
        }
        Box(Modifier.padding(top = 4.dp)) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { samsungTip() } }
    }
}
