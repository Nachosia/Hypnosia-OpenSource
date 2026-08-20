package dev.hypnosia.licenseserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CountDownLatch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 8080
private const val DEFAULT_LICENSE_FILE = "data/licenses.tsv"
private const val DEFAULT_ACCOUNT_FILE = "data/accounts.tsv"
private const val DEFAULT_ACCOUNT_ROLE_LINK_FILE = "data/account-role-links.tsv"
private const val DEFAULT_CLOUD_CONFIG_FILE = "data/cloud-configs.tsv"
private const val DEFAULT_PRESENCE_FILE = "data/account-presence.tsv"
private const val DEFAULT_NOTIFICATION_FILE = "data/notifications.tsv"
private const val DEFAULT_ROLE_SETTINGS_FILE = "data/role-settings.tsv"
private const val DEFAULT_ROLE_ICON_DIR = "data/role-icons"
private const val DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT = 3
private const val SPONSOR_CLOUD_CONFIGS_PER_ACCOUNT = 15
private const val STAFF_CLOUD_CONFIGS_PER_ACCOUNT = 100
private const val DEFAULT_USER_CLOUD_COOLDOWN_SECONDS = 15
private const val MAX_CLOUD_CONFIG_PAYLOAD_BYTES = 64 * 1024
private const val MAX_CLOUD_CONFIG_REQUEST_CHARS = 128 * 1024
private const val MAX_CLOUD_IMAGE_PAYLOAD_BYTES = 30 * 1024 * 1024
private const val MAX_CLOUD_IMAGE_REQUEST_CHARS = 50 * 1024 * 1024
private const val CLOUD_CONFIG_FORMAT = "hypnosia-config"
private const val CLOUD_CONFIG_VERSION = 1
private const val MAX_CLOUD_CONFIG_SETTINGS = 512
private const val MAX_CLOUD_CONFIG_SETTING_KEY_LENGTH = 96
private const val MAX_CLOUD_CONFIG_SETTING_VALUE_LENGTH = 512
private const val ONLINE_TTL_SECONDS = 90L

private val defaultRoles = setOf("USER", "SPONSOR", "QA", "HELPER", "MODERATOR", "ADMIN", "OWNER")
private val roleRegex = Regex("^[A-Z][A-Z0-9_]{1,31}$")
private val licenseRegex = Regex("^[A-Z0-9]{32}$")
private val accountKeyRegex = Regex("^[A-Z0-9]{32}$")
private val cloudConfigKeyRegex = Regex("^[A-Z0-9]{8}$")
private val cloudConfigNameRegex = Regex("""^[\p{L}\p{N} _.-]{1,48}$""")
private val hwidHashRegex = Regex("^[A-Fa-f0-9]{64}$")
private val cloudConfigManagedPrefixes = listOf("hud.", "watermark.", "target.", "hotkeys.", "module.", "world.", "visuals.", "other.", "icons.", "theme.")

fun main(args: Array<String>) {
    val host = env("HYPNOSIA_LICENSE_HOST") ?: DEFAULT_HOST
    val port = env("HYPNOSIA_LICENSE_PORT")?.toIntOrNull() ?: DEFAULT_PORT

    val state = ServerState(
        licenses = LicenseStorage(Path.of(env("HYPNOSIA_LICENSE_DATA") ?: DEFAULT_LICENSE_FILE)),
        accounts = AccountStorage(Path.of(env("HYPNOSIA_ACCOUNT_DATA") ?: DEFAULT_ACCOUNT_FILE)),
        roleLinks = AccountRoleLinkStorage(Path.of(env("HYPNOSIA_ACCOUNT_ROLE_LINK_DATA") ?: DEFAULT_ACCOUNT_ROLE_LINK_FILE)),
        cloudConfigs = CloudConfigStorage(Path.of(env("HYPNOSIA_CLOUD_CONFIG_DATA") ?: DEFAULT_CLOUD_CONFIG_FILE)),
        presence = PresenceStorage(Path.of(env("HYPNOSIA_PRESENCE_DATA") ?: DEFAULT_PRESENCE_FILE)),
        notifications = NotificationStorage(Path.of(env("HYPNOSIA_NOTIFICATION_DATA") ?: DEFAULT_NOTIFICATION_FILE)),
        roleSettings = RoleSettingsStorage(Path.of(env("HYPNOSIA_ROLE_SETTINGS_DATA") ?: DEFAULT_ROLE_SETTINGS_FILE)),
        roleIconDir = Path.of(env("HYPNOSIA_ROLE_ICON_DIR") ?: DEFAULT_ROLE_ICON_DIR),
        rateLimiter = RequestRateLimiter(),
    )
    state.roleIconDir.createDirectories()

    if (args.isNotEmpty()) {
        ConsoleAdmin(state).execute(args.toList())
        return
    }

    val adminApiKey = env("HYPNOSIA_ADMIN_API_KEY")

    val server = HttpServer.create(InetSocketAddress(host, port), 0)
    server.createContext("/api/license/check") { exchange -> checkLicense(exchange, state) }
    server.createContext("/api/account/create") { exchange -> createAccount(exchange, state) }
    server.createContext("/api/account/info") { exchange -> accountInfo(exchange, state) }
    server.createContext("/api/account/set-name") { exchange -> setAccountName(exchange, state) }
    server.createContext("/api/account/set-contact") { exchange -> setAccountContact(exchange, state) }
    server.createContext("/api/account/apply-key") { exchange -> applyAccountKey(exchange, state) }
    server.createContext("/api/account/reset-hwid") { exchange -> resetAccountHwid(exchange, state) }
    server.createContext("/api/cloud-config/save") { exchange -> saveCloudConfig(exchange, state) }
    server.createContext("/api/cloud-config/load") { exchange -> loadCloudConfig(exchange, state) }
    server.createContext("/api/cloud-config/delete") { exchange -> deleteCloudConfig(exchange, state) }
    server.createContext("/api/cloud-config/list") { exchange -> listCloudConfigs(exchange, state) }
    server.createContext("/api/session/online") { exchange -> sessionOnline(exchange, state) }
    server.createContext("/api/session/offline") { exchange -> sessionOffline(exchange, state) }
    server.createContext("/api/notifications/poll") { exchange -> pollNotifications(exchange, state) }
    server.createContext("/api/role-icons/") { exchange -> roleIconAsset(exchange, state) }
    server.createContext("/api/admin/accounts") { exchange -> adminAccounts(exchange, state, adminApiKey) }
    server.createContext("/api/admin/license/create-or-update") { exchange -> adminCreateOrUpdateLicense(exchange, state, adminApiKey) }
    server.createContext("/api/admin/gif-configs") { exchange -> adminGifConfigs(exchange, state, adminApiKey) }
    server.createContext("/api/admin/gif-configs/approve") { exchange -> adminApproveGifConfig(exchange, state, adminApiKey) }
    server.createContext("/api/admin/gif-configs/deny") { exchange -> adminDenyGifConfig(exchange, state, adminApiKey) }
    server.createContext("/health") { exchange -> json(exchange, 200, """{"ok":true}""") }
    server.executor = Executors.newFixedThreadPool(8)
    server.start()

    println("Hypnosia license server listening on http://$host:$port")
    println("License data: ${state.licenses.filePath.toAbsolutePath()}")
    println("Account data: ${state.accounts.filePath.toAbsolutePath()}")
    println("Type 'help' for commands.")

    Runtime.getRuntime().addShutdownHook(Thread { server.stop(2) })

    val consoleEnabled = env("HYPNOSIA_LICENSE_CONSOLE")
        ?.toBooleanStrictOrNull()
        ?: true

    if (consoleEnabled) {
        ConsoleAdmin(state).loop()
        server.stop(0)
    } else {
        CountDownLatch(1).await()
    }
}

private data class ServerState(
    val licenses: LicenseStorage,
    val accounts: AccountStorage,
    val roleLinks: AccountRoleLinkStorage,
    val cloudConfigs: CloudConfigStorage,
    val presence: PresenceStorage,
    val notifications: NotificationStorage,
    val roleSettings: RoleSettingsStorage,
    val roleIconDir: Path,
    val rateLimiter: RequestRateLimiter,
)

private class ConsoleAdmin(private val state: ServerState) {
    fun loop() {
        while (true) {
            print("hypnosia-license> ")
            val line = readlnOrNull()?.trim() ?: break
            if (line.isBlank()) continue

            val args = splitArgs(line)
            if (!execute(args)) return
        }
    }

    fun execute(args: List<String>): Boolean {
        val command = args.firstOrNull()?.lowercase(Locale.ROOT) ?: return true
        try {
            when (command) {
                "help", "?" -> help()
                "list", "ls" -> listLicenses()
                "show" -> showLicense(args)
                "create", "new" -> createLicense(args)
                "role" -> updateRole(args)
                "expires", "expire" -> updateExpires(args)
                "disable" -> setLicenseDisabled(args, disabled = true)
                "enable" -> setLicenseDisabled(args, disabled = false)
                "reset", "reset-hwid" -> resetLicenseHwid(args)
                "delete", "del", "remove" -> deleteLicense(args)
                "accounts", "account-list" -> listAccounts()
                "account-show" -> showAccount(args)
                "account-create" -> createAccount(args)
                "exit", "quit", "stop" -> return false
                else -> println("Unknown command '$command'. Type 'help'.")
            }
        } catch (error: IllegalArgumentException) {
            println("Error: ${error.message}")
        } catch (error: Throwable) {
            println("Unexpected error: ${error.message}")
            error.printStackTrace()
        }
        return true
    }

    private fun help() {
        println(
            """
            Commands:
              list
              show <license-key>
              create <role> [YYYY-MM-DD|never] [custom-32-char-key]
              role <license-key> <role>
              expires <license-key> <YYYY-MM-DD|never>
              disable <license-key>
              enable <license-key>
              reset-hwid <license-key>
              delete <license-key>
              accounts
              account-show <account-id>
              account-create <64-char-hwid-hash> [display-name]
              exit

            Default roles: ${defaultRoles.joinToString(", ")}
            """.trimIndent(),
        )
    }

    private fun listLicenses() {
        val records = state.licenses.all()
        if (records.isEmpty()) {
            println("No licenses.")
            return
        }

        records.forEach { record ->
            val status = when {
                record.disabled -> "disabled"
                record.isExpired() -> "expired"
                record.hwidHash == null -> "not-bound"
                else -> "bound"
            }
            println("${record.licenseKey} | ${record.role} | $status | expires=${record.expiresAt ?: "never"} | hwid=${record.hwidHash?.take(12) ?: "-"}")
        }
    }

    private fun showLicense(args: List<String>) {
        val key = args.getLicenseKey(1)
        val record = state.licenses.find(key) ?: throw IllegalArgumentException("License not found")
        println("key       : ${record.licenseKey}")
        println("role      : ${record.role}")
        println("disabled  : ${record.disabled}")
        println("created   : ${record.createdAt}")
        println("expires   : ${record.expiresAt ?: "never"}")
        println("boundAt   : ${record.boundAt ?: "-"}")
        println("hwidHash  : ${record.hwidHash ?: "-"}")
    }

