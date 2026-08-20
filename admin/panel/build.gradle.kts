plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

providers.gradleProperty("hypnosiaBuildDir").orNull?.let { customBuildDir ->
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("$customBuildDir/license-admin"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.hypnosia.licenseadmin.AdminPanelServerKt")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
