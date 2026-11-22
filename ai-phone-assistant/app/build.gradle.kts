plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}

val releaseKeystore = providers.environmentVariable("ASSISTANT_KEYSTORE_PATH").orNull

android {
 namespace = "com.example.aiphoneassistant"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.example.aiphoneassistant"
  minSdk = 26
  targetSdk = 35
  versionCode = 4
  versionName = "0.4"
 }
 if (releaseKeystore != null) {
  val releaseSigning = signingConfigs.create("release") {
   storeFile = file(releaseKeystore)
   storePassword = providers.environmentVariable("ASSISTANT_KEYSTORE_PASSWORD").get()
   keyAlias = providers.environmentVariable("ASSISTANT_KEY_ALIAS").get()
   keyPassword = providers.environmentVariable("ASSISTANT_KEY_PASSWORD").get()
  }
  buildTypes.getByName("release").signingConfig = releaseSigning
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
}
