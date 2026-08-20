package dev.hypnosia.ui.layout

import dev.hypnosia.HypnosiaClient
import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.config.HypnosiaConfigProfiles
import dev.hypnosia.hud.HudModuleSettings
import dev.hypnosia.hud.TargetHudSettings
import dev.hypnosia.hud.WatermarkSettings
import dev.hypnosia.license.AccountManager
import dev.hypnosia.license.AccountState
import dev.hypnosia.license.CloudConfigSummary
import dev.hypnosia.license.CloudListResult
import dev.hypnosia.license.CloudSaveResult
import dev.hypnosia.license.LicenseRole
import dev.hypnosia.other.FriendsManager
import dev.hypnosia.other.StreamerModeSettings
import dev.hypnosia.visual.AspectRatioSettings
import dev.hypnosia.visual.cosmetic.CosmeticSettings
import dev.hypnosia.visual.image.ImageRenderConfig
import dev.hypnosia.visual.world.particles.WorldParticleSettings
import dev.hypnosia.visual.image.ImageRenderModule
import dev.hypnosia.world.WorldVisualSettings
import dev.hypnosia.license.HypnosiaPaths
import java.nio.charset.StandardCharsets
import dev.hypnosia.ui.animation.AnimatedColor
import dev.hypnosia.ui.animation.AnimatedFloat
import dev.hypnosia.ui.animation.FigmaAnimation
import dev.hypnosia.ui.animation.TimedTransition
import dev.hypnosia.ui.render.FigmaTextRenderer
import dev.hypnosia.ui.render.HypnosiaScissor
import dev.hypnosia.ui.render.HypnosiaRenderUtils
import dev.hypnosia.ui.profile.HypnosiaPlaytime
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.entity.model.EntityModelLayers
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.util.Identifier
import org.joml.Matrix3x2f
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

object HypnosiaHomeV2Layout {
    private const val WINDOW_WIDTH = 698.0f
    private const val WINDOW_HEIGHT = 567.0f
    private const val PROFILE_WIDTH = 604.0f
    private const val PROFILE_HEIGHT = 370.0f

    private const val SEARCH_X = 0.0f
    private const val SEARCH_Y = 0.0f
    private const val SEARCH_WIDTH = 160.0f
    private const val SEARCH_HEIGHT = 32.0f

    private const val MAIN_X = 0.0f
    private const val MAIN_Y = 40.0f
    private const val MAIN_WIDTH = 698.0f
    private const val MAIN_HEIGHT = 464.0f

    private const val NAV_Y = 520.0f
    private const val SIDE_BUTTON_X = 18.0f
    private const val SIDE_BUTTON_WIDTH = 160.0f
    private const val BOTTOM_BUTTON_HEIGHT = 47.0f
    private const val NAV_X = 194.0f
    private const val NAV_WIDTH = 309.0f
    private const val SIDE_NAV_GAP = 16.0f

    private var selectedNavId = "hi"

    private lateinit var homeNode: HomeV2Node

    fun updateDrawer() = homeNode.updateDrawer()
    val drawerHeight: Float get() = homeNode.drawerHeight
    val selectedSettingsModuleId: String? get() = homeNode.selectedSettingsModuleId

    fun create(): FigmaRoot {
        HypnosiaConfigProfiles.bootstrap()
        val node = HomeV2Node()
        homeNode = node
        return FigmaRoot(
            designWidth = WINDOW_WIDTH,
            designHeight = WINDOW_HEIGHT,
            child = node,
            anchor = RootAnchor.Center,
            renderScale = 1.0f,
        )
    }

    internal class HomeV2Node : BaseUiNode(
        LayoutSpec(SizeMode.Fixed(WINDOW_WIDTH), SizeMode.Fixed(WINDOW_HEIGHT)),
    ) {
        private val navAnimations = NAV_ITEMS.associate { it.id to NavAnimation() }
        private val navRects = mutableMapOf<String, Rect>()
        private val rowIconAnimations = mutableMapOf<String, TimedTransition>()
        private var cloudConfigsCache: List<CloudConfigSummary> = emptyList()
        private var cloudCacheUsed = 0
        private var cloudCacheLimit = 3
        private var cloudCacheLoading = false
        private var cloudCacheLastRefresh = 0L
        private val friendRowRects = mutableMapOf<String, Rect>()
        private val configRowRects = mutableMapOf<String, Rect>()
        private val accountLocalRowRects = mutableMapOf<String, Rect>()
        private val accountCloudRowRects = mutableMapOf<String, Rect>()
        private var friendSearchRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var configSearchRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var accountCloudKeyRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var accountNameRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var accountContactRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var accountContactEyeRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var focusedField: String? = null
        private var friendSearchText = ""
        private var configSearchText = ""
        private var accountCloudKeyInput = ""
        private var accountNameInput = ""
        private var accountContactInput = ""
        private var accountInputsInitialized = false
        private var profileButtonRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var searchRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var searchClearRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var searchText = ""

        private val moduleCardRects = mutableMapOf<String, Rect>()
        private val moduleLeftButtonRects = mutableMapOf<String, Rect>()
        private val moduleRightButtonRects = mutableMapOf<String, Rect>()

        var selectedSettingsModuleId: String? = null
            private set
        private val settingsDrawer = V2ModuleSettingsDrawer(
            module = {
                val id = selectedSettingsModuleId
                modulesByCategory.values.flatten().find { it.id == id }?.let {
                    V2ModuleEntry(id = it.id, title = it.title, enabled = it.enabled, hasSettings = it.hasSettings)
                }
            },
            close = { selectedSettingsModuleId = null },
        )

        private enum class Category { Visuals, World, Client, Hud, Other }

        private data class ModuleEntry(
            val id: String,
            val category: Category,
            val title: String,
            var enabled: Boolean,
            val hasSettings: Boolean = true,
            var settingsOpen: Boolean = false,
        )

        private val modulesByCategory: Map<Category, List<ModuleEntry>> by lazy {
            mapOf(
                Category.Visuals to listOf(
                    ModuleEntry("visuals.aspect_ratio", Category.Visuals, "Aspect Ratio", AspectRatioSettings.isEnabled()),
                    ModuleEntry("visuals.cosmetics", Category.Visuals, "Cosmetics", CosmeticSettings.enabled()),
                    ModuleEntry("visuals.cosmetics.china_hat", Category.Visuals, "China Hat", CosmeticSettings.chinaHatEnabled(), hasSettings = true),
                    ModuleEntry("visuals.cosmetics.nimbus", Category.Visuals, "Nimbus", CosmeticSettings.nimbusEnabled(), hasSettings = true),
                ),
                Category.World to listOf(
                    ModuleEntry("world.fullbright", Category.World, "Fullbright", moduleEnabled("world.fullbright", moduleEnabled("visuals.fullbright", true)), hasSettings = false),
                    ModuleEntry("world.custom_fog", Category.World, "Custom Fog", WorldVisualSettings.customFogEnabled()),
                    ModuleEntry("world.particles", Category.World, "World Particles", WorldParticleSettings.enabled(), hasSettings = true),
                    ModuleEntry("world.hit_particles", Category.World, "Hit Particles", dev.hypnosia.visual.world.particles.hit.HitParticleSettings.enabled(), hasSettings = true),
                    ModuleEntry("world.target_esp", Category.World, "Target ESP", dev.hypnosia.visual.world.esp.TargetEspSettings.enabled(), hasSettings = true),
                    ModuleEntry("world.jump_circles", Category.World, "Jump Circles", dev.hypnosia.visual.world.jump.JumpCircleSettings.enabled(), hasSettings = true),
                    ModuleEntry("world.trails", Category.World, "Trails", dev.hypnosia.visual.world.trails.TrailSettings.enabled(), hasSettings = true),
                    ModuleEntry("world.hit_color", Category.World, "Hit Color", dev.hypnosia.visual.world.hitcolor.HitColorSettings.enabled(), hasSettings = true),
                ),
                Category.Client to listOf(
                    ModuleEntry("client.icons", Category.Client, "Icons", moduleEnabled("client.icons", true)),
                    ModuleEntry("client.images", Category.Client, "Images", ImageRenderConfig.isGlobalEnabled()),
                ),
                Category.Hud to listOf(
                    ModuleEntry("hud.watermark", Category.Hud, "Watermark", true),
                    ModuleEntry("hud.hotbar", Category.Hud, "HotBaR", HudModuleSettings.isEnabled(HudModuleSettings.Module.HOTBAR)),
                    ModuleEntry("hud.armor", Category.Hud, "Armor HUD", HudModuleSettings.isEnabled(HudModuleSettings.Module.ARMOR)),
                    ModuleEntry("hud.target", Category.Hud, "Target HUD", TargetHudSettings.isEnabled()),
                    ModuleEntry("hud.player_info", Category.Hud, "Player Info", HudModuleSettings.isEnabled(HudModuleSettings.Module.PLAYER_INFO)),
                    ModuleEntry("hud.inventory", Category.Hud, "Inventory HUD", HudModuleSettings.isEnabled(HudModuleSettings.Module.INVENTORY)),
                    ModuleEntry("hud.cooldowns", Category.Hud, "Cooldown HUD", HudModuleSettings.isEnabled(HudModuleSettings.Module.COOLDOWNS)),
                    ModuleEntry("hud.potions", Category.Hud, "Potions HUD", HudModuleSettings.isEnabled(HudModuleSettings.Module.POTIONS)),
                    ModuleEntry("hud.hotkeys", Category.Hud, "HotKey", HudModuleSettings.isEnabled(HudModuleSettings.Module.HOTKEYS)),
                ),
                Category.Other to listOf(
                    ModuleEntry("other.friends", Category.Other, "Friends", moduleEnabled("other.friends", true)),
                    ModuleEntry("other.streamer_mode", Category.Other, "Streamer Mode", StreamerModeSettings.isEnabled()),
                    ModuleEntry("other.discord_rpc", Category.Other, "Discord RPC", moduleEnabled("other.discord_rpc", false)),
                ),
            )
        }

        private fun moduleEnabled(key: String, default: Boolean = false): Boolean {
            return HypnosiaClientSettings.boolean("module.$key.enabled", default)
        }

        private fun toggleModule(id: String) {
            val entry = modulesByCategory.values.flatten().find { it.id == id } ?: return
            entry.enabled = !entry.enabled
            when (id) {
                "hud.hotbar" -> HudModuleSettings.setEnabled(HudModuleSettings.Module.HOTBAR, entry.enabled)
                "hud.armor" -> HudModuleSettings.setEnabled(HudModuleSettings.Module.ARMOR, entry.enabled)
                "visuals.cosmetics" -> CosmeticSettings.setEnabled(entry.enabled)
                "visuals.cosmetics.china_hat" -> CosmeticSettings.setChinaHatEnabled(entry.enabled)
                "visuals.cosmetics.nimbus" -> CosmeticSettings.setNimbusEnabled(entry.enabled)
                "world.particles" -> WorldParticleSettings.setEnabled(entry.enabled)
                "world.hit_particles" -> dev.hypnosia.visual.world.particles.hit.HitParticleSettings.setEnabled(entry.enabled)
                "world.target_esp" -> dev.hypnosia.visual.world.esp.TargetEspSettings.setEnabled(entry.enabled)
                "world.jump_circles" -> dev.hypnosia.visual.world.jump.JumpCircleSettings.setEnabled(entry.enabled)
                "world.trails" -> dev.hypnosia.visual.world.trails.TrailSettings.setEnabled(entry.enabled)
                "world.hit_color" -> dev.hypnosia.visual.world.hitcolor.HitColorSettings.setEnabled(entry.enabled)
                "hud.target" -> TargetHudSettings.setEnabled(entry.enabled)
                "hud.player_info" -> HudModuleSettings.setEnabled(HudModuleSettings.Module.PLAYER_INFO, entry.enabled)
                "hud.inventory" -> HudModuleSettings.setEnabled(HudModuleSettings.Module.INVENTORY, entry.enabled)
                "hud.cooldowns" -> HudModuleSettings.setEnabled(HudModuleSettings.Module.COOLDOWNS, entry.enabled)
                "hud.potions" -> HudModuleSettings.setEnabled(HudModuleSettings.Module.POTIONS, entry.enabled)
                "hud.hotkeys" -> HudModuleSettings.setEnabled(HudModuleSettings.Module.HOTKEYS, entry.enabled)
                "world.fullbright" -> WorldVisualSettings.setFullbrightEnabled(entry.enabled)
                "world.custom_fog" -> WorldVisualSettings.setCustomFogEnabled(entry.enabled)
                "visuals.aspect_ratio" -> AspectRatioSettings.setEnabled(entry.enabled)
                "client.images" -> ImageRenderConfig.setGlobalEnabled(entry.enabled)
                "other.friends" -> FriendsManager.setEnabled(entry.enabled)
                "other.streamer_mode" -> StreamerModeSettings.setEnabled(entry.enabled)
                else -> HypnosiaClientSettings.set("module.$id.enabled", entry.enabled.toString())
            }
        }

        // Registration screen state
        private var hwidAgreed = false
        private var isRegistering = false
        private var hwidCheckboxRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
        private var registerButtonRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)

