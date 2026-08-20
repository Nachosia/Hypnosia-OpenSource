package dev.hypnosia.visual.world.particles

enum class WorldParticleMode(val displayName: String) {
    NEON("Neon"),
    STARS("Stars"),
    DUST("Dust"),
    SNOW_ASH("Snow / Ash"),
    MAGIC("Magic");

    companion object {
        fun entriesList() = values().asList()
        fun byOrdinal(ordinal: Int): WorldParticleMode =
            values().getOrNull(ordinal) ?: NEON
    }

    fun next(): WorldParticleMode = values()[(ordinal + 1) % values().size]
}
