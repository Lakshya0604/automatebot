plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.instinct.tvremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.instinct.automatebot"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        named("debug") {
            storeFile = rootProject.file("app/automatebot-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
}
dependencies { testImplementation("junit:junit:4.13.2") }
