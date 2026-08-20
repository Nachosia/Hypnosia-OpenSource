package dev.hypnosia.config

import dev.hypnosia.hud.HudModuleSettings
import dev.hypnosia.hud.ModuleHotkeys
import dev.hypnosia.hud.TargetHudSettings
import dev.hypnosia.hud.WatermarkSettings
import dev.hypnosia.other.FriendsManager
import dev.hypnosia.other.StreamerModeSettings
import dev.hypnosia.visual.AspectRatioSettings
import dev.hypnosia.visual.image.ImageRenderModule
import dev.hypnosia.world.WorldVisualSettings
import dev.hypnosia.license.HypnosiaPaths
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

object HypnosiaConfigProfiles {
    const val DEFAULT_NAME = "Default"

    private const val SELECTED_KEY = "ui.selected.config"
    private const val FORMAT = "hypnosia-config"
    private const val VERSION = 1
    private const val MAX_SETTINGS_COUNT = 512
    private const val MAX_SETTING_KEY_LENGTH = 96
    private const val MAX_SETTING_VALUE_LENGTH = 512

    private val managedPrefixes = listOf(
        "hud.",
        "watermark.",
        "target.",
        "hotkeys.",
        "module.",
        "world.",
        "visuals.",
        "other.",
        "icons.",
        "theme.",
        "image.",
    )

