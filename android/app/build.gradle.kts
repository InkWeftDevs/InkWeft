plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val diagnosticBuild = providers.gradleProperty("inkweftDiagnosticBuild").orNull == "true"
val unsignedBuild = providers.gradleProperty("inkweftUnsignedBuild").orNull == "true"
val tlsFixture = providers.gradleProperty("inkweftTlsFixture").orNull
val performanceProbe = providers.gradleProperty("inkweftPerformanceProbe").orNull == "true"
val insertionPreview = providers.gradleProperty("inkweftInsertionPreview").orNull == "true"
val macrobenchmark = providers.gradleProperty("inkweftMacrobenchmark").orNull == "true"
val formalBuild = providers.gradleProperty("inkweftFormalBuild").orNull == "true"
val targetAbis = providers.gradleProperty("inkweftTargetAbis").orNull?.split(',')?.map { it.trim() }?.toSet().orEmpty()
require(targetAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }) { "Unknown target ABI" }
require(!formalBuild || (targetAbis.isNotEmpty() && !diagnosticBuild && !insertionPreview && !unsignedBuild && tlsFixture == null && !performanceProbe && !macrobenchmark)) {
    "Formal build requires explicit target ABIs and production configuration"
}
require(!macrobenchmark || (!diagnosticBuild && !insertionPreview && !unsignedBuild && tlsFixture == null)) {
    "Macrobenchmark requires its dedicated signed application"
}
fun commitValue(name: String): String = System.getenv(name)?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "local-unknown"
android {
    namespace = "org.inkweft.app"
    compileSdk = 36
    defaultConfig {
        applicationId = if (insertionPreview) "org.inkweft.app.a0.insertion" else if (diagnosticBuild) "org.inkweft.app.a0.workspace" else "org.inkweft.app.a0"
        minSdk = 31
        targetSdk = 36
        versionCode = 91
        versionName = "0.0.91-cloud-performance"
        if (targetAbis.isNotEmpty()) ndk { abiFilters += targetAbis }
        manifestPlaceholders["appLabel"] = if (insertionPreview) "墨织整合预览" else if (diagnosticBuild) "墨织工作台预览" else "墨织"
        manifestPlaceholders["benchmarkFixtureEnabled"] = macrobenchmark.toString()
        buildConfigField("String", "BUILD_COMMIT", "\"${commitValue("GITHUB_SHA")}\"")
        buildConfigField("String", "SOURCE_COMMIT", "\"${commitValue("INKWEFT_HEAD_SHA")}\"")
        buildConfigField("boolean", "FORMAL_BUILD", formalBuild.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Optional owner-controlled developer signing. Never put a private key into Git.
    // CI without these variables uses its isolated test key; delivery may be re-signed
    // locally with the owner-held key and must carry its own certificate/hash receipt.
    val keyPath = System.getenv("INKWEFT_SIGNING_STORE")
    require(!unsignedBuild || keyPath.isNullOrBlank()) { "Unsigned build cannot use owner signing" }
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
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("ownerPreview")
    }
    if (unsignedBuild) {
        require(providers.gradleProperty("android.experimental.useDefaultDebugSigningConfigForProfileableBuildtypes").orNull?.toBoolean() != true) {
            "Unsigned build requires default debug signing fallback to remain disabled"
        }
        buildTypes.getByName("debug").signingConfig = null
    }
    buildTypes.create("tlsProbe") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".tlsprobe"
        matchingFallbacks += listOf("debug")
    }
    buildTypes.create("performance") {
        initWith(buildTypes.getByName("release"))
        signingConfig = buildTypes.getByName("debug").signingConfig
        applicationIdSuffix = if (macrobenchmark) ".macrobenchmark" else ".performance"
        matchingFallbacks += listOf("release")
        isDebuggable = false
    }
    buildTypes.create("leakCheck") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".leakcheck"
        manifestPlaceholders["appLabel"] = "墨织内存检查"
        matchingFallbacks += listOf("debug")
    }
    buildTypes.names.filter { it != "leakCheck" }.forEach {
        sourceSets.getByName(it).kotlin.srcDir("src/noLeak/java")
    }
    if(performanceProbe){require(tlsFixture==null);testBuildType="performance"}
    if (tlsFixture != null) {
        require(insertionPreview) { "TLS fixture requires the isolated insertion application" }
        sourceSets.getByName("tlsProbe").res.srcDir(tlsFixture)
        testBuildType = "tlsProbe"
    }
    // Compress native libraries in sideload APKs; Android extracts the selected ABI at install time.
    packaging { jniLibs.useLegacyPackaging = true }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
val verifyFormalSource = tasks.register("verifyFormalSource") {
    doLast {
        require(formalBuild) { "Release packaging requires -PinkweftFormalBuild=true" }
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(rootDir).redirectErrorStream(true).start()
            val value = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "Cannot verify Git source identity" }
            return value
        }
        val head = git("rev-parse", "HEAD")
        require(commitValue("GITHUB_SHA") == head && commitValue("INKWEFT_HEAD_SHA") == head) { "Formal build commits must match checked-out HEAD" }
        require(git("status", "--porcelain", "--untracked-files=normal").isEmpty()) { "Formal build requires clean committed source" }
        require(!System.getenv("INKWEFT_SIGNING_STORE").isNullOrBlank()) { "Formal build requires owner signing" }
    }
}
tasks.matching { it.name == "preReleaseBuild" || (formalBuild && it.name == "preDebugBuild") }.configureEach {
    dependsOn(verifyFormalSource)
}
abstract class InkWeftBuildIdentity : DefaultTask() {
    @get:org.gradle.api.tasks.Input abstract val identityJson: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @org.gradle.api.tasks.TaskAction fun writeIdentity() {
        val directory = outputDirectory.get().asFile
        check(directory.mkdirs() || directory.isDirectory)
        directory.resolve("inkweft-build-identity.json").writeText(identityJson.get(), Charsets.UTF_8)
    }
}
val generateBuildIdentity = tasks.register<InkWeftBuildIdentity>("generateInkWeftBuildIdentity") {
    if (formalBuild) dependsOn(verifyFormalSource)
    identityJson.set(provider {
        val identity = mapOf("schema" to 1, "build_commit" to commitValue("GITHUB_SHA"),
            "source_commit" to commitValue("INKWEFT_HEAD_SHA"), "formal" to formalBuild,
            "target_abis" to (targetAbis.ifEmpty { setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }).sorted(),
            "application_id" to android.defaultConfig.applicationId, "version_name" to android.defaultConfig.versionName)
        groovy.json.JsonOutput.toJson(identity)
    })
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateBuildIdentity, InkWeftBuildIdentity::outputDirectory)
}
dependencies {
    implementation(project(":core-domain"))
    implementation(libs.onnxruntime)
    implementation(libs.jlatexmath)
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
    implementation(libs.ink.geometry)
    implementation(libs.ink.rendering)
    "leakCheckImplementation"(libs.leakcanary)
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
