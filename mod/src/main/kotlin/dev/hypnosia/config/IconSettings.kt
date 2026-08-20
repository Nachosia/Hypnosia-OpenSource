package dev.hypnosia.config

import net.minecraft.util.Identifier

object IconSettings {
    private const val MODULE_KEY = "module.client.icons.enabled"
    private const val BLACK_HOLE_KEY = "icons.blackHole.visible"
    private const val COLOR_KEY = "icons.color"

    private const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()

    val enabled: Boolean
        get() = HypnosiaClientSettings.boolean(MODULE_KEY, true)

    val blackHoleVisible: Boolean
        get() = HypnosiaClientSettings.boolean(BLACK_HOLE_KEY, true)

    val color: Int
        get() = parseColor(HypnosiaClientSettings.string(COLOR_KEY, "#FFFFFF")) ?: DEFAULT_COLOR

    fun setBlackHoleVisible(value: Boolean) {
        HypnosiaClientSettings.set(BLACK_HOLE_KEY, value.toString())
    }

    fun toggleBlackHoleVisible() {
        setBlackHoleVisible(!blackHoleVisible)
    }

    fun setColor(argb: Int) {
        val alpha = (argb ushr 24) and 0xFF
        HypnosiaClientSettings.set(
            COLOR_KEY,
            if (alpha == 0xFF) "#%06X".format(argb and 0x00FFFFFF) else "#%08X".format(argb),
        )
    }

    fun colorHex(): String = "#%06X".format(color and 0x00FFFFFF)

    fun shouldSkip(identifier: Identifier): Boolean {
        if (!enabled || blackHoleVisible || !isGuiIcon(identifier)) return false
        return identifier.path.substringAfterLast('/').equals("black_hole.png", ignoreCase = true)
    }

    fun tint(identifier: Identifier, inputColor: Int): Int {
        if (!enabled || !isGuiIcon(identifier)) return inputColor
        val inputAlpha = (inputColor ushr 24) and 0xFF
        val colorAlpha = (color ushr 24) and 0xFF
        val alpha = inputAlpha * colorAlpha / 255
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun isGuiIcon(identifier: Identifier): Boolean =
        identifier.path.startsWith("textures/gui/icons/")

    private fun parseColor(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        if ((hex.length != 6 && hex.length != 8) || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val value = hex.toLong(16)
        return if (hex.length == 6) {
            (0xFF000000.toInt() or value.toInt())
        } else {
            value.toInt()
        }
    }
}
