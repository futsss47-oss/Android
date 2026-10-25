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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.patients.app.PatientsApp
import ru.patients.app.sync.SyncManager
import ru.patients.core.PatientType
import ru.patients.core.Texts
import java.time.LocalDate

@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val store by PatientsApp.repo(ctx).state.collectAsState()
    val sync by SyncManager.status.collectAsState()
    val now = System.currentTimeMillis()
    val active = store.visibleReminders().filter { !it.done }
    val overdue = active.count { it.atMillis <= now }
    val week = active.count { it.atMillis in (now + 1)..(now + 7 * 86_400_000L) }
    val mainCount = store.activePatients(PatientType.MAIN).size
    val diagCount = store.activePatients(PatientType.DIAG).size

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Palette.GreenSoft, 0.35f to Palette.Bg))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    LONG_DATE_FMT.format(LocalDate.now()).replaceFirstChar { it.uppercase() },
                    color = Palette.InkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Учёт пациентов", style = MaterialTheme.typography.headlineMedium)
            }
            RoundIconButton(Icons.Default.Search, "Поиск", { nav.push(Screen.Search) })
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Default.Settings, "Настройки", { nav.push(Screen.Settings) })
        }
        if (sync.error != null && PatientsApp.settings(ctx).syncConfigured) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Синхронизация: ${sync.error}",
                color = Palette.Amber,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.AmberSoft).padding(12.dp).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))

        BigTile(
            title = "Основные пациенты",
            subtitle = "$mainCount ${Texts.plural(mainCount, "пациент", "пациента", "пациентов")} на учёте",
            icon = Icons.Default.Person,
            filled = true,
            onClick = { nav.push(Screen.List(PatientType.MAIN)) },
        )
        Spacer(Modifier.height(14.dp))
        BigTile(
            title = "Диагностические пациенты",
            subtitle = "$diagCount ${Texts.plural(diagCount, "пациент", "пациента", "пациентов")} на обследовании",
            icon = Icons.Default.Search,
            filled = false,
            onClick = { nav.push(Screen.List(PatientType.DIAG)) },
        )
        Spacer(Modifier.height(14.dp))
        SoftCard(onClick = { nav.push(Screen.Today) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(if (overdue > 0) Palette.RedSoft else Palette.GreenSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Notifications, null, tint = if (overdue > 0) Palette.Red else Palette.Green)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Сроки и напоминания", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (overdue > 0) Chip("Просрочено: $overdue", Palette.Red, Palette.RedSoft)
                        Chip("На неделе: $week", Palette.GreenDark, Palette.GreenSoft)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.InkSoft)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigTile(title: String, subtitle: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit) {
    val bg = if (filled) Brush.linearGradient(listOf(Color(0xFF2E8B57), Color(0xFF3FAE73))) else Brush.linearGradient(listOf(Color.White, Color.White))
    val fg = if (filled) Color.White else Palette.Ink
    val sub = if (filled) Color.White.copy(alpha = 0.85f) else Palette.InkSoft
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        shadowElevation = if (filled) 4.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.background(bg).padding(22.dp).fillMaxWidth()) {
            Column {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (filled) Color.White.copy(alpha = 0.22f) else Palette.GreenSoft),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = if (filled) Color.White else Palette.Green, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = fg, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, color = sub, style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (filled) Color.White else Palette.GreenSoft),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.Green) }
                }
            }
        }
    }
}
