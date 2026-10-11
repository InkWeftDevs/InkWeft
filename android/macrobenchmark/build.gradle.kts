// AGP is already on the root buildscript classpath at the catalog's locked version.
plugins { id("com.android.test") }

android {
    namespace = "org.inkweft.macrobenchmark"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    val keyPath = System.getenv("INKWEFT_SIGNING_STORE")
    if (!keyPath.isNullOrBlank()) {
        signingConfigs.create("ownerPreview") {
            storeFile = file(keyPath)
            storePassword = requireNotNull(System.getenv("INKWEFT_SIGNING_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("INKWEFT_SIGNING_ALIAS"))
            keyPassword = storePassword
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("ownerPreview")
    }
    buildTypes.create("performance") {
        initWith(buildTypes.getByName("debug"))
        matchingFallbacks += listOf("performance")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "performance" && providers.gradleProperty("inkweftMacrobenchmark").orNull == "true"
    }
}
dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.uiautomator)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    implementation(libs.junit)
}
