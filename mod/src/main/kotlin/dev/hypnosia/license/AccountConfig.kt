package dev.hypnosia.license

import dev.hypnosia.crypto.FileEncryption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

data class AccountConfig(
    val accountKey: String?,
    val accountId: Int?,
) {
    companion object {
        private const val CONFIG_FILE_NAME = "account.properties"
        private val accountKeyRegex = Regex("^[A-Za-z0-9]{32}$")

        fun loadOrCreate(): AccountConfig {
            val configFile = HypnosiaPaths.rootFile(CONFIG_FILE_NAME)
            val keyBytes = FileEncryption.deriveKey(HardwareFingerprint.currentHash64())

            if (!configFile.exists()) {
                configFile.parent.createDirectories()
                val defaults = Properties()
                defaults["account.key"] = ""
                defaults["account.id"] = ""
                configFile.outputStream().use { output ->
                    defaults.store(output, "Hypnosia account config. Encrypted at rest.")
                }
                return AccountConfig(accountKey = null, accountId = null)
            }

            val properties = Properties()
            configFile.inputStream().use(properties::load)
            var rawKey = properties.getProperty("account.key")?.trim() ?: ""

            // Migrate plaintext key to encrypted on first read
            if (rawKey.isNotBlank() && accountKeyRegex.matches(rawKey)) {
                val encrypted = FileEncryption.encrypt(rawKey.uppercase(), keyBytes)
                properties["account.key"] = encrypted
                configFile.outputStream().use { output ->
                    properties.store(output, "Hypnosia account config. Encrypted at rest.")
                }
                rawKey = encrypted
            }

            val key = if (rawKey.isNotBlank() && !accountKeyRegex.matches(rawKey)) {
                FileEncryption.decrypt(rawKey, keyBytes)
                    ?.takeIf { accountKeyRegex.matches(it) }
                    ?.uppercase()
            } else {
                rawKey.takeIf { accountKeyRegex.matches(it) }?.uppercase()
            }

            val id = properties.getProperty("account.id")
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

            return AccountConfig(accountKey = key, accountId = id)
        }

        fun save(accountKey: String, accountId: Int) {
            require(accountKeyRegex.matches(accountKey)) { "Invalid account key" }
            require(accountId > 0) { "Invalid account id" }

            val configFile = HypnosiaPaths.rootFile(CONFIG_FILE_NAME)
            configFile.parent.createDirectories()
            val keyBytes = FileEncryption.deriveKey(HardwareFingerprint.currentHash64())

            val properties = Properties()
            properties["account.key"] = FileEncryption.encrypt(accountKey.uppercase(), keyBytes)
            properties["account.id"] = accountId.toString()
            configFile.outputStream().use { output ->
                properties.store(output, "Hypnosia account config. Encrypted at rest.")
            }
        }
    }
}
