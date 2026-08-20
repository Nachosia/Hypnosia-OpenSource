package dev.hypnosia.ui.layout

object UiInputState {
    var mouseX: Float = Float.NaN
        private set

    var mouseY: Float = Float.NaN
        private set

    var frameSeconds: Float = 0.0f
        private set

    fun update(mouseX: Float, mouseY: Float, frameSeconds: Float) {
        this.mouseX = mouseX
        this.mouseY = mouseY
        this.frameSeconds = frameSeconds.coerceIn(0.0f, 0.05f)
    }

    fun contains(rect: Rect): Boolean {
        return mouseX >= rect.x &&
            mouseX <= rect.right &&
            mouseY >= rect.y &&
            mouseY <= rect.bottom
    }
}
