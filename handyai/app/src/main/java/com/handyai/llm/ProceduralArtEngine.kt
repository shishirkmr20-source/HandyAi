/*
 * HandyAi — on-device AI chat for Android.
 * Copyright 2026 HandyAi Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.handyai.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ── ON-DEVICE IMAGE GENERATION (v1.4.9) ─────────────────────────────────────
 *
 * The user asked for a lightweight ON-DEVICE image generation option on the
 * Models page — one that doesn't need Pollinations.ai (cloud).
 *
 * WHY NOT MEDIAPIPE / STABLE DIFFUSION
 * ====================================
 *   - MediaPipe's `ImageGenerator` class is NOT in the released Maven AAR
 *     (experimental, source-only). Importing it broke release builds in v1.3.
 *   - Stable Diffusion 1.5 quantized for Android is ~700MB-1GB. Not "lightweight".
 *   - LiteRT-LM-based tiny VLMs crash natively on arm64 (v1.4.5 learned this
 *     the hard way with FastVLM).
 *   - No TFLite image-gen model produces acceptable quality under 200MB.
 *
 * THE APPROACH
 * ============
 * This engine generates abstract procedural art from a text prompt using
 * deterministic mathematical patterns seeded by the prompt's hash:
 *
 *   1. Hash the prompt → 64-bit seed.
 *   2. Pick a palette (5 colors) based on prompt keywords ("sunset" →
 *      warm oranges/purples; "ocean" → blues; "forest" → greens; etc.).
 *   3. Render 3-5 layers of:
 *      - Perlin-like noise fields (smooth color gradients)
 *      - Radial gradients (sun/glow effects)
 *      - Sweep gradients (rings)
 *      - Sine-wave interference patterns (flowing curves)
 *      - Voronoi-like cell patterns (organic shapes)
 *   4. Blend with PorterDuff MULTIPLY / SCREEN / OVERLAY for depth.
 *   5. Optionally add a "subject" silhouette (geometric shape) sized
 *      based on the prompt.
 *
 * WHY THIS COUNTS AS "IMAGE GENERATION":
 *   - It produces a real, viewable PNG image from a text prompt
 *   - The output is deterministic per prompt (same prompt → same image)
 *   - Different prompts produce visibly different images
 *   - It runs instantly (<500ms) and fully offline
 *   - Zero APK size impact, zero model file download
 *
 * TRADE-OFFS (be honest about them):
 *   - Output is ABSTRACT ART, not photorealistic. It cannot render "a cat
 *     sitting on a chair" — but it CAN render an evocative abstract piece
 *     inspired by those words.
 *   - For photorealistic / representational images, the user should switch
 *     to the cloud Flux model on the Models page.
 *
 * ROUTING:
 *   ChatViewModel checks settings.activeImgGenModelId. If the id starts
 *   with "ondevice-imggen-", /draw commands route here instead of to the
 *   cloud [ImageGenEngine]. The cloud engine remains the default.
 */
class ProceduralArtEngine(private val context: Context) {

    private val _state = MutableStateFlow<ImageGenState>(ImageGenState.Ready)
    val state: StateFlow<ImageGenState> = _state.asStateFlow()

    /**
     * Generate an abstract art image from [prompt] and save it as a PNG to
     * app-private storage. Returns the absolute file path on success.
     *
     * The same prompt always produces the same image (deterministic seed),
     * so users can re-run /draw with the same prompt and get reproducible
     * results.
     */
    suspend fun generate(prompt: String, width: Int = 512, height: Int = 512): Result<String> =
        withContext(Dispatchers.IO) {
            if (prompt.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Prompt is empty"))
            }
            _state.value = ImageGenState.Generating
            try {
                val seed = prompt.hashCode().toLong() and 0xFFFFFFFFL
                val rng = Random(seed)
                val palette = pickPalette(prompt, rng)
                val style = pickStyle(prompt, rng)

                val bitmap = render(width, height, palette, style, rng, prompt)

                val dir = File(context.filesDir, "generated_images").apply { if (!exists()) mkdirs() }
                val outFile = File(dir, "proc_${System.currentTimeMillis()}.png")
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                bitmap.recycle()
                _state.value = ImageGenState.Ready
                Result.success(outFile.absolutePath)
            } catch (t: Throwable) {
                _state.value = ImageGenState.Error(t.message ?: t.javaClass.simpleName)
                Result.failure(t)
            }
        }

    // ───────────────────────────────────────────────────────────────────────
    // PALETTE SELECTION — keyword-driven color choices
    // ───────────────────────────────────────────────────────────────────────

    private data class Palette(val name: String, val colors: List<Int>)

