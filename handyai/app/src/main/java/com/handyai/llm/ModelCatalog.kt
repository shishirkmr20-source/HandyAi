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
 *   - IMAGE_GEN  → MediaPipe Image Generator API (text-to-image via
 *                  Stable Diffusion). Activated separately and invoked
 *                  from chat via "/draw <prompt>" or "/image <prompt>".
 */
enum class ModelType { LLM, IMAGE_GEN, VISION_LITERTLM }

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
        ModelSpec(
            id = "smollm-135m-q8",
            displayName = "SmolLM 135M (Q8)",
            description = "Tiny demo model. Replies are basic but downloads in seconds.",
            downloadUrl = "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeMb = 159,
            ramMb = 500,
            paramCountB = 0.135
        ),
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
        // VISION-LANGUAGE MODEL (v1.4.0)
        // ───────────────────────────────────────────────────────────────────
        // Apple FastVLM-0.5B from litert-community — a true multimodal VLM
        // that processes images natively (no OCR/labels needed). Hosted as
        // a .litertlm file (LiteRT-LM runtime format, NOT MediaPipe .task).
        // Free download, no auth required (confirmed via curl HEAD).
        //
        // When the user activates this model:
        //   1. The .litertlm file is downloaded (same flow as other models)
        //   2. LiteRtlmEngine loads it (NOT the MediaPipe LlmEngine)
        //   3. Image attachments are passed DIRECTLY to the model as
        //      Content.ImageBytes — the model's vision encoder reads the
        //      pixels and produces a real natural-language answer
        //   4. KV cache stays warm across turns (PocketPal-style speed)
        //
        // Size: 1.1GB (generic) — works on any device with ≥2GB free RAM.
        // For Qualcomm Snapdragon 8 Gen 4 / 8 Elite phones, the qualcomm-
        // specific .litertlm files use NPU acceleration (much faster) but
        // we ship the generic file for broad compatibility.
        ModelSpec(
            id = "fastvlm-0.5b",
            displayName = "Apple FastVLM 0.5B (Vision)",
            description = "True vision-language model. Sees images natively — no OCR needed. Best for image Q&A.",
            downloadUrl = "https://huggingface.co/litert-community/FastVLM-0.5B/resolve/main/FastVLM-0.5B.litertlm",
            sizeMb = 1103,
            ramMb = 2500,
            paramCountB = 0.5,
            recommended = false,
            modelType = ModelType.VISION_LITERTLM
        ),
        // ───────────────────────────────────────────────────────────────────
        // NOTE: Image generation is now a BUILT-IN cloud feature (Pollinations.ai).
        // No model download needed — just type /draw <prompt> in any chat.
        // The previous "Stable Diffusion 1.5" catalog entry was removed because:
        //   1. The HuggingFace URL returned HTTP 401 (repo is gated)
        //   2. MediaPipe doesn't ship a public ImageGenerator Android API
        //      (the class is experimental, omitted from the released AAR)
        // See ImageGenEngine.kt for the cloud-based implementation.
        // ───────────────────────────────────────────────────────────────────
    )

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }
}
