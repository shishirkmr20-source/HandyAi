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
 * mentions of journaling.
 *
 * ── SMART EXTRACTION (v1.3.1) ─────────────────────────────────────────
 * Earlier versions used naive comma / first-N-words splitting, which
 * produced embarrassing titles like "for me", "about how", "I want to"
 * — the parser was including conversational filler from the user's chat
 * message in the saved title and content.
 *
 * The new pipeline:
 *   1. Strip the trigger phrase
 *   2. Strip leading conversational filler ("for me", "about", "please",
 *      "I want to", "saying that", "to remember that", etc.) — iteratively,
 *      because users often chain them ("please, for me, ...")
 *   3. Strip trailing conversational tail ("please", "thanks", "ok?",
 *      "that would be great", etc.)
 *   4. Look for an EXPLICIT title separator — `Title: body`, `Title - body`,
 *      `Title | body` — only when the part before the separator looks like a
 *      title (short, ≤ 60 chars, doesn't end with a verb-ish word).
 *      We no longer split on commas, because natural sentences have
 *      commas that aren't title/content boundaries.
 *   5. If no explicit separator, the whole cleaned text becomes the CONTENT
 *      and we auto-generate a short title from the first 5-7 words
 *      (truncated at a word boundary, max 50 chars, no trailing punctuation).
 *   6. If the cleaned text is very short (≤ 6 words), use it as the title
 *      only with empty content — the user can flesh it out in the editor.
 *
 * Mood detection runs on the FULL original message (so "I felt great today"
 * still maps to "happy" even when "great" doesn't appear in the cleaned
 * content).
 */
object JournalIntentParser {

    private val CREATE_TRIGGERS = listOf(
        "add a journal", "add a journal entry", "add journal entry",
        "create a journal", "create a journal entry", "create journal entry",
        "write a journal", "write a journal entry", "write in my journal",
        "write in the journal", "write journal",
        "save a journal", "save a journal entry", "save journal",
        "save a journal note", "save a note in my journal",
        "journal entry:", "journal:",
        "new journal entry", "new journal",
        "log in my journal", "log a journal",
        "note in my journal", "make a journal entry", "make a journal"
    )

    /**
     * Leading filler phrases that users prepend to the actual entry text
     * after the trigger. These are stripped iteratively (longest match
     * first) so chained fillers like "please, for me, I want to..." are
     * fully removed before title/content extraction.
     *
     * ── WORD BOUNDARY REQUIREMENT ────────────────────────────────────
     * Each filler must be followed by a word boundary (space, punctuation,
     * or end-of-string) to match. This prevents "that i" from matching
     * "that I'm" (which would strip only "that I", leaving "'m feeling...").
     *
     * ── WHAT NOT TO INCLUDE ──────────────────────────────────────────
     * We deliberately do NOT include:
     *   - "today" / "today that" / "today saying" — "today" is a legitimate
     *     content word ("today was a good day"). Stripping it produced
     *     titles like "Was a good day" with no leading word.
     *   - "that i" / "that today" — too aggressive, matches "that I'm"
     *     and leaves a dangling "'m". "that" alone is sufficient.
     */
    private val LEADING_FILLER = listOf(
        // Polite / conversational openers
        "please could you", "could you please", "can you please",
        "would you please", "could you", "can you", "would you",
        "please", "kindly", "just",
        // "I want to" family
        "i want you to", "i want to", "i'd like to", "i would like to",
        "i'd like you to", "i want",
        // Content-pointing phrases — these introduce the actual entry
        // text but are not part of the entry itself
        "to remember that", "to note that", "to record that",
        "to say that", "saying that", "saying",
        "that says", "with the text", "with the content",
        "with the message", "with the note",
        "saying the following", "with the following",
        // Preposition fillers (very common — "add a journal entry ABOUT...")
        // We keep ONLY the bare preposition and let the iterative loop +
        // word-boundary check handle chained fillers. Previously we had
        // "about my", "about the", "about how" etc. as separate entries,
        // but that was too aggressive: "Add a journal entry about my
        // morning routine" had "about my" stripped, losing the possessive
        // "my" which belongs to the content ("my morning routine" reads
        // naturally as a journal entry; "morning routine" alone is generic).
        // Now only "about" is stripped, leaving "my morning routine".
        "about",
        "for me to", "for me",
        "that",  // single "that" — covers "that I felt..." etc.
        "how",   // single "how" — iterative stripping handles "how I felt"
        "to my journal saying", "in my journal saying",
        "to my journal that", "in my journal that",
        "to my journal", "in my journal",
        "saying that today", "saying that"
    )

    /**
     * Trailing filler that users append after the actual entry text.
     * Stripped so the saved content doesn't end with "please" or "thanks".
     */
    private val TRAILING_FILLER = listOf(
        "please", "thanks", "thank you", "thx", "ty",
        "ok?", "okay?", "ok", "okay",
        "will you", "would you", "could you",
        "that would be great", "that would be nice",
        "if you can", "if you could", "if you don't mind",
        "i'd appreciate it", "i would appreciate it",
        "for me", "please?"
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

        // Find the earliest matching trigger phrase.
        //
        // ── LONGEST-MATCH-WINS TIEBREAKER ─────────────────────────────
        // When two triggers both match at the same index (e.g.
        // "add a journal" and "add a journal entry" both start at idx 0),
        // we MUST pick the LONGER one. Otherwise we'd strip only
        // "add a journal" (14 chars), leaving "entry: today was a good day"
        // — and the leftover `:` would be misinterpreted as a title
        // separator, producing the title "Entry" instead of the actual
        // entry text. This was a real bug in v1.3.0.
        //
        // Implementation: sort triggers by length descending so the first
        // match found at any given index is the longest one.
        var matchedTrigger: String? = null
        var matchIdx = Int.MAX_VALUE
        for (trigger in CREATE_TRIGGERS.sortedByDescending { it.length }) {
            val idx = lower.indexOf(trigger)
            if (idx >= 0 && idx < matchIdx) {
                matchIdx = idx
                matchedTrigger = trigger
            }
        }
        if (matchedTrigger == null) return null

        // Extract the text after the trigger
        var afterTrigger = message.substring(matchIdx + matchedTrigger.length).trim()
            .trimStart(':', ',', ';', '-', '—', ' ', '"', '\'', '“', '”')
            .trim()

        if (afterTrigger.isBlank()) return null

        // ── Strip leading filler phrases iteratively ──────────────────
        // Users often chain fillers ("please, for me, I want to..."), so
        // we loop until no more filler matches at the start.
        afterTrigger = stripLeadingFiller(afterTrigger)

        // ── Strip trailing filler ─────────────────────────────────────
        afterTrigger = stripTrailingFiller(afterTrigger)

        if (afterTrigger.isBlank()) return null

        // ── Try explicit "Title: body" / "Title - body" / "Title | body" ──
        // Only treat as a title separator if the prefix is short (≤ 60 chars)
        // and doesn't look like a sentence (no period inside it). This
        // prevents "I went to the store: bought milk" from being split
        // (that's a sentence with a colon, not a title).
        val (title, content) = tryExplicitTitleSplit(afterTrigger)

        val mood = detectMood(message)

        return when {
            // Explicit "Title: body" found
            title.isNotBlank() && content.isNotBlank() -> JournalIntent(
                title = cleanTitle(title).take(120),
                content = content.trim().take(4000),
                mood = mood
            )
            // No explicit separator — auto-generate title from content
            content.isNotBlank() -> {
                val autoTitle = autoTitleFromContent(content)
                JournalIntent(
                    title = autoTitle.take(120),
                    content = content.trim().take(4000),
                    mood = mood
                )
            }
            // Very short text — use as title only, empty content
            title.isNotBlank() -> JournalIntent(
                title = cleanTitle(title).take(120),
                content = "",
                mood = mood
            )
            else -> null
        }
    }

    /**
     * Iteratively strip leading filler phrases. Longest-match-first per
     * iteration so "for me to remember that" is stripped as one unit
     * before "for me" can match.
     *
     * Also strips leading punctuation/quotes between iterations so
     * `"please, for me, about..."` fully resolves to the real content.
     *
     * WORD BOUNDARY CHECK: a filler only matches if the character right
     * after it is a non-letter (space, punctuation, digit, or end-of-
     * string). This prevents "that i" from matching "that I'm" — the
     * char after "that i" would be "'", which IS a letter-adjacent char
     * we treat as a boundary, BUT we removed "that i" from the list
     * anyway. The boundary check is mainly to prevent shorter fillers
     * like "how" from matching "however" or "about" from matching "aboutrage".
     */
    private fun stripLeadingFiller(text: String): String {
        var current = text.trim()
        var changed = true
        var iterations = 0
        while (changed && iterations < 10) {
            changed = false
            iterations++
            val lower = current.lowercase()
            for (filler in LEADING_FILLER.sortedByDescending { it.length }) {
                if (lower.startsWith(filler)) {
                    // Word boundary check: char after filler must be
                    // non-letter (or end-of-string).
                    val nextIdx = filler.length
                    val nextChar: Char? = current.getOrNull(nextIdx)
                    val isBoundary = nextChar == null ||
                        !nextChar.isLetter()
                    if (!isBoundary) continue
                    current = current.substring(filler.length)
                        .trimStart(',', '.', ':', ';', '-', '—', ' ', '"', '\'', '“', '”')
                        .trim()
                    changed = true
                    break
                }
            }
        }
        return current
    }

    /**
     * Strip trailing filler like "please", "thanks", "ok?".
     */
    private fun stripTrailingFiller(text: String): String {
        var current = text.trim()
        var changed = true
        var iterations = 0
        while (changed && iterations < 5) {
            changed = false
            iterations++
            val lower = current.lowercase()
            for (filler in TRAILING_FILLER.sortedByDescending { it.length }) {
                if (lower.endsWith(filler)) {
                    current = current.substring(0, current.length - filler.length)
                        .trimEnd(',', '.', ':', ';', '-', '—', ' ', '"', '\'', '“', '”', '?', '!')
                        .trim()
                    changed = true
                    break
                }
            }
        }
        return current
    }

    /**
     * Look for an EXPLICIT title separator: `Title: body`, `Title - body`,
     * `Title | body`. Only matches when:
     *   - The part before the separator is ≤ 60 chars
     *   - The part before the separator doesn't contain a sentence-ending
     *     period (so "I went to the store: bought milk" is NOT split —
     *     that's a sentence with a colon, not a title)
     *   - Both parts are non-blank after trimming
     *
     * Returns (title, content). If no explicit separator found, returns
     * ("", fullText) so the caller can auto-generate a title.
     *
     * We deliberately do NOT split on commas. Natural sentences have
     * commas that aren't title/content boundaries — splitting on the
     * first comma produced titles like "for me" and "about how".
     */
    private fun tryExplicitTitleSplit(text: String): Pair<String, String> {
        // Candidate separators in priority order. We use the FIRST one
        // that matches the title pattern (short, no period, both sides non-blank).
        // Em-dash and en-dash are common in user input but easy to confuse
        // with hyphenated words ("well-being"), so we require spaces around them.
        val separators = listOf(':', " - ", " — ", " – ", " | ")

        for (sep in separators) {
            val idx = if (sep is Char) text.indexOf(sep) else text.indexOf(sep as String)
            if (idx <= 0) continue
            val before = text.substring(0, idx).trim()
            val after = text.substring(idx + sep.toString().length).trim()
            // Title heuristics
            if (before.isBlank() || after.isBlank()) continue
            if (before.length > 60) continue
            // Reject if the "title" contains a sentence-ending period —
            // it's a sentence, not a title.
            if (before.contains('.')) continue
            // Reject if the "title" ends with a verb-ish word — likely
            // a sentence fragment, not a title. (Conservative: we only
            // catch a few obvious cases.)
            val lastWord = before.split(Regex("\\s+")).last()
                .lowercase().trimEnd(',', '.', ':', ';')
            if (lastWord in VERBISH_TAIL_WORDS) continue
            return before to after
        }
        return "" to text
    }

    /**
     * Words that, if they end a putative "title", strongly suggest the
     * text is a sentence fragment rather than a real title. Used by
     * [tryExplicitTitleSplit] to reject bad splits like "Today I went to:".
     */
    private val VERBISH_TAIL_WORDS = setOf(
        "to", "for", "with", "about", "from", "into", "onto", "over",
        "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did",
        "will", "would", "should", "could", "might", "must",
        "i", "you", "he", "she", "they", "we",
        "the", "a", "an", "and", "or", "but",
        "went", "go", "going", "want", "wanted", "need", "needed",
        "felt", "feel", "feeling", "got", "get", "getting"
    )

    /**
     * Auto-generate a clean, short title from the content text.
     *
     * Strategy:
     *   - If the content starts with a short first sentence (ends in
     *     `.`, `!`, or `?` within the first 80 chars), use that
     *     sentence (truncated to 50 chars at a word boundary).
     *   - Otherwise, take the first few words UP TO the first comma or
     *     semicolon (so "today was a good day, I finished..." becomes
     *     "today was a good day" rather than "today was a good day, I").
     *     Cap at 6 words if no comma appears earlier.
     *   - Truncate to 50 chars at a word boundary.
     *   - Strip trailing punctuation from the title.
     *   - Capitalize the first letter.
     */
    private fun autoTitleFromContent(content: String): String {
        val text = content.trim()
        if (text.isBlank()) return ""

        // Try first sentence (up to first . ! ? within first 80 chars)
        val sentenceEnd = text.indexOfAny(charArrayOf('.', '!', '?'))
        val candidate = if (sentenceEnd in 1..80) {
            text.substring(0, sentenceEnd).trim()
        } else {
            // Take words up to the first comma/semicolon, capped at 6 words.
            // This prevents titles like "today was a good day, I" — we stop
            // at the comma instead.
            val words = text.split(Regex("\\s+"))
            val taken = mutableListOf<String>()
            for (w in words) {
                if (taken.size >= 6) break
                // Stop if this word starts with a comma/semicolon — the
                // clause boundary is the natural title endpoint.
                val cleaned = w.trimEnd(',', ';')
                if (cleaned != w && cleaned.isNotEmpty()) {
                    taken.add(cleaned)
                    break
                }
                if (w.startsWith(',') || w.startsWith(';')) break
                taken.add(w)
            }
            taken.joinToString(" ")
        }

        // Truncate to 50 chars at a word boundary
        val truncated = if (candidate.length <= 50) {
            candidate
        } else {
            val cut = candidate.substring(0, 50)
            val lastSpace = cut.lastIndexOf(' ')
            if (lastSpace > 20) cut.substring(0, lastSpace) else cut
        }.trim()

        // Strip trailing punctuation (commas, periods, colons, semicolons, dashes)
        val cleaned = truncated.trimEnd(',', '.', ':', ';', '-', '—').trim()

        // Capitalize first letter (preserve rest as-is to respect proper nouns)
        return if (cleaned.isNotEmpty()) {
            cleaned.replaceFirstChar { it.uppercase() }
        } else {
            ""
        }
    }

    /**
     * Light cleanup for an explicitly-extracted title: strip surrounding
     * quotes, trim trailing punctuation, capitalize first letter.
     */
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
            .trimStart('"', '\'', '“', '”', '\u2018', '\u2019')
            .trimEnd('"', '\'', '“', '”', '\u2018', '\u2019')
            .trim()
            .trimEnd(':', ',', ';')
            .trim()
        if (t.isNotEmpty()) {
            t = t.replaceFirstChar { it.uppercase() }
        }
        return t
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