    private val nameRegex = Regex("""^[\p{L}\p{N} _.-]{1,48}$""")
    private var applying = false
    private var bootstrapped = false

    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        ensureDefaultExists()
        val selected = selectedName()
        val file = configFile(selected)
        if (file.exists()) {
            apply(selected)
        } else {
            select(DEFAULT_NAME)
        }
    }

    fun selectedName(): String {
        val raw = HypnosiaClientSettings.string(SELECTED_KEY, DEFAULT_NAME)
        return normalizeName(raw) ?: DEFAULT_NAME
    }

    fun listNames(): List<String> {
        ensureDefaultExists()
        val names = linkedSetOf(DEFAULT_NAME)
        runCatching {
            Files.list(HypnosiaPaths.configsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        val fileName = file.name
                        if (fileName.endsWith(".json", ignoreCase = true)) {
                            normalizeName(fileName.removeSuffix(".json"))?.let(names::add)
                        }
                    }
            }
        }
        return names.sortedWith(compareBy<String> { if (it == DEFAULT_NAME) 0 else 1 }.then(String.CASE_INSENSITIVE_ORDER))
    }

    fun create(name: String, copyCurrent: Boolean = true): Boolean {
        val normalized = normalizeName(name) ?: return false
        if (configFile(normalized).exists()) return true
        val settings = if (copyCurrent) currentSnapshot() else emptyMap()
        writeConfig(normalized, settings)
        return true
    }

    fun select(name: String): Boolean {
        val normalized = normalizeName(name) ?: return false
        saveActiveSnapshot()
        if (!configFile(normalized).exists()) {
            writeConfig(normalized, emptyMap())
        }
        apply(normalized)
        return true
    }

    fun rename(oldName: String, newName: String): Boolean {
        val old = normalizeName(oldName) ?: return false
        val new = normalizeName(newName) ?: return false
        if (old == DEFAULT_NAME || new == DEFAULT_NAME) return false
        val oldFile = configFile(old)
        val newFile = configFile(new)
        if (!oldFile.exists() || newFile.exists()) return false
        runCatching {
            Files.move(oldFile, newFile)
            if (selectedName().equals(old, ignoreCase = true)) {
                applying = true
                try {
                    HypnosiaClientSettings.set(SELECTED_KEY, new)
                } finally {
                    applying = false
                }
            }
        }.getOrElse { return false }
        return true
    }

    fun delete(name: String): Boolean {
        val normalized = normalizeName(name) ?: return false
        if (normalized == DEFAULT_NAME) return false
        val deleted = runCatching { Files.deleteIfExists(configFile(normalized)) }.getOrDefault(false)
        if (selectedName().equals(normalized, ignoreCase = true)) {
            select(DEFAULT_NAME)
        }
        return deleted
    }

    fun exportCanonicalBytes(name: String): ByteArray? {
        val normalized = normalizeName(name) ?: return null
        val settings = readConfigRaw(normalized) ?: return null
        return canonicalBytes(settings)
    }

    fun canonicalizeBytes(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val json = decodeUtf8Strict(bytes) ?: return null
        if (!json.contains("\"format\"") || !json.contains("\"$FORMAT\"")) return null
        val settingsBody = settingsObjectBody(json) ?: return null
        return canonicalBytes(decodeStringMap(settingsBody))
    }

    fun onClientSettingsChanged() {
        if (applying) return
        if (!bootstrapped) return
        saveActiveSnapshot()
    }

    fun saveActiveSnapshot() {
        val selected = selectedName()
        writeConfig(selected, currentSnapshot())
    }

    private fun apply(name: String) {
        val settings = readConfig(name) ?: emptyMap()
        applying = true
        try {
            HypnosiaClientSettings.set(SELECTED_KEY, name)
            HypnosiaClientSettings.replacePrefixes(managedPrefixes, settings)
        } finally {
            applying = false
        }
        reloadRuntimeSettings()
    }

    private fun reloadRuntimeSettings() {
        HudModuleSettings.reload()
        WatermarkSettings.reload()
        TargetHudSettings.reload()
        ModuleHotkeys.reload()
        WorldVisualSettings.reload()
        FriendsManager.reload()
        StreamerModeSettings.reload()
        AspectRatioSettings.reload()
        ImageRenderModule.reload()
        dev.hypnosia.visual.world.particles.WorldParticleSettings.reload()
        dev.hypnosia.visual.world.particles.hit.HitParticleSettings.reload()
        dev.hypnosia.visual.world.jump.JumpCircleSettings.reload()
        dev.hypnosia.visual.world.trails.TrailSettings.reload()
        dev.hypnosia.visual.world.hitcolor.HitColorSettings.reload()
        dev.hypnosia.visual.world.esp.TargetEspSettings.reload()
    }

    private fun currentSnapshot(): Map<String, String> =
        HypnosiaClientSettings.snapshot(managedPrefixes)

    private fun ensureDefaultExists() {
        if (!configFile(DEFAULT_NAME).exists()) {
            writeConfig(DEFAULT_NAME, currentSnapshot())
        }
    }

    private fun configFile(name: String): Path {
        val normalized = normalizeName(name) ?: DEFAULT_NAME
        val root = HypnosiaPaths.configsDir.normalize()
        val file = root.resolve("$normalized.json").normalize()
        if (!file.startsWith(root)) return root.resolve("$DEFAULT_NAME.json")
        return file
    }

    private fun writeConfig(name: String, settings: Map<String, String>) {
        val file = configFile(name)
        file.parent.createDirectories()
        val enriched = settings.toMutableMap()
        // Embed image files into config for cloud sync
        val entriesRaw = enriched["image.entries"]
        if (!entriesRaw.isNullOrBlank()) {
            entriesRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { imgPath ->
                val dataKey = "image.data.${imgPath.lowercase().replace(" ", "_")}"
                if (dataKey !in enriched) {
                    val imgFile = ImageRenderModule.KARTINKI_DIR.resolve(imgPath)
                    if (imgFile.exists()) {
                        runCatching {
                            val base64 = java.util.Base64.getEncoder().encodeToString(imgFile.readBytes())
                            enriched[dataKey] = base64
                        }
                    }
                }
            }
        }
        file.writeText(encodeConfig(sanitizeSettings(enriched)), StandardCharsets.UTF_8)
    }

    private fun readConfig(name: String): Map<String, String>? {
        val file = configFile(name)
        if (!file.exists()) return null
        val json = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return null
        if (!json.contains("\"format\"") || !json.contains("\"$FORMAT\"")) return emptyMap()
        val settingsBody = settingsObjectBody(json) ?: return emptyMap()
        val raw = decodeStringMap(settingsBody)
        val extracted = raw.toMutableMap()
        // Extract embedded image data to kartinki folder
        val dataKeys = raw.keys.filter { it.startsWith("image.data.") }
        if (dataKeys.isNotEmpty()) {
            for (key in dataKeys) {
                val imgPath = key.removePrefix("image.data.")
                val base64 = raw[key] ?: continue
                runCatching {
                    val bytes = java.util.Base64.getDecoder().decode(base64)
                    val target = ImageRenderModule.KARTINKI_DIR.resolve(imgPath).normalize()
                    val root = ImageRenderModule.KARTINKI_DIR.normalize()
                    if (!target.startsWith(root)) {
                        println("[Hypnosia] Path traversal blocked in config image extraction: $imgPath")
                        return@runCatching
                    }
                    target.parent.createDirectories()
                    target.writeBytes(bytes)
                }
                extracted.remove(key)
            }
            ImageRenderModule.reload()
        }
        return sanitizeSettings(extracted)
    }

    private fun readConfigRaw(name: String): Map<String, String>? {
        val file = configFile(name)
        if (!file.exists()) return null
        val json = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return null
        if (!json.contains("\"format\"") || !json.contains("\"$FORMAT\"")) return emptyMap()
        val settingsBody = settingsObjectBody(json) ?: return emptyMap()
        return sanitizeSettings(decodeStringMap(settingsBody))
    }

    private fun canonicalBytes(settings: Map<String, String>): ByteArray =
        encodeConfig(sanitizeSettings(settings)).toByteArray(StandardCharsets.UTF_8)

    private fun sanitizeSettings(settings: Map<String, String>): Map<String, String> {
        val sanitized = linkedMapOf<String, String>()
        settings.entries
            .asSequence()
            .filter { (key, value) -> validSettingKey(key) && (key.startsWith("image.data.") || validSettingValue(value)) }
            .sortedBy { it.key }
            .take(MAX_SETTINGS_COUNT)
            .forEach { (key, value) -> sanitized[key] = value }
        return sanitized
    }

    private fun validSettingKey(key: String): Boolean =
        key.length in 1..MAX_SETTING_KEY_LENGTH &&
            key.all { it.code in 0x21..0x7E } &&
            managedPrefixes.any { prefix -> key.startsWith(prefix) }

    private fun validSettingValue(value: String): Boolean =
        value.length <= MAX_SETTING_VALUE_LENGTH && value.none { it.code < 0x20 || it == '\u007F' }

    private fun encodeConfig(settings: Map<String, String>): String {
        val body = settings.entries
            .sortedBy { it.key }
            .joinToString(",\n") { (key, value) ->
                "    \"${jsonEscape(key)}\": \"${jsonEscape(value)}\""
            }
        val settingsBlock = if (body.isBlank()) "" else "\n$body\n  "
        return buildString {
            append("{\n")
            append("  \"format\": \"").append(FORMAT).append("\",\n")
            append("  \"version\": ").append(VERSION).append(",\n")
            append("  \"settings\": {").append(settingsBlock).append("}\n")
            append("}\n")
        }
    }

    private fun settingsObjectBody(json: String): String? {
        val keyIndex = json.indexOf("\"settings\"")
        if (keyIndex < 0) return null
        val start = json.indexOf('{', keyIndex)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(start + 1, i)
                }
            }
        }
        return null
    }

    private fun decodeStringMap(body: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            val keyStart = body.indexOf('"', index)
            if (keyStart < 0) break
            val keyEnd = findStringEnd(body, keyStart + 1) ?: break
            val colon = body.indexOf(':', keyEnd + 1)
            if (colon < 0) break
            val valueStart = body.indexOf('"', colon + 1)
            if (valueStart < 0) break
            val valueEnd = findStringEnd(body, valueStart + 1) ?: break
            result[jsonUnescape(body.substring(keyStart + 1, keyEnd))] =
                jsonUnescape(body.substring(valueStart + 1, valueEnd))
            index = valueEnd + 1
        }
        return result
    }

    private fun findStringEnd(value: String, start: Int): Int? {
        var escaped = false
        for (i in start until value.length) {
            val c = value[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                return i
            }
        }
        return null
    }

    private fun normalizeName(raw: String): String? {
        val withoutExtension = if (raw.endsWith(".json", ignoreCase = true)) raw.dropLast(5) else raw
        val cleaned = withoutExtension
            .trim()
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.')
        if (!nameRegex.matches(cleaned)) return null
        return if (cleaned.equals(DEFAULT_NAME, ignoreCase = true)) DEFAULT_NAME else cleaned
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun jsonUnescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c != '\\' || i >= value.length) {
                append(c)
                continue
            }
            when (val escaped = value[i++]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                '\\' -> append('\\')
                '"' -> append('"')
                else -> append(escaped)
            }
        }
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
