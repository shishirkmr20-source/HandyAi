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

/**
 * Sanitizes LLM output before rendering.
 *
 * HISTORY
 * =======
 * This object previously also parsed lightweight Markdown inline
 * formatting (`**bold**`, `*italic*`, `` `code` ``) into a Compose
 * [AnnotatedString] with [SpanStyle] spans.
 *
 * That parsing was REMOVED in v1.2.9 because it was the root cause of
 * a hard crash that appeared after the 4th chat in a session. The
 * crash chain was:
 *
 *   1. The LLM emits `**bold**` markers in its response.
 *   2. `parse()` strips the `**` markers, producing an AnnotatedString
 *      SHORTER than the raw `text` (e.g. raw="hello **world**" →
 *      parsed="hello world", length drops from 14 to 11).
 *   3. In [StreamingBubble], the [Text] composable is given the parsed
 *      (shorter) string, but the [TextLayoutResult] captured via
 *      `onTextLayout` lags ONE FRAME behind — it still holds the layout
 *      for the PREVIOUS parsed string.
 *   4. When a new chunk arrives that adds a `**` marker, the parsed
 *      string grows, but the stale layout is for the previous (shorter)
 *      parsed string. Calling `getCursorRect(parsed.length)` with the
 *      new (longer) length on the old (shorter) layout is an
 *      out-of-bounds access → IndexOutOfBoundsException → crash.
 *   5. The previous "fix" used `lr.layoutInput.text.length` (the old
 *      layout's own text length) instead of `parsed.length`. This was
 *      *technically* safe but the race was fragile and the user
 *      reported the crash was still happening after the 4th chat.
 *
 * On top of the crash, the bold rendering wasn't even visibly working
 * on most devices — the small on-device LLMs the user is running
 * (Qwen 0.5B–1.5B, SmolLM) rarely emit well-formed `**bold**` pairs,
 * so the parser was doing nothing useful 90% of the time and crashing
 * the other 10%.
 *
 * The clean fix is to remove the parser entirely. Now the [Text]
 * composable always receives the raw (sanitized) string, so
 * `text.length == layout.text.length` is invariant — the caret
 * positioning in [StreamingBubble] can never go out of bounds.
 *
 * WHAT REMAINS (v1.4.2)
 * =====================
 * Only [sanitize] — which now performs FOUR passes:
 *
 *   1. **Tag stripping** (v1.4.2 — SmolLM fix): SmolLM-135M and
 *      similar tiny models often wrap their output in XML/HTML-style
 *      tags like `<response>...</response>`, `<thought>...</thought>`,
 *      `<answer>...</answer>`. The user sees these raw tags in the
 *      chat bubble, which looks broken. This pass strips the tags but
 *      KEEPS the content inside them, so `<answer>Hello</answer>`
 *      becomes `Hello`.
 *
 *      Tags are stripped in REALTIME as chunks stream in — this
 *      function is called on every chunk in ChatViewModel. A chunk
 *      may arrive as `<ans` then `wer>Hel` then `lo</answer>` — the
 *      regex handles partial tags by leaving incomplete `<` sequences
 *      untouched (they'll be cleaned up on the next chunk once the
 *      full tag arrives).
 *
 *   2. **Escape-sequence cleanup** (v1.2.9 — original): small on-device
 *      models sometimes emit the two-character sequence `\` + `n`
 *      instead of an actual newline character. This pass converts
 *      those literal `\n` sequences into real newlines so the text
 *      reads naturally.
 *
 *   3. **Carriage return removal** (v1.4.2 — user-requested): the
 *      user asked "remove \r or \n from llm reply". We interpret this
 *      as: strip literal `\r` escape sequences (backslash + r) AND
 *      actual `\r` control characters (Windows line endings). Actual
 *      `\n` newline characters are KEPT — they're needed for paragraph
 *      breaks, list formatting, and markdown tables. Without them the
 *      bubble would render as a single run-on line.
 *
 *   4. **Markdown table preservation**: pipes (`|`) and hyphens (`-`)
 *      are NOT stripped — they're needed for table rendering in
 *      [MarkdownTable]. The TTS path has its own sanitizer
 *      ([com.handyai.tts.TtsSpeechSanitizer]) that converts tables to
 *      spoken prose without affecting the displayed text.
 *
 * Applied to:
 *   - Each streaming chunk in ChatViewModel (live)
 *   - The final response before persisting to the DB
 *   - As a defensive render-time pass in MessageBubble / StreamingBubble
 */
object MarkdownParser {

    /**
     * Sanitize LLM output for display.
     *
     * See class kdoc for the full breakdown of the four passes.
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return text

        var result = text

        // ── Pass 1: Strip XML/HTML-style tags (SmolLM fix, v1.4.2) ──────
        // Only strip if the chunk contains a '<' that looks like it could
        // be the start of a tag. The regex matches complete tags like
        // `<response>`, `</response>`, `<thought lang="en">`. INCOMPLETE
        // tags (just `<` at the end of a chunk, waiting for the rest) are
        // left alone — they'll be caught on the next chunk once the full
        // tag arrives.
        //
        // We deliberately do NOT strip tags whose name starts with a digit
        // or non-letter (e.g. `<3`, `<5px`) to avoid eating math/emojis.
        if (result.contains('<')) {
            result = result.replace(Regex("<\\/?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^<>]*?)?\\/?>"), "")
        }

        // ── Pass 2: Convert literal `\n` escapes to real newlines ───────
        // Small on-device models sometimes emit `\` + `n` as two characters
        // instead of an actual newline. This is the original v1.2.9 fix.
        if (result.contains('\\')) {
            // Convert literal \n → real newline
            result = result.replace("\\n", "\n")
            // Remove literal \r escape (user asked: "remove \r from llm reply")
            // We REMOVE (not convert) because a literal \r in chat output is
            // never useful — it would just show as a stray "r" after the
            // backslash is processed.
            result = result.replace("\\r", "")
            // Also handle literal \t → real tab (defensive, rare)
            result = result.replace("\\t", "\t")
        }

        // ── Pass 3: Remove actual \r control characters ─────────────────
        // Windows-style line endings (\r\n) become just \n. Lone \r (old
        // Mac Classic line endings, or stray carriage returns) are removed
        // entirely. Actual \n newlines are PRESERVED — they're needed for
        // paragraph breaks, list formatting, and markdown tables.
        if (result.contains('\r')) {
            result = result.replace("\r\n", "\n").replace("\r", "")
        }

        return result
    }
}
