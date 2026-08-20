package dev.hypnosia.ui.layout

data class Size(val width: Float, val height: Float) {
    companion object {
        val Zero = Size(0.0f, 0.0f)
    }
}

data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

data class Insets(
    val left: Float = 0.0f,
    val top: Float = 0.0f,
    val right: Float = 0.0f,
    val bottom: Float = 0.0f,
) {
    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom

    companion object {
        val Zero = Insets()
        fun all(value: Float) = Insets(value, value, value, value)
        fun symmetric(horizontal: Float = 0.0f, vertical: Float = 0.0f) =
            Insets(horizontal, vertical, horizontal, vertical)
    }
}

enum class Axis {
    Horizontal,
    Vertical,
}

enum class Alignment {
    Start,
    Center,
    End,
    Stretch,
}

sealed class SizeMode {
    data class Fixed(val pixels: Float) : SizeMode()
    data object Hug : SizeMode()
    data object Fill : SizeMode()
}

data class LayoutSpec(
    val width: SizeMode = SizeMode.Hug,
    val height: SizeMode = SizeMode.Hug,
)

data class Constraints(
    val maxWidth: Float = Float.POSITIVE_INFINITY,
    val maxHeight: Float = Float.POSITIVE_INFINITY,
) {
    fun constrain(size: Size): Size {
        return Size(
            width = size.width.coerceAtMost(maxWidth),
            height = size.height.coerceAtMost(maxHeight),
        )
    }
}
