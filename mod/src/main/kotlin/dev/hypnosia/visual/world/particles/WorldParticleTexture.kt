package dev.hypnosia.visual.world.particles

import net.minecraft.util.Identifier

enum class WorldParticleTexture(val displayName: String, val textureId: Identifier) {
    CROSS("Cross", id("kresti")),
    MOON("Moon", id("moon")),
    THUNDER("Thunder", id("thender")),
    SQUARES("Squares", id("kvadratiki")),
    WATER("Water", id("water")),
    PULSAR("Pulsar", id("pizler")),
    SUN("Sun", id("sun")),
    LEAF("Leaf", id("liefs")),
    PLANET("Planet", id("planet")),
    BULB("Bulb", id("lampocka")),
    HEART("Heart", id("hert")),
    DIAMOND("Diamond", id("romb"));

    companion object {
        fun entriesList() = values().asList()
        fun byOrdinal(ordinal: Int): WorldParticleTexture =
            values().getOrNull(ordinal) ?: CROSS
    }

    fun next(): WorldParticleTexture = values()[(ordinal + 1) % values().size]
}

/** Полный путь к PNG-текстуре: hypnosia:textures/particles/<name>.png */
private fun id(name: String) = Identifier.of("hypnosia", "textures/particles/$name.png")
