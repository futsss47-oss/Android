package com.example.aiphoneassistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.speech.RecognizerIntent
import android.widget.*
import android.app.AlertDialog
import kotlin.concurrent.thread
import java.util.Locale

class MainActivity : Activity() {
 private lateinit var command: EditText
 private lateinit var result: TextView
 private val speechRequest = 100
 private val aiSpeechRequest = 101
 private var aiBusy = false
 private lateinit var serverUrl: EditText
 private lateinit var accessToken: EditText

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(36, 48, 36, 24)
   setBackgroundColor(Color.rgb(245, 247, 250))
  }
  val heading = TextView(this).apply { text = "AI Phone Assistant · 0.4"; textSize = 24f; setTextColor(Color.BLACK) }
  val instructions = TextView(this).apply {
   text = "Скажи команду или введи её текстом. DeepSeek предложит действие для подтверждения."
   textSize = 15f; setPadding(0, 20, 0, 20)
  }
  command = EditText(this).apply { hint = "Введите команду"; minLines = 2 }
  result = TextView(this).apply { text = "Команды выполняются только по вашему нажатию."; setPadding(0, 20, 0, 0) }
  val run = Button(this).apply { text = "Выполнить команду" }
  val voice = Button(this).apply { text = "Диктовать текст" }
  val aiVoice = Button(this).apply { text = "🎙 Сказать команду DeepSeek" }
  val access = Button(this).apply { text = "Включить управление экраном" }
  val prefs = getSharedPreferences("ai_proxy", MODE_PRIVATE)
  serverUrl = EditText(this).apply { hint = "HTTPS-адрес личного сервера"; setSingleLine(true); setText(prefs.getString("url", "")) }
  accessToken = EditText(this).apply { hint = "Токен доступа (не ключ DeepSeek)"; setSingleLine(true); inputType = 129; setText(prefs.getString("token", "")) }
  val save = Button(this).apply { text = "Сохранить подключение" }
  val ai = Button(this).apply { text = "Спросить DeepSeek (с подтверждением)" }
  root.addView(heading); root.addView(instructions); root.addView(command)
  root.addView(aiVoice); root.addView(ai); root.addView(result)
  root.addView(voice); root.addView(run); root.addView(access)
  root.addView(serverUrl); root.addView(accessToken); root.addView(save)
  setContentView(ScrollView(this).apply { addView(root) })
  run.setOnClickListener { runCommand(command.text.toString()) }
  save.setOnClickListener {
   prefs.edit().putString("url", serverUrl.text.toString().trim()).putString("token", accessToken.text.toString().trim()).apply()
   result.text = "Подключение сохранено только на телефоне"
  }
  ai.setOnClickListener { askDeepSeek() }
  voice.setOnClickListener { recognizeSpeech(speechRequest) }
  aiVoice.setOnClickListener { if (!aiBusy) recognizeSpeech(aiSpeechRequest) }
  access.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
 }

 private fun askDeepSeek() {
   if (aiBusy) return
   val text = command.text.toString().trim()
   if (text.isBlank()) { result.text = "Сначала введите команду"; return }
   val url = serverUrl.text.toString()
   val token = accessToken.text.toString()
   aiBusy = true
   result.text = "DeepSeek обрабатывает команду..."
   thread {
    try {
     val proposal = DeepSeekClient.parse(url, token, text)
     runOnUiThread {
      if (isFinishing || isDestroyed) return@runOnUiThread
      if (proposal.action == "unknown") { aiBusy = false; result.text = "Не удалось распознать команду"; return@runOnUiThread }
      AlertDialog.Builder(this)
       .setTitle("Подтвердить действие?")
       .setMessage(proposal.describe())
       .setNegativeButton("Отмена") { _, _ -> result.text = "Отменено" }
       .setPositiveButton("Выполнить") { _, _ ->
        try { executeAi(proposal); result.text = "Выполнено: ${proposal.describe()}" }
        catch (e: Exception) { result.text = "Ошибка: ${e.message}" }
       }.create().apply {
        setOnCancelListener { result.text = "Отменено" }
        setOnDismissListener { aiBusy = false }
        show()
       }
     }
    } catch (e: Exception) { runOnUiThread {
     aiBusy = false
     if (!isFinishing && !isDestroyed) result.text = "Ошибка DeepSeek: ${e.message}"
    } }
   }
 }

 private fun recognizeSpeech(requestCode: Int) {
   try {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
     putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
     putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
     putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите команду")
    }
    startActivityForResult(intent, requestCode)
   } catch (e: ActivityNotFoundException) { result.text = "На телефоне нет доступного сервиса распознавания речи." }
 }

 @Deprecated("Legacy speech recognition callback")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if ((requestCode == speechRequest || requestCode == aiSpeechRequest) && resultCode == RESULT_OK) {
   val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
   command.setText(phrase)
   if (requestCode == aiSpeechRequest) askDeepSeek()
  }
 }

 private fun runCommand(raw: String) {
  try { execute(raw); result.text = "Команда выполнена: $raw" }
  catch (e: Exception) { result.text = "Не удалось выполнить: ${e.message}" }
 }

 private fun execute(raw: String) {
  val s = raw.trim().lowercase(Locale.ROOT)
  val service = PhoneAccessibilityService.instance
  when {
   s.startsWith("2гис") || s.startsWith("2gis") ->
    openUrl("https://2gis.ru/ufa/search/" + Uri.encode(raw.substringAfter(' ', "Вологодская улица, 11, Уфа")))
   s.startsWith("карты") || s.startsWith("maps") ->
    openUrl("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(raw.substringAfter(' ', "Москва")))
   s.contains("bluetooth") || s.contains("блютуз") -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
   s.contains("wifi") || s.contains("wi-fi") || s.contains("вайфай") -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
   s.contains("настройки") -> startActivity(Intent(Settings.ACTION_SETTINGS))
   s == "назад" || s == "вернись назад" -> requireService(service).goBack()
   s == "домой" || s == "на главный экран" -> requireService(service).goHome()
   s == "прокрути вниз" || s == "листай вниз" -> requireService(service).scroll(true)
   s == "прокрути вверх" || s == "листай вверх" -> requireService(service).scroll(false)
   s.startsWith("открыть ") -> {
    val packages = mapOf("telegram" to "org.telegram.messenger", "телеграм" to "org.telegram.messenger", "2гис" to "ru.dublgis.dgismobile", "2gis" to "ru.dublgis.dgismobile", "youtube" to "com.google.android.youtube", "ютуб" to "com.google.android.youtube")
    val pkg = packages[s.removePrefix("открыть ").trim()] ?: throw IllegalArgumentException("Приложение пока не поддерживается")
    startActivity(packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalArgumentException("Приложение не установлено или недоступно"))
   }
   else -> throw IllegalArgumentException("Неизвестная команда")
  }
 }
 private fun executeAi(proposal: AiAction) {
  when (proposal.action) {
   "open_app" -> execute("открыть " + proposal.argument)
   "open_maps" -> openUrl("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(proposal.argument))
   "open_2gis" -> openUrl("https://2gis.ru/ufa/search/" + Uri.encode(proposal.argument))
   "settings" -> when (proposal.argument) {
    "bluetooth" -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    "wifi" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    "general" -> startActivity(Intent(Settings.ACTION_SETTINGS))
    else -> throw IllegalArgumentException("Недопустимые настройки")
   }
   "back" -> requireService(PhoneAccessibilityService.instance).goBack()
   "home" -> requireService(PhoneAccessibilityService.instance).goHome()
   "scroll_down" -> requireService(PhoneAccessibilityService.instance).scroll(true)
   "scroll_up" -> requireService(PhoneAccessibilityService.instance).scroll(false)
   else -> throw IllegalArgumentException("Неизвестное действие")
  }
 }
 private fun requireService(service: PhoneAccessibilityService?): PhoneAccessibilityService =
  service ?: throw IllegalStateException("Сначала включите AI Phone Assistant в настройках специальных возможностей")
 private fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
