import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Conjunction Android app (Jetpack Compose).
 *
 * Serverless build (SPEC 5c): no Firebase, no push, no location. Nearby matching runs phone to
 * phone over Bluetooth LE (docs/P2P_PROTOCOL.md, nearby/ package).
 *
 * Library versions are the newest that support compileSdk 36 + AGP 8.13 (checked against each
 * AAR's aar-metadata.properties on Google Maven, 2026-11-03): androidx.core 1.19 / Compose UI 1.12
 * need compileSdk 37 + AGP 9.1, so this module pins core 1.18, Compose BOM 2026.06.01, lifecycle 2.10
 * and navigation 2.9.7 (lifecycle-compose 2.11 / navigation 2.10 also need compileSdk 37).
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Upload-key settings for Play releases; kept out of git in android/keystore.properties (same layout as Sundial). */
val uploadKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.metavirtuoso.conjunction"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metavirtuoso.conjunction"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Support / report address ("Report this reading", Report a person, arbitration opt-out).
        // legal/README.md [CONTACT EMAIL]; override with -Pconjunction.supportEmail=... .
        val supportEmail = providers.gradleProperty("conjunction.supportEmail").getOrElse("themetavirtuoso@gmail.com").trim()
        buildConfigField("String", "SUPPORT_EMAIL", "\"$supportEmail\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (uploadKey.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Unsigned when keystore.properties is absent; Play needs the upload-key-signed bundle.
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // MediaPipe memory-maps the model, so it must be stored uncompressed in the APK.
        noCompress += "task"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/mlmodels"))

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("conjunction.screenshots", providers.gradleProperty("conjunction.screenshots").getOrElse(""))
            it.systemProperty("conjunction.legalDir", rootProject.file("../legal").absolutePath)
            it.maxHeapSize = "2g"
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = false
        // Versions are pinned on purpose to the newest that support compileSdk 36 / AGP 8.13.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // On-device AI (Gemini Nano through AICore), same wrapper pattern as Sundial.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    // Palm: hand landmarks (model downloaded by :app:downloadHandLandmarker) + camera.
    implementation("com.google.mediapipe:tasks-vision:1.2.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    // Nearby (Bluetooth LE, android.bluetooth only; no Play services, no Firebase).
    implementation("androidx.datastore:datastore:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Offline time-zone grid generator / checker (see TimeZoneGridTool); test classpath only.
    testImplementation("net.iakovlev:timeshape:2026b.29")
}

/**
 * Downloads Google's official MediaPipe hand-landmarker model (float16, version 1) into
 * build/generated/mlmodels/models/ and verifies its SHA-256. It is packaged as an asset and loaded
 * from there; nothing is downloaded at runtime.
 */
val handLandmarkerUrl = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val handLandmarkerSha256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
val downloadHandLandmarker by tasks.registering {
    description = "Downloads and verifies the MediaPipe hand_landmarker.task model."
    val target = layout.buildDirectory.file("generated/mlmodels/models/hand_landmarker.task")
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (file.exists() && sha(file) == handLandmarkerSha256) return@doLast
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "hand_landmarker.task.part")
        try {
            URI(handLandmarkerUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            throw GradleException("Could not download $handLandmarkerUrl (network needed once per clean build): ${e.message}", e)
        }
        val actual = sha(tmp)
        check(actual == handLandmarkerSha256) { "hand_landmarker.task checksum mismatch: $actual" }
        tmp.renameTo(file)
    }
}
/**
 * Bundles the full legal documents (the .md files in legal/ at the repo root) as assets/legal/, so the waiver,
 * terms, privacy policy, community guidelines and safety tips the person accepts are exactly the
 * repository text. The acceptance record stores each document's version and SHA-256.
 */
val copyLegalDocs by tasks.registering(Copy::class) {
    description = "Copies the legal documents into the generated assets."
    from(rootProject.file("../legal")) {
        include("WAIVER.md", "TERMS.md", "PRIVACY.md", "COMMUNITY_GUIDELINES.md", "SAFETY_TIPS.md")
    }
    into(layout.buildDirectory.dir("generated/legalassets/legal"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legalassets"))

tasks.named("preBuild") { dependsOn(downloadHandLandmarker, copyLegalDocs) }

/** Regenerates src/main/assets/tz/tzgrid.bin from Timeshape (tz boundary data). Run on demand. */
tasks.register<Test>("writeTimeZoneGrid") {
    description = "Rebuilds the offline lat/lon -> IANA zone grid asset from Timeshape."
    group = "build"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTest.testClassesDirs
    classpath = unitTest.classpath
    dependsOn("compileDebugUnitTestKotlin", "processDebugJavaRes")
    systemProperty("conjunction.writeTzGrid", file("src/main/assets/tz/tzgrid.bin").absolutePath)
    filter { includeTestsMatching("com.metavirtuoso.conjunction.tz.TimeZoneGridTool") }
    maxHeapSize = "4g"
    outputs.upToDateWhen { false }
}