    private fun createLicense(args: List<String>) {
        val role = args.getOrNull(1)?.uppercaseRole() ?: throw IllegalArgumentException("Usage: create <role> [YYYY-MM-DD|never] [key]")
        val expiresAt = parseExpiresArgument(args.getOrNull(2))
        val key = args.getOrNull(3)?.uppercase(Locale.ROOT) ?: generateKey(32)
        require(licenseRegex.matches(key)) { "License key must be 32 uppercase letters/digits" }

        state.licenses.create(
            LicenseRecord(
                licenseKey = key,
                role = role,
                hwidHash = null,
                createdAt = Instant.now().toString(),
                boundAt = null,
                expiresAt = expiresAt,
                disabled = false,
            ),
        )
        println("Created: $key | role=$role | expires=${expiresAt ?: "never"}")
    }

    private fun updateRole(args: List<String>) {
        val key = args.getLicenseKey(1)
        val role = args.getOrNull(2)?.uppercaseRole() ?: throw IllegalArgumentException("Usage: role <key> <role>")
        state.licenses.updateExisting(key) { copy(role = role) }
        println("Updated role: $key -> $role")
    }

    private fun updateExpires(args: List<String>) {
        val key = args.getLicenseKey(1)
        val expiresAt = parseExpiresArgument(args.getOrNull(2))
        state.licenses.updateExisting(key) { copy(expiresAt = expiresAt) }
        println("Updated expires: $key -> ${expiresAt ?: "never"}")
    }

    private fun setLicenseDisabled(args: List<String>, disabled: Boolean) {
        val key = args.getLicenseKey(1)
        state.licenses.updateExisting(key) { copy(disabled = disabled) }
        println("${if (disabled) "Disabled" else "Enabled"}: $key")
    }

    private fun resetLicenseHwid(args: List<String>) {
        val key = args.getLicenseKey(1)
        state.licenses.updateExisting(key) { copy(hwidHash = null, boundAt = null) }
        state.roleLinks.unlinkLicense(key)
        println("HWID reset: $key")
    }

    private fun deleteLicense(args: List<String>) {
        val key = args.getLicenseKey(1)
        state.licenses.deleteExisting(key)
        state.roleLinks.unlinkLicense(key)
        println("Deleted: $key")
    }

    private fun listAccounts() {
        val accounts = state.accounts.all()
        if (accounts.isEmpty()) {
            println("No accounts.")
            return
        }
        accounts.forEach { account ->
            val roles = accountRoles(account, state).joinToString(", ")
            println("#${account.id} | ${account.displayName ?: "-"} | ${if (account.disabled) "disabled" else "active"} | roles=$roles | hwid=${account.hwidHash.take(12)}")
        }
    }

    private fun showAccount(args: List<String>) {
        val id = args.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Usage: account-show <account-id>")
        val account = state.accounts.findById(id) ?: throw IllegalArgumentException("Account not found")
        println("id          : ${account.id}")
        println("accountKey  : ${account.accountKey}")
        println("displayName : ${account.displayName ?: "-"}")
        println("disabled    : ${account.disabled}")
        println("cloud ban   : ${account.cloudUploadBanned}")
        println("created     : ${account.createdAt}")
        println("hwidHash    : ${account.hwidHash}")
        val roles = accountRoles(account, state)
        println("roles       : ${roles.joinToString(", ")}")
        println("cloud slots : ${state.cloudConfigs.usedSlots(account.id)}/${roleSettingsForRoles(roles, state).cloudLimit}")
    }

    private fun createAccount(args: List<String>) {
        val hwidHash = args.getHwid(1)
        val displayName = args.drop(2).joinToString(" ").trim().takeIf { it.isNotBlank() }
        val account = state.accounts.createOrGetByHwid(hwidHash, displayName)
        println("Account #${account.id}: key=${account.accountKey}")
    }

    private fun List<String>.getLicenseKey(index: Int): String {
        val key = getOrNull(index)?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing license key")
        require(licenseRegex.matches(key)) { "Invalid license key format" }
        return key
    }

    private fun List<String>.getHwid(index: Int): String {
        val hwid = getOrNull(index)?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing HWID hash")
        require(hwidHashRegex.matches(hwid)) { "Invalid HWID hash" }
        return hwid
    }
}

