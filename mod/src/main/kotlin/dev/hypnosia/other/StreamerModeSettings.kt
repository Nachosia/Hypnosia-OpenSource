package dev.hypnosia.other

import dev.hypnosia.config.HypnosiaClientSettings
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object StreamerModeSettings {
    enum class Level(val label: String) {
        LEVEL_1("Level 1"),
        LEVEL_2("Level 2"),
    }

    private const val ENABLED_KEY = "module.other.streamer_mode.enabled"
    private const val LEVEL_KEY = "other.streamer_mode.level"
    private const val REPLACEMENT_KEY = "other.streamer_mode.replacement"
    private const val DEFAULT_REPLACEMENT = "Hidden"

    private var loaded = false
    private var cachedEnabled = false
    private var cachedLevel = Level.LEVEL_1
    private var cachedReplacement = DEFAULT_REPLACEMENT

    fun isEnabled(): Boolean {
        ensureLoaded()
        return cachedEnabled
    }

    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(ENABLED_KEY, enabled.toString())
    }

    fun level(): Level {
        ensureLoaded()
        return cachedLevel
    }

    fun cycleLevel() {
        val next = if (level() == Level.LEVEL_1) Level.LEVEL_2 else Level.LEVEL_1
        cachedLevel = next
        loaded = true
        HypnosiaClientSettings.set(LEVEL_KEY, next.name)
    }

    fun replacement(): String {
        ensureLoaded()
        return cachedReplacement
    }

    fun setReplacement(value: String) {
        cachedReplacement = sanitizeReplacement(value)
        loaded = true
        HypnosiaClientSettings.set(REPLACEMENT_KEY, cachedReplacement)
    }

    fun decoratePlayerName(playerName: String?, original: Text): Text {
        if (!isEnabled()) return original
        val name = playerName?.takeIf { it.isNotBlank() } ?: return original
        if (level() == Level.LEVEL_1 && !isOwnName(name)) return original
        return Text.literal(replacement())
    }

    fun displayName(playerName: String): String {
        if (!isEnabled()) return playerName
        if (level() == Level.LEVEL_1 && !isOwnName(playerName)) return playerName
        return replacement()
    }

    fun shouldReplaceAnyName(): Boolean = isEnabled() && level() == Level.LEVEL_2

    fun reload() {
        loaded = false
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.boolean(ENABLED_KEY, false)
        cachedLevel = runCatching { Level.valueOf(HypnosiaClientSettings.string(LEVEL_KEY, Level.LEVEL_1.name)) }
            .getOrDefault(Level.LEVEL_1)
        cachedReplacement = sanitizeReplacement(HypnosiaClientSettings.string(REPLACEMENT_KEY, DEFAULT_REPLACEMENT))
        loaded = true
    }

    private fun isOwnName(name: String): Boolean =
        MinecraftClient.getInstance().session.username.equals(name, ignoreCase = true)

    private fun sanitizeReplacement(value: String): String =
        value.filter { !it.isISOControl() }.trim().take(32).ifBlank { DEFAULT_REPLACEMENT }
}
