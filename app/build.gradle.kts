import org.gradle.api.tasks.Sync
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

group = "io.github.yunhyok"
version = "0.1.0"

android {
    namespace = "io.github.yunhyok.usagering"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.yunhyok.usagering"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    flavorDimensions += "backend"
    productFlavors {
        create("mock") {
            dimension = "backend"
            versionNameSuffix = "-mock"
        }
        create("native") {
            dimension = "backend"
            // Native login is intentionally gated behind the six-method JNI
            // boundary. No arbitrary JSON-RPC or private HTTP endpoint is exposed.
            ndk { abiFilters.add("arm64-v8a") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    val signingAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
    val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
    val signingKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
    val signingValues = listOf(keystorePath, signingAlias, keystorePassword, signingKeyPassword)
    if (signingValues.any { !it.isNullOrBlank() } && signingValues.any { it.isNullOrBlank() }) {
        throw GradleException("Release signing requires ANDROID_KEYSTORE_PATH, ANDROID_KEY_ALIAS, ANDROID_KEYSTORE_PASSWORD, and ANDROID_KEY_PASSWORD together")
    }
    if (signingValues.all { !it.isNullOrBlank() }) {
        signingConfigs.create("releaseEnv") {
            storeFile = file(keystorePath!!)
            storePassword = keystorePassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("releaseEnv")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// The native release gate produces the JNI library outside Gradle. Stage the
// exact, deterministic artifact into the native flavor only; mock variants do
// not depend on Cargo or JNI files.
val nativeSo = rootProject.file("native/target/aarch64-linux-android/release/libusage_ring_codex.so")
val stagedNativeJni = file("$buildDir/generated/nativeJniLibs")
val stageNativeCodex = tasks.register<Sync>("stageNativeCodex") {
    doFirst {
        check(nativeSo.isFile) {
            "Missing $nativeSo. Run the fail-closed ARM64 native gate before assembling a native variant."
        }
    }
    from(nativeSo) { rename { "libusage_ring_codex.so" } }
    into(stagedNativeJni.resolve("arm64-v8a"))
}
android.sourceSets.getByName("native").jniLibs.srcDir(stagedNativeJni)
tasks.matching { it.name.startsWith("mergeNative") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(stageNativeCodex) }

// rustls-platform-verifier source is vendored into the native flavor only.
// This preserves the JNI package/signature while avoiding Cargo's prebuilt artifact.
val rustlsVerifierSourceDir = rootProject.file("third_party/rustls-platform-verifier-android/src/main/java")
check(rustlsVerifierSourceDir.resolve("org/rustls/platformverifier/CertificateVerifier.kt").isFile) {
    "Vendored rustls-platform-verifier source is missing"
}
android.sourceSets.getByName("native").apply {
    // AGP 9 built-in Kotlin requires additional Kotlin directories to be
    // registered on AndroidSourceSet.kotlin; Java directories alone do not
    // feed compileNative*Kotlin.
    kotlin.directories.add(rustlsVerifierSourceDir.absolutePath)
    java.directories.add(rustlsVerifierSourceDir.absolutePath)
}

val verifyRustlsVerifierSource = tasks.register("verifyRustlsPlatformVerifierSource") {
    val sourceFile = rustlsVerifierSourceDir.resolve("org/rustls/platformverifier/CertificateVerifier.kt")
    val buildConfigFile = rustlsVerifierSourceDir.resolve("org/rustls/platformverifier/BuildConfig.java")
    inputs.files(sourceFile, buildConfigFile)
    doLast {
        val source = sourceFile.readText()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(sourceFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        check(sha256 == "ff38c72887aaabe9c54b582777109a70c29b258c47c347af37a65fe0970635e7") {
            "Vendored CertificateVerifier.kt hash changed; review provenance and update only with an explicit source adaptation."
        }
        val buildConfig = buildConfigFile.readText()
        val buildConfigSha256 = MessageDigest.getInstance("SHA-256").digest(buildConfigFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        check(buildConfigSha256 == "92f6fbc924c2675f33e67cf9089b9e75f21610439e785e06b6eae735361d7b8f") {
            "Vendored platform-verifier BuildConfig.java hash changed; review provenance before building a native variant."
        }
        check(buildConfig.contains("package org.rustls.platformverifier;"))
        check(Regex("public\\s+static\\s+final\\s+boolean\\s+TEST\\s*=\\s*false\\s*;").containsMatchIn(buildConfig)) {
            "Vendored platform-verifier BuildConfig.TEST must remain false in application builds."
        }
        check(source.contains("package org.rustls.platformverifier"))
        check(source.contains("private fun verifyCertificateChain("))
        check(source.contains("context: Context"))
        check(source.contains("serverName: String"))
        check(source.contains("authMethod: String"))
        check(source.contains("allowedEkus: Array<String>"))
        check(source.contains("ocspResponse: ByteArray?"))
        check(source.contains("time: Long"))
        check(source.contains("certChain: Array<ByteArray>"))
        check(source.contains("SOFT_FAIL"))
        check(source.contains("ONLY_END_ENTITY"))
        check(source.contains("PREFER_CRLS"))
        check(!source.contains("NO_FALLBACK"))
        check(source.contains("if (ocspResponse == null)"))
        check(source.contains("revocationOptions.add(PKIXRevocationChecker.Option.PREFER_CRLS)"))

        // The Android system trust store can contain a deleted or malformed
        // anchor at a colliding alias. The loop must advance for both cases;
        // this source invariant prevents a regression to the old continue-before-
        // increment infinite loop.
        val knownRoot = source.substringAfter("fun isKnownRoot").substringBefore("\n    }\n}")
        check(knownRoot.contains("if (anchor is X509Certificate)"))
        check(knownRoot.contains("} else if (anchor != null)"))
        check(knownRoot.contains("i += 1"))
        check(!knownRoot.contains("continue")) {
            "isKnownRoot collision scan must not continue before advancing its alias index."
        }
    }
}
tasks.named("check") { dependsOn(verifyRustlsVerifierSource) }

// Native source integrity is a prerequisite of every native compilation and
// packaging path, not only the aggregate `check` task. Keep this dependency
// read-only and attach it after AGP has registered variant tasks to avoid a
// task graph cycle while covering debug, release, assemble, and bundle flows.
tasks.configureEach {
    val nativeTask = name.startsWith("preNative") && name.endsWith("Build") ||
        name.startsWith("compileNative") ||
        name.startsWith("mergeNative") ||
        name.startsWith("packageNative") ||
        name.startsWith("assembleNative") ||
        name.startsWith("bundleNative")
    if (nativeTask && name != verifyRustlsVerifierSource.name) {
        dependsOn(verifyRustlsVerifierSource)
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.work)
    implementation(libs.androidx.datastore)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Android supplies org.json at runtime; pin the JVM implementation so the
    // boundary decoder is exercised by plain unit tests rather than stubs.
    testImplementation("org.json:json:20240303")
}
