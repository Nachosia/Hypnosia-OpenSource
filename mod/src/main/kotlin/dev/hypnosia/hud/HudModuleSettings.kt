package dev.hypnosia.hud

import dev.hypnosia.config.HypnosiaClientSettings

object HudModuleSettings {
    enum class Module(val key: String) {
        HOTBAR("hotbar"),
        ARMOR("armor"),
        PLAYER_INFO("player_info"),
        INVENTORY("inventory"),
        COOLDOWNS("cooldowns"),
        POTIONS("potions"),
        HOTKEYS("hotkeys"),
        NOW_PLAYING("now_playing"),
    }

    enum class Version {
        V1,
        V2,
        V3,
        V4,
    }

    enum class PlayerInfoMode {
        BPS,
        TPS,
        CORDS,
        ALL,
    }

    enum class PlayerInfoPart {
        BPS,
        TPS,
        CORDS,
    }

    enum class Axis {
        X,
        Y,
    }

    data class State(
        var enabled: Boolean,
        var version: Version,
        var axis: Axis,
        var x: Float,
        var y: Float,
        var slotHighlight: Boolean,
        var playerInfoMode: PlayerInfoMode = PlayerInfoMode.ALL,
        var playerInfoBps: Boolean = true,
        var playerInfoTps: Boolean = true,
        var playerInfoCords: Boolean = true,
        var playerInfoBpsX: Float = 0.02f,
        var playerInfoBpsY: Float = 0.18f,
        var playerInfoTpsX: Float = 0.075f,
        var playerInfoTpsY: Float = 0.18f,
        var playerInfoCordsX: Float = 0.13f,
        var playerInfoCordsY: Float = 0.18f,
    )

    private const val KEY_PREFIX = "hud."
    private val defaultStates = linkedMapOf(
        Module.HOTBAR to State(enabled = true, version = Version.V1, axis = Axis.X, x = 0.5f, y = 0.94f, slotHighlight = false),
        Module.ARMOR to State(enabled = true, version = Version.V1, axis = Axis.X, x = 0.84f, y = 0.86f, slotHighlight = false),
        Module.PLAYER_INFO to State(enabled = false, version = Version.V1, axis = Axis.X, x = 0.02f, y = 0.18f, slotHighlight = false, playerInfoMode = PlayerInfoMode.ALL),
        Module.INVENTORY to State(enabled = false, version = Version.V1, axis = Axis.X, x = 0.02f, y = 0.27f, slotHighlight = false),
        Module.COOLDOWNS to State(enabled = false, version = Version.V1, axis = Axis.X, x = 0.02f, y = 0.39f, slotHighlight = false),
        Module.POTIONS to State(enabled = false, version = Version.V1, axis = Axis.X, x = 0.02f, y = 0.51f, slotHighlight = false),
        Module.HOTKEYS to State(enabled = false, version = Version.V1, axis = Axis.X, x = 0.02f, y = 0.63f, slotHighlight = false),
        Module.NOW_PLAYING to State(enabled = false, version = Version.V1, axis = Axis.X, x = 0.01f, y = 0.82f, slotHighlight = false),
    )
    private val states = linkedMapOf<Module, State>().apply {
        defaultStates.forEach { (module, state) -> put(module, state.copy()) }
    }
    private var loaded = false

    fun state(module: Module): State {
        ensureLoaded()
        return states.getValue(module)
    }

    fun isEnabled(module: Module): Boolean = state(module).enabled

    fun setEnabled(module: Module, enabled: Boolean) {
        state(module).enabled = enabled
        save()
    }

    fun toggleVersion(module: Module) {
        toggleVersion(module, maxVersion = Version.V2)
    }

    fun toggleVersion(module: Module, maxVersion: Version) {
        val state = state(module)
        state.version = if (state.version.ordinal >= maxVersion.ordinal) {
            Version.V1
        } else {
            Version.entries[state.version.ordinal + 1]
        }
        save()
    }

    fun togglePlayerInfoMode() {
        val state = state(Module.PLAYER_INFO)
        state.playerInfoMode = PlayerInfoMode.entries[(state.playerInfoMode.ordinal + 1) % PlayerInfoMode.entries.size]
        save()
    }

    fun togglePlayerInfoPart(part: PlayerInfoPart) {
        val state = state(Module.PLAYER_INFO)
        when (part) {
            PlayerInfoPart.BPS -> state.playerInfoBps = !state.playerInfoBps
            PlayerInfoPart.TPS -> state.playerInfoTps = !state.playerInfoTps
            PlayerInfoPart.CORDS -> state.playerInfoCords = !state.playerInfoCords
        }
        save()
    }

    fun setPlayerInfoPartPosition(part: PlayerInfoPart, x: Float, y: Float, persist: Boolean = true) {
        val state = state(Module.PLAYER_INFO)
        when (part) {
            PlayerInfoPart.BPS -> {
                state.playerInfoBpsX = x
                state.playerInfoBpsY = y
            }
            PlayerInfoPart.TPS -> {
                state.playerInfoTpsX = x
                state.playerInfoTpsY = y
            }
            PlayerInfoPart.CORDS -> {
                state.playerInfoCordsX = x
                state.playerInfoCordsY = y
            }
        }
        if (persist) save()
    }

    fun toggleAxis(module: Module) {
        val state = state(module)
        state.axis = if (state.axis == Axis.X) Axis.Y else Axis.X
        save()
    }

