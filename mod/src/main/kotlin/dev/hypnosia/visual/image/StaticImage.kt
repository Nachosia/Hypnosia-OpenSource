package dev.hypnosia.visual.image

import dev.hypnosia.HypnosiaClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

class StaticImage(
    val name: String,
    private val nativeImage: NativeImage,
) {
    val identifier: Identifier = Identifier.of(
        HypnosiaClient.MOD_ID,
        "dynamic/images/${name.lowercase().replace(" ", "_")}"
    )

    val width: Int get() = nativeImage.width
    val height: Int get() = nativeImage.height

    fun upload() {
        MinecraftClient.getInstance().execute {
            try {
                val texture = NativeImageBackedTexture({ "Hypnosia image $name" }, nativeImage)
                texture.upload()
                MinecraftClient.getInstance().textureManager.registerTexture(identifier, texture)
                logger.info("Uploaded static image '{}' as {}", name, identifier)
            } catch (e: Exception) {
                logger.error("Failed to upload static image '{}'", name, e)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("HypnosiaImageRender")
    }
}
