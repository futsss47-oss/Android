package ru.patients.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.patients.app.PatientsApp
import ru.patients.app.reminders.Alarms
import ru.patients.app.sync.SyncManager
import ru.patients.app.sync.YandexDisk
import ru.patients.core.PasswordCrypto
import ru.patients.core.StoreCodec
import java.time.LocalDate

@Composable
fun SettingsScreen(nav: Nav, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { PatientsApp.settings(ctx) }
    val repo = PatientsApp.repo(ctx)
    val scope = rememberCoroutineScope()
    val sync by SyncManager.status.collectAsState()
    var refresh by remember { mutableIntStateOf(0) } // перечитать состояние разрешений после возврата

    // ---- Безопасность ----
    var biometric by remember { mutableStateOf(settings.biometric) }
    val bioAvailable = remember {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // ---- Синхронизация ----
    var clientId by remember { mutableStateOf(settings.yandexClientId) }
    var token by remember { mutableStateOf(settings.yandexToken ?: "") }
    var syncPass by remember { mutableStateOf(settings.syncPassword ?: "") }
    var askDisconnect by remember { mutableStateOf(false) }

    // ---- Копии ----
    var backupMode by remember { mutableStateOf<String?>(null) } // "export" / "import"
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { pendingUri = uri; backupMode = "export" }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingUri = uri; backupMode = "import" }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        TopBar("Настройки", onBack = { nav.pop() })
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(8.dp))

            // ===== Безопасность =====
            SoftCard {
                SectionHeader("Безопасность")
                SoftButton("Сменить PIN-код", { nav.push(Screen.ChangePin) }, Modifier.fillMaxWidth(), icon = Icons.Default.Lock)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Вход по отпечатку", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (bioAvailable) "PIN-код останется запасным способом" else "На телефоне не настроен отпечаток или лицо",
                            color = Palette.InkSoft,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = biometric && bioAvailable,
                        enabled = bioAvailable,
                        onCheckedChange = { biometric = it; settings.biometric = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Green),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Приложение блокируется, если свернуть его больше чем на минуту. В списке недавних приложений содержимое скрыто.",
                    color = Palette.InkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(14.dp))

            // ===== Уведомления =====
            SoftCard {
                SectionHeader("Уведомления")
                refresh.let { } // перечитать при возврате из системных настроек
                val notifOk = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                val exactOk = Alarms.canExact(ctx)
                val batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
                StatusLine("Показ уведомлений", notifOk, "Разрешить") {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                StatusLine("Точное время срабатывания", exactOk, "Разрешить") {
                    if (Build.VERSION.SDK_INT >= 31) {
                        openSafe(ctx, Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }
                }
                StatusLine("Работа без ограничений батареи", batteryOk, "Настроить") {
                    @Suppress("BatteryLife")
                    openSafe(ctx, Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
                Spacer(Modifier.height(8.dp))
                SoftButton("Проверить: уведомление через 1 минуту", {
                    Alarms.scheduleTest(ctx)
                    onToast("Заблокируйте экран и подождите минуту")
                }, Modifier.fillMaxWidth(), icon = Icons.Default.Notifications)
                TextButton(onClick = { refresh++ }) { Text("Обновить состояние", color = Palette.Green) }
            }
            Spacer(Modifier.height(14.dp))

            // ===== Синхронизация =====
            SoftCard {
                SectionHeader("Синхронизация через Яндекс Диск")
                Text(
                    "1. Откройте oauth.yandex.ru, создайте приложение, выберите доступ «Яндекс Диск REST API → Доступ к папке приложения на Диске», " +
                        "в Redirect URI укажите https://oauth.yandex.ru/verification_code.\n" +
                        "2. Скопируйте ClientID сюда и нажмите «Войти в Яндекс».\n" +
                        "3. Скопируйте показанный Яндексом токен в поле ниже.\n" +
                        "4. Придумайте пароль синхронизации и введите его одинаково на обоих телефонах.",
                    color = Palette.InkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Field(clientId, { clientId = it }, "ClientID приложения Яндекса", capitalize = false)
                SoftButton("Войти в Яндекс", {
                    settings.yandexClientId = clientId
                    openSafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(YandexDisk.authUrl(clientId.trim()))))
                }, Modifier.fillMaxWidth(), enabled = clientId.isNotBlank())
                Spacer(Modifier.height(12.dp))
                SecretField(token, { token = it.trim() }, "Токен Яндекса")
                SecretField(syncPass, { syncPass = it }, "Пароль синхронизации")
                PillButton("Сохранить и синхронизировать", {
                    settings.yandexClientId = clientId
                    settings.yandexToken = token
                    settings.syncPassword = syncPass
                    scope.launch {
                        val err = SyncManager.sync(ctx)
                        onToast(err ?: "Синхронизировано")
                    }
                }, Modifier.fillMaxWidth(), icon = Icons.Default.Refresh, enabled = token.isNotBlank() && syncPass.length >= 6 && !sync.running)
                if (syncPass.isNotEmpty() && syncPass.length < 6) {
                    Text("Пароль синхронизации — не короче 6 символов", color = Palette.Red, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                val statusText = when {
                    sync.running -> "Идёт синхронизация…"
                    sync.error != null -> "Ошибка: ${sync.error}"
                    sync.lastAt > 0 -> "Последняя синхронизация: ${dateTimeText(sync.lastAt)}"
                    else -> "Ещё не синхронизировалось"
                }
                Text(statusText, color = if (sync.error != null) Palette.Red else Palette.InkSoft, style = MaterialTheme.typography.bodyMedium)
                if (settings.syncConfigured) {
                    TextButton(onClick = { askDisconnect = true }) { Text("Отключить синхронизацию", color = Palette.Red) }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ===== Резервная копия =====
            SoftCard {
                SectionHeader("Резервная копия")
                Text(
                    "Файл шифруется паролем. Храните его, например, в «Загрузках» или на флешке. При восстановлении данные из файла объединяются с текущими.",
                    color = Palette.InkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Сохранить", { exportLauncher.launch("patients_${LocalDate.now()}.ptsb") }, Modifier.weight(1f))
                    SoftButton("Восстановить", { importLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(14.dp))
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            Text("Учёт пациентов, версия $version. Данные хранятся зашифрованными только на телефоне и в вашей папке на Яндекс Диске.", color = Palette.InkSoft, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (askDisconnect) {
        ConfirmDialog(
            title = "Отключить синхронизацию?",
            text = "Токен и пароль будут удалены с этого телефона. Данные на телефоне и на Диске останутся.",
            confirm = "Отключить",
            onConfirm = {
                settings.yandexToken = null
                settings.syncPassword = null
                settings.lastSyncError = null
                token = ""; syncPass = ""
                SyncManager.init(ctx)
            },
            onDismiss = { askDisconnect = false },
        )
    }

    val uri = pendingUri
    if (backupMode != null && uri != null) {
        BackupPasswordDialog(
            export = backupMode == "export",
            onDismiss = { backupMode = null; pendingUri = null },
            onPassword = { pass ->
                val mode = backupMode
                backupMode = null; pendingUri = null
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        try {
                            if (mode == "export") {
                                val bytes = PasswordCrypto.encrypt(StoreCodec.encode(repo.state.value), pass)
                                ctx.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                                "Копия сохранена"
                            } else {
                                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                                val store = StoreCodec.decode(PasswordCrypto.decrypt(bytes, pass))
                                repo.mergeFrom(store, markDirty = true)
                                "Восстановлено пациентов: ${store.patients.count { !it.deleted }}"
                            }
                        } catch (e: ru.patients.core.WrongPasswordException) {
                            "Неверный пароль копии"
                        } catch (e: Exception) {
                            "Не получилось: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                    onToast(msg)
                    if (mode == "import") PatientsApp.syncInBackground(ctx)
                }
            },
        )
    }
}

@Composable
private fun StatusLine(title: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "!", color = if (ok) Palette.Green else Palette.Amber, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onAction) { Text(action, color = Palette.Green) }
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = fieldColors(),
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Скрыть" else "Показать", color = Palette.Green) } },
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

@Composable
private fun BackupPasswordDialog(export: Boolean, onDismiss: () -> Unit, onPassword: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    val ok = if (export) pass.length >= 6 && pass == pass2 else pass.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.White,
        title = { Text(if (export) "Пароль для копии" else "Пароль от копии") },
        text = {
            Column {
                if (export) Text("Без этого пароля копию не открыть. Запишите его.", color = Palette.InkSoft, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SecretField(pass, { pass = it }, "Пароль")
                if (export) {
                    SecretField(pass2, { pass2 = it }, "Повторите пароль")
                    if (pass.isNotEmpty() && pass.length < 6) Text("Не короче 6 символов", color = Palette.Red, style = MaterialTheme.typography.bodySmall)
                    else if (pass2.isNotEmpty() && pass != pass2) Text("Пароли не совпадают", color = Palette.Red, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPassword(pass) }, enabled = ok, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Palette.Green)) {
                Text(if (export) "Сохранить" else "Восстановить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Palette.InkSoft) } },
    )
}

fun openSafe(ctx: android.content.Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        runCatching {
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
