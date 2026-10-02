import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// ---------------------------------------------------------------------------
// F-Droid guard: fail the build if ANY configuration (direct or transitive)
// tries to resolve Google Mobile Services, Firebase, ML Kit or Play libraries.
// ---------------------------------------------------------------------------
val forbiddenGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.mlkit",
    "com.google.android.play",
    "com.google.android.datatransport",
)
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val group = requested.group
        if (forbiddenGroups.any { group == it || group.startsWith("$it.") }) {
            throw GradleException(
                "Forbidden proprietary dependency ${requested.group}:${requested.name} " +
                    "(GMS / Firebase / ML Kit are not allowed: F-Droid compliance)."
            )
        }
    }
}

android {
    namespace = "com.papyrus.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.papyrus.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // required by ACRA's buildConfigClass
    }

    // F-Droid: do not embed the Google-signed "dependency info" blob in APK/AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false // keep .so page-aligned for 16 KB devices
    }

    // OpenCV's libopencv_java4.so is ~60 MB per ABI, so an unsplit APK is unusable
    // on both stores. Per-ABI APKs are what users actually download; the universal APK
    // is what F-Droid builds/reproduces, so we emit both rather than choosing.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Room (SQLite index of documents / page order)
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // CameraX (AOSP-based, no GMS)
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Scanner pipeline: OpenCV (Apache-2.0, Maven Central) + PdfBox-Android (Apache-2.0)
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown (MIT) - Spannable output, no WebView, no image loader / network
    val markwon = "4.6.2"
    implementation("io.noties.markwon:core:$markwon")
    implementation("io.noties.markwon:ext-strikethrough:$markwon")
    implementation("io.noties.markwon:ext-tables:$markwon")

    // Local-only crash reporting (replaces Crashlytics)
    //
    // `auto-service` is excluded on purpose. It reaches us only as a leaked runtime
    // dependency of acra-core and drags in Guava, whose Gradle module metadata forces
    // `com.google.guava:listenablefuture` to `strictly 9999.0-empty-...` (an EMPTY
    // stub). Because AGP enables consistent resolution between the compile and
    // runtime classpaths, that stub also lands on the compile classpath and hides
    // `ListenableFuture` from CameraX and concurrent-futures-ktx -- which breaks
    // ProcessCameraProvider.getInstance().await() and startFocusAndMetering().
    // auto-service is only an annotation processor needed to build ACRA itself;
    // at runtime our sender is wired through the app's own
    // META-INF/services/org.acra.sender.ReportSenderFactory file.
    implementation("ch.acra:acra-core:5.12.0") {
        exclude(group = "com.google.auto.service", module = "auto-service")
    }

    // Unit tests. JUnit 4 rather than JUnit 5 because the extractor and sniffer tests need no
    // Android runtime beyond a stubbed XmlPullParser, and JUnit 4 is what the AGP `testDebugUnitTest`
    // task runs without an extra runner dependency. Robolectric is deliberately absent: these tests
    // cover pure parsing logic, and pulling in Robolectric would put a full Android framework on the
    // test classpath for no coverage gain.
    testImplementation("junit:junit:4.13.2")
    // kxml2 is a *reference* XmlPullParser implementation. The extractor takes its parser from an
    // injected factory precisely so these tests can substitute this one: android.util.Xml is a
    // platform stub under plain JUnit, and Robolectric would be a heavy way to get a working parser.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // A real SQLite engine so the migration test executes the app's own SQL rather than asserting
    // against a hand-written copy of it. This is what `room-testing` would do through
    // MigrationTestHelper, but that artifact pulls in Robolectric and an instrumentation runner; the
    // raw driver covers the same ground for a migration that is plain SQL.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}

