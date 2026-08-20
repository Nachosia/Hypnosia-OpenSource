package dev.hypnosia.media

import dev.hypnosia.hud.WatermarkHud
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

object GlobalMediaTracker {

    // Public media state — read by WatermarkHud and NowPlayingHud
    var trackTitle: String = ""
    var trackArtist: String = ""
    var trackPositionMs: Long = 0L
    var trackDurationMs: Long = 0L
    var lastProgressUpdate: Long = 0L
    var isMediaPlaying: Boolean = false
    var coverTextureId: Identifier? = null

    fun getSmoothProgress(): Float {
        if (!isMediaPlaying || trackDurationMs <= 0) {
            return if (trackDurationMs > 0) trackPositionMs.toFloat() / trackDurationMs.toFloat() else 0.0f
        }
        val elapsed = System.currentTimeMillis() - lastProgressUpdate
        val smooth = trackPositionMs + elapsed
        return (smooth.toFloat() / trackDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
    }

    fun isActive(): Boolean = trackTitle.isNotBlank() && trackTitle != "Minecraft"

    private var lastTitle: String = ""
    private var consecutiveFailures = 0
    private const val MAX_FAILURES = 5
    private var timer: Timer? = null
    private var wasLeftDown = false

    fun start() {
        if (timer != null) return

        if (!MediaBridge.isAvailable) {
            println("[Hypnosia] MediaBridge not available: native library missing")
            return
        }

        timer = Timer("GlobalMedia", true).apply {
            scheduleAtFixedRate(0, 5000) {
                pollMedia()
            }
        }
        println("[Hypnosia] GlobalMediaTracker started")

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            handleClicks(client)
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    private fun pollMedia() {
        try {
            val media = MediaBridge.readCurrentMedia()

            if (media != null && media.title.isNotBlank()) {
                consecutiveFailures = 0

                MinecraftClient.getInstance().execute {
                    trackTitle = media.title
                    trackArtist = media.artist
                    trackDurationMs = media.durationMs
                    trackPositionMs = media.positionMs
                    lastProgressUpdate = System.currentTimeMillis()
                    isMediaPlaying = media.isPlaying

                    // Sync to WatermarkHud for V1 display
                    WatermarkHud.syncFromTracker()

                    media.thumbnailPath?.let { path -> loadThumbnail(path) }
                }

                if (media.title != lastTitle) {
                    println("[Hypnosia] Now playing: ${media.title} — ${media.artist}")
                    lastTitle = media.title
                }

            } else {
                MinecraftClient.getInstance().execute {
                    if (trackTitle.isNotEmpty()) {
                        trackTitle = ""
                        trackArtist = ""
                        trackPositionMs = 0
                        trackDurationMs = 0
                        isMediaPlaying = false
                        coverTextureId = null
                        WatermarkHud.syncFromTracker()
                    }
                }
                lastTitle = ""
            }

        } catch (e: Exception) {
            consecutiveFailures++
            if (consecutiveFailures <= 3) {
                println("[Hypnosia] Media poll failed ($consecutiveFailures/$MAX_FAILURES)")
            }
            if (consecutiveFailures >= MAX_FAILURES) {
                println("[Hypnosia] Media tracker stopped after $MAX_FAILURES failures")
                stop()
            }
        }
    }

    private fun loadThumbnail(path: String) {
        try {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return

            val image = net.minecraft.client.texture.NativeImage.read(file.inputStream())
            val texture = NativeImageBackedTexture({ "Hypnosia media thumbnail" }, image)
            texture.upload()
            val id = Identifier.of("hypnosia", "media_cover")
            MinecraftClient.getInstance().textureManager.registerTexture(id, texture)
            coverTextureId = id
            WatermarkHud.syncFromTracker()
            println("[Hypnosia] Thumbnail registered: $id")
        } catch (e: Exception) {
            println("[Hypnosia] Thumbnail load failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun handleClicks(client: MinecraftClient) {
        if (client.currentScreen == null) return

        val window = client.window
        val fixedScale = 1.0f / window.scaleFactor.toFloat().coerceAtLeast(1.0f)
        val mouseGuiX = (client.mouse.x * window.scaledWidth / window.width).toFloat()
        val mouseGuiY = (client.mouse.y * window.scaledHeight / window.height).toFloat()
        val mouseX = mouseGuiX / fixedScale
        val mouseY = mouseGuiY / fixedScale

        val isLeftDown = GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        val clicked = !wasLeftDown && isLeftDown
        wasLeftDown = isLeftDown

        if (clicked) {
            WatermarkHud.handleV1PlayerClick(mouseX, mouseY)
        }
    }
}
