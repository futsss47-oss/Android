package ru.patients.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ru.patients.app.reminders.Alarms
import ru.patients.app.ui.HomeScreen
import ru.patients.app.ui.Nav
import ru.patients.app.ui.Palette
import ru.patients.app.ui.PatientListScreen
import ru.patients.app.ui.PatientScreen
import ru.patients.app.ui.PatientsTheme
import ru.patients.app.ui.PinSetupScreen
import ru.patients.app.ui.Screen
import ru.patients.app.ui.SettingsScreen
import ru.patients.app.ui.TodayScreen
import ru.patients.app.ui.UnlockScreen
import ru.patients.core.PinHash

/** Состояние входа живёт, пока жив процесс: поворот экрана не требует PIN заново. */
object Session {
    val unlocked = mutableStateOf(false)
    var backgroundAt = 0L
    val pendingPatient = mutableStateOf<String?>(null)
    const val LOCK_AFTER_MS = 60_000L
}

class MainActivity : FragmentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // медицинские данные: не показывать в списке недавних и не давать делать скриншоты
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        handleIntent(intent)
        setContent { PatientsTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra(Alarms.EXTRA_PATIENT)?.let { Session.pendingPatient.value = it }
    }

    override fun onStart() {
        super.onStart()
        val bg = Session.backgroundAt
        if (bg > 0 && System.currentTimeMillis() - bg > Session.LOCK_AFTER_MS) Session.unlocked.value = false
        Session.backgroundAt = 0
    }

    override fun onStop() {
        super.onStop()
        Session.backgroundAt = System.currentTimeMillis()
        if (Session.unlocked.value) PatientsApp.syncInBackground(this)
    }

    fun onUnlocked() {
        Session.unlocked.value = true
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PatientsApp.syncInBackground(this)
    }

    fun biometricAvailable(): Boolean =
        PatientsApp.settings(this).biometric &&
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun askBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    PatientsApp.settings(this@MainActivity).failedAttempts = 0
                    onUnlocked()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в «Учёт пациентов»")
            .setNegativeButtonText("Ввести PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        runCatching { prompt.authenticate(info) }
    }

    @Composable
    private fun Root() {
        val settings = PatientsApp.settings(this)
        val repo = PatientsApp.repo(this)
        var hasPin by remember { mutableStateOf(settings.hasPin) }
        val unlocked by Session.unlocked
        var askWipe by remember { mutableStateOf(false) }
        var loadError by remember { mutableStateOf(repo.loadError) }

        when {
            !hasPin -> PinSetupScreen(onDone = { pin ->
                val salt = PinHash.newSalt()
                settings.setPin(salt, PinHash.hash(pin, salt))
                hasPin = true
                onUnlocked()
            })
            !unlocked -> UnlockScreen(
                onUnlocked = { onUnlocked() },
                onBiometric = if (biometricAvailable()) ({ askBiometric() }) else null,
                onForgot = { askWipe = true },
            )
            else -> MainContent()
        }

        if (askWipe) {
            AlertDialog(
                onDismissRequest = { askWipe = false },
                shape = RoundedCornerShape(28.dp),
                containerColor = androidx.compose.ui.graphics.Color.White,
                title = { Text("Сбросить приложение?") },
                text = {
                    Text(
                        "PIN-код восстановить нельзя. Можно стереть все данные на этом телефоне и задать новый PIN. " +
                            "Если была синхронизация, после настройки данные вернутся с Яндекс Диска (понадобятся токен и пароль синхронизации).",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            askWipe = false
                            repo.wipe()
                            settings.clearAll()
                            hasPin = false
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Red),
                    ) { Text("Стереть всё") }
                },
                dismissButton = { TextButton(onClick = { askWipe = false }) { Text("Отмена", color = Palette.InkSoft) } },
            )
        }
        if (unlocked && loadError != null) {
            AlertDialog(
                onDismissRequest = { loadError = null },
                shape = RoundedCornerShape(28.dp),
                containerColor = androidx.compose.ui.graphics.Color.White,
                title = { Text("Внимание") },
                text = { Text(loadError ?: "") },
                confirmButton = { TextButton(onClick = { loadError = null }) { Text("Понятно", color = Palette.Green) } },
            )
        }
    }

    @Composable
    private fun MainContent() {
        val nav = remember { Nav() }
        val pending by Session.pendingPatient
        LaunchedEffect(pending) {
            pending?.let {
                nav.openPatient(it)
                Session.pendingPatient.value = null
            }
        }
        BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
        val toast: (String) -> Unit = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }

        AnimatedContent(
            targetState = nav.current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screens",
        ) { screen ->
            Column {
                when (screen) {
                    Screen.Home -> HomeScreen(nav)
                    is Screen.List -> PatientListScreen(screen.type, nav)
                    Screen.Search -> PatientListScreen(null, nav)
                    is Screen.PatientCard -> PatientScreen(screen.id, nav)
                    Screen.Today -> TodayScreen(nav)
                    Screen.Settings -> SettingsScreen(nav, toast)
                    Screen.ChangePin -> PinSetupScreen(
                        title = "Новый PIN-код",
                        onDone = { pin ->
                            val settings = PatientsApp.settings(this@MainActivity)
                            val salt = PinHash.newSalt()
                            settings.setPin(salt, PinHash.hash(pin, salt))
                            toast("PIN-код изменён")
                            nav.pop()
                        },
                        onCancel = { nav.pop() },
                    )
                }
            }
        }
    }
}
