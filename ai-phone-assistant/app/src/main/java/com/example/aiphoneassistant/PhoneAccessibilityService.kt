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
 override fun onInterrupt() {}
 override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
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