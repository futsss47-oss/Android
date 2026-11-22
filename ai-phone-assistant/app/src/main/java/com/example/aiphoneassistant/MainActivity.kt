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
 private val actions by lazy { PhoneActions(this) }
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
  val heading = TextView(this).apply { text = "AI Phone Assistant · 0.6"; textSize = 24f; setTextColor(Color.BLACK) }
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
  toggle = Switch(this).apply {
   text = "Слушать «Дипсик», даже когда приложение свёрнуто"
   isChecked = prefs.getBoolean("background_enabled", false)
   setOnCheckedChangeListener { _, enabled ->
    prefs.edit().putBoolean("background_enabled", enabled).apply()
    if (enabled && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
     requestPermissions(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS) else arrayOf(android.Manifest.permission.RECORD_AUDIO), 201)
    else {
     refreshVoice()
     if (enabled && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 202)
    }
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
  val service = Intent(this, WakeService::class.java)
  if (!toggle.isChecked || listening) stopService(service)
  else if (visible && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
   startForegroundService(service)
 }
 override fun onResume() { super.onResume(); visible = true; toggle.isChecked = getSharedPreferences("voice_mode", MODE_PRIVATE).getBoolean("background_enabled", false); refreshVoice() }
 override fun onPause() { visible = false; session++; aiBusy = false; super.onPause() }
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
    if (actions.openNamedTarget(text)) { result.text = "Выполнено: $text"; return }
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
       actions.executeAi(proposal)
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

 private fun recognizeSpeech(requestCode: Int) {
   if (listening || aiBusy) return
   try {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
     putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
     putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
     putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите команду")
    }
    stopService(Intent(this, WakeService::class.java))
    startActivityForResult(intent, requestCode)
    listening = true
   } catch (e: ActivityNotFoundException) { result.text = "На телефоне нет доступного сервиса распознавания речи." }
 }

 @Deprecated("Legacy speech recognition callback")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (requestCode != speechRequest && requestCode != aiSpeechRequest) return
  listening = false
  if (visible) refreshVoice()
  if (resultCode == RESULT_OK) {
   val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
   command.setText(phrase)
   if (requestCode == aiSpeechRequest) askDeepSeek()
  }
 }

 private fun runCommand(raw: String) {
  try { if (!actions.openNamedTarget(raw)) actions.execute(raw); result.text = "Команда выполнена: $raw" }
  catch (e: Exception) { result.text = "Не удалось выполнить: ${e.message}" }
 }

}
