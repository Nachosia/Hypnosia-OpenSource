package dev.hypnosia.visual.world.jump

import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import dev.hypnosia.visual.world.particles.WorldParticlePipeline
import dev.hypnosia.visual.world.particles.WorldParticleTexture
import dev.hypnosia.visual.world.particles.hit.HitGravityMode
import dev.hypnosia.visual.world.particles.hit.HitParticle
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.random.Random
import kotlin.math.cos
import kotlin.math.sin

object JumpCircleRenderer {

    private val circles = mutableListOf<JumpCircle>()
    private val particles = mutableListOf<HitParticle>()
    private val random = Random.create()
    private const val FULL_BRIGHT = 0xF000F0
    private var globalTick = 0
    private var wasOnGround = true

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!JumpCircleSettings.enabled()) {
                if (circles.isNotEmpty()) circles.clear()
                if (particles.isNotEmpty()) particles.clear()
                wasOnGround = true
                return@register
            }
            globalTick++

            val player = client.player
            if (player != null) {
                val onGround = player.isOnGround
                if (wasOnGround && !onGround && player.velocity.y > 0) {
                    onJump(player.x, player.y, player.z)
                }
                wasOnGround = onGround
            }

            val circleIt = circles.iterator()
            while (circleIt.hasNext()) {
                val c = circleIt.next()
                c.tick()
                if (!c.isAlive) circleIt.remove()
            }

            val world = client.world
            val particleIt = particles.iterator()
            while (particleIt.hasNext()) {
                val p = particleIt.next()
                p.tick()
                if (!p.isAlive) { particleIt.remove(); continue }
                if (p.gravityMode == HitGravityMode.BOUNCE && p.velocityY < 0 && world != null) {
                    val bx = MathHelper.floor(p.x)
                    val by = MathHelper.floor(p.y - 0.05)
                    val bz = MathHelper.floor(p.z)
                    val blockPos = BlockPos(bx, by, bz)
                    if (!world.getBlockState(blockPos).isAir) {
                        p.bounceFromFloor(by.toDouble() + 1.0)
                    }
                }
            }
        }

        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context ->
            if (!JumpCircleSettings.enabled()) return@register
            if (circles.isEmpty() && particles.isEmpty()) return@register

            val client = MinecraftClient.getInstance()
            val camera = client.gameRenderer.camera ?: return@register
            val tickDelta = client.renderTickCounter.getTickProgress(true)

            val camPos = camera.cameraPos
            val matrices = context.matrices()
            val queue = context.commandQueue()
            val batch = queue.getBatchingQueue(1)
            val isThirdPerson = !client.options.perspective.isFirstPerson

            // Render circles (horizontal quads)
            if (circles.isNotEmpty() && (!JumpCircleSettings.onlyF5() || isThirdPerson)) {
                val texture = JumpCircleSettings.texture()
                val layer = WorldParticlePipeline.layerFor(texture.textureId)
                val rotSpeed = JumpCircleSettings.rotationSpeed()
                val baseAlpha = JumpCircleSettings.alpha()
                val fadeMode = JumpCircleSettings.fadeMode()
                val circleColorCount = JumpCircleSettings.colorCount()
                val circleGradMode = JumpCircleSettings.gradientMode()
                val circleColors = intArrayOf(
                    JumpCircleSettings.color1(),
                    JumpCircleSettings.color2(),
                    JumpCircleSettings.color3(),
                    JumpCircleSettings.color4(),
                )

                batch.submitCustom(matrices, layer) { entry, vc ->
                    for (circle in circles) {
                        if (!circle.isAlive) continue
                        val alpha = (circle.alphaFor(tickDelta, fadeMode) * baseAlpha).toInt().coerceIn(0, 255)
                        if (alpha <= 0) continue
                        val currentSize = circle.sizeFor(tickDelta, fadeMode)
                        if (currentSize <= 0.01f) continue
                        val h = currentSize * 0.5f

                        val angle = (globalTick + tickDelta) * rotSpeed * 0.1f + circle.rotationOffset
                        val cosA = cos(angle)
                        val sinA = sin(angle)

                        val px = (circle.x - camPos.x).toFloat()
                        val py = (circle.y - camPos.y).toFloat() + 0.02f
                        val pz = (circle.z - camPos.z).toFloat()

                        val color = computeCircleColor(circleColors, circleColorCount, circleGradMode)
                        val cr = (color shr 16) and 0xFF
                        val cg = (color shr 8) and 0xFF
                        val cb = color and 0xFF

                        // Horizontal quad in XZ plane, rotated around Y axis
                        val x0 = -cosA * h - (-sinA) * h
                        val z0 = -sinA * h - cosA * h
                        val x1 = cosA * h - (-sinA) * h
                        val z1 = sinA * h - cosA * h
                        val x2 = cosA * h + (-sinA) * h
                        val z2 = sinA * h + cosA * h
                        val x3 = -cosA * h + (-sinA) * h
                        val z3 = -sinA * h + cosA * h

                        vc.vertex(entry, px + x0, py, pz + z0)
                            .color(cr, cg, cb, alpha).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                        vc.vertex(entry, px + x1, py, pz + z1)
                            .color(cr, cg, cb, alpha).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                        vc.vertex(entry, px + x2, py, pz + z2)
                            .color(cr, cg, cb, alpha).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                        vc.vertex(entry, px + x3, py, pz + z3)
                            .color(cr, cg, cb, alpha).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f)
                    }
                }
            }

            // Render particles (billboard quads)
            if (particles.isNotEmpty() && (!JumpCircleSettings.pOnlyF5() || isThirdPerson)) {
                val q = camera.rotation
                val qx = q.x(); val qy = q.y(); val qz = q.z(); val qw = q.w()
                val rx = 1f - 2f * (qy * qy + qz * qz)
                val ry = 2f * (qx * qy + qw * qz)
                val rz = 2f * (qx * qz - qw * qy)
                val ux = 2f * (qx * qy - qw * qz)
                val uy = 1f - 2f * (qx * qx + qz * qz)
                val uz = 2f * (qy * qz + qw * qx)

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

                            val ph = size * 0.5f
                            val cr = p.colorR
                            val cg = p.colorG
                            val cb = p.colorB
                            vc.vertex(entry, px - rx * ph + ux * ph, py - ry * ph + uy * ph, pz - rz * ph + uz * ph)
                                .color(cr, cg, cb, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                            vc.vertex(entry, px + rx * ph + ux * ph, py + ry * ph + uy * ph, pz + rz * ph + uz * ph)
                                .color(cr, cg, cb, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                            vc.vertex(entry, px + rx * ph - ux * ph, py + ry * ph - uy * ph, pz + rz * ph - uz * ph)
                                .color(cr, cg, cb, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                            vc.vertex(entry, px - rx * ph - ux * ph, py - ry * ph - uy * ph, pz - rz * ph - uz * ph)
                                .color(cr, cg, cb, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_BRIGHT).normal(entry, 0f, 0f, 1f)
                        }
                    }
                }
            }
        }
    }

    private fun onJump(playerX: Double, playerY: Double, playerZ: Double) {
        circles.add(JumpCircle(
            x = playerX,
            y = playerY,
            z = playerZ,
            maxAge = JumpCircleSettings.lifetime(),
            size = JumpCircleSettings.size(),
            rotationOffset = random.nextFloat() * MathHelper.TAU,
        ))

        if (JumpCircleSettings.particlesEnabled()) {
            spawnParticles(playerX, playerY, playerZ)
        }
    }

    private fun spawnParticles(px: Double, py: Double, pz: Double) {
        val count = JumpCircleSettings.pCount()
        val force = JumpCircleSettings.pForce().toDouble()
        val lifetime = JumpCircleSettings.pLifetime()
        val size = JumpCircleSettings.pSize()
        val gravity = JumpCircleSettings.pGravity()
        val textures = JumpCircleSettings.pActiveTextures().toList()
        val colorCount = JumpCircleSettings.pColorCount()
        val gradientMode = JumpCircleSettings.pGradientMode()
        val colors = intArrayOf(
            JumpCircleSettings.pColor1(),
            JumpCircleSettings.pColor2(),
            JumpCircleSettings.pColor3(),
            JumpCircleSettings.pColor4(),
        )

        repeat(count) { i ->
            val angle = (i.toFloat() / count.toFloat()) * MathHelper.TAU.toFloat()
            val speed = force * (0.8 + random.nextDouble() * 0.4)
            val vx = cos(angle).toDouble() * speed
            val vz = sin(angle).toDouble() * speed
            val vy = 0.04 + random.nextDouble() * 0.03

            val color = pickColor(colors, colorCount, gradientMode, i, count)
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF

            val texture = textures[random.nextInt(textures.size)]

            val p = HitParticle(
                x = px + cos(angle).toDouble() * 0.2,
                y = py + 0.1,
                z = pz + sin(angle).toDouble() * 0.2,
                texture = texture,
                gravityMode = gravity,
                maxAge = lifetime,
                originX = px,
                originY = py,
                originZ = pz,
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
            GradientMode.STATIC -> colors[index % colorCount]
            GradientMode.FLUID -> {
                val t = if (total <= 1) 0f else index.toFloat() / (total - 1).toFloat()
                lerpColors(colors, colorCount, t)
            }
            GradientMode.CHROMA -> {
                val animOffset = (globalTick * JumpCircleSettings.pAnimSpeed() * 0.02f) % 1f
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

    private fun computeCircleColor(colors: IntArray, colorCount: Int, gradientMode: GradientMode): Int {
        if (colorCount <= 1) return colors[0]
        return when (gradientMode) {
            GradientMode.STATIC -> colors[0]
            GradientMode.FLUID -> {
                val t = ((globalTick * JumpCircleSettings.animSpeed() * 0.02f) % 1f)
                lerpColors(colors, colorCount, t)
            }
            GradientMode.CHROMA -> {
                val t = ((globalTick * JumpCircleSettings.animSpeed() * 0.03f) % 1f)
                lerpColors(colors, colorCount, t)
            }
        }
    }
}
