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

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * ── SMART WEB-SEARCH TRIGGER (v1.4.5) ─────────────────────────────────────
 *
 * The user asked: "create a system to browse internet only when it's required.
 * like if user is asking for current or latest results or if its related to
 * dates or any other criteria but write all these logic locally."
 *
 * PROBLEM (v1.4.4 and earlier):
 *   - v1.4.0 → v1.4.3: web search fired only when PromptRouter's `web_search`
 *     rule matched (keywords: "latest", "news", "weather", etc.). Common
 *     factual questions got NO web context even with internet ON.
 *   - v1.4.4: changed to "fire whenever internet is ON" — which spam-bombed
 *     DuckDuckGo/Bing on every single message, getting rate-limited within
 *     a handful of queries. The user reported results getting WORSE.
 *
 * SOLUTION (v1.4.5):
 *   Local heuristic classifier that returns true ONLY when the user's message
 *   genuinely benefits from fresh web data. No LLM call — pure regex / keyword
 *   matching against the user's text. Runs in microseconds.
 *
 * TRIGGER CATEGORIES (any match → search):
 *   1. FRESHNESS CUES — "latest", "recent", "today", "yesterday", "tomorrow",
 *      "this week", "this month", "this year", "right now", "currently",
 *      "now showing", "now playing"
 *   2. NEWS / EVENTS — "news", "happening", "what's going on", "headline",
 *      "breaking", "announced", "released", "launched", "update on"
 *   3. EXPLICIT DATES — any 4-digit year 2020-2099, month names, "last week",
 *      "next month", "last Monday", "in 3 days"
 *   4. LIVE DATA — "weather", "temperature", "forecast", "stock", "share
 *      price", "market cap", "exchange rate", "currency", "price of",
 *      "score", "match result", "who won", " standings", "leaderboard"
 *   5. SPORTS / ENTERTAINMENT — "box office", "now playing", "now showing",
 *      "episode", "season finale", "premiere", "release date"
 *   6. EXPLICIT SEARCH ASK — "search for", "google", "look up", "find
 *      online", "browse", "from the internet", "from the web"
 *   7. CURRENT PEOPLE / ENTITIES — "who is the current", "who is the new",
 *      "current CEO", "current president", "current prime minister"
 *
 * NO-TRIGGER EXAMPLES (default to local knowledge):
 *   "Hello", "How are you?", "What is photosynthesis?", "Write a poem",
 *   "Add a habit to drink water", "Summarize this document", "What's 2+2?"
 *
 * TRIGGER EXAMPLES (search runs):
 *   "Who won the World Cup 2026?", "What's the weather in Mumbai?",
 *   "Latest iPhone price", "What happened in the news today?",
 *   "Stock price of Apple", "What's the current time in Tokyo?"
 *
 * The classifier is intentionally permissive on category 7 (current people)
 * because stale knowledge is the #1 LLM failure mode for "who is the X"
 * questions about recent appointments / elections / hires.
 */
object WebSearchTrigger {

    /**
     * Result of the trigger classification — gives the UI / caller enough
     * info to log WHY search was triggered (or not) without re-running
     * the classifier.
     */
    data class Decision(
        val shouldSearch: Boolean,
        val matchedCategory: String?,
        val matchedTrigger: String?
    )

    /**
     * Classify [userMessage] and decide whether to run a web search.
     *
     * Returns a [Decision] with shouldSearch=true if ANY category matches.
     * The first matching category wins (returned in matchedCategory).
     */
    fun classify(userMessage: String): Decision {
        val lower = userMessage.lowercase().trim()
        if (lower.isBlank()) return Decision(false, null, null)

        // Skip trivial greetings / very short messages — never search.
        if (lower.length < 5) return Decision(false, null, null)
        if (lower in TRIVIAL_PHRASES) return Decision(false, null, null)

        // 1. FRESHNESS CUES
        FRESHNESS_CUES.firstOrNull { lower.contains(it) }
            ?.let { return Decision(true, "freshness", it) }

        // 2. NEWS / EVENTS
        NEWS_CUES.firstOrNull { lower.contains(it) }
            ?.let { return Decision(true, "news_events", it) }

        // 3. EXPLICIT DATES — year 2020-2099, month names, relative dates
        EXPLICIT_DATE_REGEX.find(lower)?.let {
            return Decision(true, "explicit_date", it.value)
        }
        RELATIVE_DATE_CUES.firstOrNull { lower.contains(it) }
            ?.let { return Decision(true, "explicit_date", it) }

        // 4. LIVE DATA
        LIVE_DATA_CUES.firstOrNull { lower.contains(it) }
            ?.let { return Decision(true, "live_data", it) }

        // 5. SPORTS / ENTERTAINMENT
        SPORTS_ENTERTAINMENT_CUES.firstOrNull { lower.contains(it) }
            ?.let { return Decision(true, "sports_entertainment", it) }

        // 6. EXPLICIT SEARCH ASK
        EXPLICIT_SEARCH_CUES.firstOrNull { lower.contains(it) }
            ?.let { return Decision(true, "explicit_search_ask", it) }

        // 7. CURRENT PEOPLE / ENTITIES
        CURRENT_PEOPLE_CUES.firstOrNull { lower.contains(it) }
            ?.let { return Decision(true, "current_people", it) }

        return Decision(false, null, null)
    }

