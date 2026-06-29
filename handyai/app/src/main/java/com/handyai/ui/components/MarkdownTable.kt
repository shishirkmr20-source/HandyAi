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
package com.handyai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Renders GitHub-flavoured Markdown pipe tables as a native Compose table.
 *
 * LLMs (especially Qwen / Phi / GPT-style) frequently emit tables like:
 *
 *   | Name  | Age | City     |
 *   |-------|-----|----------|
 *   | Alice | 30  | Mumbai   |
 *   | Bob   | 25  | Delhi    |
 *
 * Without rendering, the user sees a wall of pipes and hyphens. This
 * composable detects that pattern, parses it into rows + columns, and
 * lays it out as a proper bordered table that scrolls horizontally if
 * needed.
 *
 * SUPPORTED SYNTAX
 * ────────────────
 * - Header row, followed by a separator row of `---` / `:--` / `--:` /
 *   `:--:`, followed by 1+ data rows.
 * - Leading/trailing pipes are optional (we strip them).
 * - Empty cells are rendered as a blank.
 *
 * NON-GOALS
 * ─────────
 * - Inline Markdown inside cells (bold, code, links) is NOT parsed —
 *   cells are rendered as plain text. Small on-device LLMs rarely
 *   produce well-formed inline Markdown inside table cells, and parsing
 *   it would re-introduce the streaming-layout-length crash that
 *   MarkdownParser.kt documents.
 * - Row/col spans are not supported.
 * - Cell alignment (`:--`, `--:`, `:--:`) is parsed and validated but
 *   not yet applied to the rendered text — the header is just rendered
 *   bold and everything else left-aligned.
 */
object MarkdownTable {

    /**
     * Try to parse [text] as a Markdown pipe table. Returns null if the
     * text is NOT a table (caller should fall back to rendering it as a
     * normal paragraph).
     *
     * A valid table requires:
     *   - At least 2 lines (header + separator)
     *   - The 2nd line must consist of cells matching the pattern
     *     `:?-+:?` (1+ hyphens, optional leading/trailing colon)
     *   - All rows must have the same number of cells (we're lenient —
     *     if a data row has fewer cells, we pad with ""; if more, we
     *     truncate)
     */
    fun parse(text: String): ParsedTable? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null

        // Every line must contain at least one pipe (otherwise it's
        // just a paragraph that happens to follow a table).
        if (lines.any { !it.contains('|') }) return null

        val headerCells = splitRow(lines[0])
        if (headerCells.isEmpty()) return null

        // Validate separator row
        val sepCells = splitRow(lines[1])
        if (sepCells.isEmpty()) return null
        val sepPattern = Regex("^:?-+:?$")
        if (sepCells.any { !sepPattern.matches(it) }) return null

        val dataRows = lines.drop(2).map { splitRow(it) }
        if (dataRows.isEmpty()) return null

        // Normalize: pad/truncate every row to header width
        val width = headerCells.size
        val normalizedData = dataRows.map { row ->
            row + List((width - row.size).coerceAtLeast(0)) { "" }
        }.take(width)

