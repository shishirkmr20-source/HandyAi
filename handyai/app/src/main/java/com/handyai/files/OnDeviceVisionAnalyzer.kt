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
package com.handyai.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * ── ON-DEVICE VISION (v1.4.9) ───────────────────────────────────────────────
 *
 * The user asked for a lightweight ON-DEVICE vision option on the Models page
 * — one that doesn't need the HuggingFace cloud VLM (Llama-3.2-Vision / LLaVA).
 *
 * This analyzer produces a structured, question-answer-style scene description
 * using ONLY ML Kit (which ships with Google Play Services — no extra download
 * for the user, no network round-trip, instant). It runs the same ML Kit
 * pipelines as [ImageAnalyzer] (OCR + image labeling + object detection) but
 * ASSEMBLES the output as a direct answer to the user's question rather than
 * as raw context for the LLM to interpret.
 *
 * WHY THIS IS "LIGHTWEIGHT":
 *   - ML Kit comes from Google Play services → ~0 added APK size (the model
 *     files are downloaded once by Play Services, shared across all ML Kit apps
 *     on the device)
 *   - No model file the user has to download
 *   - No network calls — works fully offline
 *   - Runs in 100–500ms per image on a modern phone
 *
 * WHAT IT CAN DO (and what it can't):
 *   ✓ "What's in this image?" → list of detected objects + scene labels
 *   ✓ "Read the text in this image" → OCR'd text verbatim
 *   ✓ "How many people/objects are in this image?" → count from object detection
 *   ✓ "What's the main subject?" → highest-confidence label
 *   ✗ "Describe the mood" → no emotion analysis
 *   ✗ "What's happening?" → no scene understanding beyond label lists
 *   ✗ "What color is the car?" → no fine-grained attribute extraction
 *
 * For richer answers, the user should switch back to a cloud VLM (Llama-3.2-
 * Vision) on the Models page — that's the trade-off for being lightweight.
 *
 * ROUTING:
 *   ChatViewModel checks settings.activeVisionModelId. If the active vision
 *   model's id starts with "ondevice-vision-", it routes to this class
 *   instead of [VisionLlm]. Otherwise, it goes to the cloud VLM as before.
 */
class OnDeviceVisionAnalyzer(private val context: Context) {

    /**
     * Analyze an image and produce a direct text answer to [question].
     *
     * @param uri       Content URI of the image
     * @param question  The user's question about the image (e.g. "what's in
     *                  this image?", "read the text", "how many objects?")
     * @return A natural-language answer, or null if the image couldn't be
     *         decoded. Always returns *something* for a valid image — even
     *         if just "I detected N objects but couldn't extract text."
     */
    suspend fun ask(uri: Uri, question: String): String? = withContext(Dispatchers.IO) {
        val bitmap = try {
            decodeBitmap(uri) ?: return@withContext null
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Bitmap decode failed: ${t.message}")
            return@withContext null
        }

        try {
            // Run all three ML Kit detectors. They're fast (100-500ms each
            // on a modern phone) and run fully on-device.
            val ocrText = runOcr(bitmap)
            val labels = runLabeling(bitmap)
            val objects = runObjectDetection(bitmap)

            // Assemble a direct answer to the user's question.
            val answer = composeAnswer(question, ocrText, labels, objects)
            answer
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "On-device vision analysis failed", t)
            "[On-device vision error: ${t.message ?: t.javaClass.simpleName}]"
        } finally {
            try { bitmap.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * Turn the raw ML Kit outputs into a natural-language answer that
     * addresses the user's question. Pattern-matches the question to pick
     * the right response template — no LLM call (that's the whole point).
     */
    private fun composeAnswer(
        question: String,
        ocrText: String,
        labels: List<Pair<String, Float>>,
        objects: List<Pair<String, String>>
    ): String {
        val q = question.lowercase().trim()
        val sb = StringBuilder()

        // ── PATTERN 1: "read the text" / "what does it say" ────────────
        if (q.contains("read") && (q.contains("text") || q.contains("say")) ||
            q.contains("ocr") || q.contains("what does it say")
        ) {
            sb.appendLine("Text detected in the image (on-device OCR):")
            if (ocrText.isBlank()) {
                sb.appendLine("(No legible text was found.)")
            } else {
                sb.appendLine(ocrText.trim())
            }
            return sb.toString().trim()
        }

        // ── PATTERN 2: "how many" — count objects ─────────────────────
        if (q.startsWith("how many") || q.contains("how many ")) {
            val totalObjects = objects.size
            val byLabel = objects.groupingBy { it.first }.eachCount()
                .toList().sortedByDescending { it.second }
            sb.appendLine("Object count (on-device detection):")
            sb.appendLine("  Total detected: $totalObjects")
            if (byLabel.isNotEmpty()) {
                sb.appendLine("  Breakdown:")
                byLabel.forEach { (label, count) ->
                    sb.appendLine("    - $label: $count")
                }
            }
            // Also surface OCR text if the question is about "how many words"
            if (q.contains("word") && ocrText.isNotBlank()) {
                val wordCount = ocrText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                sb.appendLine("  Words in image text: $wordCount")
            }
            return sb.toString().trim()
        }

        // ── PATTERN 3: "what's in" / "describe" / "what do you see" ────
        // Default — give a complete scene description.
        sb.appendLine("On-device scene analysis:")

        // Objects with positions
        if (objects.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Detected objects (${objects.size}):")
            objects.forEach { (label, position) ->
                sb.appendLine("  - $label (position: $position)")
            }
        }

        // Scene labels (top-K by confidence)
        if (labels.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Scene labels:")
            val sorted = labels.sortedByDescending { it.second }
            sorted.take(10).forEach { (label, _) ->
                sb.appendLine("  - $label")
            }
        }

        // OCR text (if any)
        if (ocrText.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("Visible text:")
            sb.appendLine(ocrText.trim().prependIndent("  "))
        }

        if (objects.isEmpty() && labels.isEmpty() && ocrText.isBlank()) {
            sb.appendLine("(Nothing was detected — the image may be too small, blurry, or contain content the on-device model wasn't trained to recognize. Try the cloud VLM on the Models page for richer analysis.)")
        } else {
            sb.appendLine()
            sb.appendLine("Note: this analysis was performed fully on-device using ML Kit. For more nuanced interpretation (mood, actions, context), switch to a cloud VLM like Llama-3.2-Vision on the Models page.")
        }

        return sb.toString().trim()
    }

    // ───────────────────────────────────────────────────────────────────────
    // Bitmap loading + ML Kit detectors (same logic as ImageAnalyzer.kt but
    // kept here so this class is self-contained and doesn't depend on the
    // image-attachment pipeline).
    // ───────────────────────────────────────────────────────────────────────

    private fun decodeBitmap(uri: Uri): android.graphics.Bitmap? {
        // First pass: bounds only → compute sample size for large images
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        } catch (_: Throwable) {}
        val sampleSize = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > 2000) sample *= 2
            sample
        } else 1

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                })
            }
        } catch (_: Throwable) { null }
    }

    private suspend fun runOcr(bitmap: android.graphics.Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
                .addOnFailureListener { cont.resume("") }
        }

    private suspend fun runLabeling(bitmap: android.graphics.Bitmap): List<Pair<String, Float>> =
        suspendCancellableCoroutine { cont ->
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.55f)
                    .build()
            )
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    cont.resume(labels.map { it.text to it.confidence })
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private suspend fun runObjectDetection(bitmap: android.graphics.Bitmap): List<Pair<String, String>> =
        suspendCancellableCoroutine { cont ->
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
            val detector = ObjectDetection.getClient(options)
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    val w = bitmap.width.toFloat().coerceAtLeast(1f)
                    val h = bitmap.height.toFloat().coerceAtLeast(1f)
                    val pairs = detectedObjects.mapNotNull { obj ->
                        val label = obj.labels.maxByOrNull { it.confidence }?.text
                            ?: "object"
                        val cx = (obj.boundingBox.left + obj.boundingBox.right) / 2f
                        val cy = (obj.boundingBox.top + obj.boundingBox.bottom) / 2f
                        val horiz = when {
                            cx < w / 3f -> "left"
                            cx > 2f * w / 3f -> "right"
                            else -> "center"
                        }
                        val vert = when {
                            cy < h / 3f -> "top"
                            cy > 2f * h / 3f -> "bottom"
                            else -> "center"
                        }
                        label to "$vert-$horiz"
                    }
                    cont.resume(pairs)
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    companion object {
        private const val TAG = "HandyAi/OnDeviceVision"

        /**
         * The catalog id prefix that identifies on-device vision models.
         * ChatViewModel uses this to decide whether to route an image
         * question here or to the cloud VLM ([VisionLlm]).
         */
        const val ID_PREFIX = "ondevice-vision-"
    }
}
