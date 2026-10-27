plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.allvideodownloader.avd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.allvideodownloader.avd"
        minSdk = 21
        targetSdk = 34
        versionCode = 220
        versionName = "2.2.0"
    }

    sourceSets["main"].apply {
        java.srcDirs("src/main/java")
        assets.srcDirs("src/main/assets")
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources.excludes += setOf("META-INF/*")
    }
}

dependencies {
    // Intentionally empty: the port is standard-library + platform only, which is
    // what makes it buildable without any repository access.
}
