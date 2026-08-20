package dev.hypnosia.config

import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.GlassSurfaceTokens
import kotlin.math.roundToInt

object ThemeSettings {
    // Theme UI is temporarily locked out. Keep the implementation in place so it can be
    // restored later, but ignore any saved config values while this flag is enabled.
    private const val THEME_SETTINGS_LOCKED = true
    private const val MODULE_KEY = "module.client.theme.enabled"
    private const val MODE_KEY = "theme.mode"
    private const val FONT_KEY = "theme.font"
    private const val LIQUID_GLASS_KEY = "theme.liquidGlass"
    private const val GRADIENT_KEY = "theme.gradient"
    private const val BASE_COLOR_KEY = "theme.baseColor"
    private const val GRADIENT_START_KEY = "theme.gradientStart"
    private const val GRADIENT_END_KEY = "theme.gradientEnd"

    enum class Mode(val label: String) {
        DARK("Dark"),
        WHITE("White"),
        TRANSPARENT("Transparent"),
        LIQUID_GLASS("Liquid Glass"),
        CUSTOM("Custom"),
    }

    enum class FontMode(val label: String) {
        INTER("Inter"),
        SATYR("Satyr"),
        CUSTOM_FILE("Custom"),
    }

    enum class ThemeRole {
        NONE,
        MAIN_PANEL,
        CARD,
        HEADER,
        BUTTON,
        INPUT,
        DRAWER,
        ICON_BUTTON,
    }

    data class BoxTheme(
        val bgColor: Int,
        val strokeColor: Int,
        val gradientStart: Int = bgColor,
        val gradientEnd: Int = bgColor,
        val useGradient: Boolean = false,
        val liquidGlass: Boolean = false,
        val glassStrength: Float = 0.0f,
    )

    data class LiquidGlassTokens(
        val panelFill: Int = 0xFF2A2A2E.toInt(),
        val cardFill: Int = 0xFF525052.toInt(),
        val rowFill: Int = 0xFF464448.toInt(),
        val border: Int = GlassSurfaceTokens.glassBorder,
        val borderStrong: Int = GlassSurfaceTokens.glassBorderStrong,
        val innerHighlight: Int = GlassSurfaceTokens.glassInnerHighlight,
        val shadow: Int = GlassSurfaceTokens.glassShadow,
        val accentBlue: Int = 0xFFAFCBFF.toInt(),
        val accentPurple: Int = 0xFFD8B4FE.toInt(),
        val accentPink: Int = 0xFFFF4FA3.toInt(),
        val textPrimary: Int = GlassSurfaceTokens.textPrimary,
        val textSecondary: Int = GlassSurfaceTokens.textSecondary,
        val textMuted: Int = GlassSurfaceTokens.textMuted,
    )

    val liquidTokens = LiquidGlassTokens()

    private var themedUiDepth = 0

    fun <T> themedUi(block: () -> T): T {
        themedUiDepth += 1
        return try {
            block()
        } finally {
            themedUiDepth -= 1
        }
    }

    val enabled: Boolean
        get() = !THEME_SETTINGS_LOCKED && HypnosiaClientSettings.boolean(MODULE_KEY, true)

    fun mode(): Mode =
        if (THEME_SETTINGS_LOCKED) Mode.DARK else
        enumValue(HypnosiaClientSettings.string(MODE_KEY, Mode.DARK.name), Mode.DARK)

    fun fontMode(): FontMode =
        if (THEME_SETTINGS_LOCKED) FontMode.INTER else
        enumValue(HypnosiaClientSettings.string(FONT_KEY, FontMode.INTER.name), FontMode.INTER)

    fun liquidGlass(): Boolean =
        if (THEME_SETTINGS_LOCKED) false else
        HypnosiaClientSettings.boolean(LIQUID_GLASS_KEY, false)

    fun glassActive(): Boolean =
        !THEME_SETTINGS_LOCKED && (mode() == Mode.LIQUID_GLASS || liquidGlass())

    fun gradient(): Boolean =
        if (THEME_SETTINGS_LOCKED) false else
        HypnosiaClientSettings.boolean(GRADIENT_KEY, false)

    fun baseColor(): Int =
        if (THEME_SETTINGS_LOCKED) 0xFF0D0D0D.toInt() else
        parseColor(HypnosiaClientSettings.string(BASE_COLOR_KEY, "#0D0D0D")) ?: 0xFF0D0D0D.toInt()

