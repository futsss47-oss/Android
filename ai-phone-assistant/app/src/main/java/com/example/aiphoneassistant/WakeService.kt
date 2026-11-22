package com.example.aiphoneassistant

import android.app.*
import android.content.Intent
import android.os.*
import kotlin.concurrent.thread

class WakeService : Service() {
 private val channel = "voice_listener"
 private lateinit var voice: ForegroundVoice
 private val handler = Handler(Looper.getMainLooper())
 private var active = false
 private var busy = false
 override fun onBind(intent: Intent?) = null
 private fun notification(message: String): Notification {
  val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
  val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
  val stop = PendingIntent.getService(this, 1, Intent(this, WakeService::class.java).setAction("stop"), flags)
  return Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_btn_speak_now)
   .setContentTitle("AI Phone Assistant").setContentText(message).setContentIntent(open)
   .setOnlyAlertOnce(true).setOngoing(true).addAction(android.R.drawable.ic_delete, "Остановить", stop).build()
 }
 private fun status(message: String) {
  if (active) getSystemService(NotificationManager::class.java).notify(64, notification(message))
 }
 override fun onCreate() {
  super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Голосовые команды", NotificationManager.IMPORTANCE_LOW))
  voice = ForegroundVoice(this, ::handle, ::status)
 }
 override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
  if (intent?.action == "stop") {
   getSharedPreferences("voice_mode", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
   stopSelf()
  } else if (!active) {
   startForeground(64, notification("Слушаю «Дипсик»"))
   active = true
   voice.start()
  }
  return START_NOT_STICKY
 }
 private fun handle(text: String) {
  if (!active || busy) return
  val context = PhoneAccessibilityService.instance ?: this
  val actions = PhoneActions(context)
  try {
   if (actions.openNamedTarget(text)) { status("Команда обработана"); return }
  } catch (e: Exception) { status(e.message ?: "Ошибка команды"); return }
  val prefs = getSharedPreferences("ai_proxy", MODE_PRIVATE)
  busy = true
  thread {
   val answer = runCatching { DeepSeekClient.parse(prefs.getString("url","").orEmpty(), prefs.getString("token","").orEmpty(), text) }
   handler.post {
    busy = false
    if (active) try { actions.executeAi(answer.getOrThrow()); status("Команда обработана") }
    catch (e: Exception) { status(e.message ?: "Ошибка команды") }
   }
  }
 }
 override fun onDestroy() {
  active = false
  voice.stop()
  stopForeground(STOP_FOREGROUND_REMOVE)
  super.onDestroy()
 }
}
