package dev.hypnosia.media

import dev.hypnosia.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object MediaBridge {

    val isAvailable: Boolean
    private val exeFile: File

    init {
        val result = try {
            val file = extractExe()
            // Проверяем что EXE работает
            val test = runExe(listOf(file.absolutePath, "read"))
            test != null
        } catch (e: Throwable) {
            println("[Hypnosia] MediaBridge failed to initialize: ${e.message}")
            false
        }
        isAvailable = result
        exeFile = if (result) extractExe() else File("")
    }

    private fun extractExe(): File {
        val osName = System.getProperty("os.name")?.lowercase() ?: ""
        if (!osName.contains("win")) {
            throw UnsupportedOperationException("MediaBridge is only supported on Windows")
        }

        val exeName = "hypnosia_media.exe"
        val tempDir = File(System.getProperty("java.io.tmpdir"), "hypnosia_native")
        tempDir.mkdirs()

        val exeFile = File(tempDir, exeName)
        if (!exeFile.exists()) {
            val resourcePath = "/native/windows/$exeName"
            val stream = MediaBridge::class.java.getResourceAsStream(resourcePath)
                ?: throw UnsatisfiedLinkError("Executable not found in resources: $resourcePath")

            stream.use { input ->
                exeFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        // Verify SHA-256 hash of the extracted binary
        if (BuildConfig.MEDIA_BRIDGE_EXE_HASH.isNotBlank()) {
            val actualHash = sha256Hex(exeFile)
            if (actualHash != BuildConfig.MEDIA_BRIDGE_EXE_HASH) {
                throw SecurityException(
                    "MediaBridge executable hash mismatch. Expected: ${BuildConfig.MEDIA_BRIDGE_EXE_HASH}, actual: $actualHash"
                )
            }
        }

        return exeFile
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } > 0) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun runExe(args: List<String>): String? {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            if (process.exitValue() != 0) {
                println("[Hypnosia] MediaBridge process error: exit=${process.exitValue()}, output=$output")
                return null
            }
            output
        } catch (e: Exception) {
            println("[Hypnosia] MediaBridge process failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private val gson = Gson()
    private var lastThumbPath: String? = null

    fun readCurrentMedia(): MediaInfo? {
        if (!isAvailable) return null

        val output = runExe(listOf(exeFile.absolutePath, "read")) ?: return null
        if (output == "{}") return null

        return try {
            val json = JsonParser.parseString(output).asJsonObject
            if (json.has("error")) {
                println("[Hypnosia] MediaBridge error: ${json["error"].asString}")
                return null
            }

            val tempPath = File(System.getProperty("java.io.tmpdir"), "hypnosia_thumb").absolutePath
            val hasThumb = saveThumbnail(tempPath)

            if (hasThumb && lastThumbPath != "$tempPath.jpg") {
                lastThumbPath?.let { File(it).deleteSilently() }
                lastThumbPath = "$tempPath.jpg"
            }

            MediaInfo(
                title = json["title"]?.asString ?: "",
                artist = json["artist"]?.asString ?: "",
                album = json["album"]?.asString ?: "",
                durationMs = json["durationMs"]?.asLong ?: 0,
                positionMs = json["positionMs"]?.asLong ?: 0,
                isPlaying = json["isPlaying"]?.asBoolean ?: false,
                thumbnailPath = if (hasThumb) "$tempPath.jpg" else null
            )
        } catch (e: Exception) {
            println("[Hypnosia] MediaBridge parse error: ${e.javaClass.simpleName}")
            null
        }
    }

    fun sendCommand(cmd: String) {
        if (!isAvailable) return
        runExe(listOf(exeFile.absolutePath, "cmd", cmd))
    }

    private fun saveThumbnail(path: String): Boolean {
        if (!isAvailable) return false
        val output = runExe(listOf(exeFile.absolutePath, "thumb", path)) ?: return false
        return output == "true"
    }
}

private fun File.deleteSilently() {
    try { delete() } catch (_: Exception) {}
}
