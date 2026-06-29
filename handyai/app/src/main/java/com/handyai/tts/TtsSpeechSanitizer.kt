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
package com.handyai.tts

/**
 * ── TTS SPEECH SANITIZER (v1.4.1) ───────────────────────────────────────
 *
 * The user reported: "when the voice is enabled and it finds multiple pipes
 * and hyphens for table it just keep on saying it, suppress that."
 *
 * PROBLEM:
 *   When the LLM emits a markdown table like:
 *
 *     | Name  | Age |
 *     |-------|-----|
 *     | Alice | 30  |
 *
 *   The TTS engine reads it verbatim: "pipe Name pipe Age pipe pipe
 *   hyphen hyphen hyphen hyphen hyphen hyphen hyphen pipe hyphen hyphen
 *   hyphen hyphen hyphen pipe pipe Alice pipe 30 pipe…"
 *
 *   This is unlistenable and wastes time. We need to convert the table
 *   to spoken-friendly text BEFORE sending to TextToSpeech.
 *
 * SOLUTION:
 *   - Detect markdown tables (lines starting and ending with |)
 *   - Drop the separator row (the |---|---| line)
 *   - Convert each data row to "Name Alice, Age 30" style phrasing
 *     for the FIRST row (treating row 0 as headers), then "Alice, 30"
 *     for subsequent rows
 *   - Replace any remaining standalone pipes with commas
 *   - Strip leading/trailing pipes from each cell
 *
 * ALSO HANDLES:
 *   - Stray **bold** markers (asterisks)
 *   - #heading hashes
 *   - Bullet markers (-, *, •) at line starts → "Item: ..."
 *   - URL brackets [text](url) → just "text"
 *   - Triple backticks ``` (code fence) → skip
 */
object TtsSpeechSanitizer {

    /**
     * Sanitize [text] for speech. Returns a string suitable for passing
     * to TextToSpeech.speak() — no markdown syntax, no pipes, no hashes.
     */
    fun sanitize(text: String): String {
        if (text.isBlank()) return text

        // ── Step 1: Extract markdown tables and convert to spoken text ──
        val lines = text.split('\n')
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isTableLine(line)) {
                // Collect consecutive table lines
                val tableLines = mutableListOf<String>()
                while (i < lines.size && isTableLine(lines[i])) {
                    tableLines.add(lines[i])
                    i++
                }
                out.append(tableToSpeech(tableLines)).append('\n')
            } else {
                out.append(line).append('\n')
                i++
            }
        }

        var result = out.toString()

        // ── Step 2: Strip markdown syntax that isn't tables ──
        // Code fences (```...```)
        result = result.replace(Regex("```[a-zA-Z0-9]*\\n?"), "")
        result = result.replace("```", "")

        // Inline code `text` → text
        result = result.replace(Regex("`([^`]+)`"), "$1")

        // Headings: # Title → Title
        result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")

        // Bold/italic: **text** / *text* / __text__ / _text_ → text
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        result = result.replace(Regex("__([^_]+)__"), "$1")
        result = result.replace(Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])"), "$1")
        result = result.replace(Regex("(?<![\\w_])_([^_\\n]+)_(?![\\w_])"), "$1")

        // Links: [text](url) → text
        result = result.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")

        // Images: ![alt](url) → alt
        result = result.replace(Regex("!\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")

        // Bullet markers at line start: - item / * item / • item → item
        result = result.replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")

        // Numbered list markers: 1. item → item
        result = result.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")

        // Blockquotes: > text → text
        result = result.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")

        // Horizontal rules: --- / *** / ___ → skip
        result = result.replace(Regex("^\\s*([-*_])\\1{2,}\\s*$", RegexOption.MULTILINE), "")

        // ── Step 3: Cleanup any remaining stray punctuation ──
        // Multiple consecutive pipes (shouldn't happen after table
        // conversion, but defensive)
        result = result.replace(Regex("\\|{2,}"), " ")
        // Single stray pipe → comma
        result = result.replace("|", ", ")
        // Multiple consecutive commas/spaces
        result = result.replace(Regex(",\\s*,+"), ", ")
        // Multiple spaces
        result = result.replace(Regex("[ \\t]{2,}"), " ")
        // Multiple blank lines
        result = result.replace(Regex("\\n{3,}"), "\n\n")

        return result.trim()
    }

    /**
     * A line is a table line if it contains at least one pipe and either
     * starts with | or ends with | (or both).
     */
    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('|')) return false
        return trimmed.startsWith("|") || trimmed.endsWith("|") ||
            trimmed.count { it == '|' } >= 2
    }

    /**
     * Convert a markdown table (list of pipe-delimited lines) to a
     * spoken-friendly string.
     *
     * Input:
     *   | Name  | Age |
     *   |-------|-----|
     *   | Alice | 30  |
     *   | Bob   | 25  |
     *
     * Output:
     *   Columns: Name, Age. Alice, 30. Bob, 25.
     *
     * If there's no separator row, treat the first row as headers anyway.
     */
    private fun tableToSpeech(tableLines: List<String>): String {
        if (tableLines.isEmpty()) return ""

        // Parse all rows
        val rows = tableLines.map { parseRow(it) }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return ""

        // Detect and skip separator row (the |---|---| line)
        val dataRows = rows.filterNot { row ->
            row.all { cell -> cell.isBlank() || Regex("^[\\-:]+$").matches(cell.trim()) }
        }
        if (dataRows.isEmpty()) return ""

        val headers = dataRows.first()
        val body = if (dataRows.size > 1) dataRows.drop(1) else emptyList()

        val sb = StringBuilder()
        // Announce columns
        if (headers.isNotEmpty()) {
            sb.append("Table. Columns: ")
            sb.append(headers.joinToString(", "))
            sb.append(". ")
        }
        // Read each data row
        body.forEach { row ->
            // Pad row to header length
            val padded = row + List(maxOf(0, headers.size - row.size)) { "" }
            val pairs = headers.zip(padded)
                .filter { (_, v) -> v.isNotBlank() }
                .joinToString(", ") { (h, v) -> "$h $v" }
            if (pairs.isNotBlank()) {
                sb.append(pairs).append(". ")
            }
        }
        return sb.toString().trim()
    }

    /**
     * Parse a markdown table row into cells. Strips leading/trailing
     * pipes, splits on |, trims each cell.
     */
    private fun parseRow(line: String): List<String> {
        var s = line.trim()
        // Strip leading/trailing pipe
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }
}