    fun gradientStart(): Int =
        if (THEME_SETTINGS_LOCKED) 0xFF0D0D0D.toInt() else
        parseColor(HypnosiaClientSettings.string(GRADIENT_START_KEY, "#0D0D0D")) ?: 0xFF0D0D0D.toInt()

    fun gradientEnd(): Int =
        if (THEME_SETTINGS_LOCKED) 0xFF151515.toInt() else
        parseColor(HypnosiaClientSettings.string(GRADIENT_END_KEY, "#151515")) ?: 0xFF151515.toInt()

    fun cycleMode() {
        if (THEME_SETTINGS_LOCKED) {
            writeLockedDefaults()
            return
        }
        val values = Mode.entries
        HypnosiaClientSettings.set(MODE_KEY, values[(mode().ordinal + 1) % values.size].name)
    }

    fun cycleFont() {
        if (THEME_SETTINGS_LOCKED) {
            writeLockedDefaults()
            return
        }
        val values = FontMode.entries
        HypnosiaClientSettings.set(FONT_KEY, values[(fontMode().ordinal + 1) % values.size].name)
    }

    fun toggleLiquidGlass() {
        if (THEME_SETTINGS_LOCKED) {
            writeLockedDefaults()
            return
        }
        HypnosiaClientSettings.set(LIQUID_GLASS_KEY, (!liquidGlass()).toString())
    }

    fun toggleGradient() {
        if (THEME_SETTINGS_LOCKED) {
            writeLockedDefaults()
            return
        }
        HypnosiaClientSettings.set(GRADIENT_KEY, (!gradient()).toString())
    }

    fun setBaseColor(color: Int) {
        if (THEME_SETTINGS_LOCKED) {
            writeLockedDefaults()
            return
        }
        HypnosiaClientSettings.set(BASE_COLOR_KEY, colorHex(color))
    }

    fun setGradientStart(color: Int) {
        if (THEME_SETTINGS_LOCKED) {
            writeLockedDefaults()
            return
        }
        HypnosiaClientSettings.set(GRADIENT_START_KEY, colorHex(color))
    }

    fun setGradientEnd(color: Int) {
        if (THEME_SETTINGS_LOCKED) {
            writeLockedDefaults()
            return
        }
        HypnosiaClientSettings.set(GRADIENT_END_KEY, colorHex(color))
    }

    fun writeLockedDefaults() {
        HypnosiaClientSettings.setAll(
            mapOf(
                MODULE_KEY to "false",
                MODE_KEY to Mode.DARK.name,
                FONT_KEY to FontMode.INTER.name,
                LIQUID_GLASS_KEY to "false",
                GRADIENT_KEY to "false",
                BASE_COLOR_KEY to "#0D0D0D",
                GRADIENT_START_KEY to "#0D0D0D",
                GRADIENT_END_KEY to "#151515",
            ),
        )
    }

    fun colorHex(color: Int): String = "#%06X".format(color and 0x00FFFFFF)

    fun customFontPathHint(): String = "hypnosia/fonts/custom.ttf"

    fun resolveFont(font: FigmaTextRenderer.Font): FigmaTextRenderer.Font {
        if (!enabled) return font
        return when (fontMode()) {
            FontMode.INTER -> font
            FontMode.SATYR -> font
            FontMode.CUSTOM_FILE -> if (font == FigmaTextRenderer.Font.Main) FigmaTextRenderer.Font.Custom else font
        }
    }

