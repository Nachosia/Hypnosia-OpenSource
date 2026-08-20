package dev.hypnosia.licenseadmin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 9090
private const val DEFAULT_LICENSE_FILE = "data/licenses.tsv"
private const val DEFAULT_ACCOUNT_FILE = "data/accounts.tsv"
private const val DEFAULT_ACCOUNT_ROLE_LINK_FILE = "data/account-role-links.tsv"
private const val DEFAULT_CLOUD_CONFIG_FILE = "data/cloud-configs.tsv"
private const val DEFAULT_PRESENCE_FILE = "data/account-presence.tsv"
private const val DEFAULT_NOTIFICATION_FILE = "data/notifications.tsv"
private const val DEFAULT_ROLE_SETTINGS_FILE = "data/role-settings.tsv"
private const val DEFAULT_ROLE_ICON_DIR = "data/role-icons"
private const val DEFAULT_BACKUP_DIR = "data/backups"
private const val DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT = 3
private const val SPONSOR_CLOUD_CONFIGS_PER_ACCOUNT = 15
private const val STAFF_CLOUD_CONFIGS_PER_ACCOUNT = 100
private const val DEFAULT_USER_CLOUD_COOLDOWN_SECONDS = 15
private const val ONLINE_TTL_SECONDS = 90L

private val defaultRoles = setOf("USER", "SPONSOR", "QA", "ADMIN", "OWNER")
private val roleRegex = Regex("^[A-Z][A-Z0-9_]{1,31}$")
private val licenseRegex = Regex("^[A-Z0-9]{32}$")
private val accountKeyRegex = Regex("^[A-Z0-9]{32}$")
private val cloudConfigKeyRegex = Regex("^[A-Z0-9]{8}$")
private val hwidHashRegex = Regex("^[A-Fa-f0-9]{64}$")
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50.toByte(),
    0x4E.toByte(),
    0x47.toByte(),
    0x0D.toByte(),
    0x0A.toByte(),
    0x1A.toByte(),
    0x0A.toByte(),
)

fun main() {
    val host = env("HYPNOSIA_ADMIN_HOST") ?: DEFAULT_HOST
    val port = env("HYPNOSIA_ADMIN_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val password = env("HYPNOSIA_ADMIN_PASSWORD")
    if (password.isNullOrBlank()) {
        println("ERROR: HYPNOSIA_ADMIN_PASSWORD is not set. Admin panel cannot start without authentication.")
        exitProcess(1)
    }

    val state = AdminState(
        licenses = LicenseStorage(Path.of(env("HYPNOSIA_LICENSE_DATA") ?: DEFAULT_LICENSE_FILE)),
        accounts = AccountStorage(Path.of(env("HYPNOSIA_ACCOUNT_DATA") ?: DEFAULT_ACCOUNT_FILE)),
        roleLinks = AccountRoleLinkStorage(Path.of(env("HYPNOSIA_ACCOUNT_ROLE_LINK_DATA") ?: DEFAULT_ACCOUNT_ROLE_LINK_FILE)),
        cloudConfigs = CloudConfigStorage(Path.of(env("HYPNOSIA_CLOUD_CONFIG_DATA") ?: DEFAULT_CLOUD_CONFIG_FILE)),
        presence = PresenceStorage(Path.of(env("HYPNOSIA_PRESENCE_DATA") ?: DEFAULT_PRESENCE_FILE)),
        notifications = NotificationStorage(Path.of(env("HYPNOSIA_NOTIFICATION_DATA") ?: DEFAULT_NOTIFICATION_FILE)),
        roleSettings = RoleSettingsStorage(Path.of(env("HYPNOSIA_ROLE_SETTINGS_DATA") ?: DEFAULT_ROLE_SETTINGS_FILE)),
        roleIconDir = Path.of(env("HYPNOSIA_ROLE_ICON_DIR") ?: DEFAULT_ROLE_ICON_DIR),
    )
    state.roleIconDir.createDirectories()
    val panel = AdminPanel(
        state = state,
        backupDir = Path.of(env("HYPNOSIA_BACKUP_DIR") ?: DEFAULT_BACKUP_DIR),
        auth = password.let {
            BasicAuth(
                username = env("HYPNOSIA_ADMIN_USER") ?: "admin",
                password = it,
            )
        },
    )

    val server = HttpServer.create(InetSocketAddress(host, port), 0)
    server.createContext("/") { exchange -> panel.route(exchange) }
    server.executor = Executors.newFixedThreadPool(4)
    server.start()

    println("Hypnosia admin panel listening on http://$host:$port")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(2) })
    CountDownLatch(1).await()
}

private data class AdminState(
    val licenses: LicenseStorage,
    val accounts: AccountStorage,
    val roleLinks: AccountRoleLinkStorage,
    val cloudConfigs: CloudConfigStorage,
    val presence: PresenceStorage,
    val notifications: NotificationStorage,
    val roleSettings: RoleSettingsStorage,
    val roleIconDir: Path,
)

