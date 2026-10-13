import java.util.Properties
import java.util.Base64
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val backendProperties = Properties().apply {
    val file = rootProject.file("savely.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun backendSetting(name: String, fallback: String = ""): String =
    providers.environmentVariable(name).orNull ?: backendProperties.getProperty(name, fallback)
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val publicBackendKey = backendSetting("SUPABASE_ANON_KEY").also { value ->
    require(!value.startsWith("sb_secret_") && value.none(Char::isWhitespace)) {
        "La build accetta solo una chiave pubblica Supabase; nessuna chiave server deve entrare nell'APK."
    }
    if (value.count { it == '.' } == 2) {
        val role = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(value.split('.')[1]), Charsets.UTF_8)
            (JsonSlurper().parseText(payload) as? Map<*, *>)?.get("role")
        }.getOrNull()
        require(role == "anon") { "Il JWT di configurazione deve avere ruolo anon." }
    }
}

val signingPath = providers.environmentVariable("SAVELY_KEYSTORE").orNull
android {
    namespace = "cc.savely.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "cc.savely.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.1.12-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Real-backend smoke is invoked explicitly with a private synthetic-user session.
        testInstrumentationRunnerArguments["notClass"] = "cc.savely.android.data.RealBackendSmokeTest,cc.savely.android.data.RealProcessDeathSmokeTest,cc.savely.android.data.RealAndroidWebSmokeTest,cc.savely.android.data.RealFinanceFormSmokeTest,cc.savely.android.data.RealRoadmapSmokeTest,cc.savely.android.data.RealAuthQualificationTest,cc.savely.android.data.RealCoupleQualificationTest,cc.savely.android.data.RealAdminQualificationTest"
        buildConfigField("String", "SUPABASE_URL", quoted(backendSetting("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_ANON_KEY", quoted(publicBackendKey))
        buildConfigField("String", "WEB_URL", quoted(backendSetting("WEB_URL", "https://savely.cc")))
    }
    signingConfigs {
        if (signingPath != null) create("distribution") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("SAVELY_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("SAVELY_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("SAVELY_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingPath != null) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("parity/fixtures"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true; checkReleaseBuilds = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
