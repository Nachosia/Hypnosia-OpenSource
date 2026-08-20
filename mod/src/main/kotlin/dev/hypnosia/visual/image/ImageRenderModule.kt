package dev.hypnosia.visual.image

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.client.texture.NativeImage
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.ImageInputStream
import kotlin.io.path.exists
import kotlin.io.path.inputStream

object ImageRenderModule {
    private val logger = LoggerFactory.getLogger("HypnosiaImageRender")

    private val staticCache = mutableMapOf<String, StaticImage>()
    private val gifCache = mutableMapOf<String, GifImage>()

    // DoS limits
    private const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10 MB
    private const val MAX_IMAGE_DIMENSION = 4096
    private const val MAX_GIF_FRAMES = 300

    /** Какой entry сейчас редактируется в V2 GUI */
    var selectedEntryPath: String? = null

    /** true когда открыт HypnosiaHomeV2Screen — HUD-рендер картинок отключается */
    var isV2GuiOpen = false

    /** Drag-and-drop state */
    var draggedEntryPath: String? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    /** Папка где пользователь сам закидывает картинки: .minecraft/Hypnosia/kartinki/ */
    val KARTINKI_DIR by lazy {
        FabricLoader.getInstance().gameDir.resolve("Hypnosia/kartinki").also {
            if (!it.exists()) {
                runCatching { Files.createDirectories(it) }
            }
        }
    }

