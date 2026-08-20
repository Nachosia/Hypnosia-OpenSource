import groovy.json.JsonSlurper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("fabric-loom") version "1.16.1"
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
}

version = property("mod_version") as String
group = property("maven_group") as String

val requestedTaskNames = gradle.startParameter.taskNames.map { it.lowercase() }
val defaultBuildDir = when {
    providers.gradleProperty("hypnosiaBuildDir").isPresent -> providers.gradleProperty("hypnosiaBuildDir").get()
    requestedTaskNames.any { it.contains("runclient") } -> "build-runclient-local-${System.currentTimeMillis()}"
    else -> null
}
defaultBuildDir?.let { customBuildDir ->
    layout.buildDirectory.set(layout.projectDirectory.dir(customBuildDir))
}

base {
    archivesName.set(property("archives_base_name") as String)
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.register("ensureIconMcmeta") {
    group = "resources"
    description = "Ensures every .png in icons folder has a corresponding .png.mcmeta with blur=true"

    val iconsDir = layout.projectDirectory.dir("src/main/resources/assets/hypnosia/textures/gui/icons")
    doLast {
        iconsDir.asFileTree.matching { include("**/*.png") }.forEach { png ->
            val mcmeta = File(png.parentFile, "${png.name}.mcmeta")
            if (!mcmeta.exists()) {
                mcmeta.writeText("""{"texture":{"blur":true,"clamp":false}}""")
                logger.lifecycle("Created ${mcmeta.relativeTo(iconsDir.asFile)}")
            }
        }
    }
}

tasks.processResources {
    dependsOn("ensureIconMcmeta")
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// ─── Build-time secret injection ───
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildconfig/main/kotlin")
    outputs.dir(outputDir)

    doLast {
        val dir = outputDir.get().asFile.resolve("dev/hypnosia")
        dir.mkdirs()

        val modApiKey = providers.gradleProperty("modApiKey").orElse(
            providers.environmentVariable("HYPNOSIA_MOD_API_KEY")
        ).getOrElse("REPLACE_ME_MOD_API_KEY")

        val modSecretKey = providers.gradleProperty("modSecretKey").orElse(
            providers.environmentVariable("HYPNOSIA_MOD_SECRET_KEY")
        ).getOrElse("REPLACE_ME_MOD_SECRET_KEY")

        val mediaExeFile = layout.projectDirectory.file("src/main/resources/native/windows/hypnosia_media.exe").asFile
        val mediaExeHash = if (mediaExeFile.isFile) {
            MessageDigest.getInstance("SHA-256").let { md ->
                mediaExeFile.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } > 0) {
                        md.update(buffer, 0, read)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        } else {
            ""
        }

        dir.resolve("BuildConfig.kt").writeText(
            """package dev.hypnosia

object BuildConfig {
    const val MOD_API_KEY = "${modApiKey.replace("\"", "\\\"")}"
    const val MOD_SECRET_KEY = "${modSecretKey.replace("\"", "\\\"")}"
    const val MEDIA_BRIDGE_EXE_HASH = "$mediaExeHash"
}
"""
        )
    }
}

sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/source/buildconfig/main/kotlin"))
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateBuildConfig)
}

tasks.withType<Jar>().configureEach {
    dependsOn(generateBuildConfig)
}

val figmaFileId = "4BfRUKPJD8vnOHSQbrzmKS"