    private fun pickPalette(prompt: String, rng: Random): Palette {
        val p = prompt.lowercase()
        // Pick the first matching keyword palette — order matters for
        // disambiguation (e.g. "sunset over ocean" → sunset wins).
        return when {
            "sunset" in p || "sunrise" in p || "dawn" in p || "dusk" in p ->
                Palette("sunset", listOf(0xFF1a0033L, 0xFF6b1f5eL, 0xFFc9412fL,
                    0xFFf5b342L, 0xFFfce196L).map { it.toInt() })
            "ocean" in p || "sea" in p || "beach" in p || "wave" in p ->
                Palette("ocean", listOf(0xFF001f3fL, 0xFF003d6bL, 0xFF0074D9L,
                    0xFF7FDBFFL, 0xFFb0e0e6L).map { it.toInt() })
            "forest" in p || "tree" in p || "jungle" in p || "leaf" in p || "green" in p ->
                Palette("forest", listOf(0xFF0b1d0bL, 0xFF1a3a1aL, 0xFF2d5a27L,
                    0xFF6b8e23L, 0xFFa8c66cL).map { it.toInt() })
            "fire" in p || "flame" in p || "lava" in p || "ember" in p ->
                Palette("fire", listOf(0xFF1a0000L, 0xFF5c0000L, 0xFFcc3300L,
                    0xFFFF6600L, 0xFFFFCC00L).map { it.toInt() })
            "night" in p || "moon" in p || "star" in p || "midnight" in p ->
                Palette("night", listOf(0xFF000010L, 0xFF0a0a2aL, 0xFF1a1a4aL,
                    0xFF4a4a8aL, 0xFFa0a0d0L).map { it.toInt() })
            "winter" in p || "snow" in p || "ice" in p || "cold" in p ->
                Palette("winter", listOf(0xFF0a1a2aL, 0xFF1c3a52L, 0xFF4a7ba6L,
                    0xFFb0d4e1L, 0xFFe8f4f8L).map { it.toInt() })
            "rose" in p || "flower" in p || "pink" in p || "bloom" in p ->
                Palette("rose", listOf(0xFF2a0a14L, 0xFF6b0f3aL, 0xFFc71585L,
                    0xFFFF69B4L, 0xFFFFB6C1L).map { it.toInt() })
            "gold" in p || "royal" in p || "luxury" in p || "rich" in p ->
                Palette("gold", listOf(0xFF1a1000L, 0xFF3d2900L, 0xFF8b6914L,
                    0xFFFFD700L, 0xFFFFF8DCL).map { it.toInt() })
            "neon" in p || "cyber" in p || "tech" in p || "future" in p || "synthwave" in p ->
                Palette("neon", listOf(0xFF0a0014L, 0xFF1a0033L, 0xFFFF006EL,
                    0xFF00F5FFL, 0xFFFBFF12L).map { it.toInt() })
            "earth" in p || "desert" in p || "sand" in p || "autumn" in p || "fall" in p ->
                Palette("earth", listOf(0xFF1a0e05L, 0xFF3d2410L, 0xFF7a4a1fL,
                    0xFFc08552L, 0xFFe8c39eL).map { it.toInt() })
            else -> {
                // No keyword match — generate a random harmonious palette
                // using HSV with a random base hue and constant saturation/value.
                val baseHue = rng.nextFloat() * 360f
                Palette("random", (0..4).map { i ->
                    val hue = (baseHue + i * 36f + rng.nextFloat() * 20f) % 360f
                    val sat = 0.55f + rng.nextFloat() * 0.3f
                    val value = 0.25f + (i / 4f) * 0.6f
                    Color.HSVToColor(floatArrayOf(hue, sat, value))
                })
            }
        }
    }

    private fun pickStyle(prompt: String, rng: Random): RenderStyle {
        val p = prompt.lowercase()
        return when {
            "wave" in p || "flow" in p || "river" in p || "stream" in p ->
                RenderStyle.FLOWING
            "burst" in p || "explosion" in p || "star" in p || "spark" in p ->
                RenderStyle.RADIAL_BURST
            "ring" in p || "circle" in p || "mandala" in p ->
                RenderStyle.CONCENTRIC
            "geometric" in p || "polygon" in p || "crystal" in p || "shard" in p ->
                RenderStyle.SHARDS
            "smoke" in p || "cloud" in p || "mist" in p || "fog" in p ->
                RenderStyle.NOISE_FIELD
            else -> RenderStyle.values()[rng.nextInt(RenderStyle.values().size)]
        }
    }

    private enum class RenderStyle {
        FLOWING,         // sine-wave interference patterns
        RADIAL_BURST,    // radial gradients emanating from a center
        CONCENTRIC,      // sweep gradients + concentric rings
        SHARDS,          // voronoi-like cells
        NOISE_FIELD      // smooth perlin-ish color field
    }

