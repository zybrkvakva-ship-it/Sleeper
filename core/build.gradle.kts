plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = 'com.sleeper.core'
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.0"
    }

    testOptions {
        unitTests.all {
            it.isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core Android/Kotlin
    implementation "androidx.core:core-ktx:1.13.0"
    implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.8.2"
    implementation "androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2"

    // Room (for persistence)
    val roomVersion = "2.6.1"
    implementation "androidx.room:room-runtime:$roomVersion"
    kapt "androidx.room:room-compiler:$roomVersion"

    // Coroutines
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0"

    // Hilt (DI)
    val hiltVersion = "2.51"
    implementation "com.google.dagger:hilt-android:$hiltVersion"
    kapt "com.google.dagger:hilt-compiler:$hiltVersion"

    // ----- Test dependencies -----
    testImplementation "junit:junit:4.13.2"
    testImplementation "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0"
}