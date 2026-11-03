import org.gradle.internal.os.OperatingSystem

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val rustCrateDir = rootProject.file("rust/spektrafilm-jni")
val upstreamDataDir = rootProject.file("third_party/spektrafilm-rs/data")
val jniLibsOut = layout.projectDirectory.dir("src/main/jniLibs")
val generatedAssets = layout.buildDirectory.dir("generated/spektrafilm-assets")

android {
    namespace = "com.spektrafilm.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.spektrafilm.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal-use build: sign release with the debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(generatedAssets)

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    jvmToolchain(17)
}

/** Spectral data (profiles, LUTs, filters) shipped as assets/spektrafilm-data. */
val copySpektrafilmData by tasks.registering(Sync::class) {
    from(upstreamDataDir) {
        include("profiles/**", "luts/**", "filters/**")
    }
    into(generatedAssets.map { it.dir("spektrafilm-data") })
}

/** Cross-compile the Rust JNI bridge for arm64 with cargo-ndk. */
val cargoNdkBuild by tasks.registering(Exec::class) {
    workingDir = rustCrateDir
    val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo" + if (OperatingSystem.current().isWindows) ".exe" else "")
    executable = if (cargo.exists()) cargo.absolutePath else "cargo"
    args("ndk", "-t", "arm64-v8a", "--platform", "29", "-o", jniLibsOut.asFile.absolutePath, "build", "--release")
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    // Windows gnu host toolchain needs a `dlltool` for build scripts; we ship the NDK's llvm-dlltool there.
    val hostBin = File(System.getProperty("user.home"), "Android/hostbin")
    if (hostBin.isDirectory) {
        environment("PATH", hostBin.absolutePath + File.pathSeparator + System.getenv("PATH"))
    }
    inputs.dir(rustCrateDir.resolve("src"))
    inputs.file(rustCrateDir.resolve("Cargo.toml"))
    inputs.dir(rootProject.file("third_party/spektrafilm-rs/crates"))
    outputs.dir(jniLibsOut)
}

tasks.named("preBuild") {
    dependsOn(copySpektrafilmData, cargoNdkBuild)
}
