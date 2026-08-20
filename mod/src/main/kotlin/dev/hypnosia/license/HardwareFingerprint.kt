package dev.hypnosia.license

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object HardwareFingerprint {
    private const val VERSION = "hypnosia-hwid-v1"
    private const val FALLBACK_INSTALL_ID_FILE = "install-id.dat"
    private val hexChars = "0123456789ABCDEF".toCharArray()

    /**
     * 32-character stable public identifier for license binding.
     *
     * This is not a raw hardware id. It is a SHA-256 hash truncated to 128 bits,
     * so the server can bind a license without storing the user's raw machine id.
     */
    fun currentKey32(): String {
        return sha256Hex(canonicalSource()).take(32)
    }

    /**
     * Full 64-character SHA-256 hash. Prefer storing this on the server if you do
     * not need the shorter 32-character display key.
     */
    fun currentHash64(): String {
        return sha256Hex(canonicalSource())
    }

    private fun canonicalSource(): String {
        val osName = System.getProperty("os.name", "unknown").lowercase(Locale.ROOT)
        val osArch = System.getProperty("os.arch", "unknown").lowercase(Locale.ROOT)
        val machineId = platformMachineId(osName) ?: fallbackInstallId()
        return listOf(VERSION, osName, osArch, machineId).joinToString("|")
    }

    private fun platformMachineId(osName: String): String? {
        return when {
            osName.contains("win") -> windowsMachineGuid()
            osName.contains("linux") -> linuxMachineId()
            osName.contains("mac") || osName.contains("darwin") -> macPlatformUuid()
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
    }

    private fun windowsMachineGuid(): String? {
        val output = runCommand(
            "reg",
            "query",
            "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
            "/v",
            "MachineGuid",
        ) ?: return null

        return output
            .lineSequence()
            .firstOrNull { it.contains("MachineGuid", ignoreCase = true) }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.lastOrNull()
    }

    private fun linuxMachineId(): String? {
        return readFirstExisting(
            Path.of("/etc/machine-id"),
            Path.of("/var/lib/dbus/machine-id"),
        )
    }

    private fun macPlatformUuid(): String? {
        val output = runCommand("ioreg", "-rd1", "-c", "IOPlatformExpertDevice") ?: return null
        val marker = "IOPlatformUUID"
        return output
            .lineSequence()
            .firstOrNull { it.contains(marker) }
            ?.substringAfter("=")
            ?.replace("\"", "")
            ?.trim()
    }

    private fun fallbackInstallId(): String {
        val file = HypnosiaPaths.rootFile(FALLBACK_INSTALL_ID_FILE)
        if (file.exists()) {
            return file.readText().trim().takeIf { it.length >= 32 } ?: createFallbackInstallId(file)
        }
        return createFallbackInstallId(file)
    }

    private fun createFallbackInstallId(file: Path): String {
        file.parent.createDirectories()
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val id = bytes.toHex()
        file.writeText(id)
        return id
    }

    private fun readFirstExisting(vararg paths: Path): String? {
        return paths
            .firstOrNull { Files.isRegularFile(it) }
            ?.readText(StandardCharsets.UTF_8)
            ?.trim()
    }

    private fun runCommand(vararg command: String): String? {
        return runCatching {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }

            if (process.exitValue() != 0) {
                return null
            }

            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    private fun sha256Hex(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .toHex()
    }

    private fun ByteArray.toHex(): String {
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            result[index * 2] = hexChars[value ushr 4]
            result[index * 2 + 1] = hexChars[value and 0x0F]
        }
        return String(result)
    }
}
