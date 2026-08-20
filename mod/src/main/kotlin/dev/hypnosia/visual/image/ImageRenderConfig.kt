package dev.hypnosia.visual.image

import dev.hypnosia.config.HypnosiaClientSettings
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.exists

/**
 * Управление списком изображений для рендера.
 * Хранит entries в виде строки через запятую в ключе `image.entries`.
 * Каждый entry имеет свой префикс `image.entry.<filename>.`.
 */
object ImageRenderConfig {
    private val logger = LoggerFactory.getLogger("HypnosiaImageRender")

    private const val GLOBAL_ENABLED_KEY = "image.global.enabled"
    private var cachedEntries: List<ImageRenderEntry>? = null
    private var cachedGlobalEnabled: Boolean? = null

    /** Все прописанные в конфиге изображения */
    fun entries(): List<ImageRenderEntry> {
        cachedEntries?.let { return it }
        val paths = readPaths()
        val list = paths.map { ImageRenderEntry.fromSettings(it) }
        cachedEntries = list
        return list
    }

    /** Глобальный включён/выключен */
    fun isGlobalEnabled(): Boolean {
        cachedGlobalEnabled?.let { return it }
        val value = HypnosiaClientSettings.boolean(GLOBAL_ENABLED_KEY, true)
        cachedGlobalEnabled = value
        return value
    }

    fun setGlobalEnabled(value: Boolean) {
        cachedGlobalEnabled = value
        HypnosiaClientSettings.set(GLOBAL_ENABLED_KEY, value.toString())
    }

    /** Только включённые */
    fun enabledEntries(): List<ImageRenderEntry> {
        if (!isGlobalEnabled()) return emptyList()
        return entries().filter { it.enabled }
    }

    /** Добавить новый файл в конфиг. Если уже есть — ничего не делает. */
    fun add(path: String) {
        val paths = readPaths().toMutableList()
        if (paths.any { it.equals(path, ignoreCase = true) }) return
        paths.add(path)
        writePaths(paths)
        // Создаём дефолтные настройки для нового entry
        val entry = ImageRenderEntry(path)
        saveEntry(entry)
        cachedEntries = null
        logger.info("Added image entry: {}", path)
    }

    /** Удалить файл из конфига */
    fun remove(path: String) {
        val paths = readPaths().filterNot { it.equals(path, ignoreCase = true) }
        writePaths(paths)
        // Чистим старые ключи
        val prefix = "image.entry.${path.lowercase().replace(" ", "_")}."
        val keysToRemove = HypnosiaClientSettings.keys(prefix)
        if (keysToRemove.isNotEmpty()) {
            val map = keysToRemove.associateWith { null as String? }
            HypnosiaClientSettings.setAll(map)
        }
        cachedEntries = null
        logger.info("Removed image entry: {}", path)
    }

    /** Обновить настройки одного entry */
    fun update(entry: ImageRenderEntry) {
        saveEntry(entry)
        cachedEntries = null
    }

    /** Обновить только координаты в памяти (без записи на диск). Для drag-and-drop. */
    fun updatePositionInMemory(path: String, x: Float, y: Float) {
        cachedEntries = cachedEntries?.map {
            if (it.path.equals(path, ignoreCase = true)) it.copy(x = x, y = y) else it
        }
    }

    /** Сохранить текущие координаты entry на диск */
    fun saveEntryNow(path: String) {
        val entry = cachedEntries?.find { it.path.equals(path, ignoreCase = true) } ?: return
        saveEntry(entry)
    }

    /** Сканировать папку kartinki и добавить все новые файлы в конфиг */
    fun scanFolder(dir: java.nio.file.Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { it.lowercase().endsWith(".png") || it.lowercase().endsWith(".gif") }
                .forEach { add(it) }
        }
    }

    /** Проверить, есть ли файл в конфиге */
    fun contains(path: String): Boolean = readPaths().any { it.equals(path, ignoreCase = true) }

    /** Прочитать сырые пути из настроек */
    private fun readPaths(): List<String> {
        val raw = HypnosiaClientSettings.string(ImageRenderEntry.ENTRIES_KEY, "")
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Сохранить список путей */
    private fun writePaths(paths: List<String>) {
        HypnosiaClientSettings.set(ImageRenderEntry.ENTRIES_KEY, paths.joinToString(","))
    }

    private fun saveEntry(entry: ImageRenderEntry) {
        val prefix = entry.settingsPrefix()
        val chromaHex = entry.chromaKeyColor?.let { String.format("%06X", it and 0x00FFFFFF) } ?: ""
        val values = mutableMapOf(
            "${prefix}enabled" to entry.enabled.toString(),
            "${prefix}x" to entry.x.toString(),
            "${prefix}y" to entry.y.toString(),
            "${prefix}scale" to entry.scale.toString(),
            "${prefix}rounded" to entry.rounded.toString(),
            "${prefix}chroma" to chromaHex,
            "${prefix}chromaThreshold" to entry.chromaKeyThreshold.toString(),
        )
        values.forEach { (k, v) -> HypnosiaClientSettings.set(k, v) }
    }
}