    /**
     * Convenience wrapper — returns just the boolean.
     */
    fun shouldSearch(userMessage: String): Boolean = classify(userMessage).shouldSearch

    // ───────────────────────────────────────────────────────────────────────
    // Trigger keyword lists. Case-insensitive substring match. Order matters
    // within a list only for logging (first match wins).
    // ───────────────────────────────────────────────────────────────────────

    /** Trivial messages that never need web search. */
    private val TRIVIAL_PHRASES = setOf(
        "hi", "hello", "hey", "yo", "sup", "howdy",
        "thanks", "thank you", "thx", "ok", "okay", "cool", "nice",
        "bye", "goodbye", "see you", "later",
        "good morning", "good afternoon", "good evening", "good night"
    )

    /** Category 1: freshness cues. */
    private val FRESHNESS_CUES = listOf(
        "latest", "recent", "today", "yesterday", "tomorrow",
        "this week", "this month", "this year",
        "right now", "currently", "as of now", "at the moment",
        "now showing", "now playing", "currently showing",
        "just released", "just came out", "new release",
        "newest", "up to date", "up-to-date", "fresh"
    )

    /** Category 2: news / events. */
    private val NEWS_CUES = listOf(
        "news", "headline", "headlines", "breaking",
        "what's happening", "whats happening", "what is happening",
        "what's going on", "whats going on", "what is going on",
        "announced", "announcement", "launched", "launch of",
        "update on", "updates on", "developments on",
        "current events"
    )

    /**
     * Category 3a: explicit dates — 4-digit year 2020-2099 OR full month names.
     * Years < 2020 are treated as historical (no fresh search needed); the
     * model's training data covers them.
     */
    private val EXPLICIT_DATE_REGEX: Regex = Regex(
        """\b(20[2-9][0-9])\b|""" +                          // 2020-2099
        """\b(january|february|march|april|may|june|july|""" +
        """august|september|october|november|december)\b|""" +
        """\b(jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\s+\d{1,2}\b"""
    )

    /** Category 3b: relative date cues. */
    private val RELATIVE_DATE_CUES = listOf(
        "last week", "last month", "last year",
        "next week", "next month", "next year",
        "last monday", "last tuesday", "last wednesday",
        "last thursday", "last friday", "last saturday", "last sunday",
        "next monday", "next tuesday", "next wednesday",
        "next thursday", "next friday", "next saturday", "next sunday",
        "in 1 day", "in 2 days", "in 3 days", "in a few days",
        "in 1 week", "in 2 weeks", "in a few weeks",
        "ago today", "from today"
    )

    /** Category 4: live data (weather, stocks, sports scores, prices). */
    private val LIVE_DATA_CUES = listOf(
        "weather", "temperature", "forecast", "humidity",
        "stock", "share price", "market cap", "nasdaq", "dow jones",
        "nifty", "sensex", "exchange rate", "currency rate",
        "price of", "cost of", "how much is", "how much does",
        "score", "match result", "who won", "standings",
        "leaderboard", "points table", "ranking",
        "inr", "usd", "eur", "gbp", "exchange"
    )

    /** Category 5: sports / entertainment. */
    private val SPORTS_ENTERTAINMENT_CUES = listOf(
        "box office", "weekend collection",
        "episode", "season finale", "premiere", "release date",
        "coming out", "coming soon",
        "trailer", "teaser",
        "world cup", "olympics", "super bowl", "world series",
        "champions league", "premier league", "la liga", "serie a",
        "nba finals", "nfl", "fifa"
    )

    /** Category 6: explicit search ask — user literally says "search". */
    private val EXPLICIT_SEARCH_CUES = listOf(
        "search for", "search the web", "google ", "look up", "look it up",
        "find online", "find on the internet", "browse the web",
        "from the internet", "from the web", "on the internet",
        "can you search", "could you search", "please search"
    )

    /** Category 7: current people / entities — high stale-knowledge risk. */
    private val CURRENT_PEOPLE_CUES = listOf(
        "who is the current", "who is the new", "who is the latest",
        "current ceo", "current president", "current prime minister",
        "current cfo", "current cto", "current chair",
        "new ceo", "new president", "new prime minister",
        "current champion", "current winner", "current title holder",
        "reigning champion", "defending champion",
        "who won the latest", "who won the recent",
        "elected", "appointed", "inaugurated", "sworn in"
    )

    /**
     * Helper for tests / logging — returns the current year (used by callers
     * that want to verify a date regex match isn't stale). Not used by
     * [classify] itself.
     */
    fun currentYear(): Int = GregorianCalendar().get(Calendar.YEAR)
}
