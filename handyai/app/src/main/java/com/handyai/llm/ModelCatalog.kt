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
 * Pre-configured models available in the Models page.
 *
 * Three categories:
 *
 *   1. LLM         → on-device text generation (MediaPipe .task file)
 *                    Downloaded once, runs fully offline.
 *   2. VISION      → cloud multimodal vision-language model (HF Inference API).
 *                    No download; activated instantly; used when user asks
 *                    a question about an attached image.
 *   3. IMAGE_GEN   → cloud text-to-image (Pollinations.ai).
 *                    No download; activated instantly; invoked from chat
 *                    via "/draw <prompt>" or "/image <prompt>".
 *
 * v1.4.7: VISION category added. Previously vision was an invisible cloud
 *   pipeline (BLIP caption + OCR) with no model picker. Users can now
 *   choose which VLM answers their image questions: Llama-3.2-11B-Vision
 *   (best), LLaVA-1.6-Mistral (balanced), LLaVA-1.5-7B (lightweight).
 *
 * v1.4.7: IMAGE_GEN entries promoted to first-class model cards. Users
 *   can pick Flux (default), Flux-Realism, Turbo, Flux-Anime, Flux-3D as
 *   their default image generator — no more typing flags every time.
 *
 * v1.4.5: SmolLM 135M + FastVLM-0.5B (.litertlm) entries REMOVED.
 *   SmolLM produced low-quality output; FastVLM crashed natively.
 */
enum class ModelType { LLM, VISION, IMAGE_GEN }

/**
 * @param cloudModelId  For VISION/IMAGE_GEN cloud models: the upstream
 *   model identifier sent to the API (HuggingFace model id for VISION,
 *   Pollinations model id for IMAGE_GEN). Ignored for LLM (which uses
 *   `downloadUrl` instead).
 * @param downloadUrl   For LLM only: HuggingFace .task file URL.
 *   Empty string for cloud models (no download needed).
 * @param sizeMb        Download size in MB. 0 for cloud models.
 * @param ramMb         Runtime RAM in MB. 0 for cloud models (the model
 *   runs on someone else's server — only matters for our network buffer).
 * @param paramCountB   Parameter count in billions. 0 for cloud models
 *   (we don't pay for their memory). Used by ChatViewModel to detect
 *   "small" on-device LLMs (≤0.7B) that need a different prompt strategy.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val sizeMb: Int,
    val ramMb: Int,
    val paramCountB: Double,
    val recommended: Boolean = false,
    val modelType: ModelType = ModelType.LLM,
    val cloudModelId: String = ""
)

object ModelCatalog {

    val ALL: List<ModelSpec> = listOf(
        // ─── ON-DEVICE TEXT LLMs ──────────────────────────────────────────
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

        // ─── CLOUD VISION MODELS (v1.4.7) ─────────────────────────────────
        // These answer user questions about attached images. They take the
        // image + the user's question and return a natural-language answer
        // — not just a caption. The active vision model is invoked by
        // ChatViewModel whenever a user attaches an image and asks about it
        // (see VisionLlm.kt).
        //
        // All three run on HuggingFace's free anonymous Inference API.
        // They have independent cold-start states, so a cold Llama-3.2-Vision
        // doesn't imply a cold LLaVA — VisionLlm tries them in order and
        // falls back to the next on cold-start failure.
        ModelSpec(
            id = "vision-llama-3.2-11b",
            displayName = "Llama 3.2 11B Vision",
            description = "Strongest open VLM. Best for complex questions about images " +
                "(reading text, identifying objects, describing scenes). " +
                "Cloud-only — no download. Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 11.0,
            recommended = true,
            modelType = ModelType.VISION,
            cloudModelId = "meta-llama/Llama-3.2-11B-Vision-Instruct"
        ),
        ModelSpec(
            id = "vision-llava-1.6-mistral",
            displayName = "LLaVA 1.6 Mistral 7B",
            description = "Balanced VLM — fast, reliable, usually warm on HuggingFace. " +
                "Good default if Llama 3.2 Vision is cold-starting. " +
                "Cloud-only. Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 7.0,
            modelType = ModelType.VISION,
            cloudModelId = "llava-hf/llava-v1.6-mistral-7b-hf"
        ),
        ModelSpec(
            id = "vision-llava-1.5-7b",
            displayName = "LLaVA 1.5 7B",
            description = "Older LLaVA — lightweight fallback. Works when the newer " +
                "VLMs are cold-starting. Cloud-only. Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 7.0,
            modelType = ModelType.VISION,
            cloudModelId = "llava-hf/llava-1.5-7b-hf"
        ),

        // ─── CLOUD IMAGE GENERATION MODELS (v1.4.7) ──────────────────────
        // These power /draw <prompt>. Pollinations.ai hosts several free
        // Flux variants — the user picks which one is their default from
        // the Models page. They can still override per-draw with --turbo /
        // --realism / --anime / --3d flags.
        ModelSpec(
            id = "imggen-flux",
            displayName = "Flux (default)",
            description = "Best general-purpose image model. High quality, 5–15s " +
                "generation time. Cloud-only (Pollinations.ai). Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 12.0,
            recommended = true,
            modelType = ModelType.IMAGE_GEN,
            cloudModelId = "flux"
        ),
        ModelSpec(
            id = "imggen-flux-realism",
            displayName = "Flux Realism",
            description = "Photorealistic variant of Flux. Best for portraits, " +
                "landscapes, product shots. Cloud-only. Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 12.0,
            modelType = ModelType.IMAGE_GEN,
            cloudModelId = "flux-realism"
        ),
        ModelSpec(
            id = "imggen-turbo",
            displayName = "Turbo (fast)",
            description = "Fastest image model (~3–5s). Lower quality but great for " +
                "quick sketches and drafts. Cloud-only. Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 3.0,
            modelType = ModelType.IMAGE_GEN,
            cloudModelId = "turbo"
        ),
        ModelSpec(
            id = "imggen-flux-anime",
            displayName = "Flux Anime",
            description = "Anime / manga art style. Best for character illustrations. " +
                "Cloud-only. Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 12.0,
            modelType = ModelType.IMAGE_GEN,
            cloudModelId = "flux-anime"
        ),
        ModelSpec(
            id = "imggen-flux-3d",
            displayName = "Flux 3D",
            description = "3D-render art style. Good for product mockups, game assets, " +
                "isometric scenes. Cloud-only. Requires internet.",
            downloadUrl = "",
            sizeMb = 0,
            ramMb = 0,
            paramCountB = 12.0,
            modelType = ModelType.IMAGE_GEN,
            cloudModelId = "flux-3d"
        )
    )

    /** All on-device text LLMs. */
    val LLM_MODELS: List<ModelSpec> = ALL.filter { it.modelType == ModelType.LLM }

    /** All cloud vision-language models. */
    val VISION_MODELS: List<ModelSpec> = ALL.filter { it.modelType == ModelType.VISION }

    /** All cloud image-generation models. */
    val IMAGE_GEN_MODELS: List<ModelSpec> = ALL.filter { it.modelType == ModelType.IMAGE_GEN }

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }
}
