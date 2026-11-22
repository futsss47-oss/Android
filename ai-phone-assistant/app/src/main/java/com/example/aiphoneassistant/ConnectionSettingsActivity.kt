package com.example.aiphoneassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class ConnectionSettingsActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs = getSharedPreferences("ai_proxy", MODE_PRIVATE)
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(36, 64, 36, 32)
  }
  val heading = TextView(this).apply { text = "Настройки"; textSize = 24f }
  val url = EditText(this).apply {
   hint = "HTTPS-адрес личного сервера"
   inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
   setSingleLine()
   setText(prefs.getString("url", ""))
  }
  val token = EditText(this).apply {
   hint = "Токен доступа"
   inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
   setSingleLine()
   setText(prefs.getString("token", ""))
  }
  val status = TextView(this)
  val save = Button(this).apply { text = "Сохранить подключение" }
  val check = Button(this).apply { text = "Проверить подключение" }
  val accessibility = Button(this).apply { text = "Управление экраном" }
  val back = Button(this).apply { text = "Назад" }
  listOf(heading, url, token, save, check, status, accessibility, back).forEach { root.addView(it) }
  setContentView(ScrollView(this).apply { addView(root) })

  save.setOnClickListener {
   prefs.edit().putString("url", url.text.toString().trim())
    .putString("token", token.text.toString().trim()).apply()
   status.text = "Подключение сохранено"
  }
  check.setOnClickListener {
   val address = url.text.toString().trim()
   val accessToken = token.text.toString().trim()
   check.isEnabled = false
   status.text = "Проверяем ответ DeepSeek…"
   thread {
    val message = try {
     DeepSeekClient.parse(address, accessToken, "Открой YouTube")
     "Worker и DeepSeek отвечают"
    } catch (e: Exception) {
     e.message ?: "Ошибка подключения"
    }
    runOnUiThread {
     if (!isFinishing && !isDestroyed) {
      status.text = message
      check.isEnabled = true
     }
    }
   }
  }
  accessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
  back.setOnClickListener { finish() }
 }
}
