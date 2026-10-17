plugins {
    id("com.android.application") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

val sdkOutput = layout.buildDirectory.dir("webrtc-sdk")
val prepareSdk = tasks.register<Exec>("prepareWebRtcSdk") {
    inputs.files("sdk/pin.json", "sdk/external-playout.patch", "tools/prepare_sdk.py", "tools/validate_sdk_patch.py")
    outputs.dir(sdkOutput)
    commandLine("python3", "tools/prepare_sdk.py")
}

android {
    namespace = "com.callagent.webrtcprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.callagent.gateway.webrtcprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-probe"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets.getByName("main") {
        java.srcDir(sdkOutput.map { it.dir("source") })
        assets.srcDir(sdkOutput.map { it.dir("assets") })
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild") { dependsOn(prepareSdk) }
dependencies {
    implementation(files(sdkOutput.map { it.file("external-playout.aar") }).builtBy(prepareSdk))
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
