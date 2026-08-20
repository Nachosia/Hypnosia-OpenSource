package dev.hypnosia.license

import dev.hypnosia.BuildConfig
import dev.hypnosia.playtime.ActivityTracker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Timer
import java.util.concurrent.CompletableFuture
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.timerTask

object SessionManager {
    private const val SITE_API_URL = "http://127.0.0.1:3000"
    private const val MOD_API_KEY = BuildConfig.MOD_API_KEY
    private const val MOD_SECRET_KEY = BuildConfig.MOD_SECRET_KEY // Must match server env.MOD_SECRET_KEY
    private const val HEARTBEAT_INTERVAL_MS = 120_000L // 2 minutes
    private const val BATCH_INTERVAL_MS = 600_000L // 10 minutes
    private const val REQUEST_TIMEOUT_SECONDS = 8L

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build()

    @Volatile
    private var currentSessionToken: String? = null
    private var heartbeatTimer: Timer? = null
    private var batchTimer: Timer? = null

    @Volatile
    private var currentAccountKey: String? = null
    @Volatile
    private var currentAccountId: Int = 0
    @Volatile
    private var currentHwidHash: String? = null

    fun startSession(
        accountKey: String,
        hwidHash: String,
        accountId: Int = 0,
        minecraftVersion: String? = null,
        modVersion: String? = null,
    ): CompletableBoolean {
        currentAccountKey = accountKey
        currentHwidHash = hwidHash
        currentAccountId = accountId

        val apiUri = secureSiteUri() ?: return CompletableFuture.completedFuture(false)
        val fields = linkedMapOf(
            "accountKey" to accountKey,
            "hwidHash" to hwidHash,
        )
        minecraftVersion?.let { fields["minecraftVersion"] = it }
        modVersion?.let { fields["modVersion"] = it }

        val request = HttpRequest.newBuilder(apiUri.resolve("/api/mod/session/start"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("X-API-Key", MOD_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(jsonObject(fields)))
            .build()

        println("[Hypnosia] Starting session for accountId=$accountId")

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    println("[Hypnosia] Session start failed: HTTP ${response.statusCode()}, body=${response.body()}")
                } else {
                    val body = response.body()
                    val token = stringValue(body, "sessionToken")
                    if (token != null) {
                        currentSessionToken = token
                        println("[Hypnosia] Session started successfully, token=${token.take(8)}...")
                        startHeartbeatTimer()
                    } else {
                        println("[Hypnosia] Session start: no sessionToken in response, batch will work without it")
                    }
                }
                // Always start batch timer so playtime is tracked even if sessionToken is missing
                startBatchTimer()
                true
            }
            .exceptionally { ex ->
                println("[Hypnosia] Session start exception: ${ex.message}")
                // Still start batch timer on exception — playtime must accumulate
                startBatchTimer()
                true
            }
    }

    fun sendHeartbeat(): CompletableBoolean {
        val token = currentSessionToken ?: return CompletableFuture.completedFuture(false)
        return doHeartbeat(token)
    }

