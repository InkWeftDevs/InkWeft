plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(libs.junit) }

tasks.register<JavaExec>("storageScaleProbe") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("org.inkweft.core.StorageScaleProbe")
    maxHeapSize="64m"
    args(layout.buildDirectory.dir("storage-scale").get().asFile.absolutePath)
}
