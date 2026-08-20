package dev.hypnosia.hud

import dev.hypnosia.config.HypnosiaClientSettings

object TargetHudSettings {
    enum class Version {
        V1,
        V2,
        V3,
        V4,
        V5,
        V6,
    }

    data class State(
        var enabled: Boolean = true,
        var version: Version = Version.V1,
        var x: Float = 0.5f,
        var y: Float = 0.18f,
        var showEquipmentStrip: Boolean = true,
        var modelSpin: Boolean = false,
        var modelYaw: Float = -35.0f,
        var modelPitch: Float = 12.0f,
        var modelScale: Float = 42.0f,
        var modelOffsetX: Float = 0.0f,
        var modelOffsetY: Float = 0.0f,
    )

    private const val KEY_PREFIX = "target."
    private val defaultState = State()
    private val state = State()
    private var loaded = false

    fun state(): State {
        ensureLoaded()
        return state
    }

    fun isEnabled(): Boolean = state().enabled

    fun setEnabled(enabled: Boolean) {
        state().enabled = enabled
        save()
    }

    fun nextVersion() {
        val current = state()
        val entries = Version.entries
        current.version = entries[(current.version.ordinal + 1) % entries.size]
        save()
    }

    fun setX(value: Float) {
        state().x = value
        save()
    }

    fun setY(value: Float) {
        state().y = value
        save()
    }

    fun setPosition(x: Float, y: Float, persist: Boolean = true) {
        val current = state()
        current.x = x
        current.y = y
        if (persist) save()
    }

    fun toggleEquipmentStrip() {
        state().showEquipmentStrip = !state().showEquipmentStrip
        save()
    }

    fun toggleModelSpin() {
        state().modelSpin = !state().modelSpin
        save()
    }

    fun setModelYaw(value: Float, persist: Boolean = true) {
        state().modelYaw = value.coerceIn(-180.0f, 180.0f)
        if (persist) save()
    }

    fun setModelPitch(value: Float, persist: Boolean = true) {
        state().modelPitch = value.coerceIn(-90.0f, 90.0f)
        if (persist) save()
    }

    fun saveNow() {
        ensureLoaded()
        save()
    }

    fun setModelScale(value: Float) {
        state().modelScale = value.coerceIn(20.0f, 80.0f)
        save()
    }

    fun setModelOffsetX(value: Float) {
        state().modelOffsetX = value.coerceIn(-50.0f, 50.0f)
        save()
    }

    fun setModelOffsetY(value: Float) {
        state().modelOffsetY = value.coerceIn(-50.0f, 50.0f)
        save()
    }

    fun yawToSlider(value: Float): Float = ((value.coerceIn(-180.0f, 180.0f) + 180.0f) / 360.0f)

    fun sliderToYaw(value: Float): Float = value.coerceIn(0.0f, 1.0f) * 360.0f - 180.0f

    fun pitchToSlider(value: Float): Float = ((value.coerceIn(-90.0f, 90.0f) + 90.0f) / 180.0f)

    fun sliderToPitch(value: Float): Float = value.coerceIn(0.0f, 1.0f) * 180.0f - 90.0f

    fun scaleToSlider(value: Float): Float = ((value.coerceIn(20.0f, 80.0f) - 20.0f) / 60.0f)

    fun sliderToScale(value: Float): Float = 20.0f + value.coerceIn(0.0f, 1.0f) * 60.0f

    fun offsetToSlider(value: Float): Float = ((value.coerceIn(-50.0f, 50.0f) + 50.0f) / 100.0f)

    fun sliderToOffset(value: Float): Float = value.coerceIn(0.0f, 1.0f) * 100.0f - 50.0f

    fun reload() {
        loaded = false
        copyState(defaultState, state)
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            state.enabled = HypnosiaClientSettings.boolean(KEY_PREFIX + "enabled", state.enabled)
            state.version = runCatching {
                Version.valueOf(HypnosiaClientSettings.string(KEY_PREFIX + "version", state.version.name))
            }.getOrDefault(state.version)
            state.x = HypnosiaClientSettings.float(KEY_PREFIX + "x", state.x)
            state.y = HypnosiaClientSettings.float(KEY_PREFIX + "y", state.y)
            state.showEquipmentStrip = HypnosiaClientSettings.boolean(KEY_PREFIX + "showEquipmentStrip", state.showEquipmentStrip)
            state.modelSpin = false
            state.modelYaw = HypnosiaClientSettings.float(KEY_PREFIX + "modelYaw", state.modelYaw)
            state.modelPitch = HypnosiaClientSettings.float(KEY_PREFIX + "modelPitch", state.modelPitch)
            state.modelScale = HypnosiaClientSettings.float(KEY_PREFIX + "modelScale", state.modelScale)
            state.modelOffsetX = HypnosiaClientSettings.float(KEY_PREFIX + "modelOffsetX", state.modelOffsetX)
            state.modelOffsetY = HypnosiaClientSettings.float(KEY_PREFIX + "modelOffsetY", state.modelOffsetY)
        }
    }

    private fun save() {
        runCatching {
            HypnosiaClientSettings.setAll(
                mapOf(
                    KEY_PREFIX + "enabled" to state.enabled.toString(),
                    KEY_PREFIX + "version" to state.version.name,
                    KEY_PREFIX + "x" to state.x.toString(),
                    KEY_PREFIX + "y" to state.y.toString(),
                    KEY_PREFIX + "showEquipmentStrip" to state.showEquipmentStrip.toString(),
                    KEY_PREFIX + "modelSpin" to state.modelSpin.toString(),
                    KEY_PREFIX + "modelYaw" to state.modelYaw.toString(),
                    KEY_PREFIX + "modelPitch" to state.modelPitch.toString(),
                    KEY_PREFIX + "modelScale" to state.modelScale.toString(),
                    KEY_PREFIX + "modelOffsetX" to state.modelOffsetX.toString(),
                    KEY_PREFIX + "modelOffsetY" to state.modelOffsetY.toString(),
                ),
            )
        }
    }

    private fun copyState(from: State, to: State) {
        to.enabled = from.enabled
        to.version = from.version
        to.x = from.x
        to.y = from.y
        to.showEquipmentStrip = from.showEquipmentStrip
        to.modelSpin = from.modelSpin
        to.modelYaw = from.modelYaw
        to.modelPitch = from.modelPitch
        to.modelScale = from.modelScale
        to.modelOffsetX = from.modelOffsetX
        to.modelOffsetY = from.modelOffsetY
    }
}
