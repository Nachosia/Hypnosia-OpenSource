package dev.hypnosia.license

import dev.hypnosia.BuildConfig
import dev.hypnosia.HypnosiaClient
import dev.hypnosia.config.HypnosiaConfigProfiles
import java.net.URI
import java.net.http.HttpTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object AccountManager {
    // Public license/account API. Admin panel is still localhost-only on the VPS.
    private const val DEFAULT_API_URL = "http://127.0.0.1:8080"
    private const val SITE_API_URL = "http://127.0.0.1:3000"
    const val SITE_URL = "http://127.0.0.1:3000"
    private const val MAX_CLOUD_CONFIG_BYTES = 64 * 1024
    private const val MAX_CLOUD_IMAGE_BYTES = 30 * 1024 * 1024
    private val DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(8)
    private val CLOUD_REQUEST_TIMEOUT = Duration.ofSeconds(30)

    private fun chatMessage(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal("§8[§bHypnosia§8] §7$msg"), false)
        }
    }
    private val licenseRegex = Regex("^[A-Za-z0-9]{32}$")
    private val cloudConfigKeyRegex = Regex("^[A-Za-z0-9]{8}$")
    private val localConfigNameRegex = Regex("""^[\p{L}\p{N} _.-]{1,48}$""")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build()
    private const val MOD_API_KEY = BuildConfig.MOD_API_KEY

    private val stateRef = AtomicReference<AccountState>(AccountState.NotChecked)
    private val sessionFutureRef = AtomicReference<CompletableFuture<AccountState>?>(null)
    private val notificationPollRef = AtomicReference<CompletableFuture<List<Pair<String, String>>>?>(null)
    private var lastNotificationPollMs: Long = 0L
    private val shownNotificationIds = mutableSetOf<String>()

    val state: AccountState
        get() = stateRef.get()

    val sessionRoles: List<LicenseRole>
        get() = (state as? AccountState.Valid)?.session?.roles ?: listOf(LicenseRole.USER)

    fun logout() {
        stateRef.set(AccountState.NoAccount)
        val configFile = HypnosiaPaths.rootFile("account.properties")
        if (configFile.exists()) {
            val properties = java.util.Properties()
            properties["account.key"] = ""
            properties["account.id"] = ""
            configFile.outputStream().use { output ->
                properties.store(output, "Hypnosia account config")
            }
        }
    }

    fun hasAccountKey(): Boolean {
        return AccountConfig.loadOrCreate().accountKey != null
    }

    fun startSessionAsync(): CompletableFuture<AccountState> {
        sessionFutureRef.get()?.let { return it }

        val accountConfig = AccountConfig.loadOrCreate()
        val legacyLicense = LicenseConfig.loadOrCreate().licenseKey
        val future = when {
            accountConfig.accountKey != null -> infoAsync(accountConfig.accountKey)
            legacyLicense != null -> createAsync().thenCompose { created ->
                if (created is AccountState.Valid) {
                    applyRoleKeyAsync(legacyLicense)
                } else {
                    CompletableFuture.completedFuture(created)
                }
            }
            else -> completed(AccountState.NoAccount)
        }

        return if (sessionFutureRef.compareAndSet(null, future)) {
            future
        } else {
            sessionFutureRef.get() ?: future
        }
    }

    fun refreshSessionAsync(): CompletableFuture<AccountState> {
        val accountKey = AccountConfig.loadOrCreate().accountKey
            ?: return completed(AccountState.NoAccount)
        return infoAsync(accountKey)
    }

    fun createAsync(): CompletableFuture<AccountState> {
        return postAccount("/api/account/create", extraFields = emptyMap())
    }

    fun checkServiceAvailableAsync(): CompletableFuture<Boolean> {
        val apiUri = secureApiUri(DEFAULT_API_URL)
            ?: return CompletableFuture.completedFuture(false)
        val request = HttpRequest.newBuilder(apiUri.resolve("/health"))
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .GET()
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                response.statusCode() in 200..299 && boolValue(response.body(), "ok") == true
            }
            .exceptionally { false }
    }

    fun markOnlineAsync(displayName: String?): CompletableFuture<Boolean> {
        val session = state as? AccountState.Valid ?: return CompletableFuture.completedFuture(false)
        return postJson(
            "/api/session/online",
            mapOf(
                "accountKey" to session.session.accountKey,
                "hwidHash" to HardwareFingerprint.currentHash64(),
                "displayName" to displayName.orEmpty(),
            ),
        ).thenApply { response ->
            response.statusCode() in 200..299 && boolValue(response.body(), "ok") == true
        }.exceptionally { false }
    }

    // ─── Site integration (127.0.0.1:3000) ───

    fun registerLinkCodeAsync(): CompletableFuture<LinkCodeResult> {
        val apiUri = secureApiUri(SITE_API_URL) ?: return CompletableFuture.completedFuture(LinkCodeResult.Error("INSECURE_ENDPOINT"))
        val accountKey = AccountConfig.loadOrCreate().accountKey
        if (accountKey == null) {
            return CompletableFuture.completedFuture(LinkCodeResult.Error("NO_ACCOUNT_KEY"))
        }
        val request = HttpRequest.newBuilder(apiUri.resolve("/api/mod/link/create"))
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-API-Key", MOD_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(jsonObject(mapOf(
                "accountKey" to accountKey,
                "hwidHash" to HardwareFingerprint.currentHash64(),
            ))))
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    return@thenApply LinkCodeResult.Error("HTTP_${response.statusCode()}")
                }
                val code = stringValue(response.body(), "code") ?: return@thenApply LinkCodeResult.Error("NO_CODE")
                val expiresIn = intValue(response.body(), "expiresIn") ?: 600
                LinkCodeResult.Success(code, expiresIn)
            }
            .exceptionally { LinkCodeResult.Error(networkErrorReason(it)) }
    }

    fun markOfflineAsync(): CompletableFuture<Boolean> {
        val accountKey = AccountConfig.loadOrCreate().accountKey ?: return CompletableFuture.completedFuture(false)
        return postJson(
            "/api/session/offline",
            mapOf(
                "accountKey" to accountKey,
                "hwidHash" to HardwareFingerprint.currentHash64(),
            ),
        ).thenApply { response ->
            response.statusCode() in 200..299 && boolValue(response.body(), "ok") == true
        }.exceptionally { false }
    }

    fun tickNotifications(client: MinecraftClient) {
        val player = client.player ?: return
        state as? AccountState.Valid ?: return

        val now = System.currentTimeMillis()
        if (now - lastNotificationPollMs < 5000L) return
        if (notificationPollRef.get() != null) return

        lastNotificationPollMs = now
        val future = pollNotificationsAsync()
        if (!notificationPollRef.compareAndSet(null, future)) return

        future.whenComplete { notifications, _ ->
            notificationPollRef.set(null)
            if (notifications.isNullOrEmpty()) return@whenComplete
            val newOnes = notifications.filter { (id, _) -> shownNotificationIds.add(id) }
            if (newOnes.isEmpty()) return@whenComplete
            client.execute {
                newOnes.forEach { (_, message) ->
                    player.sendMessage(Text.literal("Nachosia $message"), false)
                }
            }
        }
    }

    fun setNameAsync(displayName: String): CompletableFuture<AccountState> {
        val current = (state as? AccountState.Valid)?.session
            ?: return CompletableFuture.completedFuture(AccountState.NoAccount)
        return postAccount(
            path = "/api/account/set-name",
            accountKey = current.accountKey,
            extraFields = mapOf("displayName" to displayName.take(32)),
        )
    }

    fun setContactAsync(contact: String): CompletableFuture<AccountState> {
        val current = (state as? AccountState.Valid)?.session
            ?: return CompletableFuture.completedFuture(AccountState.NoAccount)
        return postAccount(
            path = "/api/account/set-contact",
            accountKey = current.accountKey,
            extraFields = mapOf("contact" to contact.take(96)),
        )
    }

    fun applyRoleKeyAsync(licenseKey: String): CompletableFuture<AccountState> {
        val normalized = licenseKey.trim().uppercase()
        if (!licenseRegex.matches(normalized)) {
            return CompletableFuture.completedFuture(AccountState.ServerRejected("LICENSE_FORMAT"))
        }

        val session = state as? AccountState.Valid
        val base = if (session == null) createAsync() else CompletableFuture.completedFuture(session)
        return base.thenCompose { created ->
            val current = (created as? AccountState.Valid)?.session
                ?: return@thenCompose CompletableFuture.completedFuture(created)
            postAccount(
                path = "/api/account/apply-key",
                accountKey = current.accountKey,
                extraFields = mapOf("licenseKey" to normalized),
            ).thenApply { result ->
                if (result is AccountState.Valid) {
                    LicenseConfig.saveLicenseKey(normalized)
                }
                result
            }
        }
    }

    fun saveCloudConfigAsync(name: String): CompletableFuture<CloudSaveResult> {
        val source = resolveLocalConfigFile(name)
            ?: return CompletableFuture.completedFuture(CloudSaveResult.Error("CONFIG_NAME_FORMAT"))
        if (!source.exists()) {
            return CompletableFuture.completedFuture(CloudSaveResult.Error("LOCAL_CONFIG_NOT_FOUND: ${source.name}"))
        }

        val bytes = HypnosiaConfigProfiles.exportCanonicalBytes(source.name.removeSuffix(".json"))
            ?: runCatching { source.readBytes() }.getOrNull()?.let(HypnosiaConfigProfiles::canonicalizeBytes)
            ?: return CompletableFuture.completedFuture(CloudSaveResult.Error("LOCAL_CONFIG_READ_FAILED"))
        val rawConfigType = detectConfigType(bytes)
        val session = (state as? AccountState.Valid)?.session
        val hasGifRights = session?.roleGifLimitBytes != null && session.roleGifMaxConfigs != null
        val configType = if ((rawConfigType == "GIF" || rawConfigType == "PNG") && !hasGifRights) {
            null
        } else {
            rawConfigType
        }
        val maxBytes = if (configType == "GIF" || configType == "PNG") MAX_CLOUD_IMAGE_BYTES else MAX_CLOUD_CONFIG_BYTES
        val validationError = if (configType == "GIF" || configType == "PNG") {
            if (bytes.size > maxBytes) "CONFIG_TOO_LARGE" else null
        } else {
            validateConfigBytes(bytes, hasGifRights)
        }
        if (validationError != null) {
            chatMessage("§cОшибка выгрузки: $validationError")
            return CompletableFuture.completedFuture(CloudSaveResult.Error(validationError))
        }

        val payload = Base64.getEncoder().encodeToString(bytes)
        val base = if (session == null) createAsync() else CompletableFuture.completedFuture(AccountState.Valid(session))
        return base.thenCompose { created ->
            val current = (created as? AccountState.Valid)?.session
            val fields = linkedMapOf(
                "hwidHash" to HardwareFingerprint.currentHash64(),
                "name" to source.name.removeSuffix(".json"),
                "payloadBase64" to payload,
            )
            if (current != null) {
                fields["accountKey"] = current.accountKey
            }
            configType?.let { fields["configType"] = it }
            LicenseConfig.loadOrCreate().licenseKey?.let { fields["license"] = it }

            postJsonWithRetry("/api/cloud-config/save", fields, CLOUD_REQUEST_TIMEOUT, retries = 1)
                .thenApply { response ->
                    if (response.statusCode() !in 200..299) {
                        chatMessage("§cОшибка сервера: HTTP ${response.statusCode()}")
                        return@thenApply CloudSaveResult.Error("HTTP_${response.statusCode()}")
                    }
                    val body = response.body()
                    if (boolValue(body, "ok") != true) {
                        val status = stringValue(body, "status") ?: "INVALID_RESPONSE"
                        chatMessage("§cОшибка выгрузки: $status")
                        return@thenApply CloudSaveResult.Error(status)
                    }

                    val accountKey = stringValue(body, "accountKey")
                    val accountId = intValue(body, "accountId")
                    if (accountKey != null && accountId != null) {
                        AccountConfig.save(accountKey, accountId)
                    }
                    val key = stringValue(body, "configKey") ?: return@thenApply CloudSaveResult.Error("CONFIG_KEY_MISSING")
                    CloudSaveResult.Saved(
                        configKey = key,
                        used = intValue(body, "used") ?: 0,
                        limit = intValue(body, "limit") ?: 3,
                    )
                }
        }.exceptionally {
            val reason = networkErrorReason(it)
            chatMessage("§cОшибка сети: $reason")
            CloudSaveResult.Error(reason)
        }
    }

    fun loadCloudConfigAsync(configKey: String, outputName: String?): CompletableFuture<CloudLoadResult> {
        val normalized = configKey.trim().uppercase()
        if (!cloudConfigKeyRegex.matches(normalized)) {
            return CompletableFuture.completedFuture(CloudLoadResult.Error("CONFIG_KEY_FORMAT"))
        }

        val fields = linkedMapOf("configKey" to normalized)
        (state as? AccountState.Valid)?.session?.let { session ->
            fields["accountKey"] = session.accountKey
            fields["hwidHash"] = HardwareFingerprint.currentHash64()
        }

        return postJson("/api/cloud-config/load", fields, CLOUD_REQUEST_TIMEOUT)
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    chatMessage("§cОшибка сервера: HTTP ${response.statusCode()}")
                    return@thenApply CloudLoadResult.Error("HTTP_${response.statusCode()}")
                }
                val body = response.body()
                if (boolValue(body, "ok") != true) {
                    val status = stringValue(body, "status") ?: "INVALID_RESPONSE"
                    chatMessage("§cОшибка загрузки: $status")
                    return@thenApply CloudLoadResult.Error(status)
                }
                val payload = stringValue(body, "payloadBase64") ?: return@thenApply CloudLoadResult.Error("PAYLOAD_MISSING")
                val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
                    ?: return@thenApply CloudLoadResult.Error("PAYLOAD_FORMAT")
                val canonicalBytes = HypnosiaConfigProfiles.canonicalizeBytes(bytes)
                    ?: return@thenApply CloudLoadResult.Error("PAYLOAD_SCHEMA_INVALID")
                val validationError = validateConfigBytes(canonicalBytes, true)
                if (validationError != null) {
                    chatMessage("§cОшибка валидации конфига: $validationError")
                    return@thenApply CloudLoadResult.Error(validationError)
                }
                val finalName = outputName?.takeIf { it.isNotBlank() } ?: stringValue(body, "name") ?: normalized
                val target = resolveLocalConfigFile(finalName) ?: resolveLocalConfigFile(normalized)
                    ?: return@thenApply CloudLoadResult.Error("CONFIG_NAME_FORMAT")
                target.parent.createDirectories()
                target.writeBytes(canonicalBytes)
                CloudLoadResult.Loaded(target.name)
            }
            .exceptionally {
                val reason = networkErrorReason(it)
                chatMessage("§cОшибка сети: $reason")
                CloudLoadResult.Error(reason)
            }
    }

    fun listCloudConfigsAsync(): CompletableFuture<CloudListResult> {
        val current = (state as? AccountState.Valid)?.session
            ?: return CompletableFuture.completedFuture(CloudListResult.Error("NO_ACCOUNT"))
        return postJson(
            "/api/cloud-config/list",
            mapOf(
                "accountKey" to current.accountKey,
                "hwidHash" to HardwareFingerprint.currentHash64(),
            ),
            timeout = CLOUD_REQUEST_TIMEOUT,
        ).thenApply { response ->
            if (response.statusCode() !in 200..299) {
                chatMessage("§cОшибка сервера: HTTP ${response.statusCode()}")
                return@thenApply CloudListResult.Error("HTTP_${response.statusCode()}")
            }
            val body = response.body()
            if (boolValue(body, "ok") != true) {
                val status = stringValue(body, "status") ?: "INVALID_RESPONSE"
                chatMessage("§cОшибка получения списка: $status")
                return@thenApply CloudListResult.Error(status)
            }
            CloudListResult.Listed(
                used = intValue(body, "used") ?: 0,
                limit = intValue(body, "limit") ?: 3,
                configs = configSummaries(body),
            )
        }.exceptionally {
            val reason = networkErrorReason(it)
            chatMessage("§cОшибка сети: $reason")
            CloudListResult.Error(reason)
        }
    }

    fun deleteCloudConfigAsync(nameOrKey: String): CompletableFuture<CloudDeleteResult> {
        val current = (state as? AccountState.Valid)?.session
            ?: return CompletableFuture.completedFuture(CloudDeleteResult.Error("NO_ACCOUNT"))
        val raw = nameOrKey.trim()
        val keyFuture = if (cloudConfigKeyRegex.matches(raw.uppercase())) {
            CompletableFuture.completedFuture(raw.uppercase())
        } else {
            listCloudConfigsAsync().thenApply { list ->
                val listed = list as? CloudListResult.Listed ?: return@thenApply null
                listed.configs.firstOrNull {
                    it.name.equals(raw, ignoreCase = true) ||
                        it.name.equals(raw.removeSuffix(".json"), ignoreCase = true)
                }?.configKey
            }
        }

        return keyFuture.thenCompose { key ->
            if (key == null) {
                chatMessage("§cКонфиг не найден: §f$raw")
                return@thenCompose CompletableFuture.completedFuture(CloudDeleteResult.Error("CONFIG_NOT_FOUND"))
            }
            postJson(
                "/api/cloud-config/delete",
                mapOf(
                    "accountKey" to current.accountKey,
                    "hwidHash" to HardwareFingerprint.currentHash64(),
                    "configKey" to key,
                ),
                timeout = CLOUD_REQUEST_TIMEOUT,
            ).thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    chatMessage("§cОшибка сервера: HTTP ${response.statusCode()}")
                    return@thenApply CloudDeleteResult.Error("HTTP_${response.statusCode()}")
                }
                val body = response.body()
                if (boolValue(body, "ok") == true) {
                    CloudDeleteResult.Deleted(key)
                } else {
                    val status = stringValue(body, "status") ?: "INVALID_RESPONSE"
                    chatMessage("§cОшибка удаления: $status")
                    CloudDeleteResult.Error(status)
                }
            }
        }.exceptionally {
            val reason = networkErrorReason(it)
            chatMessage("§cОшибка сети: $reason")
            CloudDeleteResult.Error(reason)
        }
    }

    private fun pollNotificationsAsync(): CompletableFuture<List<Pair<String, String>>> {
        val current = (state as? AccountState.Valid)?.session
            ?: return CompletableFuture.completedFuture(emptyList())
        val apiUri = secureApiUri(SITE_API_URL)
            ?: return CompletableFuture.completedFuture(emptyList())
        val request = HttpRequest.newBuilder(apiUri.resolve("/api/mod/notifications/poll"))
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-API-Key", MOD_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(jsonObject(mapOf(
                "accountKey" to current.accountKey,
            ))))
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) return@thenApply emptyList()
                val body = response.body()
                val error = stringValue(body, "error")
                if (error != null) return@thenApply emptyList()
                notificationMessages(body)
            }.exceptionally { emptyList() }
    }

    private fun infoAsync(accountKey: String): CompletableFuture<AccountState> {
        return postAccount(
            path = "/api/account/info",
            accountKey = accountKey,
            extraFields = emptyMap(),
        )
    }

    private fun postAccount(
        path: String,
        accountKey: String? = null,
        extraFields: Map<String, String>,
    ): CompletableFuture<AccountState> {
        val fields = linkedMapOf("hwidHash" to HardwareFingerprint.currentHash64())
        if (accountKey != null) fields["accountKey"] = accountKey
        fields.putAll(extraFields)

        return postJson(path, fields)
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    return@thenApply AccountState.ServerRejected("HTTP_${response.statusCode()}")
                }
                parseAccountResponse(response.body())
            }
            .exceptionally { AccountState.NetworkError(it.message ?: "NETWORK_ERROR") }
            .thenApply { result ->
                stateRef.set(result)
                if (result is AccountState.Valid) {
                    AccountConfig.save(result.session.accountKey, result.session.accountId)
                }
                result
            }
    }

    private fun parseAccountResponse(body: String): AccountState {
        if (boolValue(body, "ok") != true) {
            return AccountState.ServerRejected(stringValue(body, "status") ?: "INVALID_RESPONSE")
        }
        val accountId = intValue(body, "accountId") ?: return AccountState.InvalidResponse
        val accountKey = stringValue(body, "accountKey") ?: return AccountState.InvalidResponse
        val roles = stringArray(body, "roles").mapNotNull(LicenseRole::parse).ifEmpty { listOf(LicenseRole.USER) }
        val roleIcons = stringObject(body, "roleIcons")
        val roleGradients = stringObject(body, "roleGradients")
        val nickGradients = stringObject(body, "nickGradients")
        return AccountState.Valid(
            AccountSession(
                accountId = accountId,
                accountKey = accountKey,
                createdAt = stringValue(body, "createdAt") ?: "",
                displayName = stringValue(body, "displayName"),
                contact = stringValue(body, "contact"),
                roles = roles,
                roleIcons = roleIcons,
                roleGradients = roleGradients,
                nickGradients = nickGradients,
                cloudUsed = intValue(body, "cloudUsed") ?: 0,
                cloudLimit = intValue(body, "cloudLimit") ?: 3,
                status = stringValue(body, "status") ?: "OK",
                roleGifLimitBytes = longValue(body, "roleGifLimitBytes"),
                roleGifMaxConfigs = intValue(body, "roleGifMaxConfigs"),
                roleCanChangeGradient = boolValue(body, "roleCanChangeGradient") ?: false,
                roleCanResetHwid = boolValue(body, "roleCanResetHwid") ?: false,
                roleHwidResetCount = intValue(body, "roleHwidResetCount") ?: 0,
            ),
        )
    }

    private fun completed(state: AccountState): CompletableFuture<AccountState> {
        stateRef.set(state)
        return CompletableFuture.completedFuture(state)
    }

    private fun postJson(
        path: String,
        fields: Map<String, String>,
        timeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    ): CompletableFuture<HttpResponse<String>> {
        val apiUri = secureApiUri(DEFAULT_API_URL)
            ?: return CompletableFuture.failedFuture(IllegalStateException("INSECURE_ENDPOINT"))
        val request = HttpRequest.newBuilder(apiUri.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonObject(fields)))
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun postJsonWithRetry(
        path: String,
        fields: Map<String, String>,
        timeout: Duration,
        retries: Int,
    ): CompletableFuture<HttpResponse<String>> {
        return postJson(path, fields, timeout).handle { response, error ->
            if (error == null) {
                CompletableFuture.completedFuture(response)
            } else if (retries > 0 && rootCause(error) is HttpTimeoutException) {
                postJsonWithRetry(path, fields, timeout, retries - 1)
            } else {
                CompletableFuture.failedFuture<HttpResponse<String>>(error)
            }
        }.thenCompose { it }
    }

    private fun networkErrorReason(error: Throwable): String {
        return when (rootCause(error)) {
            is HttpTimeoutException -> "NETWORK_TIMEOUT"
            else -> error.message ?: "NETWORK_ERROR"
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun secureApiUri(apiUrl: String): URI? {
        val uri = runCatching { URI.create(apiUrl.trimEnd('/') + "/") }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val localDev = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && localDev)) return null
        return uri
    }

    private fun resolveLocalConfigFile(name: String): Path? {
        val normalizedName = normalizeConfigName(name) ?: return null
        val root = HypnosiaPaths.configsDir.normalize()
        val file = root.resolve("$normalizedName.json").normalize()
        return file.takeIf { it.startsWith(root) }
    }

    private fun normalizeConfigName(name: String): String? {
        val raw = name.trim()
        val withoutExtension = if (raw.endsWith(".json", ignoreCase = true)) raw.dropLast(5) else raw
        val trimmed = withoutExtension
            .trim(' ', '.')
        if (trimmed.isBlank() || trimmed == "." || trimmed == "..") return null
        if (trimmed.any { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) return null
        return trimmed.takeIf { localConfigNameRegex.matches(it) }
    }

    private fun validateConfigBytes(bytes: ByteArray, allowImages: Boolean = false): String? {
        if (bytes.isEmpty()) return "CONFIG_EMPTY"

        val text = decodeUtf8Strict(bytes)
            ?: return "CONFIG_ENCODING"
        val hasImages = text.contains("\"image.data.") || text.contains("\"image.data\"")
        val maxBytes = if (hasImages && allowImages) MAX_CLOUD_IMAGE_BYTES else MAX_CLOUD_CONFIG_BYTES
        if (bytes.size > maxBytes) return "CONFIG_TOO_LARGE"
        if (hasImages && !allowImages) return "GIF_NOT_ALLOWED"

        if (!looksLikeJsonObject(text)) return "CONFIG_JSON_OBJECT_REQUIRED"
        if (!text.contains("\"format\"") || !text.contains("\"hypnosia-config\"")) return "CONFIG_FORMAT_REQUIRED"
        return null
    }

    private fun detectConfigType(bytes: ByteArray): String? {
        // GIF87a / GIF89a
        if (bytes.size >= 6 && bytes[0] == 'G'.toByte() && bytes[1] == 'I'.toByte() && bytes[2] == 'F'.toByte()) return "GIF"
        // PNG
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()) return "PNG"
        return null
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun looksLikeJsonObject(text: String): Boolean {
        return JsonShapeParser(text).parseRootObject()
    }

    private class JsonShapeParser(private val text: String) {
        private var index = 0

        fun parseRootObject(): Boolean {
            skipWhitespace()
            if (!parseObject()) return false
            skipWhitespace()
            return index == text.length
        }

        private fun parseValue(): Boolean {
            skipWhitespace()
            if (index >= text.length) return false
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consumeLiteral("true")
                'f' -> consumeLiteral("false")
                'n' -> consumeLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> false
            }
        }

        private fun parseObject(): Boolean {
            if (!consume('{')) return false
            skipWhitespace()
            if (consume('}')) return true
            while (true) {
                skipWhitespace()
                if (!parseString()) return false
                skipWhitespace()
                if (!consume(':')) return false
                if (!parseValue()) return false
                skipWhitespace()
                if (consume('}')) return true
                if (!consume(',')) return false
            }
        }

        private fun parseArray(): Boolean {
            if (!consume('[')) return false
            skipWhitespace()
            if (consume(']')) return true
            while (true) {
                if (!parseValue()) return false
                skipWhitespace()
                if (consume(']')) return true
                if (!consume(',')) return false
            }
        }

        private fun parseString(): Boolean {
            if (!consume('"')) return false
            while (index < text.length) {
                val char = text[index++]
                when {
                    char == '"' -> return true
                    char.code < 0x20 -> return false
                    char == '\\' -> {
                        if (index >= text.length) return false
                        when (text[index++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                if (index >= text.length || text[index++] !in '0'..'9' && text[index - 1] !in 'a'..'f' && text[index - 1] !in 'A'..'F') {
                                    return false
                                }
                            }
                            else -> return false
                        }
                    }
                }
            }
            return false
        }

        private fun parseNumber(): Boolean {
            if (consume('-') && index >= text.length) return false
            if (consume('0')) {
                if (index < text.length && text[index] in '0'..'9') return false
            } else if (!consumeDigits()) {
                return false
            }
            if (consume('.')) {
                if (!consumeDigits()) return false
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (!consumeDigits()) return false
            }
            return true
        }

        private fun consumeDigits(): Boolean {
            val start = index
            while (index < text.length && text[index] in '0'..'9') index++
            return index > start
        }

        private fun consumeLiteral(literal: String): Boolean {
            if (!text.regionMatches(index, literal, 0, literal.length)) return false
            index += literal.length
            return true
        }

        private fun consume(char: Char): Boolean {
            if (index >= text.length || text[index] != char) return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }

    private fun configSummaries(body: String): List<CloudConfigSummary> {
        val array = Regex(""""configs"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        return Regex("""\{([^{}]*)}""")
            .findAll(array)
            .mapNotNull { match ->
                val item = match.value
                val key = stringValue(item, "configKey") ?: return@mapNotNull null
                CloudConfigSummary(
                    configKey = key,
                    name = stringValue(item, "name") ?: key,
                    disabled = boolValue(item, "disabled") ?: false,
                    updatedAt = stringValue(item, "updatedAt") ?: "",
                    configType = stringValue(item, "configType"),
                    gifApproved = boolValue(item, "gifApproved"),
                )
            }
            .toList()
    }

    private fun notificationMessages(body: String): List<Pair<String, String>> {
        val array = Regex(""""notifications"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        return Regex("""\{([^{}]*)}""")
            .findAll(array)
            .mapNotNull { match ->
                val id = stringValue(match.value, "id") ?: return@mapNotNull null
                val message = stringValue(match.value, "message") ?: return@mapNotNull null
                id to message
            }
            .toList()
    }

    private fun jsonObject(fields: Map<String, String>): String {
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            """"${escapeJson(key)}":"${escapeJson(value)}""""
        }
    }

    private fun boolValue(json: String, name: String): Boolean? {
        return Regex(""""${Regex.escape(name)}"\s*:\s*(true|false)""")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toBooleanStrictOrNull()
    }

    private fun intValue(json: String, name: String): Int? {
        return Regex(""""${Regex.escape(name)}"\s*:\s*(\d+)""")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun longValue(json: String, name: String): Long? {
        return Regex(""""${Regex.escape(name)}"\s*:\s*(\d+)""")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private fun stringValue(json: String, name: String): String? {
        val key = "\"$name\""
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
            return json.substring(valueStart, valueEnd).unescapeJson()
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

    private fun stringArray(json: String, name: String): List<String> {
        val body = Regex(""""${Regex.escape(name)}"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        return Regex(""""((?:\\.|[^"\\])*)"""")
            .findAll(body)
            .map { it.groupValues[1].unescapeJson() }
            .toList()
    }

    private fun stringObject(json: String, name: String): Map<String, String> {
        val body = Regex(""""${Regex.escape(name)}"\s*:\s*\{(.*?)}""", RegexOption.DOT_MATCHES_ALL)
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyMap()
        return Regex(""""((?:\\.|[^"\\])*)"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .findAll(body)
            .associate { it.groupValues[1].unescapeJson() to it.groupValues[2].unescapeJson() }
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun String.unescapeJson(): String {
        return replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}