    fun endSession(flush: Boolean = true): CompletableBoolean {
        val token = currentSessionToken
        println("[Hypnosia] Ending session, flush=$flush, hasToken=${token != null}")
        stopHeartbeatTimer()
        stopBatchTimer()

        val flushFuture = if (flush) {
            sendBatchInternal(status = "offline", isEmergency = true)
        } else {
            CompletableFuture.completedFuture(true)
        }

        currentSessionToken = null
        currentAccountKey = null
        currentHwidHash = null
        currentAccountId = 0

        if (token == null) {
            return flushFuture.thenApply { it }
        }

        val apiUri = secureSiteUri() ?: return CompletableFuture.completedFuture(false)
        val request = HttpRequest.newBuilder(apiUri.resolve("/api/mod/session/end"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("X-API-Key", MOD_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(jsonObject(mapOf("sessionToken" to token))))
            .build()

        return flushFuture.thenCompose {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply { response -> response.statusCode() in 200..299 }
                .exceptionally { false }
        }
    }

    fun sendBatchNow(): CompletableBoolean {
        println("[Hypnosia] Manual batch send requested")
        return sendBatchInternal(status = "online")
    }

    fun sendEmergencyFlush(): CompletableBoolean {
        println("[Hypnosia] Emergency flush requested")
        return sendBatchInternal(status = "offline", isEmergency = true)
    }

    private fun sendBatchInternal(status: String, isEmergency: Boolean = false): CompletableBoolean {
        println("[Hypnosia] sendBatchInternal ENTER: status=$status, emergency=$isEmergency, accountKey=${currentAccountKey != null}, hwidHash=${currentHwidHash != null}, sessionToken=${currentSessionToken != null}")
        val accountKey = currentAccountKey ?: run {
            println("[Hypnosia] sendBatchInternal early return: accountKey is null")
            return CompletableFuture.completedFuture(false)
        }
        val hwidHash = currentHwidHash ?: run {
            println("[Hypnosia] sendBatchInternal early return: hwidHash is null")
            return CompletableFuture.completedFuture(false)
        }
        val accountId = currentAccountId
        val token = currentSessionToken

        val activeMinutes = ActivityTracker.activeMinutesAccumulated
        val servers = ActivityTracker.getServerMinutes()

        if (activeMinutes == 0 && status == "online" && !isEmergency) {
            println("[Hypnosia] Skipping batch: no active minutes, status=$status")
            return CompletableFuture.completedFuture(true)
        }

        val timestamp = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        // v2.2 HMAC payload: accountKey:sessionToken:timestamp:activeMinutes:status:nonce
        // sessionToken may be null — backend accepts batch on accountKey + hwidHash alone
        val payload = if (token != null) {
            "$accountKey:$token:$timestamp:$activeMinutes:$status:$nonce"
        } else {
            "$accountKey::$timestamp:$activeMinutes:$status:$nonce"
        }
        val signature = hmacSha256(MOD_SECRET_KEY, payload)

        // Build servers array JSON
        val serversJson = servers
            .filter { it.second > 0 }
            .joinToString(prefix = "[", postfix = "]") { (srvIp, mins) ->
                """{"serverIp":"${escapeJson(srvIp)}","activeMinutes":$mins}"""
            }

        val body = buildString {
            append("{")
            append(""""accountId":$accountId,""")
            append(""""accountKey":"${escapeJson(accountKey)}",""")
            append(""""hwidHash":"${escapeJson(hwidHash)}",""")
            if (token != null) {
                append(""""sessionToken":"${escapeJson(token)}",""")
            }
            append(""""activeMinutes":$activeMinutes,""")
            append(""""status":"$status",""")
            append(""""timestamp":$timestamp,""")
            append(""""nonce":"${escapeJson(nonce)}",""")
            append(""""signature":"${escapeJson(signature)}",""")
            append(""""servers":$serversJson""")
            if (isEmergency) {
                append(",\"isEmergency\":true")
            }
            append("}")
        }

        println("[Hypnosia] Sending batch: status=$status, activeMinutes=$activeMinutes, servers=$servers, emergency=$isEmergency, payloadSize=${body.length}")

        val apiUri = secureSiteUri()
        if (apiUri == null) {
            println("[Hypnosia] sendBatchInternal early return: secureSiteUri() is null")
            return CompletableFuture.completedFuture(false)
        }
        val request = HttpRequest.newBuilder(apiUri.resolve("/api/mod/session/batch"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("X-API-Key", MOD_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() in 200..299) {
                    println("[Hypnosia] Batch sent successfully: HTTP ${response.statusCode()}, body=${response.body()}")
                    ActivityTracker.clearAccumulated()
                    true
                } else {
                    println("[Hypnosia] Batch failed: HTTP ${response.statusCode()}, body=${response.body()}")
                    false
                }
            }
            .exceptionally { ex ->
                println("[Hypnosia] Batch exception: ${ex.message}")
                false
            }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun startBatchTimer() {
        stopBatchTimer()
        println("[Hypnosia] Batch timer started (interval=${BATCH_INTERVAL_MS}ms)")
        batchTimer = Timer("HypnosiaBatch", true).apply {
            scheduleAtFixedRate(timerTask {
                try {
                    println("[Hypnosia] Batch timer fired, activeMinutes=${ActivityTracker.activeMinutesAccumulated}")
                    if (ActivityTracker.activeMinutesAccumulated > 0) {
                        val future = sendBatchInternal("online")
                        println("[Hypnosia] Batch timer: sendBatchInternal returned future=$future")
                    } else {
                        println("[Hypnosia] No active minutes to send, skipping batch")
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                    println("[Hypnosia] CRITICAL: Batch timer task crashed: ${t.message}")
                }
            }, BATCH_INTERVAL_MS, BATCH_INTERVAL_MS)
        }
    }

    private fun stopBatchTimer() {
        batchTimer?.cancel()
        batchTimer = null
    }

    fun hasActiveSession(): Boolean = currentAccountKey != null && currentHwidHash != null

    private fun doHeartbeat(token: String): CompletableBoolean {
        val apiUri = secureSiteUri() ?: return CompletableFuture.completedFuture(false)
        val request = HttpRequest.newBuilder(apiUri.resolve("/api/mod/session/heartbeat"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("X-API-Key", MOD_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(jsonObject(mapOf("sessionToken" to token))))
            .build()

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response -> response.statusCode() in 200..299 }
            .exceptionally { false }
    }

    private fun startHeartbeatTimer() {
        stopHeartbeatTimer()
        heartbeatTimer = Timer("HypnosiaHeartbeat", true).apply {
            scheduleAtFixedRate(timerTask {
                val token = currentSessionToken ?: return@timerTask
                doHeartbeat(token)
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun stopHeartbeatTimer() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private fun secureSiteUri(): URI? {
        val uri = runCatching { URI.create(SITE_API_URL.trimEnd('/') + "/") }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val localDev = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && localDev)) return null
        return uri
    }

    private fun jsonObject(fields: Map<String, String>): String {
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            """"${escapeJson(key)}":"${escapeJson(value)}""""
        }
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun stringValue(json: String, name: String): String? {
        val key = """"$name"""
        var searchFrom = 0
        while (true) {
            val keyIndex = json.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            var index = keyIndex + key.length
            while (index < json.length && json[index].isWhitespace()) index++
            if (index >= json.length || json[index] != ':') {
                searchFrom = keyIndex + key.length
                continue
            }
            index++
            while (index < json.length && json[index].isWhitespace()) index++
            if (index + 4 <= json.length && json.regionMatches(index, "null", 0, 4)) return null
            if (index >= json.length || json[index] != '"') return null
            val valueStart = index + 1
            val valueEnd = findJsonStringEnd(json, valueStart) ?: return null
            return json.substring(valueStart, valueEnd)
        }
    }

    private fun findJsonStringEnd(value: String, start: Int): Int? {
        var escaped = false
        for (i in start until value.length) {
            val c = value[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                return i
            }
        }
        return null
    }
}

private typealias CompletableBoolean = CompletableFuture<Boolean>
