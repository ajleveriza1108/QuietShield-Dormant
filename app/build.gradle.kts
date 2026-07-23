plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ajcoder.quietshield.dormant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ajcoder.quietshield.dormant"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.0-alpha3-r4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    // Locked to stable AndroidX releases that compile against Android API 36.
    implementation("androidx.core:core:1.17.0") {
        version { strictly("1.17.0") }
    }
    implementation("androidx.core:core-ktx:1.17.0") {
        version { strictly("1.17.0") }
    }
    implementation("androidx.activity:activity-compose:1.11.0") {
        version { strictly("1.11.0") }
    }
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4") {
        version { strictly("2.9.4") }
    }
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4") {
        version { strictly("2.9.4") }
    }
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4") {
        version { strictly("2.9.4") }
    }
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4") {
        version { strictly("2.9.4") }
    }
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4") {
        version { strictly("2.9.4") }
    }
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4") {
        version { strictly("2.9.4") }
    }
    implementation("androidx.datastore:datastore-preferences:1.1.7") {
        version { strictly("1.1.7") }
    }

    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
