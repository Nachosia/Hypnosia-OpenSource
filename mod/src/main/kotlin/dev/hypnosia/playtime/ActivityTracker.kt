package dev.hypnosia.playtime

import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ActivityTracker {
    private val _activeMinutesAccumulated = AtomicInteger(0)
    val activeMinutesAccumulated: Int
        get() = _activeMinutesAccumulated.get()

    private val _afkMinutesAccumulated = AtomicInteger(0)
    val afkMinutesAccumulated: Int
        get() = _afkMinutesAccumulated.get()

    // Per-server active minutes for v2.2 batch
    private val serverMinutes = ConcurrentHashMap<String, AtomicInteger>()

    private var afkCounter = 0
    private var lastMinuteCheck = 0L
    private var lastLogMinute = -1

    // Activity state for the current minute
    private var hadMouseActivity = false
    private var hadKeyActivity = false
    private var hadInventoryActivity = false
    private var lastPosition: Vec3d? = null
    private var lastInventoryHash = 0

    private var lastMouseX = -1.0
    private var lastMouseY = -1.0

    fun tick(client: MinecraftClient) {
        val isInGame = client.currentServerEntry != null || client.isInSingleplayer
        if (!isInGame) {
            // In menu or loading screen — do not accumulate anything
            return
        }

        val now = System.currentTimeMillis()

        // Check activity every tick (but accumulate only once per minute)
        checkActivity(client)

        if (now - lastMinuteCheck < 60_000) return
        lastMinuteCheck = now

        val hasActivity = hadMouseActivity || hadKeyActivity || hadInventoryActivity || positionChanged(client)

        // Reset per-minute activity flags
        hadMouseActivity = false
        hadKeyActivity = false
        hadInventoryActivity = false

        if (hasActivity) {
            _activeMinutesAccumulated.incrementAndGet()
            afkCounter = 0
            // Track per-server minute
            val serverIp = getCurrentServerIp(client)
            serverMinutes.computeIfAbsent(serverIp) { AtomicInteger(0) }.incrementAndGet()
        } else {
            afkCounter++
            if (afkCounter <= 2) {
                // 2 minutes loyalty: still count as active
                _activeMinutesAccumulated.incrementAndGet()
                val serverIp = getCurrentServerIp(client)
                serverMinutes.computeIfAbsent(serverIp) { AtomicInteger(0) }.incrementAndGet()
            } else {
                // After 2 minutes AFK — only afkMinutes grows
                _afkMinutesAccumulated.incrementAndGet()
            }
        }

        // Log once per minute
        println("[Hypnosia] ActivityTracker minute: active=$activeMinutesAccumulated, afk=$afkMinutesAccumulated, server=${getCurrentServerIp(client)}, hasActivity=$hasActivity, afkCounter=$afkCounter")
        if (activeMinutesAccumulated != lastLogMinute) {
            lastLogMinute = activeMinutesAccumulated
            println("[Hypnosia] ActivityTracker tick: active=$activeMinutesAccumulated, afk=$afkMinutesAccumulated, server=${getCurrentServerIp(client)}, serversMap=$serverMinutes")
        }
    }

    fun reset() {
        _activeMinutesAccumulated.set(0)
        _afkMinutesAccumulated.set(0)
        afkCounter = 0
        lastLogMinute = -1
        serverMinutes.clear()
        lastPosition = null
        lastInventoryHash = 0
        hadMouseActivity = false
        hadKeyActivity = false
        hadInventoryActivity = false
    }

    fun clearAccumulated() {
        _activeMinutesAccumulated.set(0)
        _afkMinutesAccumulated.set(0)
        afkCounter = 0
        lastLogMinute = -1
        serverMinutes.clear()
    }

    /** Returns per-server minutes as list of (serverIp, minutes) pairs */
    fun getServerMinutes(): List<Pair<String, Int>> = serverMinutes.map { (k, v) -> k to v.get() }

    /** Get normalized current server IP from Minecraft client */
    private fun getCurrentServerIp(client: MinecraftClient): String {
        if (client.isInSingleplayer) {
            return "singleplayer"
        }
        val serverInfo = client.currentServerEntry
        if (serverInfo != null) {
            return normalizeServerIp(serverInfo.address)
        }
        return "idle"
    }

    /** Normalize server IP: strip port, strip lobby. subdomain */
    private fun normalizeServerIp(raw: String): String {
        if (raw == "singleplayer") return raw
        var ip = raw.split(":")[0].trim().lowercase()
        if (ip.startsWith("lobby.")) {
            ip = ip.substring(6)
        }
        return ip
    }

    private fun checkActivity(client: MinecraftClient) {
        val player = client.player ?: return
        val window = client.window.handle

        // Mouse movement (use Minecraft's mouse abstraction)
        val mx = client.mouse.x
        val my = client.mouse.y
        if (lastMouseX >= 0 && (mx != lastMouseX || my != lastMouseY)) {
            hadMouseActivity = true
        }
        lastMouseX = mx
        lastMouseY = my

        // Keys (movement, jump, attack, use)
        if (!hadKeyActivity) {
            if (client.options.forwardKey.isPressed ||
                client.options.backKey.isPressed ||
                client.options.leftKey.isPressed ||
                client.options.rightKey.isPressed ||
                client.options.jumpKey.isPressed ||
                client.options.attackKey.isPressed ||
                client.options.useKey.isPressed
            ) {
                hadKeyActivity = true
            }
        }

        // Inventory changes
        var hash = 0
        val invSize = player.inventory.size()
        for (i in 0 until invSize) {
            val stack = player.inventory.getStack(i)
            hash = hash * 31 + (stack.item.hashCode() + stack.count)
        }
        if (lastInventoryHash != 0 && hash != lastInventoryHash) {
            hadInventoryActivity = true
        }
        lastInventoryHash = hash
    }

    private fun positionChanged(client: MinecraftClient): Boolean {
        val player = client.player ?: return false
        val current = Vec3d(player.x, player.y, player.z)
        val last = lastPosition
        lastPosition = current
        return last != null && current.squaredDistanceTo(last) > 0.001
    }
}
