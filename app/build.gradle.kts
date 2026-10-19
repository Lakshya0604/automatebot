plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.instinct.tvremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.instinct.tvremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
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