private class LicenseStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun all(): List<LicenseRecord> = lock.read { readAll().sortedByDescending { it.createdAt } }

    fun find(key: String): LicenseRecord? = lock.read { readAll().firstOrNull { it.licenseKey == key } }

    fun create(record: LicenseRecord) {
        lock.write {
            val records = readAll().toMutableList()
            require(records.none { it.licenseKey == record.licenseKey }) { "License already exists" }
            records += record
            writeAll(records)
        }
    }

    fun updateExisting(key: String, transform: LicenseRecord.() -> LicenseRecord) {
        lock.write {
            var found = false
            val records = readAll().map { record ->
                if (record.licenseKey == key) {
                    found = true
                    record.transform()
                } else {
                    record
                }
            }
            require(found) { "License not found" }
            writeAll(records)
        }
    }

    fun deleteExisting(key: String) {
        lock.write {
            val records = readAll()
            require(records.any { it.licenseKey == key }) { "License not found" }
            writeAll(records.filterNot { it.licenseKey == key })
        }
    }

    fun checkAndBind(key: String, hwidHash: String): LicenseCheckResult {
        return lock.write {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.licenseKey == key }
            if (index == -1) return@write LicenseCheckResult.Invalid("LICENSE_NOT_FOUND")

            val record = records[index]
            val invalid = record.invalidFor(hwidHash)
            if (invalid != null) return@write LicenseCheckResult.Invalid(invalid)

            if (record.hwidHash == null) {
                val updated = record.copy(hwidHash = hwidHash.uppercase(Locale.ROOT), boundAt = Instant.now().toString())
                records[index] = updated
                writeAll(records)
                return@write LicenseCheckResult.Valid(updated.role, "BOUND_NOW", updated.expiresAt)
            }

            LicenseCheckResult.Valid(record.role, "OK", record.expiresAt)
        }
    }

    private fun readAll(): List<LicenseRecord> {
        return filePath.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull(LicenseRecord::fromLine)
    }

    private fun writeAll(records: List<LicenseRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private class AccountStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun all(): List<AccountRecord> = lock.read { readAll().sortedBy { it.id } }

    fun findById(id: Int): AccountRecord? = lock.read { readAll().firstOrNull { it.id == id } }

    fun findByKey(accountKey: String): AccountRecord? = lock.read { readAll().firstOrNull { it.accountKey == accountKey } }

    fun findByHwid(hwidHash: String): AccountRecord? {
        return lock.read { readAll().firstOrNull { it.hwidHash.equals(hwidHash, ignoreCase = true) } }
    }

    fun createOrGetByHwid(hwidHash: String, displayName: String? = null): AccountRecord {
        return lock.write {
            val records = readAll().toMutableList()
            val existing = records.firstOrNull { it.hwidHash.equals(hwidHash, ignoreCase = true) }
            if (existing != null) {
                if (!displayName.isNullOrBlank() && existing.displayName != displayName) {
                    val updated = existing.copy(displayName = displayName)
                    records[records.indexOf(existing)] = updated
                    writeAll(records)
                    return@write updated
                }
                return@write existing
            }

            val now = Instant.now().toString()
            val usedKeys = records.asSequence().map { it.accountKey }.toSet()
            val account = AccountRecord(
                id = (records.maxOfOrNull { it.id } ?: 0) + 1,
                accountKey = generateUniqueKey(32, usedKeys),
                hwidHash = hwidHash.uppercase(Locale.ROOT),
                displayName = displayName?.take(32)?.takeIf { it.isNotBlank() },
                contact = null,
                createdAt = now,
                disabled = false,
                cloudUploadBanned = false,
            )
            records += account
            writeAll(records)
            account
        }
    }

    fun updateExisting(id: Int, transform: AccountRecord.() -> AccountRecord) {
        lock.write {
            var found = false
            val records = readAll().map { account ->
                if (account.id == id) {
                    found = true
                    account.transform()
                } else {
                    account
                }
            }
            require(found) { "Account not found" }
            writeAll(records)
        }
    }

    fun bindHwidIfEmpty(accountId: Int, hwidHash: String): AccountRecord? {
        return lock.write {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.id == accountId }
            if (index < 0) return@write null
            val account = records[index]
            if (account.hwidHash.isNotBlank()) return@write account
            val updated = account.copy(hwidHash = hwidHash.uppercase(Locale.ROOT))
            records[index] = updated
            writeAll(records)
            updated
        }
    }

    private fun readAll(): List<AccountRecord> {
        return filePath.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull(AccountRecord::fromLine)
    }

    private fun writeAll(records: List<AccountRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private class AccountRoleLinkStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun all(): List<AccountRoleLinkRecord> = lock.read { readAll().exclusiveActiveLinks() }

    fun linksForAccount(accountId: Int): List<AccountRoleLinkRecord> {
        return lock.read { readAll().exclusiveActiveLinks().filter { it.accountId == accountId && !it.disabled } }
    }

    fun activeAccountIdForLicense(licenseKey: String): Int? {
        return lock.read {
            readAll()
                .exclusiveActiveLinks()
                .firstOrNull { it.licenseKey == licenseKey && !it.disabled }
                ?.accountId
        }
    }

    fun linkExclusive(accountId: Int, licenseKey: String) {
        lock.write {
            val records = readAll().toMutableList()
            val existing = records.indexOfFirst { it.accountId == accountId && it.licenseKey == licenseKey }
            for (index in records.indices) {
                val record = records[index]
                if (record.licenseKey == licenseKey && record.accountId != accountId && !record.disabled) {
                    records[index] = record.copy(disabled = true)
                }
            }
            if (existing >= 0) {
                records[existing] = records[existing].copy(disabled = false)
            } else {
                records += AccountRoleLinkRecord(
                    accountId = accountId,
                    licenseKey = licenseKey,
                    grantedAt = Instant.now().toString(),
                    disabled = false,
                )
            }
            writeAll(records)
        }
    }

    fun unlinkLicense(licenseKey: String) {
        lock.write {
            val records = readAll().map { record ->
                if (record.licenseKey == licenseKey) record.copy(disabled = true) else record
            }
            writeAll(records)
        }
    }

    private fun readAll(): List<AccountRoleLinkRecord> {
        return filePath.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull(AccountRoleLinkRecord::fromLine)
    }

    private fun writeAll(records: List<AccountRoleLinkRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private class CloudConfigStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun all(): List<CloudConfigRecord> = lock.read { readAll().sortedByDescending { it.updatedAt } }

    fun usedSlots(accountId: Int): Int {
        return lock.read { readAll().count { it.ownerAccountId == accountId && !it.disabled } }
    }

    fun create(account: AccountRecord, ownerLicenseKey: String?, name: String, payloadBase64: String, configType: String?, isImagePayload: Boolean, limit: Int, gifLimitBytes: Long?, gifMaxConfigs: Int?): CloudConfigCreateResult {
        return lock.write {
            val records = readAll().toMutableList()
            val used = records.count { it.ownerAccountId == account.id && !it.disabled }
            if (used >= limit) {
                return@write CloudConfigCreateResult.LimitReached(used, limit)
            }

            val isImageConfig = configType == "GIF" || configType == "PNG" || isImagePayload
            if (isImageConfig) {
                if (gifLimitBytes == null || gifMaxConfigs == null) {
                    return@write CloudConfigCreateResult.GifNotAllowed
                }
                val payloadBytes = runCatching { Base64.getDecoder().decode(payloadBase64) }.getOrNull()
                if (payloadBytes != null && payloadBytes.size > gifLimitBytes) {
                    return@write CloudConfigCreateResult.GifTooLarge(gifLimitBytes)
                }
                val imageCount = records.count { it.ownerAccountId == account.id && !it.disabled && (it.configType == "GIF" || it.configType == "PNG" || it.isImagePayload) }
                if (imageCount >= gifMaxConfigs) {
                    return@write CloudConfigCreateResult.GifLimitReached(gifMaxConfigs)
                }
            }

            val now = Instant.now().toString()
            val key = generateCloudConfigKey(records.asSequence().map { it.configKey }.toSet())
            records += CloudConfigRecord(
                configKey = key,
                ownerAccountId = account.id,
                ownerHwidHash = account.hwidHash,
                ownerLicenseKey = ownerLicenseKey,
                name = name,
                createdAt = now,
                updatedAt = now,
                disabled = false,
                payloadBase64 = payloadBase64,
                configType = configType,
                gifApproved = if (isImageConfig) null else true,
                isImagePayload = isImagePayload,
            )
            writeAll(records)
            CloudConfigCreateResult.Created(key, used + 1)
        }
    }

    fun findActive(key: String): CloudConfigRecord? {
        return lock.read { readAll().firstOrNull { it.configKey == key && !it.disabled } }
    }

    fun listOwned(accountId: Int): List<CloudConfigRecord> {
        return lock.read { readAll().filter { it.ownerAccountId == accountId }.sortedByDescending { it.updatedAt } }
    }

    fun deleteOwned(accountId: Int, configKey: String): Boolean {
        return lock.write {
            val records = readAll()
            val owned = records.any { it.configKey == configKey && it.ownerAccountId == accountId }
            if (!owned) return@write false
            writeAll(records.filterNot { it.configKey == configKey })
            true
        }
    }

    fun listGifConfigs(status: String?): List<CloudConfigRecord> {
        return lock.read {
            readAll()
                .filter { it.configType == "GIF" && !it.disabled }
                .filter {
                    when (status) {
                        "pending" -> it.gifApproved == null
                        "approved" -> it.gifApproved == true
                        "denied" -> it.gifApproved == false
                        else -> true
                    }
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    fun approveGifConfig(configKey: String): Boolean {
        return lock.write {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.configKey == configKey && it.configType == "GIF" }
            if (index < 0) return@write false
            records[index] = records[index].copy(gifApproved = true, updatedAt = Instant.now().toString())
            writeAll(records)
            true
        }
    }

    fun denyGifConfig(configKey: String): Boolean {
        return lock.write {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.configKey == configKey && it.configType == "GIF" }
            if (index < 0) return@write false
            records[index] = records[index].copy(gifApproved = false, updatedAt = Instant.now().toString())
            writeAll(records)
            true
        }
    }

    private fun readAll(): List<CloudConfigRecord> {
        return filePath.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull(CloudConfigRecord::fromLine)
    }

    private fun writeAll(records: List<CloudConfigRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private class RoleSettingsStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun all(): List<RoleSettingsRecord> = lock.read { readAll() }

    fun find(role: String): RoleSettingsRecord? {
        return lock.read { readAll().firstOrNull { it.role == role } }
    }

    fun upsert(record: RoleSettingsRecord) {
        lock.write {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.role == record.role }
            if (index >= 0) {
                records[index] = record
            } else {
                records += record
            }
            writeAll(records.sortedBy { it.role })
        }
    }

    private fun readAll(): List<RoleSettingsRecord> {
        return filePath.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull(RoleSettingsRecord::fromLine)
    }

    private fun writeAll(records: List<RoleSettingsRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private class RequestRateLimiter {
    private val lastAllowed = ConcurrentHashMap<String, Instant>()

    fun check(bucket: String, cooldownSeconds: Int): RateLimitResult {
        if (cooldownSeconds <= 0) return RateLimitResult.Allowed
        val now = Instant.now()
        while (true) {
            val previous = lastAllowed[bucket]
            if (previous != null) {
                val nextAllowed = previous.plusSeconds(cooldownSeconds.toLong())
                if (nextAllowed.isAfter(now)) {
                    return RateLimitResult.Limited(nextAllowed.epochSecond - now.epochSecond)
                }
            }
            if (previous == null) {
                if (lastAllowed.putIfAbsent(bucket, now) == null) return RateLimitResult.Allowed
            } else if (lastAllowed.replace(bucket, previous, now)) {
                return RateLimitResult.Allowed
            }
        }
    }
}

private class PresenceStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun markOnline(account: AccountRecord, displayName: String?) {
        lock.write {
            val now = Instant.now().toString()
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.accountId == account.id }
            val updated = PresenceRecord(
                accountId = account.id,
                hwidHash = account.hwidHash,
                displayName = displayName?.take(32)?.takeIf { it.isNotBlank() } ?: account.displayName,
                online = true,
                onlineSince = records.getOrNull(index)?.onlineSince ?: now,
                lastSeenAt = now,
                offlineAt = null,
            )
            if (index >= 0) records[index] = updated else records += updated
            writeAll(records)
        }
    }

    fun touch(account: AccountRecord) {
        lock.write {
            val now = Instant.now().toString()
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.accountId == account.id }
            if (index < 0) {
                records += PresenceRecord(account.id, account.hwidHash, account.displayName, true, now, now, null)
            } else {
                records[index] = records[index].copy(online = true, lastSeenAt = now, offlineAt = null)
            }
            writeAll(records)
        }
    }

    fun markOffline(account: AccountRecord) {
        lock.write {
            val now = Instant.now().toString()
            val records = readAll().map { record ->
                if (record.accountId == account.id) record.copy(online = false, lastSeenAt = now, offlineAt = now) else record
            }
            writeAll(records)
        }
    }

    fun all(): List<PresenceRecord> = lock.read { readAll() }

    fun onlineAccountIds(now: Instant = Instant.now()): Set<Int> {
        return lock.read {
            readAll()
                .asSequence()
                .filter { it.isOnline(now) }
                .map { it.accountId }
                .toSet()
        }
    }

    private fun readAll(): List<PresenceRecord> {
        return filePath.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull(PresenceRecord::fromLine)
    }

    private fun writeAll(records: List<PresenceRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private class NotificationStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun pendingFor(accountId: Int): List<NotificationRecord> {
        return lock.read {
            readAll()
                .filter { it.accountId == accountId && it.deliveredAt == null }
                .sortedBy { it.createdAt }
        }
    }

    fun markDelivered(ids: Set<Long>) {
        if (ids.isEmpty()) return
        lock.write {
            val now = Instant.now().toString()
            val records = readAll().map { record ->
                if (record.id in ids && record.deliveredAt == null) record.copy(deliveredAt = now) else record
            }
            writeAll(records)
        }
    }

    private fun readAll(): List<NotificationRecord> {
        return filePath.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull(NotificationRecord::fromLine)
    }

    private fun writeAll(records: List<NotificationRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private data class LicenseRecord(
    val licenseKey: String,
    val role: String,
    val hwidHash: String?,
    val createdAt: String,
    val boundAt: String?,
    val expiresAt: String?,
    val disabled: Boolean,
) {
    fun isExpired(): Boolean {
        val expires = expiresAt ?: return false
        return runCatching { Instant.parse(expires).isBefore(Instant.now()) }.getOrDefault(false)
    }

    fun invalidFor(hwidHash: String): String? {
        if (disabled) return "LICENSE_DISABLED"
        if (isExpired()) return "LICENSE_EXPIRED"
        val bound = this.hwidHash
        if (bound != null && !bound.equals(hwidHash, ignoreCase = true)) return "HWID_MISMATCH"
        return null
    }

    fun activeFor(hwidHash: String): Boolean {
        return invalidFor(hwidHash) == null && this.hwidHash != null
    }

    fun toLine(): String {
        return listOf(
            licenseKey,
            role,
            hwidHash ?: "",
            createdAt,
            boundAt ?: "",
            expiresAt ?: "",
            disabled.toString(),
        ).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): LicenseRecord? {
            val parts = line.split('\t')
            if (parts.size < 7) return null
            return LicenseRecord(
                licenseKey = parts[0],
                role = parts[1],
                hwidHash = parts[2].ifBlank { null },
                createdAt = parts[3],
                boundAt = parts[4].ifBlank { null },
                expiresAt = parts[5].ifBlank { null },
                disabled = parts[6].toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

private data class AccountRecord(
    val id: Int,
    val accountKey: String,
    val hwidHash: String,
    val displayName: String?,
    val contact: String?,
    val createdAt: String,
    val disabled: Boolean,
    val cloudUploadBanned: Boolean,
    val metadata: String? = null,
) {
    fun toLine(): String {
        return listOf(
            id.toString(),
            accountKey,
            hwidHash,
            displayName ?: "",
            contact ?: "",
            createdAt,
            disabled.toString(),
            cloudUploadBanned.toString(),
            metadata ?: "",
        ).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): AccountRecord? {
            val parts = line.split('\t')
            if (parts.size < 6) return null
            return AccountRecord(
                id = parts[0].toIntOrNull() ?: return null,
                accountKey = parts[1],
                hwidHash = parts[2],
                displayName = parts[3].ifBlank { null },
                contact = if (parts.size >= 7) parts[4].ifBlank { null } else null,
                createdAt = if (parts.size >= 7) parts[5] else parts[4],
                disabled = (if (parts.size >= 7) parts[6] else parts[5]).toBooleanStrictOrNull() ?: false,
                cloudUploadBanned = if (parts.size >= 8) parts[7].toBooleanStrictOrNull() ?: false else false,
                metadata = if (parts.size >= 9) parts[8].ifBlank { null } else null,
            )
        }
    }
}

private data class AccountRoleLinkRecord(
    val accountId: Int,
    val licenseKey: String,
    val grantedAt: String,
    val disabled: Boolean,
) {
    fun toLine(): String {
        return listOf(accountId.toString(), licenseKey, grantedAt, disabled.toString()).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): AccountRoleLinkRecord? {
            val parts = line.split('\t')
            if (parts.size < 4) return null
            return AccountRoleLinkRecord(
                accountId = parts[0].toIntOrNull() ?: return null,
                licenseKey = parts[1],
                grantedAt = parts[2],
                disabled = parts[3].toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

private data class CloudConfigRecord(
    val configKey: String,
    val ownerAccountId: Int?,
    val ownerHwidHash: String,
    val ownerLicenseKey: String?,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val disabled: Boolean,
    val payloadBase64: String,
    val configType: String? = null,
    val gifApproved: Boolean? = null,
    val isImagePayload: Boolean = false,
) {
    fun toLine(): String {
        return listOf(
            configKey,
            ownerAccountId?.toString() ?: "",
            ownerHwidHash,
            ownerLicenseKey ?: "",
            name,
            createdAt,
            updatedAt,
            disabled.toString(),
            payloadBase64,
            configType ?: "",
            gifApproved?.toString() ?: "",
            isImagePayload.toString(),
        ).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): CloudConfigRecord? {
            val parts = line.split('\t')
            if (parts.size >= 12) {
                return CloudConfigRecord(
                    configKey = parts[0],
                    ownerAccountId = parts[1].toIntOrNull(),
                    ownerHwidHash = parts[2],
                    ownerLicenseKey = parts[3].ifBlank { null },
                    name = parts[4],
                    createdAt = parts[5],
                    updatedAt = parts[6],
                    disabled = parts[7].toBooleanStrictOrNull() ?: false,
                    payloadBase64 = parts[8],
                    configType = parts.getOrNull(9)?.ifBlank { null },
                    gifApproved = parts.getOrNull(10)?.ifBlank { null }?.toBooleanStrictOrNull(),
                    isImagePayload = parts.getOrNull(11)?.toBooleanStrictOrNull() ?: false,
                )
            }
            if (parts.size >= 10) {
                return CloudConfigRecord(
                    configKey = parts[0],
                    ownerAccountId = parts[1].toIntOrNull(),
                    ownerHwidHash = parts[2],
                    ownerLicenseKey = parts[3].ifBlank { null },
                    name = parts[4],
                    createdAt = parts[5],
                    updatedAt = parts[6],
                    disabled = parts[7].toBooleanStrictOrNull() ?: false,
                    payloadBase64 = parts[8],
                    configType = parts.getOrNull(9)?.ifBlank { null },
                    gifApproved = parts.getOrNull(10)?.ifBlank { null }?.toBooleanStrictOrNull(),
                )
            }
            if (parts.size >= 9) {
                return CloudConfigRecord(
                    configKey = parts[0],
                    ownerAccountId = parts[1].toIntOrNull(),
                    ownerHwidHash = parts[2],
                    ownerLicenseKey = parts[3].ifBlank { null },
                    name = parts[4],
                    createdAt = parts[5],
                    updatedAt = parts[6],
                    disabled = parts[7].toBooleanStrictOrNull() ?: false,
                    payloadBase64 = parts[8],
                    configType = parts.getOrNull(9)?.ifBlank { null },
                )
            }
            if (parts.size >= 8) {
                return CloudConfigRecord(
                    configKey = parts[0],
                    ownerAccountId = null,
                    ownerHwidHash = parts[1],
                    ownerLicenseKey = parts[2].ifBlank { null },
                    name = parts[3],
                    createdAt = parts[4],
                    updatedAt = parts[5],
                    disabled = parts[6].toBooleanStrictOrNull() ?: false,
                    payloadBase64 = parts[7],
                    configType = parts.getOrNull(8)?.ifBlank { null },
                )
            }
            return null
        }
    }
}

private data class RoleSettingsRecord(
    val role: String,
    val cloudLimit: Int,
    val saveCooldownSeconds: Int,
    val loadCooldownSeconds: Int,
    val textGradient: String? = null,
    val nickGradient: String? = null,
    val iconFile: String? = null,
    val gifLimitBytes: Long? = null,
    val gifMaxConfigs: Int? = null,
    val canChangeGradient: Boolean = false,
    val canResetHwid: Boolean = false,
    val hwidResetCount: Int = 0,
    val displayName: String? = null,
) {
    fun toLine(): String {
        return listOf(
            role,
            cloudLimit.toString(),
            saveCooldownSeconds.toString(),
            loadCooldownSeconds.toString(),
            textGradient ?: "",
            nickGradient ?: "",
            iconFile ?: "",
            gifLimitBytes?.toString() ?: "",
            gifMaxConfigs?.toString() ?: "",
            canChangeGradient.toString(),
            canResetHwid.toString(),
            hwidResetCount.toString(),
            displayName ?: "",
        ).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): RoleSettingsRecord? {
            val parts = line.split('\t')
            if (parts.size < 4) return null
            val role = parts[0].uppercaseRole() ?: return null
            return RoleSettingsRecord(
                role = role,
                cloudLimit = parts[1].toIntOrNull()?.coerceIn(0, 1000) ?: return null,
                saveCooldownSeconds = parts[2].toIntOrNull()?.coerceIn(0, 3600) ?: return null,
                loadCooldownSeconds = parts[3].toIntOrNull()?.coerceIn(0, 3600) ?: return null,
                textGradient = parts.getOrNull(4)?.ifBlank { null },
                nickGradient = parts.getOrNull(5)?.ifBlank { null },
                iconFile = parts.getOrNull(6)?.ifBlank { null },
                gifLimitBytes = parts.getOrNull(7)?.ifBlank { null }?.toLongOrNull(),
                gifMaxConfigs = parts.getOrNull(8)?.ifBlank { null }?.toIntOrNull(),
                canChangeGradient = parts.getOrNull(9)?.toBooleanStrictOrNull() ?: false,
                canResetHwid = parts.getOrNull(10)?.toBooleanStrictOrNull() ?: false,
                hwidResetCount = parts.getOrNull(11)?.toIntOrNull() ?: 0,
                displayName = parts.getOrNull(12)?.ifBlank { null },
            )
        }
    }
}

private data class RoleRuntimeSettings(
    val cloudLimit: Int,
    val saveCooldownSeconds: Int,
    val loadCooldownSeconds: Int,
    val gifLimitBytes: Long?,
    val gifMaxConfigs: Int?,
    val canChangeGradient: Boolean,
    val canResetHwid: Boolean,
    val hwidResetCount: Int,
)

private sealed interface RateLimitResult {
    data object Allowed : RateLimitResult
    data class Limited(val retryAfterSeconds: Long) : RateLimitResult
}

private data class PresenceRecord(
    val accountId: Int,
    val hwidHash: String,
    val displayName: String?,
    val online: Boolean,
    val onlineSince: String,
    val lastSeenAt: String,
    val offlineAt: String?,
) {
    fun isOnline(now: Instant): Boolean {
        if (!online) return false
        val lastSeen = runCatching { Instant.parse(lastSeenAt) }.getOrNull() ?: return false
        return !lastSeen.plusSeconds(ONLINE_TTL_SECONDS).isBefore(now)
    }

    fun toLine(): String {
        return listOf(
            accountId.toString(),
            hwidHash,
            displayName ?: "",
            online.toString(),
            onlineSince,
            lastSeenAt,
            offlineAt ?: "",
        ).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): PresenceRecord? {
            val parts = line.split('\t')
            if (parts.size < 7) return null
            return PresenceRecord(
                accountId = parts[0].toIntOrNull() ?: return null,
                hwidHash = parts[1],
                displayName = parts[2].ifBlank { null },
                online = parts[3].toBooleanStrictOrNull() ?: false,
                onlineSince = parts[4],
                lastSeenAt = parts[5],
                offlineAt = parts[6].ifBlank { null },
            )
        }
    }
}

private data class NotificationRecord(
    val id: Long,
    val accountId: Int,
    val message: String,
    val createdAt: String,
    val deliveredAt: String?,
) {
    fun toLine(): String {
        return listOf(
            id.toString(),
            accountId.toString(),
            message,
            createdAt,
            deliveredAt ?: "",
        ).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): NotificationRecord? {
            val parts = line.split('\t')
            if (parts.size < 5) return null
            return NotificationRecord(
                id = parts[0].toLongOrNull() ?: return null,
                accountId = parts[1].toIntOrNull() ?: return null,
                message = parts[2],
                createdAt = parts[3],
                deliveredAt = parts[4].ifBlank { null },
            )
        }
    }
}

private sealed class LicenseCheckResult {
    data class Valid(val role: String, val status: String, val expiresAt: String?) : LicenseCheckResult()
    data class Invalid(val reason: String) : LicenseCheckResult()
}

private sealed class CloudConfigCreateResult {
    data class Created(val configKey: String, val used: Int) : CloudConfigCreateResult()
    data class LimitReached(val used: Int, val limit: Int) : CloudConfigCreateResult()
    data object GifNotAllowed : CloudConfigCreateResult()
    data class GifTooLarge(val limitBytes: Long) : CloudConfigCreateResult()
    data class GifLimitReached(val maxConfigs: Int) : CloudConfigCreateResult()
}

private fun checkLicense(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val license = jsonString(body, "license")?.trim()?.uppercase(Locale.ROOT)
    val hwidHash = jsonString(body, "hwidHash")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "license-check", license ?: hwidHash, 30)) return

    if (license == null || !licenseRegex.matches(license)) return json(exchange, 200, invalidJson("LICENSE_FORMAT"))
    if (hwidHash == null || !hwidHashRegex.matches(hwidHash)) return json(exchange, 200, invalidJson("HWID_FORMAT"))

    json(exchange, 200, state.licenses.checkAndBind(license, hwidHash).toJson())
}

private fun createAccount(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val hwidHash = jsonString(body, "hwidHash")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "account-create", hwidHash, 10)) return
    val displayName = jsonString(body, "displayName")?.trim()?.take(32)?.takeIf { it.isNotBlank() }
    if (hwidHash == null || !hwidHashRegex.matches(hwidHash)) return json(exchange, 200, apiError("HWID_FORMAT"))

    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    val account = if (!accountKey.isNullOrBlank()) {
        if (!accountKeyRegex.matches(accountKey)) return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
        val existing = state.accounts.findByKey(accountKey) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
        if (existing.disabled) return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
        if (existing.hwidHash.isBlank()) {
            state.accounts.bindHwidIfEmpty(existing.id, hwidHash)
        } else if (!existing.hwidHash.equals(hwidHash, ignoreCase = true)) {
            return json(exchange, 200, apiError("HWID_ALREADY_BOUND"))
        }
        state.accounts.findById(existing.id) ?: existing
    } else {
        state.accounts.createOrGetByHwid(hwidHash, displayName)
    }

    json(exchange, 200, accountPayload(account, state, "OK"))
}

private fun accountInfo(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "account-info", accountKey, 60)) return
    val account = requireAccount(body, state)
        ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))

    json(exchange, 200, accountPayload(account, state, "OK"))
}

