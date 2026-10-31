package ru.overtime.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.data.TimeConv
import ru.overtime.domain.Absence
import ru.overtime.domain.DayKind
import ru.overtime.domain.MonthCalculator
import ru.overtime.domain.Settings
import ru.overtime.domain.Span
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Скриншоты карточек и страниц на телефонах разной ширины. Рисуются на GitHub при каждой сборке,
 * чтобы проверять вёрстку (не уехало ли, не обрезалось ли) без запуска на телефоне.
 */
abstract class ScreenshotBase(device: DeviceConfig) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, maxPercentDifference = 100.0)

    private val month = YearMonth.of(2026, 10)

    private fun dates(from: Int, to: Int) = (from..to).map { month.atDay(it) }

    private val settings = Settings(
        salary = 32000.0,
        bonusPercent = 158.78125,
        nightEnabled = true,
        absences = dates(13, 16).associateWith { Absence.VACATION } +
            dates(20, 21).associateWith { Absence.SICK } +
            (month.atDay(28) to Absence.DAY_OFF),
        calendarOverrides = mapOf(month.atDay(30) to DayKind.SHORT),
    )

    private val spans = listOf(
        Span(month.atDay(3).atTime(8, 0), month.atDay(3).atTime(17, 0), true),
        Span(month.atDay(27).atTime(17, 0), month.atDay(27).atTime(20, 0), true),
        Span(month.atDay(29).atTime(20, 0), month.atDay(29).atTime(23, 0), true),
    )

    private val calc = MonthCalculator.calc(month, spans, settings)

    private val now: LocalDateTime = month.atDay(30).atTime(18, 30)

    private fun millis(t: LocalDateTime) = TimeConv.toMillis(t)

    /** Записи месяца, новые сверху: идущая, исправленная с заметкой, ручная, автоматическая. */
    private val sessions = listOf(
        SessionEntity(id = 4, startMillis = millis(now.minusMinutes(90)), endMillis = null, manual = false),
        SessionEntity(
            id = 3,
            startMillis = millis(month.atDay(29).atTime(20, 0)),
            endMillis = millis(month.atDay(29).atTime(23, 0)),
            manual = false,
            edited = true,
            note = "Попросил начальник смены: срочная отгрузка",
        ),
        SessionEntity(
            id = 2,
            startMillis = millis(month.atDay(27).atTime(17, 0)),
            endMillis = millis(month.atDay(27).atTime(20, 0)),
            manual = true,
        ),
        SessionEntity(
            id = 1,
            startMillis = millis(month.atDay(3).atTime(8, 0)),
            endMillis = millis(month.atDay(3).atTime(17, 0)),
            manual = false,
        ),
    )

    /** Карточки столбиком на фоне экрана. */
    private fun shot(content: @Composable () -> Unit) {
        paparazzi.snapshot {
            AppTheme(dynamic = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { content() }
                }
            }
        }
    }

    /** Целая страница во весь экран. */
    private fun page(content: @Composable () -> Unit) {
        paparazzi.snapshot {
            AppTheme(dynamic = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
    }

    @Test
    fun monthMain() = page {
        MonthMainPage(
            month = month,
            calc = calc,
            settings = settings,
            sessions = sessions,
            now = now,
            onMonthChange = {},
            onOpenPayslip = {},
            onOpenCalendar = {},
            onAdd = {},
            onShare = {},
            onSessionClick = {},
        )
    }

    @Test
    fun monthCards() = shot {
        MonthHeader(month) {}
        MonthHeroCard(month, calc)
        OvertimeChartCard(month, calc.report, settings, now.toLocalDate())
        MonthLinksCard(calc, onOpenPayslip = {}, onOpenCalendar = {})
    }

    @Test
    fun sessionsCard() = shot { SessionsCard(sessions, now, onAdd = {}, onShare = {}, onSessionClick = {}) }

    @Test
    fun payslipPage() = page {
        PayslipPage(month = month, calc = calc, settings = settings, onBack = {}, onSaveOverride = {})
    }

    @Test
    fun payslipCards() = shot {
        PayslipTiles(calc.summary)
        PayslipLinesCard(calc, settings)
        AdvanceDetailsCard(month, calc.advance, initiallyOpen = true)
    }

    @Test
    fun calendarPage() = page {
        CalendarPage(
            month = month,
            calc = calc,
            settings = settings,
            onBack = {},
            onSetAbsence = { _, _ -> },
            onSetDayKind = { _, _ -> },
            today = now.toLocalDate(),
        )
    }

    @Test
    fun calendarCards() = shot {
        CalendarTotalsCard(month, settings)
        LeavePayCard(calc.leave, settings)
    }

    @Test
    fun settingsGroups() = shot {
        SettingsGroup("Оплата", "Оклад 32 000 ₽ · премия 158,78% · районный 15%", initiallyExpanded = false) {}
        SettingsGroup("График и обед", "Пн–Пт 08:00–17:00 · обед 12:00–13:00", initiallyExpanded = false) {}
    }
}

/** Обычный телефон (~411 dp, как Galaxy S25 Ultra по умолчанию). */
class ScreenshotPhone : ScreenshotBase(DeviceConfig.PIXEL_5)

/** Узкий телефон (360 dp) — самый тесный случай. */
class ScreenshotNarrow : ScreenshotBase(
    DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2340, density = Density.XXHIGH),
)
