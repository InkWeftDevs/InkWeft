pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.ghostscript.com"); content { includeGroup("com.artifex.mupdf") } }
        maven { url = uri("https://jitpack.io"); content { includeGroup("com.github.requery") } }
    }
}
rootProject.name = "InkWeftAndroid"
include(":app", ":core-domain", ":data-local", ":macrobenchmark")