    fun resolveTextColor(color: Int): Int {
        if (!enabled || themedUiDepth <= 0) return color
        if (mode() == Mode.LIQUID_GLASS) {
            val rgb = color and 0x00FFFFFF
            val alpha = color and 0xFF000000.toInt()
            return when {
                rgb == 0xFFFFFF || rgb == 0xF2F2F2 || rgb == 0xEDEDF1 || rgb == 0xE7E7EA -> setAlpha(liquidTokens.textPrimary, (alpha ushr 24) and 0xFF)
                rgb == 0xD6D6DE || rgb == 0xBFC0CA || rgb == 0xB8B8C2 || rgb == 0x8E8E98 || rgb == 0x8E8E8E || rgb == 0x929292 -> setAlpha(liquidTokens.textSecondary, (alpha ushr 24) and 0xFF)
                rgb == 0xFF2F86 || rgb == 0xD8B4FE || rgb == 0xAFCBFF -> setAlpha(GlassSurfaceTokens.accentActive, (alpha ushr 24) and 0xFF)
                else -> color
            }
        }
        if (mode() != Mode.WHITE) return color
        val rgb = color and 0x00FFFFFF
        val alpha = color and 0xFF000000.toInt()
        return when {
            rgb == 0xFFFFFF || rgb == 0xF2F2F2 || rgb == 0xEDEDF1 || rgb == 0xE7E7EA -> alpha or 0x151518
            rgb == 0xD6D6DE || rgb == 0xBFC0CA || rgb == 0xB8B8C2 -> alpha or 0x3A3A42
            rgb == 0x8E8E98 || rgb == 0x8E8E8E || rgb == 0x929292 -> alpha or 0x666872
            else -> color
        }
    }

    fun resolveIconTint(identifierPath: String, color: Int): Int {
        if (!enabled || themedUiDepth <= 0) return color
        if (identifierPath.endsWith("black_hole.png", ignoreCase = true)) return color
        if (mode() == Mode.LIQUID_GLASS) {
            val rgb = color and 0x00FFFFFF
            val alpha = color and 0xFF000000.toInt()
            return when {
                rgb == 0xFFFFFF || rgb == 0xF2F2F2 || rgb == 0xEDEDF1 || rgb == 0xE7E7EA -> setAlpha(liquidTokens.textPrimary, (alpha ushr 24) and 0xFF)
                rgb == 0x8E8E98 || rgb == 0x8E8E8E || rgb == 0x929292 -> setAlpha(liquidTokens.textSecondary, (alpha ushr 24) and 0xFF)
                else -> color
            }
        }
        if (mode() != Mode.WHITE) return color

        val rgb = color and 0x00FFFFFF
        val alpha = color and 0xFF000000.toInt()
        return when {
            rgb == 0xFFFFFF || rgb == 0xF2F2F2 || rgb == 0xEDEDF1 || rgb == 0xE7E7EA -> alpha or 0x111114
            rgb == 0xD6D6DE || rgb == 0xBFC0CA || rgb == 0xB8B8C2 -> alpha or 0x303038
            else -> color
        }
    }

    fun surface(role: ThemeRole, baseColor: Int, baseStroke: Int): BoxTheme? {
        if (!enabled || role == ThemeRole.NONE) return null
        if (mode() == Mode.DARK && !gradient() && !glassActive()) return null

        val baseAlpha = (baseColor ushr 24) and 0xFF
        val strokeAlpha = ((baseStroke ushr 24) and 0xFF).takeIf { it > 0 } ?: baseAlpha
        val glass = glassActive()
        val palette = paletteFor(role, baseAlpha, strokeAlpha, glass)
        return if (palette.useGradient || palette.liquidGlass || palette.bgColor != baseColor || palette.strokeColor != baseStroke) {
            palette
        } else {
            null
        }
    }

    fun resolveBox(bgColor: Int, strokeColor: Int): BoxTheme? {
        return null
    }