        override fun measure(constraints: Constraints): Size = constraints.constrain(Size(WINDOW_WIDTH, WINDOW_HEIGHT))

        var drawerHeight: Float = 0.0f
            private set

        fun updateDrawer() {
            val client = MinecraftClient.getInstance()
            val window = client.window
            val guiScale = window.scaleFactor.toFloat().coerceAtLeast(1.0f)
            val scale = 1.0f / guiScale
            val screenW = window.scaledWidth / scale
            val drawerPreferredX = bounds.x + MAIN_X + MAIN_WIDTH + 16.0f
            val drawerFitsRight = drawerPreferredX + V2ModuleSettingsDrawer.WIDTH <= screenW
            val drawerPreferredLeftX = bounds.x + MAIN_X - V2ModuleSettingsDrawer.WIDTH - 16.0f
            val drawerFitsLeft = drawerPreferredLeftX >= 0.0f
            val drawerX = when {
                drawerFitsRight -> drawerPreferredX
                drawerFitsLeft -> drawerPreferredLeftX
                else -> bounds.x + MAIN_X + (MAIN_WIDTH - V2ModuleSettingsDrawer.WIDTH) * 0.5f
            }
            val maxDrawerY = bounds.y + NAV_Y - V2ModuleSettingsDrawer.HEIGHT - 8.0f
            val drawerY = (bounds.y + MAIN_Y).coerceAtMost(maxDrawerY)
            settingsDrawer.bounds = Rect(drawerX, drawerY, V2ModuleSettingsDrawer.WIDTH, V2ModuleSettingsDrawer.HEIGHT)
            drawerHeight = settingsDrawer.preRender()
            if (drawerHeight < 0.5f && selectedSettingsModuleId != null) {
                selectedSettingsModuleId = null
            }
        }

        override fun render(context: DrawContext) {
            renderSearch(context)
            renderMain(context)
            renderBottomBar(context)
            settingsDrawer.render(context)
        }

