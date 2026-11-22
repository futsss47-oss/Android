package com.example.aiphoneassistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiAction(val action: String, val argument: String) {
 fun describe(): String = when(action) {
  "open_app" -> "Открыть приложение: $argument"
  "open_maps" -> "Найти на карте: $argument"
  "open_2gis" -> "Найти в 2ГИС: $argument"
  "settings" -> "Открыть настройки: $argument"
  "back" -> "Нажать Назад"
  "home" -> "Перейти на главный экран"
  "scroll_down" -> "Прокрутить вниз"
  "scroll_up" -> "Прокрутить вверх"
  else -> "Неизвестное действие"
 }
}

object DeepSeekClient {
 fun parse(base: String, token: String, command: String): AiAction {
  val uri = android.net.Uri.parse(base.trim())
  require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Нужен HTTPS-адрес сервера" }
  require(token.isNotBlank()) { "Введите токен доступа к серверу" }
  val url = URL(base.trim().trimEnd('/') + "/command")
  val connection = (url.openConnection() as HttpURLConnection).apply {
   requestMethod = "POST"
   connectTimeout = 10000
   readTimeout = 22000
   doOutput = true
   setRequestProperty("Content-Type","application/json; charset=utf-8")
   setRequestProperty("Authorization","Bearer $token")
   instanceFollowRedirects = false
  }
  try {
   val payload = JSONObject().put("command", command).toString().toByteArray(Charsets.UTF_8)
   connection.outputStream.use { it.write(payload) }
   if (connection.responseCode != 200) throw IllegalStateException("Сервер ответил: ${connection.responseCode}")
   val response = connection.inputStream.bufferedReader().use { it.readText() }
   val obj = JSONObject(response)
   val action = obj.getString("action")
   val arg = obj.optString("argument", "")
   require(action in setOf("open_app","open_maps","open_2gis","settings","back","home","scroll_down","scroll_up","unknown")) { "Недопустимое действие" }
   require(arg.length <= 180) { "Слишком длинный аргумент" }
   if (action == "open_app") require(arg in setOf("telegram","youtube","2gis"))
   if (action == "settings") require(arg in setOf("general","bluetooth","wifi"))
   return AiAction(action,arg)
  } finally { connection.disconnect() }
 }
}
