plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionCode = providers.environmentVariable("GSM_RELEASE_VERSION_CODE").orNull?.let {
    requireNotNull(it.toIntOrNull()) { "GSM_RELEASE_VERSION_CODE must be an integer" }
} ?: 430
require(releaseVersionCode in 1..2_100_000_000) {
    "GSM_RELEASE_VERSION_CODE must be in Android's supported range 1..2100000000"
}
val releaseVersionName = providers.environmentVariable("GSM_RELEASE_VERSION_NAME").orNull ?: "1.4.0-dev"

android {
    namespace = "com.callagent.gateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callagent.gateway"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    // Release identity is supplied outside the repository and persists across upgrades.
    val releaseStore = providers.environmentVariable("GSM_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (!releaseStore.isNullOrBlank()) {
            create("production") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("GSM_RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("GSM_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("GSM_RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.8.7") {
        version { strictly("2.8.7") }
    }
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.window:window-java:1.5.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}
