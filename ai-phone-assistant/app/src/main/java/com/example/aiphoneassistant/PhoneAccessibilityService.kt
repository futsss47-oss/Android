package com.example.aiphoneassistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PhoneAccessibilityService : AccessibilityService() {
 companion object {
  @Volatile var instance: PhoneAccessibilityService? = null
   private set
 }
 override fun onServiceConnected() { super.onServiceConnected(); instance = this }
 override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
 override fun onInterrupt() { cancelClick() }
 override fun onDestroy() { cancelClick(); if (instance === this) instance = null; super.onDestroy() }
 private val handler = android.os.Handler(android.os.Looper.getMainLooper())
 private var pendingClick: Runnable? = null

 fun cancelClick() {
  pendingClick?.let { handler.removeCallbacks(it) }
  pendingClick = null
 }

 fun clickAfterLaunch(targetPackage: String, label: String) {
  cancelClick()
  val deadline = android.os.SystemClock.elapsedRealtime() + 5000
  val click = object : Runnable {
   var targetSeen = false
   override fun run() {
    val root = rootInActiveWindow
    val active = root?.packageName?.toString()
    if (targetSeen && active != targetPackage) { cancelClick(); return }
    if (root != null && active == targetPackage) {
     targetSeen = true
     val buttons = mutableSetOf<AccessibilityNodeInfo>()
     fun collect(node: AccessibilityNodeInfo) {
      if (!node.isVisibleToUser) return
      if (listOf(node.text, node.contentDescription).any { it?.toString()?.trim()?.equals(label, true) == true }) {
       var button: AccessibilityNodeInfo? = node
       while (button != null && !button.isClickable) button = button.parent
       if (button != null && button.isEnabled && button.packageName?.toString() == targetPackage) buttons.add(button)
      }
      for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it) }
     }
     collect(root)
     if (buttons.isNotEmpty()) {
      cancelClick()
      val ok = buttons.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
      if (!ok) notifyUser("Не удалось однозначно нажать «$label»")
      return
     }
    }
    if (android.os.SystemClock.elapsedRealtime() < deadline) handler.postDelayed(this, 200)
    else { cancelClick(); notifyUser("Кнопка «$label» не найдена") }
   }
  }
  pendingClick = click
  handler.postDelayed(click, 200)
 }

 private fun notifyUser(message: String) {
  android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
 }
 fun goBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
 fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME) }
 fun scroll(down: Boolean) {
  val root = rootInActiveWindow ?: throw IllegalStateException("Экран недоступен")
  val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
  try {
   if (!scrollNode(root, action)) throw IllegalStateException("Не найден прокручиваемый элемент")
  } finally { root.recycle() }
 }
 private fun scrollNode(node: AccessibilityNodeInfo, action: Int): Boolean {
  if (node.isScrollable && node.performAction(action)) return true
  for (i in 0 until node.childCount) {
   val child = node.getChild(i) ?: continue
   try { if (scrollNode(child, action)) return true }
   finally { child.recycle() }
  }
  return false
 }
}