private fun setAccountName(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "account-set-name", accountKey, 30)) return
    val account = requireAccount(body, state) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    val displayName = jsonString(body, "displayName")?.trim()?.take(32)?.takeIf { it.isNotBlank() }
    state.accounts.updateExisting(account.id) { copy(displayName = displayName) }
    val updated = state.accounts.findById(account.id) ?: account.copy(displayName = displayName)
    json(exchange, 200, accountPayload(updated, state, "NAME_UPDATED"))
}

private fun setAccountContact(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "account-set-contact", accountKey, 30)) return
    val account = requireAccount(body, state) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    val contact = jsonString(body, "contact")?.trim()?.take(96)?.takeIf { it.isNotBlank() }
    state.accounts.updateExisting(account.id) { copy(contact = contact) }
    val updated = state.accounts.findById(account.id) ?: account.copy(contact = contact)
    json(exchange, 200, accountPayload(updated, state, "CONTACT_UPDATED"))
}

private fun applyAccountKey(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "account-apply-key", accountKey, 30)) return
    val account = requireAccount(body, state) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    val licenseKey = jsonString(body, "licenseKey")?.trim()?.uppercase(Locale.ROOT)
    if (licenseKey == null || !licenseRegex.matches(licenseKey)) return json(exchange, 200, apiError("LICENSE_FORMAT"))

    val boundAccountId = state.roleLinks.activeAccountIdForLicense(licenseKey)
    if (boundAccountId != null && boundAccountId != account.id) {
        return json(exchange, 200, apiError("LICENSE_ACCOUNT_BOUND"))
    }

    val result = state.licenses.checkAndBind(licenseKey, account.hwidHash)
    when (result) {
        is LicenseCheckResult.Valid -> {
            state.roleLinks.linkExclusive(account.id, licenseKey)
            json(exchange, 200, accountPayload(account, state, result.status))
        }
        is LicenseCheckResult.Invalid -> json(exchange, 200, apiError(result.reason))
    }
}

