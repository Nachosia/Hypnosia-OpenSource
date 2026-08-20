package dev.hypnosia.visual.world.particles

import dev.hypnosia.visual.cosmetic.CosmeticSettings.GradientMode
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.random.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Спавнер + рендер частиц мира.
 *
 * Рендер построен по проверенному паттерну косметики: камера-фейсинг билборды
 * собираются вручную и сабмитятся через [net.minecraft.client.render.command.OrderedRenderCommandQueue]
 * (`getBatchingQueue(n).submitCustom(matrices, layer)`). Никакого BLOCK_ATLAS —
 * каждая текстура рисуется своим [net.minecraft.client.render.RenderLayer]
 * из [WorldParticlePipeline].
 *
 * Координаты вершин — относительно камеры (требование world render context).
 */
object WorldParticleRenderer {

    private val particles = mutableListOf<WorldAmbientParticle>()
    private val random = Random.create()
    private var spawnAccumulator = 0
    private const val FULL_BRIGHT = 0xF000F0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!WorldParticleSettings.enabled()) {
                if (particles.isNotEmpty()) particles.clear()
                return@register
            }
            val world = client.world
            val it = particles.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.tick()
                if (!p.isAlive) { it.remove(); continue }
                // Bounce: коллизия с реальными блоками.
                if (p.gravityMode == WorldGravityMode.BOUNCE && p.velocityY < 0 && world != null) {
                    val bx = net.minecraft.util.math.MathHelper.floor(p.x)
                    val by = net.minecraft.util.math.MathHelper.floor(p.y - 0.05)
                    val bz = net.minecraft.util.math.MathHelper.floor(p.z)
                    val blockPos = net.minecraft.util.math.BlockPos(bx, by, bz)
                    val state = world.getBlockState(blockPos)
                    if (!state.isAir) {
                        p.bounceFromFloor(by.toDouble() + 1.0)
                    }
                }
            }
            cullDistant(client)
            spawnParticles(client)
        }

        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context ->
            if (!WorldParticleSettings.enabled() || particles.isEmpty()) return@register
            val client = MinecraftClient.getInstance()
            val camera = client.gameRenderer.camera ?: return@register
            val tickDelta = client.renderTickCounter.getTickProgress(true)

            val camPos = camera.cameraPos
            // Билборд: вычисляем right/up из quaternion камеры напрямую.
            // Vanilla BillboardParticle использует именно этот подход.
            val q = camera.rotation
            val qx = q.x(); val qy = q.y(); val qz = q.z(); val qw = q.w()
            // right = quaternion * (1,0,0): standard formula
            val rx = 1f - 2f * (qy * qy + qz * qz)
            val ry = 2f * (qx * qy + qw * qz)
            val rz = 2f * (qx * qz - qw * qy)
            // up = quaternion * (0,1,0): standard formula
            val ux = 2f * (qx * qy - qw * qz)
            val uy = 1f - 2f * (qx * qx + qz * qz)
            val uz = 2f * (qy * qz + qw * qx)

            val matrices = context.matrices()
            val queue = context.commandQueue()
            val batch = queue.getBatchingQueue(1)

            val alphaSetting = WorldParticleSettings.alpha()
            val gradientMode = WorldParticleSettings.gradientMode()
            val animSpeed = WorldParticleSettings.animSpeed()

            // Группируем по текстуре, чтобы каждый RenderLayer прошёл одним submit.
            val byTexture = particles.groupBy { it.texture }
            for ((texture, list) in byTexture) {
                val layer = WorldParticlePipeline.layerFor(texture.textureId)
                batch.submitCustom(matrices, layer) { entry, vc ->
                    for (p in list) {
                        if (!p.isAlive) continue
                        val size = p.sizeFor(tickDelta)
                        if (size <= 0.0001f) continue

                        val a = (p.alphaFor(tickDelta) * alphaSetting).toInt().coerceIn(0, 255)
                        if (a <= 0) continue

                        val (cr, cg, cb) = colorFor(p, gradientMode, animSpeed)

                        // Позиция частицы относительно камеры (интерполяция через velocity).
                        val px = (p.lerpX(tickDelta) - camPos.x).toFloat()
                        val py = (p.lerpY(tickDelta) - camPos.y).toFloat()
                        val pz = (p.lerpZ(tickDelta) - camPos.z).toFloat()

                        val h = size * 0.5f
                        // Углы билборда: центр ± right*h ± up*h.
                        // Поворот вокруг оси взгляда (rotation) — опционально для STARS/MAGIC.
                        val rot = p.rotationFor(tickDelta)
                        val cosR = cos(rot)
                        val sinR = sin(rot)

                        // повёрнутые орты в плоскости билборда
                        val r2x = rx * cosR + ux * sinR
                        val r2y = ry * cosR + uy * sinR
                        val r2z = rz * cosR + uz * sinR
                        val u2x = -rx * sinR + ux * cosR
                        val u2y = -ry * sinR + uy * cosR
                        val u2z = -rz * sinR + uz * cosR

                        // 4 угла quad (CW front-face). UV: TL(0,0), TR(1,0), BR(1,1), BL(0,1).
                        // top-left
                        vertex(entry, vc, px - r2x * h + u2x * h, py - r2y * h + u2y * h, pz - r2z * h + u2z * h, 0f, 0f, cr, cg, cb, a)
                        // top-right
                        vertex(entry, vc, px + r2x * h + u2x * h, py + r2y * h + u2y * h, pz + r2z * h + u2z * h, 1f, 0f, cr, cg, cb, a)
                        // bottom-right
                        vertex(entry, vc, px + r2x * h - u2x * h, py + r2y * h - u2y * h, pz + r2z * h - u2z * h, 1f, 1f, cr, cg, cb, a)
                        // bottom-left
                        vertex(entry, vc, px - r2x * h - u2x * h, py - r2y * h - u2y * h, pz - r2z * h - u2z * h, 0f, 1f, cr, cg, cb, a)
                    }
                }
            }
        }
    }

    private fun vertex(
        entry: net.minecraft.client.util.math.MatrixStack.Entry,
        vc: net.minecraft.client.render.VertexConsumer,
        x: Float, y: Float, z: Float,
        u: Float, v: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        vc.vertex(entry, x, y, z)
            .color(r, g, b, a)
            .texture(u, v)
            .overlay(OverlayTexture.DEFAULT_UV)
            .light(FULL_BRIGHT)
            .normal(entry, 0f, 0f, 1f)
    }

    /** Разрешает цвет частицы по режиму градиента (как в косметике). */
    private fun colorFor(
        p: WorldAmbientParticle,
        mode: GradientMode,
        animSpeed: Float,
    ): Triple<Int, Int, Int> {
        return when (mode) {
            GradientMode.CHROMA -> {
                val time = System.currentTimeMillis() / 1000.0
                val hue = ((time * animSpeed * 0.05 + p.seed) % 1.0).toFloat()
                val rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f)
                Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            }
            GradientMode.FLUID -> {
                val count = WorldParticleSettings.colorCount()
                if (count <= 1) return rgb(WorldParticleSettings.color1())
                val time = System.currentTimeMillis() / 1000.0 * animSpeed
                val wave = sin(p.seed * 6.28 + time * 1.1) * 0.5 + 0.5
                val idx = (wave * count).toFloat()
                val i0 = idx.toInt() % count
                val i1 = (i0 + 1) % count
                val frac = idx - idx.toInt()
                lerp(frac, rgb(colorIndex(i0)), rgb(colorIndex(i1)))
            }
            else -> {
                val count = WorldParticleSettings.colorCount()
                if (count <= 1) return rgb(WorldParticleSettings.color1())
                val idx = (p.seed * count).toInt().coerceIn(0, count - 1)
                rgb(colorIndex(idx))
            }
        }
    }

    private fun colorIndex(i: Int): Int = when (i) {
        0 -> WorldParticleSettings.color1()
        1 -> WorldParticleSettings.color2()
        2 -> WorldParticleSettings.color3()
        else -> WorldParticleSettings.color4()
    }

    private fun rgb(c: Int): Triple<Int, Int, Int> =
        Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    private fun lerp(t: Float, a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        val ct = t.coerceIn(0f, 1f)
        return Triple(
            (a.first + (b.first - a.first) * ct).toInt(),
            (a.second + (b.second - a.second) * ct).toInt(),
            (a.third + (b.third - a.third) * ct).toInt(),
        )
    }

    private fun cullDistant(client: MinecraftClient) {
        val player = client.player ?: return
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val dx = p.x - player.x
            val dy = p.y - player.y
            val dz = p.z - player.z
            if (dx * dx + dy * dy + dz * dz > 30.0 * 30.0) it.remove()
        }
    }

    private fun spawnParticles(client: MinecraftClient) {
        val player = client.player ?: return
        val maxCount = WorldParticleSettings.count()
        if (particles.size >= maxCount) return

        spawnAccumulator++
        val rate = WorldParticleSettings.spawnRate()
        if (spawnAccumulator % rate != 0) return

        val spawnBudget = (maxCount - particles.size).coerceAtMost(2)
        repeat(spawnBudget) { spawnSingleParticle(player) }
    }

    private fun spawnSingleParticle(player: net.minecraft.entity.player.PlayerEntity) {
        val activeTextures = WorldParticleSettings.activeTextures()
        val texture = activeTextures.elementAt(random.nextInt(activeTextures.size))
        val mode = WorldParticleSettings.mode()
        val speedBase = WorldParticleSettings.speed()
        val sizeBase = WorldParticleSettings.size()
        val lifeBase = WorldParticleSettings.life()
        val gravity = WorldParticleSettings.gravity()
        val spawnHeight = WorldParticleSettings.spawnHeight()

        val minRadius = 8.0
        val maxRadius = 18.0
        val angle = random.nextDouble() * MathHelper.TAU
        val dist = minRadius + random.nextDouble() * (maxRadius - minRadius)
        val x = player.x + cos(angle) * dist
        val z = player.z + sin(angle) * dist
        // База = player.y - 2 (вшито). Высота = настройка spawnHeight (5-30).
        val baseY = player.y - 2.0
        val y = baseY + random.nextDouble() * spawnHeight

        val (vx, vy, vz) = when (mode) {
            WorldParticleMode.NEON -> Triple(
                (random.nextFloat() - 0.5f) * speedBase * 0.4,
                (random.nextFloat() - 0.5f) * speedBase * 0.4,
                (random.nextFloat() - 0.5f) * speedBase * 0.4
            )
            WorldParticleMode.STARS -> Triple(
                (random.nextFloat() - 0.5f) * speedBase * 0.6,
                (random.nextFloat() - 0.5f) * speedBase * 0.2,
                (random.nextFloat() - 0.5f) * speedBase * 0.6
            )
            WorldParticleMode.DUST -> Triple(
                (random.nextFloat() - 0.5f) * speedBase * 0.5,
                (random.nextFloat() - 0.5f) * speedBase * 0.3,
                (random.nextFloat() - 0.5f) * speedBase * 0.5
            )
            WorldParticleMode.SNOW_ASH -> Triple(
                (random.nextFloat() - 0.5f) * speedBase * 0.3,
                -speedBase * (0.3f + random.nextFloat() * 0.4f),
                (random.nextFloat() - 0.5f) * speedBase * 0.3
            )
            WorldParticleMode.MAGIC -> Triple(
                (random.nextFloat() - 0.5f) * speedBase * 0.5,
                speedBase * (0.2f + random.nextFloat() * 0.3f),
                (random.nextFloat() - 0.5f) * speedBase * 0.5
            )
        }

        val maxAgeTicks = (lifeBase * 20f * (0.7f + random.nextFloat() * 0.6f)).toInt()

        val particle = WorldAmbientParticle(
            x = x,
            y = y,
            z = z,
            texture = texture,
            mode = mode,
            gravityMode = WorldParticleSettings.gravityMode(),
            maxAge = maxAgeTicks.coerceAtLeast(20),
            baseScale = sizeBase,
            driftPhase = random.nextFloat() * MathHelper.TAU.toFloat(),
            gravity = gravity,
            baseAlpha = 1.0f,
            seed = random.nextFloat(),
        )
        particle.setVelocity(vx.toDouble(), vy.toDouble(), vz.toDouble())
        particles.add(particle)
    }
}
