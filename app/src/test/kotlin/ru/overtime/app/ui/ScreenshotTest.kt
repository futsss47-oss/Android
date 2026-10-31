package ru.overtime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import ru.overtime.domain.Absence
import ru.overtime.domain.Calculator
import ru.overtime.domain.Settings
import ru.overtime.domain.Span
import java.time.LocalDate
import java.time.YearMonth

/**
 * Скриншоты карточек на телефонах разной ширины. Рисуются на GitHub при каждой сборке,
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
        absences = dates(13, 16).associateWith { Absence.VACATION } +
            dates(20, 21).associateWith { Absence.SICK } +
            (month.atDay(28) to Absence.DAY_OFF),
    )

    private val spans = listOf(
        Span(month.atDay(3).atTime(8, 0), month.atDay(3).atTime(17, 0), true),
        Span(month.atDay(27).atTime(17, 0), month.atDay(27).atTime(20, 0), true),
        Span(month.atDay(29).atTime(17, 0), month.atDay(29).atTime(20, 0), true),
    )

    private val report = Calculator.report(month.atDay(1), month.atEndOfMonth(), spans, settings)
    private val summary = Calculator.monthSummary(month, report, settings)
    private val advance = Calculator.advance(month, report, summary, settings)

    private fun shot(content: @Composable () -> Unit) {
        paparazzi.snapshot {
            AppTheme(dynamic = false) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }

    @Test
    fun calendar() = shot { AbsenceCalendarCard(month, settings, report) { _, _ -> } }

    @Test
    fun advanceAndSummary() = shot {
        AdvanceCard(advance, summary)
        MonthSummaryCard(summary, report, settings.districtPercent, settings.taxPercent) {}
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
