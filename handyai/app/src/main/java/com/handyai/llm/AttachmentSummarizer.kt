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
 * Map-reduce summarization pipeline for large documents on small on-device LLMs.
 *
 * WHY THIS EXISTS
 * ───────────────
 * Small models (Qwen 0.5B, 1.5B) have two problems when asked to summarize
 * an attached document:
 *
 *   1. They ignore the system prompt. Confirmed by HuggingFace:
 *      https://huggingface.co/Qwen/Qwen2.5-0.5B/discussions/6
 *      "The model seems to ignore my system prompt, even if I make it very
 *       explicit."
 *
 *   2. They hallucinate from the filename. When the attachment label says
 *      "Class_11_History_English_Medium-2024_Edition-www.tntextbooks.in.pdf",
 *      the model generates a plausible-sounding summary from the FILENAME
 *      alone without ever reading the extracted text.
 *
 * The root cause is that 3,500 chars of document text buried inside a long
 * system prompt (alongside habit context, journal context, tool-use
 * preamble, web search results) is invisible to a small model's limited
 * attention. It latches onto the most prominent short string it can find —
 * the filename — and hallucinates from that.
 *
 * THE FIX: MAP-REDUCE
 * ────────────────────
 * Instead of dumping 3,500 chars into the system prompt and hoping the
 * model reads them, we:
 *
 *   MAP stage:
 *     Split the document into ~800-char chunks.
 *     For each chunk, call the model with a TINY, focused prompt:
 *       "Summarize this passage in 2 sentences:\n\n{chunk}"
 *     The model can't avoid reading the content because it's the ONLY
 *     thing in the prompt — no system prompt, no filename, no habits.
 *
 *   REDUCE stage:
 *     Concatenate all chunk summaries.
 *     Call the model with:
 *       "Combine these section summaries into one coherent summary:\n\n{all}"
 *
 * This works because each individual call has a tiny context window and
 * the model is forced to attend to the content. The final combined
 * summary is grounded in actual document text rather than the filename.
 *
 * PERFORMANCE
 * ───────────
 * A 16,000-char document → 20 chunks → 20 map calls + 1 reduce call.
 * On Qwen 0.5B each call takes ~3-5 seconds → total ~60-100 seconds.
 * This is slow but CORRECT, which is better than fast and wrong.
 *
 * We show a progress status ("Summarizing… part 3/20") so the user
 * knows the app hasn't frozen.
 */
class AttachmentSummarizer(private val llm: LlmEngine) {

