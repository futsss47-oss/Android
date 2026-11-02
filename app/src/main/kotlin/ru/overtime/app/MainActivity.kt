package ru.overtime.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.overtime.app.ui.AppTheme
import ru.overtime.app.ui.AppViewModel
import ru.overtime.app.ui.Brand
import ru.overtime.app.ui.HomeScreen
import ru.overtime.app.ui.ListokScreen
import ru.overtime.app.ui.SettingsScreen
import ru.overtime.app.ui.TabelScreen

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
        return intent.getIntExtra(EXTRA_TAB, 0).takeIf { it in 0..3 }
    }

    companion object {
        /** Номер вкладки при открытии: 0 — Проходная, 1 — Табель, 2 — Листок, 3 — Настройки. */
        const val EXTRA_TAB = "tab"
    }
}

private data class Tab(val title: String, @DrawableRes val icon: Int)

/** Разделы называются как на заводе: проходная (сегодня), табель (месяц), листок (расчёт), настройки. */
private val TABS = listOf(
    Tab("Проходная", R.drawable.ic_nav_gate),
    Tab("Табель", R.drawable.ic_nav_tabel),
    Tab("Листок", R.drawable.ic_nav_listok),
    Tab("Настройки", R.drawable.ic_nav_settings),
)

/**
 * Каркас приложения: четыре вкладки и нижняя панель. Выбранная вкладка — цвет пихты и жёлтая черта под подписью.
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
            Column(Modifier.background(scheme.surfaceContainer)) {
                HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .selectableGroup(),
                ) {
                    TABS.forEachIndexed { index, t ->
                        val selected = tab == index
                        val color = if (selected) scheme.primary else scheme.onSurfaceVariant
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = selected, role = Role.Tab, onClick = { tab = index })
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                        ) {
                            Icon(painterResource(t.icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                            Text(
                                t.title,
                                color = color,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                            Box(
                                Modifier
                                    .size(width = 18.dp, height = 3.dp)
                                    .background(if (selected) Brand.Signal else Color.Transparent, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm, onOpenListok = { tab = 2 }, onOpenTabel = { tab = 1 })
                1 -> TabelScreen(vm)
                2 -> ListokScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