data class AccountSession(
    val accountId: Int,
    val accountKey: String,
    val createdAt: String,
    val displayName: String?,
    val contact: String?,
    val roles: List<LicenseRole>,
    val roleIcons: Map<String, String>,
    val roleGradients: Map<String, String>,
    val nickGradients: Map<String, String>,
    val cloudUsed: Int,
    val cloudLimit: Int,
    val status: String,
    val roleGifLimitBytes: Long? = null,
    val roleGifMaxConfigs: Int? = null,
    val roleCanChangeGradient: Boolean = false,
    val roleCanResetHwid: Boolean = false,
    val roleHwidResetCount: Int = 0,
)

data class CloudConfigSummary(
    val configKey: String,
    val name: String,
    val disabled: Boolean,
    val updatedAt: String,
    val configType: String? = null,
    val gifApproved: Boolean? = null,
)

sealed class AccountState {
    data object NotChecked : AccountState()
    data object NoAccount : AccountState()
    data object InvalidResponse : AccountState()
    data class Valid(val session: AccountSession) : AccountState()
    data class ServerRejected(val reason: String) : AccountState()
    data class NetworkError(val message: String) : AccountState()
}

sealed class CloudSaveResult {
    data class Saved(val configKey: String, val used: Int, val limit: Int) : CloudSaveResult()
    data class Error(val reason: String) : CloudSaveResult()
}

sealed class CloudLoadResult {
    data class Loaded(val fileName: String) : CloudLoadResult()
    data class Error(val reason: String) : CloudLoadResult()
}

sealed class CloudListResult {
    data class Listed(val used: Int, val limit: Int, val configs: List<CloudConfigSummary>) : CloudListResult()
    data class Error(val reason: String) : CloudListResult()
}

sealed class CloudDeleteResult {
    data class Deleted(val configKey: String) : CloudDeleteResult()
    data class Error(val reason: String) : CloudDeleteResult()
}
