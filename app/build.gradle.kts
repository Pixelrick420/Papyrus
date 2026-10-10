import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
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

// ---------------------------------------------------------------------------
// Opt-in release signing.
//
// Signing credentials are read from Gradle properties first, then environment
// variables, and the `release` signing config is only created when all four are
// present. With none of them set -- the normal local and F-Droid case -- `release`
// stays unsigned exactly as before, so the default build can never accidentally
// produce a debug-signed artifact that looks shippable.
//
// Release signing is supplied per build, never committed. CI passes the real
// keystore in through these four variables from repository secrets:
//   PAPYRUS_STORE_FILE=/path/to/papyrus-release.jks
//   PAPYRUS_STORE_PASSWORD=<store password>
//   PAPYRUS_KEY_ALIAS=<key alias>
//   PAPYRUS_KEY_PASSWORD=<key password>
//
// The keystore itself is deliberately absent from the repository. It is also
// deliberately not reproducible per build: a key generated inside the runner
// differs on every run, which makes each release mutually uninstallable and
// unusable as an app-store upload. Back the one keystore up somewhere private
// and permanent -- losing it means never shipping an update to an installed app.
// ---------------------------------------------------------------------------
fun signingSetting(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val releaseSigning = listOf(
    "PAPYRUS_STORE_FILE",
    "PAPYRUS_STORE_PASSWORD",
    "PAPYRUS_KEY_ALIAS",
    "PAPYRUS_KEY_PASSWORD",
).map { it to signingSetting(it) }.toMap()

val hasReleaseSigning = releaseSigning.values.all { it != null }

android {
    namespace = "com.papyrus.app"
    compileSdk = 36

    // Pinned so `stripReleaseDebugSymbols` can find a strip tool. Without it AGP falls back to
    // its own built-in NDK default (27.0.120779), which is not what is installed here.
    // The NDK is here for that strip step alone. Keep this
    // line and the `ndk;` entry in .github/workflows/build.yml in step.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.papyrus.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.9"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigning.getValue("PAPYRUS_STORE_FILE"))
                storePassword = releaseSigning.getValue("PAPYRUS_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("PAPYRUS_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("PAPYRUS_KEY_PASSWORD")
            }
        }
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
            // Null when no signing properties were supplied, which leaves the APK unsigned.
            signingConfig = signingConfigs.findByName("release")
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
        // pdfbox-android pulls in BouncyCastle, which also ships post-quantum crypto
        // parameter files Papyrus never uses; those ~4 MB of PQC data are dead weight.
        resources.excludes += "org/bouncycastle/pqc/**"
        jniLibs.useLegacyPackaging = false // keep any .so page-aligned for 16 KB devices
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Room (SQLite index of documents / page order)
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // PDF text extraction (find-in-file) and page geometry. PdfBox-Android is the Apache-2.0
    // AAR port; the platform's PdfRenderer draws the pages.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown (MIT) - Spannable output, no WebView, no image loader / network
    val markwon = "4.6.2"
    implementation("io.noties.markwon:core:$markwon")
    implementation("io.noties.markwon:ext-strikethrough:$markwon")
    implementation("io.noties.markwon:ext-tables:$markwon")

    // Local-only crash reporting (replaces Crashlytics)
    //
    // `auto-service` is excluded on purpose: it reaches us only as a leaked runtime
    // dependency of acra-core and drags in Guava, which is dead weight. It is an
    // annotation processor needed only to build ACRA itself; at runtime our sender
    // is wired through the app's own
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
}

// Static analysis, scoped to dead code and correctness. The rule selection and the
// reasons each borderline ruleset is off live in config/detekt/detekt.yml.
detekt {
    buildUponDefaultConfig = false
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    // Type resolution is left off: it would require detekt to run against the
    // compiled classpath (and therefore after every Kotlin compile). Every rule
    // enabled in the config works on the AST alone.
    ignoreFailures = false
}

// ---------------------------------------------------------------------------
// Local device install helpers
//
// `release` carries no signingConfig unless the build was given PAPYRUS_*
// properties (see the block above), so a plain local `assembleRelease` still
// produces an unsigned, store-ready artifact. What we hand to adb is a throwaway
// copy signed with the local debug keystore, purely so adb will accept it. The
// debug key must never become the release build type's permanent signing config:
// that would produce an APK that looks shippable but must never reach F-Droid or
// Play.
// ---------------------------------------------------------------------------

val adb = android.sdkDirectory.resolve("platform-tools/adb")
val apksigner = android.sdkDirectory.resolve(
    "build-tools/${android.buildToolsVersion}/apksigner",
)
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

/**
 * Runs [command] to completion, returning its trimmed stdout and throwing on failure. Called only
 * from a task action, never at configuration time.
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
 * Resolves the single APK for [variant], signed name first. AGP writes `app-<variant>.apk` when the
 * variant has a signing config and `app-<variant>-unsigned.apk` when it does not, so both are probed:
 * `release` only gets a config when the build was given PAPYRUS_* properties.
 */
fun pickApk(variant: String): File {
    val out = layout.buildDirectory.dir("outputs/apk/$variant").get().asFile
    return listOf(File(out, "app-$variant.apk"), File(out, "app-$variant-unsigned.apk"))
        .firstOrNull { it.isFile }
        ?: error("No APK for $variant in ${out.absolutePath}. Did assemble$variant run?")
}

/** True when AGP already signed this output. `apksigner` would reject a second signature. */
fun isAlreadySigned(apk: File): Boolean = !apk.name.contains("-unsigned")

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

/**
 * Registers a task that builds [variant], signs the result if AGP left it unsigned, and
 * adb-installs it. Signing is read off the artifact rather than declared, because `release` is
 * unsigned by default and signed when the build supplied PAPYRUS_* properties.
 */
fun registerLocalInstall(
    name: String,
    variant: String,
    apkProvider: () -> File,
) {
    tasks.register(name) {
        group = "install"
        description = "Builds and installs the $variant APK on the connected device."
        dependsOn("assemble$variant")

        doLast {
            val picked = apkProvider()
            val toInstall = if (isAlreadySigned(picked)) picked else signForLocalInstall(picked)
            logger.lifecycle("Installing ${toInstall.name} (${toInstall.length() / 1024 / 1024} MB)")
            val result = runAdb("install", "-r", "-t", toInstall.absolutePath)
            logger.lifecycle(result)
        }
    }
}

registerLocalInstall(
    name = "installLocalDebug",
    variant = "Debug",
    apkProvider = { pickApk("debug") },
)

registerLocalInstall(
    name = "installLocalRelease",
    variant = "Release",
    apkProvider = { pickApk("release") },
)
