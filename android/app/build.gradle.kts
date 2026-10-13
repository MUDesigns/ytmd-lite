import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

val repoRoot = rootDir.parentFile
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Same version as the desktop app (src-tauri/tauri.conf.json).
@Suppress("UNCHECKED_CAST")
val desktopVersion = (JsonSlurper().parse(File(repoRoot, "src-tauri/tauri.conf.json")) as Map<String, Any>)["version"] as String

// Last.fm credentials are shared with the desktop build (gitignored). Missing → Last.fm disabled.
@Suppress("UNCHECKED_CAST")
val lastFm: Map<String, String> = File(repoRoot, "src-tauri/lastfm.secrets.json").let { f ->
    if (f.exists()) JsonSlurper().parse(f) as Map<String, String> else emptyMap()
}

fun quoted(value: String?) = "\"" + (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.ytmdesktop.lite"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ytmdesktop.lite"
        minSdk = 26
        targetSdk = 37
        versionName = desktopVersion
        // CI patch numbers go up to 65535 (scripts/release-version.mjs), so leave room for them.
        versionCode = desktopVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10_000_000 + b * 100_000 + c }

        buildConfigField("String", "LASTFM_API_KEY", quoted(lastFm["api_key"]))
        buildConfigField("String", "LASTFM_SECRET", quoted(lastFm["secret"]))

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing comes from the environment (CI secrets); without it, release builds are unsigned.
    val keystore = System.getenv("YTMD_KEYSTORE")?.takeIf { it.isNotBlank() && File(it).exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = File(keystore)
                storePassword = System.getenv("YTMD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YTMD_KEY_ALIAS")
                keyPassword = System.getenv("YTMD_KEY_PASSWORD") ?: System.getenv("YTMD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.nio / java.time APIs.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        (localProps.getProperty("chaquopy.buildPython") ?: System.getenv("YTMD_BUILD_PYTHON"))?.let { buildPython(it) }
        pip {
            // Catalog API dependencies of python-backend/server.py (see its requirements.txt).
            // yt-dlp is the fallback stream resolver; the Node-based PO-token tools are desktop-only.
            install("ytmusicapi==1.12.1")
            install("flask")
            install("flask-cors")
            install("requests")
            install("yt-dlp")
        }
    }
    sourceSets {
        getByName("main") {
            // Share the desktop backend instead of copying it.
            srcDir(File(repoRoot, "python-backend"))
            include("ytmd_android.py", "server.py", "audio_proxy.py", "stream_resolver.py")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
