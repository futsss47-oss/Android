// Игра «Кинозакуски»: страница из assets в WebView. Зависимостей нет, только платформа Android.
plugins {
    id("com.android.application")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "ru.kinozakuski.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.kinozakuski.app"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        // Тот же постоянный debug-ключ, что и у основного приложения: новая сборка ставится поверх старой.
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