private fun resetAccountHwid(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (accountKey == null || !accountKeyRegex.matches(accountKey)) return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    if (!checkEndpointRateLimit(exchange, state, "account-reset-hwid", accountKey, 5)) return
    val account = state.accounts.findByKey(accountKey) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))

    val resetSecret = env("HYPNOSIA_RESET_SECRET")
    if (resetSecret.isNullOrBlank()) {
        return json(exchange, 200, apiError("RESET_NOT_CONFIGURED"))
    }
    val resetToken = jsonString(body, "resetToken")?.trim()
    val timestamp = jsonLong(body, "resetTimestamp")
    if (resetToken == null || timestamp == null || !verifyResetToken(resetSecret, account.accountKey, timestamp, resetToken)) {
        return json(exchange, 200, apiError("RESET_TOKEN_INVALID"))
    }

    val metadata = parseAccountMetadata(account.metadata)
    if (metadata.hwidResetCount >= 5) {
        return json(exchange, 200, apiError("HWID_RESET_LIMIT_REACHED"))
    }
    val updatedMetadata = metadata.copy(hwidResetCount = metadata.hwidResetCount + 1, lastHwidResetAt = Instant.now().toString())

    // Reset HWID on all linked licenses
    val links = state.roleLinks.linksForAccount(account.id)
    for (link in links) {
        state.licenses.updateExisting(link.licenseKey) { copy(hwidHash = null, boundAt = null) }
        state.roleLinks.unlinkLicense(link.licenseKey)
    }

    // Generate new account key
    val usedKeys = state.accounts.all().map { it.accountKey }.toSet()
    val newAccountKey = generateUniqueKey(32, usedKeys)
    state.accounts.updateExisting(account.id) { copy(accountKey = newAccountKey, hwidHash = "", metadata = updatedMetadata.toJson()) }

    val updated = state.accounts.findById(account.id) ?: account
    json(exchange, 200, """{"ok":true,"status":"HWID_RESET","newAccountKey":"${newAccountKey}","accountId":${updated.id}}""")
}

private fun saveCloudConfig(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "cloud-save", accountKey, 30)) return
    if (body.length > MAX_CLOUD_IMAGE_REQUEST_CHARS) return json(exchange, 200, apiError("REQUEST_TOO_LARGE"))
    val account = accountFromBodyOrCreate(body, state) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    if (account.cloudUploadBanned) return json(exchange, 200, apiError("CLOUD_UPLOAD_BANNED"))
    val ownerLicenseKey = jsonString(body, "license")?.trim()?.uppercase(Locale.ROOT)?.takeIf { licenseRegex.matches(it) }
    val name = normalizeCloudConfigName(jsonString(body, "name") ?: "Shared config")
        ?: return json(exchange, 200, apiError("CONFIG_NAME_FORMAT"))
    val payloadBase64 = jsonString(body, "payloadBase64")?.trim()
    if (payloadBase64 == null) return json(exchange, 200, apiError("PAYLOAD_MISSING"))

    val payloadBytes = runCatching { Base64.getDecoder().decode(payloadBase64) }.getOrNull()
    if (payloadBytes == null || payloadBytes.isEmpty()) return json(exchange, 200, apiError("PAYLOAD_FORMAT"))

    val roles = accountRoles(account, state)
    val roleSettings = roleSettingsForRoles(roles, state)
    val hasImageRights = roleSettings.gifLimitBytes != null && roleSettings.gifMaxConfigs != null

    val text = runCatching { String(payloadBytes, StandardCharsets.UTF_8) }.getOrNull()
    val hasImages = text?.contains("\"image.data.") == true || text?.contains("\"image.data\":") == true
    val maxRequestChars = if (hasImages && hasImageRights) MAX_CLOUD_IMAGE_REQUEST_CHARS else MAX_CLOUD_CONFIG_REQUEST_CHARS
    val maxPayloadBytes = if (hasImages && hasImageRights) MAX_CLOUD_IMAGE_PAYLOAD_BYTES else MAX_CLOUD_CONFIG_PAYLOAD_BYTES
    if (body.length > maxRequestChars) return json(exchange, 200, apiError("REQUEST_TOO_LARGE"))
    if (payloadBytes.size > maxPayloadBytes) return json(exchange, 200, apiError("PAYLOAD_TOO_LARGE"))
    if (hasImages && !hasImageRights) return json(exchange, 200, apiError("GIF_NOT_ALLOWED"))

    val configType = jsonString(body, "configType")?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    val safePayloadBase64 = if (configType == "GIF" || configType == "PNG" || hasImages) {
        payloadBase64
    } else {
        val safePayloadBytes = canonicalizeCloudConfigPayload(payloadBytes)
            ?: return json(exchange, 200, apiError("PAYLOAD_SCHEMA_INVALID"))
        Base64.getEncoder().encodeToString(safePayloadBytes)
    }

    when (val limit = state.rateLimiter.check("cloud-save:${account.id}", roleSettings.saveCooldownSeconds)) {
        is RateLimitResult.Limited -> return json(exchange, 200, rateLimitJson(limit.retryAfterSeconds))
        RateLimitResult.Allowed -> Unit
    }
    when (val result = state.cloudConfigs.create(account, ownerLicenseKey, name, safePayloadBase64, configType, hasImages, roleSettings.cloudLimit, roleSettings.gifLimitBytes, roleSettings.gifMaxConfigs)) {
        is CloudConfigCreateResult.Created -> {
            json(
                exchange,
                200,
                """{"ok":true,"status":"CREATED","accountId":${account.id},"accountKey":"${account.accountKey}","configKey":"${result.configKey}","used":${result.used},"limit":${roleSettings.cloudLimit}}""",
            )
        }
        is CloudConfigCreateResult.LimitReached -> {
            json(exchange, 200, """{"ok":false,"status":"LIMIT_REACHED","used":${result.used},"limit":${result.limit}}""")
        }
        CloudConfigCreateResult.GifNotAllowed -> {
            json(exchange, 200, """{"ok":false,"status":"GIF_NOT_ALLOWED"}""")
        }
        is CloudConfigCreateResult.GifTooLarge -> {
            json(exchange, 200, """{"ok":false,"status":"GIF_TOO_LARGE","limitBytes":${result.limitBytes}}""")
        }
        is CloudConfigCreateResult.GifLimitReached -> {
            json(exchange, 200, """{"ok":false,"status":"GIF_LIMIT_REACHED","maxConfigs":${result.maxConfigs}}""")
        }
    }
}

private fun loadCloudConfig(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "cloud-load", accountKey, 30)) return
    val key = jsonString(body, "configKey")?.trim()?.uppercase(Locale.ROOT)
    if (key == null || !cloudConfigKeyRegex.matches(key)) return json(exchange, 200, apiError("CONFIG_KEY_FORMAT"))

    val account = accountFromBodyIfPresent(body, state)
    if (account?.cloudUploadBanned == true) return json(exchange, 200, apiError("CLOUD_UPLOAD_BANNED"))
    val roles = account?.let { accountRoles(it, state) } ?: listOf("USER")
    val roleSettings = roleSettingsForRoles(roles, state)
    val bucket = account?.let { "cloud-load:${it.id}" }
        ?: "cloud-load:ip:${exchange.remoteAddress.address.hostAddress}"
    when (val limit = state.rateLimiter.check(bucket, roleSettings.loadCooldownSeconds)) {
        is RateLimitResult.Limited -> return json(exchange, 200, rateLimitJson(limit.retryAfterSeconds))
        RateLimitResult.Allowed -> Unit
    }

    val record = state.cloudConfigs.findActive(key) ?: run {
        val failIp = exchange.remoteAddress.address.hostAddress
        val failBucket = "cloud-load-fail:$failIp"
        return when (val limit = state.rateLimiter.check(failBucket, 5)) {
            is RateLimitResult.Limited -> json(exchange, 200, rateLimitJson(limit.retryAfterSeconds))
            RateLimitResult.Allowed -> json(exchange, 200, apiError("CONFIG_NOT_FOUND"))
        }
    }
    val typeField = record.configType?.let { ""","configType":"$it""" } ?: ""
    val gifField = if (record.configType == "GIF") ""","gifApproved":${record.gifApproved}""" else ""
    json(
        exchange,
        200,
        """{"ok":true,"configKey":"${record.configKey}","name":"${jsonEscape(record.name)}","payloadBase64":"${jsonEscape(record.payloadBase64)}","updatedAt":"${record.updatedAt}"$typeField$gifField}""",
    )
}

private fun deleteCloudConfig(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "cloud-delete", accountKey, 30)) return
    val account = requireAccount(body, state) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    if (account.cloudUploadBanned) return json(exchange, 200, apiError("CLOUD_UPLOAD_BANNED"))
    val key = jsonString(body, "configKey")?.trim()?.uppercase(Locale.ROOT)
    if (key == null || !cloudConfigKeyRegex.matches(key)) return json(exchange, 200, apiError("CONFIG_KEY_FORMAT"))

    if (!state.cloudConfigs.deleteOwned(account.id, key)) return json(exchange, 200, apiError("CONFIG_NOT_FOUND"))
    json(exchange, 200, """{"ok":true,"status":"DELETED"}""")
}

private fun listCloudConfigs(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "cloud-list", accountKey, 30)) return
    val account = requireAccount(body, state)
        ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    if (account.cloudUploadBanned) return json(exchange, 200, apiError("CLOUD_UPLOAD_BANNED"))
    val configs = state.cloudConfigs.listOwned(account.id)
    val used = configs.count { !it.disabled }
    val roleSettings = roleSettingsForRoles(accountRoles(account, state), state)
    val items = configs.joinToString(",") { record ->
        val typeField = record.configType?.let { """"configType":"$it"""" } ?: ""
        val gifField = if (record.configType == "GIF") """"gifApproved":${record.gifApproved}""" else ""
        val base = """{"configKey":"${record.configKey}","name":"${jsonEscape(record.name)}","disabled":${record.disabled},"updatedAt":"${record.updatedAt}"""
        val fields = listOfNotNull(typeField.takeIf { it.isNotEmpty() }, gifField.takeIf { it.isNotEmpty() }).joinToString(",")
        if (fields.isNotEmpty()) "$base,$fields}" else "$base}"
    }
    json(exchange, 200, """{"ok":true,"used":$used,"limit":${roleSettings.cloudLimit},"configs":[$items]}""")
}

private fun sessionOnline(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "session-online", accountKey, 60)) return
    val account = requireAccount(body, state) ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    val displayName = jsonString(body, "displayName")?.trim()?.take(32)?.takeIf { it.isNotBlank() }
    state.presence.markOnline(account, displayName)
    json(exchange, 200, """{"ok":true,"status":"ONLINE"}""")
}

private fun sessionOffline(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "session-offline", accountKey, 60)) return
    val account = requireAccount(body, state)
        ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    state.presence.markOffline(account)
    json(exchange, 200, """{"ok":true,"status":"OFFLINE"}""")
}

