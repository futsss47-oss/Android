package ru.patients.app.ui

import androidx.compose.runtime.mutableStateListOf
import ru.patients.core.PatientType

sealed interface Screen {
    data object Home : Screen
    data class List(val type: PatientType) : Screen
    data object Search : Screen
    data class PatientCard(val id: String) : Screen
    data object Today : Screen
    data object Settings : Screen
    data object ChangePin : Screen
}

/** Простой стек экранов. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(s: Screen) {
        if (stack.last() != s) stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Открыть карточку из уведомления: поверх главного экрана. */
    fun openPatient(id: String) {
        stack.clear()
        stack.add(Screen.Home)
        stack.add(Screen.PatientCard(id))
    }
}
