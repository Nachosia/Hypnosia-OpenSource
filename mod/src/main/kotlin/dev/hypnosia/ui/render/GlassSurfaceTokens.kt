package dev.hypnosia.ui.render

object GlassSurfaceTokens {
    val glassBase: Int = rgba(42, 42, 46, 0.42f)
    val glassLayer: Int = rgba(70, 68, 72, 0.28f)
    val glassCard: Int = rgba(82, 78, 82, 0.24f)
    val glassScrim: Int = rgba(10, 10, 12, 0.22f)
    val glassBorder: Int = rgba(255, 255, 255, 0.16f)
    val glassBorderStrong: Int = rgba(255, 255, 255, 0.24f)
    val glassHighlight: Int = rgba(255, 255, 255, 0.20f)
    val glassInnerHighlight: Int = rgba(255, 255, 255, 0.08f)
    val glassShadow: Int = rgba(0, 0, 0, 0.28f)

    val accentSoft: Int = rgba(190, 215, 255, 0.22f)
    val accentActive: Int = rgba(210, 225, 255, 0.34f)
    val warmReflection: Int = rgba(255, 246, 230, 0.12f)
    val coolReflection: Int = rgba(190, 215, 255, 0.12f)

    val textPrimary: Int = rgba(255, 255, 255, 0.88f)
    val textSecondary: Int = rgba(255, 255, 255, 0.60f)
    val textMuted: Int = rgba(255, 255, 255, 0.42f)

    fun rgba(r: Int, g: Int, b: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
        return (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