private fun pollNotifications(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val body = exchange.bodyString()
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!checkEndpointRateLimit(exchange, state, "notifications-poll", accountKey, 60)) return
    val account = requireAccount(body, state)
        ?: return json(exchange, 200, apiError("ACCOUNT_NOT_FOUND"))
    state.presence.touch(account)
    val pending = state.notifications.pendingFor(account.id)
    state.notifications.markDelivered(pending.map { it.id }.toSet())
    val items = pending.joinToString(",") { record ->
        """{"id":${record.id},"message":"${jsonEscape(record.message)}","createdAt":"${record.createdAt}"}"""
    }
    json(exchange, 200, """{"ok":true,"notifications":[$items]}""")
}

private fun adminCreateOrUpdateLicense(exchange: HttpExchange, state: ServerState, adminApiKey: String?) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")

    val providedKey = exchange.requestHeaders.getFirst("X-Admin-Key")
    if (adminApiKey.isNullOrBlank() || providedKey != adminApiKey) {
        return json(exchange, 200, """{"success":false,"error":"UNAUTHORIZED"}""")
    }

    val body = exchange.bodyString()

    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    val roleInput = jsonString(body, "role")?.trim()?.uppercase(Locale.ROOT)
    val durationDays = run {
        val str = jsonString(body, "durationDays")
        if (str != null) return@run str.toIntOrNull()
        val regex = Regex("\"durationDays\"\\s*:\\s*(-?\\d+)")
        regex.find(body)?.groupValues?.get(1)?.toIntOrNull()
    }

    if (accountKey == null || !accountKeyRegex.matches(accountKey)) {
        return json(exchange, 200, """{"success":false,"error":"INVALID_ACCOUNT_KEY"}""")
    }
    if (roleInput == null) {
        return json(exchange, 200, """{"success":false,"error":"INVALID_ROLE"}""")
    }
    if (durationDays == null || durationDays <= 0) {
        return json(exchange, 200, """{"success":false,"error":"INVALID_DURATION"}""")
    }

    val normalizedRole = when (roleInput) {
        "SPONSOR" -> "SPONSOR"
        "SPONSOR+" -> "SPONSOR_PLUS"
        "SPONSOR++" -> "SPONSOR_PLUS_PLUS"
        else -> roleInput.takeIf { roleRegex.matches(it) }
    }
    if (normalizedRole == null) {
        return json(exchange, 200, """{"success":false,"error":"INVALID_ROLE"}""")
    }

    val account = state.accounts.findByKey(accountKey)
        ?: return json(exchange, 200, """{"success":false,"error":"ACCOUNT_NOT_FOUND"}""")

    // Determine all required roles based on purchased tier
    val requiredRoles = when (normalizedRole) {
        "SPONSOR" -> listOf("SPONSOR")
        "SPONSOR_PLUS" -> listOf("SPONSOR", "SPONSOR_PLUS")
        "SPONSOR_PLUS_PLUS" -> listOf("SPONSOR", "SPONSOR_PLUS", "SPONSOR_PLUS_PLUS")
        else -> listOf(normalizedRole)
    }

    val now = Instant.now()
    val durationSeconds = durationDays.toLong() * 24 * 60 * 60

    val links = state.roleLinks.linksForAccount(account.id)

    // Resolve the existing license (if any) for each role we are about to apply.
    val existingByRole = HashMap<String, LicenseRecord?>()
    for (role in requiredRoles) {
        var existingLicense: LicenseRecord? = null
        for (link in links) {
            val license = state.licenses.find(link.licenseKey)
            if (license != null && license.role == role) {
                existingLicense = license
                break
            }
        }
        existingByRole[role] = existingLicense
    }

    // Single unified expiry date for ALL roles of this purchase, so a tier and
    // its sub-tiers (e.g. SPONSOR / SPONSOR_PLUS / SPONSOR_PLUS_PLUS) always end
    // on the same day. The site may dictate the absolute target date directly;
    // otherwise we fall back to (latest future expiry among affected roles) + duration.
    val targetExpiresInput = jsonString(body, "targetExpiresAt")
        ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }

    val targetExpires: Instant = if (targetExpiresInput != null) {
        targetExpiresInput
    } else {
        val latestExisting = requiredRoles
            .mapNotNull { role -> existingByRole[role]?.expiresAt }
            .mapNotNull { runCatching { Instant.parse(it) }.getOrNull() }
            .filter { it.isAfter(now) }
            .maxOrNull()
        val base = latestExisting ?: now
        base.plusSeconds(durationSeconds)
    }
    val expiresAt = targetExpires.toString()
    val baseExpires = expiresAt

    val results = mutableListOf<String>()

    for (role in requiredRoles) {
        val existingLicense = existingByRole[role]
        val isNewKey: Boolean
        val keyString: String

        if (existingLicense != null) {
            isNewKey = false
            keyString = existingLicense.licenseKey
            state.licenses.updateExisting(keyString) { copy(expiresAt = expiresAt) }
        } else {
            isNewKey = true
            val usedKeys = state.licenses.all().map { it.licenseKey }.toSet()
            keyString = generateUniqueKey(32, usedKeys)
            state.licenses.create(
                LicenseRecord(
                    licenseKey = keyString,
                    role = role,
                    hwidHash = null,
                    createdAt = now.toString(),
                    boundAt = null,
                    expiresAt = expiresAt,
                    disabled = false,
                ),
            )
            state.roleLinks.linkExclusive(account.id, keyString)
        }

        results += """{"role":"$role","key":"$keyString","expiresAt":"$expiresAt","isNewKey":$isNewKey}"""
    }

    // Apply limits to role settings for the highest tier
    val limitsObj = jsonObjectString(body, "limits")
    if (limitsObj != null) {
        state.accounts.updateExisting(account.id) { copy(metadata = limitsObj) }
    }

    json(
        exchange,
        200,
        """{"success":true,"keys":[${results.joinToString(",")}],"expiresAt":"$baseExpires"}""",
    )
}



private fun adminAccounts(exchange: HttpExchange, state: ServerState, adminApiKey: String?) {
    if (exchange.requestMethod != "GET") return text(exchange, 405, "Method not allowed")
    val providedKey = exchange.requestHeaders.getFirst("X-Admin-Key")
    if (adminApiKey.isNullOrBlank() || providedKey != adminApiKey) {
        return json(exchange, 200, """{"ok":false,"error":"UNAUTHORIZED"}""")
    }

    val accounts = state.accounts.all().sortedBy { it.id }
    val allLinks = state.roleLinks.all()
    val allPresence = state.presence.all()
    val now = Instant.now()
    val body = buildString {
        append("[")
        accounts.forEachIndexed { i, a ->
            if (i > 0) append(",")
            val roles = accountRoles(a, state)
            val presence = allPresence.firstOrNull { it.accountId == a.id }
            val isOnline = presence?.isOnline(now) == true
            val roleGradientsJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleGradient(it, state)) + "\"" }
            val nickGradientsJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleNickGradient(it, state)) + "\"" }
            val roleIconsJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleIcon(it, state)) + "\"" }
            val roleDisplayNamesJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleDisplayName(it, state)) + "\"" }
            append("{")
            append("\"id\":${a.id},")
            append("\"accountKey\":\"${a.accountKey}\",")
            append("\"hwidHash\":\"${a.hwidHash}\",")
            append("\"displayName\":${jsonNullable(a.displayName)},")
            append("\"contact\":${jsonNullable(a.contact)},")
            append("\"createdAt\":\"${a.createdAt}\",")
            append("\"disabled\":${a.disabled},")
            append("\"isOnline\":$isOnline,")
            append("\"lastSeenAt\":\"${presence?.lastSeenAt ?: a.createdAt}\",")
            append("\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],")
            append("\"roleGradients\":{$roleGradientsJson},")
            append("\"nickGradients\":{$nickGradientsJson},")
            append("\"roleIcons\":{$roleIconsJson},")
            append("\"roleDisplayNames\":{$roleDisplayNamesJson}")
            append("}")
        }
        append("]")
    }
    json(exchange, 200, body)
}

private fun adminGifConfigs(exchange: HttpExchange, state: ServerState, adminApiKey: String?) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")
    val providedKey = exchange.requestHeaders.getFirst("X-Admin-Key")
    if (adminApiKey.isNullOrBlank() || providedKey != adminApiKey) {
        return json(exchange, 200, """{"ok":false,"error":"UNAUTHORIZED"}""")
    }

    val body = exchange.bodyString()
    val status = jsonString(body, "status")?.trim()?.lowercase(Locale.ROOT)
    val configs = state.cloudConfigs.listGifConfigs(status)
    val items = configs.joinToString(",") { record ->
        val account = record.ownerAccountId?.let { state.accounts.findById(it) }
        """{"configKey":"${record.configKey}","name":"${jsonEscape(record.name)}","accountId":${record.ownerAccountId ?: 0},"accountName":"${jsonEscape(account?.displayName ?: "Unknown")}","gifFileName":"${jsonEscape(record.name)}","gifFileSize":${runCatching { Base64.getDecoder().decode(record.payloadBase64).size }.getOrDefault(0)},"gifApproved":${record.gifApproved},"updatedAt":"${record.updatedAt}"}"""
    }
    json(exchange, 200, """{"ok":true,"configs":[$items]}""")
}

private fun adminApproveGifConfig(exchange: HttpExchange, state: ServerState, adminApiKey: String?) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")
    val providedKey = exchange.requestHeaders.getFirst("X-Admin-Key")
    if (adminApiKey.isNullOrBlank() || providedKey != adminApiKey) {
        return json(exchange, 200, """{"ok":false,"error":"UNAUTHORIZED"}""")
    }

    val body = exchange.bodyString()
    val configKey = jsonString(body, "configKey")?.trim()?.uppercase(Locale.ROOT)
    if (configKey == null || !cloudConfigKeyRegex.matches(configKey)) {
        return json(exchange, 200, """{"ok":false,"error":"INVALID_CONFIG_KEY"}""")
    }
    val record = state.cloudConfigs.findActive(configKey)
    if (record == null) {
        return json(exchange, 200, """{"ok":false,"error":"CONFIG_NOT_FOUND"}""")
    }
    val success = state.cloudConfigs.approveGifConfig(configKey)
    if (!success) {
        return json(exchange, 200, """{"ok":false,"error":"CONFIG_NOT_FOUND"}""")
    }
    if (record.ownerAccountId != null) {
        state.accounts.updateExisting(record.ownerAccountId) { copy(cloudUploadBanned = false) }
    }
    json(exchange, 200, """{"ok":true,"status":"APPROVED"}""")
}

private fun adminDenyGifConfig(exchange: HttpExchange, state: ServerState, adminApiKey: String?) {
    if (exchange.requestMethod != "POST") return text(exchange, 405, "Method not allowed")
    val providedKey = exchange.requestHeaders.getFirst("X-Admin-Key")
    if (adminApiKey.isNullOrBlank() || providedKey != adminApiKey) {
        return json(exchange, 200, """{"ok":false,"error":"UNAUTHORIZED"}""")
    }

    val body = exchange.bodyString()
    val configKey = jsonString(body, "configKey")?.trim()?.uppercase(Locale.ROOT)
    if (configKey == null || !cloudConfigKeyRegex.matches(configKey)) {
        return json(exchange, 200, """{"ok":false,"error":"INVALID_CONFIG_KEY"}""")
    }
    val record = state.cloudConfigs.findActive(configKey)
    if (record == null) {
        return json(exchange, 200, """{"ok":false,"error":"CONFIG_NOT_FOUND"}""")
    }
    val success = state.cloudConfigs.denyGifConfig(configKey)
    if (!success) {
        return json(exchange, 200, """{"ok":false,"error":"CONFIG_NOT_FOUND"}""")
    }
    if (record.ownerAccountId != null) {
        state.accounts.updateExisting(record.ownerAccountId) { copy(cloudUploadBanned = true) }
        val account = state.accounts.findById(record.ownerAccountId)
        // Discord notification is sent by the site (127.0.0.1:3000) after successful deny
    }
    json(exchange, 200, """{"ok":true,"status":"DENIED"}""")
}

