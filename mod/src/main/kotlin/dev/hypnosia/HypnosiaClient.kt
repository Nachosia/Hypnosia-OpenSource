package dev.hypnosia

import dev.hypnosia.render.HypnosiaShaders
import dev.hypnosia.config.HypnosiaConfigProfiles
import dev.hypnosia.hud.CooldownHud
import dev.hypnosia.hud.HotKeyHud
import dev.hypnosia.hud.HudModulesHud
import dev.hypnosia.hud.InventoryHud
import dev.hypnosia.hud.ModuleHotkeys
import dev.hypnosia.hud.NowPlayingHud
import dev.hypnosia.hud.PlayerInfoHud
import dev.hypnosia.hud.PotionsHud
import dev.hypnosia.hud.TargetHud
import dev.hypnosia.hud.WatermarkHud
import dev.hypnosia.visual.cosmetic.CosmeticRenderModule
import dev.hypnosia.visual.cosmetic.CosmeticSettings
import dev.hypnosia.visual.image.ImageRenderModule
import dev.hypnosia.media.GlobalMediaTracker
import dev.hypnosia.license.ActKeyCommand
import dev.hypnosia.license.AccountManager
import dev.hypnosia.license.HardwareFingerprint
import dev.hypnosia.license.LinkCommand
import dev.hypnosia.license.LogoutCommand
import dev.hypnosia.license.SessionManager
import dev.hypnosia.other.DiscordRpcManager
import dev.hypnosia.playtime.ActivityTracker
import dev.hypnosia.ui.HypnosiaHomeV2Screen
import dev.hypnosia.ui.profile.HypnosiaPlaytime
import dev.hypnosia.ui.render.HighQualityTextRenderer
import dev.hypnosia.world.WorldVisualSettings
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object HypnosiaClient : ClientModInitializer {
    const val MOD_ID: String = "hypnosia"
    private val logger = LoggerFactory.getLogger(MOD_ID)
    private const val SERVICE_WARNING_DELAY_MS = 5000L

    private lateinit var openHomeV2Key: KeyBinding
    @Volatile private var serviceCheckStartedAtMs = 0L
    @Volatile private var serviceCheckAvailable = false
    @Volatile private var serviceWarningSent = false

    override fun onInitializeClient() {
        HypnosiaShaders.initialize()
        HypnosiaConfigProfiles.bootstrap()
        CosmeticRenderModule.register()
        CosmeticSettings.ensureLoaded()
        dev.hypnosia.visual.world.particles.WorldParticleRenderer.register()
        dev.hypnosia.visual.world.particles.hit.HitParticleRenderer.register()
        dev.hypnosia.visual.world.esp.TargetEspRenderer.register()
        dev.hypnosia.visual.world.jump.JumpCircleRenderer.register()
        dev.hypnosia.visual.world.trails.TrailRenderer.register()
        dev.hypnosia.visual.world.hitcolor.HitColorManager.register()
        WatermarkHud.register()
        NowPlayingHud.register()
        ImageRenderModule.register()
        GlobalMediaTracker.start()
        HudModulesHud.register()
        TargetHud.register()
        PlayerInfoHud.register()
        InventoryHud.register()
        CooldownHud.register()
        PotionsHud.register()
        HotKeyHud.register()
        ActKeyCommand.register()
        LinkCommand.register()
        LogoutCommand.register()
        HypnosiaPlaytime.recordLaunch()

        // Shutdown hook for emergency flush (in case CLIENT_STOPPING doesn't fire)
        Runtime.getRuntime().addShutdownHook(Thread {
            println("[Hypnosia] Shutdown hook triggered, sending emergency flush")
            if (ActivityTracker.activeMinutesAccumulated > 0 && SessionManager.hasActiveSession()) {
                try {
                    SessionManager.sendEmergencyFlush().get(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    println("[Hypnosia] Emergency flush failed or timed out: ${e.message}")
                }
            }
        })

        ClientLifecycleEvents.CLIENT_STARTED.register {
            if (System.getProperty("hypnosia.prewarmText", "true").toBoolean()) {
                runCatching { HighQualityTextRenderer.prewarmCommonAtlases() }
                    .onSuccess { warmed -> logger.info("Prewarmed {} Hypnosia text atlases.", warmed) }
                    .onFailure { error -> logger.warn("Failed to prewarm Hypnosia text atlases.", error) }
            }
        }

        AccountManager.startSessionAsync().thenAccept { state ->
            logger.info("Hypnosia account session state: {}", state)
            if (state is dev.hypnosia.license.AccountState.Valid) {
                AccountManager.markOnlineAsync(net.minecraft.client.MinecraftClient.getInstance().session.username)
                val future = SessionManager.startSession(
                    accountKey = state.session.accountKey,
                    hwidHash = HardwareFingerprint.currentHash64(),
                    accountId = state.session.accountId,
                )
                println("[Hypnosia] SessionManager.startSession called, future=$future")
            }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            val client = net.minecraft.client.MinecraftClient.getInstance()
            WorldVisualSettings.restoreGamma(client)
            AccountManager.markOfflineAsync()
            try {
                SessionManager.endSession(flush = true).get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                println("[Hypnosia] End session flush failed or timed out: ${e.message}")
            }
            DiscordRpcManager.shutdown()
        }

        val hypnosiaKeyCategory = KeyBinding.Category.create(Identifier.of(MOD_ID, "hypnosia"))

        openHomeV2Key = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.hypnosia.open_home_v2",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                hypnosiaKeyCategory,
            ),
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            HypnosiaPlaytime.tick(client)
            ActivityTracker.tick(client)
            AccountManager.tickNotifications(client)
            ImageRenderModule.tickDrag(client)
            HudModulesHud.tickDrag(client)
            NowPlayingHud.tickDrag(client)
            TargetHud.tickDrag(client)
            PlayerInfoHud.tickDrag(client)
            InventoryHud.tickDrag(client)
            CooldownHud.tickDrag(client)
            PotionsHud.tickDrag(client)
            HotKeyHud.tickDrag(client)
            WorldVisualSettings.tick(client)
            ModuleHotkeys.tick(client)
            DiscordRpcManager.tick(client)
            tickServiceWarning(client)
            while (openHomeV2Key.wasPressed()) {
                client.setScreen(HypnosiaHomeV2Screen())
            }
        }
    }

    private fun tickServiceWarning(client: net.minecraft.client.MinecraftClient) {
        if (serviceWarningSent || serviceCheckAvailable) return
        if (serviceCheckStartedAtMs == 0L) {
            serviceCheckStartedAtMs = System.currentTimeMillis()
            AccountManager.checkServiceAvailableAsync().thenAccept { available ->
                serviceCheckAvailable = available
            }
            return
        }
        if (System.currentTimeMillis() - serviceCheckStartedAtMs < SERVICE_WARNING_DELAY_MS) return
        val player = client.player ?: return
        serviceWarningSent = true
        player.sendMessage(
            Text.literal("Hypnosia: нет подключения к серверу или ведутся технические работы.")
                .formatted(Formatting.RED),
            false,
        )
    }
}
