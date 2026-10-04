plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.callagent.gateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callagent.gateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 429
        versionName = "1.3.3-dev"
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
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}
