package ru.patients.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.patients.app.PatientsApp
import ru.patients.core.PinHash

private const val MIN_PIN = 4
private const val MAX_PIN = 8

/** Экран установки PIN-кода (первый запуск или смена). */
@Composable
fun PinSetupScreen(title: String = "Придумайте PIN-код", onDone: (String) -> Unit, onCancel: (() -> Unit)? = null) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    PinLayout(
        title = if (first == null) title else "Повторите PIN-код",
        hint = error ?: if (first == null) "От $MIN_PIN до $MAX_PIN цифр. Он защищает данные пациентов." else "Введите тот же PIN ещё раз",
        hintError = error != null,
        pin = pin,
        onDigit = { if (pin.length < MAX_PIN) { pin += it; error = null } },
        onErase = { pin = pin.dropLast(1) },
        confirmEnabled = pin.length >= MIN_PIN,
        onConfirm = {
            val f = first
            if (f == null) {
                first = pin; pin = ""
            } else if (f == pin) {
                onDone(pin)
            } else {
                error = "PIN-коды не совпали, придумайте заново"
                first = null; pin = ""
            }
        },
        bottom = { if (onCancel != null) TextButton(onClick = onCancel) { Text("Отмена", color = Palette.InkSoft) } },
    )
}

/** Экран входа. */
@Composable
fun UnlockScreen(onUnlocked: () -> Unit, onBiometric: (() -> Unit)?, onForgot: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { PatientsApp.settings(ctx) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lockedFor = ((settings.lockedUntil - now) / 1000).coerceAtLeast(0)

    LaunchedEffect(Unit) { onBiometric?.invoke() }
    LaunchedEffect(lockedFor) {
        if (lockedFor > 0) { delay(1000); now = System.currentTimeMillis() }
    }

    fun check(p: String) {
        val salt = settings.pinSalt ?: return
        val hash = settings.pinHash ?: return
        if (PinHash.verify(p, salt, hash)) {
            settings.failedAttempts = 0
            onUnlocked()
        } else {
            val n = settings.failedAttempts + 1
            settings.failedAttempts = n
            pin = ""
            if (n >= 5) {
                // после 5 ошибок пауза растёт: 30 с, 60 с, 90 с…
                settings.lockedUntil = System.currentTimeMillis() + 30_000L * (n - 4)
                now = System.currentTimeMillis()
                error = "Слишком много попыток"
            } else {
                error = "Неверный PIN-код. Осталось попыток до паузы: ${5 - n}"
            }
        }
    }

    PinLayout(
        title = "Учёт пациентов",
        hint = when {
            lockedFor > 0 -> "Повторите через $lockedFor с"
            error != null -> error!!
            else -> "Введите PIN-код"
        },
        hintError = error != null || lockedFor > 0,
        pin = pin,
        enabled = lockedFor == 0L,
        onDigit = {
            if (pin.length < MAX_PIN) {
                pin += it; error = null
            }
        },
        onErase = { pin = pin.dropLast(1) },
        confirmEnabled = pin.length >= MIN_PIN,
        onConfirm = { check(pin) },
        bottom = {
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                if (onBiometric != null) TextButton(onClick = onBiometric) { Text("Отпечаток пальца", color = Palette.Green) }
                TextButton(onClick = onForgot) { Text("Забыли PIN?", color = Palette.InkSoft) }
            }
        },
    )
}

@Composable
private fun PinLayout(
    title: String,
    hint: String,
    hintError: Boolean,
    pin: String,
    onDigit: (String) -> Unit,
    onErase: () -> Unit,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    enabled: Boolean = true,
    bottom: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.GreenSoft, Palette.Bg, Color.White)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Lock, null, tint = Palette.Green, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            color = if (hintError) Palette.Red else Palette.InkSoft,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(16.dp)) {
            val slots = maxOf(MIN_PIN, pin.length)
            repeat(slots) { i ->
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (i < pin.length) Palette.Green else Palette.Mint),
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                for (k in row) {
                    val isOk = k == "OK"
                    val keyEnabled = enabled && (!isOk || confirmEnabled)
                    Surface(
                        onClick = {
                            when (k) {
                                "⌫" -> onErase()
                                "OK" -> onConfirm()
                                else -> onDigit(k)
                            }
                        },
                        enabled = keyEnabled,
                        shape = CircleShape,
                        color = when {
                            isOk && keyEnabled -> Palette.Green
                            isOk -> Palette.Mint
                            else -> Color.White
                        },
                        shadowElevation = if (isOk) 0.dp else 1.dp,
                        modifier = Modifier.size(74.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                k,
                                fontSize = if (isOk) 18.sp else 26.sp,
                                color = if (isOk) Color.White else Palette.Ink,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        bottom()
        Spacer(Modifier.weight(1f))
    }
}
