package com.example.aiphoneassistant
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.widget.*

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36,48,36,24); setBackgroundColor(Color.rgb(245,247,250)) }
  val heading = TextView(this).apply { text = "AI Phone Assistant · 0.1"; textSize = 24f; setTextColor(Color.BLACK) }
  val instructionsView = TextView(this).apply { text = "Команды: 2гис Вологодская 11; карты Москва; настройки; bluetooth; wifi; открыть Telegram"; textSize = 15f; setPadding(0,20,0,20) }
  val command = EditText(this).apply { hint = "Введите команду"; setSingleLine(false); minLines = 2 }
  val result = TextView(this).apply { text = "Команды выполняются только после нажатия кнопки."; setPadding(0,20,0,0) }
  val run = Button(this).apply { text = "Выполнить команду" }
  root.addView(heading); root.addView(instructionsView); root.addView(command); root.addView(run); root.addView(result)
  setContentView(root)
  run.setOnClickListener { try { execute(command.text.toString()); result.text = "Команда отправлена в Android" } catch(e: Exception) { result.text = "Не удалось выполнить: ${e.message}" } }
 }
 private fun execute(raw: String) {
  val s = raw.trim().lowercase()
  when {
   s.startsWith("2гис") || s.startsWith("2gis") -> openUrl("https://2gis.ru/ufa/search/" + Uri.encode(raw.substringAfter(' ', "Вологодская улица, 11, Уфа")))
   s.startsWith("карты") || s.startsWith("maps") -> openUrl("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(raw.substringAfter(' ', "Москва")))
   s.contains("bluetooth") || s.contains("блютуз") -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
   s.contains("wifi") || s.contains("wi-fi") || s.contains("вайфай") -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
   s.contains("настройки") -> startActivity(Intent(Settings.ACTION_SETTINGS))
   s.startsWith("открыть ") -> {
    val packages = mapOf("telegram" to "org.telegram.messenger", "телеграм" to "org.telegram.messenger", "2гис" to "ru.dublgis.dgismobile", "2gis" to "ru.dublgis.dgismobile", "youtube" to "com.google.android.youtube")
    val pkg = packages[s.removePrefix("открыть ").trim()] ?: throw IllegalArgumentException("Приложение пока не поддерживается")
    startActivity(packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalArgumentException("Приложение не установлено или недоступно"))
   }
   else -> throw IllegalArgumentException("Неизвестная команда")
  }
 }
 private fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}