package dev.hypnosia.visual.world.particles.hit

import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import dev.hypnosia.visual.world.particles.WorldParticlePipeline
import dev.hypnosia.visual.world.particles.WorldParticleTexture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.entity.Entity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.ActionResult
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.random.Random
import kotlin.math.cos
import kotlin.math.sin

object HitParticleRenderer {

    private val particles = mutableListOf<HitParticle>()
    private val random = Random.create()
    private const val FULL_BRIGHT = 0xF000F0
    private var globalTick = 0

    fun register() {
        AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (world.isClient && HitParticleSettings.enabled()) {
                onAttack(player, entity)
            }
            ActionResult.PASS
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!HitParticleSettings.enabled()) {
                if (particles.isNotEmpty()) particles.clear()
                return@register
            }
            globalTick++
            val world = client.world
            val it = particles.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.tick()
                if (!p.isAlive) { it.remove(); continue }
                if (p.gravityMode == HitGravityMode.BOUNCE && p.velocityY < 0 && world != null) {
                    val bx = MathHelper.floor(p.x)
                    val by = MathHelper.floor(p.y - 0.05)
                    val bz = MathHelper.floor(p.z)
                    val blockPos = net.minecraft.util.math.BlockPos(bx, by, bz)
                    if (!world.getBlockState(blockPos).isAir) {
                        p.bounceFromFloor(by.toDouble() + 1.0)
                    }
                }
            }
        }

        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context ->
            if (!HitParticleSettings.enabled() || particles.isEmpty()) return@register
            val client = MinecraftClient.getInstance()
            val camera = client.gameRenderer.camera ?: return@register
            val tickDelta = client.renderTickCounter.getTickProgress(true)

            val camPos = camera.cameraPos
            val q = camera.rotation
            val qx = q.x(); val qy = q.y(); val qz = q.z(); val qw = q.w()
            val rx = 1f - 2f * (qy * qy + qz * qz)
            val ry = 2f * (qx * qy + qw * qz)
            val rz = 2f * (qx * qz - qw * qy)
            val ux = 2f * (qx * qy - qw * qz)
            val uy = 1f - 2f * (qx * qx + qz * qz)
            val uz = 2f * (qy * qz + qw * qx)

            val matrices = context.matrices()
            val queue = context.commandQueue()
            val batch = queue.getBatchingQueue(1)

            val byTexture = particles.groupBy { it.texture }
            for ((texture, list) in byTexture) {
                val layer = WorldParticlePipeline.layerFor(texture.textureId)
                batch.submitCustom(matrices, layer) { entry, vc ->
                    for (p in list) {
                        if (!p.isAlive) continue
                        val alpha = p.alphaFor(tickDelta)
                        if (alpha <= 0.01f) continue
                        val size = p.sizeFor(tickDelta)

                        val a = (alpha * 255).toInt().coerceIn(0, 255)
                        val px = (p.lerpX(tickDelta) - camPos.x).toFloat()
                        val py = (p.lerpY(tickDelta) - camPos.y).toFloat()
                        val pz = (p.lerpZ(tickDelta) - camPos.z).toFloat()

                        val h = size * 0.5f
                        val cr = p.colorR
                        val cg = p.colorG
                        val cb = p.colorB
                        vc.vertex(entry, px - rx * h + ux * h, py - ry * h + uy * h, pz - rz * h + uz * h)
                            .color(cr, cg, cb, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                        vc.vertex(entry, px + rx * h + ux * h, py + ry * h + uy * h, pz + rz * h + uz * h)
                            .color(cr, cg, cb, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                        vc.vertex(entry, px + rx * h - ux * h, py + ry * h - uy * h, pz + rz * h - uz * h)
                            .color(cr, cg, cb, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                        vc.vertex(entry, px - rx * h - ux * h, py - ry * h - uy * h, pz - rz * h - uz * h)
                            .color(cr, cg, cb, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                    }
                }
            }
        }
    }

    private fun onAttack(player: PlayerEntity, entity: Entity) {
        val trigger = HitParticleSettings.trigger()
        val isCrit = player.getAttackCooldownProgress(0.5f) > 0.9f &&
            player.fallDistance > 0f &&
            !player.isOnGround &&
            !player.isClimbing &&
            !player.isTouchingWater &&
            !player.hasVehicle()

        val shouldSpawn = when (trigger) {
            HitTriggerMode.HIT_AND_CRIT -> true
            HitTriggerMode.HIT_ONLY -> !isCrit
            HitTriggerMode.CRIT_ONLY -> isCrit
        }
        if (!shouldSpawn) return

        val count = HitParticleSettings.count()
        val force = HitParticleSettings.force().toDouble()
        val lifetime = HitParticleSettings.lifetime()
        val textures = HitParticleSettings.activeTextures().toList()
        val gravityMode = HitParticleSettings.gravity()
        val size = HitParticleSettings.size()
        val colorCount = HitParticleSettings.colorCount()
        val gradientMode = HitParticleSettings.gradientMode()
        val colors = intArrayOf(
            HitParticleSettings.color1(),
            HitParticleSettings.color2(),
            HitParticleSettings.color3(),
            HitParticleSettings.color4(),
        )

        val cx = entity.x
        val cy = entity.y + entity.height * 0.5
        val cz = entity.z

        repeat(count) { i ->
            val theta = random.nextDouble() * MathHelper.TAU
            val phi = random.nextDouble() * Math.PI - Math.PI / 2.0
            val speed = force * (0.6 + random.nextDouble() * 0.8)
            val vx = cos(phi) * cos(theta) * speed
            val vy = sin(phi) * speed * 0.6
            val vz = cos(phi) * sin(theta) * speed

            val color = pickColor(colors, colorCount, gradientMode, i, count)
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF

            val texture = textures[random.nextInt(textures.size)]

            val p = HitParticle(
                x = cx + (random.nextDouble() - 0.5) * 0.3,
                y = cy + (random.nextDouble() - 0.5) * 0.3,
                z = cz + (random.nextDouble() - 0.5) * 0.3,
                texture = texture,
                gravityMode = gravityMode,
                maxAge = lifetime,
                originX = cx,
                originY = cy,
                originZ = cz,
                seed = random.nextFloat(),
                baseSize = size,
                colorR = cr,
                colorG = cg,
                colorB = cb,
            )
            p.velocityX = vx
            p.velocityY = vy
            p.velocityZ = vz
            particles.add(p)
        }
    }

    private fun pickColor(colors: IntArray, colorCount: Int, gradientMode: GradientMode, index: Int, total: Int): Int {
        if (colorCount <= 1) return colors[0]
        return when (gradientMode) {
            GradientMode.STATIC -> {
                colors[index % colorCount]
            }
            GradientMode.FLUID -> {
                val t = if (total <= 1) 0f else index.toFloat() / (total - 1).toFloat()
                lerpColors(colors, colorCount, t)
            }
            GradientMode.CHROMA -> {
                val animOffset = (globalTick * HitParticleSettings.animSpeed() * 0.02f) % 1f
                val t = ((index.toFloat() / total.toFloat()) + animOffset) % 1f
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
