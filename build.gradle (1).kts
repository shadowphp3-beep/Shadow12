plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val serverUrl: String = ((project.findProperty("SHADOW_SERVER_URL") as String?) ?: "").trim().trimEnd('/')

android {
    namespace = "com.shadow.store"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shadow.store"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
}

// Fail the build early (with a clear message) if the server address is missing or not HTTPS.
tasks.named("preBuild") {
    doFirst {
        if (serverUrl.isEmpty()) {
            throw GradleException("SHADOW_SERVER_URL is empty. Set it in gradle.properties (e.g. https://example.com).")
        }
        if (!serverUrl.startsWith("https://")) {
            throw GradleException("SHADOW_SERVER_URL must start with https://")
        }
    }
}
