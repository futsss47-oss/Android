package ru.overtime.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.overtime.app.location.Perms
import java.time.Duration
import android.Manifest

@Composable
fun HomeScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tracker by vm.tracker.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val report by vm.homeReport.collectAsStateWithLifecycle()

    val manualOpen = sessions.firstOrNull { it.manual && it.endMillis == null }
    val today = report.days.firstOrNull { it.date == now.toLocalDate() }
    val autoStart = tracker.openStart

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Сверхурочные", style = MaterialTheme.typography.headlineMedium)

        PermissionsCard(onPermissionsChanged = { vm.refreshGeofence() })

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сейчас", style = MaterialTheme.typography.titleMedium)
                val presence = tracker.presenceSince
                Text(
                    when {
                        !settings.hasWorkPlace -> "Место работы не задано. Откройте «Настройки»."
                        presence != null -> "Вы на работе с ${presence.format(TIME_FMT)}"
                        else -> "Вас нет на работе"
                    },
                )
                if (autoStart != null) {
                    val minutes = Duration.between(autoStart, now).toMinutes().toInt().coerceAtLeast(0)
                    Text("Сверхурочная идёт с ${autoStart.format(TIME_FMT)} (${formatMinutes(minutes)})")
                }
                if (manualOpen != null) {
                    val start = ru.overtime.app.data.TimeConv.toLocal(manualOpen.startMillis)
                    val minutes = Duration.between(start, now).toMinutes().toInt().coerceAtLeast(0)
                    Text("Ручная сверхурочная идёт с ${start.format(TIME_FMT)} (${formatMinutes(minutes)})")
                }
            }
        }

        if (manualOpen == null) {
            Button(onClick = { vm.manualStart() }, modifier = Modifier.fillMaxWidth()) {
                Text("Начать сверхурочную вручную")
            }
        } else {
            Button(onClick = { vm.manualStop() }, modifier = Modifier.fillMaxWidth()) {
                Text("Закончить сверхурочную")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сегодня", style = MaterialTheme.typography.titleMedium)
                if (today == null) {
                    Text("Сверхурочной нет")
                } else {
                    Text("${formatMinutes(today.countedMinutes)}, ${formatMoney(today.money)}")
                    if (today.rawMinutes != today.countedMinutes) {
                        Text(
                            "Фактически ${formatMinutes(today.rawMinutes)}, округлено по настройкам",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Этот месяц", style = MaterialTheme.typography.titleMedium)
                Text("${formatMinutes(report.totalMinutes)}, ${formatMoney(report.totalMoney)}")
                report.formulaError?.let {
                    Text("Ошибка в формуле: $it", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Шаги выдачи разрешений и подсказка по настройкам батареи Samsung. */
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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fine && background && notifications) {
                Text("✅ Разрешения выданы", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Нужно выдать разрешения", style = MaterialTheme.typography.titleMedium)
            }
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
                    "В настройках выберите «Разрешать всегда», иначе учёт не будет работать, когда приложение закрыто.",
                    style = MaterialTheme.typography.bodySmall,
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
                OutlinedButton(
                    onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("3. Уведомления (иконка работы в фоне)") }
            }

            Text("Samsung: чтобы система не усыпляла приложение", style = MaterialTheme.typography.titleSmall)
            Text(
                "Откройте «О приложении → Батарея» и выберите «Без ограничений». " +
                    "В «Батарея → Ограничения в фоновом режиме» отключите автоматическое усыпление неиспользуемых приложений.",
                style = MaterialTheme.typography.bodySmall,
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
    }
}