    fun register() {
        // Создаём папку сразу при старте, даже если конфиг пустой
        KARTINKI_DIR.toString()

        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            Identifier.of(HypnosiaClient.MOD_ID, "image_render_hud"),
            ::renderHud,
        )
        // Предзагружаем все изображения, которые прописаны в конфиге
        ImageRenderConfig.entries().forEach { entry ->
            loadEntry(entry)
        }
        logger.info(
            "ImageRenderModule ready. Entries: {}, loaded: {}/{}",
            ImageRenderConfig.entries().size,
            staticCache.size,
            gifCache.size,
        )
    }

    private fun renderHud(context: DrawContext, _tickCounter: RenderTickCounter) {
        if (isV2GuiOpen) return
        renderOverlay(context)
    }

    /** Явно перезагрузить изображения по текущему конфигу */
    fun reload() {
        staticCache.clear()
        gifCache.clear()
        ImageRenderConfig.entries().forEach { loadEntry(it) }
        logger.info("ImageRenderModule reloaded.")
    }

    /** Доступ к загруженным объектам (для API) */
    fun getStatic(name: String): StaticImage? = staticCache[name]
    fun getGif(name: String): GifImage? = gifCache[name]

    fun getEntrySize(entry: ImageRenderEntry): Pair<Float, Float> {
        val static = staticCache[entry.name]
        val gif = gifCache[entry.name]
        val aspect = when {
            static != null && static.height > 0 -> static.width.toFloat() / static.height.toFloat()
            gif != null && gif.height > 0 -> gif.width.toFloat() / gif.height.toFloat()
            else -> 1f
        }
        val maxWidth = 200f * entry.scale
        val h = maxWidth / aspect
        return maxWidth to h
    }

    fun tickDrag(client: MinecraftClient) {
        val isChatOpen = client.currentScreen is net.minecraft.client.gui.screen.ChatScreen
        val window = client.window
        val isMouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window.handle, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS

        if (!isChatOpen || client.player == null) {
            draggedEntryPath = null
            return
        }

        val fixedScale = 1.0f / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        // Convert mouse to the same coordinate space as renderOverlay (fixed space)
        val mouseX = (client.mouse.x * window.scaledWidth / window.width).toFloat() / fixedScale
        val mouseY = (client.mouse.y * window.scaledHeight / window.height).toFloat() / fixedScale

        if (isMouseDown) {
            if (draggedEntryPath == null) {
                for (entry in ImageRenderConfig.enabledEntries()) {
                    val (w, h) = getEntrySize(entry)
                    if (mouseX >= entry.x && mouseX < entry.x + w && mouseY >= entry.y && mouseY < entry.y + h) {
                        draggedEntryPath = entry.path
                        dragOffsetX = mouseX - entry.x
                        dragOffsetY = mouseY - entry.y
                        println("[Hypnosia] Image drag started: ${entry.path} at (${entry.x},${entry.y}) size=(${w},${h})")
                        break
                    }
                }
            } else {
                val entry = ImageRenderConfig.entries().find { it.path.equals(draggedEntryPath, ignoreCase = true) } ?: run {
                    draggedEntryPath = null
                    return
                }
                val newX = mouseX - dragOffsetX
                val newY = mouseY - dragOffsetY
                ImageRenderConfig.updatePositionInMemory(entry.path, newX, newY)
            }
        } else {
            if (draggedEntryPath != null) {
                ImageRenderConfig.saveEntryNow(draggedEntryPath!!)
                println("[Hypnosia] Image drag ended")
            }
            draggedEntryPath = null
        }
    }

    private fun loadEntry(entry: ImageRenderEntry) {
        when {
            entry.isPng -> loadStatic(entry)
            entry.isGif -> loadGif(entry)
            else -> logger.warn("Unknown image extension for '{}', skipping", entry.path)
        }
    }

    private fun loadStatic(entry: ImageRenderEntry) {
        val name = entry.name
        if (staticCache.containsKey(name)) return
        val path = KARTINKI_DIR.resolve(entry.path)
        if (!path.exists()) {
            logger.warn("PNG not found in kartinki: {}", entry.path)
            return
        }
        if (!checkFileSize(path)) return
        try {
            path.inputStream().use { s ->
                val nativeImage = if (entry.chromaKeyColor != null) {
                    val bi = javax.imageio.ImageIO.read(s)
                    if (bi != null && (bi.width > MAX_IMAGE_DIMENSION || bi.height > MAX_IMAGE_DIMENSION)) {
                        logger.warn("PNG '{}' exceeds max dimensions ({}x{}), skipping", entry.path, bi.width, bi.height)
                        return
                    }
                    bufferedImageToNativeImage(bi, entry)
                } else {
                    NativeImage.read(s)
                }
                val image = StaticImage(name, nativeImage)
                image.upload()
                staticCache[name] = image
            }
        } catch (e: OutOfMemoryError) {
            logger.error("OutOfMemoryError loading static image '{}'", entry.path)
        } catch (e: Exception) {
            logger.error("Failed to load static image '{}'", entry.path, e)
        }
    }

    private fun loadGif(entry: ImageRenderEntry) {
        val name = entry.name
        if (gifCache.containsKey(name)) return
        val path = KARTINKI_DIR.resolve(entry.path)
        if (!path.exists()) {
            logger.warn("GIF not found in kartinki: {}", entry.path)
            return
        }
        if (!checkFileSize(path)) return
        try {
            val frames = mutableListOf<GifImage.Frame>()
            path.inputStream().use { s ->
                val iis: ImageInputStream = ImageIO.createImageInputStream(s)
                val readers = ImageIO.getImageReadersByFormatName("gif")
                if (!readers.hasNext()) {
                    logger.warn("No GIF reader available")
                    return
                }
                val reader = readers.next()
                reader.input = iis
                val count = reader.getNumImages(true)
                if (count > MAX_GIF_FRAMES) {
                    logger.warn("GIF '{}' has too many frames ({} > {}), skipping", entry.path, count, MAX_GIF_FRAMES)
                    reader.dispose()
                    iis.close()
                    return
                }

                val screenW = reader.getWidth(0)
                val screenH = reader.getHeight(0)
                // Persistent canvas — carries pixels forward between frames (disposal=doNotDispose)
                val canvas = BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_ARGB)
                // Saved canvas for disposal=restoreToPrevious
                var savedCanvas: BufferedImage? = null

                val uniqueName = "${name.lowercase().replace(" ", "_")}_${System.identityHashCode(entry)}"

                for (i in 0 until count) {
                    val rawFrame = reader.read(i)

                    // Read frame metadata
                    val meta = reader.getImageMetadata(i)
                    val root = meta.getAsTree("javax_imageio_gif_image_1.0") as? IIOMetadataNode

                    val idNode = root?.getElementsByTagName("ImageDescriptor")?.item(0) as? IIOMetadataNode
                    val fx = idNode?.getAttribute("imageLeftPosition")?.toIntOrNull() ?: 0
                    val fy = idNode?.getAttribute("imageTopPosition")?.toIntOrNull() ?: 0

                    val gce = root?.getElementsByTagName("GraphicControlExtension")?.item(0) as? IIOMetadataNode
                    val disposalStr = gce?.getAttribute("disposalMethod") ?: "none"

                    // For disposal=restoreToPrevious, snapshot BEFORE drawing
                    if (disposalStr == "restoreToPrevious") {
                        savedCanvas = deepCopyArgb(canvas)
                    }

                    // Convert indexed frame to ARGB preserving transparency, then composite onto canvas
                    val argbFrame = indexedToArgb(rawFrame)
                    val g = canvas.createGraphics()
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)
                    g.drawImage(argbFrame, fx, fy, null)
                    g.dispose()

                    if (canvas.width > MAX_IMAGE_DIMENSION || canvas.height > MAX_IMAGE_DIMENSION) {
                        logger.warn("GIF frame {} exceeds max dimensions, skipping", i)
                        continue
                    }

                    // Snapshot composited canvas as the output frame
                    val snapshot = deepCopyArgb(canvas)
                    val nativeImage = bufferedImageToNativeImage(snapshot, entry)
                    val delayMs = readFrameDelay(reader, i)
                    val id = Identifier.of(HypnosiaClient.MOD_ID, "dynamic/images/gif_${uniqueName}_frame_$i")
                    frames.add(GifImage.Frame(id, nativeImage, delayMs))

                    // Apply disposal for next frame
                    when (disposalStr) {
                        "restoreToBackgroundColor" -> {
                            val gc = canvas.createGraphics()
                            gc.composite = AlphaComposite.getInstance(AlphaComposite.CLEAR)
                            gc.fillRect(fx, fy, rawFrame.width, rawFrame.height)
                            gc.dispose()
                        }
                        "restoreToPrevious" -> {
                            val gc = canvas.createGraphics()
                            gc.composite = AlphaComposite.getInstance(AlphaComposite.SRC)
                            gc.drawImage(savedCanvas ?: canvas, 0, 0, null)
                            gc.dispose()
                        }
                        // "doNotDispose", "none" — leave canvas as-is
                    }
                }
                reader.dispose()
                iis.close()
            }
            if (frames.isNotEmpty()) {
                val gif = GifImage(name, frames)
                gif.upload()
                gifCache[name] = gif
            }
        } catch (e: OutOfMemoryError) {
            logger.error("OutOfMemoryError loading GIF '{}'", entry.path)
        } catch (e: Exception) {
            logger.error("Failed to load GIF '{}'", entry.path, e)
        }
    }

    // Convert any BufferedImage (indexed or otherwise) to TYPE_INT_ARGB preserving transparency
    private fun indexedToArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val dst = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        // SRC composite: copies source pixels as-is including alpha=0 from IndexColorModel
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC)
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return dst
    }

    private fun deepCopyArgb(src: BufferedImage): BufferedImage {
        val dst = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC)
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return dst
    }

    /** Ищет файл в папке images/<subfolder>/ внутри мода */
    private fun findResourceStream(fileName: String): java.io.InputStream? {
        val path = KARTINKI_DIR.resolve(fileName)
        return if (path.exists()) path.inputStream() else null
    }

    private fun checkFileSize(path: java.nio.file.Path): Boolean {
        val size = java.nio.file.Files.size(path)
        if (size > MAX_FILE_SIZE) {
            logger.warn("Image '{}' exceeds max file size ({} > {}), skipping", path.fileName, size, MAX_FILE_SIZE)
            return false
        }
        return true
    }

    private fun readFrameDelay(reader: ImageReader, frameIndex: Int): Int {
        return try {
            val meta = reader.getImageMetadata(frameIndex)
            val tree = meta.getAsTree("javax_imageio_gif_image_1.0") as? IIOMetadataNode
            val gce = tree?.getElementsByTagName("GraphicControlExtension")?.item(0) as? IIOMetadataNode
            val delay = gce?.getAttribute("delayTime")?.toIntOrNull() ?: 10
            delay * 10
        } catch (e: Exception) {
            100
        }
    }

    private fun bufferedImageToNativeImage(bi: java.awt.image.BufferedImage, entry: ImageRenderEntry? = null): NativeImage {
        val width = bi.width
        val height = bi.height
        val native = NativeImage(width, height, false)
        val chroma = entry?.chromaKeyColor
        val threshold = (entry?.chromaKeyThreshold ?: 0.08f) * 255.0f
        val ckR = chroma?.let { (it ushr 16) and 0xFF } ?: -1
        val ckG = chroma?.let { (it ushr 8) and 0xFF } ?: -1
        val ckB = chroma?.let { it and 0xFF } ?: -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = bi.getRGB(x, y)
                var a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (chroma != null) {
                    val diffR = kotlin.math.abs(r - ckR)
                    val diffG = kotlin.math.abs(g - ckG)
                    val diffB = kotlin.math.abs(b - ckB)
                    if (diffR <= threshold && diffG <= threshold && diffB <= threshold) {
                        a = 0
                    }
                }
                val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
                native.setColor(x, y, abgr)
            }
        }
        return native
    }

    /** Рендер всех включённых изображений. Вызывай из нужного места (HUD / GUI). */
    fun renderOverlay(context: DrawContext, offsetY: Float = 0.0f) {
        val entries = ImageRenderConfig.enabledEntries()
        if (entries.isEmpty()) return

        val client = MinecraftClient.getInstance()
        val fixedScale = 1.0f / client.window.scaleFactor.toFloat().coerceAtLeast(1.0f)

        context.matrices.pushMatrix()
        context.matrices.scale(fixedScale, fixedScale)

        for (entry in entries) {
            val y = entry.y + offsetY
            when {
                entry.isPng -> {
                    val image = staticCache[entry.name] ?: continue
                    val aspect = if (image.height > 0) image.width.toFloat() / image.height.toFloat() else 1f
                    val maxWidth = 200f * entry.scale
                    val h = maxWidth / aspect
                    drawImage(context, image.identifier, entry.x, y, maxWidth, h, entry.rounded)
                }
                entry.isGif -> {
                    val gif = gifCache[entry.name] ?: continue
                    val frameId = gif.currentFrameIdentifier() ?: continue
                    val aspect = if (gif.height > 0) gif.width.toFloat() / gif.height.toFloat() else 1f
                    val maxWidth = 200f * entry.scale
                    val h = maxWidth / aspect
                    drawImage(context, frameId, entry.x, y, maxWidth, h, entry.rounded)
                }
            }
        }

        context.matrices.popMatrix()
    }

    fun drawImage(context: DrawContext, id: Identifier, x: Float, y: Float, w: Float, h: Float, rounded: Float) {
        if (rounded > 0.5f) {
            HypnosiaRenderUtils.drawRoundedTexture(context, id, x, y, w, h, rounded)
        } else {
            HypnosiaRenderUtils.drawVanillaIcon(context, id, x, y, w, h)
        }
    }
}
