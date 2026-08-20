package dev.hypnosia.visual

import dev.hypnosia.config.HypnosiaClientSettings

object AspectRatioSettings {
    enum class Mode(val label: String) {
        RATIO_4_3("4:3"),
        RATIO_16_9("16:9"),
        FREE("Free"),
    }

    private const val ENABLED_KEY = "module.visuals.aspect_ratio.enabled"
    private const val MODE_KEY = "visuals.aspect_ratio.mode"
    private const val FREE_KEY = "visuals.aspect_ratio.free"
    private const val DEFAULT_FREE = 16.0f / 9.0f
    private const val MIN_FREE = 0.75f
    private const val MAX_FREE = 2.40f

    private var loaded = false
    private var cachedEnabled = false
    private var cachedMode = Mode.RATIO_4_3
    private var cachedFree = DEFAULT_FREE

    fun isEnabled(): Boolean {
        ensureLoaded()
        return cachedEnabled
    }

    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        loaded = true
        HypnosiaClientSettings.set(ENABLED_KEY, enabled.toString())
    }

    fun mode(): Mode {
        ensureLoaded()
        return cachedMode
    }

    fun cycleMode() {
        val entries = Mode.entries
        cachedMode = entries[(mode().ordinal + 1) % entries.size]
        loaded = true
        HypnosiaClientSettings.set(MODE_KEY, cachedMode.name)
    }

    fun aspect(): Float {
        ensureLoaded()
        return when (cachedMode) {
            Mode.RATIO_4_3 -> 4.0f / 3.0f
            Mode.RATIO_16_9 -> 16.0f / 9.0f
            Mode.FREE -> cachedFree
        }
    }

    fun freeValue(): Float {
        ensureLoaded()
        return cachedFree
    }

    fun freeSlider(): Float = ((freeValue() - MIN_FREE) / (MAX_FREE - MIN_FREE)).coerceIn(0.0f, 1.0f)

    fun setFreeFromSlider(value: Float) {
        cachedFree = (MIN_FREE + (MAX_FREE - MIN_FREE) * value.coerceIn(0.0f, 1.0f)).coerceIn(MIN_FREE, MAX_FREE)
        cachedMode = Mode.FREE
        loaded = true
        HypnosiaClientSettings.setAll(
            mapOf(
                MODE_KEY to Mode.FREE.name,
                FREE_KEY to cachedFree.toString(),
            ),
        )
    }

    fun reload() {
        loaded = false
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        cachedEnabled = HypnosiaClientSettings.boolean(ENABLED_KEY, false)
        cachedMode = runCatching { Mode.valueOf(HypnosiaClientSettings.string(MODE_KEY, Mode.RATIO_4_3.name)) }
            .getOrDefault(Mode.RATIO_4_3)
        cachedFree = HypnosiaClientSettings.float(FREE_KEY, DEFAULT_FREE).coerceIn(MIN_FREE, MAX_FREE)
        loaded = true
    }
}
