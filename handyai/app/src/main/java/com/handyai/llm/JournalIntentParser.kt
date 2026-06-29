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

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Detects "add a journal entry" intent in the user's chat message and
 * extracts the entry title, content, and optional mood. Used so the
 * user can say things like:
 *
 *   "Add a journal entry: today was a good day, I finished my project"
 *   "Write in my journal that I'm feeling grateful for my family"
 *   "Save a journal note: feeling anxious about the presentation"
 *   "Journal: I went for a run this morning and felt great"
 *
 * and the app will actually create the entry in the database — instead
 * of the LLM just *saying* it created one (small on-device models have
 * no tool-use capability and would hallucinate the action).
 *
 * Returns null when no creation intent is detected.
 *
 * CONSERVATIVE TRIGGERING: this parser only fires on explicit
 * "create / add / write / save to journal" commands, not on casual
 * mentions of journaling. We also require at least some content text
 * after the trigger phrase.
 */
object JournalIntentParser {

    private val CREATE_TRIGGERS = listOf(
        "add a journal", "add a journal entry", "add journal entry",
        "create a journal", "create a journal entry", "create journal entry",
        "write a journal", "write a journal entry", "write in my journal",
        "write in the journal", "write journal",
        "save a journal", "save a journal entry", "save journal",
        "journal entry:", "journal:",
        "new journal entry", "new journal",
        "log in my journal", "log a journal",
        "note in my journal", "make a journal entry", "make a journal"
    )

    /** Mood keywords → standard mood label. Lets "I felt great today" map to "happy". */
    private val MOOD_KEYWORDS = listOf(
        "happy" to "happy",
        "glad" to "happy",
        "joyful" to "happy",
        "great" to "happy",
        "excited" to "excited",
        "calm" to "calm",
        "peaceful" to "calm",
        "relaxed" to "calm",
        "grateful" to "grateful",
        "thankful" to "grateful",
        "sad" to "sad",
        "down" to "sad",
        "unhappy" to "sad",
        "anxious" to "anxious",
        "worried" to "anxious",
        "nervous" to "anxious",
        "stressed" to "stressed",
        "overwhelmed" to "stressed",
        "angry" to "angry",
        "frustrated" to "angry",
        "tired" to "tired",
        "exhausted" to "tired",
        "energetic" to "energetic",
        "motivated" to "motivated",
        "productive" to "productive"
    )

    data class JournalIntent(
        val title: String,
        val content: String,
        val mood: String?
    )

    fun parse(message: String): JournalIntent? {
        val lower = message.lowercase().trim()
        if (lower.isBlank()) return null

        // Find the earliest matching trigger phrase
        var matchedTrigger: String? = null
        var matchIdx = Int.MAX_VALUE
        for (trigger in CREATE_TRIGGERS) {
            val idx = lower.indexOf(trigger)
            if (idx >= 0 && idx < matchIdx) {
                matchIdx = idx
                matchedTrigger = trigger
            }
        }
        if (matchedTrigger == null) return null

        // Extract the text after the trigger
        val afterTrigger = message.substring(matchIdx + matchedTrigger.length).trim()
            .trimStart(':', '-', '—', ' ', '"', '\'')

        if (afterTrigger.isBlank()) return null

        // Split into title (first sentence or up to ~50 chars) and content
        val (title, content) = splitTitleAndContent(afterTrigger)
        if (content.isBlank() && title.isBlank()) return null

        val mood = detectMood(message)

        return JournalIntent(
            title = title.take(120),
            content = content.ifBlank { afterTrigger }.take(4000),
            mood = mood
        )
    }

    /**
     * Split the entry text into a short title + body content.
     * Heuristic: if the text starts with a short phrase followed by a
     * colon, comma, or newline, treat that as the title.
     */
    private fun splitTitleAndContent(text: String): Pair<String, String> {
        // Try colon split — "Title: rest of entry"
        val colonIdx = text.indexOf(':')
        if (colonIdx in 1..80) {
            val title = text.substring(0, colonIdx).trim()
            val body = text.substring(colonIdx + 1).trim()
            if (title.isNotBlank() && body.isNotBlank()) {
                return title to body
            }
        }
        // Try newline split — "First line\n rest"
        val nlIdx = text.indexOf('\n')
        if (nlIdx in 1..80) {
            val title = text.substring(0, nlIdx).trim()
            val body = text.substring(nlIdx + 1).trim()
            if (title.isNotBlank() && body.isNotBlank()) {
                return title to body
            }
        }
        // Try comma split for short title
        val commaIdx = text.indexOf(',')
        if (commaIdx in 4..60) {
            val title = text.substring(0, commaIdx).trim()
            val body = text.substring(commaIdx + 1).trim()
            if (title.isNotBlank() && body.isNotBlank() && title.length <= 60) {
                return title to body
            }
        }
        // Fallback: use first 6-8 words as title, rest as content
        val words = text.split(Regex("\\s+"))
        return if (words.size > 8) {
            val titleWords = words.take(6)
            val title = titleWords.joinToString(" ").trimEnd(',', '.', ':', ';')
            val content = words.drop(6).joinToString(" ")
            title to content
        } else {
            // Whole thing is the content; no title
            "" to text
        }
    }

    private fun detectMood(message: String): String? {
        val lower = message.lowercase()
        // First match wins
        for ((keyword, mood) in MOOD_KEYWORDS) {
            // Match as a word boundary
            val pattern = "\\b" + Regex.escape(keyword) + "\\b"
            if (Regex(pattern).containsMatchIn(lower)) {
                return mood.replaceFirstChar { it.uppercase() }
            }
        }
        // Explicit "mood: X" pattern
        val moodTagIdx = lower.indexOf("mood:")
        if (moodTagIdx >= 0) {
            val rest = message.substring(moodTagIdx + 5).trim().split(Regex("[,.;\\n]"))[0].trim()
            if (rest.length in 2..30) {
                return rest.replaceFirstChar { it.uppercase() }
            }
        }
        // "feeling X" / "felt X" pattern
        val feelingPatterns = listOf("feeling ", "felt ", "i feel ", "i'm feeling ", "i am feeling ")
        for (pattern in feelingPatterns) {
            val idx = lower.indexOf(pattern)
            if (idx >= 0) {
                val rest = message.substring(idx + pattern.length).trim().split(Regex("[,.;\\n]"))[0].trim()
                // Strip leading "like a" / "like an" / "very" / "so" / "really"
                val cleaned = rest.removePrefix("like a ").removePrefix("like an ")
                    .removePrefix("very ").removePrefix("so ").removePrefix("really ")
                    .trim()
                if (cleaned.length in 2..30) {
                    return cleaned.replaceFirstChar { it.uppercase() }
                }
            }
        }
        return null
    }

    /** Today's date in ISO format, used as default title fragment when none given. */
    fun todayLabel(): String = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
}
