package com.example.aiphoneassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.Locale

class PhoneActions(private val context: Context) {
 private val packageManager get() = context.packageManager
 private fun startActivity(intent: Intent) {
  if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  context.startActivity(intent)
 }
 fun openNamedTarget(raw: String): Boolean {
  val click = Regex("""^(?:в приложении|открой(?: приложение)?)\s+(.+?)\s*,?\s+(?:и\s+)?нажми\s+(?:на\s+)?(?:кнопку\s+)?(.+)$""", RegexOption.IGNORE_CASE).matchEntire(raw.trim())
  if (click != null) {
   val name = click.groupValues[1].trim().trimEnd(',')
   val label = click.groupValues[2].trim().trimEnd('.', '!', '?').trim('«', '»', '"')
   val app = findLauncher(name) ?: throw IllegalArgumentException("Приложение «$name» не найдено")
   val service = requireService(PhoneAccessibilityService.instance)
   startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(app.packageName, app.name))
   service.clickAfterLaunch(app.packageName, label)
   return true
  }
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
  val app = findLauncher(name) ?: return false
  startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(app.packageName, app.name))
  return true
 }

 private fun findLauncher(name: String): android.content.pm.ActivityInfo? {
  val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  val matches = packageManager.queryIntentActivities(launcher, 0).filter { it.loadLabel(packageManager).toString().trim().equals(name, true) }
  if (matches.size > 1) throw IllegalArgumentException("Найдено несколько приложений с таким названием")
  return matches.singleOrNull()?.activityInfo
 }

 fun execute(raw: String) {
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
 fun executeAi(proposal: AiAction) {
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
