import com.google.protobuf.gradle.*
import java.security.MessageDigest

plugins {
    id("com.google.protobuf")
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.posepocket"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.posepocket"
        minSdk = 26
        targetSdk = 35
        ndk { abiFilters += providers.gradleProperty("poseAbis").orElse("arm64-v8a,armeabi-v7a").get().split(",") }
        versionCode = 16
        versionName = "0.7.0-beta.1"
        manifestPlaceholders["poseDisplayLabel"]="@string/app_name"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Local reviewers can coexist with APKs signed on another development machine.
    buildTypes { getByName("debug") {
        applicationIdSuffix = providers.gradleProperty("poseApplicationIdSuffix").orElse(".debug").get()
        versionNameSuffix="-pose33-dev"
        manifestPlaceholders["poseDisplayLabel"]="Pocket Poser Dev 33"
    } }
    // ML Kit is the new local tracking baseline; -PposeDetectorMlKit=false re-enables HOG for QA.
    buildTypes.getByName("debug").buildConfigField(
        "boolean", "POSE_DETECTOR_MLKIT",
        providers.gradleProperty("poseDetectorMlKit").orElse("true").get()
    )
    buildTypes.getByName("release").buildConfigField("boolean", "POSE_DETECTOR_MLKIT", "true")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("io.grpc:grpc-okhttp:1.67.1")
    implementation("io.grpc:grpc-protobuf-lite:1.67.1")
    implementation("io.grpc:grpc-stub:1.67.1")
    implementation("org.opencv:opencv:4.11.0")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation("io.grpc:grpc-inprocess:1.67.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.67.1" } }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { maybeCreate("java").apply { option("lite") } }
            task.plugins { create("grpc") { option("lite") } }
        }
    }
}


// The MediaPipe model bundle is deliberately excluded from the public source.
// Developers must obtain it from its official URL and verify the fixed digest
// before building. This task is not a grant of redistribution rights.
tasks.register("verifyPoseLandmarkerAsset") {
    doLast {
        val asset = file("src/main/assets/pose_landmarker_lite.task")
        check(asset.isFile) {
            "Missing MediaPipe Lite model. Follow docs/MODELS.md before compiling."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        asset.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val length = stream.read(buffer)
                if (length < 0) break
                digest.update(buffer, 0, length)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a") {
            "MediaPipe Lite model digest mismatch; do not build with an unknown model."
        }
    }
}
tasks.named("preBuild") { dependsOn("verifyPoseLandmarkerAsset") }
