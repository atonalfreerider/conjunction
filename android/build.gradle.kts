// Versions match ~/Desktop/SUNDIAL/sundial-android-native (Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Applied by :app only when app/google-services.json exists (the app builds without Firebase).
    id("com.google.gms.google-services") version "4.5.0" apply false
}