    fun setX(module: Module, value: Float) {
        val state = state(module)
        if (module == Module.PLAYER_INFO) {
            val delta = value - state.x
            state.playerInfoBpsX = (state.playerInfoBpsX + delta).coerceIn(0.0f, 1.0f)
            state.playerInfoTpsX = (state.playerInfoTpsX + delta).coerceIn(0.0f, 1.0f)
            state.playerInfoCordsX = (state.playerInfoCordsX + delta).coerceIn(0.0f, 1.0f)
        }
        state.x = value
        save()
    }

    fun setY(module: Module, value: Float) {
        val state = state(module)
        if (module == Module.PLAYER_INFO) {
            val delta = value - state.y
            state.playerInfoBpsY = (state.playerInfoBpsY + delta).coerceIn(0.0f, 1.0f)
            state.playerInfoTpsY = (state.playerInfoTpsY + delta).coerceIn(0.0f, 1.0f)
            state.playerInfoCordsY = (state.playerInfoCordsY + delta).coerceIn(0.0f, 1.0f)
        }
        state.y = value
        save()
    }

    fun setPosition(module: Module, x: Float, y: Float, persist: Boolean = true) {
        val state = state(module)
        state.x = x
        state.y = y
        if (persist) save()
    }

    fun saveNow() {
        ensureLoaded()
        save()
    }

    fun toggleSlotHighlight(module: Module) {
        val state = state(module)
        state.slotHighlight = !state.slotHighlight
        save()
    }

    fun reload() {
        loaded = false
        states.clear()
        defaultStates.forEach { (module, state) -> states[module] = state.copy() }
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            Module.entries.forEach { module ->
                val state = states.getValue(module)
                val prefix = KEY_PREFIX + module.key
                state.enabled = HypnosiaClientSettings.string("$prefix.enabled", state.enabled.toString())
                    .toBooleanStrictOrNull() ?: state.enabled
                state.version = runCatching {
                    Version.valueOf(HypnosiaClientSettings.string("$prefix.version", state.version.name))
                }.getOrDefault(state.version)
                state.axis = runCatching {
                    Axis.valueOf(HypnosiaClientSettings.string("$prefix.axis", state.axis.name))
                }.getOrDefault(state.axis)
                state.x = HypnosiaClientSettings.float("$prefix.x", state.x)
                state.y = HypnosiaClientSettings.float("$prefix.y", state.y)
                state.slotHighlight = HypnosiaClientSettings.string("$prefix.slotHighlight", state.slotHighlight.toString())
                    .toBooleanStrictOrNull() ?: state.slotHighlight
                state.playerInfoMode = runCatching {
                    PlayerInfoMode.valueOf(HypnosiaClientSettings.string("$prefix.playerInfoMode", state.playerInfoMode.name))
                }.getOrDefault(state.playerInfoMode)
                state.playerInfoBps = HypnosiaClientSettings.string("$prefix.playerInfoBps", state.playerInfoBps.toString())
                    .toBooleanStrictOrNull() ?: state.playerInfoBps
                state.playerInfoTps = HypnosiaClientSettings.string("$prefix.playerInfoTps", state.playerInfoTps.toString())
                    .toBooleanStrictOrNull() ?: state.playerInfoTps
                state.playerInfoCords = HypnosiaClientSettings.string("$prefix.playerInfoCords", state.playerInfoCords.toString())
                    .toBooleanStrictOrNull() ?: state.playerInfoCords
                state.playerInfoBpsX = HypnosiaClientSettings.float("$prefix.playerInfoBpsX", state.playerInfoBpsX)
                state.playerInfoBpsY = HypnosiaClientSettings.float("$prefix.playerInfoBpsY", state.playerInfoBpsY)
                state.playerInfoTpsX = HypnosiaClientSettings.float("$prefix.playerInfoTpsX", state.playerInfoTpsX)
                state.playerInfoTpsY = HypnosiaClientSettings.float("$prefix.playerInfoTpsY", state.playerInfoTpsY)
                state.playerInfoCordsX = HypnosiaClientSettings.float("$prefix.playerInfoCordsX", state.playerInfoCordsX)
                state.playerInfoCordsY = HypnosiaClientSettings.float("$prefix.playerInfoCordsY", state.playerInfoCordsY)
            }
        }
    }

    private fun save() {
        runCatching {
            val values = linkedMapOf<String, String>()
            states.forEach { (module, state) ->
                val prefix = KEY_PREFIX + module.key
                values["$prefix.enabled"] = state.enabled.toString()
                values["$prefix.version"] = state.version.name
                values["$prefix.axis"] = state.axis.name
                values["$prefix.x"] = state.x.toString()
                values["$prefix.y"] = state.y.toString()
                values["$prefix.slotHighlight"] = state.slotHighlight.toString()
                values["$prefix.playerInfoMode"] = state.playerInfoMode.name
                values["$prefix.playerInfoBps"] = state.playerInfoBps.toString()
                values["$prefix.playerInfoTps"] = state.playerInfoTps.toString()
                values["$prefix.playerInfoCords"] = state.playerInfoCords.toString()
                values["$prefix.playerInfoBpsX"] = state.playerInfoBpsX.toString()
                values["$prefix.playerInfoBpsY"] = state.playerInfoBpsY.toString()
                values["$prefix.playerInfoTpsX"] = state.playerInfoTpsX.toString()
                values["$prefix.playerInfoTpsY"] = state.playerInfoTpsY.toString()
                values["$prefix.playerInfoCordsX"] = state.playerInfoCordsX.toString()
                values["$prefix.playerInfoCordsY"] = state.playerInfoCordsY.toString()
            }
            HypnosiaClientSettings.setAll(values)
        }
    }
}
