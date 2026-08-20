package dev.hypnosia.visual.world.jump

import net.minecraft.util.Identifier

enum class JumpCircleTexture(val displayName: String, val textureId: Identifier) {
    HYP("Hypnosia", id("hyp")),
    CRYG("Circle", id("cryg")),
    HOV("Wave", id("hov"));

    companion object {
        fun entriesList() = values().asList()
        fun byOrdinal(ordinal: Int): JumpCircleTexture =
            values().getOrNull(ordinal) ?: HYP
    }

    fun next(): JumpCircleTexture = values()[(ordinal + 1) % values().size]
}

private fun id(name: String) = Identifier.of("hypnosia", "textures/jump/$name.png")
