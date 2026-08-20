package dev.hypnosia.license

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object ActKeyCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("actkey")
                    .then(
                        argument("key", StringArgumentType.word())
                            .executes { context ->
                                val key = StringArgumentType.getString(context, "key")
                                val source = context.source
                                source.sendFeedback(Text.literal("[Hypnosia] Проверяю ключ роли..."))

                                AccountManager.applyRoleKeyAsync(key).thenAccept { result ->
                                    MinecraftClient.getInstance().execute {
                                        source.sendFeedback(Text.literal(messageFor(result)))
                                    }
                                }
                                1
                            },
                    ),
            )
        }
    }

    private fun messageFor(state: AccountState): String {
        return when (state) {
            is AccountState.Valid -> {
                val roles = state.session.roles.joinToString(", ") { it.name }
                "[Hypnosia] Роль подключена. Account #${state.session.accountId}. Роли: $roles"
            }
            AccountState.InvalidResponse -> "[Hypnosia] Сервер вернул неправильный ответ."
            AccountState.NoAccount -> "[Hypnosia] Аккаунт не найден."
            AccountState.NotChecked -> "[Hypnosia] Аккаунт еще не проверен."
            is AccountState.ServerRejected -> "[Hypnosia] Ключ отклонен: ${state.reason}"
            is AccountState.NetworkError -> "[Hypnosia] Ошибка сети: ${state.message}"
        }
    }
}
