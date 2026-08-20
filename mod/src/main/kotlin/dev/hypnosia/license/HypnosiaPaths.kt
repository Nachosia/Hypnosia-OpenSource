package dev.hypnosia.license

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object HypnosiaPaths {
    private val gameDir: Path
        get() = FabricLoader.getInstance().gameDir

    private val legacyConfigDir: Path
        get() = FabricLoader.getInstance().configDir.resolve("hypnosia")

    val rootDir: Path
        get() {
            val dir = gameDir.resolve("hypnosia")
            dir.createDirectories()
            return dir
        }

    val configsDir: Path
        get() {
            migrateDirectory("configs")
            val dir = rootDir.resolve("configs")
            dir.createDirectories()
            return dir
        }

    fun rootFile(name: String): Path {
        migrateFile(name)
        return rootDir.resolve(name)
    }

    private fun migrateFile(name: String) {
        val target = gameDir.resolve("hypnosia").resolve(name)
        val legacy = legacyConfigDir.resolve(name)
        if (target.exists() || !legacy.exists()) {
            return
        }

        target.parent.createDirectories()
        Files.copy(legacy, target)
    }

    private fun migrateDirectory(name: String) {
        val target = gameDir.resolve("hypnosia").resolve(name)
        val legacy = legacyConfigDir.resolve(name)
        if (target.exists() || !legacy.exists() || !Files.isDirectory(legacy)) {
            return
        }

        Files.walk(legacy).use { paths ->
            paths.forEach { source ->
                val destination = target.resolve(legacy.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    destination.createDirectories()
                } else {
                    destination.parent.createDirectories()
                    Files.copy(source, destination)
                }
            }
        }
    }
}
