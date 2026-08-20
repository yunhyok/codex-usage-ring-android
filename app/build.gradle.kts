import org.gradle.api.tasks.Sync

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
            // Native login is intentionally gated behind NativeCodexBridge. No JNI
            // implementation or private OpenAI endpoint is bundled in this build.
            ndk { abiFilters.add("arm64-v8a") }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
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
}
