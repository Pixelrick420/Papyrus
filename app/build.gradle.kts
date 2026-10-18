import dev.detekt.gradle.extensions.FailOnSeverity
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("dev.detekt")
}

// F-Droid guard: fail the build if any configuration (direct or transitive) resolves
// GMS, Firebase, ML Kit or Play libraries.
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

// Opt-in release signing: `release` gets a signing config only when all four PAPYRUS_*
// properties/env vars are set, so the default build stays unsigned. The keystore is never committed, and a per-run key would make releases mutually uninstallable.
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
    compileSdk = 37

    // Pinned so `stripReleaseDebugSymbols` finds a strip tool; AGP's NDK default (27.0.120779) isn't installed here.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.papyrus.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.12"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigning.getValue("PAPYRUS_STORE_FILE")))
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
        // pdfbox-android's BouncyCastle ships ~4 MB of post-quantum crypto parameter files Papyrus never uses.
        resources.excludes += "org/bouncycastle/pqc/**"
        // Cert-path-validator i18n bundles; no bundled class references them.
        resources.excludes += "org/bouncycastle/x509/**"
        jniLibs.useLegacyPackaging = false // keep any .so page-aligned for 16 KB devices
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // PDF text extraction (find-in-file) and page geometry. PdfBox-Android is the Apache-2.0
    // AAR port; the platform's PdfRenderer draws the pages.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown (MIT): Spannable output, no WebView or network.
    val markwon = "4.6.2"
    implementation("io.noties.markwon:core:$markwon")
    implementation("io.noties.markwon:ext-strikethrough:$markwon")
    implementation("io.noties.markwon:ext-tables:$markwon")

    // Local-only crash reporting (replaces Crashlytics). `auto-service` is excluded on purpose: it
    // leaks in from acra-core and drags in Guava; our sender loads via the app's own META-INF/services file.
    implementation("ch.acra:acra-core:5.12.0") {
        exclude(group = "com.google.auto.service", module = "auto-service")
    }

    // JUnit 4, not 5: the extractor/sniffer tests need no Android runtime beyond a stubbed
    // XmlPullParser, and AGP's testDebugUnitTest runs JUnit 4 without an extra runner. No Robolectric -- these tests cover pure parsing logic.
    testImplementation("junit:junit:4.13.2")
    // kxml2 is a reference XmlPullParser: the extractor takes its parser from an injected factory so
    // tests can substitute this one, since android.util.Xml is a platform stub under plain JUnit.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

// Room exports each schema version as JSON here, committed so a migration is written against the exact
// schema a release was built from. `.gitignore` re-includes this dir because it ignores `*.json` globally.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Static analysis scoped to dead code and correctness; rule selection and reasons live in config/detekt/detekt.yml.
detekt {
    buildUponDefaultConfig = false
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline.set(file("$rootDir/config/detekt/baseline.xml"))
    // detekt 2.x replaced 1.x's weight-based maxIssues with failOnSeverity; Info -- the lowest
    // severity -- preserves the old maxIssues=0 strictness.
    failOnSeverity = FailOnSeverity.Info
    // Type resolution is left off: it would need detekt to run against the compiled classpath. Every enabled rule works on the AST alone.
    ignoreFailures = false
}

// Local device install helpers. `release` is unsigned unless the PAPYRUS_* properties were given, so we
// hand adb a throwaway debug-signed copy. The debug key must never become the release build type's signing config.

val sdkDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile
val adb = sdkDirectory.resolve("platform-tools/adb")
val apksigner = sdkDirectory.resolve(
    "build-tools/${android.buildToolsVersion}/apksigner",
)
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

/** Runs [command] to completion, returning trimmed stdout and throwing on failure. Task-action only, never at configuration time. */
fun runCommand(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exit = process.waitFor()
    check(exit == 0) { "Command failed (exit $exit): ${command.joinToString(" ")}\n$output" }
    return output
}

fun runAdb(vararg args: String): String = runCommand(listOf(adb.absolutePath) + args)

/** Resolves the single APK for [variant], probing the signed and `-unsigned` names: `release`
 *  only gets a signing config when the build was given PAPYRUS_* properties. */
fun pickApk(variant: String): File {
    val out = layout.buildDirectory.dir("outputs/apk/$variant").get().asFile
    return listOf(File(out, "app-$variant.apk"), File(out, "app-$variant-unsigned.apk"))
        .firstOrNull { it.isFile }
        ?: error("No APK for $variant in ${out.absolutePath}. Did assemble$variant run?")
}

/** True when AGP already signed this output; apksigner rejects a second signature. */
fun isAlreadySigned(apk: File): Boolean = !apk.name.contains("-unsigned")

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

/** Registers a task that builds [variant], signs it if AGP left it unsigned, and adb-installs it.
 *  Signing is read off the artifact because `release` is unsigned by default. */
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
