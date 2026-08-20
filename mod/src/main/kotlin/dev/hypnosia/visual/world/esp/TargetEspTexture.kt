package dev.hypnosia.visual.world.esp

import net.minecraft.util.Identifier

enum class TargetEspTexture(val displayName: String, val textureId: Identifier) {
    PLANET("Planet", id("planet")),
    ROD("Rod", id("rod")),
    SQAD("Sqad", id("sqad")),
    TARGET("Target", id("target")),
    DO("Do", id("do")),
    ENEGRW("Energy", id("enegrw"));

    companion object {
        fun entriesList() = values().asList()
        fun byOrdinal(ordinal: Int): TargetEspTexture =
            values().getOrNull(ordinal) ?: PLANET
    }

    fun next(): TargetEspTexture = values()[(ordinal + 1) % values().size]
}

private fun id(name: String) = Identifier.of("hypnosia", "textures/esp/$name.png")
