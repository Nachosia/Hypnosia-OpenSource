package dev.hypnosia.license

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object LinkCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("hypnosia")
                    .then(
                        literal("link")
                            .executes { context ->
                                val source = context.source
                                val client = MinecraftClient.getInstance()

                                val session = (AccountManager.state as? AccountState.Valid)?.session
                                if (session != null && !session.contact.isNullOrBlank()) {
                                    source.sendFeedback(Text.literal("[Hypnosia] §cВаш аккаунт уже привязан. Если хотите перепривязать, обратитесь в поддержку."))
                                    return@executes 1
                                }

                                source.sendFeedback(Text.literal("[Hypnosia] Генерация кода привязки..."))

                                AccountManager.registerLinkCodeAsync().thenAccept { result ->
                                    client.execute {
                                        when (result) {
                                            is LinkCodeResult.Success -> {
                                                source.sendFeedback(Text.literal("[Hypnosia] Код привязки: §a${result.code}"))
                                                source.sendFeedback(Text.literal("[Hypnosia] Введите код на сайте в разделе Привязка Minecraft. Действителен 10 минут."))
                                            }
                                            is LinkCodeResult.Error -> {
                                                source.sendFeedback(Text.literal("[Hypnosia] Ошибка: §c${result.reason}"))
                                            }
                                        }
                                    }
                                }
                                1
                            },
                    ),
            )
        }
    }
}

sealed class LinkCodeResult {
    data class Success(val code: String, val expiresIn: Int) : LinkCodeResult()
    data class Error(val reason: String) : LinkCodeResult()
}
