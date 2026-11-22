import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// Keys come from local.properties (git-ignored). See local.properties.sample.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
fun prop(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: default).trim()

android {
    namespace = "com.aurviz.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aurviz.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-t1"

        buildConfigField("String", "LLM_DEFAULT_PROVIDER", "\"${prop("LLM_DEFAULT_PROVIDER", "gemini")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${prop("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${prop("GEMINI_MODEL", "gemini-2.5-flash")}\"")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${prop("DEEPSEEK_API_KEY")}\"")
        buildConfigField("String", "DEEPSEEK_BASE_URL", "\"${prop("DEEPSEEK_BASE_URL", "https://api.deepseek.com")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${prop("GROQ_API_KEY")}\"")
        buildConfigField("String", "GROQ_MODEL", "\"${prop("GROQ_MODEL", "llama-3.1-8b-instant")}\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${prop("GOOGLE_OAUTH_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_SECRET", "\"${prop("GOOGLE_OAUTH_CLIENT_SECRET")}\"")
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0")
    kapt("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    // org.json ships with Android at runtime; this is for JVM unit tests only.
    testImplementation("org.json:json:20240303")
}