private fun normalizeCloudConfigName(value: String): String? {
    val raw = value.trim()
    val withoutExtension = if (raw.endsWith(".json", ignoreCase = true)) raw.dropLast(5) else raw
    val normalized = withoutExtension.trim(' ', '.')
    if (normalized.isBlank() || normalized == "." || normalized == "..") return null
    if (normalized.any { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) return null
    return normalized.takeIf { cloudConfigNameRegex.matches(it) }
}

private fun validCloudConfigPayload(bytes: ByteArray): Boolean {
    if (bytes.isEmpty() || bytes.size > MAX_CLOUD_CONFIG_PAYLOAD_BYTES) return false
    val text = decodeUtf8Strict(bytes) ?: return false
    return looksLikeJsonObject(text) &&
        text.contains("\"format\"") &&
        text.contains("\"hypnosia-config\"")
}

private fun canonicalizeCloudConfigPayload(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty() || bytes.size > MAX_CLOUD_CONFIG_PAYLOAD_BYTES) return null
    val text = decodeUtf8Strict(bytes) ?: return null
    if (!looksLikeJsonObject(text)) return null
    if (!text.contains("\"format\"") || !text.contains("\"$CLOUD_CONFIG_FORMAT\"")) return null
    val settingsBody = cloudConfigSettingsObjectBody(text) ?: return null
    val settings = sanitizeCloudConfigSettings(decodeCloudConfigStringMap(settingsBody))
    return encodeCloudConfig(settings).toByteArray(StandardCharsets.UTF_8)
}

private fun sanitizeCloudConfigSettings(settings: Map<String, String>): Map<String, String> {
    val sanitized = linkedMapOf<String, String>()
    settings.entries
        .asSequence()
        .filter { (key, value) -> validCloudConfigSettingKey(key) && validCloudConfigSettingValue(value) }
        .sortedBy { it.key }
        .take(MAX_CLOUD_CONFIG_SETTINGS)
        .forEach { (key, value) -> sanitized[key] = value }
    return sanitized
}

private fun validCloudConfigSettingKey(key: String): Boolean =
    key.length in 1..MAX_CLOUD_CONFIG_SETTING_KEY_LENGTH &&
        key.all { it.code in 0x21..0x7E } &&
        cloudConfigManagedPrefixes.any { prefix -> key.startsWith(prefix) }

private fun validCloudConfigSettingValue(value: String): Boolean =
    value.length <= MAX_CLOUD_CONFIG_SETTING_VALUE_LENGTH && value.none { it.code < 0x20 || it == '\u007F' }

private fun encodeCloudConfig(settings: Map<String, String>): String {
    val body = settings.entries.joinToString(",\n") { (key, value) ->
        "    \"${jsonEscape(key)}\": \"${jsonEscape(value)}\""
    }
    val settingsBlock = if (body.isBlank()) "" else "\n$body\n  "
    return buildString {
        append("{\n")
        append("  \"format\": \"").append(CLOUD_CONFIG_FORMAT).append("\",\n")
        append("  \"version\": ").append(CLOUD_CONFIG_VERSION).append(",\n")
        append("  \"settings\": {").append(settingsBlock).append("}\n")
        append("}\n")
    }
}

private fun cloudConfigSettingsObjectBody(json: String): String? {
    val keyIndex = json.indexOf("\"settings\"")
    if (keyIndex < 0) return null
    val start = json.indexOf('{', keyIndex)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until json.length) {
        val c = json[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return json.substring(start + 1, i)
            }
        }
    }
    return null
}

private fun decodeCloudConfigStringMap(body: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < body.length) {
        val keyStart = body.indexOf('"', index)
        if (keyStart < 0) break
        val keyEnd = findCloudConfigStringEnd(body, keyStart + 1) ?: break
        val colon = body.indexOf(':', keyEnd + 1)
        if (colon < 0) break
        val valueStart = body.indexOf('"', colon + 1)
        if (valueStart < 0) break
        val valueEnd = findCloudConfigStringEnd(body, valueStart + 1) ?: break
        result[body.substring(keyStart + 1, keyEnd).jsonUnescape()] =
            body.substring(valueStart + 1, valueEnd).jsonUnescape()
        index = valueEnd + 1
    }
    return result
}

private fun findCloudConfigStringEnd(value: String, start: Int): Int? {
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

private fun requireAccount(body: String, state: ServerState): AccountRecord? {
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT) ?: return null
    val hwidHash = jsonString(body, "hwidHash")?.trim()?.uppercase(Locale.ROOT) ?: return null
    if (!accountKeyRegex.matches(accountKey) || !hwidHashRegex.matches(hwidHash)) return null
    val account = state.accounts.findByKey(accountKey) ?: return null
    if (account.disabled) return null
    if (account.hwidHash.isBlank()) return state.accounts.bindHwidIfEmpty(account.id, hwidHash)
    if (!account.hwidHash.equals(hwidHash, ignoreCase = true)) return null
    return account
}

private fun accountFromBodyOrCreate(body: String, state: ServerState): AccountRecord? {
    val hwidHash = jsonString(body, "hwidHash")?.trim()?.uppercase(Locale.ROOT) ?: return null
    if (!hwidHashRegex.matches(hwidHash)) return null

    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT)
    if (!accountKey.isNullOrBlank()) {
        if (!accountKeyRegex.matches(accountKey)) return null
        val account = state.accounts.findByKey(accountKey) ?: return null
        if (account.disabled) return null
        if (account.hwidHash.isBlank()) return state.accounts.bindHwidIfEmpty(account.id, hwidHash)
        if (!account.hwidHash.equals(hwidHash, ignoreCase = true)) return null
        return account
    }

    return state.accounts.createOrGetByHwid(hwidHash)
}

private fun accountFromBodyIfPresent(body: String, state: ServerState): AccountRecord? {
    val accountKey = jsonString(body, "accountKey")?.trim()?.uppercase(Locale.ROOT) ?: return null
    val hwidHash = jsonString(body, "hwidHash")?.trim()?.uppercase(Locale.ROOT) ?: return null
    if (!accountKeyRegex.matches(accountKey) || !hwidHashRegex.matches(hwidHash)) return null
    return requireAccount(body, state)
}

private fun accountPayload(account: AccountRecord, state: ServerState, status: String): String {
    val used = state.cloudConfigs.usedSlots(account.id)
    val roles = accountRoles(account, state)
    val rolesJson = roles.joinToString(",") { """"$it"""" }
    val roleIconsJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleIcon(it, state)) + "\"" }
    val roleGradientsJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleGradient(it, state)) + "\"" }
    val nickGradientsJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleNickGradient(it, state)) + "\"" }
    val roleDisplayNamesJson = roles.joinToString(",") { "\"" + it + "\":\"" + jsonEscape(roleDisplayName(it, state)) + "\"" }
    val roleSettings = roleSettingsForRoles(roles, state)
    return buildString {
        append("""{"ok":true,"status":"${jsonEscape(status)}","accountId":${account.id},"accountKey":"${account.accountKey}","createdAt":"${account.createdAt}","displayName":""")
        append(jsonNullable(account.displayName))
        append(""","contact":""")
        append(jsonNullable(account.contact))
        append(""","roles":[""")
        append(rolesJson)
        append("""],"roleIcons":{$roleIconsJson},"roleGradients":{$roleGradientsJson},"nickGradients":{$nickGradientsJson},"roleDisplayNames":{$roleDisplayNamesJson},"cloudUsed":$used,"cloudLimit":${roleSettings.cloudLimit},"cloudSaveCooldownSeconds":${roleSettings.saveCooldownSeconds},"cloudLoadCooldownSeconds":${roleSettings.loadCooldownSeconds},"cloudUploadBanned":${account.cloudUploadBanned}""")
        append(",\"roleGifLimitBytes\":${roleSettings.gifLimitBytes?.toString() ?: "null"}")
        append(",\"roleGifMaxConfigs\":${roleSettings.gifMaxConfigs?.toString() ?: "null"}")
        append(",\"roleCanChangeGradient\":${roleSettings.canChangeGradient}")
        append(",\"roleCanResetHwid\":${roleSettings.canResetHwid}")
        append(",\"roleHwidResetCount\":${roleSettings.hwidResetCount}")
        append("}")
    }
}

private fun accountRoles(account: AccountRecord, state: ServerState): List<String> {
    val found = linkedSetOf("USER")
    state.roleLinks.linksForAccount(account.id).forEach { link ->
        val license = state.licenses.find(link.licenseKey) ?: return@forEach
        if (!roleRegex.matches(license.role)) return@forEach

        val isActive = if (license.hwidHash == null && account.hwidHash.isNotBlank()) {
            when (state.licenses.checkAndBind(link.licenseKey, account.hwidHash)) {
                is LicenseCheckResult.Valid -> true
                is LicenseCheckResult.Invalid -> false
            }
        } else {
            license.activeFor(account.hwidHash)
        }

        if (isActive) {
            found += license.role
        }
    }
    if ("SPONSOR_PLUS_PLUS" in found) {
        found += "SPONSOR_PLUS"
        found += "SPONSOR"
    }
    if ("SPONSOR_PLUS" in found) {
        found += "SPONSOR"
    }
    return found.sortedWith(compareByDescending<String> { rolePriority(it) }.thenBy { it })
}

private fun rolePriority(role: String): Int {
    return when (role) {
        "OWNER" -> 50
        "ADMIN" -> 40
        "MODERATOR" -> 36
        "HELPER" -> 34
        "QA" -> 30
        "SPONSOR_PLUS_PLUS" -> 28
        "SPONSOR_PLUS" -> 26
        "SPONSOR" -> 20
        "USER" -> 10
        else -> 25
    }
}

private fun roleSettingsForRoles(roles: Collection<String>, state: ServerState): RoleRuntimeSettings {
    val settings = roles.map { role -> state.roleSettings.find(role) ?: defaultRoleSettings(role) }
    return RoleRuntimeSettings(
        cloudLimit = settings.maxOfOrNull { it.cloudLimit } ?: DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT,
        saveCooldownSeconds = settings.minOfOrNull { it.saveCooldownSeconds } ?: DEFAULT_USER_CLOUD_COOLDOWN_SECONDS,
        loadCooldownSeconds = settings.minOfOrNull { it.loadCooldownSeconds } ?: DEFAULT_USER_CLOUD_COOLDOWN_SECONDS,
        gifLimitBytes = settings.mapNotNull { it.gifLimitBytes }.maxOrNull(),
        gifMaxConfigs = settings.mapNotNull { it.gifMaxConfigs }.maxOrNull(),
        canChangeGradient = settings.any { it.canChangeGradient },
        canResetHwid = settings.any { it.canResetHwid },
        hwidResetCount = settings.maxOfOrNull { it.hwidResetCount } ?: 0,
    )
}

