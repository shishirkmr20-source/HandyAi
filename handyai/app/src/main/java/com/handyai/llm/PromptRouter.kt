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
 * ── SMART PROMPT ROUTER (v1.4.1) ─────────────────────────────────────────
 *
 * The user asked: "what will happen if you dont give pre prompt from your end?
 * maybe whatever prompts you are giving for habit or journal integration or
 * any other tasks store it locally and do a search when user sends chat and
 * if it looks like it should be given to llm then give."
 *
 * PROBLEM (v1.4.0 and earlier):
 *   Every system prompt carried a ~600-char "tool capabilities" preamble
 *   explaining habit creation, journal creation, attachment handling,
 *   file format support, etc. — even when the user was just saying
 *   "hello" or asking the weather. Small models (0.5B, 1.5B) wasted
 *   precious attention budget on irrelevant instructions, leading to
 *   slower prefill and degraded reply quality.
 *
 * SOLUTION:
 *   Each tool/rule has a set of TRIGGER KEYWORDS. When the user sends a
 *   message, the router scans the text and only injects the prompts for
 *   rules whose keywords match. A "minimal base" rule (always-on) carries
 *   just the core identity + format rules — ~120 chars.
 *
 *   For a typical "hello, how are you?" message, ZERO tool prompts are
 *   injected. For "add a habit to drink water daily", only the habit
 *   creation ack rule fires. This keeps the system prompt small and
 *   focused, which:
 *     1. Speeds up prefill (smaller prompt = fewer tokens to process)
 *     2. Improves instruction-following (model isn't diluted across
 *        5 unrelated instructions)
 *     3. Lets the user's actual question get more attention budget
 *
 * OUTPUT:
 *   [basePrompt] = always-on core identity + format rules (~120 chars)
 *   [matchedRules] = list of (ruleId, prompt) pairs that matched the
 *                    user's message — to be appended after basePrompt
 *   [totalPrompt] = basePrompt + all matched rule prompts, joined
 */
object PromptRouter {

    /**
     * A single rule with trigger keywords and the prompt to inject when
     * any keyword matches. Keywords are case-insensitive, matched as
     * substrings (so "journal" matches "journal", "journals", "journaling").
     */
    data class Rule(
        val id: String,
        val description: String,
        val triggers: List<String>,
        val prompt: String,
        /** If true, the rule fires for EVERY message (base rule). */
        val alwaysOn: Boolean = false
    )

    /**
     * The full rule catalog. Order matters — base rules come first so they
     * appear at the top of the system prompt.
     *
     * WHY THIS DESIGN:
     *   - Adding a new tool/rule = adding one entry here. No other code
     *     needs to change. The router picks it up automatically.
     *   - Removing a tool/rule = commenting out the entry.
     *   - Tweaking trigger words = editing the list inline.
     *   - Each rule is SELF-CONTAINED — its prompt makes sense on its own,
     *     without depending on other rules being present.
     */
    private val RULES: List<Rule> = listOf(
        // ── BASE RULE (always-on) ───────────────────────────────────────
        Rule(
            id = "base",
            description = "Core identity + format rules",
            triggers = emptyList(),
            alwaysOn = true,
            prompt = "You are HandyAi, a helpful assistant running fully on-device. " +
                "Reply in plain text. No **asterisks**, no #headings. " +
                "Markdown tables (| pipes |) are OK for tabular data. " +
                "Keep prose concise unless the user asks for detail."
        ),

        // ── HABIT CREATION ──────────────────────────────────────────────
        Rule(
            id = "habit_create",
            description = "Acknowledge habit creation side-effect",
            triggers = listOf(
                "add a habit", "add habit", "create a habit", "create habit",
                "new habit", "start a habit", "start habit",
                "track a habit", "track habit", "i want to track",
                "build a habit", "build habit",
                "remind me to", "daily reminder", "habit to"
            ),
            prompt = "Tool capabilities: when the user explicitly asks to create/add/start/track a habit, " +
                "the app has ALREADY created it in the database. Just acknowledge in ONE short sentence " +
                "(e.g. \"Got it — I've added '\$name' to your habits.\"). Do not navigate, do not repeat the action, " +
                "do not say \"I have created...\" if no [APP ACTION] line is present below."
        ),

        // ── JOURNAL CREATION ────────────────────────────────────────────
        Rule(
            id = "journal_create",
            description = "Acknowledge journal entry creation side-effect",
            triggers = listOf(
                "add a journal", "add journal", "add an entry", "add entry",
                "journal entry", "write a journal", "write journal",
                "save a journal", "save journal", "log a journal",
                "remember that", "note that", "today was", "today i",
                "dear diary", "my day", "how i'm feeling", "how i am feeling",
                "i'm feeling", "i am feeling", "i feel", "feeling "
            ),
            prompt = "Tool capabilities: when the user explicitly asks to add/write/save a journal entry, " +
                "the app has ALREADY saved it. Just acknowledge in ONE short sentence. " +
                "Do not say \"I have saved...\" if no [APP ACTION] line is present below."
        ),

        // ── ATTACHMENT HANDLING (REMOVED v1.4.2) ─────────────────────────
        // This rule previously fired on broad triggers ("file", "document",
        // "image", "what is", "how to", "summarize") which matched almost
        // ANY substantive question. On small models (Qwen 0.5B) the injected
        // paragraph was echoed back verbatim — the user saw the model reply
        // with "document downloaded image downloaded" text on the FIRST chat,
        // and the extra ~400 chars made first-token latency worse.
        //
        // The attachment instruction is now injected ONLY when a file is
        // actually attached — see ChatViewModel.buildSmartSystemPrompt's
        // `fileBlock` (gated on hasFile). Non-attachment messages no longer
        // carry any attachment-related text, keeping the prompt minimal.

        // ── HABIT/JOURNAL CONTEXT LOOKUP ────────────────────────────────
        Rule(
            id = "habit_journal_lookup",
            description = "Inject personal context when user asks about their data",
            triggers = listOf(
                "my habit", "my habits", "my journal", "my journals",
                "my entries", "my progress", "streak", "check-in", "checkin",
                "how am i doing", "how am i going", "how's my",
                "what have i done", "what did i do",
                "show me my", "list my", "my recent"
            ),
            prompt = "When the user asks about their habits or journal, use the personal context " +
                "included below (recent journal entries + habit summary) to give a personalized response. " +
                "If no context is included, say you don't have any saved entries yet."
        ),

        // ── WEB SEARCH (only when internet enabled) ─────────────────────
        // Narrowed in v1.4.2: previously fired on "what is", "how to",
        // "who is" etc. — phrases that appear in almost every question.
        // Now only fires on explicit freshness signals (latest, recent,
        // news, today, weather, score, etc.) so the web-search paragraph
        // + actual web search don't bloat every reply's latency.
        Rule(
            id = "web_search",
            description = "Tell LLM that web context may be included",
            triggers = listOf(
                "latest", "recent", "today", "yesterday", "this week",
                "news", "current events", "what's happening", "whats happening",
                "weather", "stock", "stock price", "score", "match result",
                "who won", "election", "update on", "price of",
                "now showing", "now playing", "box office",
                "just released", "just came out", "new release"
            ),
            prompt = "Web search results may be included below when the user's question benefits from " +
                "fresh information. Cite the source URL when relevant."
        )
    )

