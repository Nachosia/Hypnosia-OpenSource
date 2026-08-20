package dev.hypnosia.hud

import dev.hypnosia.config.HypnosiaClientSettings

object WatermarkSettings {
    enum class Version {
        V1,
        V2,
    }

    enum class Module(val key: String, val label: String) {
        VISUAL_ICON("visual_icon", "Visuals"),
        ROLE("role", "Role"),
        NICK("nick", "Nick"),
        FPS("fps", "FPS"),
        SERVER("server", "Server"),
        PING("ping", "Ping"),
        RAM("ram", "RAM"),
        CPU("cpu", "CPU"),
    }

    private const val KEY_PREFIX = "watermark."
    private const val VERSION_KEY = "version"
    private val values = linkedMapOf<Module, Boolean>().apply {
        Module.entries.forEach { put(it, true) }
    }
    private var version = Version.V2
    private var loaded = false

    fun version(): Version {
        ensureLoaded()
        return version
    }

    fun setVersion(value: Version) {
        ensureLoaded()
        version = value
        save()
    }

    fun toggleVersion() {
        setVersion(if (version() == Version.V1) Version.V2 else Version.V1)
    }

    fun isEnabled(module: Module): Boolean {
        ensureLoaded()
        return values[module] == true
    }

    fun setEnabled(module: Module, enabled: Boolean) {
        ensureLoaded()
        values[module] = enabled
        save()
    }

    fun toggle(module: Module) {
        setEnabled(module, !isEnabled(module))
    }

    fun reload() {
        loaded = false
        version = Version.V2
        Module.entries.forEach { values[it] = true }
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            version = runCatching {
                Version.valueOf(HypnosiaClientSettings.string(KEY_PREFIX + VERSION_KEY, Version.V2.name))
            }.getOrDefault(Version.V2)
            Module.entries.forEach { module ->
                values[module] = HypnosiaClientSettings.boolean(KEY_PREFIX + module.key, true)
            }
        }
    }

    private fun save() {
        runCatching {
            val valuesToSave = linkedMapOf<String, String>()
            valuesToSave[KEY_PREFIX + VERSION_KEY] = version.name
            Module.entries.forEach { module ->
                valuesToSave[KEY_PREFIX + module.key] = (values[module] == true).toString()
            }
            HypnosiaClientSettings.setAll(valuesToSave)
        }
    }
}
