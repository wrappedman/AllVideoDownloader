// Standard Android Studio project (AGP). A normal Gradle build works here when a
// Gradle distribution + the Android SDK are available; in sandboxed CI without
// them, `python3 build_apk.py` produces the very same APK with the same
// underlying tools (aapt2 → kotlinc → dx → apksigner). See README.md.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AllVideoDownloader"
include(":app")
