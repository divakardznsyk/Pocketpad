plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pocketpad"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.pocketpad"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.1"
    }

    // Stable key (committed on purpose) so every build installs as an update over the last one.
    signingConfigs {
        create("pocketpad") {
            storeFile = file("pocketpad.jks")
            storePassword = "pocketpad"
            keyAlias = "pocketpad"
            keyPassword = "pocketpad"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pocketpad")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
