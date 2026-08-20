package dev.hypnosia.other

import dev.hypnosia.config.HypnosiaClientSettings
import dev.hypnosia.license.AccountManager
import dev.hypnosia.license.AccountState
import net.minecraft.client.MinecraftClient
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DiscordRpcManager {
    private const val MODULE_KEY = "module.other.discord_rpc.enabled"
    private const val APP_ID_KEY = "discord_rpc.applicationId"
    private const val ICON_URL_KEY = "discord_rpc.iconUrl"
    private const val DEFAULT_APP_ID = "1507742783379734628"
    private const val CONNECT_RETRY_MS = 15_000L
    private const val UPDATE_INTERVAL_MS = 15_000L

    private val connecting = AtomicBoolean(false)
    private val lock = Any()
    private var pipe: RandomAccessFile? = null
    private var lastConnectAttemptMs = 0L
    private var lastUpdateMs = 0L
    private var lastPayload = ""
    private var readyReceived = false
    private val startedAtSeconds = System.currentTimeMillis() / 1000L

    fun tick(client: MinecraftClient) {
        if (client.player == null || !isEnabled()) {
            disconnect()
            return
        }

        val appId = applicationId()
        if (appId.isBlank()) {
            disconnect()
            return
        }

        if (pipe == null) {
            connectAsync(appId)
            return
        }

        if (!readyReceived) return

        val now = System.currentTimeMillis()
        val payload = activityPayload(client)
        if (payload == lastPayload && now - lastUpdateMs < UPDATE_INTERVAL_MS) return

        lastPayload = payload
        lastUpdateMs = now
        sendFrameAsync(1, payload)
    }

    fun shutdown() {
        disconnect()
    }

    private fun isEnabled(): Boolean =
        HypnosiaClientSettings.boolean(MODULE_KEY, false)

    private fun applicationId(): String =
        HypnosiaClientSettings.string(APP_ID_KEY, DEFAULT_APP_ID).trim()

    private fun iconUrl(): String =
        HypnosiaClientSettings.string(ICON_URL_KEY, "http://127.0.0.1:3000/discord-rpc-icon.gif").trim()

    private fun connectAsync(appId: String) {
        val now = System.currentTimeMillis()
        if (now - lastConnectAttemptMs < CONNECT_RETRY_MS) return
        if (!connecting.compareAndSet(false, true)) return

        lastConnectAttemptMs = now
        CompletableFuture.runAsync {
            runCatching {
                val opened = openDiscordPipe() ?: run {
                    log(null, "[DiscordRPC] no discord pipe found")
                    return@runCatching
                }
                synchronized(lock) {
                    pipe?.close()
                    pipe = opened
                    readyReceived = false
                }
                log(null, "[DiscordRPC] handshake appId=$appId")
                sendFrame(0, """{"v":1,"client_id":"${json(appId)}"}""")

                // Read READY response synchronously (Discord IPC handshake)
                val buf = ByteArray(8)
                val read = opened.read(buf)
                if (read != 8) {
                    log(null, "[DiscordRPC] bad header read=$read")
                    return@runCatching
                }
                val header = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val op = header.getInt()
                val len = header.getInt()
                if (len < 0 || len > 65536) {
                    log(null, "[DiscordRPC] bad len=$len")
                    return@runCatching
                }
                val payloadBytes = ByteArray(len)
                opened.readFully(payloadBytes)
                val text = String(payloadBytes, StandardCharsets.UTF_8)
                log(null, "[DiscordRPC] response: $text")

                if (text.contains("\"evt\":\"READY\"")) {
                    readyReceived = true
                    log(null, "[DiscordRPC] READY received")
                } else if (text.contains("\"error\"")) {
                    log(null, "[DiscordRPC] handshake error: $text")
                    return@runCatching
                }

                lastPayload = ""
                lastUpdateMs = 0L
            }.onFailure { e ->
                log(null, "[DiscordRPC] connect error: ${e.message}")
                disconnect()
            }
            connecting.set(false)
        }
    }

    private fun sendFrameAsync(op: Int, payload: String) {
        CompletableFuture.runAsync {
            runCatching { sendFrame(op, payload) }
                .onFailure { e ->
                    log(null, "[DiscordRPC] send error: ${e.message}")
                    disconnect()
                }
        }
    }

    private fun sendFrame(op: Int, payload: String) {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op)
            .putInt(bytes.size)
            .array()

        synchronized(lock) {
            val current = pipe ?: return
            current.write(header)
            current.write(bytes)
        }
    }

    private fun activityPayload(client: MinecraftClient): String {
        val (details, state) = rpcLines(client)
        val nonce = UUID.randomUUID().toString()
        val largeText = client.session.username
        val icon = iconUrl()
        val profileUrl = profileUrl()
        val stateField = if (state.isBlank()) "" else ",\"state\":\"${json(state)}\""
        val buttonsField = if (profileUrl.isBlank()) "" else ",\"buttons\":[{\"label\":\"Профиль\",\"url\":\"${json(profileUrl)}\"}]"
        val largeImageField = if (icon.isBlank()) "" else ",\"large_image\":\"${json(icon)}\""
        return """
            {
              "cmd":"SET_ACTIVITY",
              "args":{
                "pid":${pid()},
                "activity":{
                  "details":"${json(details)}"$stateField,
                  "timestamps":{"start":$startedAtSeconds},
                  "assets":{"large_text":"${json(largeText)}"$largeImageField}
                  $buttonsField
                }
              },
              "nonce":"$nonce"
            }
        """.trimIndent().replace("\n", "")
    }

    private fun rpcLines(client: MinecraftClient): Pair<String, String> {
        val state = AccountManager.state
        if (state !is AccountState.Valid) return "no acc" to ""

        val displayName = state.session.displayName?.takeIf { it.isNotBlank() }
            ?: client.session.username
        return "ID: ${state.session.accountId}" to displayName
    }

    private fun profileUrl(): String {
        val state = AccountManager.state
        if (state !is AccountState.Valid) return ""
        return "${AccountManager.SITE_URL}/#/profile/${state.session.accountId}"
    }

    private fun openDiscordPipe(): RandomAccessFile? {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val candidates = when {
            os.contains("win") -> (0..9).map { "\\\\?\\pipe\\discord-ipc-$it" }
            os.contains("mac") -> {
                val tmp = System.getenv("TMPDIR") ?: "/tmp"
                (0..9).map { "$tmp/discord-ipc-$it" }
            }
            else -> {
                val runtime = System.getenv("XDG_RUNTIME_DIR")
                val tmp = runtime ?: System.getenv("TMPDIR") ?: "/tmp"
                (0..9).map { "$tmp/discord-ipc-$it" }
            }
        }

        return candidates.firstNotNullOfOrNull { path ->
            runCatching { RandomAccessFile(path, "rw") }.getOrNull()
        }
    }

    private fun disconnect() {
        synchronized(lock) {
            runCatching { pipe?.close() }
            pipe = null
            readyReceived = false
            lastPayload = ""
        }
    }

    private fun pid(): Long {
        val runtimeName = ManagementFactory.getRuntimeMXBean().name
        return runtimeName.substringBefore('@').toLongOrNull() ?: ProcessHandle.current().pid()
    }

    private fun json(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun log(client: MinecraftClient?, msg: String) {
        println(msg)
    }
}
