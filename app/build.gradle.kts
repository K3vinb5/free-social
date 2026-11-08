import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

// Release signing material is never committed. It comes from the environment
// (CI: FREE_SOCIAL_KEYSTORE_FILE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD) or
// from a gitignored keystore.properties next to the root build file.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = signingValue("FREE_SOCIAL_KEYSTORE_FILE", "storeFile")

fun resolveStoreFile(path: String): File =
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }

android {
    namespace = "dev.k3vinb5.freesocial"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.k3vinb5.freesocial"
        minSdk = 32
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.2"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFilePath != null) {
                storeFile = resolveStoreFile(releaseStoreFilePath)
                storePassword = signingValue("FREE_SOCIAL_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("FREE_SOCIAL_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("FREE_SOCIAL_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
