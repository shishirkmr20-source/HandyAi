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
import java.util.regex.Pattern

/**
 * Detects "create a habit" intent in the user's chat message and extracts
 * the habit details. Used so the user can say things like:
 *
 *   "Add a habit to drink water every day at 9am"
 *   "Create a new habit: Exercise, category Health, time 7:00"
 *   "Remind me to read 10 pages daily"
 *
 * and the app will actually create the habit in the database — instead of
 * the LLM just *saying* it created one (which is what happened before:
 * the on-device model has no tool-use capability and would hallucinate
 * "I've created the habit" without any actual side-effect).
 *
 * Returns null when no creation intent is detected.
 */
object HabitIntentParser {

    /**
     * Triggers that unambiguously signal the user wants a NEW habit
     * created in the database.
     *
     * CONSERVATIVE LIST: We avoid generic phrases like "i want to track"
     * or "remind me to" because those can also appear in casual
     * conversation where the user is NOT asking the app to create a
     * habit row — they're just talking to the AI about intentions.
     * The triggers below are all explicit "add / create / start a
     * habit" commands that fire only when the user clearly wants the
     * side-effect to happen.
     */
    private val CREATE_TRIGGERS = listOf(
        "create a habit", "create a new habit", "create habit",
        "add a habit", "add a new habit", "add habit",
        "new habit:", "new habit -", "new habit,",
        "start a habit", "start a new habit",
        "track a habit", "track a new habit",
        "set up a habit", "set up a new habit",
        "habit:", "habit -", "habit named", "habit called",
        "make a habit", "make a new habit"
    )

    private val TIME_PATTERN = Pattern.compile(
        "\\b(?:at\\s+)?(\\d{1,2})(?:(?::|\\.)?(\\d{2}))?\\s*(am|pm|AM|PM)?\\b"
    )

    private val DATE_PATTERN = Pattern.compile(
        "\\b(\\d{4})-(\\d{2})-(\\d{2})\\b"
    )

    private val CATEGORIES = listOf(
        "health", "fitness", "work", "learning", "study", "mindfulness",
        "meditation", "productivity", "personal", "social", "finance",
        "creative", "diet", "sleep", "exercise"
    )

    data class HabitIntent(
        val name: String,
        val description: String = "",
        val category: String = "",
        val targetDate: String = "",
        val targetTime: String = ""
    )

    fun parse(message: String): HabitIntent? {
        val lower = message.lowercase().trim()
        if (lower.isBlank()) return null

        // Must contain a creation trigger phrase
        val triggered = CREATE_TRIGGERS.any { lower.contains(it) }
        if (!triggered) return null

        // Extract the habit name. Try several patterns:
        //   "create a habit to drink water"  ->  "drink water"
        //   "new habit: exercise"            ->  "exercise"
        //   "remind me to read 10 pages"     ->  "read 10 pages"
        //   "add a habit called meditation"  ->  "meditation"
        val name = extractName(message) ?: return null

        // Extract optional fields
        val time = extractTime(message)
        val date = extractDate(message)
        val category = extractCategory(message)

        return HabitIntent(
            name = name,
            description = "",
            category = category,
            targetDate = date,
            targetTime = time
        )
    }

    private fun extractName(message: String): String? {
        val lower = message.lowercase()

        // "new habit: X" or "new habit - X" or "new habit, X"
        listOf("new habit:", "new habit -", "new habit,", "habit:", "habit -", "habit named",
               "habit called", "habit to").forEach { trigger ->
            val idx = lower.indexOf(trigger)
            if (idx >= 0) {
                val rest = message.substring(idx + trigger.length).trim()
                return cleanName(rest)
            }
        }

        // "create a habit to X" / "add a habit to X" / "start a habit to X"
        listOf("create a habit to", "add a habit to", "start a habit to",
               "create a new habit to", "add a new habit to",
               "track a habit to", "set up a habit to",
               "i want to track", "remind me to", "start tracking").forEach { trigger ->
            val idx = lower.indexOf(trigger)
            if (idx >= 0) {
                val rest = message.substring(idx + trigger.length).trim()
                return cleanName(rest)
            }
        }

        // "create a habit" / "add a habit" (no "to" — name is the next clause)
        listOf("create a habit", "add a habit", "start a habit",
               "create a new habit", "add a new habit",
               "track a habit", "set up a habit").forEach { trigger ->
            val idx = lower.indexOf(trigger)
            if (idx >= 0) {
                val rest = message.substring(idx + trigger.length).trim()
                // Strip leading "for" / "called" / "named" / "to"
                val cleaned = rest.removePrefix("for").removePrefix("called")
                    .removePrefix("named").removePrefix("to").trim()
                if (cleaned.isNotBlank()) return cleanName(cleaned)
            }
        }

        return null
    }

