import dev.detekt.gradle.extensions.FailOnSeverity
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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

// Opt-in release signing: only when all four PAPYRUS_* properties/env vars are set; the default build stays unsigned and the keystore is never committed.
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
    namespace = "io.github.pixelrick420.papyrus"
    compileSdk = 37

    // Pinned so `stripReleaseDebugSymbols` finds an installed strip tool.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.pixelrick420.papyrus"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "2.1.2"
        testInstrumentationRunner = "io.github.pixelrick420.papyrus.e2e.PapyrusTestRunner"
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

    // RaTeX, the only native dependency, ships arm64-v8a, armeabi-v7a and x86_64; each ARM ABI gets a slim APK and the universal one carries all three.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
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

    // The migration test opens each exported schema; MigrationTestHelper reads them as assets.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

    // Deck builders are shared by JVM parser tests and the instrumented raster goldens.
    sourceSets.getByName("test").kotlin.directories.add("src/sharedTest/java")
    sourceSets.getByName("androidTest").kotlin.directories.add("src/sharedTest/java")

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
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.customview)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.pdfbox.android)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    // `data:` URI images only; the HTTP loaders are removed in MarkdownRenderer, the reader stays offline.
    implementation(libs.markwon.image)
    implementation(libs.ratex.android)
    // No Prism4j: fenced code is coloured by the app's own tokenizer (SyntaxHighlighter), and autolinks are
    // matched by Autolinks, so neither `syntax-highlight` nor `linkify` is needed.

    // Local-only crash reporting (replaces Crashlytics). `auto-service` is excluded: it leaks in from acra-core and drags in Guava.
    implementation(libs.acra.core) {
        exclude(group = "com.google.auto.service", module = "auto-service")
    }

    // JUnit 4, not 5: AGP's testDebugUnitTest runs it without an extra runner. No Robolectric: these tests need no Android runtime beyond a stubbed XmlPullParser.
    testImplementation(libs.junit)
    // kxml2 is a reference XmlPullParser: the extractor takes its parser from an injected factory so
    // tests can substitute this one, since android.util.Xml is a platform stub under plain JUnit.
    testImplementation(libs.kxml2)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // The end-to-end tests drive the installed app through its accessibility tree, so they never call into its code.
    androidTestImplementation(libs.uiautomator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Room exports each schema version here, committed so a migration is written against the exact schema a release was built from.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Both variants export to the same file; run concurrently, one truncates it while the other deserializes it.
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}

// Reports drive the Compose stability review before screen decomposition.
if (providers.gradleProperty("papyrus.composeReports").map(String::toBoolean).getOrElse(false)) {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose-reports")
    }
}

detekt {
    buildUponDefaultConfig = false
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline.set(file("$rootDir/config/detekt/baseline.xml"))
    // detekt 2.x replaced 1.x's weight-based maxIssues with failOnSeverity; Info -- the lowest
    // severity -- preserves the old maxIssues=0 strictness.
    failOnSeverity = FailOnSeverity.Info
    // The AST-only rules run here; the type-resolution rules run in detektDebug below.
    ignoreFailures = false
}

// Plain `detekt` cannot resolve types, so the three type-resolution rules only run under detektDebug.
// That task gets its own config: the AST "unused" rules produce false positives when AGP's BuildConfig is unresolved.
tasks.matching { it.name == "detektDebug" }.configureEach {
    if (this is dev.detekt.gradle.Detekt) {
        config.setFrom(rootProject.file("config/detekt/detekt-type-resolution.yml"))
        baseline.set(rootProject.file("config/detekt/baseline.xml"))
    }
}
tasks.named("check") { dependsOn("detektDebug") }

// `release` is unsigned unless PAPYRUS_* properties were given, so adb gets a throwaway debug-signed copy. The debug key must never become the release signing config.

val sdkDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile
val adbPath = sdkDirectory.resolve("platform-tools/adb").absolutePath
val apksignerPath = sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/apksigner").absolutePath
val debugKeystorePath = File(System.getProperty("user.home"), ".android/debug.keystore").absolutePath
val apkOutputDir = layout.buildDirectory.dir("outputs/apk").get().asFile.absolutePath

fun registerLocalInstall(name: String, variant: String, apkNames: List<String>) {
    tasks.register(name) {
        group = "install"
        description = "Builds and installs the $variant APK on the connected device."
        dependsOn("assemble$variant")

        val adb = adbPath
        val apksigner = apksignerPath
        val keystore = debugKeystorePath
        val outputDir = apkOutputDir
        doLast {
            val apk = apkNames.firstNotNullOfOrNull { candidate ->
                File(outputDir, candidate).takeIf { it.isFile }
            } ?: error("No APK for $variant in $outputDir. Did assemble$variant run?")

            val toInstall = if (apk.name.contains("-unsigned")) {
                val signed = File(apk.parentFile, "${apk.nameWithoutExtension}-local-signed.apk")
                Files.copy(apk.toPath(), signed.toPath(), StandardCopyOption.REPLACE_EXISTING)
                val signer = ProcessBuilder(
                    listOf(
                        apksigner, "sign",
                        "--ks", keystore,
                        "--ks-pass", "pass:android",
                        "--key-pass", "pass:android",
                        "--ks-key-alias", "androiddebugkey",
                        "--min-sdk-version", "26",
                        signed.absolutePath,
                    ),
                ).redirectErrorStream(true).start()
                val signOutput = signer.inputStream.bufferedReader().readText().trim()
                check(signer.waitFor() == 0) { "apksigner failed: $signOutput" }
                signed
            } else {
                apk
            }

            val installer = ProcessBuilder(listOf(adb, "install", "-r", "-t", toInstall.absolutePath))
                .redirectErrorStream(true).start()
            val output = installer.inputStream.bufferedReader().readText().trim()
            check(installer.waitFor() == 0) { "adb install failed: $output" }
            logger.lifecycle("Installed ${toInstall.name} (${toInstall.length() / 1024 / 1024} MB): $output")
        }
    }
}

registerLocalInstall(
    name = "installLocalDebug",
    variant = "Debug",
    apkNames = listOf("debug/app-universal-debug.apk", "debug/app-universal-debug-unsigned.apk"),
)

registerLocalInstall(
    name = "installLocalRelease",
    variant = "Release",
    apkNames = listOf("release/app-universal-release.apk", "release/app-universal-release-unsigned.apk"),
)
