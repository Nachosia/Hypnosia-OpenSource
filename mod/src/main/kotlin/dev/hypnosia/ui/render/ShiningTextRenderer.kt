package dev.hypnosia.ui.render

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import kotlin.math.sin

/**
 * Утилита для рендера текста с эффектами:
 * 1. **Glow** — неоновое свечение вокруг текста (drop-shadow из 8 смещённых копий)
 * 2. **Shine/Sweep** — бегущий блик по буквам через sin() от времени и индекса символа
 *
 * Использует стандартный Minecraft TextRenderer + DrawContext (1.21.11).
 * Каждый символ рисуется отдельно, чтобы иметь свой цвет блика.
 */
object ShiningTextRenderer {

    /**
     * Рисует текст с glow + shine эффектами.
     *
     * @param context   DrawContext текущего кадра
     * @param text      Строка для отрисовки
     * @param x         Позиция X (левый верхний угол)
     * @param y         Позиция Y (левый верхний угол)
     * @param baseColor Базовый цвет текста (ARGB, обычно 0xFF____)
     * @param shineColor Цвет блика (например, белый 0xFFFFFFFF или светло-жёлтый)
     * @param speed     Скорость движения блика. 1.0f ≈ 1 полный цикл sin за ~2.5 секунды.
     *                  Чем больше, тем быстрее блик бежит по тексту.
     */
    fun draw(
        context: DrawContext,
        text: String,
        x: Int,
        y: Int,
        baseColor: Int,
        shineColor: Int,
        speed: Float = 1.0f,
    ) {
        val textRenderer = MinecraftClient.getInstance().textRenderer
        val time = System.currentTimeMillis() / 1000.0 * speed

        // ─── 1. GLOW (Неоновое свечение) ───
        // Рисуем 8 копий текста со смещением ±1px по всем направлениям.
        // Alpha glow слоя ~40% (0x66), чтобы свечение было мягким.
        val glowColor = withAlpha(baseColor, alpha = 0x66)
        GLOW_OFFSETS.forEach { (dx, dy) ->
            context.drawText(textRenderer, text, x + dx, y + dy, glowColor, false)
        }

        // ─── 2. SHINE (Бегущий блик) ───
        // Рисуем посимвольно. Каждая буква имеет свой shineFactor,
        // зависящий от её индекса и времени.
        var cursorX = x
        text.forEachIndexed { index, char ->
            val charStr = char.toString()

            // Формула блика:
            // index * 0.4f   — сдвигает фазу sin для каждой буквы (чем больше множитель, тем чаще блик)
            // - time         — двигает волну влево со временем
            // sin(...)       — колебание от -1 до +1
            // (+1) / 2       — нормализуем в диапазон 0.0 .. 1.0
            val offset = index * 0.4f - time.toFloat()
            val shineFactor = ((sin(offset) + 1f) / 2f).coerceIn(0f, 1f)

            // Интерполируем между baseColor и shineColor
            val charColor = mixColor(baseColor, shineColor, shineFactor)

            context.drawText(textRenderer, charStr, cursorX, y, charColor, false)
            cursorX += textRenderer.getWidth(charStr)
        }
    }

    /**
     * То же самое, но с кастомным [spacing] между символами (в пикселях).
     * Полезно, если нужен monospace-эффект или больший tracking.
     */
    fun drawWithSpacing(
        context: DrawContext,
        text: String,
        x: Int,
        y: Int,
        baseColor: Int,
        shineColor: Int,
        speed: Float = 1.0f,
        spacing: Int = 0,
    ) {
        val textRenderer = MinecraftClient.getInstance().textRenderer
        val time = System.currentTimeMillis() / 1000.0 * speed
        val glowColor = withAlpha(baseColor, alpha = 0x66)

        GLOW_OFFSETS.forEach { (dx, dy) ->
            context.drawText(textRenderer, text, x + dx, y + dy, glowColor, false)
        }

        var cursorX = x
        text.forEachIndexed { index, char ->
            val charStr = char.toString()
            val offset = index * 0.4f - time.toFloat()
            val shineFactor = ((sin(offset) + 1f) / 2f).coerceIn(0f, 1f)
            val charColor = mixColor(baseColor, shineColor, shineFactor)

            context.drawText(textRenderer, charStr, cursorX, y, charColor, false)
            cursorX += textRenderer.getWidth(charStr) + spacing
        }
    }

    // ─── Вспомогательные функции ───

    /**
     * Линейная интерполяция между двумя ARGB цветами.
     * @param t Фактор смешивания 0.0 .. 1.0
     * @return Цвет с alpha = 0xFF (полностью непрозрачный)
     */
    private fun mixColor(a: Int, b: Int, t: Float): Int {
        // Извлекаем RGB каналы (Alpha игнорируем, т.к. Minecraft TextRenderer
        // сам управляет прозрачностью через старший байт color)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF

        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF

        // Lerp по каждому каналу
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val b = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)

        // Возвращаем ARGB с alpha = 0xFF (непрозрачный)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /**
     * Заменяет alpha-канал цвета на заданное значение.
     */
    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    /** Смещения для glow (8 направлений вокруг пикселя) */
    private val GLOW_OFFSETS = listOf(
        -1 to 0,  1 to 0,
        0 to -1,  0 to 1,
        -1 to -1, -1 to 1,
        1 to -1,  1 to 1,
    )
}