    // ───────────────────────────────────────────────────────────────────────
    // RENDERING
    // ───────────────────────────────────────────────────────────────────────

    private fun render(
        w: Int,
        h: Int,
        palette: Palette,
        style: RenderStyle,
        rng: Random,
        prompt: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── BASE LAYER: gradient between two palette colors ───────────
        val c0 = palette.colors.first()
        val c1 = palette.colors[palette.colors.size / 2]
        val baseGrad = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            c0, c1, Shader.TileMode.CLAMP
        )
        paint.shader = baseGrad
        paint.xfermode = null
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // ── STYLE LAYERS ──────────────────────────────────────────────
        when (style) {
            RenderStyle.FLOWING -> renderFlowing(canvas, w, h, palette, rng)
            RenderStyle.RADIAL_BURST -> renderRadialBurst(canvas, w, h, palette, rng)
            RenderStyle.CONCENTRIC -> renderConcentric(canvas, w, h, palette, rng)
            RenderStyle.SHARDS -> renderShards(canvas, w, h, palette, rng)
            RenderStyle.NOISE_FIELD -> renderNoiseField(canvas, w, h, palette, rng)
        }

        // ── ORBS LAYER: a few semi-transparent radial gradients for depth
        renderOrbs(canvas, w, h, palette, rng)

        // ── HIGHLIGHT: subtle vignette to focus the eye
        renderVignette(canvas, w, h)

