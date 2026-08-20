package dev.hypnosia.visual.image

import dev.hypnosia.HypnosiaClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

class GifImage(
    val name: String,
    private val frames: List<Frame>,
) {
    data class Frame(
        val identifier: Identifier,
        val nativeImage: NativeImage,
        val delayMs: Int,
    )

    private val totalDuration: Int = frames.sumOf { it.delayMs.coerceAtLeast(MIN_FRAME_DELAY) }
    private var startTime: Long = System.currentTimeMillis()

    val width: Int get() = frames.firstOrNull()?.nativeImage?.width ?: 0
    val height: Int get() = frames.firstOrNull()?.nativeImage?.height ?: 0
    val frameCount: Int get() = frames.size

    fun resetAnimation() {
        startTime = System.currentTimeMillis()
    }

    fun currentFrameIdentifier(): Identifier? {
        if (frames.isEmpty()) return null
        if (totalDuration <= 0) return frames.first().identifier

        val elapsed = ((System.currentTimeMillis() - startTime) % totalDuration).toInt()
        var accumulated = 0
        for (frame in frames) {
            accumulated += frame.delayMs.coerceAtLeast(MIN_FRAME_DELAY)
            if (elapsed < accumulated) return frame.identifier
        }
        return frames.last().identifier
    }

    fun upload() {
        val client = MinecraftClient.getInstance()
        frames.forEachIndexed { index, frame ->
            client.execute {
                try {
                    val texture = NativeImageBackedTexture(
                        { "Hypnosia GIF ${name} frame $index" },
                        frame.nativeImage
                    )
                    texture.upload()
                    client.textureManager.registerTexture(frame.identifier, texture)
                } catch (e: Exception) {
                    logger.error("Failed to upload GIF frame {} for '{}'", index, name, e)
                }
            }
        }
        logger.info("Uploaded GIF '{}' with {} frames ({}ms total)", name, frames.size, totalDuration)
    }

    companion object {
        private val logger = LoggerFactory.getLogger("HypnosiaImageRender")
        const val MIN_FRAME_DELAY: Int = 20 // ~50fps minimum
    }
}