    private fun paletteFor(role: ThemeRole, baseAlpha: Int, strokeAlpha: Int, glass: Boolean): BoxTheme {
        val alpha = roleAlpha(role, baseAlpha)
        return when (mode()) {
            Mode.DARK -> {
                val bg = darkSurface(role, alpha)
                BoxTheme(
                    bgColor = bg,
                    strokeColor = withAlpha(darkStroke(role), strokeAlpha),
                    gradientStart = withAlpha(lighten(bg, 0.10f), alpha),
                    gradientEnd = withAlpha(darken(bg, 0.06f), alpha),
                    useGradient = gradient() && !glass,
                    liquidGlass = glass,
                    glassStrength = if (glass) 0.72f else 0.0f,
                )
            }
            Mode.WHITE -> {
                val bg = whiteSurface(role, alpha)
                BoxTheme(
                    bgColor = bg,
                    strokeColor = withAlpha(whiteStroke(role), strokeAlpha),
                    gradientStart = withAlpha(0xFFFDFDFE.toInt(), alpha),
                    gradientEnd = withAlpha(0xFFE6E8EE.toInt(), alpha),
                    useGradient = gradient() && !glass,
                    liquidGlass = glass,
                    glassStrength = if (glass) 0.58f else 0.0f,
                )
            }
            Mode.TRANSPARENT -> {
                val bg = setAlpha(transparentSurface(role), transparentAlpha(role))
                BoxTheme(
                    bgColor = bg,
                    strokeColor = setAlpha(transparentStroke(role), transparentStrokeAlpha(role)),
                    gradientStart = setAlpha(0xFF566176.toInt(), 0x42),
                    gradientEnd = setAlpha(0xFF080B12.toInt(), 0x22),
                    useGradient = false,
                    // Transparent is a frosted-backdrop theme: the surface stays translucent,
                    // while text/icons are drawn later at full opacity.
                    liquidGlass = true,
                    glassStrength = transparentGlassStrength(role),
                )
            }
            Mode.LIQUID_GLASS -> {
                val alpha = liquidGlassAlpha(role)
                val base = when (role) {
                    ThemeRole.MAIN_PANEL, ThemeRole.DRAWER -> liquidTokens.panelFill
                    ThemeRole.CARD -> liquidTokens.cardFill
                    ThemeRole.HEADER, ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> liquidTokens.rowFill
                    ThemeRole.NONE -> liquidTokens.panelFill
                }
                BoxTheme(
                    bgColor = setAlpha(base, alpha),
                    strokeColor = liquidGlassStroke(role),
                    gradientStart = setAlpha(0xFF2B3344.toInt(), alpha),
                    gradientEnd = setAlpha(0xFF07080D.toInt(), alpha),
                    useGradient = false,
                    liquidGlass = true,
                    glassStrength = liquidGlassStrength(role),
                )
            }
            Mode.CUSTOM -> BoxTheme(
                bgColor = setAlpha(baseColor(), if (glass) 0xB8 else alpha),
                strokeColor = setAlpha(lighten(baseColor(), 0.28f), if (glass) 0xA8 else strokeAlpha),
                gradientStart = setAlpha(gradientStart(), if (glass) 0xE4 else alpha),
                gradientEnd = setAlpha(gradientEnd(), if (glass) 0xD0 else alpha),
                useGradient = gradient() && !glass,
                liquidGlass = glass,
                glassStrength = if (glass) 0.82f else 0.0f,
            )
        }
    }

    private fun roleAlpha(role: ThemeRole, baseAlpha: Int): Int = when (role) {
        ThemeRole.INPUT -> baseAlpha.coerceAtMost(0xF2)
        ThemeRole.ICON_BUTTON -> baseAlpha.coerceAtMost(0xF6)
        else -> baseAlpha
    }

    private fun transparentAlpha(role: ThemeRole): Int = when (role) {
        ThemeRole.MAIN_PANEL -> 0x3C
        ThemeRole.DRAWER -> 0x46
        ThemeRole.CARD -> 0x34
        ThemeRole.HEADER -> 0x38
        ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0x42
        ThemeRole.NONE -> 0xFF
    }

    private fun transparentStrokeAlpha(role: ThemeRole): Int = when (role) {
        ThemeRole.MAIN_PANEL, ThemeRole.DRAWER -> 0x58
        ThemeRole.CARD -> 0x46
        ThemeRole.HEADER -> 0x4C
        ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0x55
        ThemeRole.NONE -> 0x00
    }

    private fun transparentGlassStrength(role: ThemeRole): Float = when (role) {
        ThemeRole.MAIN_PANEL -> 0.78f
        ThemeRole.DRAWER -> 0.78f
        ThemeRole.CARD -> 0.66f
        ThemeRole.HEADER -> 0.70f
        ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0.72f
        ThemeRole.NONE -> 0.0f
    }

    private fun transparentSurface(role: ThemeRole): Int = when (role) {
        ThemeRole.MAIN_PANEL -> 0xFF1E2532.toInt()
        ThemeRole.DRAWER -> 0xFF202838.toInt()
        ThemeRole.CARD -> 0xFF222936.toInt()
        ThemeRole.HEADER -> 0xFF243044.toInt()
        ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0xFF1F2A3E.toInt()
        ThemeRole.NONE -> 0xFF0D0D0D.toInt()
    }

