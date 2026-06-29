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
 * WHAT REMAINS
 * ============
 * Only [sanitize] — the `\n` escape cleanup. Small on-device models
 * sometimes emit the two-character sequence `\` + `n` instead of an
 * actual newline character (they're trying to format their output but
 * haven't learned that the escape only works inside source code). The
 * user sees "Here is the summary:\n- Point one" as a single run-on
 * line with visible `\n` markers. [sanitize] converts those literal
 * sequences into real newlines so the text reads naturally.
 */
object MarkdownParser {

    /**
     * Sanitize LLM output by replacing literal backslash-n sequences with
     * actual newline characters.
     *
     * Small on-device models (Qwen 0.5B–1.5B, SmolLM, Phi-4-mini) sometimes
     * emit the two-character sequence `\` + `n` instead of an actual newline
     * character — they're trying to format their output but haven't learned
     * that the escape only works inside source code, not in chat text. The
     * user sees "Here is the summary:\n- Point one\n- Point two" rendered
     * as a single run-on line with visible `\n` markers.
     *
     * This function converts those literal sequences into real newlines so
     * the text reads naturally. It's applied:
     *   - To each streaming chunk in ChatViewModel (live)
     *   - To the final response before persisting to the DB
     *   - As a defensive render-time pass in MessageBubble / StreamingBubble
     *
     * NOTE: only handles `\n` (the most common offender). `\t` and `\r`
     * are rarer and not stripped — the LLM almost never emits them as
     * literal escapes in chat output.
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        if (!text.contains('\\')) return text  // fast path: no backslashes = no escapes
        return text.replace("\\n", "\n")
    }
}
