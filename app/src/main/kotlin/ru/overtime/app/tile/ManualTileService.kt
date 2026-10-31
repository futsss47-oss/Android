package ru.overtime.app.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch
import ru.overtime.app.OvertimeApp
import ru.overtime.app.R
import ru.overtime.app.data.TimeConv
import ru.overtime.app.ui.TIME_FMT

/**
 * Плитка в шторке быстрых настроек: старт и стоп ручной сверхурочной.
 * Активна, пока ручная сверхурочная идёт; подзаголовок — «идёт с ЧЧ:ММ» или «не идёт».
 * База читается в фоне ([OvertimeApp.appScope]), плитка обновляется на главном потоке.
 */
class ManualTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val app = application as OvertimeApp
        app.appScope.launch {
            val running = app.manualToggle()
            val start = if (running) app.db.sessionDao().openManual()?.startMillis else null
            main.post { show(running, start) }
        }
    }

    /** Прочитать состояние из базы в фоне и показать его на плитке. */
    private fun refresh() {
        val app = application as OvertimeApp
        app.appScope.launch {
            val open = app.db.sessionDao().openManual()
            main.post { show(open != null, open?.startMillis) }
        }
    }

    /** Только на главном потоке. */
    private fun show(running: Boolean, startMillis: Long?) {
        val tile = qsTile ?: return
        try {
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (running && startMillis != null) {
                    "идёт с " + TimeConv.toLocal(startMillis).format(TIME_FMT)
                } else {
                    "не идёт"
                }
            }
            tile.updateTile()
        } catch (e: Exception) {
            // Плитку успели убрать из шторки или служба уже отвязана: обновлять нечего.
        }
    }

    companion object {
        /** Попросить систему обновить плитку (после старта или стопа из приложения, виджета, плитки). */
        fun requestUpdate(context: Context) {
            try {
                TileService.requestListeningState(context, ComponentName(context, ManualTileService::class.java))
            } catch (e: Exception) {
                // Плитка не добавлена или система не даёт запросить обновление: обновится при открытии шторки.
            }
        }
    }
}