// ---------------------------------------------------------------------------
// Local device install helpers
//
// `installLocalRelease` deliberately does NOT attach a signingConfig to the
// `release` build type. The release artifact stays unsigned and store-ready; we
// sign a throwaway copy with the local debug keystore purely so adb will accept
// it. Attaching the debug key to `release` would produce an APK that looks
// shippable but must never reach F-Droid or Play.
// ---------------------------------------------------------------------------

val adb = android.sdkDirectory.resolve("platform-tools/adb")
val apksigner = android.sdkDirectory.resolve(
    "build-tools/${android.buildToolsVersion}/apksigner",
)
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

/**
 * Runs [command] to completion and returns its trimmed stdout, throwing if it fails.
 * Only ever called from a task action, never at configuration time.
 */
fun runCommand(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exit = process.waitFor()
    check(exit == 0) { "Command failed (exit $exit): ${command.joinToString(" ")}\n$output" }
    return output
}

fun runAdb(vararg args: String): String = runCommand(listOf(adb.absolutePath) + args)

/**
 * Picks the ABI split matching the connected device's primary ABI, falling back to the
 * universal APK. Installing the 32 MB arm64 split beats pushing the 102 MB universal APK
 * over USB, and the per-ABI APKs are what a store would serve the device anyway.
 */
fun pickSplitApk(variant: String): File {
    val out = layout.buildDirectory.dir("outputs/apk/$variant").get().asFile

    val primaryAbi = runCatching { runAdb("shell", "getprop", "ro.product.cpu.abi") }
        .getOrNull()
        .orEmpty()
        .trim()
    if (primaryAbi.isNotEmpty()) {
        val split = File(out, "app-$primaryAbi-$variant-unsigned.apk")
        if (split.isFile) return split
        logger.lifecycle("No $primaryAbi split for $variant; falling back to the universal APK.")
    }

    val universal = File(out, "app-universal-$variant-unsigned.apk")
    return if (universal.isFile) universal else File(out, "app-$variant-unsigned.apk")
}

/** Copies [apk] aside and signs the copy with the local debug keystore. */
fun signForLocalInstall(apk: File): File {
    require(apk.isFile) { "APK not found: ${apk.absolutePath}" }
    require(apksigner.isFile) { "apksigner not found: ${apksigner.absolutePath}" }
    require(debugKeystore.isFile) { "Debug keystore not found: ${debugKeystore.absolutePath}" }

    val signed = File(apk.parentFile, "${apk.nameWithoutExtension}-local-signed.apk")
    Files.copy(apk.toPath(), signed.toPath(), StandardCopyOption.REPLACE_EXISTING)

    runCommand(
        listOf(
            apksigner.absolutePath, "sign",
            "--ks", debugKeystore.absolutePath,
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--ks-key-alias", "androiddebugkey",
            "--min-sdk-version", "26",
            signed.absolutePath,
        ),
    )

    return signed
}

/** Registers a task that builds [variant], signs if needed, and adb-installs it. */
fun registerLocalInstall(
    name: String,
    variant: String,
    apkProvider: () -> File,
    signFirst: Boolean,
) {
    tasks.register(name) {
        group = "install"
        description = "Builds and installs the $variant APK on the connected device."
        dependsOn("assemble$variant")

        doLast {
            val toInstall = apkProvider().let { if (signFirst) signForLocalInstall(it) else it }
            logger.lifecycle("Installing ${toInstall.name} (${toInstall.length() / 1024 / 1024} MB)")
            val result = runAdb("install", "-r", "-t", toInstall.absolutePath)
            logger.lifecycle(result)
        }
    }
}

registerLocalInstall(
    name = "installLocalDebug",
    variant = "Debug",
    // Debug is already signed by AGP with the debug keystore; no re-signing needed.
    apkProvider = { pickSplitApk("debug") },
    signFirst = false,
)

registerLocalInstall(
    name = "installLocalRelease",
    variant = "Release",
    // Release ships unsigned on purpose; the copy handed to adb is signed locally.
    apkProvider = { pickSplitApk("release") },
    signFirst = true,
)
