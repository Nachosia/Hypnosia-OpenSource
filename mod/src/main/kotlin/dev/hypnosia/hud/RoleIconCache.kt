package dev.hypnosia.hud

import dev.hypnosia.HypnosiaClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.concurrent.CompletableFuture

object RoleIconCache {
    private val cache = mutableMapOf<String, Identifier>()
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val logger = LoggerFactory.getLogger("HypnosiaRoleIconCache")

    fun get(role: String): Identifier? = synchronized(cache) { cache[role] }

    fun loadAsync(role: String, url: String): CompletableFuture<Unit> {
        synchronized(loading) {
            if (loading.contains(role)) return CompletableFuture.completedFuture(Unit)
            if (cache.containsKey(role)) return CompletableFuture.completedFuture(Unit)
            if (failed.contains(role)) return CompletableFuture.completedFuture(Unit)
            loading.add(role)
        }

        val future = CompletableFuture<Unit>()
        CompletableFuture.runAsync {
            var lastException: Throwable? = null
            for (attempt in 1..3) {
                try {
                    logger.info("Loading role icon for '$role' from $url (attempt $attempt)")
                    val connection = URL(url).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 10000
                    connection.getInputStream().use { stream ->
                        registerTexture(role, stream)
                    }
                    logger.info("Role icon for '$role' loaded successfully")
                    future.complete(Unit)
                    return@runAsync
                } catch (e: Exception) {
                    lastException = e
                    logger.warn("Attempt $attempt failed for '$role': ${e.message}")
                    if (attempt < 3) {
                        Thread.sleep(1000L * attempt)
                    }
                }
            }
            logger.error("Failed to load role icon for '$role' after 3 attempts", lastException)
            synchronized(failed) { failed.add(role) }
            future.complete(Unit)
        }.thenRun {
            synchronized(loading) { loading.remove(role) }
        }
        return future
    }

    private fun registerTexture(role: String, stream: java.io.InputStream) {
        val nativeImage = NativeImage.read(stream)
        val id = Identifier.of(HypnosiaClient.MOD_ID, "dynamic/role_icon_${role.lowercase()}")
        MinecraftClient.getInstance().execute {
            try {
                val texture = NativeImageBackedTexture({ "Hypnosia role icon $role" }, nativeImage)
                texture.upload()
                MinecraftClient.getInstance().textureManager.registerTexture(id, texture)
                synchronized(cache) { cache[role] = id }
                logger.info("Registered dynamic role icon texture for '$role' as $id")
            } catch (e: Exception) {
                logger.error("Failed to register texture for '$role'", e)
                synchronized(failed) { failed.add(role) }
            }
        }
    }
}