        override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
            if (button == 0) {
                if (settingsDrawer.bounds.height > 1.0f) {
                    if (settingsDrawer.mouseClicked(mouseX, mouseY, button)) return true
                }
                if (isModulePage()) {
                    moduleLeftButtonRects.entries.find { (_, rect) -> contains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height) }?.let { (id, _) ->
                        toggleModule(id)
                        return true
                    }

                    moduleRightButtonRects.entries.find { (_, rect) -> contains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height) }?.let { (id, _) ->
                        val entry = modulesByCategory.values.flatten().firstOrNull { it.id == id }
                        if (entry?.hasSettings == true) {
                            selectedSettingsModuleId = if (selectedSettingsModuleId == id) null else id
                        }
                        return true
                    }
                }

                if (contains(mouseX, mouseY, searchRect.x, searchRect.y, searchRect.width, searchRect.height)) {
                    focusedField = "search"
                    return true
                }
                if (searchText.isNotEmpty() && contains(mouseX, mouseY, searchClearRect.x, searchClearRect.y, searchClearRect.width, searchClearRect.height)) {
                    searchText = ""
                    return true
                }
                if (selectedNavId == "home") {
                    if (contains(mouseX, mouseY, friendSearchRect.x, friendSearchRect.y, friendSearchRect.width, friendSearchRect.height)) {
                        focusedField = "friend_search"
                        return true
                    }
                    if (contains(mouseX, mouseY, configSearchRect.x, configSearchRect.y, configSearchRect.width, configSearchRect.height)) {
                        focusedField = "config_search"
                        return true
                    }
                }
                focusedField = null

                if (selectedNavId == "home") {
                    friendRowRects.entries.find { (_, rect) -> contains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height) }?.let { (name, rect) ->
                        if (mouseX > rect.x + rect.width - 26.0f) {
                            FriendsManager.removeFriend(name)
                        }
                        return true
                    }

                    configRowRects.entries.find { (_, rect) -> contains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height) }?.let { (config, rect) ->
                        val isActive = config == HypnosiaConfigProfiles.selectedName()
                        val iconCount = if (isActive) 3 else 2
                        val iconAreaWidth = 20.0f * iconCount + 6.0f
                        if (mouseX <= rect.x + rect.width - iconAreaWidth) {
                            HypnosiaConfigProfiles.select(config)
                        }
                        return true
                    }
                }

                if (contains(mouseX, mouseY, profileButtonRect.x, profileButtonRect.y, profileButtonRect.width, profileButtonRect.height)) {
                    selectedNavId = "profile"
                    return true
                }

                if (selectedNavId == "account") {
                    accountLocalRowRects.entries.find { (_, rect) -> contains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height) }?.let { (config, rect) ->
                        val iconAreaWidth = 20.0f * 2 + 6.0f
                        if (mouseX > rect.x + rect.width - iconAreaWidth) {
                            val iconStartX = rect.x + rect.width - iconAreaWidth
                            val relativeX = mouseX - iconStartX
                            if (relativeX < 20.0f) {
                                AccountManager.saveCloudConfigAsync(config).thenAccept { result ->
                                    MinecraftClient.getInstance().execute {
                                        when (result) {
                                            is CloudSaveResult.Saved -> {
                                                cloudCacheLastRefresh = 0L
                                            }
                                            is CloudSaveResult.Error -> {
                                            }
                                        }
                                    }
                                }
                            } else {
                                HypnosiaConfigProfiles.delete(config)
                            }
                        }
                        return true
                    }

                    accountCloudRowRects.entries.find { (_, rect) -> contains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height) }?.let { (configKey, rect) ->
                        val iconAreaWidth = 20.0f * 2 + 6.0f
                        if (mouseX > rect.x + rect.width - iconAreaWidth) {
                            val iconStartX = rect.x + rect.width - iconAreaWidth
                            val relativeX = mouseX - iconStartX
                            if (relativeX < 20.0f) {
                                MinecraftClient.getInstance().keyboard.clipboard = configKey
                            } else {
                                AccountManager.deleteCloudConfigAsync(configKey)
                            }
                        }
                        return true
                    }

                    if (contains(mouseX, mouseY, accountCloudKeyRect.x, accountCloudKeyRect.y, accountCloudKeyRect.width, accountCloudKeyRect.height)) {
                        focusedField = "cloud_key"
                        return true
                    }

                    if (contains(mouseX, mouseY, accountContactEyeRect.x, accountContactEyeRect.y, accountContactEyeRect.width, accountContactEyeRect.height)) {
                        HypnosiaHomeV2Layout.contactBlurred = !HypnosiaHomeV2Layout.contactBlurred
                        return true
                    }

                    if (contains(mouseX, mouseY, accountNameRect.x, accountNameRect.y, accountNameRect.width, accountNameRect.height)) {
                        focusedField = "account_name"
                        return true
                    }

                    if (contains(mouseX, mouseY, accountContactRect.x, accountContactRect.y, accountContactRect.width, accountContactRect.height)) {
                        focusedField = "account_contact"
                        return true
                    }

                    // Registration screen interactions
                    if (AccountManager.state !is AccountState.Valid) {
                        if (contains(mouseX, mouseY, hwidCheckboxRect.x, hwidCheckboxRect.y, hwidCheckboxRect.width, hwidCheckboxRect.height)) {
                            hwidAgreed = !hwidAgreed
                            return true
                        }
                        if (hwidAgreed && contains(mouseX, mouseY, registerButtonRect.x, registerButtonRect.y, registerButtonRect.width, registerButtonRect.height)) {
                            if (!isRegistering) {
                                isRegistering = true
                                AccountManager.createAsync().thenAccept { state ->
                                    isRegistering = false
                                    if (state is AccountState.Valid) {
                                        accountInputsInitialized = false
                                    }
                                }
                            }
                            return true
                        }
                    }
                }

                NAV_ITEMS.firstOrNull { item ->
                    val rect = navRects[item.id]
                    if (rect != null) {
                        contains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height)
                    } else {
                        contains(mouseX, mouseY, bounds.x + item.buttonX, bounds.y + item.buttonY, BASE_NAV_BUTTON_SIZE, BASE_NAV_BUTTON_SIZE)
                    }
                }?.let { item ->
                    selectedNavId = item.id
                    return true
                }

            }

            return contains(mouseX, mouseY, bounds.x, bounds.y, WINDOW_WIDTH, WINDOW_HEIGHT)
        }

        private fun isModulePage(): Boolean {
            return selectedNavId == "visuals" || selectedNavId == "world" || selectedNavId == "client" || selectedNavId == "hud" || selectedNavId == "other"
        }

        override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
            if (settingsDrawer.bounds.height > 1.0f) {
                if (settingsDrawer.mouseReleased(mouseX, mouseY, button)) return true
            }
            return false
        }

        override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int, deltaX: Float, deltaY: Float): Boolean {
            if (settingsDrawer.bounds.height > 1.0f) {
                if (settingsDrawer.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true
            }
            return false
        }

        override fun mouseScrolled(mouseX: Float, mouseY: Float, horizontalAmount: Float, verticalAmount: Float): Boolean {
            if (settingsDrawer.bounds.height > 1.0f) {
                if (settingsDrawer.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
            }
            return false
        }

        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
            if (settingsDrawer.bounds.height > 1.0f) {
                if (settingsDrawer.keyPressed(keyCode, scanCode, modifiers)) return true
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && selectedSettingsModuleId != null) {
                selectedSettingsModuleId = null
                return true
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_V && (modifiers and org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL != 0)) {
                val text = MinecraftClient.getInstance().keyboard.clipboard
                when (focusedField) {
                    "cloud_key" -> {
                        val filtered = text.filter { it.isLetterOrDigit() }.uppercase().take(8)
                        accountCloudKeyInput = filtered
                        return true
                    }
                    "account_name" -> {
                        val remaining = 32 - accountNameInput.length
                        if (remaining > 0) accountNameInput += text.take(remaining)
                        return true
                    }
                    "account_contact" -> {
                        val remaining = 64 - accountContactInput.length
                        if (remaining > 0) accountContactInput += text.take(remaining)
                        return true
                    }
                    "search" -> {
                        searchText += text
                        return true
                    }
                    "config_search" -> {
                        configSearchText += text
                        return true
                    }
                    "friend_search" -> {
                        friendSearchText += text
                        return true
                    }
                }
            }
            when (focusedField) {
                "friend_search" -> when (keyCode) {
                    257 -> {
                        if (friendSearchText.isNotBlank()) {
                            FriendsManager.addFriend(friendSearchText)
                            friendSearchText = ""
                        }
                        return true
                    }
                    259 -> {
                        if (friendSearchText.isNotEmpty()) friendSearchText = friendSearchText.dropLast(1)
                        return true
                    }
                }
                "config_search" -> when (keyCode) {
                    257 -> {
                        if (configSearchText.isNotBlank()) {
                            HypnosiaConfigProfiles.create(configSearchText)
                            configSearchText = ""
                        }
                        return true
                    }
                    259 -> {
                        if (configSearchText.isNotEmpty()) configSearchText = configSearchText.dropLast(1)
                        return true
                    }
                }
                "cloud_key" -> when (keyCode) {
                    257 -> {
                        val key = accountCloudKeyInput.trim().uppercase()
                        if (key.length == 8 && key.all { it.isLetterOrDigit() }) {
                            AccountManager.loadCloudConfigAsync(key, key)
                            accountCloudKeyInput = ""
                        }
                        return true
                    }
                    259 -> {
                        if (accountCloudKeyInput.isNotEmpty()) accountCloudKeyInput = accountCloudKeyInput.dropLast(1)
                        return true
                    }
                }
                "account_name" -> when (keyCode) {
                    257 -> {
                        if (accountNameInput.isNotBlank()) {
                            AccountManager.setNameAsync(accountNameInput).thenAccept { result ->
                                if (result is AccountState.Valid) {
                                    accountNameInput = result.session.displayName ?: ""
                                }
                            }
                        }
                        return true
                    }
                    259 -> {
                        if (accountNameInput.isNotEmpty()) accountNameInput = accountNameInput.dropLast(1)
                        return true
                    }
                }
                "account_contact" -> when (keyCode) {
                    257 -> {
                        if (accountContactInput.isNotBlank()) {
                            AccountManager.setContactAsync(accountContactInput).thenAccept { result ->
                                if (result is AccountState.Valid) {
                                    accountContactInput = result.session.contact ?: ""
                                }
                            }
                        }
                        return true
                    }
                    259 -> {
                        if (accountContactInput.isNotEmpty()) accountContactInput = accountContactInput.dropLast(1)
                        return true
                    }
                }
                "search" -> when (keyCode) {
                    259 -> {
                        if (searchText.isNotEmpty()) searchText = searchText.dropLast(1)
                        return true
                    }
                }
            }
            return false
        }

        override fun charTyped(chr: Char, modifiers: Int): Boolean {
            if (settingsDrawer.bounds.height > 1.0f) {
                if (settingsDrawer.charTyped(chr, modifiers)) return true
            }
            if (chr.code < 32 || chr == '\u007F') return false
            when (focusedField) {
                "friend_search" -> {
                    friendSearchText += chr
                    return true
                }
                "config_search" -> {
                    configSearchText += chr
                    return true
                }
                "cloud_key" -> {
                    if (accountCloudKeyInput.length < 8 && chr.isLetterOrDigit()) {
                        accountCloudKeyInput += chr.uppercase()
                    }
                    return true
                }
                "account_name" -> {
                    if (accountNameInput.length < 32) accountNameInput += chr
                    return true
                }
                "account_contact" -> {
                    if (accountContactInput.length < 64) accountContactInput += chr
                    return true
                }
                "search" -> {
                    if (chr.code < 32 || chr == '\u007F') return false
                    searchText += chr
                    return true
                }
            }
            return false
        }

        override fun onScreenClose() {
            selectedSettingsModuleId = null
            settingsDrawer.onScreenClose()
        }

        private fun renderSearch(context: DrawContext) {
            val x = bounds.x + SEARCH_X
            val y = bounds.y + SEARCH_Y
            val isFocused = focusedField == "search"
            val displayText = if (searchText.isNotEmpty()) searchText else if (isFocused) "" else "search"
            val textW = FigmaTextRenderer.width(displayText, SEARCH_TEXT)
            val iconArea = 36.0f
            val clearArea = if (searchText.isNotEmpty()) 32.0f else 8.0f
            val fieldWidth = maxOf(SEARCH_WIDTH, iconArea + textW + clearArea).coerceAtMost(WINDOW_WIDTH)

            box(context, x, y, fieldWidth, SEARCH_HEIGHT, 10.0f)
            icon(context, "v2_search.png", x + 4.0f, y + 4.0f, 24.0f, 24.0f)
            val textColor = if (searchText.isNotEmpty() || isFocused) WHITE else MUTED_DARK
            val textAreaWidth = fieldWidth - iconArea - clearArea
            drawTextBox(
                context = context,
                text = displayText,
                x = x + iconArea,
                y = y,
                width = textAreaWidth,
                height = SEARCH_HEIGHT,
                color = textColor,
                style = SEARCH_TEXT,
                horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
            )
            searchRect = Rect(x, y, fieldWidth, SEARCH_HEIGHT)
            if (searchText.isNotEmpty()) {
                searchClearRect = Rect(x + fieldWidth - 32.0f, y + 4.0f, 24.0f, 24.0f)
                drawTextBox(context, "✕", searchClearRect.x, searchClearRect.y, searchClearRect.width, searchClearRect.height, TEXT_MUTED, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
            } else {
                searchClearRect = Rect(0.0f, 0.0f, 0.0f, 0.0f)
            }
            if (isFocused && (System.currentTimeMillis() / 500) % 2 == 0L) {
                val caretX = x + iconArea + FigmaTextRenderer.width(displayText, SEARCH_TEXT)
                rect(context, caretX, y + 8.0f, 1.0f, 16.0f, WHITE)
            }
        }

        private fun renderMain(context: DrawContext) {
            val x = bounds.x + MAIN_X
            val y = bounds.y + MAIN_Y
            box(context, x, y, MAIN_WIDTH, MAIN_HEIGHT, 10.0f)

            when (selectedNavId) {
                "hi" -> renderWelcomeChapter(context, x, y)
                "home" -> renderHomePage(context, x, y)
                "account" -> renderAccountPage(context, x, y)
                "profile" -> {
                    val panelX = x + (MAIN_WIDTH - PROFILE_WIDTH) * 0.5f
                    val panelY = y + (MAIN_HEIGHT - PROFILE_HEIGHT) * 0.5f
                    renderProfileActivity(context, panelX, panelY)
                }
                "visuals" -> renderModuleGrid(context, x, y, Category.Visuals)
                "world" -> renderModuleGrid(context, x, y, Category.World)
                "client" -> renderModuleGrid(context, x, y, Category.Client)
                "hud" -> renderModuleGrid(context, x, y, Category.Hud)
                "other" -> renderModuleGrid(context, x, y, Category.Other)
                else -> renderPlaceholderPage(context, x, y, chapterLabel())
            }
        }

        private fun chapterLabel(): String {
            return when (selectedNavId) {
                "hi" -> "HI"
                "profile" -> "Profile"
                else -> NAV_ITEMS.firstOrNull { it.id == selectedNavId }?.label ?: "Home"
            }
        }

        private fun renderWelcomeChapter(context: DrawContext, x: Float, y: Float) {
            welcomeAsset(context, "welcome_title.png", x + 257.0f, y + 131.125f, 183.0f, 45.75f)
            welcomeAsset(context, "black_hole.png", x + 317.0f, y + 200.0f, 64.0f, 64.0f)
            welcomeAsset(context, "hypnosia_visuals_title.png", x + 158.75f, y + 285.5f, 379.5f, 49.0f)
            welcomeAsset(context, "signature_by_nachosia.png", x + 18.0f, y + 382.0f, 159.51f, 61.97f)
        }

        private fun renderBottomBar(context: DrawContext) {
            val y = bounds.y + NAV_Y
            val navLayout = buildNavLayout()
            val sideX = navLayout.dockX - SIDE_NAV_GAP - SIDE_BUTTON_WIDTH
            val profileX = navLayout.dockX + navLayout.dockWidth + SIDE_NAV_GAP

            box(context, sideX, y, SIDE_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT, 10.0f)
            drawTextBox(
                context = context,
                text = chapterLabel(),
                x = sideX + 5.0f,
                y = y + 9.0f,
                width = SIDE_BUTTON_WIDTH - 10.0f,
                height = 30.0f,
                color = WHITE,
                style = BOTTOM_TITLE,
                horizontalAlign = FigmaTextRenderer.HorizontalAlign.Center,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
            )

            box(context, navLayout.dockX, navLayout.dockY, navLayout.dockWidth, navLayout.dockHeight, 10.0f)
            navRects.clear()
            navLayout.items.forEach { visual ->
                navRects[visual.item.id] = visual.rect
                renderNavItem(context, visual)
            }

            val session = (AccountManager.state as? AccountState.Valid)?.session
            val nick = session?.displayName?.takeIf { it.isNotBlank() } ?: "Nick"
            val primaryRole = session?.roles?.firstOrNull { it.name != "USER" }
                ?: session?.roles?.firstOrNull()
                ?: LicenseRole.USER
            val role = LicenseRole.displayName(primaryRole)
            val nickGrad = parseGradientColors(session?.nickGradients?.get(primaryRole.name))
            val roleGrad = parseGradientColors(session?.roleGradients?.get(primaryRole.name))
            val time = (System.currentTimeMillis() % 1000000L) / 1000f

            profileButtonRect = Rect(profileX, y, SIDE_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT)
            box(context, profileX, y, SIDE_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT, 10.0f)
            if (nickGrad.size >= 2) {
                FigmaTextRenderer.drawGradientInBox(
                    context = context, text = nick,
                    x = profileX + 7.0f, y = y + 5.0f, width = 146.0f, height = 16.0f,
                    color = WHITE, style = PROFILE_TEXT,
                    gradientColor1 = nickGrad[0], gradientColor2 = nickGrad[1], time = time,
                    horizontalAlign = FigmaTextRenderer.HorizontalAlign.Center,
                    verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
                    fallbackColor = WHITE,
                )
            } else {
                drawTextBox(context, nick, profileX + 7.0f, y + 5.0f, 146.0f, 16.0f, WHITE, PROFILE_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
            }
            if (roleGrad.size >= 2) {
                FigmaTextRenderer.drawGradientInBox(
                    context = context, text = role,
                    x = profileX + 7.0f, y = y + 26.0f, width = 146.0f, height = 16.0f,
                    color = WHITE, style = PROFILE_TEXT,
                    gradientColor1 = roleGrad[0], gradientColor2 = roleGrad[1], time = time,
                    horizontalAlign = FigmaTextRenderer.HorizontalAlign.Center,
                    verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
                    fallbackColor = WHITE,
                )
            } else {
                drawTextBox(context, role, profileX + 7.0f, y + 26.0f, 146.0f, 16.0f, WHITE, PROFILE_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
            }
        }

        private fun buildNavLayout(): NavLayout {
            val dockHovered = contains(UiInputState.mouseX, UiInputState.mouseY, bounds.x + NAV_X, bounds.y + NAV_Y, NAV_WIDTH, BOTTOM_BUTTON_HEIGHT)
            val seconds = UiInputState.frameSeconds
            val visuals = NAV_ITEMS.map { item ->
                val animation = navAnimations.getValue(item.id)
                val centerX = bounds.x + item.buttonX + BASE_NAV_BUTTON_SIZE * 0.5f
                val targetBoost = if (dockHovered && UiInputState.mouseX.isFinite()) {
                    val distance = abs(UiInputState.mouseX - centerX)
                    val linear = (1.0f - distance / NAV_HOVER_RANGE).coerceIn(0.0f, 1.0f)
                    1.0f - (1.0f - linear) * (1.0f - linear) * (1.0f - linear)
                } else {
                    0.0f
                }

                animation.boost.target = targetBoost
                val boost = animation.boost.update(seconds)
                val iconScale = 1.0f + NAV_ICON_BOOST * boost
                val size = BASE_NAV_BUTTON_SIZE + NAV_MAX_BOOST * boost
                NavVisual(item, Rect(0.0f, 0.0f, size, size), boost, iconScale)
            }

            val totalItemsWidth = visuals.sumOf { it.rect.width.toDouble() }.toFloat() + NAV_ITEM_GAP * (visuals.size - 1).coerceAtLeast(0)
            val dockWidth = maxOf(NAV_WIDTH, totalItemsWidth + NAV_DOCK_PADDING * 2.0f)
            val dockX = bounds.x + NAV_X + (NAV_WIDTH - dockWidth) * 0.5f
            val dockHeight = maxOf(BOTTOM_BUTTON_HEIGHT, visuals.maxOf { it.rect.height } + NAV_DOCK_VERTICAL_PADDING * 2.0f)
            var nextX = dockX + (dockWidth - totalItemsWidth) * 0.5f
            val centerY = bounds.y + NAV_Y + BOTTOM_BUTTON_HEIGHT * 0.5f
            val dockY = centerY - dockHeight * 0.5f

            return NavLayout(
                dockX = dockX,
                dockY = dockY,
                dockWidth = dockWidth,
                dockHeight = dockHeight,
                items = visuals.map { visual ->
                    val rect = Rect(nextX, centerY - visual.rect.height * 0.5f, visual.rect.width, visual.rect.height)
                    nextX += visual.rect.width + NAV_ITEM_GAP
                    visual.copy(rect = rect)
                },
            )
        }

        private fun parseGradientColors(gradientStr: String?): List<Int> {
            if (gradientStr.isNullOrBlank()) return emptyList()
            val hexRegex = Regex("#([A-Fa-f0-9]{6})")
            return hexRegex.findAll(gradientStr).map { match ->
                val hex = match.groupValues[1]
                0xFF000000.toInt() or hex.toInt(16)
            }.toList()
        }

        private fun renderNavItem(context: DrawContext, visual: NavVisual) {
            val item = visual.item
            val rect = visual.rect
            val hovered = contains(UiInputState.mouseX, UiInputState.mouseY, rect.x, rect.y, rect.width, rect.height)
            val selected = selectedNavId == item.id
            val active = hovered || selected

            val navBg = when {
                selected -> 0xFF1A1A1A.toInt()
                hovered -> 0xFF1A1A1A.toInt()
                else -> 0xFF0D0D0D.toInt()
            }
            val navStroke = when {
                selected -> 0xFF80FF97.toInt()
                hovered -> 0xFF80FF97.toInt()
                else -> 0xFF333333.toInt()
            }

            val iconWidth = item.iconWidth * visual.iconScale
            val iconHeight = item.iconHeight * visual.iconScale
            val iconCenterX = rect.x + rect.width * 0.5f
            val iconCenterY = rect.y + rect.height * 0.5f
            val iconFile = if (active) item.hoverIcon else item.icon

            HypnosiaRenderUtils.drawFigmaBox(
                context,
                rect.x,
                rect.y,
                rect.width,
                rect.height,
                10.0f,
                navBg,
                navStroke,
                1.0f,
            )
            icon(
                context = context,
                fileName = iconFile,
                x = iconCenterX - iconWidth * 0.5f,
                y = iconCenterY - iconHeight * 0.5f,
                width = iconWidth,
                height = iconHeight,
                tint = WHITE,
            )
        }

        private fun renderHomePage(context: DrawContext, x: Float, y: Float) {
            val contentX = x + CONTENT_PADDING
            val contentY = y + CONTENT_PADDING
            val contentH = MAIN_HEIGHT - CONTENT_PADDING * 2.0f
            val columnW = 300.0f
            val dividerX = contentX + columnW + 24.0f
            val rightX = dividerX + 25.0f

            renderHomeFriendsColumn(context, contentX, contentY, columnW, contentH)
            rect(context, dividerX, contentY, 1.0f, contentH, DIVIDER)
            renderHomeConfigsColumn(context, rightX, contentY, columnW, contentH)
        }

        private fun renderHomeFriendsColumn(context: DrawContext, x: Float, y: Float, width: Float, height: Float) {
            drawLabel(context, "FRIENDS", x, y, width)
            friendSearchRect = Rect(x, y + 27.0f, width, 28.0f)
            renderInput(context, x, y + 27.0f, width, "Search or add...", "friend_search", friendSearchText)
            drawSmall(context, "Press Enter to add", x + 1.0f, y + 60.0f, width, MUTED_DARK)

            val allNames = FriendsManager.listFriends()
            val names = if (friendSearchText.isNotEmpty()) allNames.filter { it.contains(friendSearchText, ignoreCase = true) } else allNames
            friendRowRects.clear()
            var rowY = y + 82.0f
            names.forEach { name ->
                friendRowRects[name] = Rect(x, rowY, width, 34.0f)
                renderTextRow(context, name, x, rowY, width, active = false, actionIcons = listOf("remove_friend.png"), rowId = "friend:$name")
                rowY += 34.0f
                if (rowY + 28.0f > y + height) return@forEach
            }
        }

        private fun renderHomeConfigsColumn(context: DrawContext, x: Float, y: Float, width: Float, height: Float) {
            drawLabel(context, "CONFIG MANAGER", x, y, width)
            configSearchRect = Rect(x, y + 27.0f, width, 28.0f)
            renderInput(context, x, y + 27.0f, width, "Search or add Cfg...", "config_search", configSearchText)
            drawSmall(context, "Press Enter to add", x + 1.0f, y + 60.0f, width, MUTED_DARK)

            val allConfigs = HypnosiaConfigProfiles.listNames()
            val selected = HypnosiaConfigProfiles.selectedName()
            val configs = if (configSearchText.isNotEmpty()) allConfigs.filter { it.contains(configSearchText, ignoreCase = true) } else allConfigs
            configRowRects.clear()
            var rowY = y + 82.0f
            configs.forEach { config ->
                val isActive = config == selected
                val hasImages = configHasImages(config)
                configRowRects[config] = Rect(x, rowY, width, 34.0f)
                renderTextRow(
                    context, config, x, rowY, width,
                    active = isActive,
                    actionIcons = listOf("save.png", "delete.png"),
                    rowId = "config:$config",
                    textColor = if (hasImages) 0xFF4D40FF.toInt() else null,
                )
                rowY += 34.0f
                if (rowY + 28.0f > y + height) return@forEach
            }
        }

        private fun configHasImages(configName: String): Boolean {
            return runCatching {
                val file = HypnosiaPaths.configsDir.resolve("$configName.json")
                if (!java.nio.file.Files.exists(file)) return false
                val json = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)
                val entriesMatch = Regex(""""image\.entries"\s*:\s*"([^"]+)"""").find(json)
                val entries = entriesMatch?.groupValues?.get(1)
                !entries.isNullOrBlank() && entries.split(",").any { it.trim().isNotBlank() }
            }.getOrDefault(false)
        }

        private fun renderAccountPage(context: DrawContext, x: Float, y: Float) {
            val contentX = x + CONTENT_PADDING
            val contentY = y + CONTENT_PADDING
            val contentH = MAIN_HEIGHT - CONTENT_PADDING * 2.0f
            val profileW = 300.0f
            val dividerX = contentX + profileW
            val rightX = dividerX + 24.0f
            val rightW = x + MAIN_WIDTH - CONTENT_PADDING - rightX

            renderAccountProfile(context, contentX, contentY, profileW - 24.0f, contentH)
            rect(context, dividerX, contentY, 1.0f, contentH, DIVIDER)
            renderAccountConfigs(context, rightX, contentY, rightW, contentH)
        }

        private fun renderAccountProfile(context: DrawContext, x: Float, y: Float, width: Float, height: Float) {
            if (AccountManager.state !is AccountState.Valid) {
                renderAccountRegistration(context, x, y, width, height)
                return
            }

            val session = (AccountManager.state as? AccountState.Valid)?.session
            if (!accountInputsInitialized) {
                accountNameInput = session?.displayName ?: ""
                accountContactInput = session?.contact ?: ""
                accountInputsInitialized = true
            }

            drawLabel(context, "ПРОФИЛЬ АККАУНТА", x, y, width)
            renderKeyValue(context, "ID Аккаунта:", session?.accountId?.toString() ?: "—", x, y + 32.0f, width)
            val createdAt = session?.createdAt?.take(10) ?: "—"
            renderKeyValue(context, "Создан:", createdAt, x, y + 56.0f, width)
            rect(context, x, y + 82.0f, width, 1.0f, DIVIDER)
            drawTextBox(context, "Роли:", x, y + 94.0f, width, 14.0f, MUTED_DARK, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)

            val roleColors = mapOf(
                "OWNER" to 0xFFFFD700.toInt(),
                "ADMIN" to 0xFFFF3333.toInt(),
                "QA" to 0xFF00E5FF.toInt(),
                "SPONSOR" to 0xFFFFA500.toInt(),
                "USER" to TEXT_MUTED,
            )
            val userRoles = AccountManager.sessionRoles.let { roles ->
                if (roles.size > 1) roles.filter { it.name != "USER" } else roles
            }

            var chipX = x
            var chipY = y + 114.0f
            userRoles.forEach { role ->
                val gradient = session?.roleGradients?.get(role.name)
                val textColor = gradient?.firstHexColor() ?: roleColors[role.name] ?: TEXT_MUTED
                val textW = FigmaTextRenderer.width(LicenseRole.displayName(role), CHIP_TEXT)
                val chipW = textW + 16.0f
                if (chipX + chipW > x + width) {
                    chipX = x
                    chipY += 17.0f + 4.0f
                }
                fieldBox(context, chipX, chipY, chipW, 17.0f, 4.0f, FIELD, BORDER_DARK, 1.0f)
                drawTextBox(context, LicenseRole.displayName(role), chipX + 3.0f, chipY + 1.0f, chipW - 6.0f, 14.0f, textColor, CHIP_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
                chipX += chipW + 6.0f
            }

            renderProfileInput(context, x, y + 154.0f, width, "Введите имя...", "account_name", accountNameInput)
            accountNameRect = Rect(x, y + 154.0f, width, 36.0f)

            renderProfileInput(context, x, y + 202.0f, width, "Введите контакт...", "account_contact", accountContactInput, blurred = HypnosiaHomeV2Layout.contactBlurred, trailingIcon = "eye.png")
            accountContactRect = Rect(x, y + 202.0f, width - 34.0f, 36.0f)
            accountContactEyeRect = Rect(x + width - 34.0f, y + 202.0f, 34.0f, 36.0f)

            drawSmall(context, "Без привязанного аккаунта TG или DC", x, y + height - 72.0f, width, MUTED_DARK)
            drawSmall(context, "восстановление невозможно.", x, y + height - 58.0f, width, MUTED_DARK)
            drawSmall(context, "Для переноса обращайтесь:", x, y + height - 36.0f, width, MUTED_DARK)
            drawSmall(context, "DS: nachosia", x, y + height - 20.0f, width * 0.5f, TEXT_MUTED)
            drawSmall(context, "TG: @Hypnosia_NSXS", x + width * 0.52f, y + height - 20.0f, width * 0.48f, TEXT_MUTED)
        }

        private fun renderAccountRegistration(context: DrawContext, x: Float, y: Float, width: Float, height: Float) {
            drawLabel(context, "РЕГИСТРАЦИЯ АККАУНТА", x, y, width)

            // HWID notice box
            val noticeY = y + 28.0f
            fieldBox(context, x, noticeY, width, 64.0f, 8.0f, FIELD, BORDER_DARK, 1.0f)
            drawTextBox(context, "При регистрации вы подтверждаете согласие на привязку вашего HWID устройства и сбор статистики о времени в игре.", x + 8.0f, noticeY + 4.0f, width - 16.0f, 56.0f, MUTED_DARK, SMALL_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)

            // Checkbox
            val checkboxY = noticeY + 76.0f
            val checkboxSize = 16.0f
            val checkColor = if (hwidAgreed) 0xFF4F46E5.toInt() else BORDER_DARK
            val checkFill = if (hwidAgreed) 0xFF4F46E5.toInt() else FIELD
            fieldBox(context, x, checkboxY, checkboxSize, checkboxSize, 4.0f, checkFill, checkColor, 1.5f)
            if (hwidAgreed) {
                drawTextBox(context, "✓", x, checkboxY, checkboxSize, checkboxSize, WHITE, SMALL_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
            }
            hwidCheckboxRect = Rect(x, checkboxY, checkboxSize, checkboxSize)
            drawTextBox(context, "Я согласен с условиями использования HWID и учетом времени", x + checkboxSize + 8.0f, checkboxY, width - checkboxSize - 8.0f, checkboxSize, MUTED_DARK, SMALL_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)

            // Register button
            val buttonY = y + height - 48.0f
            val buttonH = 40.0f
            val buttonFill = when {
                !hwidAgreed -> FIELD
                else -> 0xFF4F46E5.toInt()
            }
            val buttonStroke = when {
                !hwidAgreed -> BORDER_DARK
                else -> buttonFill
            }
            val buttonTextColor = if (hwidAgreed) WHITE else 0xFF444444.toInt()
            fieldBox(context, x, buttonY, width, buttonH, 10.0f, buttonFill, buttonStroke, 1.0f)
            drawTextBox(context, if (isRegistering) "СОЗДАНИЕ..." else "СОЗДАТЬ АККАУНТ", x, buttonY, width, buttonH, buttonTextColor, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
            registerButtonRect = Rect(x, buttonY, width, buttonH)
        }

        private fun renderAccountConfigs(context: DrawContext, x: Float, y: Float, width: Float, height: Float) {
            val topH = (height - 17.0f) * 0.5f
            drawLabel(context, "ЛОКАЛЬНЫЕ КОНФИГИ", x, y, width)

            renderInput(context, x, y + 26.0f, width, "Вставьте ключ облака...", "cloud_key", accountCloudKeyInput, iconName = null)
            iconAction(context, "tags.png", x + width - 22.0f, y + 32.0f, 16.0f, MUTED_DARK)
            accountCloudKeyRect = Rect(x, y + 26.0f, width, 28.0f)

            var rowY = y + 64.0f
            val localConfigs = HypnosiaConfigProfiles.listNames()
            accountLocalRowRects.clear()
            localConfigs.forEach { config ->
                renderTextRow(context, config, x, rowY, width, active = false, actionIcons = listOf("copy.png", "delete.png"), compact = true, rowId = "local:$config")
                accountLocalRowRects[config] = Rect(x, rowY, width, 26.0f)
                rowY += 28.0f
                if (rowY + 24.0f > y + topH) return@forEach
            }

            val dividerY = y + topH + 8.0f
            rect(context, x, dividerY, width, 1.0f, DIVIDER)
            drawLabel(context, "ОБЛАЧНЫЕ КОНФИГИ", x, dividerY + 18.0f, width - 50.0f)

            val session = (AccountManager.state as? AccountState.Valid)?.session
            val cloudUsed = session?.cloudUsed ?: cloudCacheUsed
            val cloudLimit = session?.cloudLimit ?: cloudCacheLimit
            drawSmall(context, "$cloudUsed/$cloudLimit", x + width - 42.0f, dividerY + 18.0f, 42.0f, MUTED_DARK, FigmaTextRenderer.HorizontalAlign.Right)

            refreshCloudConfigsIfNeeded()
            val cloudConfigs = cloudConfigsCache
            rowY = dividerY + 44.0f
            accountCloudRowRects.clear()
            if (cloudConfigs.isEmpty()) {
                drawSmall(context, "Нет облачных конфигов", x + 1.0f, rowY, width, MUTED_DARK)
            } else {
                cloudConfigs.forEach { config ->
                    val typeLabel = config.configType?.takeIf { it.isNotBlank() }
                    val cleanName = config.name.replace(Regex("""\s*\[(true|false)]$"""), "")
                    val displayName = if (typeLabel != null) "$cleanName  [$typeLabel]" else cleanName
                    val textColor = when (config.configType) {
                        "GIF" -> 0xFFFF69B4.toInt()
                        "PNG" -> 0xFF9370DB.toInt()
                        else -> null
                    }
                    renderTextRow(context, displayName, x, rowY, width, active = false, actionIcons = listOf("copy.png", "delete.png"), compact = true, rowId = "cloud:${config.configKey}", textColor = textColor)
                    accountCloudRowRects[config.configKey] = Rect(x, rowY, width, 26.0f)
                    rowY += 28.0f
                    if (rowY + 24.0f > y + height) return@forEach
                }
            }
        }

        private fun renderModuleGrid(context: DrawContext, x: Float, y: Float, category: Category) {
            val modules = if (searchText.isNotEmpty()) {
                modulesByCategory.values.flatten().filter {
                    it.title.contains(searchText, ignoreCase = true) ||
                    it.id.contains(searchText, ignoreCase = true) ||
                    it.category.name.contains(searchText, ignoreCase = true)
                }
            } else {
                modulesByCategory[category] ?: return
            }

            if (modules.isEmpty()) {
                drawTextBox(
                    context = context,
                    text = "Ничего не найдено",
                    x = x,
                    y = y + MAIN_HEIGHT * 0.5f - 10.0f,
                    width = MAIN_WIDTH,
                    height = 20.0f,
                    color = TEXT_MUTED,
                    style = BODY_TEXT,
                    horizontalAlign = FigmaTextRenderer.HorizontalAlign.Center,
                    verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
                )
                return
            }

            moduleCardRects.clear()
            moduleLeftButtonRects.clear()
            moduleRightButtonRects.clear()

            val startX = x + 27.0f
            val startY = y + 21.0f
            val cardW = 204.0f
            val cardH = 62.0f
            val gapX = 16.0f
            val gapY = 21.0f
            val cols = 3

            modules.forEachIndexed { index, entry ->
                val col = index % cols
                val row = index / cols
                val cardX = startX + col * (cardW + gapX)
                val cardY = startY + row * (cardH + gapY)

                val cardBg = 0xFF0D0D0D.toInt()
                val cardStroke = if (entry.enabled) WHITE else 0xFF272727.toInt()
                HypnosiaRenderUtils.drawFigmaBox(context, cardX, cardY, cardW, cardH, 5.0f, cardBg, cardStroke, 1.0f)

                val headerColor = if (entry.enabled) WHITE else 0xFF191919.toInt()
                HypnosiaRenderUtils.drawFigmaBox(context, cardX, cardY, cardW, 20.0f, 5.0f, headerColor, 0x00000000, 0.0f)
                HypnosiaRenderUtils.drawFigmaBox(context, cardX, cardY + 10.0f, cardW, 10.0f, 0.0f, headerColor, 0x00000000, 0.0f)

                val titleColor = if (entry.enabled) 0xFF000000.toInt() else WHITE
                drawTextBox(
                    context = context,
                    text = entry.title,
                    x = cardX + 5.0f,
                    y = cardY + 4.0f,
                    width = 194.0f,
                    height = 15.0f,
                    color = titleColor,
                    style = MODULE_TITLE,
                    horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                    verticalAlign = FigmaTextRenderer.VerticalAlign.Center,
                )

                val frameStroke = if (entry.enabled) WHITE else 0xFF272727.toInt()
                val leftFrameFill = if (entry.enabled) WHITE else 0x00000000
                val leftFrameX = cardX + 8.0f
                val leftFrameY = cardY + 28.0f
                HypnosiaRenderUtils.drawFigmaBox(context, leftFrameX, leftFrameY, 26.0f, 26.0f, 5.0f, leftFrameFill, frameStroke, 1.0f)
                val plugTint = if (entry.enabled) WHITE else 0xFF848484.toInt()
                drawModuleIcon(
                    context = context,
                    fileName = if (entry.enabled) "module_plug_on.png" else "module_plug_off.png",
                    x = leftFrameX + 1.0f,
                    y = leftFrameY + 1.0f,
                    width = 24.0f,
                    height = 24.0f,
                    tint = plugTint,
                )

                if (entry.hasSettings) {
                    val rightFrameX = cardX + 170.0f
                    val rightFrameY = cardY + 27.0f
                    val rightBg = 0xFF0D0D0D.toInt()
                    val rightStroke = 0xFF333333.toInt()
                    HypnosiaRenderUtils.drawFigmaBox(context, rightFrameX, rightFrameY, 28.0f, 28.0f, 5.0f, rightBg, rightStroke, 1.0f)
                    val isDrawerOpen = selectedSettingsModuleId == entry.id
                    val settingsTint = if (entry.enabled) WHITE else 0xFF848484.toInt()
                    if (isDrawerOpen) {
                        drawModuleIcon(
                            context = context,
                            fileName = "setup_01.png",
                            x = rightFrameX + 2.0f,
                            y = rightFrameY + 2.0f,
                            width = 24.0f,
                            height = 24.0f,
                            tint = settingsTint,
                        )
                    } else {
                        drawModuleIcon(
                            context = context,
                            fileName = if (entry.enabled) "module_settings_on_closed.png" else "module_settings_off_closed.png",
                            x = rightFrameX + 7.0f,
                            y = rightFrameY + 7.0f,
                            width = 14.0f,
                            height = 14.0f,
                            tint = settingsTint,
                        )
                    }
                    moduleRightButtonRects[entry.id] = Rect(rightFrameX, rightFrameY, 28.0f, 28.0f)
                }

                moduleCardRects[entry.id] = Rect(cardX, cardY, cardW, cardH)
                moduleLeftButtonRects[entry.id] = Rect(leftFrameX, leftFrameY, 26.0f, 26.0f)
            }
        }

        private fun drawModuleIcon(
            context: DrawContext,
            fileName: String,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            tint: Int = WHITE,
        ) {
            val identifier = Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/icons/$fileName")
            HypnosiaRenderUtils.drawRoundedTexture(context, identifier, x, y, width, height, 0.0f, tint)
        }

        private fun renderPlaceholderPage(context: DrawContext, x: Float, y: Float, title: String) {
            drawTextBox(context, title, x, y + 185.0f, MAIN_WIDTH, 42.0f, WHITE, BOTTOM_TITLE, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
            drawTextBox(context, "Coming soon", x, y + 232.0f, MAIN_WIDTH, 20.0f, TEXT_MUTED, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
        }

        private fun renderInput(context: DrawContext, x: Float, y: Float, width: Float, placeholder: String, fieldId: String, textValue: String, iconName: String? = "search.png") {
            val isFocused = focusedField == fieldId
            val leftPadding = if (iconName != null) 29.0f else 12.0f
            fieldBox(context, x, y, width, 28.0f, 8.0f, if (isFocused) HOVER_FIELD else FIELD_ALT, if (isFocused) BORDER_DARK else 0x00000000, if (isFocused) 1.0f else 0.0f)
            if (iconName != null) {
                iconAction(context, iconName, x + 6.0f, y + 6.0f, 16.0f, if (isFocused) TEXT_MUTED else MUTED_DARK)
            }
            val displayText = if (textValue.isNotEmpty()) textValue else if (isFocused) "" else placeholder
            val textColor = if (textValue.isNotEmpty() || isFocused) WHITE else MUTED_DARK
            drawTextBox(context, displayText, x + leftPadding, y + 1.0f, width - leftPadding - 5.0f, 26.0f, textColor, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)
            if (isFocused && (System.currentTimeMillis() / 500) % 2 == 0L) {
                val caretX = x + leftPadding + FigmaTextRenderer.width(displayText, BODY_TEXT)
                rect(context, caretX, y + 6.0f, 1.0f, 16.0f, WHITE)
            }
        }

        private fun renderPlainInput(context: DrawContext, x: Float, y: Float, width: Float, placeholder: String) {
            fieldBox(context, x, y, width, 28.0f, 8.0f, FIELD, 0x00000000, 0.0f)
            drawTextBox(context, placeholder, x + 13.0f, y + 1.0f, width - 26.0f, 26.0f, MUTED_DARK, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)
        }

        private fun renderProfileInput(context: DrawContext, x: Float, y: Float, width: Float, placeholder: String, fieldId: String, textValue: String, blurred: Boolean = false, trailingIcon: String? = null) {
            val isFocused = focusedField == fieldId
            val rightPad = if (trailingIcon != null) 34.0f else 12.0f
            fieldBox(context, x, y, width, 36.0f, 8.0f, if (isFocused) HOVER_FIELD else FIELD_ALT, if (isFocused) BORDER_DARK else 0x00000000, if (isFocused) 1.0f else 0.0f)
            val rawDisplay = if (textValue.isNotEmpty()) textValue else if (isFocused) "" else placeholder
            val displayText = if (blurred && !isFocused && textValue.isNotEmpty()) "•".repeat(8) else rawDisplay
            val textColor = if (textValue.isNotEmpty() || isFocused) WHITE else MUTED_DARK
            drawTextBox(context, displayText, x + 12.0f, y + 1.0f, width - 12.0f - rightPad, 34.0f, textColor, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)
            if (isFocused && (System.currentTimeMillis() / 500) % 2 == 0L) {
                val caretX = x + 12.0f + FigmaTextRenderer.width(displayText, BODY_TEXT)
                rect(context, caretX, y + 10.0f, 1.0f, 16.0f, WHITE)
            }
            if (trailingIcon != null) {
                val eyeTint = if (HypnosiaHomeV2Layout.contactBlurred) MUTED_DARK else TEXT_MUTED
                iconAction(context, trailingIcon, x + width - 26.0f, y + 10.0f, 16.0f, eyeTint)
            }
        }

        private fun renderTextRow(
            context: DrawContext,
            text: String,
            x: Float,
            y: Float,
            width: Float,
            active: Boolean,
            actions: List<String> = emptyList(),
            actionIcons: List<String>? = null,
            compact: Boolean = false,
            rowId: String = "",
            textColor: Int? = null,
        ) {
            val rowH = if (compact) 26.0f else 34.0f
            val hovered = contains(UiInputState.mouseX, UiInputState.mouseY, x, y, width, rowH)
            val bg = when {
                active && hovered -> HOVER_FIELD
                active -> ACTIVE_ROW_BG
                hovered -> HOVER_FIELD
                else -> 0x00000000
            }
            val stroke = if (active) ACTIVE_ROW_STROKE else 0x00000000
            fieldBox(context, x, y, width, rowH, 6.0f, bg, stroke, if (active) 1.0f else 0.0f)
            val labelColor = textColor ?: if (active) WHITE else TEXT_MAIN
            drawTextBox(context, text, x + 12.0f, y + 1.0f, width - 80.0f, rowH - 2.0f, labelColor, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)

            val transition = rowIconAnimations.getOrPut(rowId) { TimedTransition(durationSeconds = 0.3f, easing = FigmaAnimation.EaseInOut) }
            if (hovered) transition.show() else transition.hide()
            val iconAlpha = transition.update(UiInputState.frameSeconds)

            val hoverIcons = mutableListOf<Pair<String, Int>>()
            if (actionIcons != null && (hovered || iconAlpha > 0.01f)) {
                actionIcons.forEach { name ->
                    hoverIcons.add(name to TEXT_MUTED)
                }
            }

            val hoverCount = hoverIcons.size
            val slideOffset = 20.0f * hoverCount * iconAlpha

            val eyeX = x + width - 20.0f - 6.0f - slideOffset
            val eyeY = y + (rowH - 16.0f) * 0.5f

            HypnosiaScissor.withLocalRect(context, Rect(x, y, width, rowH)) {
                if (active) {
                    val eyeHovered = contains(UiInputState.mouseX, UiInputState.mouseY, eyeX, eyeY, 16.0f, 16.0f)
                    val eyeTint = if (eyeHovered) 0xFFA5B4FC.toInt() else 0xFF818CF8.toInt()
                    iconAction(context, "eye.png", eyeX, eyeY, 16.0f, eyeTint)
                }

                if (hoverIcons.isNotEmpty() && iconAlpha > 0.01f) {
                    var iconX = eyeX + 20.0f
                    val iconSize = 16.0f
                    hoverIcons.forEach { (name, defaultTint) ->
                        val ix = iconX
                        val iy = y + (rowH - iconSize) * 0.5f
                        val iconHovered = contains(UiInputState.mouseX, UiInputState.mouseY, ix, iy, iconSize, iconSize)
                        val tint = when {
                            iconHovered && name == "remove_friend.png" -> DANGER
                            iconHovered && name == "delete.png" -> DANGER
                            iconHovered && (name == "save.png" || name == "copy.png") -> WHITE
                            else -> defaultTint
                        }
                        iconAction(context, name, ix, iy, iconSize, applyAlpha(tint, iconAlpha))
                        iconX += 20.0f
                    }
                }
            }

            if (hovered && actions.isNotEmpty()) {
                var actionX = x + width - 18.0f * actions.size - 6.0f
                actions.forEach { action ->
                    val color = when (action) {
                        "x", "✕" -> DANGER
                        "●" -> 0xFF818CF8.toInt()
                        else -> TEXT_MUTED
                    }
                    drawTinyAction(context, action, actionX, y + (rowH - 16.0f) * 0.5f, 16.0f, color)
                    actionX += 18.0f
                }
            }
        }

        private fun refreshCloudConfigsIfNeeded() {
            if (cloudCacheLoading) return
            val now = System.currentTimeMillis()
            if (now - cloudCacheLastRefresh < 5000L) return
            cloudCacheLoading = true
            cloudCacheLastRefresh = now
            AccountManager.listCloudConfigsAsync().thenAccept { result ->
                net.minecraft.client.MinecraftClient.getInstance().execute {
                    cloudCacheLoading = false
                    when (result) {
                        is CloudListResult.Listed -> {
                            cloudConfigsCache = result.configs
                            cloudCacheUsed = result.used
                            cloudCacheLimit = result.limit
                        }
                        else -> {}
                    }
                }
            }
        }

        private fun applyAlpha(color: Int, alpha: Float): Int {
            val a = ((color ushr 24) and 0xFF) * alpha
            return (color and 0x00FFFFFF) or (a.toInt().coerceIn(0, 255) shl 24)
        }

        private fun iconAction(context: DrawContext, fileName: String, x: Float, y: Float, size: Float, tint: Int) {
            val identifier = Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/v2/icons/$fileName")
            HypnosiaRenderUtils.drawIconTexture(context, identifier, x, y, size, size, tint)
        }

        private fun renderKeyValue(context: DrawContext, label: String, value: String, x: Float, y: Float, width: Float) {
            drawTextBox(context, label, x, y, width * 0.55f, 18.0f, MUTED_DARK, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)
            drawTextBox(context, value, x + width * 0.45f, y, width * 0.55f, 18.0f, TEXT_MAIN, BODY_TEXT, FigmaTextRenderer.HorizontalAlign.Right, FigmaTextRenderer.VerticalAlign.Center)
        }

        private fun drawLabel(context: DrawContext, text: String, x: Float, y: Float, width: Float) {
            drawTextBox(context, text, x, y, width, 16.0f, TEXT_MUTED, HEADER_TEXT, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Center)
        }

        private fun drawSmall(
            context: DrawContext,
            text: String,
            x: Float,
            y: Float,
            width: Float,
            color: Int,
            align: FigmaTextRenderer.HorizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
        ) {
            drawTextBox(context, text, x, y, width, 12.0f, color, SMALL_TEXT, align, FigmaTextRenderer.VerticalAlign.Center)
        }

        private fun drawTinyAction(context: DrawContext, label: String, x: Float, y: Float, size: Float, color: Int) {
            drawTextBox(context, label, x, y, size, size, color, SMALL_TEXT, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Center)
        }

        private fun rect(context: DrawContext, x: Float, y: Float, width: Float, height: Float, color: Int) {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, 0.0f, color)
        }

        private fun fieldBox(context: DrawContext, x: Float, y: Float, width: Float, height: Float, radius: Float, bg: Int, stroke: Int, strokeWidth: Float) {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, radius, bg, stroke, strokeWidth)
        }

        private fun box(context: DrawContext, x: Float, y: Float, width: Float, height: Float, radius: Float) {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, width, height, radius, SURFACE, STROKE, 1.0f)
        }

        private fun icon(
            context: DrawContext,
            fileName: String,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            tint: Int = WHITE,
        ) {
            val identifier = Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/v2/icons/$fileName")
            HypnosiaRenderUtils.drawRoundedTexture(context, identifier, x, y, width, height, 0.0f, tint)
        }

        private fun welcomeAsset(
            context: DrawContext,
            fileName: String,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
        ) {
            val identifier = Identifier.of(HypnosiaClient.MOD_ID, "textures/gui/v2/welcome/$fileName")
            HypnosiaRenderUtils.drawRoundedTexture(context, identifier, x, y, width, height, 0.0f, WHITE)
        }

        private fun drawTextBox(
            context: DrawContext,
            text: String,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            color: Int,
            style: FigmaTextRenderer.FigmaTextStyle,
            horizontalAlign: FigmaTextRenderer.HorizontalAlign,
            verticalAlign: FigmaTextRenderer.VerticalAlign,
        ) {
            FigmaTextRenderer.drawInBox(context, text, x, y, width, height, color, style, horizontalAlign, verticalAlign)
        }

        private val labelStyle = FigmaTextRenderer.FigmaTextStyle(
            font = FigmaTextRenderer.Font.Main,
            size = 10.0f,
            lineHeight = 18.0f,
            letterSpacing = 1.4f,
            baselineOffset = 1.0f,
        )
        private val subtitleStyle = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 11.0f, 18.0f, baselineOffset = 1.0f)
        private val calendarTitleStyle = FigmaTextRenderer.FigmaTextStyle(
            font = FigmaTextRenderer.Font.Main,
            size = 11.0f,
            lineHeight = 16.0f,
            letterSpacing = 0.88f,
            baselineOffset = 1.0f,
        )
        private val tinyStyle = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 9.0f, 13.0f, baselineOffset = 1.0f)
        private val statValueStyle = FigmaTextRenderer.FigmaTextStyle(
            font = FigmaTextRenderer.Font.Main,
            size = 16.0f,
            lineHeight = 20.0f,
            letterSpacing = -0.16f,
            baselineOffset = 0.5f,
        )
        private val statLabelStyle = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 10.5f, 10.0f, baselineOffset = 0.5f)
        private val graphTitleStyle = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 11.0f, 13.0f, baselineOffset = 0.5f)
        private val graphSmallStyle = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 9.5f, 12.0f, baselineOffset = 0.5f)
        private val graphTinyStyle = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 8.5f, 10.0f, baselineOffset = 0.5f)
        private val profileNameStyle = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 12.0f, 14.0f, baselineOffset = 1.0f)
        private val profileRoleStyle = FigmaTextRenderer.FigmaTextStyle(
            font = FigmaTextRenderer.Font.Main,
            size = 10.0f,
            lineHeight = 12.0f,
            letterSpacing = 0.8f,
            baselineOffset = 1.0f,
        )

        private data class ModelTuning(
            val yaw: Float = -32.56f,
            val phi: Float = -17.95f,
            val x: Float = 38.0f,
            val y: Float = 42.0f,
            val scale: Float = 82.0f,
        )

        private val modelTuning = ModelTuning()
        private var cachedPlayerModel: PlayerEntityModel? = null
        private val PLAYER_SKIN_Y_PIVOT = 1.0f

        private fun profileName(): String {
            val client = MinecraftClient.getInstance()
            val minecraftName = client.player?.gameProfile?.name ?: client.session.username
            val session = (AccountManager.state as? AccountState.Valid)?.session
            return session?.displayName?.takeIf { it.isNotBlank() } ?: minecraftName
        }

        private fun profileRoleLine(): String {
            val session = (AccountManager.state as? AccountState.Valid)?.session ?: return "no acc"
            return session.roles.firstOrNull { it.name != "USER" }?.name ?: "USER"
        }

        private fun profileRoleColor(): Int {
            return if (profileRoleLine() == "no acc") 0xFF8C8C93.toInt() else 0xFF68E673.toInt()
        }

        private fun renderProfileActivity(context: DrawContext, x: Float, y: Float) {
            val playtime = HypnosiaPlaytime.snapshot()
            drawTextBox(
                context = context,
                text = "PROFILE ACTIVITY",
                x = x + 17.0f,
                y = y + 13.0f,
                width = 210.0f,
                height = 18.0f,
                color = 0xFF7C7C82.toInt(),
                style = labelStyle,
                horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
            )
            drawTextBox(
                context = context,
                text = "Each day square has a circle: bigger circle = more activity that day.",
                x = x + 17.0f,
                y = y + 33.0f,
                width = 370.0f,
                height = 18.0f,
                color = 0xFF8C8C92.toInt(),
                style = subtitleStyle,
                horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
            )

            drawSummaryCard(context, x + 391.0f, y + 17.0f, 0xFFFF2F93.toInt(), formatTotalPlaytime(playtime.totalSeconds), "time total")
            drawSummaryCard(context, x + 455.0f, y + 17.0f, 0xFF68E673.toInt(), playtime.opensToday.toString(), "opens today")
            drawSummaryCard(context, x + 519.0f, y + 17.0f, 0xFF75CFFF.toInt(), "${playtime.streakDays}d", "login streak")

            drawCalendarPanel(context, x + 17.0f, y + 58.0f, playtime)
            drawProfileSkinPanel(context, x + 389.0f, y + 93.0f)
            drawSevenDayGraph(context, x + 17.0f, y + 310.0f, playtime)
        }

        private fun drawSummaryCard(context: DrawContext, x: Float, y: Float, dotColor: Int, value: String, label: String) {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, 56.0f, 64.0f, 8.0f, 0xFF0D0D0E.toInt(), 0xFF26262A.toInt(), 1.0f)
            HypnosiaRenderUtils.drawFigmaBox(context, x + 7.0f, y + 9.0f, 8.0f, 8.0f, 4.0f, dotColor)
            drawTextBox(
                context = context,
                text = value,
                x = x + 7.0f,
                y = y + 18.0f,
                width = 42.0f,
                height = 20.0f,
                color = 0xFFF1F1F2.toInt(),
                style = statValueStyle,
                horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
            )
            drawSummaryLabel(context, x + 5.0f, y + 40.0f, label)
        }

        private fun drawSummaryLabel(context: DrawContext, x: Float, y: Float, label: String) {
            label.split(' ')
                .filter { it.isNotBlank() }
                .forEachIndexed { index, word ->
                    drawTextBox(
                        context = context,
                        text = word,
                        x = x,
                        y = y + index * 10.0f,
                        width = 48.0f,
                        height = 10.0f,
                        color = 0xFF8A8A90.toInt(),
                        style = statLabelStyle,
                        horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                        verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
                    )
                }
        }

        private fun drawCalendarPanel(
            context: DrawContext,
            x: Float,
            y: Float,
            playtime: HypnosiaPlaytime.Snapshot,
        ) {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, 354.0f, 248.0f, 9.0f, SURFACE, 0xFF242428.toInt(), 1.0f)
            drawTextBox(context, monthTitle(playtime), x + 15.0f, y + 13.0f, 110.0f, 16.0f, 0xFFD6D6D8.toInt(), calendarTitleStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)
            drawTextBox(context, "less", x + 238.0f, y + 13.0f, 28.0f, 12.0f, 0xFF77777D.toInt(), tinyStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)
            drawLegendDot(context, x + 270.5f, y + 16.5f, 5.0f, 0.18f)
            drawLegendDot(context, x + 281.0f, y + 14.0f, 10.0f, 0.52f)
            drawLegendDot(context, x + 295.5f, y + 10.5f, 17.0f, 1.0f)
            drawTextBox(context, "more", x + 318.0f, y + 13.0f, 28.0f, 12.0f, 0xFF77777D.toInt(), tinyStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)

            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, label ->
                drawTextBox(context, label, x + 17.0f + index * 45.0f, y + 42.0f, 28.0f, 14.0f, 0xFF75757B.toInt(), tinyStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)
            }

            monthCircleSizes(playtime).forEachIndexed { index, size ->
                val col = index % 7
                val row = index / 7
                val cellX = x + 15.0f + col * 45.0f
                val cellY = y + 61.0f + row * 34.0f
                HypnosiaRenderUtils.drawFigmaBox(context, cellX, cellY, 30.0f, 30.0f, 6.0f, 0xFF080809.toInt(), 0xFF25252A.toInt(), 1.0f)
                if (size > 0.0f) {
                    val activity = (size / 26.0f).coerceIn(0.0f, 1.0f)
                    val color = FigmaAnimation.lerpArgb(0xFF2E2E35.toInt(), 0xFFFF2F93.toInt(), activity)
                    HypnosiaRenderUtils.drawFigmaBox(
                        context = context,
                        x = cellX + (30.0f - size) * 0.5f,
                        y = cellY + (30.0f - size) * 0.5f,
                        width = size,
                        height = size,
                        radius = size * 0.5f,
                        bgColor = color,
                    )
                }
            }

            drawTextBox(
                context = context,
                text = "Local data: playtime is stored only on this Minecraft instance.",
                x = x + 8.0f,
                y = y + 231.0f,
                width = 300.0f,
                height = 14.0f,
                color = 0xFF6E6E74.toInt(),
                style = tinyStyle,
                horizontalAlign = FigmaTextRenderer.HorizontalAlign.Left,
                verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
            )
        }

        private fun drawLegendDot(context: DrawContext, x: Float, y: Float, size: Float, activity: Float) {
            val color = FigmaAnimation.lerpArgb(0xFF2E2E35.toInt(), 0xFFFF2F93.toInt(), activity)
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, size, size, size * 0.5f, color)
        }

        private fun drawProfileSkinPanel(context: DrawContext, x: Float, y: Float) {
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, 196.0f, 260.0f, 9.0f, 0xFF0D0D0E.toInt(), 0xFF242428.toInt(), 1.0f)
            val session = (AccountManager.state as? AccountState.Valid)?.session
            val primaryRole = session?.roles?.firstOrNull { it.name != "USER" }
                ?: session?.roles?.firstOrNull()
                ?: LicenseRole.USER
            val nickGrad = parseGradientColors(session?.nickGradients?.get(primaryRole.name))
            val roleGrad = parseGradientColors(session?.roleGradients?.get(primaryRole.name))
            val time = (System.currentTimeMillis() % 1000000L) / 1000f

            if (nickGrad.size >= 2) {
                FigmaTextRenderer.drawGradientInBox(
                    context = context, text = profileName(),
                    x = x + 12.0f, y = y + 9.0f, width = 172.0f, height = 15.0f,
                    color = WHITE, style = profileNameStyle,
                    gradientColor1 = nickGrad[0], gradientColor2 = nickGrad[1], time = time,
                    horizontalAlign = FigmaTextRenderer.HorizontalAlign.Center,
                    verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
                    fallbackColor = 0xFFF1F1F2.toInt(),
                )
            } else {
                drawTextBox(context, profileName(), x + 12.0f, y + 9.0f, 172.0f, 15.0f, 0xFFF1F1F2.toInt(), profileNameStyle, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Top)
            }
            if (roleGrad.size >= 2) {
                FigmaTextRenderer.drawGradientInBox(
                    context = context, text = profileRoleLine().uppercase(),
                    x = x + 12.0f, y = y + 25.0f, width = 172.0f, height = 13.0f,
                    color = WHITE, style = profileRoleStyle,
                    gradientColor1 = roleGrad[0], gradientColor2 = roleGrad[1], time = time,
                    horizontalAlign = FigmaTextRenderer.HorizontalAlign.Center,
                    verticalAlign = FigmaTextRenderer.VerticalAlign.Top,
                    fallbackColor = profileRoleColor(),
                )
            } else {
                drawTextBox(context, profileRoleLine().uppercase(), x + 12.0f, y + 25.0f, 172.0f, 13.0f, profileRoleColor(), profileRoleStyle, FigmaTextRenderer.HorizontalAlign.Center, FigmaTextRenderer.VerticalAlign.Top)
            }

            renderPlayerModel(context, x, y)
        }

        private fun drawSevenDayGraph(
            context: DrawContext,
            x: Float,
            y: Float,
            playtime: HypnosiaPlaytime.Snapshot,
        ) {
            val graphPoints = weekGraphPoints(playtime)
            HypnosiaRenderUtils.drawFigmaBox(context, x, y, 354.0f, 56.0f, 8.0f, 0xFF0D0D0E.toInt(), 0xFF242428.toInt(), 1.0f)
            drawTextBox(context, "Last 7 days active", x + 11.0f, y + 6.0f, 126.0f, 13.0f, 0xFFD6D6D8.toInt(), graphTitleStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)
            drawTextBox(context, weekPeakHours(playtime), x + 308.0f, y + 5.0f, 38.0f, 12.0f, 0xFF8C8C93.toInt(), graphSmallStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)
            drawTextBox(context, "0h", x + 310.0f, y + 25.0f, 28.0f, 10.0f, 0xFF6F6F76.toInt(), graphTinyStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)
            HypnosiaRenderUtils.drawFigmaBox(context, x + 127.0f, y + 22.5f, 172.0f, 1.0f, 0.5f, 0x662A2A2E.toInt())
            HypnosiaRenderUtils.drawFigmaBox(context, x + 127.0f, y + 33.5f, 172.0f, 2.0f, 1.0f, 0xBF2A2A2E.toInt())

            graphPoints.windowed(2).forEach { pair ->
                val (x1, y1) = pair[0]
                val (x2, y2) = pair[1]
                drawSegmentApproximation(context, x + x1, y + y1, x + x2, y + y2)
            }
            val labels = lastSevenDayLabels()
            graphPoints.forEachIndexed { index, point ->
                val pointSize = if (index == 4) 5.0f else 4.0f
                HypnosiaRenderUtils.drawFigmaBox(context, x + point.first - pointSize * 0.5f, y + point.second - pointSize * 0.5f, pointSize, pointSize, pointSize * 0.5f, WHITE)
                drawTextBox(context, labels[index], x + 122.0f + index * 24.0f, y + 42.0f, 22.0f, 9.0f, 0xFF6E6E75.toInt(), graphTinyStyle, FigmaTextRenderer.HorizontalAlign.Left, FigmaTextRenderer.VerticalAlign.Top)
            }
        }

        private fun drawSegmentApproximation(context: DrawContext, startX: Float, startY: Float, endX: Float, endY: Float) {
            val steps = 12
            for (step in 0 until steps) {
                val t = step / steps.toFloat()
                val nextT = (step + 1) / steps.toFloat()
                val x1 = startX + (endX - startX) * t
                val y1 = startY + (endY - startY) * t
                val x2 = startX + (endX - startX) * nextT
                val y2 = startY + (endY - startY) * nextT
                HypnosiaRenderUtils.drawFigmaBox(
                    context = context,
                    x = (x1 + x2) * 0.5f - 1.5f,
                    y = (y1 + y2) * 0.5f - 1.5f,
                    width = 3.0f,
                    height = 3.0f,
                    radius = 1.5f,
                    bgColor = 0x99EDEDF1.toInt(),
                )
            }
        }

        private fun renderPlayerModel(context: DrawContext, x: Float, y: Float) {
            val client = MinecraftClient.getInstance()
            val player = client.player ?: return

            val skin = player.skin
            val model = cachedPlayerModel ?: PlayerEntityModel(client.loadedEntityModels.getModelPart(EntityModelLayers.PLAYER), false).also {
                cachedPlayerModel = it
            }
            model.setVisible(true)

            val matrix = Matrix3x2f(context.matrices)
            val localLeft = x + modelTuning.x
            val localTop = y + modelTuning.y
            val guiLeft = transformX(matrix, localLeft, localTop)
            val guiTop = transformY(matrix, localLeft, localTop)
            val scaleX = matrix.m00().takeIf { it != 0.0f } ?: 1.0f
            val scaleY = matrix.m11().takeIf { it != 0.0f } ?: scaleX
            val modelScale = modelTuning.scale * scaleX.coerceAtLeast(0.01f)
            val modelWidth = 120.0f * scaleX
            val modelHeight = 210.0f * scaleY

            HypnosiaScissor.withLocalRect(context, Rect(x + 1.0f, y + 40.0f, 194.0f, 219.0f)) {
                context.matrices.pushMatrix()
                context.matrices.scale(1.0f / scaleX, 1.0f / scaleY)
                context.matrices.translate(-matrix.m20(), -matrix.m21())
                try {
                    context.addPlayerSkin(
                        model,
                        skin.body().texturePath(),
                        modelScale,
                        modelTuning.phi,
                        modelTuning.yaw,
                        PLAYER_SKIN_Y_PIVOT,
                        guiLeft.toInt(),
                        guiTop.toInt(),
                        (guiLeft + modelWidth).toInt(),
                        (guiTop + modelHeight).toInt(),
                    )
                } finally {
                    context.matrices.popMatrix()
                }
            }
        }

        private fun transformX(matrix: Matrix3x2f, x: Float, y: Float): Float =
            matrix.m00() * x + matrix.m10() * y + matrix.m20()

        private fun transformY(matrix: Matrix3x2f, x: Float, y: Float): Float =
            matrix.m01() * x + matrix.m11() * y + matrix.m21()

        private fun formatTotalPlaytime(totalSeconds: Long): String {
            val hours = totalSeconds / 3600.0
            return when {
                totalSeconds < 60L -> "${totalSeconds}s"
                totalSeconds < 3600L -> "${totalSeconds / 60L}m"
                hours < 10.0 -> String.format(Locale.US, "%.1fh", hours)
                else -> "${hours.toInt()}h"
            }
        }

        private fun monthTitle(playtime: HypnosiaPlaytime.Snapshot): String {
            val month = playtime.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(Locale.ENGLISH)
            return "$month ${playtime.month.year}"
        }

        private fun monthCircleSizes(playtime: HypnosiaPlaytime.Snapshot): FloatArray {
            val result = FloatArray(35)
            val first = playtime.month.atDay(1)
            val firstIndex = first.dayOfWeek.value - 1
            val maxSeconds = (1..playtime.month.lengthOfMonth())
                .maxOfOrNull { day -> playtime.dailySeconds[playtime.month.atDay(day)] ?: 0L }
                ?: 0L
            if (maxSeconds <= 0L) {
                return result
            }

            for (day in 1..playtime.month.lengthOfMonth()) {
                val index = firstIndex + day - 1
                if (index !in result.indices) {
                    continue
                }
                val seconds = playtime.dailySeconds[playtime.month.atDay(day)] ?: 0L
                if (seconds <= 0L) {
                    continue
                }
                val activity = (seconds / maxSeconds.toFloat()).coerceIn(0.0f, 1.0f)
                result[index] = 5.0f + activity * 17.0f
            }

            return result
        }

        private fun weekGraphPoints(playtime: HypnosiaPlaytime.Snapshot): List<Pair<Float, Float>> {
            val today = LocalDate.now()
            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val hours = days.map { (playtime.dailySeconds[it] ?: 0L) / 3600.0f }
            val maxHours = hours.maxOrNull()?.coerceAtLeast(1.0f) ?: 1.0f
            return hours.mapIndexed { index, value ->
                val x = 131.0f + index * 24.0f
                val y = 34.0f - (value / maxHours).coerceIn(0.0f, 1.0f) * 22.0f
                x to y
            }
        }

        private fun lastSevenDayLabels(): List<String> {
            val today = LocalDate.now()
            return (6 downTo 0).map { offset ->
                today.minusDays(offset.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            }
        }

        private fun weekPeakHours(playtime: HypnosiaPlaytime.Snapshot): String {
            val today = LocalDate.now()
            val peak = (6 downTo 0)
                .maxOf { offset -> (playtime.dailySeconds[today.minusDays(offset.toLong())] ?: 0L) / 3600.0 }
            return if (peak < 10.0) {
                String.format(Locale.US, "%.1fh", peak)
            } else {
                "${peak.toInt()}h"
            }
        }

        private fun contains(mouseX: Float, mouseY: Float, x: Float, y: Float, width: Float, height: Float): Boolean {
            return mouseX >= x && mouseY >= y && mouseX <= x + width && mouseY <= y + height
        }
    }

    private var contactBlurred = false

    private data class NavItem(
        val id: String,
        val label: String,
        val buttonX: Float,
        val buttonY: Float,
        val icon: String,
        val hoverIcon: String,
        val iconX: Float,
        val iconY: Float,
        val iconWidth: Float,
        val iconHeight: Float,
    )

    private data class NavVisual(
        val item: NavItem,
        val rect: Rect,
        val boost: Float,
        val iconScale: Float,
    )

    private data class NavLayout(
        val dockX: Float,
        val dockY: Float,
        val dockWidth: Float,
        val dockHeight: Float,
        val items: List<NavVisual>,
    )

    private class NavAnimation {
        val boost = AnimatedFloat(0.0f, speed = 18.0f)
        val stroke = AnimatedColor(STROKE, speed = 18.0f)
        val iconTint = AnimatedColor(WHITE, speed = 18.0f)
        val strokeWidth = AnimatedFloat(1.0f, speed = 18.0f)
    }

    private val NAV_ITEMS = listOf(
        NavItem("home", "Home", 202.0f, 526.0f, "v2_home.png", "v2_home_hover.png", 204.0f, 528.0f, 31.0f, 31.0f),
        NavItem("visuals", "Visuals", 245.0f, 526.0f, "v2_visuals.png", "v2_visuals_hover.png", 249.0f, 530.0f, 27.0f, 27.0f),
        NavItem("world", "World", 288.0f, 526.0f, "v2_world.png", "v2_world_hover.png", 290.0f, 528.0f, 31.0f, 31.0f),
        NavItem("client", "Client", 331.0f, 526.0f, "v2_client.png", "v2_client_hover.png", 333.0f, 528.0f, 31.0f, 31.0f),
        NavItem("hud", "Hud", 374.0f, 526.0f, "v2_hud.png", "v2_hud_hover.png", 380.0f, 532.0f, 23.0f, 23.0f),
        NavItem("other", "Other", 417.0f, 526.0f, "v2_other.png", "v2_other_hover.png", 421.0f, 531.0f, 24.0f, 25.0f),
        NavItem("account", "Account", 460.0f, 526.0f, "v2_account_cloud.png", "v2_account_cloud_hover.png", 465.0f, 531.0f, 24.0f, 24.0f),
    )

    private const val BASE_NAV_BUTTON_SIZE = 35.0f
    private const val NAV_ITEM_GAP = 8.0f
    private const val NAV_DOCK_PADDING = 8.0f
    private const val NAV_DOCK_VERTICAL_PADDING = 6.0f
    private const val NAV_MAX_BOOST = 10.0f
    private const val NAV_ICON_BOOST = 0.18f
    private const val NAV_HOVER_RANGE = 108.0f
    private const val CONTENT_PADDING = 24.0f
    private const val WELCOME_CONTENT_WIDTH = 604.0f
    private const val WELCOME_CONTENT_HEIGHT = 370.0f
    private const val SURFACE = 0xFF0D0D0D.toInt()
    private const val STROKE = 0xFF272727.toInt()
    private const val HOVER_WHITE = 0xFFE9E9E9.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val FIELD = 0xFF151515.toInt()
    private const val FIELD_ALT = 0xFF222222.toInt()
    private const val HOVER_FIELD = 0xFF2A2A2A.toInt()
    private const val DIVIDER = 0x801A1A1A.toInt()
    private const val BORDER_DARK = 0xFF2A2A2A.toInt()
    private const val TEXT_MAIN = 0xFFE5E5E5.toInt()
    private const val TEXT_MUTED = 0xFF888888.toInt()
    private const val MUTED_DARK = 0xFF666666.toInt()
    private const val DANGER = 0xFFEF4444.toInt()
    private const val ACTIVE_ROW_BG = 0x802A2A2A.toInt()
    private const val ACTIVE_ROW_STROKE = 0x4D333333.toInt()

    private fun withAlpha(color: Int, multiplier: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * multiplier.coerceIn(0.0f, 1.0f)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private val SEARCH_TEXT = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 20.0f, 24.204544f, baselineOffset = 2.0f)
    private val BOTTOM_TITLE = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 24.0f, 29.045454f, baselineOffset = 3.0f)
    private val PROFILE_TEXT = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 16.0f, 19.363636f, baselineOffset = 2.0f)
    private val HEADER_TEXT = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 11.0f, 14.0f, baselineOffset = 1.0f)
    private val BODY_TEXT = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 13.0f, 16.0f, baselineOffset = 1.0f)
    private val SMALL_TEXT = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 10.0f, 12.0f, baselineOffset = 1.0f)
    private val CHIP_TEXT = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 9.0f, 11.0f, baselineOffset = 0.5f)
    private val MODULE_TITLE = FigmaTextRenderer.FigmaTextStyle(FigmaTextRenderer.Font.Main, 12.0f, 14.0f, baselineOffset = 1.0f)

    private val hexColorRegex = Regex("""#[0-9A-Fa-f]{6}""")
    private fun String.firstHexColor(): Int? {
        val match = hexColorRegex.find(this) ?: return null
        val hex = match.value.removePrefix("#")
        return (0xFF000000.toInt() or hex.toInt(16))
    }
}