    /**
     * The result of routing: the base prompt + the list of matched rule
     * prompts, ready to be assembled into the final system prompt.
     */
    data class RoutedPrompt(
        val basePrompt: String,
        val matchedRules: List<Rule>,
        /** True if NO tool rules matched (only base) — minimal mode. */
        val isMinimal: Boolean
    )

    /**
     * Scan [userMessage] and return only the rules whose triggers match
     * (plus the always-on base rule).
     *
     * Matching is case-insensitive substring match. A rule fires if ANY
     * of its triggers appears anywhere in the message.
     *
     * Example:
     *   userMessage = "hello, how are you?"
     *   → returns base only (isMinimal = true)
     *
     *   userMessage = "add a habit to drink water daily"
     *   → returns base + habit_create
     */
    fun route(userMessage: String): RoutedPrompt {
        val lower = userMessage.lowercase().trim()
        val base = RULES.filter { it.alwaysOn }
        val matched = RULES.filter { !it.alwaysOn && it.triggers.any { lower.contains(it) } }
        return RoutedPrompt(
            basePrompt = base.joinToString("\n") { it.prompt },
            matchedRules = matched,
            isMinimal = matched.isEmpty()
        )
    }

    /**
     * Build the final system prompt by joining the base + matched rules.
     * Each rule's prompt is on its own paragraph (separated by blank lines)
     * so the model can clearly distinguish them.
     *
     * Optional app-action acks (habitCreatedName / journalCreatedTitle)
     * are appended AFTER the rules — they're per-message state, not
     * standing rules.
     */
    fun buildSystemPrompt(
        routed: RoutedPrompt,
        habitCreatedName: String? = null,
        journalCreatedTitle: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append(routed.basePrompt)
        sb.append("\n\n")
        if (routed.matchedRules.isNotEmpty()) {
            routed.matchedRules.forEach { rule ->
                sb.append(rule.prompt).append("\n\n")
            }
        }
        // Per-message acks (only when the app actually performed a side-effect)
        if (habitCreatedName != null) {
            sb.append("[APP ACTION] A habit named \"$habitCreatedName\" was just created. ")
            sb.append("Acknowledge in one short sentence.\n\n")
        }
        if (journalCreatedTitle != null) {
            sb.append("[APP ACTION] A journal entry titled \"$journalCreatedTitle\" was just saved. ")
            sb.append("Acknowledge in one short sentence.\n\n")
        }
        return sb.toString().trim()
    }

    /**
     * Quick check: does this user message look like a "trivial" greeting
     * (hello, hi, hey, thanks, etc.) that doesn't need ANY tool rules?
     *
     * Used by ChatViewModel to skip even the base rule's verbose preamble
     * and use a TINY greeting-mode prompt instead (~40 chars). This makes
     * replies to "hi" feel instant on small models.
     */
    fun isTrivialGreeting(userMessage: String): Boolean {
        val lower = userMessage.lowercase().trim()
        if (lower.length > 40) return false
        val trivial = listOf(
            "hi", "hello", "hey", "yo", "sup", "howdy",
            "good morning", "good afternoon", "good evening", "good night",
            "thanks", "thank you", "thx", "ok", "okay", "cool", "nice",
            "bye", "goodbye", "see you", "later"
        )
        return trivial.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it!") || lower.startsWith("$it.") }
    }

    /**
     * Tiny prompt for trivial greetings — bypasses all rules. The model
     * just needs to say hi back. ~80 chars total system prompt means
     * prefill is near-instant even on the smallest model.
     */
    const val GREETING_PROMPT = "You are HandyAi. Reply briefly and warmly. One sentence max."
}