private class AdminPanel(
    private val state: AdminState,
    private val backupDir: Path,
    private val auth: BasicAuth?,
) {
    fun route(exchange: HttpExchange) {
        try {
            if (auth != null && !auth.authorized(exchange)) return unauthorized(exchange)

            val path = exchange.requestURI.path
            when {
                path == "/" || path == "/admin" -> serveStatic(exchange, "/index.html")
                path.startsWith("/assets/") -> serveStatic(exchange, path)
                path == "/api/stats" -> requireMethod(exchange, "GET") { apiStats(exchange) }
                path == "/api/accounts" -> requireMethod(exchange, "GET") { apiAccounts(exchange) }
                path == "/api/licenses" -> requireMethod(exchange, "GET") { apiLicenses(exchange) }
                path == "/api/cloud-configs" -> requireMethod(exchange, "GET") { apiCloudConfigs(exchange) }
                path == "/api/role-settings" -> requireMethod(exchange, "GET") { apiRoleSettings(exchange) }
                path == "/license/create" -> requireMethod(exchange, "POST") { createLicense(exchange) }
                path == "/license/update" -> requireMethod(exchange, "POST") { updateLicense(exchange) }
                path == "/license/reset-hwid" -> requireMethod(exchange, "POST") { resetLicenseHwid(exchange) }
                path == "/license/unlink-account" -> requireMethod(exchange, "POST") { unlinkLicenseAccount(exchange) }
                path == "/license/delete" -> requireMethod(exchange, "POST") { deleteLicense(exchange) }
                path == "/license/download" -> requireMethod(exchange, "GET") { downloadLicense(exchange) }
                path == "/role-icon/upload" -> requireMethod(exchange, "POST") { uploadRoleIcon(exchange) }
                path.startsWith("/api/role-icons/") -> requireMethod(exchange, "GET") { serveRoleIcon(exchange) }
                path == "/api/role-icons" -> requireMethod(exchange, "GET") { apiRoleIconsList(exchange) }
                path == "/role-icons-page" -> requireMethod(exchange, "GET") { roleIconsPage(exchange) }
                path == "/register-page" -> requireMethod(exchange, "GET") { registerPage(exchange) }
                path == "/role-settings/update" -> requireMethod(exchange, "POST") { updateRoleSettings(exchange) }
                path == "/account" -> requireMethod(exchange, "GET") { accountDetails(exchange) }
                path == "/account/create" -> requireMethod(exchange, "POST") { createAccount(exchange) }
                path == "/account/update" -> requireMethod(exchange, "POST") { updateAccount(exchange) }
                path == "/account/reset-key" -> requireMethod(exchange, "POST") { resetAccountKey(exchange) }
                path == "/account/reset-hwid" -> requireMethod(exchange, "POST") { resetAccountHwid(exchange) }
                path == "/account/cloud-ban" -> requireMethod(exchange, "POST") { toggleAccountCloudBan(exchange) }
                path == "/account/delete" -> requireMethod(exchange, "POST") { deleteAccount(exchange) }
                path == "/account/link-license" -> requireMethod(exchange, "POST") { linkLicenseToAccount(exchange) }
                path == "/account/unlink-license" -> requireMethod(exchange, "POST") { unlinkLicenseFromAccount(exchange) }
                path == "/account/download" -> requireMethod(exchange, "GET") { downloadAccount(exchange) }
                path == "/notification/send" -> requireMethod(exchange, "POST") { sendNotification(exchange) }
                path == "/cloud-config/download" -> requireMethod(exchange, "GET") { downloadCloudConfig(exchange) }
                path == "/cloud-config/toggle" -> requireMethod(exchange, "POST") { toggleCloudConfig(exchange) }
                path == "/cloud-config/delete" -> requireMethod(exchange, "POST") { deleteCloudConfig(exchange) }
                path == "/logs" -> requireMethod(exchange, "GET") { logs(exchange) }
                path == "/backup" -> requireMethod(exchange, "POST") { backup(exchange) }
                else -> {
                    if (path.contains(".")) {
                        serveStatic(exchange, path)
                    } else {
                        serveStatic(exchange, "/index.html")
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            redirect(exchange, "/?error=${url(error.message ?: "Invalid request")}")
        } catch (error: Throwable) {
            error.printStackTrace()
            redirect(exchange, "/?error=${url(error.message ?: "Server error")}")
        }
    }

    private fun index(exchange: HttpExchange) {
        val accounts = state.accounts.all()
        val licenses = state.licenses.all()
        val links = state.roleLinks.all()
        val cloudConfigs = state.cloudConfigs.all()
        val presence = state.presence.all()
        val onlineIds = state.presence.onlineAccountIds()
        val message = query(exchange, "msg")
        val error = query(exchange, "error")
        val accountSearch = query(exchange, "accountSearch")?.trim().orEmpty()
        val visibleAccounts = accounts.filterAccounts(accountSearch)
        val stats = Stats.from(accounts, licenses, cloudConfigs)

        html(exchange, page("Hypnosia Admin") {
            append("<section class=\"hero\">")
            append("<div><h1>Hypnosia Admin</h1><p>Accounts, role keys, and player cloud configs. Local SSH tunnel only.</p></div>")
            append("<div class=\"hero-actions\">")
            append("<a class=\"button\" href=\"/logs\">Debug logs 24h</a>")
            append("<form method=\"post\" action=\"/backup\"><button>Backup now</button></form>")
            append("</div>")
            append("</section>")

            if (message != null) append("<div class=\"notice ok\">${esc(message)}</div>")
            if (error != null) append("<div class=\"notice err\">${esc(error)}</div>")

            append("<section class=\"stats\">")
            stat("Accounts", stats.accounts)
            stat("Active Accounts", stats.activeAccounts)
            stat("Online Now", onlineIds.size)
            stat("Role Keys", stats.licenses)
            stat("Bound Keys", stats.boundLicenses)
            stat("Cloud Configs", stats.cloudConfigs)
            append("</section>")

            notificationsSection(onlineIds.size)
            accountsSection(visibleAccounts, links, presence, accountSearch)
            roleKeysSection(licenses, links)
            cloudConfigsSection(cloudConfigs, accounts)
        })
    }

    private fun StringBuilder.notificationsSection(onlineCount: Int) {
        append("<section class=\"card\"><h2>Notifications</h2>")
        append("<p class=\"muted\">Message is queued only for accounts that are online now. Offline users will not receive it.</p>")
        append("<form class=\"notify-grid\" method=\"post\" action=\"/notification/send\">")
        append("<input name=\"message\" maxlength=\"240\" placeholder=\"Nachosia message text\">")
        append("<button>Send to online ($onlineCount)</button>")
        append("</form>")
        append("</section>")
    }

    private fun StringBuilder.accountsSection(accounts: List<AccountRecord>, links: List<AccountRoleLinkRecord>, presence: List<PresenceRecord>, search: String) {
        append("<section class=\"card\"><h2>Accounts</h2>")
        append("<form class=\"search-grid\" method=\"get\" action=\"/\">")
        append("<input name=\"accountSearch\" value=\"${esc(search)}\" placeholder=\"search by id, name, contact, hwid\">")
        append("<button>Search</button>")
        if (search.isNotBlank()) append("<a class=\"button\" href=\"/\">Clear</a>")
        append("</form>")
        append("<form class=\"create-grid\" method=\"post\" action=\"/account/create\">")
        input("hwid", "HWID hash", "", "64-char HWID hash")
        input("displayName", "Display name", "", "optional")
        append("<button>Create account</button>")
        append("</form>")

        if (accounts.isEmpty()) {
            append("<p class=\"muted\">No accounts yet.</p>")
        } else {
            append("<div class=\"table accounts\">")
            append("<div class=\"row account-row head\"><span>ID</span><span>Name</span><span>Contact</span><span>Status</span><span>Roles</span><span>HWID</span><span>Created</span><span>Cloud</span><span>Actions</span></div>")
            accounts.forEach { account ->
                val roles = rolesFor(account, links).joinToString(", ")
                val roleList = rolesFor(account, links)
                val cloudUsed = state.cloudConfigs.usedSlots(account.id)
                val presenceRecord = presence.firstOrNull { it.accountId == account.id }
                val online = presenceRecord?.isOnline() == true
                append("<div class=\"row account-row\">")
                append("<code><a href=\"/account?id=${account.id}\">#${account.id}</a></code>")
                append("<span>${esc(account.displayName ?: "-")}</span>")
                append("<span>${esc(account.contact ?: "-")}</span>")
                append("<span class=\"pill ${if (online) "bound" else "disabled"}\">${if (online) "online" else "offline"}</span>")
                append("<span>${esc(roles)}</span>")
                append("<span>${esc(account.hwidHash.take(12).ifBlank { "reset" })}</span>")
                append("<span>${esc(account.createdAt.substringBefore('T'))}</span>")
                append("<span>$cloudUsed/${roleSettingsForRoles(roleList, state.roleSettings).cloudLimit}${if (account.cloudUploadBanned) " <span class=\"pill disabled\">upload banned</span>" else ""}</span>")
                append("<div class=\"actions\">")
                append("<form method=\"post\" action=\"/account/update\">")
                hidden("id", account.id.toString())
                append("<input name=\"displayName\" value=\"${esc(account.displayName ?: "")}\" placeholder=\"name\">")
                append("<input name=\"contact\" value=\"${esc(account.contact ?: "")}\" placeholder=\"contact\">")
                append("<label><input type=\"checkbox\" name=\"disabled\" value=\"true\" ${if (account.disabled) "checked" else ""}> disabled</label>")
                append("<button>Save</button>")
                append("</form>")
                append("<a class=\"button\" href=\"/account/download?id=${account.id}\">Download file</a>")
                append("<a class=\"button\" href=\"/account?id=${account.id}\">Details</a>")
                append("<form method=\"post\" action=\"/account/cloud-ban\">")
                hidden("id", account.id.toString())
                hidden("banned", (!account.cloudUploadBanned).toString())
                append("<button${if (!account.cloudUploadBanned) " class=\"danger\"" else ""}>${if (account.cloudUploadBanned) "Unban upload" else "Ban upload"}</button>")
                append("</form>")
                postButton("/account/reset-key", account.id.toString(), "Reset key")
                postButton("/account/reset-hwid", account.id.toString(), "Reset HWID")
                postButton("/account/delete", account.id.toString(), "Delete", danger = true)
                append("</div>")
                append("</div>")
            }
            append("</div>")
        }
        append("</section>")
    }

    private fun StringBuilder.roleKeysSection(licenses: List<LicenseRecord>, links: List<AccountRoleLinkRecord>) {
        append("<section class=\"card\"><h2>Role Keys</h2>")
        append("<form class=\"create-grid\" method=\"post\" action=\"/license/create\">")
        selectRole()
        input("customRole", "Custom role", "", "for example MEDIA")
        input("cloudLimit", "Cloud slots", "3", "USER=3, SPONSOR=15")
        input("saveCooldown", "Save cooldown", "15", "seconds")
        input("loadCooldown", "Load cooldown", "15", "seconds")
        input("gifLimitBytes", "GIF limit bytes", "", "e.g. 5242880")
        input("gifMaxConfigs", "GIF max configs", "", "e.g. 3")
        input("hwidResetCount", "HWID resets", "0", "-1 = unlimited")
        append("<div class=\"field\"><label>Perks</label>")
        append("<label><input type=\"checkbox\" name=\"canChangeGradient\" value=\"true\"> change gradient</label>")
        append("<label><input type=\"checkbox\" name=\"canResetHwid\" value=\"true\"> reset HWID</label>")
        append("</div>")
        input("expires", "Expires", "never", "YYYY-MM-DD or never")
        input("key", "Custom key", "", "empty = generate")
        append("<button>Create key</button>")
        append("</form>")
        append("<form class=\"create-grid\" method=\"post\" action=\"/role-icon/upload\" enctype=\"multipart/form-data\">")
        append("<div class=\"field\"><label>Role icon PNG</label><input name=\"role\" placeholder=\"SPONSOR or CUSTOM_ROLE\"></div>")
        append("<div class=\"field\"><label>PNG file</label><input type=\"file\" name=\"icon\" accept=\"image/png\"></div>")
        append("<button>Upload role icon</button>")
        append("</form>")
        val allIcons = state.roleIconDir.toFile().listFiles()?.filter { it.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()
        append("<form class=\"create-grid\" method=\"post\" action=\"/role-settings/update\">")
        append("<div class=\"field\"><label>Role</label><select name=\"role\">")
        allKnownRoles().forEach { r -> append("<option>$r</option>") }
        append("</select></div>")
        append("<div class=\"field\"><label>Icon file</label><select name=\"iconFile\"><option value=\"\">-- same as role name --</option>")
        allIcons.forEach { icon -> append("<option>${esc(icon.nameWithoutExtension)}</option>") }
        append("</select></div>")
        append("<button>Assign icon</button>")
        append("</form>")

        if (licenses.isEmpty()) {
            append("<p class=\"muted\">No role keys yet.</p>")
        } else {
            append("<div class=\"table rolekeys\">")
            append("<div class=\"row role-row head\"><span>Key</span><span>Role</span><span>Account</span><span>State</span><span>Expires</span><span>HWID</span><span>Actions</span></div>")
            licenses.forEach { record -> licenseRow(record, links.linkedAccountIds(record.licenseKey)) }
            append("</div>")
        }
        append("</section>")
    }

    private fun StringBuilder.licenseRow(record: LicenseRecord, linkedAccountIds: List<Int>) {
        val stateClass = when {
            record.disabled -> "disabled"
            record.isExpired() -> "expired"
            record.hwidHash == null -> "not-bound"
            else -> "bound"
        }

        append("<div class=\"row role-row\">")
        append("<code>${esc(record.licenseKey)}</code>")
        val settings = state.roleSettings.find(record.role) ?: defaultRoleSettings(record.role)
        val iconRole = settings.iconFile ?: record.role
        val hasIcon = roleIconExists(iconRole)
        append("<span>${esc(record.role)}${if (hasIcon) " <span class=\"pill bound\">icon</span>" else ""}</span>")
        append("<span>${linkedAccountIds.accountLinksHtml()}</span>")
        append("<span class=\"pill $stateClass\">$stateClass</span>")
        append("<span>${esc(record.expiresAt?.substringBefore('T') ?: "never")}</span>")
        append("<span>${esc(record.hwidHash?.take(12) ?: "-")}</span>")
        append("<div class=\"actions\">")
        append("<a class=\"button\" href=\"/license/download?key=${url(record.licenseKey)}\">Download file</a>")
        append("<form method=\"post\" action=\"/license/update\">")
        hidden("key", record.licenseKey)
        append("<select name=\"role\">")
        allKnownRoles().forEach { role ->
            append("<option${if (role == record.role) " selected" else ""}>$role</option>")
        }
        append("</select>")
        append("<input name=\"customRole\" value=\"\" placeholder=\"custom role\">")
        append("<input name=\"cloudLimit\" value=\"${settings.cloudLimit}\" title=\"cloud slots\">")
        append("<input name=\"saveCooldown\" value=\"${settings.saveCooldownSeconds}\" title=\"save cooldown seconds\">")
        append("<input name=\"loadCooldown\" value=\"${settings.loadCooldownSeconds}\" title=\"load cooldown seconds\">")
        append("<input name=\"gifLimitBytes\" value=\"${settings.gifLimitBytes?.toString() ?: ""}\" title=\"GIF limit bytes\">")
        append("<input name=\"gifMaxConfigs\" value=\"${settings.gifMaxConfigs?.toString() ?: ""}\" title=\"GIF max configs\">")
        append("<input name=\"hwidResetCount\" value=\"${settings.hwidResetCount}\" title=\"HWID resets (-1=unlimited)\">")
        append("<label><input type=\"checkbox\" name=\"canChangeGradient\" value=\"true\" ${if (settings.canChangeGradient) "checked" else ""}> gradient</label>")
        append("<label><input type=\"checkbox\" name=\"canResetHwid\" value=\"true\" ${if (settings.canResetHwid) "checked" else ""}> hwid</label>")
        append("<input name=\"expires\" value=\"${esc(record.expiresAt?.substringBefore('T') ?: "never")}\">")
        append("<label><input type=\"checkbox\" name=\"disabled\" value=\"true\" ${if (record.disabled) "checked" else ""}> disabled</label>")
        append("<button>Save</button>")
        append("</form>")
        postButton("/license/unlink-account", record.licenseKey, "Unlink ID")
        postButton("/license/reset-hwid", record.licenseKey, "Reset HWID")
        postButton("/license/delete", record.licenseKey, "Delete", danger = true)
        append("</div>")
        append("</div>")
    }

    private fun StringBuilder.cloudConfigsSection(cloudConfigs: List<CloudConfigRecord>, accounts: List<AccountRecord>) {
        append("<section class=\"card\"><h2>Cloud Configs</h2>")
        append("<p class=\"muted\">Configs uploaded by players through /cloudcfg save. Players share the 8-character config key.</p>")
        if (cloudConfigs.isEmpty()) {
            append("<p class=\"muted\">No cloud configs yet.</p>")
        } else {
            append("<div class=\"table cloud\">")
            append("<div class=\"row cloud-row head\"><span>Key</span><span>Name</span><span>Type</span><span>Owner</span><span>State</span><span>Updated</span><span>Actions</span></div>")
            cloudConfigs.forEach { record ->
                val account = record.ownerAccountId?.let { id -> accounts.firstOrNull { it.id == id } }
                val owner = if (account != null) {
                    "<a href=\"/account?id=${account.id}\">#${account.id} ${esc(account.displayName ?: "")}</a>".trim()
                } else {
                    esc(record.ownerHwidHash.take(12))
                }
                append("<div class=\"row cloud-row\">")
                append("<code>${esc(record.configKey)}</code>")
                append("<span>${esc(record.name)}</span>")
                append("<span class=\"pill ${when (record.configType) { "GIF" -> "bound"; "PNG" -> "bound"; else -> "disabled" }}\">${record.configType ?: "json"}</span>")
                append("<span>$owner</span>")
                append("<span class=\"pill ${if (record.disabled) "disabled" else "bound"}\">${if (record.disabled) "disabled" else "active"}</span>")
                append("<span>${esc(record.updatedAt.substringBefore('T'))}</span>")
                append("<div class=\"actions\">")
                append("<a class=\"button\" href=\"/cloud-config/download?key=${url(record.configKey)}\">Download</a>")
                append("<form method=\"post\" action=\"/cloud-config/toggle\">")
                hidden("key", record.configKey)
                hidden("disabled", (!record.disabled).toString())
                append("<button>${if (record.disabled) "Enable" else "Disable"}</button>")
                append("</form>")
                postButton("/cloud-config/delete", record.configKey, "Delete", danger = true)
                append("</div>")
                append("</div>")
            }
            append("</div>")
        }
        append("</section>")
    }

    private fun accountDetails(exchange: HttpExchange) {
        val id = queryAccountId(exchange)
        val account = state.accounts.findById(id) ?: throw IllegalArgumentException("Account not found")
        val allLinks = state.roleLinks.all()
        val roles = rolesFor(account, allLinks)
        val linkedLicenses = allLinks.filter { it.accountId == id }
        val configs = state.cloudConfigs.all().filter { it.ownerAccountId == id }
        val presence = state.presence.findByAccount(id)
        val online = presence?.isOnline() == true
        val message = query(exchange, "msg")
        val error = query(exchange, "error")

        html(exchange, page("Account #$id") {
            append("<section class=\"hero\">")
            append("<div><h1>Account #${account.id}</h1><p>${esc(account.displayName ?: "No display name")} · ${esc(account.contact ?: "no contact")}</p></div>")
            append("<a class=\"button\" href=\"/\">Back to overview</a>")
            append("</section>")
            if (message != null) append("<div class=\"notice ok\">${esc(message)}</div>")
            if (error != null) append("<div class=\"notice err\">${esc(error)}</div>")

            append("<section class=\"stats\">")
            stat("Cloud Slots", state.cloudConfigs.usedSlots(account.id))
            stat("Cloud Limit", roleSettingsForRoles(roles, state.roleSettings).cloudLimit)
            stat("Role Keys", linkedLicenses.count { !it.disabled })
            stat("Configs", configs.size)
            stat("Upload Ban", if (account.cloudUploadBanned) 1 else 0)
            stat("Online", if (online) 1 else 0)
            append("</section>")

            append("<section class=\"card detail-grid\">")
            append("<div>")
            append("<h2>Account Info</h2>")
            append("<p><b>ID:</b> #${account.id}</p>")
            append("<p><b>Name:</b> ${esc(account.displayName ?: "-")}</p>")
            append("<p><b>Contact:</b> ${esc(account.contact ?: "-")}</p>")
            append("<p><b>Created:</b> ${esc(account.createdAt)}</p>")
            append("<p><b>HWID:</b> <code>${esc(account.hwidHash.ifBlank { "reset" })}</code></p>")
            append("<p><b>Roles:</b> ${esc(roles.joinToString(", "))}</p>")
            append("<p><b>Status:</b> <span class=\"pill ${if (online) "bound" else "disabled"}\">${if (online) "online" else "offline"}</span></p>")
            append("<p><b>Last seen:</b> ${esc(presence?.lastSeenAt ?: "-")}</p>")
            append("<p><b>Account disabled:</b> ${account.disabled}</p>")
            append("<p><b>Cloud upload banned:</b> ${account.cloudUploadBanned}</p>")
            append("<div class=\"actions detail-actions\">")
            append("<a class=\"button\" href=\"/account/download?id=${account.id}\">Download account file</a>")
            append("<form method=\"post\" action=\"/account/cloud-ban\">")
            hidden("id", account.id.toString())
            hidden("banned", (!account.cloudUploadBanned).toString())
            hidden("return", "/account?id=${account.id}")
            append("<button${if (!account.cloudUploadBanned) " class=\"danger\"" else ""}>${if (account.cloudUploadBanned) "Unban cloud upload" else "Ban cloud upload"}</button>")
            append("</form>")
            postButton("/account/reset-key", account.id.toString(), "Reset key")
            postButton("/account/reset-hwid", account.id.toString(), "Reset HWID")
            append("</div>")
            append("</div>")

            append("<div>")
            append("<h2>Edit</h2>")
            append("<form class=\"detail-form\" method=\"post\" action=\"/account/update\">")
            hidden("id", account.id.toString())
            append("<label>Name<input name=\"displayName\" value=\"${esc(account.displayName ?: "")}\" placeholder=\"name\"></label>")
            append("<label>Contact<input name=\"contact\" value=\"${esc(account.contact ?: "")}\" placeholder=\"telegram / discord\"></label>")
            append("<label><input type=\"checkbox\" name=\"disabled\" value=\"true\" ${if (account.disabled) "checked" else ""}> disabled</label>")
            append("<button>Save account</button>")
            append("</form>")
            append("</div>")
            append("</section>")

            append("<section class=\"card\"><h2>Linked Role Keys</h2>")
            if (linkedLicenses.isEmpty()) {
                append("<p class=\"muted\">No linked role keys.</p>")
            } else {
                append("<div class=\"table detail-table\">")
                append("<div class=\"row detail-row head\"><span>Key</span><span>Role</span><span>State</span><span>Expires</span><span>Granted</span></div>")
                linkedLicenses.forEach { link ->
                    val license = state.licenses.find(link.licenseKey)
                    val stateClass = when {
                        link.disabled -> "disabled"
                        license == null -> "expired"
                        license.disabled -> "disabled"
                        license.isExpired() -> "expired"
                        else -> "bound"
                    }
                    append("<div class=\"row detail-row\">")
                    append("<code>${esc(link.licenseKey)}</code>")
                    append("<span>${esc(license?.role ?: "-")}</span>")
                    append("<span class=\"pill $stateClass\">$stateClass</span>")
                    append("<span>${esc(license?.expiresAt?.substringBefore('T') ?: "never")}</span>")
                    append("<span>${esc(link.grantedAt.substringBefore('T'))}</span>")
                    append("</div>")
                }
                append("</div>")
            }
            append("</section>")

            append("<section class=\"card\"><h2>Cloud Configs</h2>")
            if (configs.isEmpty()) {
                append("<p class=\"muted\">No cloud configs.</p>")
            } else {
                append("<div class=\"table detail-table\">")
                append("<div class=\"row detail-row head\"><span>Key</span><span>Name</span><span>Type</span><span>State</span><span>Updated</span><span>Actions</span></div>")
                configs.forEach { record ->
                    append("<div class=\"row detail-row\">")
                    append("<code>${esc(record.configKey)}</code>")
                    append("<span>${esc(record.name)}</span>")
                    append("<span class=\"pill ${when (record.configType) { "GIF" -> "bound"; "PNG" -> "bound"; else -> "disabled" }}\">${record.configType ?: "json"}</span>")
                    append("<span class=\"pill ${if (record.disabled) "disabled" else "bound"}\">${if (record.disabled) "disabled" else "active"}</span>")
                    append("<span>${esc(record.updatedAt.substringBefore('T'))}</span>")
                    append("<span class=\"actions\"><a class=\"button\" href=\"/cloud-config/download?key=${url(record.configKey)}\">Download</a></span>")
                    append("</div>")
                }
                append("</div>")
            }
            append("</section>")
        })
    }

    private fun createLicense(exchange: HttpExchange) {
        val form = exchange.form()
        val role = form.roleFromForm()
        val expiresAt = parseExpiresArgument(form["expires"])
        val key = form["key"]?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: generateKey(32)
        require(licenseRegex.matches(key)) { "License key must be 32 uppercase letters/digits" }
        state.roleSettings.upsert(form.roleSettings(role))

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
        redirect(exchange, "/?msg=${url("Created role key $key")}")
    }

    private fun updateLicense(exchange: HttpExchange) {
        val form = exchange.form()
        val key = form.licenseKey()
        val role = form.roleFromForm()
        val expiresAt = parseExpiresArgument(form["expires"])
        val disabled = form["disabled"] == "true"
        state.roleSettings.upsert(form.roleSettings(role))
        state.licenses.updateExisting(key) { copy(role = role, expiresAt = expiresAt, disabled = disabled) }
        redirect(exchange, "/?msg=${url("Updated role key $key")}")
    }

    private fun resetLicenseHwid(exchange: HttpExchange) {
        val key = exchange.form().licenseKey()
        state.licenses.updateExisting(key) { copy(hwidHash = null, boundAt = null) }
        state.roleLinks.unlinkLicense(key)
        redirect(exchange, "/?msg=${url("HWID reset $key")}")
    }

    private fun unlinkLicenseAccount(exchange: HttpExchange) {
        val key = exchange.form().licenseKey()
        state.roleLinks.unlinkLicense(key)
        redirect(exchange, "/?msg=${url("Account ID unlinked $key")}")
    }

    private fun deleteLicense(exchange: HttpExchange) {
        val key = exchange.form().licenseKey()
        state.licenses.deleteExisting(key)
        state.roleLinks.unlinkLicense(key)
        redirect(exchange, "/?msg=${url("Deleted role key $key")}")
    }

    private fun logs(exchange: HttpExchange) {
        html(exchange, page("Hypnosia Debug Logs") {
            append("<section class=\"hero\">")
            append("<div><h1>Debug logs</h1><p>Last 24 hours from hypnosia-license and hypnosia-admin services.</p></div>")
            append("<a class=\"button\" href=\"/\">Back</a>")
            append("</section>")
            append("<section class=\"card\">")
            append("<pre class=\"logs\">${esc(recentJournalLogs())}</pre>")
            append("</section>")
        })
    }

    private fun downloadLicense(exchange: HttpExchange) {
        val key = queryLicenseKey(exchange)
        state.licenses.find(key) ?: throw IllegalArgumentException("License not found")
        val body = """
            # Hypnosia license config.
            # Put this file into config/hypnosia/license.properties
            license.key=$key
        """.trimIndent() + "\n"
        download(exchange, "license.properties", "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun uploadRoleIcon(exchange: HttpExchange) {
        val parts = exchange.multipartForm()
        val role = parts["role"]?.text()?.uppercaseRole() ?: throw IllegalArgumentException("Invalid role")
        require(role != "USER") { "USER icon is local and cannot be overridden here" }

        val icon = parts["icon"] ?: throw IllegalArgumentException("Missing icon file")
        require(icon.bytes.size in 1..262_144) { "Icon must be a PNG up to 256 KB" }
        require(icon.bytes.size >= 8 && icon.bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) { "Icon must be a PNG file" }

        state.roleIconDir.createDirectories()
        Files.write(state.roleIconDir.resolve("$role.png"), icon.bytes)
        redirect(exchange, "/?msg=${url("Uploaded icon for $role")}")
    }

    private fun serveRoleIcon(exchange: HttpExchange) {
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

    private fun apiRoleIconsList(exchange: HttpExchange) {
        val icons = state.roleIconDir.toFile().listFiles()
            ?.filter { it.name.endsWith(".png") }
            ?.map {
                val role = it.nameWithoutExtension.uppercase(Locale.ROOT)
                """{"role":"$role","url":"/api/role-icons/$role.png"}"""
            } ?: emptyList()
        json(exchange, 200, "[${icons.joinToString(",")}]")
    }

    private fun roleIconsPage(exchange: HttpExchange) {
        val icons = state.roleIconDir.toFile().listFiles()
            ?.filter { it.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Role Icons</title>")
            append("<style>")
            append("body{font-family:sans-serif;background:#13131A;color:#F0F0F5;padding:20px;margin:0;}")
            append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:16px;}")
            append(".card{background:#1A1A24;border-radius:8px;padding:12px;text-align:center;border:1px solid #27272F;}")
            append(".card img{width:64px;height:64px;object-fit:contain;}")
            append(".card p{margin:8px 0 0;font-size:13px;color:#8A8A9A;word-break:break-all;}")
            append("h1{font-size:20px;margin-bottom:4px;}")
            append(".subtitle{font-size:13px;color:#8A8A9A;margin-bottom:16px;}")
            append("</style></head><body>")
            append("<h1>Custom Role Icons</h1>")
            append("<p class='subtitle'>${icons.size} icon(s) found in data/role-icons/</p>")
            append("<div class='grid'>")
            for (icon in icons) {
                val role = icon.nameWithoutExtension.uppercase(Locale.ROOT)
                append("<div class='card'><img src='/api/role-icons/$role.png'><p>$role</p></div>")
            }
            append("</div></body></html>")
        }
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun registerPage(exchange: HttpExchange) {
        val html = javaClass.getResource("/admin-static/register.html")?.readText()
            ?: throw IllegalStateException("register.html not found in classpath")
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun updateRoleSettings(exchange: HttpExchange) {
        val form = exchange.form()
        val role = form["role"]?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing role")
        state.roleSettings.upsert(form.roleSettings(role))
        redirect(exchange, "/?msg=${url("Updated role settings for $role")}")
    }

    private fun roleIconExists(role: String): Boolean {
        if (role == "USER") return false
        return state.roleIconDir.resolve("$role.png").exists()
    }

    private fun createAccount(exchange: HttpExchange) {
        val form = exchange.form()
        val hwid = form["hwid"]?.trim()?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing HWID")
        require(hwidHashRegex.matches(hwid)) { "Invalid HWID hash" }
        val name = form["displayName"]?.trim()?.take(32)?.takeIf { it.isNotBlank() }
        val account = state.accounts.createOrGetByHwid(hwid, name)
        redirect(exchange, "/?msg=${url("Created account #${account.id}")}")
    }

    private fun updateAccount(exchange: HttpExchange) {
        val form = exchange.form()
        val id = form.accountId()
        val disabled = form["disabled"] == "true"
        val cloudBanned = form["cloudUploadBanned"]?.toBooleanStrictOrNull()
        val name = form["displayName"]?.trim()?.take(32)?.takeIf { it.isNotBlank() }
        val contact = form["contact"]?.trim()?.take(96)?.takeIf { it.isNotBlank() }
        state.accounts.updateExisting(id) {
            copy(
                displayName = name ?: displayName,
                contact = contact ?: contact,
                disabled = if (form.containsKey("disabled")) disabled else this.disabled,
                cloudUploadBanned = if (cloudBanned != null) cloudBanned else this.cloudUploadBanned,
            )
        }
        redirect(exchange, "/?msg=${url("Updated account #$id")}")
    }

    private fun toggleAccountCloudBan(exchange: HttpExchange) {
        val form = exchange.form()
        val id = form.accountId()
        val banned = form["banned"]?.toBooleanStrictOrNull() ?: true
        state.accounts.updateExisting(id) { copy(cloudUploadBanned = banned) }
        val target = form["return"]?.takeIf { it.startsWith("/account?id=") } ?: "/"
        redirect(exchange, "$target${if ('?' in target) "&" else "?"}msg=${url(if (banned) "Cloud upload banned for account #$id" else "Cloud upload unbanned for account #$id")}")
    }

    private fun resetAccountKey(exchange: HttpExchange) {
        val id = exchange.form().accountId()
        val existing = state.accounts.all().map { it.accountKey }.toSet()
        val newKey = generateUniqueKey(32, existing)
        state.accounts.updateExisting(id) { copy(accountKey = newKey) }
        redirect(exchange, "/?msg=${url("Reset account #$id key")}")
    }

    private fun resetAccountHwid(exchange: HttpExchange) {
        val id = exchange.form().accountId()
        val linkedKeys = state.roleLinks.all().filter { it.accountId == id }.map { it.licenseKey }
        state.accounts.updateExisting(id) { copy(hwidHash = "") }
        linkedKeys.forEach { key ->
            runCatching { state.licenses.updateExisting(key) { copy(hwidHash = null, boundAt = null) } }
        }
        redirect(exchange, "/?msg=${url("Reset account #$id HWID")}")
    }

    private fun deleteAccount(exchange: HttpExchange) {
        val id = exchange.form().accountId()
        state.accounts.deleteExisting(id)
        state.roleLinks.deleteByAccount(id)
        state.cloudConfigs.deleteByOwner(id)
        state.presence.deleteByAccount(id)
        state.notifications.deleteByAccount(id)
        redirect(exchange, "/?msg=${url("Deleted account #$id")}")
    }

    private fun linkLicenseToAccount(exchange: HttpExchange) {
        val form = exchange.form()
        val accountId = form["accountId"]?.toIntOrNull() ?: throw IllegalArgumentException("Missing accountId")
        val key = form.licenseKey()
        state.roleLinks.link(accountId, key)
        redirect(exchange, "/?msg=${url("Linked key $key to account #$accountId")}")
    }

    private fun unlinkLicenseFromAccount(exchange: HttpExchange) {
        val form = exchange.form()
        val accountId = form["accountId"]?.toIntOrNull() ?: throw IllegalArgumentException("Missing accountId")
        val key = form.licenseKey()
        state.roleLinks.unlinkAccountLicense(accountId, key)
        redirect(exchange, "/?msg=${url("Unlinked key $key from account #$accountId")}")
    }

    private fun sendNotification(exchange: HttpExchange) {
        val form = exchange.form()
        val message = form["message"]?.trim()?.take(240)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Notification message is empty")
        val onlineIds = state.presence.onlineAccountIds()
        val activeIds = state.accounts.all()
            .filter { !it.disabled && it.id in onlineIds }
            .map { it.id }
            .toSet()
        val created = state.notifications.enqueue(activeIds, message)
        redirect(exchange, "/?msg=${url("Notification queued for $created online account(s)")}")
    }

    private fun downloadAccount(exchange: HttpExchange) {
        val id = queryAccountId(exchange)
        val account = state.accounts.findById(id) ?: throw IllegalArgumentException("Account not found")
        val body = """
            # Hypnosia account config.
            # Put this file into .minecraft/hypnosia/account.properties
            account.key=${account.accountKey}
            account.id=${account.id}
        """.trimIndent() + "\n"
        download(exchange, "account.properties", "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun downloadCloudConfig(exchange: HttpExchange) {
        val key = queryCloudConfigKey(exchange)
        val record = state.cloudConfigs.find(key) ?: throw IllegalArgumentException("Cloud config not found")
        download(exchange, "${record.configKey}.json", "application/json; charset=utf-8", record.payloadBytes())
    }

    private fun toggleCloudConfig(exchange: HttpExchange) {
        val form = exchange.form()
        val key = form.cloudConfigKey()
        val disabled = form["disabled"]?.toBooleanStrictOrNull() ?: false
        state.cloudConfigs.updateExisting(key) { copy(disabled = disabled, updatedAt = Instant.now().toString()) }
        redirect(exchange, "/?msg=${url("Cloud config $key updated")}")
    }

    private fun deleteCloudConfig(exchange: HttpExchange) {
        val key = exchange.form().cloudConfigKey()
        state.cloudConfigs.deleteExisting(key)
        redirect(exchange, "/?msg=${url("Cloud config $key deleted")}")
    }

    private fun backup(exchange: HttpExchange) {
        backupDir.createDirectories()
        val stamp = Instant.now().toString().replace(":", "-")
        state.licenses.copyTo(backupDir.resolve("licenses-$stamp.tsv"))
        state.accounts.copyTo(backupDir.resolve("accounts-$stamp.tsv"))
        state.roleLinks.copyTo(backupDir.resolve("account-role-links-$stamp.tsv"))
        state.cloudConfigs.copyTo(backupDir.resolve("cloud-configs-$stamp.tsv"))
        state.presence.copyTo(backupDir.resolve("account-presence-$stamp.tsv"))
        state.notifications.copyTo(backupDir.resolve("notifications-$stamp.tsv"))
        state.roleSettings.copyTo(backupDir.resolve("role-settings-$stamp.tsv"))
        redirect(exchange, "/?msg=${url("Backup created $stamp")}")
    }

    private fun rolesFor(account: AccountRecord, links: List<AccountRoleLinkRecord>): List<String> {
        val found = linkedSetOf("USER")
        links.filter { it.accountId == account.id && !it.disabled }.forEach { link ->
            val license = state.licenses.find(link.licenseKey) ?: return@forEach
            if (roleRegex.matches(license.role) && license.activeFor(account.hwidHash)) {
                found += license.role
            }
        }
        return found.sortedWith(compareByDescending<String> { rolePriority(it) }.thenBy { it })
    }

    private fun allKnownRoles(): List<String> {
        return (defaultRoles + state.licenses.all().map { it.role } + state.roleSettings.all().map { it.role })
            .filter { roleRegex.matches(it) }
            .sortedWith(compareByDescending<String> { rolePriority(it) }.thenBy { it })
    }

    private fun recentJournalLogs(): String {
        return try {
            val process = ProcessBuilder(
                "journalctl",
                "-u",
                "hypnosia-license",
                "-u",
                "hypnosia-admin",
                "--since",
                "-24h",
                "--no-pager",
                "-n",
                "1200",
            )
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "journalctl timed out after 3 seconds."
            }
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                .takeLast(80_000)
                .ifBlank { "No journal logs for the last 24 hours." }
        } catch (error: Throwable) {
            "Unable to read journalctl: ${error.message ?: error::class.simpleName}"
        }
    }

    private fun requireMethod(exchange: HttpExchange, method: String, block: () -> Unit) {
        if (exchange.requestMethod != method) {
            text(exchange, 405, "Method not allowed")
            return
        }
        block()
    }

    private fun serveStatic(exchange: HttpExchange, path: String) {
        val resourcePath = "/admin-static$path"
        val stream = javaClass.getResourceAsStream(resourcePath)
        if (stream == null) {
            if (!path.startsWith("/api/") && !path.contains(".")) {
                serveStatic(exchange, "/index.html")
            } else {
                text(exchange, 404, "Not found")
            }
            return
        }
        val bytes = stream.readAllBytes()
        val contentType = when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".html") -> "text/html"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun apiStats(exchange: HttpExchange) {
        val accounts = state.accounts.all()
        val licenses = state.licenses.all()
        val cloudConfigs = state.cloudConfigs.all()
        val onlineIds = state.presence.onlineAccountIds()
        val body = buildString {
            append("{")
            append("\"accounts\":${accounts.size},")
            append("\"activeAccounts\":${accounts.count { !it.disabled }},")
            append("\"licenses\":${licenses.size},")
            append("\"boundLicenses\":${licenses.count { it.hwidHash != null }},")
            append("\"cloudConfigs\":${cloudConfigs.size},")
            append("\"onlineNow\":${onlineIds.size}")
            append("}")
        }
        json(exchange, 200, body)
    }

    private fun apiAccounts(exchange: HttpExchange) {
        val accounts = state.accounts.all().sortedBy { it.id }
        val allLinks = state.roleLinks.all()
        val body = buildString {
            append("[")
            accounts.forEachIndexed { i, a ->
                if (i > 0) append(",")
                val links = allLinks.filter { it.accountId == a.id }
                val keys = links.map { it.licenseKey }
                val roles = keys.mapNotNull { key -> state.licenses.find(key)?.role }.distinct()
                append("{")
                append("\"id\":${a.id},")
                append("\"accountKey\":\"${a.accountKey}\",")
                append("\"hwidHash\":\"${a.hwidHash}\",")
                append("\"displayName\":${a.displayName?.let { "\"$it\"" } ?: "null"},")
                append("\"contact\":${a.contact?.let { "\"$it\"" } ?: "null"},")
                append("\"createdAt\":\"${a.createdAt}\",")
                append("\"disabled\":${a.disabled},")
                append("\"cloudUploadBanned\":${a.cloudUploadBanned},")
                append("\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],")
                append("\"licenseKeys\":[${keys.joinToString(",") { "\"$it\"" }}]")
                append("}")
            }
            append("]")
        }
        json(exchange, 200, body)
    }

    private fun apiLicenses(exchange: HttpExchange) {
        val licenses = state.licenses.all().sortedByDescending { it.createdAt }
        val allLinks = state.roleLinks.all()
        val body = buildString {
            append("[")
            licenses.forEachIndexed { i, l ->
                if (i > 0) append(",")
                val link = allLinks.firstOrNull { it.licenseKey == l.licenseKey }
                val accountId = link?.accountId
                val accountKey = accountId?.let { id -> state.accounts.findById(id)?.accountKey }
                append("{")
                append("\"licenseKey\":\"${l.licenseKey}\",")
                append("\"role\":\"${l.role}\",")
                append("\"hwidHash\":${l.hwidHash?.let { "\"$it\"" } ?: "null"},")
                append("\"createdAt\":\"${l.createdAt}\",")
                append("\"boundAt\":${l.boundAt?.let { "\"$it\"" } ?: "null"},")
                append("\"expiresAt\":${l.expiresAt?.let { "\"$it\"" } ?: "null"},")
                append("\"disabled\":${l.disabled},")
                append("\"accountId\":${accountId ?: "null"},")
                append("\"accountKey\":${accountKey?.let { "\"$it\"" } ?: "null"}")
                append("}")
            }
            append("]")
        }
        json(exchange, 200, body)
    }

    private fun apiCloudConfigs(exchange: HttpExchange) {
        val configs = state.cloudConfigs.all().sortedByDescending { it.updatedAt }
        val body = buildString {
            append("[")
            configs.forEachIndexed { i, c ->
                if (i > 0) append(",")
                append("{")
                append("\"configKey\":\"${c.configKey}\",")
                append("\"ownerAccountId\":${c.ownerAccountId ?: "null"},")
                append("\"ownerHwidHash\":\"${c.ownerHwidHash}\",")
                append("\"ownerLicenseKey\":${c.ownerLicenseKey?.let { "\"$it\"" } ?: "null"},")
                append("\"name\":\"${c.name}\",")
                append("\"createdAt\":\"${c.createdAt}\",")
                append("\"updatedAt\":\"${c.updatedAt}\",")
                append("\"disabled\":${c.disabled}")
                append("}")
            }
            append("]")
        }
        json(exchange, 200, body)
    }

    private fun apiRoleSettings(exchange: HttpExchange) {
        val settings = state.roleSettings.all().sortedBy { it.role }
        val body = buildString {
            append("[")
            settings.forEachIndexed { i, s ->
                if (i > 0) append(",")
                val iconRole = s.iconFile ?: s.role
                val hasIcon = roleIconExists(iconRole)
                append("{")
                append("\"role\":\"${s.role}\",")
                append("\"cloudLimit\":${s.cloudLimit},")
                append("\"saveCooldownSeconds\":${s.saveCooldownSeconds},")
                append("\"loadCooldownSeconds\":${s.loadCooldownSeconds},")
                append("\"textGradient\":${s.textGradient?.let { "\"$it\"" } ?: "null"},")
                append("\"nickGradient\":${s.nickGradient?.let { "\"$it\"" } ?: "null"},")
                append("\"iconFile\":${s.iconFile?.let { "\"$it\"" } ?: "null"},")
                append("\"iconUrl\":${if (hasIcon) "\"/api/role-icons/${iconRole}.png\"" else "null"},")
                append("\"gifLimitBytes\":${s.gifLimitBytes?.toString() ?: "null"},")
                append("\"gifMaxConfigs\":${s.gifMaxConfigs?.toString() ?: "null"},")
                append("\"canChangeGradient\":${s.canChangeGradient},")
                append("\"canResetHwid\":${s.canResetHwid},")
                append("\"hwidResetCount\":${s.hwidResetCount}")
                append("}")
            }
            append("]")
        }
        json(exchange, 200, body)
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

    fun copyTo(target: Path) = lock.read { copyFile(filePath, target) }

    private fun readAll(): List<LicenseRecord> {
        return filePath.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(LicenseRecord::fromLine)
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

    fun createOrGetByHwid(hwidHash: String, displayName: String?): AccountRecord {
        return lock.write {
            val records = readAll().toMutableList()
            val existing = records.firstOrNull { it.hwidHash.equals(hwidHash, ignoreCase = true) }
            if (existing != null) return@write existing
            val account = AccountRecord(
                id = (records.maxOfOrNull { it.id } ?: 0) + 1,
                accountKey = generateUniqueKey(32, records.asSequence().map { it.accountKey }.toSet()),
                hwidHash = hwidHash.uppercase(Locale.ROOT),
                displayName = displayName,
                contact = null,
                createdAt = Instant.now().toString(),
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

    fun findById(id: Int): AccountRecord? = lock.read { readAll().firstOrNull { it.id == id } }

    fun deleteExisting(id: Int) {
        lock.write {
            val records = readAll()
            require(records.any { it.id == id }) { "Account not found" }
            writeAll(records.filterNot { it.id == id })
        }
    }

    fun copyTo(target: Path) = lock.read { copyFile(filePath, target) }

    private fun readAll(): List<AccountRecord> {
        return filePath.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(AccountRecord::fromLine)
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

    fun deleteByAccount(accountId: Int) {
        lock.write {
            writeAll(readAll().filterNot { it.accountId == accountId })
        }
    }

    fun unlinkLicense(licenseKey: String) {
        lock.write {
            writeAll(readAll().map { record ->
                if (record.licenseKey == licenseKey) record.copy(disabled = true) else record
            })
        }
    }

    fun link(accountId: Int, licenseKey: String) {
        lock.write {
            val now = Instant.now().toString()
            val existing = readAll().find { it.accountId == accountId && it.licenseKey == licenseKey }
            if (existing != null) {
                if (!existing.disabled) return@write
                writeAll(readAll().map { if (it.accountId == accountId && it.licenseKey == licenseKey) it.copy(disabled = false) else it })
            } else {
                writeAll(readAll() + AccountRoleLinkRecord(accountId, licenseKey, now, false))
            }
        }
    }

    fun unlinkAccountLicense(accountId: Int, licenseKey: String) {
        lock.write {
            writeAll(readAll().map { record ->
                if (record.accountId == accountId && record.licenseKey == licenseKey) record.copy(disabled = true) else record
            })
        }
    }

    fun copyTo(target: Path) = lock.read { copyFile(filePath, target) }

    private fun readAll(): List<AccountRoleLinkRecord> {
        return filePath.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(AccountRoleLinkRecord::fromLine)
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
    fun find(key: String): CloudConfigRecord? = lock.read { readAll().firstOrNull { it.configKey == key } }
    fun usedSlots(accountId: Int): Int = lock.read { readAll().count { it.ownerAccountId == accountId && !it.disabled } }

    fun updateExisting(key: String, transform: CloudConfigRecord.() -> CloudConfigRecord) {
        lock.write {
            var found = false
            val records = readAll().map { record ->
                if (record.configKey == key) {
                    found = true
                    record.transform()
                } else {
                    record
                }
            }
            require(found) { "Cloud config not found" }
            writeAll(records)
        }
    }

    fun deleteExisting(key: String) {
        lock.write {
            val records = readAll()
            require(records.any { it.configKey == key }) { "Cloud config not found" }
            writeAll(records.filterNot { it.configKey == key })
        }
    }

    fun deleteByOwner(accountId: Int) {
        lock.write {
            writeAll(readAll().filterNot { it.ownerAccountId == accountId })
        }
    }

    fun copyTo(target: Path) = lock.read { copyFile(filePath, target) }

    private fun readAll(): List<CloudConfigRecord> {
        return filePath.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(CloudConfigRecord::fromLine)
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

    fun copyTo(target: Path) = lock.read { copyFile(filePath, target) }

    private fun readAll(): List<RoleSettingsRecord> {
        return filePath.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(RoleSettingsRecord::fromLine)
    }

    private fun writeAll(records: List<RoleSettingsRecord>) {
        atomicWrite(filePath, records.joinToString("\n") { it.toLine() } + if (records.isEmpty()) "" else "\n")
    }
}

private class PresenceStorage(val filePath: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        filePath.parent?.createDirectories()
        if (!filePath.exists()) filePath.writeText("")
    }

    fun all(): List<PresenceRecord> = lock.read { readAll().sortedBy { it.accountId } }

    fun findByAccount(accountId: Int): PresenceRecord? {
        return lock.read { readAll().firstOrNull { it.accountId == accountId } }
    }

    fun onlineAccountIds(now: Instant = Instant.now()): Set<Int> {
        return lock.read { readAll().filter { it.isOnline(now) }.map { it.accountId }.toSet() }
    }

    fun deleteByAccount(accountId: Int) {
        lock.write { writeAll(readAll().filterNot { it.accountId == accountId }) }
    }

    fun copyTo(target: Path) = lock.read { copyFile(filePath, target) }

    private fun readAll(): List<PresenceRecord> {
        return filePath.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(PresenceRecord::fromLine)
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

    fun enqueue(accountIds: Set<Int>, message: String): Int {
        if (accountIds.isEmpty()) return 0
        return lock.write {
            val records = readAll().toMutableList()
            var nextId = (records.maxOfOrNull { it.id } ?: 0L) + 1L
            val now = Instant.now().toString()
            accountIds.sorted().forEach { accountId ->
                records += NotificationRecord(
                    id = nextId++,
                    accountId = accountId,
                    message = message,
                    createdAt = now,
                    deliveredAt = null,
                )
            }
            writeAll(records)
            accountIds.size
        }
    }

    fun deleteByAccount(accountId: Int) {
        lock.write { writeAll(readAll().filterNot { it.accountId == accountId }) }
    }

    fun copyTo(target: Path) = lock.read { copyFile(filePath, target) }

    private fun readAll(): List<NotificationRecord> {
        return filePath.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(NotificationRecord::fromLine)
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

    fun activeFor(hwidHash: String): Boolean {
        return !disabled && !isExpired() && this.hwidHash != null && this.hwidHash.equals(hwidHash, ignoreCase = true)
    }

    fun toLine(): String {
        return listOf(licenseKey, role, hwidHash ?: "", createdAt, boundAt ?: "", expiresAt ?: "", disabled.toString()).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): LicenseRecord? {
            val parts = line.split('\t')
            if (parts.size < 7) return null
            return LicenseRecord(parts[0], parts[1], parts[2].ifBlank { null }, parts[3], parts[4].ifBlank { null }, parts[5].ifBlank { null }, parts[6].toBooleanStrictOrNull() ?: false)
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
) {
    fun toLine(): String {
        return listOf(id.toString(), accountKey, hwidHash, displayName ?: "", contact ?: "", createdAt, disabled.toString(), cloudUploadBanned.toString()).joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): AccountRecord? {
            val parts = line.split('\t')
            if (parts.size < 6) return null
            return AccountRecord(
                parts[0].toIntOrNull() ?: return null,
                parts[1],
                parts[2],
                parts[3].ifBlank { null },
                if (parts.size >= 7) parts[4].ifBlank { null } else null,
                if (parts.size >= 7) parts[5] else parts[4],
                (if (parts.size >= 7) parts[6] else parts[5]).toBooleanStrictOrNull() ?: false,
                if (parts.size >= 8) parts[7].toBooleanStrictOrNull() ?: false else false,
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
            return AccountRoleLinkRecord(parts[0].toIntOrNull() ?: return null, parts[1], parts[2], parts[3].toBooleanStrictOrNull() ?: false)
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
) {
    fun payloadBytes(): ByteArray = runCatching { Base64.getDecoder().decode(payloadBase64) }.getOrElse { ByteArray(0) }

    fun toLine(): String {
        return listOf(configKey, ownerAccountId?.toString() ?: "", ownerHwidHash, ownerLicenseKey ?: "", name, createdAt, updatedAt, disabled.toString(), payloadBase64, configType ?: "").joinToString("\t") { it.tsv() }
    }

    companion object {
        fun fromLine(line: String): CloudConfigRecord? {
            val parts = line.split('\t')
            if (parts.size >= 9) {
                return CloudConfigRecord(parts[0], parts[1].toIntOrNull(), parts[2], parts[3].ifBlank { null }, parts[4], parts[5], parts[6], parts[7].toBooleanStrictOrNull() ?: false, parts[8], parts.getOrNull(9)?.ifBlank { null })
            }
            if (parts.size >= 8) {
                return CloudConfigRecord(parts[0], null, parts[1], parts[2].ifBlank { null }, parts[3], parts[4], parts[5], parts[6].toBooleanStrictOrNull() ?: false, parts[7], parts.getOrNull(8)?.ifBlank { null })
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
) {
    fun toLine(): String {
        return listOf(role, cloudLimit.toString(), saveCooldownSeconds.toString(), loadCooldownSeconds.toString(), textGradient ?: "", nickGradient ?: "", iconFile ?: "", gifLimitBytes?.toString() ?: "", gifMaxConfigs?.toString() ?: "", canChangeGradient.toString(), canResetHwid.toString(), hwidResetCount.toString())
            .joinToString("\t") { it.tsv() }
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
            )
        }
    }
}

private data class RoleRuntimeSettings(
    val cloudLimit: Int,
    val saveCooldownSeconds: Int,
    val loadCooldownSeconds: Int,
)

private data class PresenceRecord(
    val accountId: Int,
    val hwidHash: String,
    val displayName: String?,
    val online: Boolean,
    val onlineSince: String,
    val lastSeenAt: String,
    val offlineAt: String?,
) {
    fun isOnline(now: Instant = Instant.now()): Boolean {
        if (!online) return false
        val lastSeen = runCatching { Instant.parse(lastSeenAt) }.getOrNull() ?: return false
        return !lastSeen.plusSeconds(ONLINE_TTL_SECONDS).isBefore(now)
    }

    fun toLine(): String {
        return listOf(accountId.toString(), hwidHash, displayName ?: "", online.toString(), onlineSince, lastSeenAt, offlineAt ?: "")
            .joinToString("\t") { it.tsv() }
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
        return listOf(id.toString(), accountId.toString(), message, createdAt, deliveredAt ?: "").joinToString("\t") { it.tsv() }
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

private data class Stats(
    val accounts: Int,
    val activeAccounts: Int,
    val licenses: Int,
    val boundLicenses: Int,
    val cloudConfigs: Int,
) {
    companion object {
        fun from(accounts: List<AccountRecord>, licenses: List<LicenseRecord>, cloudConfigs: List<CloudConfigRecord>): Stats {
            return Stats(
                accounts = accounts.size,
                activeAccounts = accounts.count { !it.disabled },
                licenses = licenses.size,
                boundLicenses = licenses.count { it.hwidHash != null },
                cloudConfigs = cloudConfigs.size,
            )
        }
    }
}

private class BasicAuth(private val username: String, private val password: String) {
    private val expected = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

    fun authorized(exchange: HttpExchange): Boolean {
        val provided = exchange.requestHeaders.getFirst("Authorization") ?: return false
        return MessageDigest.isEqual(provided.toByteArray(StandardCharsets.UTF_8), expected.toByteArray(StandardCharsets.UTF_8))
    }
}

private fun unauthorized(exchange: HttpExchange) {
    exchange.responseHeaders.set("WWW-Authenticate", """Basic realm="Hypnosia Admin"""")
    text(exchange, 401, "Unauthorized")
}

private fun page(title: String, body: StringBuilder.() -> Unit): String {
    val content = buildString(body)
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${esc(title)}</title>
          <style>
            :root { color-scheme: dark; --bg:#080808; --panel:#111; --line:#272727; --text:#f4f4f4; --muted:#9a9aa2; --accent:#72e0ff; --danger:#ff6969; }
            * { box-sizing: border-box; }
            body { margin:0; padding:28px; background:var(--bg); color:var(--text); font:15px/1.45 system-ui, Segoe UI, Arial, sans-serif; }
            h1,h2 { margin:0; } h1 { font-size:30px; } h2 { font-size:18px; margin-bottom:14px; }
            p { color:var(--muted); margin:6px 0 0; }
            button,input,select { border:1px solid var(--line); border-radius:8px; background:#0b0b0b; color:var(--text); padding:9px 11px; }
            button,.button { cursor:pointer; background:#181818; font-weight:650; }
            button:hover,.button:hover { border-color:var(--accent); }
            code { font-family:ui-monospace, Cascadia Mono, Consolas, monospace; }
            a { color:var(--accent); }
            .hero,.card,.stats>div { border:1px solid var(--line); border-radius:12px; background:var(--panel); }
            .hero { display:flex; justify-content:space-between; gap:16px; align-items:center; padding:20px; margin-bottom:18px; }
            .hero-actions { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
            .card { padding:18px; margin-top:18px; overflow-x:auto; }
            .stats { display:grid; grid-template-columns:repeat(6,minmax(120px,1fr)); gap:12px; }
            .stats>div { padding:14px; } .stats b { display:block; color:var(--muted); font-size:12px; text-transform:uppercase; } .stats span { display:block; font-size:24px; font-weight:750; margin-top:4px; }
            .create-grid { display:grid; grid-template-columns:minmax(180px,1fr) minmax(180px,1fr) minmax(180px,1fr) auto; gap:10px; align-items:end; margin-bottom:16px; }
            .search-grid { display:grid; grid-template-columns:minmax(240px,1fr) auto auto; gap:10px; align-items:center; margin-bottom:16px; }
            .notify-grid { display:grid; grid-template-columns:minmax(280px,1fr) auto; gap:10px; align-items:center; margin-top:12px; }
            .field label { display:block; color:var(--muted); font-size:12px; margin-bottom:5px; } .field input,.field select { width:100%; }
            .notice { padding:12px 14px; border-radius:8px; margin:12px 0; border:1px solid var(--line); } .notice.ok { color:#97f2a8; } .notice.err { color:var(--danger); }
            .table { min-width:1100px; }
            .row { display:grid; gap:10px; align-items:center; padding:10px 0; border-top:1px solid var(--line); }
            .account-row { grid-template-columns:60px 140px 160px 90px 180px 120px 110px 130px 1fr; }
            .role-row { grid-template-columns:280px 90px 90px 100px 130px 100px 1fr; }
            .cloud-row { grid-template-columns:100px 220px 180px 90px 120px 1fr; }
            .detail-row { grid-template-columns:280px 140px 100px 140px 1fr; }
            .row.head { color:var(--muted); font-size:12px; text-transform:uppercase; border-top:0; }
            .actions { display:flex; gap:8px; align-items:center; flex-wrap:wrap; } .actions form { display:flex; gap:8px; align-items:center; } .actions input { width:130px; }
            .detail-actions { margin-top:14px; }
            .detail-grid { display:grid; grid-template-columns:minmax(320px,1fr) minmax(280px,420px); gap:18px; }
            .detail-form { display:grid; gap:10px; }
            .detail-form label { display:grid; gap:5px; color:var(--muted); }
            .button { display:inline-flex; align-items:center; text-decoration:none; border:1px solid var(--line); border-radius:8px; color:var(--text); padding:9px 11px; }
            .pill { display:inline-flex; width:max-content; padding:4px 8px; border-radius:999px; border:1px solid var(--line); color:var(--muted); }
            .pill.bound { color:#97f2a8; } .pill.not-bound { color:#ffd37a; } .pill.disabled,.pill.expired,.danger { color:var(--danger); } .muted { color:var(--muted); }
            .logs { max-height:70vh; overflow:auto; white-space:pre-wrap; color:#dcdcdc; margin:0; font:12px/1.45 ui-monospace, Cascadia Mono, Consolas, monospace; }
          </style>
        </head>
        <body>$content</body>
        </html>
    """.trimIndent()
}

private fun StringBuilder.stat(label: String, value: Int) {
    append("<div><b>${esc(label)}</b><span>$value</span></div>")
}

private fun StringBuilder.input(name: String, label: String, value: String, placeholder: String) {
    append("<div class=\"field\"><label>${esc(label)}</label><input name=\"${esc(name)}\" value=\"${esc(value)}\" placeholder=\"${esc(placeholder)}\"></div>")
}

private fun StringBuilder.selectRole() {
    append("<div class=\"field\"><label>Role</label><select name=\"role\">")
    defaultRoles.forEach { append("<option>$it</option>") }
    append("</select></div>")
}

private fun StringBuilder.hidden(name: String, value: String) {
    append("<input type=\"hidden\" name=\"${esc(name)}\" value=\"${esc(value)}\">")
}

private fun StringBuilder.postButton(action: String, key: String, label: String, danger: Boolean = false) {
    append("<form method=\"post\" action=\"${esc(action)}\">")
    hidden("key", key)
    append("<button${if (danger) " class=\"danger\"" else ""}>${esc(label)}</button>")
    append("</form>")
}

private fun HttpExchange.form(): Map<String, String> {
    val raw = requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    if (raw.isBlank()) return emptyMap()
    return raw.split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=')
        if (name.isBlank()) return@mapNotNull null
        decode(name) to decode(pair.substringAfter('=', ""))
    }.toMap()
}

private data class MultipartPart(
    val name: String,
    val filename: String?,
    val bytes: ByteArray,
) {
    fun text(): String = bytes.toString(StandardCharsets.UTF_8).trim()
}

private fun HttpExchange.multipartForm(): Map<String, MultipartPart> {
    val contentType = requestHeaders.getFirst("Content-Type").orEmpty()
    val boundary = contentType
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("boundary=") }
        ?.substringAfter('=')
        ?.trim('"')
        ?: throw IllegalArgumentException("Missing multipart boundary")

    val body = requestBody.readAllBytes().toString(StandardCharsets.ISO_8859_1)
    val result = linkedMapOf<String, MultipartPart>()
    body.split("--$boundary").forEach { rawPart ->
        val part = rawPart.trimStart('\r', '\n')
        if (part.isBlank() || part.startsWith("--")) return@forEach
        val headerEnd = part.indexOf("\r\n\r\n")
        if (headerEnd < 0) return@forEach

        val headers = part.substring(0, headerEnd)
        val disposition = headers.lineSequence().firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) } ?: return@forEach
        val name = Regex("""name="([^"]+)"""").find(disposition)?.groupValues?.getOrNull(1) ?: return@forEach
        val filename = Regex("""filename="([^"]*)"""").find(disposition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        var value = part.substring(headerEnd + 4)
        if (value.endsWith("\r\n")) value = value.dropLast(2)
        result[name] = MultipartPart(name, filename, value.toByteArray(StandardCharsets.ISO_8859_1))
    }
    return result
}

private fun Map<String, String>.licenseKey(): String {
    val key = get("key")?.trim()?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing key")
    require(licenseRegex.matches(key)) { "Invalid key" }
    return key
}

private fun Map<String, String>.accountId(): Int {
    return get("id")?.toIntOrNull() ?: get("key")?.toIntOrNull() ?: throw IllegalArgumentException("Missing account id")
}

private fun Map<String, String>.cloudConfigKey(): String {
    val key = get("key")?.trim()?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing config key")
    require(cloudConfigKeyRegex.matches(key)) { "Invalid config key" }
    return key
}

private fun queryLicenseKey(exchange: HttpExchange): String {
    val key = query(exchange, "key")?.trim()?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing key")
    require(licenseRegex.matches(key)) { "Invalid key" }
    return key
}

private fun queryCloudConfigKey(exchange: HttpExchange): String {
    val key = query(exchange, "key")?.trim()?.uppercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing config key")
    require(cloudConfigKeyRegex.matches(key)) { "Invalid config key" }
    return key
}

private fun queryAccountId(exchange: HttpExchange): Int {
    return query(exchange, "id")?.toIntOrNull() ?: throw IllegalArgumentException("Missing account id")
}

private fun query(exchange: HttpExchange, name: String): String? {
    return exchange.requestURI.rawQuery
        ?.split('&')
        ?.mapNotNull {
            val key = it.substringBefore('=')
            val value = it.substringAfter('=', "")
            if (decode(key) == name) decode(value) else null
        }
        ?.firstOrNull()
}

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

private fun rolePriority(role: String): Int {
    return when (role) {
        "OWNER" -> 50
        "ADMIN" -> 40
        "QA" -> 30
        "SPONSOR" -> 20
        "USER" -> 10
        else -> 25
    }
}

private fun roleSettingsForRoles(roles: Collection<String>, storage: RoleSettingsStorage): RoleRuntimeSettings {
    val settings = roles.map { role -> storage.find(role) ?: defaultRoleSettings(role) }
    return RoleRuntimeSettings(
        cloudLimit = settings.maxOfOrNull { it.cloudLimit } ?: DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT,
        saveCooldownSeconds = settings.minOfOrNull { it.saveCooldownSeconds } ?: DEFAULT_USER_CLOUD_COOLDOWN_SECONDS,
        loadCooldownSeconds = settings.minOfOrNull { it.loadCooldownSeconds } ?: DEFAULT_USER_CLOUD_COOLDOWN_SECONDS,
    )
}

private fun defaultRoleSettings(role: String): RoleSettingsRecord {
    return when (role) {
        "OWNER" -> RoleSettingsRecord(role, STAFF_CLOUD_CONFIGS_PER_ACCOUNT, 0, 0, "linear-gradient(90deg, #9932CC, #DA70D6)")
        "ADMIN" -> RoleSettingsRecord(role, STAFF_CLOUD_CONFIGS_PER_ACCOUNT, 0, 0, "linear-gradient(90deg, #FF4444, #FF6B6B)")
        "SPONSOR" -> RoleSettingsRecord(role, 10, 5, 5, "linear-gradient(90deg, #32CD32, #00FF7F)", gifLimitBytes = 5L * 1024 * 1024, gifMaxConfigs = 3)
        "SPONSOR_PLUS" -> RoleSettingsRecord(role, 25, 5, 5, "linear-gradient(90deg, #4169E1, #00BFFF)", canChangeGradient = true, gifLimitBytes = 10L * 1024 * 1024, gifMaxConfigs = 6)
        "SPONSOR_PLUS_PLUS" -> RoleSettingsRecord(role, 70, 0, 0, "linear-gradient(90deg, #FFD700, #FFA500)", canChangeGradient = true, canResetHwid = true, hwidResetCount = -1, gifLimitBytes = 30L * 1024 * 1024, gifMaxConfigs = 20)
        "QA" -> RoleSettingsRecord(role, DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, "linear-gradient(90deg, #00CED1, #20B2AA)")
        else -> RoleSettingsRecord(role, DEFAULT_CLOUD_CONFIGS_PER_ACCOUNT, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, DEFAULT_USER_CLOUD_COOLDOWN_SECONDS, "linear-gradient(90deg, #888888, #BBBBBB)")
    }
}

private fun List<AccountRecord>.filterAccounts(search: String): List<AccountRecord> {
    val query = search.trim().lowercase(Locale.ROOT)
    if (query.isBlank()) return this
    return filter { account ->
        account.id.toString() == query.removePrefix("#") ||
            account.displayName.orEmpty().lowercase(Locale.ROOT).contains(query) ||
            account.contact.orEmpty().lowercase(Locale.ROOT).contains(query) ||
            account.hwidHash.lowercase(Locale.ROOT).contains(query)
    }
}

private fun List<AccountRoleLinkRecord>.linkedAccountIds(licenseKey: String): List<Int> {
    return filter { it.licenseKey == licenseKey && !it.disabled }
        .map { it.accountId }
        .distinct()
        .sorted()
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

private fun List<Int>.accountLinksHtml(): String {
    if (isEmpty()) return "-"
    return joinToString(", ") { id -> """<a href="/account?id=$id">#$id</a>""" }
}

private fun String.uppercaseRole(): String? {
    val role = trim().uppercase(Locale.ROOT)
    return role.takeIf { roleRegex.matches(it) }
}

private fun Map<String, String>.roleFromForm(): String {
    val raw = get("customRole")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: get("role")
        ?: throw IllegalArgumentException("Invalid role")
    return raw.uppercaseRole() ?: throw IllegalArgumentException("Invalid role")
}

private fun Map<String, String>.roleSettings(role: String): RoleSettingsRecord {
    val defaults = defaultRoleSettings(role)
    return RoleSettingsRecord(
        role = role,
        cloudLimit = intField("cloudLimit", defaults.cloudLimit).coerceIn(0, 1000),
        saveCooldownSeconds = intField("saveCooldown", defaults.saveCooldownSeconds).coerceIn(0, 3600),
        loadCooldownSeconds = intField("loadCooldown", defaults.loadCooldownSeconds).coerceIn(0, 3600),
        textGradient = get("textGradient")?.trim()?.takeIf { it.isNotBlank() } ?: defaults.textGradient,
        nickGradient = get("nickGradient")?.trim()?.takeIf { it.isNotBlank() } ?: defaults.nickGradient,
        iconFile = get("iconFile")?.trim()?.takeIf { it.isNotBlank() } ?: defaults.iconFile,
        gifLimitBytes = get("gifLimitBytes")?.trim()?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: defaults.gifLimitBytes,
        gifMaxConfigs = get("gifMaxConfigs")?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: defaults.gifMaxConfigs,
        canChangeGradient = get("canChangeGradient")?.toBooleanStrictOrNull() ?: defaults.canChangeGradient,
        canResetHwid = get("canResetHwid")?.toBooleanStrictOrNull() ?: defaults.canResetHwid,
        hwidResetCount = get("hwidResetCount")?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: defaults.hwidResetCount,
    )
}

private fun Map<String, String>.intField(name: String, default: Int): Int {
    val raw = get(name)?.trim()
    if (raw.isNullOrBlank()) return default
    return raw.toIntOrNull() ?: throw IllegalArgumentException("$name must be a number")
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

private fun atomicWrite(file: Path, content: String) {
    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
    tmp.writeText(content)
    runCatching {
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse {
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun copyFile(source: Path, target: Path) {
    target.parent?.createDirectories()
    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}

private fun html(exchange: HttpExchange, body: String) {
    exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    write(exchange, 200, body)
}

private fun text(exchange: HttpExchange, code: Int, body: String) {
    exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
    write(exchange, code, body)
}

private fun json(exchange: HttpExchange, code: Int, body: String) {
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    write(exchange, code, body)
}

private fun download(exchange: HttpExchange, filename: String, contentType: String, bytes: ByteArray) {
    exchange.responseHeaders.set("Content-Type", contentType)
    exchange.responseHeaders.set("Content-Disposition", """attachment; filename="$filename"""")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun redirect(exchange: HttpExchange, location: String) {
    exchange.responseHeaders.set("Location", location)
    exchange.sendResponseHeaders(303, -1)
    exchange.close()
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

private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun url(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun esc(value: String): String {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

private fun String.tsv(): String = replace("\t", " ").replace("\n", " ").replace("\r", " ")

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
