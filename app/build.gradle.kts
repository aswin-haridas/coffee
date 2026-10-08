import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.coffee"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.coffee"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"
        // Key lives in gitignored secrets.properties so it never lands in the public repo.
        val secrets = rootProject.file("secrets.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
        buildConfigField("String", "OPENROUTER_KEY", "\"${secrets?.getProperty("OPENROUTER_KEY").orEmpty()}\"")
    }
    buildFeatures { buildConfig = true }
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
