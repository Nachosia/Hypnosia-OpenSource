package dev.hypnosia.license

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object LogoutCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("hypnosia")
                    .then(
                        literal("logout")
                            .executes { context ->
                                AccountManager.logout()
                                MinecraftClient.getInstance().execute {
                                    context.source.sendFeedback(Text.literal("[Hypnosia] Вы вышли из аккаунта."))
                                }
                                1
                            },
                    ),
            )
        }
    }
}
