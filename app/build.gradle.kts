plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.asistan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.asistan"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        // Gemini anahtarı koda yazılmaz; GitHub Secrets'tan derleme sırasında eklenir
        val geminiKey = (System.getenv("GEMINI_API_KEY") ?: "").replace("\"", "").trim()
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
