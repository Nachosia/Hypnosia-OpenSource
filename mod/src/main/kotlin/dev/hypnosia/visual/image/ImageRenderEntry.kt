package dev.hypnosia.visual.image

import dev.hypnosia.config.HypnosiaClientSettings

/**
 * Одна запись (слот) в конфиге рендера изображений.
 * Путь задаётся с расширением: "logo.png" или "anim.gif".
 * По расширению автоматически определяется тип (PNG / GIF).
 */
data class ImageRenderEntry(
    val path: String,          // имя файла с расширением, например "logo.png"
    val enabled: Boolean = true,
    val x: Float = 20.0f,
    val y: Float = 200.0f,
    val scale: Float = 1.0f,
    val rounded: Float = 8.0f,
    val chromaKeyColor: Int? = null,   // 0xAARRGGBB, null = выключено
    val chromaKeyThreshold: Float = 0.08f,
) {
    /** Имя без расширения (для идентификаторов) */
    val name: String
        get() = path.substringBeforeLast(".", path)

    /** Расширение в нижнем регистре */
    val extension: String
        get() = path.substringAfterLast(".", "").lowercase()

    /** Это GIF-анимация? */
    val isGif: Boolean
        get() = extension == "gif"

    /** Это статичный PNG? */
    val isPng: Boolean
        get() = extension == "png"

    /** Ключ для сохранения в HypnosiaClientSettings */
    fun settingsPrefix(): String = "image.entry.${path.lowercase().replace(" ", "_")}."

    companion object {
        const val ENTRIES_KEY = "image.entries"

        fun fromSettings(path: String): ImageRenderEntry {
            val prefix = "image.entry.${path.lowercase().replace(" ", "_")}."
            val enabled = HypnosiaClientSettings.boolean("${prefix}enabled", true)
            val x = HypnosiaClientSettings.float("${prefix}x", 20.0f)
            val y = HypnosiaClientSettings.float("${prefix}y", 200.0f)
            val scale = HypnosiaClientSettings.float("${prefix}scale", 1.0f)
            val rounded = HypnosiaClientSettings.float("${prefix}rounded", 8.0f)
            val chromaKeyRaw = HypnosiaClientSettings.string("${prefix}chroma", "")
            val chromaKeyColor = chromaKeyRaw.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
            val chromaKeyThreshold = HypnosiaClientSettings.float("${prefix}chromaThreshold", 0.08f)
            return ImageRenderEntry(path, enabled, x, y, scale, rounded, chromaKeyColor, chromaKeyThreshold)
        }
    }
}
