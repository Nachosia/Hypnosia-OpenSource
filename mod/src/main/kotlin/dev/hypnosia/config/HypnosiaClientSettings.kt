package dev.hypnosia.config

import dev.hypnosia.license.HypnosiaPaths
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.exists

object HypnosiaClientSettings {
    private const val FILE_NAME = "client-settings.properties"

    private val legacyFiles = mapOf(
        "hud." to "hud-modules.properties",
        "hotkeys." to "module-hotkeys.properties",
        "target." to "target-hud.properties",
        "watermark." to "watermark.properties",
        "menu." to "menu-state.properties",
        "ui." to "ui-state.properties",
        "playtime." to "playtime.properties",
    )

    private val lock = Any()
    private val properties = Properties()
    private var loaded = false

    fun string(key: String, default: String): String {
        synchronized(lock) {
            ensureLoadedLocked()
            return properties.getProperty(key, default)
        }
    }

    fun nullableString(key: String): String? {
        synchronized(lock) {
            ensureLoadedLocked()
            return properties.getProperty(key)?.takeIf { it.isNotBlank() }
        }
    }

    fun boolean(key: String, default: Boolean): Boolean {
        return string(key, default.toString()).toBooleanStrictOrNull() ?: default
    }

    fun int(key: String, default: Int): Int {
        return string(key, default.toString()).toIntOrNull() ?: default
    }

    fun long(key: String, default: Long): Long {
        return string(key, default.toString()).toLongOrNull() ?: default
    }

    fun float(key: String, default: Float): Float {
        return string(key, default.toString()).toFloatOrNull() ?: default
    }

    fun keys(prefix: String): Set<String> {
        synchronized(lock) {
            ensureLoadedLocked()
            return properties.stringPropertyNames()
                .asSequence()
                .filter { it.startsWith(prefix) }
                .toSet()
        }
    }

    fun snapshot(prefixes: Collection<String>): Map<String, String> {
        synchronized(lock) {
            ensureLoadedLocked()
            return properties.stringPropertyNames()
                .asSequence()
                .filter { key -> prefixes.any { prefix -> key.startsWith(prefix) } }
                .associateWith { key -> properties.getProperty(key) }
        }
    }

    fun replacePrefixes(prefixes: Collection<String>, values: Map<String, String>) {
        synchronized(lock) {
            ensureLoadedLocked()
            properties.stringPropertyNames()
                .filter { key -> prefixes.any { prefix -> key.startsWith(prefix) } }
                .forEach(properties::remove)
            values.forEach { (key, value) -> properties[key] = value }
            saveLocked()
        }
    }

    fun set(key: String, value: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            properties[key] = value
            saveLocked()
        }
        HypnosiaConfigProfiles.onClientSettingsChanged()
    }

    fun setAll(values: Map<String, String?>) {
        synchronized(lock) {
            ensureLoadedLocked()
            values.forEach { (key, value) ->
                if (value == null) {
                    properties.remove(key)
                } else {
                    properties[key] = value
                }
            }
            saveLocked()
        }
        HypnosiaConfigProfiles.onClientSettingsChanged()
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true

        val file = HypnosiaPaths.rootFile(FILE_NAME)
        if (file.exists()) {
            runCatching {
                Files.newInputStream(file).use(properties::load)
            }
        }

        var migrated = false
        legacyFiles.forEach { (prefix, fileName) ->
            migrated = migrateLegacyLocked(prefix, fileName) || migrated
        }
        if (migrated || !file.exists()) {
            saveLocked()
        }
    }

    private fun migrateLegacyLocked(prefix: String, fileName: String): Boolean {
        val legacy = HypnosiaPaths.rootFile(fileName)
        if (!legacy.exists()) return false

        val old = Properties()
        return runCatching {
            Files.newInputStream(legacy).use(old::load)
            var changed = false
            old.stringPropertyNames().forEach { oldKey ->
                val newKey = prefix + oldKey
                if (!properties.containsKey(newKey)) {
                    properties[newKey] = old.getProperty(oldKey)
                    changed = true
                }
            }
            changed
        }.getOrDefault(false)
    }

    private fun saveLocked() {
        val file = HypnosiaPaths.rootFile(FILE_NAME)
        file.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(file).use {
            properties.store(it, "Hypnosia client settings. Account and license keys are intentionally stored separately.")
        }
    }
}