tasks.register("exportFigmaIcons") {
    group = "figma"
    description = "Discovers icon nodes in Figma and exports them as 4x PNG files into assets/hypnosia/textures/gui/icons."

    val outputDir = layout.projectDirectory.dir("src/main/resources/assets/hypnosia/textures/gui/icons")
    outputs.dir(outputDir)

    doLast {
        val figmaProperties = Properties()
        val figmaPropertiesFile = rootProject.file("figma.properties")
        if (figmaPropertiesFile.isFile) {
            figmaPropertiesFile.inputStream().use(figmaProperties::load)
        }

        val token = providers.environmentVariable("FIGMA_PAT").orNull
            ?: providers.environmentVariable("FIGMA_TOKEN").orNull
            ?: figmaProperties.getProperty("figma.token")
            ?: throw GradleException(
                "Missing Figma token. Set FIGMA_PAT, FIGMA_TOKEN, or figma.token in figma.properties.",
            )

        val fileId = figmaProperties.getProperty("figma.fileId", figmaFileId)
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        fun figmaGet(uri: URI): String {
            val request = HttpRequest.newBuilder(uri)
                .header("X-Figma-Token", token)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw GradleException("Figma API failed: HTTP ${response.statusCode()}\n${response.body()}")
            }
            return response.body()
        }

        fun sanitizeIconName(name: String): String {
            val cleaned = name
                .trim()
                .replace(Regex("""(?i)^icon[/_\-\s:]*"""), "icon_")
                .replace(Regex("""[^A-Za-z0-9]+"""), "_")
                .trim('_')
                .lowercase()
            return cleaned.ifBlank { "icon" }
        }

        fun isIconCandidate(name: String, type: String): Boolean {
            val normalized = name.trim().lowercase()
            if (type == "COMPONENT") {
                return true
            }
            if (normalized.startsWith("icon")) {
                return true
            }
            if (normalized.contains(" icon") || normalized.contains("/icon") || normalized.contains("_icon")) {
                return true
            }

            val commonIconNames = setOf(
                "search",
                "settings",
                "gear",
                "profile",
                "user",
                "home",
                "visuals",
                "world",
                "client",
                "hud",
                "other",
                "calendar",
                "black hole",
                "black_hole",
                "plug",
                "plug socket",
            )
            return normalized in commonIconNames
        }

        fun collectIconNodes(node: Map<*, *>, icons: LinkedHashMap<String, String>) {
            val id = node["id"] as? String
            val name = node["name"] as? String
            val type = node["type"] as? String

            if (!id.isNullOrBlank() && !name.isNullOrBlank() && !type.isNullOrBlank() && isIconCandidate(name, type)) {
                icons[id] = name
            }

            @Suppress("UNCHECKED_CAST")
            val children = node["children"] as? List<Map<*, *>>
            children?.forEach { collectIconNodes(it, icons) }
        }

        fun uniqueFileNames(iconNodes: Map<String, String>): Map<String, String> {
            val used = mutableMapOf<String, Int>()
            return iconNodes.mapValues { (nodeId, nodeName) ->
                val baseName = sanitizeIconName(nodeName)
                val duplicateIndex = used.getOrDefault(baseName, 0)
                used[baseName] = duplicateIndex + 1
                if (duplicateIndex == 0) {
                    baseName
                } else {
                    val nodeSuffix = sanitizeIconName(nodeId.replace(':', '_'))
                    "${baseName}_${nodeSuffix}"
                }
            }
        }

        fun requestImageUrls(nodeIds: List<String>): Map<String, String?> {
            val images = linkedMapOf<String, String?>()
            nodeIds.chunked(100).forEach { batch ->
                val ids = batch.joinToString(",") { URLEncoder.encode(it, Charsets.UTF_8) }
                val imagesUri = URI.create("https://api.figma.com/v1/images/$fileId?ids=$ids&format=png&scale=4")
                val json = JsonSlurper().parseText(figmaGet(imagesUri)) as Map<*, *>
                val err = json["err"] as? String
                if (!err.isNullOrBlank()) {
                    throw GradleException("Figma Images API returned an error: $err")
                }

                @Suppress("UNCHECKED_CAST")
                val batchImages = json["images"] as? Map<String, String?>
                    ?: throw GradleException("Figma Images API response did not include an images map.")
                images.putAll(batchImages)
            }
            return images
        }

        logger.lifecycle("Fetching Figma document structure for $fileId...")
        val fileUri = URI.create("https://api.figma.com/v1/files/$fileId")
        val fileJson = JsonSlurper().parseText(figmaGet(fileUri)) as Map<*, *>

        @Suppress("UNCHECKED_CAST")
        val document = fileJson["document"] as? Map<*, *>
            ?: throw GradleException("Figma Files API response did not include a document tree.")

        val iconNodes = linkedMapOf<String, String>()
        collectIconNodes(document, iconNodes)

        if (iconNodes.isEmpty()) {
            throw GradleException("No icon-like nodes were found in Figma file $fileId.")
        }

        val fileNamesByNodeId = uniqueFileNames(iconNodes)
        logger.lifecycle("Discovered ${iconNodes.size} icon candidate node(s). Requesting PNG render URLs...")

        val images = requestImageUrls(iconNodes.keys.toList())

        val outputDirectory = outputDir.asFile
        outputDirectory.mkdirs()

        iconNodes.forEach { (nodeId, nodeName) ->
            val imageUrl = images[nodeId]
            if (imageUrl.isNullOrBlank()) {
                logger.warn("Skipping '$nodeName' ($nodeId): Figma did not return a render URL.")
                return@forEach
            }

            val downloadRequest = HttpRequest.newBuilder(URI.create(imageUrl))
                .GET()
                .build()
            val pngResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray())
            if (pngResponse.statusCode() !in 200..299) {
                throw GradleException("Failed to download icon '$nodeName' ($nodeId): HTTP ${pngResponse.statusCode()}")
            }

            val fileName = fileNamesByNodeId.getValue(nodeId)
            val outFile = outputDirectory.resolve("$fileName.png")
            outFile.writeBytes(pngResponse.body())
            logger.lifecycle("Exported $nodeName ($nodeId) -> ${outFile.invariantSeparatorsPath}")
        }
    }
}