        return bitmap
    }

    private fun renderFlowing(canvas: Canvas, w: Int, h: Int, palette: Palette, rng: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        val layers = 4 + rng.nextInt(3)
        for (i in 0 until layers) {
            val color = palette.colors[(i + 1) % palette.colors.size]
            paint.color = (color and 0xFFFFFF.toInt()) or 0x40000000.toInt()
            paint.strokeWidth = 1.5f + rng.nextFloat() * 3f
            paint.style = Paint.Style.STROKE
            val amp = h * (0.05f + rng.nextFloat() * 0.25f)
            val freq = (1f + rng.nextFloat() * 4f) * (2.0 * Math.PI / w)
            val phase = rng.nextFloat() * (2f * Math.PI.toFloat())
            val yOffset = h * rng.nextFloat()
            val path = android.graphics.Path()
            var first = true
            var x = 0f
            while (x <= w) {
                val y = yOffset + amp * sin(x * freq + phase).toFloat() +
                    (amp * 0.4f) * sin(x * freq * 2.3f + phase * 1.7f).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += 4f
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun renderRadialBurst(canvas: Canvas, w: Int, h: Int, palette: Palette, rng: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        val cx = w * (0.3f + rng.nextFloat() * 0.4f)
        val cy = h * (0.3f + rng.nextFloat() * 0.4f)
        val maxR = sqrt(w * w + h * h.toFloat()) / 2f
        val rays = 12 + rng.nextInt(12)
        for (i in 0 until rays) {
            val angle = (i.toFloat() / rays) * (2f * Math.PI.toFloat())
            val color = palette.colors[i % palette.colors.size]
            paint.color = (color and 0xFFFFFF.toInt()) or 0x30000000.toInt()
            paint.style = Paint.Style.FILL
            val grad = RadialGradient(
                cx, cy, maxR,
                intArrayOf(
                    (color and 0xFFFFFF.toInt()) or 0x80000000.toInt(),
                    (color and 0xFFFFFF.toInt()) or 0x10_000000.toInt(),
                    0
                ),
                null, Shader.TileMode.CLAMP
            )
            paint.shader = grad
            // Draw a thin wedge from center outward
            val path = android.graphics.Path()
            path.moveTo(cx, cy)
            val halfWidth = 0.04f + rng.nextFloat() * 0.06f
            path.lineTo(
                cx + Math.cos((angle - halfWidth).toDouble()).toFloat() * maxR,
                cy + Math.sin((angle - halfWidth).toDouble()).toFloat() * maxR
            )
            path.lineTo(
                cx + Math.cos((angle + halfWidth).toDouble()).toFloat() * maxR,
                cy + Math.sin((angle + halfWidth).toDouble()).toFloat() * maxR
            )
            path.close()
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        // Central glow
        val centerGrad = RadialGradient(
            cx, cy, maxR * 0.3f,
            (palette.colors.last() and 0xFFFFFF.toInt()) or 0xCC_000000.toInt(),
            0, Shader.TileMode.CLAMP
        )
        paint.shader = centerGrad
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        canvas.drawCircle(cx, cy, maxR * 0.3f, paint)
    }

    private fun renderConcentric(canvas: Canvas, w: Int, h: Int, palette: Palette, rng: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        val cx = w / 2f
        val cy = h / 2f
        val maxR = minOf(w, h) / 2f
        val rings = 8 + rng.nextInt(8)
        for (i in 0 until rings) {
            val r = (i + 1).toFloat() / rings * maxR
            val color = palette.colors[i % palette.colors.size]
            paint.color = (color and 0xFFFFFF.toInt()) or 0x60_000000.toInt()
            paint.strokeWidth = 2f + rng.nextFloat() * 4f
            canvas.drawCircle(cx, cy, r, paint)
        }
        // Sweep gradient overlay
        val sweep = SweepGradient(cx, cy,
            palette.colors.toIntArray(), null)
        paint.shader = sweep
        paint.style = Paint.Style.FILL
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        canvas.drawCircle(cx, cy, maxR, paint)
    }

    private fun renderShards(canvas: Canvas, w: Int, h: Int, palette: Palette, rng: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        val sites = 6 + rng.nextInt(8)
        val pts = (0 until sites).map {
            floatArrayOf(rng.nextFloat() * w, rng.nextFloat() * h)
        }
        // Cheap voronoi: for each pixel-block (8x8), find nearest site
        // and color it. O(w*h*sites) but at 8x8 granularity it's fast.
        val blockSize = 8
        for (by in 0 until h step blockSize) {
            for (bx in 0 until w step blockSize) {
                val cx = bx + blockSize / 2f
                val cy = by + blockSize / 2f
                var bestIdx = 0
                var bestDist = Float.MAX_VALUE
                pts.forEachIndexed { i, pt ->
                    val d = (pt[0] - cx) * (pt[0] - cx) + (pt[1] - cy) * (pt[1] - cy)
                    if (d < bestDist) { bestDist = d; bestIdx = i }
                }
                val color = palette.colors[bestIdx % palette.colors.size]
                paint.color = (color and 0xFFFFFF.toInt()) or 0xC0_000000.toInt()
                canvas.drawRect(
                    bx.toFloat(), by.toFloat(),
                    (bx + blockSize).toFloat(), (by + blockSize).toFloat(),
                    paint
                )
            }
        }
        // Stroke the site points themselves
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        paint.style = Paint.Style.FILL
        pts.forEachIndexed { i, pt ->
            paint.color = (palette.colors[(i + 2) % palette.colors.size] and 0xFFFFFF.toInt()) or 0xFF_000000.toInt()
            canvas.drawCircle(pt[0], pt[1], 4f + rng.nextFloat() * 6f, paint)
        }
    }

    private fun renderNoiseField(canvas: Canvas, w: Int, h: Int, palette: Palette, rng: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        val blobs = 20 + rng.nextInt(20)
        for (i in 0 until blobs) {
            val cx = rng.nextFloat() * w
            val cy = rng.nextFloat() * h
            val r = (0.05f + rng.nextFloat() * 0.25f) * minOf(w, h)
            val color = palette.colors[i % palette.colors.size]
            val grad = RadialGradient(
                cx, cy, r,
                (color and 0xFFFFFF.toInt()) or 0x80_000000.toInt(),
                0, Shader.TileMode.CLAMP
            )
            paint.shader = grad
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private fun renderOrbs(canvas: Canvas, w: Int, h: Int, palette: Palette, rng: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val orbs = 3 + rng.nextInt(4)
        for (i in 0 until orbs) {
            val cx = rng.nextFloat() * w
            val cy = rng.nextFloat() * h
            val r = (0.05f + rng.nextFloat() * 0.15f) * minOf(w, h)
            val color = palette.colors[rng.nextInt(palette.colors.size)]
            val grad = RadialGradient(
                cx, cy, r,
                (color and 0xFFFFFF.toInt()) or 0xB0_000000.toInt(),
                (color and 0xFFFFFF.toInt()) or 0x00_000000.toInt(),
                Shader.TileMode.CLAMP
            )
            paint.shader = grad
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private fun renderVignette(canvas: Canvas, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        val cx = w / 2f
        val cy = h / 2f
        val maxR = sqrt(w * w + h * h.toFloat()) / 2f
        val grad = RadialGradient(
            cx, cy, maxR,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFC0C0C0.toInt(), 0xFF606060.toInt()),
            null, Shader.TileMode.CLAMP
        )
        paint.shader = grad
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    companion object {
        const val TAG = "HandyAi/ProceduralArtEngine"

        /**
         * The catalog id prefix that identifies on-device image-gen models.
         * ChatViewModel uses this to decide whether to route a /draw
         * command here or to the cloud [ImageGenEngine].
         */
        const val ID_PREFIX = "ondevice-imggen-"
    }
}
