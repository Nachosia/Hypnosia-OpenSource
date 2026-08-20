package dev.hypnosia.license

import dev.hypnosia.crypto.FileEncryption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

data class LicenseConfig(
    val licenseKey: String?,
) {
    companion object {
        private const val CONFIG_FILE_NAME = "license.properties"
        private val licenseRegex = Regex("^[A-Za-z0-9]{32}$")

        fun loadOrCreate(): LicenseConfig {
            val configFile = HypnosiaPaths.rootFile(CONFIG_FILE_NAME)
            val keyBytes = FileEncryption.deriveKey(HardwareFingerprint.currentHash64())

            if (!configFile.exists()) {
                configFile.parent.createDirectories()
                val defaults = Properties()
                defaults["license.key"] = ""
                configFile.outputStream().use { output ->
                    defaults.store(
                        output,
                        "Hypnosia license config. Encrypted at rest.",
                    )
                }
                return LicenseConfig(licenseKey = null)
            }

            val properties = Properties()
            configFile.inputStream().use(properties::load)
            var rawKey = properties.getProperty("license.key")?.trim() ?: ""

            // Migrate plaintext key to encrypted on first read
            if (rawKey.isNotBlank() && licenseRegex.matches(rawKey)) {
                val encrypted = FileEncryption.encrypt(rawKey.uppercase(), keyBytes)
                properties["license.key"] = encrypted
                configFile.outputStream().use { output ->
                    properties.store(output, "Hypnosia license config. Encrypted at rest.")
                }
                rawKey = encrypted
            }

            val key = if (rawKey.isNotBlank() && !licenseRegex.matches(rawKey)) {
                FileEncryption.decrypt(rawKey, keyBytes)
                    ?.takeIf { licenseRegex.matches(it) }
                    ?.uppercase()
            } else {
                rawKey.takeIf { licenseRegex.matches(it) }?.uppercase()
            }

            return LicenseConfig(licenseKey = key)
        }

        fun saveLicenseKey(licenseKey: String) {
            require(licenseRegex.matches(licenseKey)) { "Invalid license key" }
            val configFile = HypnosiaPaths.rootFile(CONFIG_FILE_NAME)
            configFile.parent.createDirectories()
            val keyBytes = FileEncryption.deriveKey(HardwareFingerprint.currentHash64())

            val properties = Properties()
            properties["license.key"] = FileEncryption.encrypt(licenseKey.uppercase(), keyBytes)
            configFile.outputStream().use { output ->
                properties.store(
                    output,
                    "Hypnosia license config. Encrypted at rest.",
                )
            }
        }
    }
}
