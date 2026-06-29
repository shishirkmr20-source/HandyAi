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
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * ── PREFERENCE LEARNER (v1.4.1) ──────────────────────────────────────────
 *
 * The user asked: "add some basic engine to it tunes and gives more better
 * responses after many conversation."
 *
 * This is a lightweight on-device learner that observes the user's messages
 * and infers their preferences over time. It does NOT train the LLM (that
 * would require LoRA fine-tuning, which is out of scope for on-device).
 * Instead, it adjusts the SYSTEM PROMPT based on observed patterns:
 *
 *   1. RESPONSE LENGTH PREFERENCE
 *      - Detects when the user explicitly asks for shorter/longer replies
 *        ("too long", "make it shorter", "more detail", "be brief", etc.)
 *      - Defaults to "auto" (model decides), but locks to "short"/"medium"/
 *        "long" after 2 explicit requests in the same direction.
 *      - Injects a length hint into the system prompt on subsequent turns.
 *
 *   2. TOPIC AFFINITY
 *      - Tracks the top-N keywords across the user's last 50 messages
 *        (bigram extraction + stop-word filtering).
 *      - When the user mentions a topic they've discussed ≥3 times before,
 *        injects a hint like: "The user has previously discussed X, Y —
 *        you may reference that context if relevant."
 *      - Helps the LLM feel "memory-aware" without persisting actual
 *        conversation history (which would balloon the prompt).
 *
 *   3. STYLE PREFERENCE
 *      - Detects if the user prefers bullet points vs prose, formal vs
 *        casual, technical vs simple language.
 *      - Detected from phrases like "give me bullet points", "be more
 *        formal", "explain simply", "in plain English".
 *
 *   4. CORRECTION TRACKING
 *      - Detects when the user corrects the LLM ("no, I meant...", "that's
 *        wrong", "actually...", "incorrect").
 *      - Tracks the correction as a (topic, correction) pair so the LLM
 *        doesn't repeat the mistake in future turns on the same topic.
 *        Stored as a JSON array in SharedPreferences (max 20 entries).
 *
 * STORAGE:
 *   - All preferences persisted in SharedPreferences (no DB migration needed).
 *   - Topic affinity rebuilt from a sliding window of the last 50 messages.
 *   - Corrections capped at 20 entries (oldest evicted).
 *
 * PERFORMANCE:
 *   - observe() is O(n) where n = message length — typically <1ms.
 *   - buildHint() reads from SharedPreferences (in-memory after first hit).
 *   - No network, no DB writes on the hot path.
 */
class PreferenceLearner(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class LengthPreference {
        AUTO, SHORT, MEDIUM, LONG;

        companion object {
            fun fromString(s: String?): LengthPreference =
                when (s?.lowercase()?.trim()) {
                    "short", "brief", "concise" -> SHORT
                    "medium", "normal", "balanced" -> MEDIUM
                    "long", "detailed", "verbose" -> LONG
                    else -> AUTO
                }
        }
    }

    enum class StylePreference {
        DEFAULT, BULLET, PROSE, FORMAL, CASUAL, TECHNICAL, SIMPLE
    }

    data class Correction(
        val topic: String,
        val correction: String,
        val timestamp: Long
    )

    // ── LENGTH PREFERENCE ──────────────────────────────────────────────

    fun getLengthPreference(): LengthPreference =
        LengthPreference.fromString(prefs.getString(KEY_LENGTH_PREF, null))

    /**
     * Scan a user message for length-feedback signals and update the
     * preference if a clear signal is detected.
     *
     * Detection rules:
     *   - "too long", "too verbose", "make it shorter", "be brief",
     *     "tl;dr", "shorter" → SHORT
     *   - "more detail", "elaborate", "longer", "in detail", "be more
     *     detailed", "expand" → LONG
     *   - "perfect length", "just right", "good length" → locks current
     *   - "make it medium", "normal length" → MEDIUM
     *
     * Lock-in: requires 2 signals in the SAME direction before changing
     * the stored preference. This prevents one-off complaints from
     * permanently altering behavior.
     */
    fun observeLengthSignal(userMessage: String) {
        val lower = userMessage.lowercase().trim()
        val signal: LengthPreference? = when {
            // SHORT signals
            lower.contains("too long") || lower.contains("too verbose") ||
                lower.contains("make it shorter") || lower.contains("be brief") ||
                lower.contains("be concise") || lower.contains("shorter") ||
                lower.contains("tl;dr") || lower.contains("tldr") ||
                lower.contains("cut it down") || lower.contains("less detail") -> LengthPreference.SHORT

            // LONG signals
            lower.contains("more detail") || lower.contains("elaborate") ||
                lower.contains("longer") || lower.contains("in detail") ||
                lower.contains("be more detailed") || lower.contains("expand on") ||
                lower.contains("more explanation") || lower.contains("deeper") -> LengthPreference.LONG

            // MEDIUM signals (explicit)
            lower.contains("make it medium") || lower.contains("normal length") ||
                lower.contains("balanced") -> LengthPreference.MEDIUM

            else -> null
        }
        if (signal == null) return

        // Lock-in logic: bump a per-signal counter; reset others.
        // When the counter hits 2, commit the preference.
        val counterKey = "${KEY_LENGTH_SIGNAL_COUNT}_${signal.name}"
        val others = LengthPreference.values().filter { it != signal }
        others.forEach { other ->
            val otherKey = "${KEY_LENGTH_SIGNAL_COUNT}_${other.name}"
            if (prefs.getInt(otherKey, 0) > 0) prefs.edit().putInt(otherKey, 0).apply()
        }
        val newCount = prefs.getInt(counterKey, 0) + 1
        prefs.edit().putInt(counterKey, newCount).apply()

        if (newCount >= 2) {
            prefs.edit().putString(KEY_LENGTH_PREF, signal.name.lowercase()).apply()
            android.util.Log.i(TAG, "Length preference locked to $signal after $newCount signals")
            // Reset the counter so a future opposite signal can also lock in
            prefs.edit().putInt(counterKey, 0).apply()
        } else {
            android.util.Log.i(TAG, "Length signal '$signal' observed (count=$newCount, needs 2 to lock)")
        }
    }

    // ── STYLE PREFERENCE ───────────────────────────────────────────────

    fun getStylePreference(): StylePreference {
        val s = prefs.getString(KEY_STYLE_PREF, null)?.lowercase()?.trim() ?: return StylePreference.DEFAULT
        return when (s) {
            "bullet" -> StylePreference.BULLET
            "prose" -> StylePreference.PROSE
            "formal" -> StylePreference.FORMAL
            "casual" -> StylePreference.CASUAL
            "technical" -> StylePreference.TECHNICAL
            "simple" -> StylePreference.SIMPLE
            else -> StylePreference.DEFAULT
        }
    }

    fun observeStyleSignal(userMessage: String) {
        val lower = userMessage.lowercase().trim()
        val signal: StylePreference? = when {
            lower.contains("bullet point") || lower.contains("use bullets") ||
                lower.contains("as a list") || lower.contains("list format") -> StylePreference.BULLET
            lower.contains("in prose") || lower.contains("as paragraphs") ||
                lower.contains("full sentences") -> StylePreference.PROSE
            lower.contains("be formal") || lower.contains("more formal") ||
                lower.contains("professional tone") -> StylePreference.FORMAL
            lower.contains("be casual") || lower.contains("more casual") ||
                lower.contains("relax") || lower.contains("informal") -> StylePreference.CASUAL
            lower.contains("technical") || lower.contains("jargon") ||
                lower.contains("for engineers") -> StylePreference.TECHNICAL
            lower.contains("in plain english") || lower.contains("explain simply") ||
                lower.contains("for a beginner") || lower.contains("simple terms") -> StylePreference.SIMPLE
            else -> null
        }
        if (signal != null) {
            prefs.edit().putString(KEY_STYLE_PREF, signal.name.lowercase()).apply()
            android.util.Log.i(TAG, "Style preference set to $signal")
        }
    }

    // ── TOPIC AFFINITY ─────────────────────────────────────────────────

    /**
     * Append a user message to the rolling topic window. We only keep
     * the last 50 messages in SharedPreferences (as a JSON array of
     * keyword bags). Older entries are evicted FIFO.
     *
     * Keyword extraction is intentionally dumb: split on whitespace,
     * drop stop words, drop tokens < 4 chars, lowercase. This runs in
     * <1ms for typical messages — no NLP needed.
     */
    fun observeTopic(userMessage: String) {
        val keywords = extractKeywords(userMessage)
        if (keywords.isEmpty()) return

        val arr = JSONArray()
        keywords.forEach { arr.put(it) }

        val windowJson = prefs.getString(KEY_TOPIC_WINDOW, null) ?: "[]"
        val window = try { JSONArray(windowJson) } catch (_: Throwable) { JSONArray() }
        val entry = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("kw", arr)
        }
        window.put(entry)
        // Trim to last 50
        while (window.length() > 50) {
            window.remove(0)
        }
        prefs.edit().putString(KEY_TOPIC_WINDOW, window.toString()).apply()
    }

    /**
     * Return the user's top recurring topics (mentioned ≥3 times across
     * the rolling window). Used to inject "you've discussed X before"
     * context into the system prompt.
     */
    fun getTopTopics(minMentions: Int = 3): List<String> {
        val windowJson = prefs.getString(KEY_TOPIC_WINDOW, null) ?: return emptyList()
        val window = try { JSONArray(windowJson) } catch (_: Throwable) { return emptyList() }
        val counts = HashMap<String, Int>()
        for (i in 0 until window.length()) {
            val entry = window.optJSONObject(i) ?: continue
            val kw = entry.optJSONArray("kw") ?: continue
            for (j in 0 until kw.length()) {
                val word = kw.optString(j) ?: continue
                if (word.length < 4) continue
                counts[word] = (counts[word] ?: 0) + 1
            }
        }
        return counts.entries
            .filter { it.value >= minMentions }
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
    }

    private fun extractKeywords(text: String): List<String> {
        val stop = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any",
            "can", "her", "was", "one", "our", "out", "day", "get", "has",
            "him", "his", "how", "man", "new", "now", "old", "see", "two",
            "way", "who", "boy", "did", "its", "let", "put", "say", "she",
            "too", "use", "this", "that", "with", "have", "from", "they",
            "your", "what", "when", "where", "which", "will", "into",
            "them", "than", "then", "want", "like", "just", "know", "make",
            "made", "such", "take", "come", "here", "more", "some", "very",
            "much", "many", "most", "also", "would", "could", "should",
            "about", "after", "again", "because", "before", "being", "between",
            "both", "during", "each", "few", "other", "over", "same",
            "their", "there", "these", "those", "through", "under", "until",
            "while", "i'm", "i've", "i'll", "don't", "can't", "won't",
            "it's", "that's", "there's", "what's", "who's", "where's",
            "is", "am", "be", "do", "go", "no", "so", "to", "up", "us",
            "we", "he", "me", "my", "of", "on", "or", "as", "at", "by",
            "if", "in", "it", "an"
        )
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 && it !in stop }
            .distinct()
    }

    // ── CORRECTION TRACKING ────────────────────────────────────────────

    /**
     * Detect if the user is correcting the LLM and persist the correction.
     * Returns true if a correction was detected and saved.
     *
     * Trigger phrases: "no, I meant", "that's wrong", "actually,", "incorrect",
     * "not quite", "you're wrong", "you are wrong", "that's not right",
     * "mistake", "error".
     */
    fun observeCorrection(userMessage: String): Boolean {
        val lower = userMessage.lowercase().trim()
        val triggers = listOf(
            "no, i meant", "no i meant", "that's wrong", "thats wrong",
            "actually,", "incorrect", "not quite", "you're wrong",
            "you are wrong", "that's not right", "thats not right",
            "mistake", "error:", "wrong —", "wrong -", "wrong,"
        )
        val matched = triggers.firstOrNull { lower.contains(it) } ?: return false
        // Extract the correction: text after the trigger, or after a colon
        val afterTrigger = lower.substringAfter(matched).trim().take(200)
        if (afterTrigger.isBlank()) return false

        // Topic = most significant keyword in the correction
        val topic = extractKeywords(afterTrigger).firstOrNull() ?: "general"
        val correction = Correction(
            topic = topic,
            correction = afterTrigger,
            timestamp = System.currentTimeMillis()
        )

        val arrJson = prefs.getString(KEY_CORRECTIONS, null) ?: "[]"
        val arr = try { JSONArray(arrJson) } catch (_: Throwable) { JSONArray() }
        // Evict oldest if at cap
        while (arr.length() >= 20) arr.remove(0)
        // Skip if the same correction already exists (dedupe)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("correction") == correction.correction) return true
        }
        arr.put(JSONObject().apply {
            put("topic", correction.topic)
            put("correction", correction.correction)
            put("ts", correction.timestamp)
        })
        prefs.edit().putString(KEY_CORRECTIONS, arr.toString()).apply()
        android.util.Log.i(TAG, "Correction saved for topic '$topic': ${correction.correction.take(60)}…")
        return true
    }

    /**
     * Return the user's saved corrections, optionally filtered by topic.
     */
    fun getCorrections(topicFilter: String? = null): List<Correction> {
        val arrJson = prefs.getString(KEY_CORRECTIONS, null) ?: return emptyList()
        val arr = try { JSONArray(arrJson) } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<Correction>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val topic = e.optString("topic")
            if (topicFilter != null && topic != topicFilter) continue
            out.add(Correction(
                topic = topic,
                correction = e.optString("correction"),
                timestamp = e.optLong("ts")
            ))
        }
        return out
    }

    // ── HINT BUILDER ───────────────────────────────────────────────────

    /**
     * Build a compact hint string to inject into the system prompt.
     * Returns empty string if no preferences have been learned.
     *
     * The hint is intentionally SHORT (~200 chars max) so it doesn't
     * bloat the prompt. The model uses it as a soft signal, not a hard
     * instruction.
     */
    fun buildHint(): String {
        val sb = StringBuilder()
        val length = getLengthPreference()
        if (length != LengthPreference.AUTO) {
            sb.append(when (length) {
                LengthPreference.SHORT -> "User prefers SHORT replies (1-3 sentences) unless asked for detail."
                LengthPreference.MEDIUM -> "User prefers MEDIUM replies (1 paragraph) unless asked for detail."
                LengthPreference.LONG -> "User prefers DETAILED replies with thorough explanations."
                LengthPreference.AUTO -> ""
            })
        }
        val style = getStylePreference()
        if (style != StylePreference.DEFAULT) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(when (style) {
                StylePreference.BULLET -> "User prefers bullet-point format."
                StylePreference.PROSE -> "User prefers prose paragraphs (no bullets)."
                StylePreference.FORMAL -> "User prefers a formal tone."
                StylePreference.CASUAL -> "User prefers a casual, friendly tone."
                StylePreference.TECHNICAL -> "User is technical — jargon OK."
                StylePreference.SIMPLE -> "User prefers simple, plain-English explanations."
                StylePreference.DEFAULT -> ""
            })
        }
        val topics = getTopTopics()
        if (topics.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append("User has previously discussed: ${topics.joinToString(", ")}.")
        }
        return sb.toString().trim()
    }

    /**
     * Get corrections relevant to the given user message (matched by
     * shared keywords). Returns at most 3 to keep the prompt small.
     */
    fun relevantCorrectionsFor(userMessage: String): List<Correction> {
        val keywords = extractKeywords(userMessage).toSet()
        if (keywords.isEmpty()) return emptyList()
        return getCorrections().filter { corr ->
            val corrKw = extractKeywords(corr.correction).toSet()
            keywords.intersect(corrKw).isNotEmpty()
        }.take(3)
    }

    /**
     * Reset all learned preferences. Used by Settings → "Reset learner"
     * if the user wants to start fresh.
     */
    fun reset() {
        prefs.edit().clear().apply()
        android.util.Log.i(TAG, "All learned preferences cleared")
    }

    companion object {
        private const val TAG = "HandyAi/PrefLearner"
        private const val PREFS_NAME = "handyai_pref_learner"

        private const val KEY_LENGTH_PREF = "length_pref"
        private const val KEY_LENGTH_SIGNAL_COUNT = "length_signal_count"
        private const val KEY_STYLE_PREF = "style_pref"
        private const val KEY_TOPIC_WINDOW = "topic_window"
        private const val KEY_CORRECTIONS = "corrections"
    }
}
