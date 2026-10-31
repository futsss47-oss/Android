// Версии плагинов. Если Android Studio предложит обновления, их можно принять.
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.31" apply false
    // Скриншоты экранов при сборке на GitHub (проверка вёрстки без телефона).
    id("app.cash.paparazzi") version "2.0.0-alpha02" apply false
}
