package ru.overtime.app.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.util.Locale

/** Центр карты по умолчанию, если место работы ещё не задано: Уфа. */
private val DEFAULT_CENTER = GeoPoint(54.7348, 55.9579)

private fun formatCoords(p: GeoPoint): String =
    String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)

/** Разбирает «54.7348, 55.9579» (дробная часть через точку). */
private fun parseCoords(text: String): GeoPoint? {
    val parts = text.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
    return GeoPoint(lat, lon)
}

private fun setupOsmdroid(c: Context) {
    val conf = Configuration.getInstance()
    conf.load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    // OpenStreetMap требует, чтобы приложение представлялось своим именем.
    conf.userAgentValue = c.packageName
    val base = File(c.cacheDir, "osmdroid")
    conf.osmdroidBasePath = base
    conf.osmdroidTileCache = File(base, "tiles")
}

private fun circle(map: MapView, center: GeoPoint, radiusM: Double, fill: Int, stroke: Int): Polygon {
    val polygon = Polygon(map)
    polygon.setPoints(Polygon.pointsAsCircle(center, radiusM))
    polygon.fillPaint.color = fill
    polygon.outlinePaint.color = stroke
    polygon.outlinePaint.strokeWidth = 4f
    polygon.setOnClickListener { _, _, _ -> true } // без всплывающих подсказок по нажатию
    return polygon
}

private fun drawOverlays(
    map: MapView,
    point: GeoPoint?,
    enterRadiusM: Int,
    exitRadiusM: Int,
    onLongPress: (GeoPoint) -> Unit,
) {
    map.overlays.clear()
    if (point != null) {
        map.overlays.add(circle(map, point, exitRadiusM.toDouble(), Color.argb(40, 255, 152, 0), Color.rgb(255, 152, 0)))
        map.overlays.add(circle(map, point, enterRadiusM.toDouble(), Color.argb(60, 33, 150, 243), Color.rgb(33, 150, 243)))
        val marker = Marker(map)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = "Работа"
        marker.setOnMarkerClickListener { _, _ -> true }
        map.overlays.add(marker)
    }
    // Слой событий последним: он первым получает долгое нажатие.
    map.overlays.add(
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) onLongPress(p)
                return true
            }
        }),
    )
    map.invalidate()
}

/** Полноэкранный выбор места работы: карта OpenStreetMap и ввод координат. */
@Composable
fun MapPickerDialog(
    initialLat: Double?,
    initialLon: Double?,
    enterRadiusM: Int,
    exitRadiusM: Int,
    locate: suspend () -> Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onPick: (Double, Double) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var point by remember {
        mutableStateOf(if (initialLat != null && initialLon != null) GeoPoint(initialLat, initialLon) else null)
    }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var coordText by remember { mutableStateOf(point?.let { formatCoords(it) } ?: "") }
    var info by remember { mutableStateOf<String?>(null) }

    fun moveTo(p: GeoPoint) {
        mapRef?.controller?.setZoom(17.0)
        mapRef?.controller?.animateTo(p)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Место работы", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Долгое нажатие на карту ставит точку. Синий круг: «я на работе» ($enterRadiusM м), " +
                        "оранжевый: «я ушёл» ($exitRadiusM м).",
                    style = MaterialTheme.typography.bodySmall,
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { c ->
                        setupOsmdroid(c)
                        MapView(c).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(if (point != null) 17.0 else 12.0)
                            controller.setCenter(point ?: DEFAULT_CENTER)
                            onResume()
                            mapRef = this
                        }
                    },
                    update = { map ->
                        drawOverlays(map, point, enterRadiusM, exitRadiusM) { p ->
                            point = p
                            coordText = formatCoords(p)
                            info = null
                        }
                    },
                    onRelease = { map ->
                        map.onPause()
                        map.onDetach()
                        mapRef = null
                    },
                )
                Text("© участники OpenStreetMap", style = MaterialTheme.typography.labelSmall)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = coordText,
                        onValueChange = { coordText = it },
                        label = { Text("Координаты (широта, долгота)") },
                        placeholder = { Text("54.734800, 55.957900") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        val p = parseCoords(coordText)
                        if (p == null) {
                            info = "Не понял координаты. Пример: 54.734800, 55.957900 (дробная часть через точку)."
                        } else {
                            point = p
                            info = null
                            moveTo(p)
                        }
                    }) { Text("Поставить") }
                }
                info?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val here = locate()
                            if (here == null) {
                                info = "Не удалось определить местоположение. Проверьте GPS и разрешение."
                            } else {
                                info = null
                                moveTo(GeoPoint(here.first, here.second))
                            }
                        }
                    }) { Text("Где я") }
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Button(
                        onClick = { point?.let { onPick(it.latitude, it.longitude) } },
                        enabled = point != null,
                    ) { Text("Готово") }
                }
            }
        }
    }
}
