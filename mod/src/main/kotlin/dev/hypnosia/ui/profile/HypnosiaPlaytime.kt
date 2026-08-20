package dev.hypnosia.ui.profile

import dev.hypnosia.config.HypnosiaClientSettings
import net.minecraft.client.MinecraftClient
import java.time.LocalDate
import java.time.YearMonth

object HypnosiaPlaytime {
    private const val KEY_PREFIX = "playtime."
    private const val SAVE_INTERVAL_TICKS = 20 * 20

    private val lock = Any()
    private val dailySeconds = mutableMapOf<LocalDate, Long>()

    private var loaded = false
    private var launchRecorded = false
    private var totalSeconds = 0L
    private var ticksThisSecond = 0
    private var ticksSinceSave = 0
    private var opensDate: LocalDate = LocalDate.now()
    private var opensToday = 0

    data class Snapshot(
        val totalSeconds: Long,
        val opensToday: Int,
        val streakDays: Int,
        val month: YearMonth,
        val dailySeconds: Map<LocalDate, Long>,
    )

    fun recordLaunch() {
        synchronized(lock) {
            loadIfNeeded()
            if (launchRecorded) {
                return
            }

            val today = LocalDate.now()
            if (opensDate != today) {
                opensDate = today
                opensToday = 0
            }
            opensToday += 1
            launchRecorded = true
            saveLocked()
        }
    }

    fun tick(client: MinecraftClient) {
        synchronized(lock) {
            loadIfNeeded()
            if (client.player == null || client.world == null) {
                return
            }

            ticksThisSecond += 1
            ticksSinceSave += 1
            if (ticksThisSecond >= 20) {
                ticksThisSecond = 0
                val today = LocalDate.now()
                totalSeconds += 1
                dailySeconds[today] = (dailySeconds[today] ?: 0L) + 1L
            }

            if (ticksSinceSave >= SAVE_INTERVAL_TICKS) {
                ticksSinceSave = 0
                saveLocked()
            }
        }
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            loadIfNeeded()
            val today = LocalDate.now()
            if (opensDate != today) {
                opensDate = today
                opensToday = 0
            }
            return Snapshot(
                totalSeconds = totalSeconds,
                opensToday = opensToday,
                streakDays = streakDays(today),
                month = YearMonth.from(today),
                dailySeconds = dailySeconds.toMap(),
            )
        }
    }

    private fun streakDays(today: LocalDate): Int {
        var cursor = today
        var streak = 0
        while ((dailySeconds[cursor] ?: 0L) > 0L) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun loadIfNeeded() {
        if (loaded) {
            return
        }

        loaded = true
        runCatching {
            totalSeconds = HypnosiaClientSettings.long(KEY_PREFIX + "total.seconds", 0L)
            opensDate = HypnosiaClientSettings.nullableString(KEY_PREFIX + "opens.date")?.let(LocalDate::parse) ?: LocalDate.now()
            opensToday = HypnosiaClientSettings.int(KEY_PREFIX + "opens.today", 0)
            HypnosiaClientSettings.keys(KEY_PREFIX + "day.")
                .asSequence()
                .filter { it.endsWith(".seconds") }
                .forEach { key ->
                    val dateText = key.removePrefix(KEY_PREFIX + "day.").removeSuffix(".seconds")
                    val seconds = HypnosiaClientSettings.long(key, 0L)
                    runCatching { LocalDate.parse(dateText) }.getOrNull()?.let { date ->
                        if (seconds > 0L) {
                            dailySeconds[date] = seconds
                        }
                    }
                }
        }
    }

    private fun saveLocked() {
        val values = linkedMapOf<String, String>()
        values[KEY_PREFIX + "total.seconds"] = totalSeconds.toString()
        values[KEY_PREFIX + "opens.date"] = opensDate.toString()
        values[KEY_PREFIX + "opens.today"] = opensToday.toString()
        dailySeconds.entries
            .sortedBy { it.key }
            .takeLast(420)
            .forEach { (date, seconds) ->
                values[KEY_PREFIX + "day.$date.seconds"] = seconds.toString()
            }
        HypnosiaClientSettings.setAll(values)
    }
}