    /**
     * Run map-reduce summarization on [fullText].
     *
     * @param fullText The extracted document text (up to ~16,000 chars).
     * @param userQuestion The user's original question (used to guide the
     *                     reduce stage — e.g. "focus on causes" vs. a
     *                     generic summary).
     * @param onProgress Called with (currentIndex, totalChunks) so the UI
     *                   can show "Summarizing… part 3/20".
     * @return The final combined summary, or an error message.
     */
    suspend fun summarize(
        fullText: String,
        userQuestion: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<String> {
        if (fullText.isBlank()) {
            return Result.failure(IllegalStateException("Document text is empty"))
        }

        // ── MAP: split into chunks and summarize each ──────────────────
        val chunks = chunkText(fullText, CHUNK_SIZE)
        val totalChunks = chunks.size
        android.util.Log.i(TAG, "Map-reduce: $totalChunks chunks of ~${CHUNK_SIZE} chars each")

        val chunkSummaries = mutableListOf<String>()
        chunks.forEachIndexed { index, chunk ->
            onProgress(index + 1, totalChunks)
            val summary = summarizeChunk(chunk, index + 1, totalChunks)
            if (summary.isNotBlank()) {
                chunkSummaries.add(summary)
            }
        }

        if (chunkSummaries.isEmpty()) {
            return Result.failure(IllegalStateException("All chunk summaries were empty"))
        }

        // ── REDUCE: combine chunk summaries into final summary ─────────
        onProgress(totalChunks, totalChunks)
        val combined = chunkSummaries.joinToString("\n\n") { "• $it" }
        val finalSummary = reduceSummaries(combined, userQuestion, totalChunks)
        return Result.success(finalSummary)
    }

    /**
     * Split [text] into chunks of approximately [size] chars, breaking at
     * paragraph or sentence boundaries when possible to avoid cutting
     * mid-sentence.
     */
    private fun chunkText(text: String, size: Int): List<String> {
        if (text.length <= size) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + size, text.length)
            if (end == text.length) {
                chunks.add(text.substring(start, end).trim())
                break
            }
            // Try to break at a paragraph boundary (double newline)
            // or sentence boundary (. ! ?) within the last 20% of the chunk
            val searchStart = start + (size * 0.8).toInt()
            val searchRegion = text.substring(searchStart, end)
            val breakPoint = searchRegion.indexOf("\n\n").let { paraIdx ->
                if (paraIdx >= 0) searchStart + paraIdx + 2
                else {
                    // Fall back to sentence boundary
                    val sentIdx = searchRegion.lastIndexOfAny(charArrayOf('.', '!', '?'))
                    if (sentIdx >= 0) searchStart + sentIdx + 1
                    else end
                }
            }
            chunks.add(text.substring(start, breakPoint).trim())
            start = breakPoint
        }
        return chunks.filter { it.isNotBlank() }
    }

    /**
     * Summarize a single chunk. Uses a TINY prompt with NO system prompt
     * and NO filename — just the chunk content and a direct instruction.
     * This forces even 0.5B models to actually read the text.
     */
    private suspend fun summarizeChunk(
        chunk: String,
        chunkNum: Int,
        totalChunks: Int
    ): String {
        // The prompt is deliberately minimal: no system prompt, no role
        // preamble, no context about the app. Just the instruction + the
        // text. This is the ONLY way small models will attend to the
        // content instead of hallucinating.
        val prompt = buildString {
            append("Summarize this passage in 2-3 sentences. ")
            append("Focus on the key facts, topics, and main ideas. ")
            append("Do not mention that you are summarizing — just write the summary.\n\n")
            append("--- PASSAGE START ---\n")
            append(chunk)
            append("\n--- PASSAGE END ---\n\n")
            append("Summary:")
        }

        // Use the LlmEngine directly with an empty system prompt.
        // history = single user turn = the prompt itself.
        val result = llm.generateReplyStream(
            history = listOf("user" to prompt),
            systemPrompt = "",
            onChunk = { /* discard — we only want the final string */ }
        )

        return result.fold(
            onSuccess = { cleanSummary(it) },
            onFailure = {
                android.util.Log.w(TAG, "Chunk $chunkNum/$totalChunks failed: ${it.message}")
                ""
            }
        )
    }

    /**
     * Combine all chunk summaries into a final coherent summary.
     */
    private suspend fun reduceSummaries(
        combinedSummaries: String,
        userQuestion: String,
        totalChunks: Int
    ): String {
        val focusHint = if (userQuestion.isNotBlank()) {
            " The user asked: \"$userQuestion\". Focus your summary on what's relevant to that question if possible."
        } else ""

        val prompt = buildString {
            append("Below are $totalChunks section summaries from a document.")
            append(" Combine them into one coherent summary of 4-6 sentences.")
            append(focusHint)
            append(" Write in a natural, informative style.\n\n")
            append("--- SECTION SUMMARIES ---\n")
            append(combinedSummaries)
            append("\n--- END ---\n\n")
            append("Combined summary:")
        }

        val result = llm.generateReplyStream(
            history = listOf("user" to prompt),
            systemPrompt = "",
            onChunk = { /* discard */ }
        )

        return result.fold(
            onSuccess = { cleanSummary(it) },
            onFailure = {
                // If the reduce stage fails, just concatenate the raw
                // chunk summaries — still better than hallucinating.
                android.util.Log.w(TAG, "Reduce stage failed: ${it.message}")
                combinedSummaries.replace("• ", "").take(2000)
            }
        )
    }

    /**
     * Clean up model output: strip leading/trailing whitespace, remove
     * any "Summary:" prefix the model might have echoed, trim excessive
     * newlines.
     */
    private fun cleanSummary(raw: String): String {
        var s = raw.trim()
        // Remove common prefixes the model might echo
        val prefixes = listOf("Summary:", "Summary：", "Here is the summary:", "The summary is:")
        prefixes.forEach { p ->
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim()
            }
        }
        // Collapse multiple newlines into one
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s
    }

    companion object {
        private const val TAG = "HandyAi/AttachmentSummarizer"

        /**
         * Chunk size for the map stage. ~800 chars ≈ ~200 tokens, which
         * leaves plenty of room in a 2048-token context window for the
         * instruction + the model's output.
         *
         * Smaller chunks = more model calls = slower but each call is
         * more focused. 800 is a good balance — small enough that the
         * model can't avoid reading it, large enough that we don't make
         * 50+ calls for a typical document.
         */
        private const val CHUNK_SIZE = 800
    }
}
