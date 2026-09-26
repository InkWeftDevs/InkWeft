plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val diagnosticBuild = providers.gradleProperty("inkweftDiagnosticBuild").orNull == "true"
val insertionPreview = providers.gradleProperty("inkweftInsertionPreview").orNull == "true"
fun commitValue(name: String): String = System.getenv(name)?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "local-unknown"
android {
    namespace = "org.inkweft.app"
    compileSdk = 36
    defaultConfig {
        applicationId = if (insertionPreview) "org.inkweft.app.a0.insertion" else if (diagnosticBuild) "org.inkweft.app.a0.workspace" else "org.inkweft.app.a0"
        minSdk = 31
        targetSdk = 36
        versionCode = 17
        versionName = "0.0.17-reading-handwriting"
        manifestPlaceholders["appLabel"] = if (insertionPreview) "墨织整合预览" else if (diagnosticBuild) "墨织工作台预览" else "墨织"
        buildConfigField("String", "BUILD_COMMIT", "\"${commitValue("GITHUB_SHA")}\"")
        buildConfigField("String", "SOURCE_COMMIT", "\"${commitValue("INKWEFT_HEAD_SHA")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Optional owner-controlled developer signing. Never put a private key into Git.
    // CI without these variables uses its isolated test key; delivery may be re-signed
    // locally with the owner-held key and must carry its own certificate/hash receipt.
    val keyPath = System.getenv("INKWEFT_SIGNING_STORE")
    if (!keyPath.isNullOrBlank()) {
        val password = requireNotNull(System.getenv("INKWEFT_SIGNING_PASSWORD")) { "Signing password missing" }
        val alias = requireNotNull(System.getenv("INKWEFT_SIGNING_ALIAS")) { "Signing alias missing" }
        signingConfigs.create("ownerPreview") {
            storeFile = file(keyPath)
            storePassword = password
            keyAlias = alias
            keyPassword = password
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("ownerPreview")
    }
    // Compress native libraries in sideload APKs; Android extracts the selected ABI at install time.
    packaging { jniLibs.useLegacyPackaging = true }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core-domain"))
    implementation(libs.onnxruntime)
    implementation(libs.mupdf)
    implementation(project(":data-local"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.ink.brush)
    implementation(libs.ink.strokes)
    implementation(libs.ink.rendering)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
    // Backup UI tests create and close an isolated source Room database.
    androidTestImplementation(libs.room.runtime)
    debugImplementation(libs.compose.ui.test.manifest)
}
