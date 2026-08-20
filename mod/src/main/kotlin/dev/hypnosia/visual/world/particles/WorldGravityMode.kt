package dev.hypnosia.visual.world.particles

enum class WorldGravityMode(val displayName: String) {
    FLOAT("Float"),
    FALL("Fall"),
    BOUNCE("Bounce");

    companion object {
        fun entriesList() = values().asList()
        fun byOrdinal(ordinal: Int): WorldGravityMode =
            values().getOrNull(ordinal) ?: FLOAT
    }
}