    /**
     * Clean up an extracted name: strip trailing punctuation, time/date
     * references, category labels, and "every day" / "daily" qualifiers.
     */
    private fun cleanName(raw: String): String? {
        var s = raw.trim()
        // Cut at sentence-ending punctuation or conjunction
        listOf(".", "!", "?", ";", "\n", " and ", " with ", " for ", " at ",
               " every ", " daily", " each day", " in the ", " in category",
               " category ", " on ", " by ").forEach { sep ->
            val idx = s.indexOf(sep, ignoreCase = true)
            if (idx > 0) s = s.substring(0, idx).trim()
        }
        // Strip leading colons / dashes
        s = s.trimStart(':', '-', ' ', '"', '\'')
        s = s.trimEnd(':', '-', ' ', '"', '\'', ',', '.')
        // Title-case the first letter
        if (s.isNotEmpty()) {
            s = s.substring(0, 1).uppercase() + s.substring(1)
        }
        return s.ifBlank { null }
    }

    private fun extractTime(message: String): String {
        val m = TIME_PATTERN.matcher(message)
        while (m.find()) {
            val hour = m.group(1)?.toIntOrNull() ?: continue
            val minute = m.group(2)?.ifBlank { null }?.toIntOrNull() ?: 0
            val ampm = m.group(3)?.lowercase()

            if (hour < 1 || hour > 23) continue
            if (minute < 0 || minute > 59) continue

            // Normalize to 24-hour HH:MM
            val h24 = when {
                ampm == "am" && hour == 12 -> 0
                ampm == "pm" && hour != 12 -> hour + 12
                ampm == null && hour > 12 -> hour  // already 24-hour
                ampm == null -> hour
                else -> hour
            }
            return String.format("%02d:%02d", h24, minute)
        }
        return ""
    }

    private fun extractDate(message: String): String {
        val m = DATE_PATTERN.matcher(message)
        if (m.find()) {
            val y = m.group(1)?.toIntOrNull() ?: return ""
            val mo = m.group(2)?.toIntOrNull() ?: return ""
            val d = m.group(3)?.toIntOrNull() ?: return ""
            return try {
                String.format("%04d-%02d-%02d", y, mo, d)
            } catch (_: Throwable) { "" }
        }
        // Also accept "today" / "tomorrow"
        val lower = message.lowercase()
        return when {
            "today" in lower -> LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            "tomorrow" in lower -> LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_DATE)
            else -> ""
        }
    }

    private fun extractCategory(message: String): String {
        val lower = message.lowercase()
        // "category X" or "in category X"
        listOf("category:", "category ", "in category ").forEach { tag ->
            val idx = lower.indexOf(tag)
            if (idx >= 0) {
                val rest = message.substring(idx + tag.length).trim()
                // Take the first word
                val word = rest.split(Regex("\\s+"), limit = 2).firstOrNull() ?: ""
                val cleaned = word.trimEnd(',', '.', ';', ':')
                if (cleaned.length in 2..30) {
                    return cleaned.replaceFirstChar { it.uppercase() }
                }
            }
        }
        // Detect known category keywords in the message
        for (cat in CATEGORIES) {
            if (lower.contains(cat)) {
                return cat.replaceFirstChar { it.uppercase() }
            }
        }
        return ""
    }
}
