plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

providers.gradleProperty("hypnosiaBuildDir").orNull?.let { customBuildDir ->
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("$customBuildDir/license-server"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.hypnosia.licenseserver.LocalLicenseServerKt")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
