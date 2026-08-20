package dev.hypnosia.hud

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.other.FriendsManager
import dev.hypnosia.world.WorldVisualSettings
import net.minecraft.client.MinecraftClient
import org.lwjgl.glfw.GLFW

object ModuleHotkeys {
    private const val KEY_PREFIX = "hotkeys."
    private val bindings = linkedMapOf<String, Binding>()
    private val pressed = mutableMapOf<Int, Boolean>()
    private val genericEnabled = mutableMapOf<String, Boolean>()
    private var cachedActiveBindings: List<Binding>? = null
    private var loaded = false

    data class Binding(
        val moduleId: String,
        var title: String,
        var keyCode: Int,
    )

    fun bind(moduleId: String, title: String, keyCode: Int) {
        ensureLoaded()
        bindings[moduleId] = Binding(moduleId, title, keyCode)
        cachedActiveBindings = null
        save()
    }

    fun unbind(moduleId: String) {
        ensureLoaded()
        bindings.remove(moduleId)
        cachedActiveBindings = null
        save()
    }

    fun keyName(moduleId: String): String {
        ensureLoaded()
        val key = bindings[moduleId]?.keyCode ?: return "Bind"
        return displayKey(key)
    }

    fun activeBindings(): List<Binding> {
        ensureLoaded()
        cachedActiveBindings?.let { return it }
        return bindings.values
            .filter { it.keyCode > 0 }
            .sortedWith(compareBy<Binding> { it.title.lowercase() }.thenBy { it.moduleId })
            .also { cachedActiveBindings = it }
    }

    fun tick(client: MinecraftClient) {
        if (client.currentScreen != null || client.player == null) {
            pressed.clear()
            return
        }

        ensureLoaded()
        val window = client.window.handle
        activeBindings().forEach { binding ->
            val down = GLFW.glfwGetKey(window, binding.keyCode) == GLFW.GLFW_PRESS
            val wasDown = pressed[binding.keyCode] == true
            if (down && !wasDown) {
                toggle(binding.moduleId)
            }
            pressed[binding.keyCode] = down
        }
    }

    fun toggle(moduleId: String) {
        ensureLoaded()
        when (moduleId) {
            "hud.hotbar" -> toggleHud(HudModuleSettings.Module.HOTBAR)
            "hud.armor" -> toggleHud(HudModuleSettings.Module.ARMOR)
            "hud.player_info" -> toggleHud(HudModuleSettings.Module.PLAYER_INFO)
            "hud.inventory" -> toggleHud(HudModuleSettings.Module.INVENTORY)
            "hud.cooldowns" -> toggleHud(HudModuleSettings.Module.COOLDOWNS)
            "hud.potions" -> toggleHud(HudModuleSettings.Module.POTIONS)
            "hud.hotkeys" -> toggleHud(HudModuleSettings.Module.HOTKEYS)
            "hud.target" -> TargetHudSettings.setEnabled(!TargetHudSettings.isEnabled())
            "world.fullbright" -> WorldVisualSettings.setFullbrightEnabled(!WorldVisualSettings.fullbrightEnabled())
            "world.custom_fog" -> WorldVisualSettings.setCustomFogEnabled(!WorldVisualSettings.customFogEnabled())
            "other.friends" -> FriendsManager.setEnabled(!FriendsManager.isEnabled())
            else -> {
                val next = !HypnosiaClientSettings.boolean("module.$moduleId.enabled", genericEnabled[moduleId] ?: false)
                genericEnabled[moduleId] = next
                HypnosiaClientSettings.set("module.$moduleId.enabled", next.toString())
            }
        }
    }

    fun displayKey(keyCode: Int): String {
        val glfwName = GLFW.glfwGetKeyName(keyCode, 0)
        if (!glfwName.isNullOrBlank()) return glfwName.uppercase()
        return when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> "ESC"
            GLFW.GLFW_KEY_SPACE -> "SPACE"
            GLFW.GLFW_KEY_TAB -> "TAB"
            GLFW.GLFW_KEY_ENTER -> "ENTER"
            GLFW.GLFW_KEY_BACKSPACE -> "BACKSPACE"
            GLFW.GLFW_KEY_LEFT_SHIFT -> "L-SHIFT"
            GLFW.GLFW_KEY_RIGHT_SHIFT -> "R-SHIFT"
            GLFW.GLFW_KEY_LEFT_CONTROL -> "L-CTRL"
            GLFW.GLFW_KEY_RIGHT_CONTROL -> "R-CTRL"
            GLFW.GLFW_KEY_LEFT_ALT -> "L-ALT"
            GLFW.GLFW_KEY_RIGHT_ALT -> "R-ALT"
            GLFW.GLFW_KEY_UP -> "UP"
            GLFW.GLFW_KEY_DOWN -> "DOWN"
            GLFW.GLFW_KEY_LEFT -> "LEFT"
            GLFW.GLFW_KEY_RIGHT -> "RIGHT"
            GLFW.GLFW_KEY_DELETE -> "DEL"
            GLFW.GLFW_KEY_INSERT -> "INS"
            GLFW.GLFW_KEY_HOME -> "HOME"
            GLFW.GLFW_KEY_END -> "END"
            GLFW.GLFW_KEY_PAGE_UP -> "PGUP"
            GLFW.GLFW_KEY_PAGE_DOWN -> "PGDN"
            in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25 -> "F${keyCode - GLFW.GLFW_KEY_F1 + 1}"
            else -> "KEY$keyCode"
        }
    }

    fun reload() {
        loaded = false
        bindings.clear()
        pressed.clear()
        cachedActiveBindings = null
        ensureLoaded()
    }

    private fun toggleHud(module: HudModuleSettings.Module) {
        HudModuleSettings.setEnabled(module, !HudModuleSettings.isEnabled(module))
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            HypnosiaClientSettings.keys(KEY_PREFIX)
                .filter { it.endsWith(".key") }
                .forEach { keyName ->
                    val moduleId = keyName.removePrefix(KEY_PREFIX).removeSuffix(".key")
                    val keyCode = HypnosiaClientSettings.string(keyName, "").toIntOrNull() ?: return@forEach
                    val title = HypnosiaClientSettings.string("$KEY_PREFIX$moduleId.title", moduleId)
                    bindings[moduleId] = Binding(moduleId, title, keyCode)
                }
            cachedActiveBindings = null
        }
    }

    private fun save() {
        runCatching {
            val values = linkedMapOf<String, String?>()
            HypnosiaClientSettings.keys(KEY_PREFIX).forEach { key ->
                values[key] = null
            }
            bindings.values.forEach { binding ->
                values["$KEY_PREFIX${binding.moduleId}.title"] = binding.title
                values["$KEY_PREFIX${binding.moduleId}.key"] = binding.keyCode.toString()
            }
            HypnosiaClientSettings.setAll(values)
        }
    }
}