        return ParsedTable(
            headers = headerCells,
            rows = normalizedData
        )
    }

    /**
     * Split a Markdown table row into cells. Strips leading/trailing
     * pipes and trims each cell. Handles escaped pipes (`\|`) inside
     * cells by treating them as literal pipes (the escape is removed).
     */
    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith('|')) s = s.substring(1)
        if (s.endsWith('|')) s = s.substring(0, s.length - 1)
        // Split on | but not on \|
        // We use a placeholder for escaped pipes, split, then restore.
        val placeholder = "\u0000PIPE\u0000"
        s = s.replace("\\|", placeholder)
        return s.split('|').map {
            it.replace(placeholder, "|").trim()
        }
    }

    data class ParsedTable(
        val headers: List<String>,
        val rows: List<List<String>>
    )

    /**
     * Split a message body into a sequence of plain-text paragraphs and
     * markdown tables. Used by [MessageBubble] to render tables as native
     * Compose tables while leaving the surrounding text as plain Text.
     *
     * Detection rules:
     *   - A "table block" is a run of 2+ consecutive non-empty lines where
     *     every line contains at least one '|' AND the 2nd line of the run
     *     matches the separator pattern (`:?-+:?` per cell).
     *   - Everything between table blocks is treated as a single text
     *     block (preserving its internal newlines).
     *
     * Empty text blocks (e.g. between two adjacent tables) are dropped so
     * the rendered output doesn't have spurious gaps.
     */
    fun splitBlocks(text: String): List<MessageBlock> {
        if (text.isEmpty()) return listOf(MessageBlock.Text(""))

        val lines = text.lines()
        val blocks = mutableListOf<MessageBlock>()
        val textAcc = StringBuilder()
        val tableAcc = mutableListOf<String>()

        fun flushText() {
            if (textAcc.isNotBlank()) {
                blocks.add(MessageBlock.Text(textAcc.toString()))
            }
            textAcc.clear()
        }

        fun flushTable() {
            if (tableAcc.isEmpty()) return
            val joined = tableAcc.joinToString("\n")
            val parsed = parse(joined)
            if (parsed != null) {
                blocks.add(MessageBlock.Table(parsed))
            } else {
                // Not actually a table — render as text
                blocks.add(MessageBlock.Text(joined))
            }
            tableAcc.clear()
        }

        fun isTableLine(s: String): Boolean = s.trim().isNotEmpty() && s.contains('|')

        for (line in lines) {
            if (isTableLine(line)) {
                // Maybe start or continue a table block. We tentatively
                // accumulate — flushTable() will verify it really is a
                // table (separator row matches).
                if (textAcc.isNotBlank()) flushText()
                tableAcc.add(line)
            } else {
                // Non-table line — close any pending table
                if (tableAcc.isNotEmpty()) flushTable()
                if (textAcc.isNotEmpty()) textAcc.append('\n')
                textAcc.append(line)
            }
        }
        flushText()
        flushTable()

        return blocks
    }

    /**
     * A piece of a message body — either plain text or a parsed table.
     */
    sealed interface MessageBlock {
        data class Text(val content: String) : MessageBlock
        data class Table(val table: ParsedTable) : MessageBlock
    }
}

/**
 * Render a [MarkdownTable.ParsedTable] as a Compose table.
 *
 * Layout:
 *   - Header row: tinted background, bold text
 *   - Data rows: alternating background (zebra striping) for readability
 *   - Cells: padded, with vertical dividers between columns
 *   - Whole table: rounded corners + thin border, horizontally scrollable
 *     so wide tables don't wrap or get truncated
 *
 * Color choices:
 *   - Header background = primaryContainer (tied to the active theme)
 *   - Striped row = surfaceVariant (subtle)
 *   - Border = outlineVariant (1dp)
 *
 * The table is sized to its content — it does NOT fill the parent's
 * width, so a 2-column table renders narrow and a 6-column table
 * scrolls horizontally.
 */
@Composable
fun MarkdownTableView(table: MarkdownTable.ParsedTable) {
    val headerBg = MaterialTheme.colorScheme.primaryContainer
    val stripeBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerColor = MaterialTheme.colorScheme.onPrimaryContainer
    val cellColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
    ) {
        // Header row
        Row(modifier = Modifier.background(headerBg)) {
            table.headers.forEachIndexed { _, cell ->
                Text(
                    text = cell.ifBlank { " " },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = headerColor,
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .widthIn(min = 60.dp)
                )
                // Vertical divider — drawn as a 1dp column
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(borderColor)
                )
            }
        }
        // Data rows
        table.rows.forEachIndexed { idx, row ->
            val rowBg = if (idx % 2 == 0) Color.Transparent else stripeBg
            Row(modifier = Modifier.background(rowBg)) {
                row.forEach { cell ->
                    Text(
                        text = cell.ifBlank { " " },
                        style = MaterialTheme.typography.bodySmall,
                        color = cellColor,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .widthIn(min = 60.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(borderColor)
                    )
                }
            }
        }
    }
}
