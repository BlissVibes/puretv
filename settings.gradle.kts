rootProject.name = "PureTVforTwitch"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":core")
include(":app-windows")

// The Android and TV apps need an Android SDK just to CONFIGURE (the Android Gradle
// plugin refuses to load without one). A desktop-only checkout — the macOS port is
// built on Macs that have never installed Android Studio — skips them so
// `./gradlew :app-windows:run` works out of the box. Set ANDROID_HOME, or add
// `sdk.dir=` to local.properties, and they come back.
val hasAndroidSdk: Boolean = run {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val fromProps = file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.trim().startsWith("sdk.dir=") }?.substringAfter("=")?.trim()
    listOfNotNull(fromEnv, fromProps).any { File(it).isDirectory }
}
gradle.rootProject { extra["hasAndroidSdk"] = hasAndroidSdk }
if (hasAndroidSdk) {
    include(":app-android")
    include(":app-tv")
} else {
    logger.lifecycle("No Android SDK found: skipping :app-android and :app-tv (desktop-only build).")
}
