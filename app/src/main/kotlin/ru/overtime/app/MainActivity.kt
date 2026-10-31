package ru.overtime.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.overtime.app.ui.AppTheme
import ru.overtime.app.ui.AppViewModel
import ru.overtime.app.ui.HomeScreen
import ru.overtime.app.ui.MonthScreen
import ru.overtime.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    /** Вкладка, которую попросили открыть через intent extra [EXTRA_TAB]; null — не трогать текущую. */
    private var requestedTab by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // При пересоздании (поворот) вкладку восстанавливает rememberSaveable, extra повторно не применяем.
        if (savedInstanceState == null) requestedTab = tabFrom(intent)
        val repo = (application as OvertimeApp).settingsRepo
        setContent {
            val settings by repo.flow.collectAsStateWithLifecycle()
            AppTheme(dynamic = settings.wallpaperColors) {
                Root(requestedTab = requestedTab, onTabConsumed = { requestedTab = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFrom(intent)?.let { requestedTab = it }
    }

    private fun tabFrom(intent: Intent?): Int? {
        if (intent == null || !intent.hasExtra(EXTRA_TAB)) return null
        return intent.getIntExtra(EXTRA_TAB, 0).takeIf { it in 0..2 }
    }

    companion object {
        /** Номер вкладки при открытии: 0 — Главная, 1 — Месяц, 2 — Настройки. */
        const val EXTRA_TAB = "tab"
    }
}

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Главная", Icons.Rounded.Home),
    Tab("Месяц", Icons.Rounded.DateRange),
    Tab("Настройки", Icons.Rounded.Settings),
)

/**
 * Каркас приложения: три вкладки и нижняя навигация.
 * [requestedTab] — вкладка из intent (виджет, уведомление); после переключения вызывается [onTabConsumed].
 */
@Composable
fun Root(
    vm: AppViewModel = viewModel(),
    requestedTab: Int? = null,
    onTabConsumed: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onTabConsumed()
        }
    }
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.surface,
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
                NavigationBar(containerColor = scheme.surfaceContainer, tonalElevation = 0.dp) {
                    TABS.forEachIndexed { index, t ->
                        val selected = tab == index
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = index },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = {
                                Text(
                                    t.title,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = scheme.onPrimaryContainer,
                                selectedTextColor = scheme.onPrimaryContainer,
                                indicatorColor = scheme.primaryContainer,
                                unselectedIconColor = scheme.onSurfaceVariant,
                                unselectedTextColor = scheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm)
                1 -> MonthScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