private fun defaultRoleSettings(role: String): RoleSettingsRecord {
    return when (role) {
        "OWNER" -> RoleSettingsRecord(role, STAFF_CLOUD_CONFIGS_PER_ACCOUNT, 0, 0, "linear-gradient(90deg, #9932CC, #DA70D6)", gifLimitBytes = 30L * 1024 * 1024, gifMaxConfigs = 30, displayName = "Owner")
        "ADMIN" -> RoleSettingsRecord(role, STAFF_CLOUD_CONFIGS_PER_ACCOUNT, 0, 0, "linear-gradient(90deg, #FF4444, #FF6B6B)", displayName = "Admin")
        "MODERATOR" -> RoleSettingsRecord(role, STAFF_CLOUD_CONFIGS_PER_ACCOUNT, 0, 0, "linear-gradient(90deg, #3BA55D, #57F287)", displayName = "Moderator")
        "HELPER" -> RoleSettingsRecord(role, STAFF_CLOUD_CONFIGS_PER_ACCOUNT, 0, 0, "linear-gradient(90deg, #5865F2, #7289DA)", displayName = "Helper")
        "SPONSOR" -> RoleSettingsRecord(role, 12, 5, 5, "linear-gradient(90deg, #38BDF8, #7DD3FC)", nickGradient = "linear-gradient(90deg, #80FF97, #6BB7FF)", iconFile = "role_sponsor", gifLimitBytes = 5L * 1024 * 1024, gifMaxConfigs = 3, displayName = "Sponsor")
        "SPONSOR_PLUS" -> RoleSettingsRecord(role, 25, 5, 5, "linear-gradient(90deg, #6BB7FF, #FFD700)", nickGradient = "linear-gradient(90deg, #A217FF, #2C37FF)", iconFile = "role_sponsor", canChangeGradient = true, gifLimitBytes = 10L * 1024 * 1024, gifMaxConfigs = 6, displayName = "Sponsor [+]")
        "SPONSOR_PLUS_PLUS" -> RoleSettingsRecord(role, 50, 5, 5, "linear-gradient(90deg, #9932CC, #DA70D6)", nickGradient = "linear-gradient(90deg, #FFD700, #FFA500)", iconFile = "role_sponsor", canChangeGradient = true, canResetHwid = true, hwidResetCount = -1, gifLimitBytes = 30L * 1024 * 1024, gifMaxConfigs = 20, displayName = "Sponsor [++]")
        "QA" -> RoleSettingsRecord(role, DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, "linear-gradient(90deg, #00CED1, #20B2AA)", displayName = "QA")
        else -> RoleSettingsRecord(role, DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, "linear-gradient(90deg, #888888, #BBBBBB)", displayName = role.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
    }
}

private fun List<AccountRoleLinkRecord>.exclusiveActiveLinks(): List<AccountRoleLinkRecord> {
    val activeWinnerByKey = asSequence()
        .filter { !it.disabled }
        .groupBy { it.licenseKey }
        .mapValues { (_, records) ->
            records.maxWithOrNull(compareBy<AccountRoleLinkRecord> { parseInstantOrEpoch(it.grantedAt) }.thenBy { it.accountId })
        }
    return map { record ->
        if (!record.disabled && activeWinnerByKey[record.licenseKey] != record) record.copy(disabled = true) else record
    }
}

private fun roleIcon(role: String, state: ServerState): String {
    if (role == "USER") return "role_user.png"
    val settings = state.roleSettings.find(role)
    val iconRole = settings?.iconFile ?: role
    return if (state.roleIconDir.resolve("$iconRole.png").exists()) {
        "/api/role-icons/$iconRole.png"
    } else {
        when (role) {
            "OWNER" -> "role_owner.png"
            "ADMIN" -> "role_admin.png"
            "MODERATOR" -> "role_moderator.png"
            "HELPER" -> "role_helper.png"
            "QA" -> "role_qa.png"
            "SPONSOR" -> "role_sponsor.png"
            "SPONSOR_PLUS" -> "role_sponsor.png"
            "SPONSOR_PLUS_PLUS" -> "role_sponsor.png"
            else -> "role_custom.png"
        }
    }
}

private fun roleGradient(role: String, state: ServerState): String {
    return state.roleSettings.find(role)?.textGradient ?: defaultRoleSettings(role).textGradient ?: ""
}

private fun roleNickGradient(role: String, state: ServerState): String {
    return state.roleSettings.find(role)?.nickGradient ?: ""
}

private fun roleDisplayName(role: String, state: ServerState): String {
    return state.roleSettings.find(role)?.displayName ?: when (role) {
        "OWNER" -> "Owner"
        "ADMIN" -> "Admin"
        "MODERATOR" -> "Moderator"
        "HELPER" -> "Helper"
        "QA" -> "QA"
        "SPONSOR" -> "Sponsor"
        "SPONSOR_PLUS" -> "Sponsor [+]"
        "SPONSOR_PLUS_PLUS" -> "Sponsor [++]"
        "VIP" -> "VIP"
        "USER" -> "User"
        else -> role.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}

private fun roleIconAsset(exchange: HttpExchange, state: ServerState) {
    if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
        return text(exchange, 405, "Method not allowed")
    }

    val fileName = exchange.requestURI.path.substringAfterLast('/')
    if (!fileName.endsWith(".png", ignoreCase = true)) return text(exchange, 404, "Not found")
    val role = fileName.removeSuffix(".png").uppercase(Locale.ROOT)
    if (role == "USER" || !roleRegex.matches(role)) return text(exchange, 404, "Not found")

    val file = state.roleIconDir.resolve("$role.png").normalize()
    if (!file.startsWith(state.roleIconDir.normalize()) || !file.exists()) return text(exchange, 404, "Not found")

    val bytes = Files.readAllBytes(file)
    exchange.responseHeaders.set("Content-Type", "image/png")
    if (exchange.requestMethod == "HEAD") {
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
        return
    }
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun LicenseCheckResult.toJson(): String {
    return when (this) {
        is LicenseCheckResult.Valid -> """{"valid":true,"status":"$status","role":"$role","expiresAt":${jsonNullable(expiresAt)}}"""
        is LicenseCheckResult.Invalid -> invalidJson(reason)
    }
}

private fun apiError(status: String): String = """{"ok":false,"status":"${jsonEscape(status)}"}"""

private fun rateLimitJson(retryAfterSeconds: Long): String = """{"ok":false,"status":"RATE_LIMIT","retryAfterSeconds":$retryAfterSeconds}"""

private data class AccountMetadata(
    val hwidResetCount: Int = 0,
    val lastHwidResetAt: String? = null,
) {
    fun toJson(): String {
        val parts = mutableListOf("\"hwidResetCount\":$hwidResetCount")
        lastHwidResetAt?.let { parts += "\"lastHwidResetAt\":\"${jsonEscape(it)}\"" }
        return "{${parts.joinToString(",")}}"
    }
}

private fun parseAccountMetadata(json: String?): AccountMetadata {
    if (json.isNullOrBlank()) return AccountMetadata()
    val count = Regex("\"hwidResetCount\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val lastAt = Regex("\"lastHwidResetAt\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    return AccountMetadata(count, lastAt)
}

private fun hmacSha256(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)))
}

private fun verifyResetToken(secret: String, accountKey: String, timestamp: Long, token: String): Boolean {
    if (kotlin.math.abs(System.currentTimeMillis() - timestamp) > 5 * 60 * 1000) return false
    val expected = hmacSha256(secret, "$accountKey:$timestamp")
    return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), token.toByteArray(StandardCharsets.UTF_8))
}

private fun checkEndpointRateLimit(exchange: HttpExchange, state: ServerState, endpoint: String, clientKey: String?, maxPerMinute: Int): Boolean {
    if (maxPerMinute <= 0) return true
    val key = clientKey?.takeIf { it.isNotBlank() } ?: exchange.remoteAddress.address.hostAddress
    val cooldownSeconds = 60 / maxPerMinute
    return when (val limit = state.rateLimiter.check("ratelimit:$endpoint:$key", cooldownSeconds)) {
        is RateLimitResult.Limited -> {
            json(exchange, 200, rateLimitJson(limit.retryAfterSeconds))
            false
        }
        RateLimitResult.Allowed -> true
    }
}

private fun invalidJson(reason: String): String = """{"valid":false,"status":"${jsonEscape(reason)}"}"""

private fun parseExpiresArgument(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank() || raw.equals("never", ignoreCase = true)) return null
    return try {
        LocalDate.parse(raw).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("Date must be YYYY-MM-DD or never")
    }
}

private fun parseInstantOrEpoch(value: String): Instant = runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)

private fun String.uppercaseRole(): String? {
    val role = trim().uppercase(Locale.ROOT)
    return role.takeIf { roleRegex.matches(it) }
}

private fun generateKey(length: Int): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

private fun generateUniqueKey(length: Int, existing: Set<String>): String {
    repeat(100) {
        val key = generateKey(length)
        if (key !in existing) return key
    }
    throw IllegalStateException("Could not generate unique key")
}

private fun generateCloudConfigKey(existing: Set<String>): String = generateUniqueKey(8, existing)

private fun atomicWrite(file: Path, content: String) {
    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
    tmp.writeText(content)
    runCatching {
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse {
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun splitArgs(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for (char in line) {
        when {
            char == '"' -> quoted = !quoted
            char.isWhitespace() && !quoted -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }
            else -> current.append(char)
        }
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

private fun HttpExchange.bodyString(): String {
    return requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

private fun jsonString(json: String, name: String): String? {
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
        return json.substring(valueStart, valueEnd).jsonUnescape()
    }
}

private fun jsonLong(json: String, name: String): Long? {
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
        if (index >= json.length || json[index] == '"') {
            // Fall back to string-encoded number
            return jsonString(json, name)?.toLongOrNull()
        }
        val valueStart = index
        var valueEnd = index
        while (valueEnd < json.length && (json[valueEnd].isDigit() || json[valueEnd] == '-' || json[valueEnd] == '+')) valueEnd++
        if (valueEnd == valueStart) return null
        return json.substring(valueStart, valueEnd).toLongOrNull()
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

private fun jsonInt(json: String, name: String): Int? {
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
        val start = index
        if (start < json.length && json[start] == '-') index++
        while (index < json.length && json[index] in '0'..'9') index++
        if (start == index) return null
        return json.substring(start, index).toIntOrNull()
    }
}

private fun jsonObjectString(json: String, name: String): String? {
    val key = """$name"""
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
        if (index >= json.length || json[index] != '{') return null
        val start = index
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return json.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}

private fun text(exchange: HttpExchange, code: Int, body: String) {
    exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
    write(exchange, code, body)
}

private fun json(exchange: HttpExchange, code: Int, body: String) {
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    write(exchange, code, body)
}

private fun write(exchange: HttpExchange, code: Int, body: String) {
    if (exchange.requestMethod == "HEAD") {
        exchange.sendResponseHeaders(code, -1)
        exchange.close()
        return
    }
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun jsonNullable(value: String?): String = value?.let { """"${jsonEscape(it)}"""" } ?: "null"

private fun jsonEscape(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

private fun String.jsonUnescape(): String {
    return replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
}

private fun String.tsv(): String = replace("\t", " ").replace("\n", " ").replace("\r", " ")

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

