package dev.hypnosia.license

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.hypnosia.HypnosiaClient
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

object LicenseManager {
    // Public endpoint only. Never put admin tokens, database credentials, VPS
    // passwords, or signing private keys in the client mod.
    private const val DEFAULT_API_URL = "http://127.0.0.1:8080"
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build()

    private val stateRef = AtomicReference<LicenseState>(LicenseState.NotChecked)
    private val sessionFutureRef = AtomicReference<CompletableFuture<LicenseState>?>(null)
    private val gson = Gson()

    val state: LicenseState
        get() = stateRef.get()

    val sessionRole: LicenseRole
        get() = (state as? LicenseState.Valid)?.role ?: LicenseRole.USER

    fun startSessionAsync(): CompletableFuture<LicenseState> {
        sessionFutureRef.get()?.let { return it }

        val future = checkLicenseOnceAsync()
        return if (sessionFutureRef.compareAndSet(null, future)) {
            future
        } else {
            sessionFutureRef.get() ?: future
        }
    }

    private fun checkLicenseOnceAsync(): CompletableFuture<LicenseState> {
        val config = LicenseConfig.loadOrCreate()
        val licenseKey = config.licenseKey

        if (licenseKey == null) {
            return completed(LicenseState.NoKey)
        }

        val apiUrl = DEFAULT_API_URL.takeIf { it.isNotBlank() }
        if (apiUrl == null) {
            return completed(LicenseState.NoEndpoint)
        }
        val apiUri = secureApiUri(apiUrl) ?: return completed(LicenseState.InsecureEndpoint)

        val requestBody = licenseRequestJson(
            licenseKey = licenseKey,
            hwid = HardwareFingerprint.currentKey32(),
            hwidHash = HardwareFingerprint.currentHash64(),
            modVersion = modVersion(),
        )

        val request = HttpRequest.newBuilder(apiUri.resolve("/api/license/check"))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response -> parseResponse(response.statusCode(), response.body()) }
            .exceptionally { LicenseState.NetworkError(it.message ?: "Network error") }
            .thenApply { result ->
                stateRef.set(result)
                result
            }
    }

    private fun completed(state: LicenseState): CompletableFuture<LicenseState> {
        stateRef.set(state)
        return CompletableFuture.completedFuture(state)
    }

    private fun parseResponse(statusCode: Int, body: String): LicenseState {
        if (statusCode !in 200..299) {
            return LicenseState.ServerRejected("HTTP_$statusCode")
        }

        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val valid = json.get("valid")?.asBoolean ?: return LicenseState.InvalidResponse
            val status = json.get("status")?.asString
            if (!valid) {
                return LicenseState.ServerRejected(status ?: "INVALID")
            }
            val role = LicenseRole.parse(json.get("role")?.asString)
                ?: return LicenseState.InvalidResponse
            LicenseState.Valid(role = role, status = status ?: "OK")
        } catch (_: Exception) {
            LicenseState.InvalidResponse
        }
    }

    private fun secureApiUri(apiUrl: String): URI? {
        val uri = runCatching { URI.create(apiUrl.trimEnd('/') + "/") }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val localDev = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && localDev)) {
            return null
        }
        return uri
    }

    private fun licenseRequestJson(
        licenseKey: String,
        hwid: String,
        hwidHash: String,
        modVersion: String,
    ): String {
        return buildString {
            append('{')
            appendJsonField("license", licenseKey)
            append(',')
            appendJsonField("hwid", hwid)
            append(',')
            appendJsonField("hwidHash", hwidHash)
            append(',')
            appendJsonField("modVersion", modVersion)
            append('}')
        }
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append('"').append(escapeJson(name)).append('"')
        append(':')
        append('"').append(escapeJson(value)).append('"')
    }

    private fun stringValue(json: String, name: String): String? {
        val pattern = Regex(""""${Regex.escape(name)}"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun modVersion(): String {
        return FabricLoader.getInstance()
            .getModContainer(HypnosiaClient.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
    }
}

sealed class LicenseState {
    data object NotChecked : LicenseState()
    data object NoKey : LicenseState()
    data object NoEndpoint : LicenseState()
    data object InsecureEndpoint : LicenseState()
    data object InvalidResponse : LicenseState()
    data class Valid(val role: LicenseRole, val status: String) : LicenseState()
    data class ServerRejected(val reason: String) : LicenseState()
    data class NetworkError(val message: String) : LicenseState()
}
