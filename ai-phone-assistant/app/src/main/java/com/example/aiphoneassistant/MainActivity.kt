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
import kotlin.concurrent.thread
import java.util.Locale

class MainActivity : Activity() {
 private lateinit var command: EditText
 private lateinit var result: TextView
 private val speechRequest = 100
 private val aiSpeechRequest = 101
 private var aiBusy = false
 private var listening = false
 private lateinit var wake: ForegroundVoice
 private var visible = false
 private var session = 0
 private lateinit var toggle: Switch

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(36, 48, 36, 24)
   setBackgroundColor(Color.rgb(245, 247, 250))
  }
  val heading = TextView(this).apply { text = "AI Phone Assistant · 0.5"; textSize = 24f; setTextColor(Color.BLACK) }
  val instructions = TextView(this).apply {
   text = "Скажи команду или введи её текстом. Поддерживаемая команда выполнится после распознавания."
   textSize = 15f; setPadding(0, 20, 0, 20)
  }
  command = EditText(this).apply { hint = "Введите команду"; minLines = 2 }
  result = TextView(this).apply { text = "Команды выполняются только по вашему нажатию."; setPadding(0, 20, 0, 0) }
  val run = Button(this).apply { text = "Выполнить команду" }
  val voice = Button(this).apply { text = "Диктовать текст" }
  val aiVoice = Button(this).apply { text = "🎙 Сказать команду DeepSeek" }
  val settings = Button(this).apply { text = "Настройки" }
  val ai = Button(this).apply { text = "Выполнить через DeepSeek" }
  val prefs = getSharedPreferences("voice_mode", MODE_PRIVATE)
  wake = ForegroundVoice(this, { phrase ->
   if (visible && !listening && !aiBusy) {
    command.setText(phrase)
    askDeepSeek()
   }
  }, { message -> result.text = message })
  toggle = Switch(this).apply {
   text = "Слушать «Дипсик» на этом экране"
   isChecked = prefs.getBoolean("enabled", false)
   setOnCheckedChangeListener { _, enabled ->
    prefs.edit().putBoolean("enabled", enabled).apply()
    if (enabled && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
     requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 201)
    else refreshVoice()
   }
  }
  root.addView(heading); root.addView(toggle); root.addView(instructions); root.addView(command)
  root.addView(aiVoice); root.addView(ai); root.addView(result)
  root.addView(voice); root.addView(run); root.addView(settings)
  setContentView(ScrollView(this).apply { addView(root) })
  run.setOnClickListener { runCommand(command.text.toString()) }
  ai.setOnClickListener { askDeepSeek() }
  voice.setOnClickListener { recognizeSpeech(speechRequest) }
  aiVoice.setOnClickListener { if (!aiBusy) recognizeSpeech(aiSpeechRequest) }
  settings.setOnClickListener { startActivity(Intent(this, ConnectionSettingsActivity::class.java)) }
  command.setText(savedInstanceState?.getString("command"))
  listening = savedInstanceState?.getBoolean("listening") ?: false
  openWidgetCommand(intent)
 }

 private fun refreshVoice() {
  wake.stop()
  if (visible && toggle.isChecked && !listening &&
   checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) wake.start()
 }
 override fun onResume() { super.onResume(); visible = true; refreshVoice() }
 override fun onPause() { visible = false; session++; aiBusy = false; wake.stop(); super.onPause() }
 override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
  super.onRequestPermissionsResult(code, permissions, results)
  if (code == 201) {
   if (results.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) toggle.isChecked = false
   refreshVoice()
  }
 }

 override fun onNewIntent(intent: Intent) {
  super.onNewIntent(intent)
  openWidgetCommand(intent)
 }

 override fun onSaveInstanceState(outState: Bundle) {
  super.onSaveInstanceState(outState)
  outState.putString("command", command.text.toString())
  outState.putBoolean("listening", listening)
 }

 private fun openWidgetCommand(intent: Intent) {
  if (!intent.getBooleanExtra("voice_widget", false)) return
  intent.removeExtra("voice_widget")
  recognizeSpeech(aiSpeechRequest)
 }

 private fun askDeepSeek() {
   if (aiBusy) return
   val text = command.text.toString().trim()
   if (text.isBlank()) { result.text = "Сначала введите команду"; return }
   try {
    if (openNamedTarget(text)) { result.text = "Выполнено: $text"; return }
   } catch (e: Exception) { result.text = e.message; return }
   val requestSession = session
   val prefs = getSharedPreferences("ai_proxy", MODE_PRIVATE)
   val url = prefs.getString("url", "").orEmpty()
   val token = prefs.getString("token", "").orEmpty()
   aiBusy = true
   result.text = "DeepSeek обрабатывает команду..."
   thread {
    try {
     val proposal = DeepSeekClient.parse(url, token, text)
     runOnUiThread {
      if (isFinishing || isDestroyed || requestSession != session) return@runOnUiThread
      if (proposal.action == "unknown") { aiBusy = false; result.text = "Не удалось распознать команду"; return@runOnUiThread }
      aiBusy = false
      try {
       executeAi(proposal)
       result.text = "Распознано: $text\nВыполнено: ${proposal.describe()}"
      } catch (e: Exception) {
       result.text = "Не удалось выполнить: ${e.message}"
      }
     }
    } catch (e: Exception) { runOnUiThread {
     if (requestSession != session) return@runOnUiThread
     aiBusy = false
     if (!isFinishing && !isDestroyed) result.text = "Ошибка DeepSeek: ${e.message}"
    } }
   }
 }

 private fun openNamedTarget(raw: String): Boolean {
  val match = Regex("""^(?:открой|открыть|запусти)\s+(?:(приложение|сайт)\s+)?(.+)$""", RegexOption.IGNORE_CASE).matchEntire(raw.trim()) ?: return false
  val name = match.groupValues[2].trim().trimEnd('.', '!', '?').lowercase(Locale.ROOT)
  if (match.groupValues[1].lowercase(Locale.ROOT) == "сайт") {
   val domain = when (name.replace(" ", "")) {
    "чатгпт", "чатджипити", "chatgpt" -> "chatgpt.com"
    else -> name
   }
   if (!android.util.Patterns.DOMAIN_NAME.matcher(domain).matches()) throw IllegalArgumentException("Назовите домен сайта")
   openUrl("https://" + domain)
   return true
  }
  val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  val matches = packageManager.queryIntentActivities(launcher, 0).filter { it.loadLabel(packageManager).toString().trim().equals(name, true) }
  if (matches.size > 1) throw IllegalArgumentException("Найдено несколько приложений с таким названием")
  val app = matches.singleOrNull()?.activityInfo ?: return false
  startActivity(launcher.setClassName(app.packageName, app.name))
  return true
 }

 private fun recognizeSpeech(requestCode: Int) {
   if (listening || aiBusy) return
   try {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
     putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
     putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
     putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите команду")
    }
    wake.stop()
    startActivityForResult(intent, requestCode)
    listening = true
   } catch (e: ActivityNotFoundException) { result.text = "На телефоне нет доступного сервиса распознавания речи." }
 }

 @Deprecated("Legacy speech recognition callback")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (requestCode != speechRequest && requestCode != aiSpeechRequest) return
  listening = false
  if (resultCode == RESULT_OK) {
   val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
   command.setText(phrase)
   if (requestCode == aiSpeechRequest) askDeepSeek()
  }
 }

 private fun runCommand(raw: String) {
  try { if (!openNamedTarget(raw)) execute(raw); result.text = "Команда выполнена: $raw" }
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
