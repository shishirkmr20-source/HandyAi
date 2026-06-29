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

/**
 * Pre-configured models available for download.
 *
 * All URLs point to Hugging Face and have been verified to return HTTP 302
 * (publicly downloadable, no auth required) as of 2026-06-29.
 *
 * Size = download size on disk. RAM = approximate runtime memory.
 *
 * paramCountB = parameter count in billions. Used to detect "small" models
 *   (≤0.7B) that need a different prompt strategy — see ChatViewModel.
 *
 * modelType = which MediaPipe task this model runs on:
 *   - LLM        → MediaPipe LLM Inference API (text generation)
 *   - IMAGE_GEN  → Cloud text-to-image (Pollinations.ai). Activated
 *                  separately and invoked from chat via "/draw <prompt>"
 *                  or "/image <prompt>".
 *
 * v1.4.5: VISION_LITERTLM removed. The on-device LiteRT-LM runtime
 *   (alpha05) crashed natively on arm64 — see worklog. Vision is now
 *   a CLOUD-ONLY pipeline (HuggingFace BLIP-large + ML Kit OCR + ML
 *   Kit labels + OCR.space fallback) wired through CloudImageAnalyzer,
 *   invoked from FileTextExtractor on every image attachment. No model
 *   download, no native crash.
 */
enum class ModelType { LLM, IMAGE_GEN }

data class ModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val sizeMb: Int,
    val ramMb: Int,
    val paramCountB: Double,
    val recommended: Boolean = false,
    val modelType: ModelType = ModelType.LLM
)

object ModelCatalog {

    val ALL: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen2.5-0.5b-q8",
            displayName = "Qwen 2.5 0.5B (Q8)",
            description = "Smallest model. Fast, works on any phone. Best for first try.",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeMb = 521,
            ramMb = 900,
            paramCountB = 0.5,
            recommended = true
        ),
        // ── v1.4.5: SmolLM 135M REMOVED ───────────────────────────────
        // User explicitly asked to remove SmolLM fully. The 135M model
        // produced low-quality replies, ignored system prompts, and emitted
        // XML-style tags that needed dedicated stripping code. The
        // defensive tag-stripping in MarkdownParser.kt / TtsSpeechSanitizer.kt
        // is kept (other tiny models can emit the same tags) but the model
        // itself is gone from the catalog and cannot be downloaded.
        ModelSpec(
            id = "qwen2.5-1.5b-q8",
            displayName = "Qwen 2.5 1.5B (Q8)",
            description = "Good balance of quality and speed. Needs ~2GB RAM.",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeMb = 1524,
            ramMb = 2200,
            paramCountB = 1.5
        ),
        ModelSpec(
            id = "phi-4-mini-q8",
            displayName = "Phi-4 Mini (Q8)",
            description = "High-quality but heavy. Needs 4GB+ RAM. Flagship phones only.",
            downloadUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeMb = 3762,
            ramMb = 4500,
            paramCountB = 3.8
        ),
        // ───────────────────────────────────────────────────────────────────
        // v1.4.5: FastVLM-0.5B (.litertlm) entry REMOVED.
        //
        // The on-device LiteRT-LM alpha05 runtime crashed with a native
        // SIGSEGV inside eng.initialize() on arm64-v8a devices, killing
        // the app whenever the user tried to activate the model. Rather
        // than ship a crash, vision is now a CLOUD-ONLY pipeline:
        //
        //   1. User attaches an image to any text-model chat
        //   2. FileTextExtractor routes images to ImageAnalyzer (ML Kit
        //      OCR + image labeling, on-device, offline) AND CloudImageAnalyzer
        //      (HuggingFace BLIP-large natural-language caption + OCR.space
        //      cloud OCR fallback when ML Kit returns empty)
        //   3. The combined "image content" block is inlined into the user's
        //      message and the text LLM answers based on it
        //
        // This works on every device, never crashes, and needs no model
        // download. The LiteRT-LM dependency has also been stripped from
        // build.gradle.kts to shrink the APK and eliminate the native
        // crash surface entirely.
        // ───────────────────────────────────────────────────────────────────
    )

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }
}
