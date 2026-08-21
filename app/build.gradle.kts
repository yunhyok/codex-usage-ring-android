import org.gradle.api.tasks.Sync
import groovy.json.JsonSlurper

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

// rustls-platform-verifier 0.7.0 calls a small Kotlin/Java Android verifier
// component. Resolve the matching AAR from the exact Cargo.lock package rather
// than a floating Maven release; the provider is only evaluated for native
// dependency resolution, so mockDebug remains JNI/verifier independent.
val nativeVerifierAar = providers.provider {
    providers.environmentVariable("RUSTLS_PLATFORM_VERIFIER_AAR").orNull?.let { staged ->
        val stagedFile = file(staged)
        check(stagedFile.isFile) { "RUSTLS_PLATFORM_VERIFIER_AAR does not exist: $stagedFile" }
        return@provider stagedFile
    }
    val cargo = sequenceOf(
        providers.environmentVariable("CARGO").orNull,
        providers.environmentVariable("CARGO_HOME").orNull?.let { file(it).resolve("bin/cargo.exe").path },
        file(System.getProperty("user.home")).resolve(".cargo/bin/cargo.exe").path,
        "cargo",
    ).filterNotNull().firstOrNull { it == "cargo" || file(it).isFile }
        ?: error("Cargo is required to locate rustls-platform-verifier-android")
    val metadata = providers.exec {
        workingDir(rootProject.projectDir)
        commandLine(cargo, "metadata", "--locked", "--format-version", "1", "--filter-platform", "aarch64-linux-android", "--manifest-path", rootProject.file("native/Cargo.toml").path)
    }.standardOutput.asText.get()
    val packages = (JsonSlurper().parseText(metadata) as Map<*, *>)["packages"] as List<*>
    val pkg = packages.asSequence().map { it as Map<*, *> }.first { it["name"] == "rustls-platform-verifier-android" }
    val manifest = file(pkg["manifest_path"].toString())
    val version = pkg["version"].toString()
    val aar = manifest.parentFile.resolve("maven/rustls/rustls-platform-verifier/$version/rustls-platform-verifier-$version.aar")
    check(aar.isFile) { "Missing exact rustls-platform-verifier $version AAR: $aar" }
    aar
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
    add("nativeImplementation", files(nativeVerifierAar))
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android supplies org.json at runtime; pin the JVM implementation so the
    // boundary decoder is exercised by plain unit tests rather than stubs.
    testImplementation("org.json:json:20240303")
}
