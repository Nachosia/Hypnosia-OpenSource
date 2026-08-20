package dev.hypnosia.visual.world.esp

import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import dev.hypnosia.visual.world.particles.WorldParticlePipeline
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.entity.LivingEntity
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import kotlin.math.cos
import kotlin.math.sin

object TargetEspRenderer {

    private var activeTarget: LivingEntity? = null
    private var remainingTicks = 0
    private var globalTick = 0
    private const val FULL_BRIGHT = 0xF000F0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!TargetEspSettings.enabled()) {
                activeTarget = null
                remainingTicks = 0
                return@register
            }
            globalTick++

            val crosshair = client.crosshairTarget
            val entity = (crosshair as? EntityHitResult)?.entity as? LivingEntity

            if (entity != null && entity.isAlive && entity != client.player && !entity.isInvisible) {
                activeTarget = entity
                remainingTicks = TargetEspSettings.lifetime()
            } else {
                if (remainingTicks > 0) {
                    remainingTicks--
                } else {
                    activeTarget = null
                }
            }

            val target = activeTarget
            if (target != null && (target.isRemoved || !target.isAlive)) {
                activeTarget = null
                remainingTicks = 0
            }
        }

        WorldRenderEvents.AFTER_ENTITIES.register { context ->
            if (!TargetEspSettings.enabled()) return@register
            val target = activeTarget ?: return@register

            val client = MinecraftClient.getInstance()
            val camera = client.gameRenderer.camera ?: return@register
            val tickDelta = client.renderTickCounter.getTickProgress(true)
            val world = client.world ?: return@register

            val camPos = camera.cameraPos

            val lerpedPos = target.getLerpedPos(tickDelta)
            val espPos = Vec3d(lerpedPos.x, lerpedPos.y + target.height * 0.5, lerpedPos.z)
            val blockHit = world.raycast(
                RaycastContext(
                    camPos, espPos,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,
                    client.player
                )
            )
            if (blockHit.type == HitResult.Type.BLOCK) {
                val hitDist = blockHit.pos.squaredDistanceTo(camPos)
                val espDist = espPos.squaredDistanceTo(camPos)
                if (hitDist < espDist) return@register
            }
            val q = camera.rotation
            val qx = q.x(); val qy = q.y(); val qz = q.z(); val qw = q.w()
            var rx = 1f - 2f * (qy * qy + qz * qz)
            var ry = 2f * (qx * qy + qw * qz)
            var rz = 2f * (qx * qz - qw * qy)
            var ux = 2f * (qx * qy - qw * qz)
            var uy = 1f - 2f * (qx * qx + qz * qz)
            var uz = 2f * (qy * qz + qw * qx)

            val rotSpeed = TargetEspSettings.rotationSpeed()
            if (rotSpeed > 0.001f) {
                val angle = (globalTick + tickDelta) * rotSpeed * 0.1f
                val cosA = cos(angle)
                val sinA = sin(angle)
                val nrx = rx * cosA + ux * sinA
                val nry = ry * cosA + uy * sinA
                val nrz = rz * cosA + uz * sinA
                val nux = -rx * sinA + ux * cosA
                val nuy = -ry * sinA + uy * cosA
                val nuz = -rz * sinA + uz * cosA
                rx = nrx; ry = nry; rz = nrz
                ux = nux; uy = nuy; uz = nuz
            }

            val lifetime = TargetEspSettings.lifetime()
            val fadeStart = (lifetime * 0.7f).toInt()
            val baseAlpha = TargetEspSettings.alpha()
            val alpha = if (remainingTicks < (lifetime - fadeStart)) {
                val fadeProgress = remainingTicks.toFloat() / (lifetime - fadeStart).toFloat()
                (baseAlpha * fadeProgress).toInt().coerceIn(0, 255)
            } else {
                baseAlpha
            }
            if (alpha <= 0) return@register

            val size = TargetEspSettings.size()
            val texture = TargetEspSettings.texture()

            val color = computeColor()
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF

            val px = (espPos.x - camPos.x).toFloat()
            val py = (espPos.y - camPos.y).toFloat()
            val pz = (espPos.z - camPos.z).toFloat()

            val h = size * 0.5f

            val matrices = context.matrices()
            val queue = context.commandQueue()
            val batch = queue.getBatchingQueue(1)
            val layer = WorldParticlePipeline.layerNoDepth(texture.textureId)

            batch.submitCustom(matrices, layer) { entry, vc ->
                vc.vertex(entry, px - rx * h + ux * h, py - ry * h + uy * h, pz - rz * h + uz * h)
                    .color(cr, cg, cb, alpha).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                vc.vertex(entry, px + rx * h + ux * h, py + ry * h + uy * h, pz + rz * h + uz * h)
                    .color(cr, cg, cb, alpha).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                vc.vertex(entry, px + rx * h - ux * h, py + ry * h - uy * h, pz + rz * h - uz * h)
                    .color(cr, cg, cb, alpha).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                vc.vertex(entry, px - rx * h - ux * h, py - ry * h - uy * h, pz - rz * h - uz * h)
                    .color(cr, cg, cb, alpha).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
            }
        }
    }

    private fun computeColor(): Int {
        val colorCount = TargetEspSettings.colorCount()
        val colors = intArrayOf(
            TargetEspSettings.color1(),
            TargetEspSettings.color2(),
            TargetEspSettings.color3(),
            TargetEspSettings.color4(),
        )
        if (colorCount <= 1) return colors[0]

        return when (TargetEspSettings.gradientMode()) {
            GradientMode.STATIC -> colors[0]
            GradientMode.FLUID -> {
                val t = ((globalTick * TargetEspSettings.animSpeed() * 0.02f) % 1f)
                lerpColors(colors, colorCount, t)
            }
            GradientMode.CHROMA -> {
                val t = ((globalTick * TargetEspSettings.animSpeed() * 0.03f) % 1f)
                lerpColors(colors, colorCount, t)
            }
        }
    }

    private fun lerpColors(colors: IntArray, count: Int, t: Float): Int {
        val segT = t * (count - 1)
        val idx = segT.toInt().coerceIn(0, count - 2)
        val frac = segT - idx
        val c1 = colors[idx]
        val c2 = colors[idx + 1]
        val r = ((c1 shr 16 and 0xFF) + (((c2 shr 16 and 0xFF) - (c1 shr 16 and 0xFF)) * frac)).toInt()
        val g = ((c1 shr 8 and 0xFF) + (((c2 shr 8 and 0xFF) - (c1 shr 8 and 0xFF)) * frac)).toInt()
        val b = ((c1 and 0xFF) + (((c2 and 0xFF) - (c1 and 0xFF)) * frac)).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
