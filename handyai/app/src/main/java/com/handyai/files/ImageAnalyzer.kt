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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Analyzes image attachments on-device using ML Kit:
 *   1. **OCR** (text recognition) — extracts any printed/handwritten
 *      Latin-script text visible in the image (signs, screenshots,
 *      scanned documents, chat captures, etc.).
 *   2. **Image labeling** — identifies the top objects/scenes/concepts
 *      detected in the image (e.g. "Plant", "Coffee", "Screenshot",
 *      "Document", "Cat", "Sky", "Smile"). Each label has a confidence
 *      score 0..1; we keep labels with confidence ≥ 0.6.
 *
 * Both ML models run fully on-device. No network calls. No API keys.
 * The bundled Latin text recognizer (~10MB) and the default image
 * labeler (~5MB) ship inside the APK.
 *
 * The combined output is a single string suitable for feeding to the
 * on-device LLM as part of the chat's system context. The format is
 * deliberately written as PLAIN PROSE rather than a structured dump:
 *
 *   ---IMAGE CONTENT START---
 *   File: photo.jpg
 *
 *   Visible text: <OCR text, or "no legible text was detected in this image">
 *
 *   What the image shows: plant, coffee, book
 *   ---IMAGE CONTENT END---
 *
 * IMPORTANT — why we do NOT include label confidence scores in the output:
 *   Small on-device models (Qwen 0.5B/1.5B, SmolLM) see decimal numbers
 *   like "(0.92)" and "(0.81)" next to labels and fixate on them,
 *   treating the confidence percentages as the topic of conversation
 *   instead of describing the image. We strip them from the prompt text.
 *   The confidence threshold (≥0.6) is still applied internally — only
 *   labels the labeler is reasonably sure about make it into the output.
 *
 * Failure modes are surfaced clearly so the LLM can tell the user
 * what went wrong instead of hallucinating.
 */
class ImageAnalyzer(private val context: Context) {

    data class Result(
        /** Combined OCR + label text, ready to splice into the system prompt. */
        val text: String,
        /** Short human-readable label, e.g. "image:photo.jpg". */
        val label: String,
        /** True if at least one of OCR/labeling produced usable content. */
        val hasContent: Boolean
    )

    /**
     * Run OCR + image labeling on the given image Uri.
     * Falls back gracefully — if OCR throws, we still try labeling;
     * if labeling throws, we still return whatever OCR got.
     */
    suspend fun analyze(uri: Uri, displayName: String): Result = withContext(Dispatchers.IO) {
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (t: Throwable) {
            null
        } ?: return@withContext Result(
            text = "[Image decode error: could not load bitmap from $displayName]",
            label = "image:$displayName",
            hasContent = false
        )

        // Run OCR + labeling in parallel-ish (sequential under IO, but each
        // is fast — typically 100–500ms per image on a modern phone).
        val ocrText = runOcr(bitmap)
        val labels = runLabeling(bitmap)

        val sb = StringBuilder()
        sb.appendLine("---IMAGE CONTENT START---")
        sb.appendLine("File: $displayName")
        sb.appendLine()
        sb.append("Visible text: ")
        if (ocrText.isBlank()) {
            sb.appendLine("no legible text was detected in this image")
        } else {
            sb.appendLine(ocrText.trim())
        }
        sb.appendLine()
        sb.append("What the image shows: ")
        if (labels.isEmpty()) {
            sb.appendLine("no clear objects or scenes were detected by the on-device labeler")
        } else {
            // Sort by confidence DESC so the most prominent labels appear
            // first — gives the LLM a natural priority order to describe.
            // Confidence scores themselves are intentionally NOT included
            // (see class kdoc for the reason).
            val sorted = labels.sortedByDescending { it.second }
            sb.appendLine(sorted.joinToString(", ") { it.first })
        }
        sb.appendLine("---IMAGE CONTENT END---")

        // Recycle the bitmap to free memory promptly
        try { bitmap.recycle() } catch (_: Throwable) {}

        Result(
            text = sb.toString(),
            label = "image:$displayName",
            hasContent = ocrText.isNotBlank() || labels.isNotEmpty()
        )
    }

    private suspend fun runOcr(bitmap: android.graphics.Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
                .addOnFailureListener { err -> cont.resume("[OCR error: ${err.message ?: err.javaClass.simpleName}]") }
        }

    private suspend fun runLabeling(bitmap: android.graphics.Bitmap): List<Pair<String, Float>> =
        suspendCancellableCoroutine { cont ->
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.6f)
                    .build()
            )
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val pairs = labels.map { it.text to it.confidence }
                    cont.resume(pairs)
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }
}