    private fun transparentStroke(role: ThemeRole): Int = when (role) {
        ThemeRole.MAIN_PANEL, ThemeRole.DRAWER -> 0xFF73809A.toInt()
        ThemeRole.CARD -> 0xFF6B7891.toInt()
        ThemeRole.HEADER -> 0xFF7F8DA8.toInt()
        ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0xFF7F8DA8.toInt()
        ThemeRole.NONE -> 0x00000000
    }

    private fun liquidGlassAlpha(role: ThemeRole): Int = when (role) {
        ThemeRole.MAIN_PANEL -> 0x86
        ThemeRole.DRAWER -> 0x8A
        ThemeRole.CARD -> 0x64
        ThemeRole.HEADER -> 0x72
        ThemeRole.BUTTON -> 0x70
        ThemeRole.INPUT -> 0x6C
        ThemeRole.ICON_BUTTON -> 0x70
        ThemeRole.NONE -> 0xFF
    }

    private fun liquidGlassStrength(role: ThemeRole): Float = when (role) {
        ThemeRole.MAIN_PANEL -> 1.0f
        ThemeRole.DRAWER -> 1.0f
        ThemeRole.CARD -> 0.92f
        ThemeRole.HEADER -> 0.88f
        ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0.90f
        ThemeRole.NONE -> 0.0f
    }

    private fun liquidGlassStroke(role: ThemeRole): Int = when (role) {
        ThemeRole.MAIN_PANEL, ThemeRole.DRAWER -> liquidTokens.borderStrong
        ThemeRole.CARD -> liquidTokens.border
        ThemeRole.HEADER -> 0x30FFFFFF
        ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0x3DFFFFFF
        ThemeRole.NONE -> 0x00000000
    }

    private fun darkSurface(role: ThemeRole, alpha: Int): Int = withAlpha(
        when (role) {
            ThemeRole.HEADER -> 0xFF191919.toInt()
            ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0xFF0D0D0D.toInt()
            ThemeRole.CARD -> 0xFF111114.toInt()
            else -> 0xFF0D0D0D.toInt()
        },
        alpha,
    )

    private fun darkStroke(role: ThemeRole): Int = when (role) {
        ThemeRole.ICON_BUTTON -> 0xFF34343C.toInt()
        else -> 0xFF272727.toInt()
    }

    private fun whiteSurface(role: ThemeRole, alpha: Int): Int = withAlpha(
        when (role) {
            ThemeRole.MAIN_PANEL -> 0xFFF1F2F5.toInt()
            ThemeRole.DRAWER -> 0xFFF5F6F9.toInt()
            ThemeRole.CARD -> 0xFFF9FAFC.toInt()
            ThemeRole.HEADER -> 0xFFE8EAF0.toInt()
            ThemeRole.BUTTON, ThemeRole.INPUT, ThemeRole.ICON_BUTTON -> 0xFFF6F7FA.toInt()
            ThemeRole.NONE -> 0xFF0D0D0D.toInt()
        },
        alpha,
    )

    private fun whiteStroke(role: ThemeRole): Int = when (role) {
        ThemeRole.MAIN_PANEL, ThemeRole.DRAWER -> 0xFFBFC4CE.toInt()
        ThemeRole.HEADER -> 0xFFD1D5DE.toInt()
        else -> 0xFFC4C8D2.toInt()
    }

    private fun setAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun lighten(color: Int, amount: Float): Int = mix(color, 0xFFFFFFFF.toInt(), amount)

    private fun darken(color: Int, amount: Float): Int = mix(color, 0xFF000000.toInt(), amount)

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0.0f, 1.0f)
        fun ch(color: Int, shift: Int) = (color ushr shift) and 0xFF
        val r = (ch(a, 16) + (ch(b, 16) - ch(a, 16)) * t).roundToInt()
        val g = (ch(a, 8) + (ch(b, 8) - ch(a, 8)) * t).roundToInt()
        val bl = (ch(a, 0) + (ch(b, 0) - ch(a, 0)) * t).roundToInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    private fun parseColor(raw: String): Int? {
        val cleaned = raw.trim().removePrefix("#")
        if (cleaned.length != 6 && cleaned.length != 8) return null
        return cleaned.toLongOrNull(16)?.let {
            if (cleaned.length == 6) (0xFF000000 or it).toInt() else it.toInt()
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback
}
