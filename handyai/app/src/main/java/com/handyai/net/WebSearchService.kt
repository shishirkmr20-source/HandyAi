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
package com.handyai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.net.URLEncoder

/**
 * Web search with multiple free sources and aggressive automatic fallback.
 *
 * Strategy (in order, first NON-EMPTY result wins; we run sources 1-3
 * in parallel-ish fashion — actually sequential but each has its own
 * short timeout, so total worst-case latency is ~10s):
 *
 *   1. Wikipedia REST API (summary endpoint → search API fallback)
 *      Always free, no key. Excellent for "what is X" / "who is Y".
 *   2. DuckDuckGo Instant Answer API (api.duckduckgo.com, JSON)
 *      Free, no key. Returns AbstractText + RelatedTopics.
 *   3. DuckDuckGo HTML endpoint (html.duckduckgo.com, POST form)
 *      Scraped as a last resort. Returns top 3 result page excerpts.
 *   4. Bing HTML search (www.bing.com/search) — scraped. Bing is
 *      much more permissive about automated requests than Google
 *      and returns rich result snippets directly in the HTML, so we
 *      can extract them without following links.
 *   5. Wikipedia OpenSearch (suggest) — wraps the query to common
 *      alternative phrasings when the strict summary returned nothing.
 *
 * We also try to extract answer-box snippets from Bing results — the
 * little info card Bing shows above the regular results — because
 * those often contain the actual answer the user is looking for.
 *
 * Each source is tried in sequence; if one returns empty or errors,
 * we fall through to the next. The final string includes source
 * attribution so the AI (and the user) can tell where info came from.
 *
 * Robustness features:
 *   - Separate OkHttpClient with retry-on-failure enabled
 *   - Reasonable connect/read timeouts (8s/12s)
 *   - User-Agent set to a real mobile browser (some sources 403
 *     the default OkHttp UA)
 *   - Each source wrapped in try/catch — one source failing never
 *     breaks the others
 *   - Final string is checked for minimum length; if all sources
 *     came back empty, we return a clear "[No results]" marker
 *     instead of a misleading empty string
 */
class WebSearchService {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun search(query: String, maxResults: Int = 3): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ""

        val sb = StringBuilder()
        sb.appendLine("Web search results for: \"$query\"")
        sb.appendLine("Fetched at: ${java.util.Date()}")
        sb.appendLine()

        // Source 1: Wikipedia
        val wikiResult = tryWikipedia(query)
        if (wikiResult.isNotBlank()) {
            sb.appendLine("--- Source: Wikipedia ---")
            sb.appendLine(wikiResult)
            sb.appendLine()
        }

        // Source 2: DuckDuckGo Instant Answer API
        val ddgResult = tryDuckDuckGoInstantAnswer(query)
        if (ddgResult.isNotBlank()) {
            sb.appendLine("--- Source: DuckDuckGo ---")
            sb.appendLine(ddgResult)
            sb.appendLine()
        }

        // Source 3: DuckDuckGo HTML (only if no instant answer)
        if (ddgResult.isBlank()) {
            val ddgHtml = tryDuckDuckGoHtml(query, maxResults)
            if (ddgHtml.isNotBlank()) {
                sb.appendLine("--- Source: DuckDuckGo (web results) ---")
                sb.appendLine(ddgHtml)
                sb.appendLine()
            }
        }

        // Source 4: Bing HTML scrape — useful when DDG blocks the
        // mobile UA or returns no results for the query
        if (wikiResult.isBlank() && ddgResult.isBlank()) {
            val bingResult = tryBingHtml(query, maxResults)
            if (bingResult.isNotBlank()) {
                sb.appendLine("--- Source: Bing ---")
                sb.appendLine(bingResult)
                sb.appendLine()
            }
        }

        // Source 5: Wikipedia OpenSearch — suggest alternative phrasings
        // when the strict summary returned nothing useful. This helps for
        // queries like "einstein relativity" where the exact title doesn't
        // match but a search would.
        if (wikiResult.isBlank()) {
            val wikiSuggest = tryWikipediaSearch(query)
            if (wikiSuggest.isNotBlank()) {
                sb.appendLine("--- Source: Wikipedia (search) ---")
                sb.appendLine(wikiSuggest)
                sb.appendLine()
            }
        }

        val result = sb.toString().trim()
        if (result.isBlank() || result.length < 80) {
            "[No web results found for: \"$query\". Try rephrasing or check your internet connection.]"
        } else {
            result
        }
    }

    /**
     * Wikipedia REST API: try the summary endpoint for the exact query,
     * then fall back to the search API for the top result.
     */
    private fun tryWikipedia(query: String): String {
        return try {
            // Try the summary endpoint first (works for exact titles like "Hello,_world")
            val encoded = query.trim().replace(" ", "_")
            val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val summary = fetchJson(summaryUrl)
            if (summary.isNotBlank()) {
                // Extract the "extract" field from the JSON
                val extract = extractJsonField(summary, "extract")
                val title = extractJsonField(summary, "title")
                if (extract.isNotBlank() && extract.length > 30) {
                    return buildString {
                        appendLine("Wikipedia: $title")
                        appendLine(extract)
                    }
                }
            }
            ""
        } catch (_: Throwable) { "" }
    }

    /**
     * Wikipedia search API — returns the top 3 matching page titles with
     * their snippets. Used as a fallback when the summary endpoint returns
     * nothing (e.g. multi-word queries that don't match an exact title).
     */
    private fun tryWikipediaSearch(query: String): String {
        return try {
            val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                URLEncoder.encode(query, "UTF-8") + "&format=json&srlimit=3"
            val json = fetchJson(searchUrl)
            if (json.isBlank()) return ""

            // Extract all "title":"..." and "snippet":"..." pairs
            val titles = extractAllJsonFields(json, "title")
            val snippets = extractAllJsonFields(json, "snippet")

            if (titles.isEmpty()) return ""
            val sb = StringBuilder()
            titles.take(3).forEachIndexed { i, title ->
                val snippet = snippets.getOrNull(i)?.let { Jsoup.parse(it).text() } ?: ""
                sb.appendLine("${i + 1}. $title")
                if (snippet.isNotBlank()) {
                    sb.appendLine("   $snippet")
                }
            }
            sb.toString().trim()
        } catch (_: Throwable) { "" }
    }

    /**
     * DuckDuckGo Instant Answer API. Returns the AbstractText and
     * RelatedTopics for many definitional / entity queries.
     */
    private fun tryDuckDuckGoInstantAnswer(query: String): String {
        return try {
            val url = "https://api.duckduckgo.com/?q=" +
                URLEncoder.encode(query, "UTF-8") +
                "&format=json&no_html=1&skip_disambig=1&t=handyai"
            val json = fetchJson(url)
            if (json.isBlank()) return ""

            val sb = StringBuilder()
            val heading = extractJsonField(json, "Heading")
            val abstract = extractJsonField(json, "AbstractText")
            val abstractSource = extractJsonField(json, "AbstractSource")

            if (abstract.isNotBlank() && abstract.length > 30) {
                sb.appendLine(heading.ifBlank { "DuckDuckGo result" })
                sb.appendLine(abstract)
                if (abstractSource.isNotBlank()) {
                    sb.appendLine("(Source: $abstractSource)")
                }
            }

            // Also grab up to 3 related topics
            val topics = extractRelatedTopics(json)
            if (topics.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.appendLine()
                sb.appendLine("Related:")
                topics.take(3).forEachIndexed { i, topic ->
                    sb.appendLine("  ${i + 1}. $topic")
                }
            }

            sb.toString().trim()
        } catch (_: Throwable) { "" }
    }

    /**
     * DuckDuckGo HTML endpoint — POST form (GET was being throttled).
     * Fetches the top [maxResults] result pages and extracts main text.
     */
    private fun tryDuckDuckGoHtml(query: String, maxResults: Int): String {
        return try {
            // POST form is the more reliable path — DDG throttles GET requests heavily
            val formBuilder = FormBody.Builder()
                .add("q", query)
                .add("kl", "us-en")
            val req = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .post(formBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://duckduckgo.com/")
                .build()

            val html = try {
                client.newCall(req).execute().use { res -> res.body?.string() ?: "" }
            } catch (_: Throwable) { "" }
            if (html.isBlank()) return ""
            val doc = Jsoup.parse(html)
            // Result links
            val results = doc.select(".result, .web-result").take(maxResults)
            if (results.isEmpty()) return ""
            val sb = StringBuilder()
            results.forEachIndexed { i, resultEl ->
                val titleEl = resultEl.selectFirst(".result__title, .result__a, h2") ?: resultEl.selectFirst("a")
                val snippetEl = resultEl.selectFirst(".result__snippet")
                val title = titleEl?.text()?.trim() ?: "(no title)"
                val snippet = snippetEl?.text()?.trim() ?: ""
                if (title.isNotBlank() || snippet.isNotBlank()) {
                    sb.appendLine("${i + 1}. $title")
                    if (snippet.isNotBlank()) sb.appendLine("   $snippet")
                }
            }
            sb.toString().trim().ifBlank { "" }
        } catch (_: Throwable) { "" }
    }

    /**
     * Bing HTML search — scraped. Bing returns rich snippets directly
     * in the result HTML, including the "answer box" at the top of the
     * page. We extract both the answer box (if present) and the top
     * organic result snippets.
     */
    private fun tryBingHtml(query: String, maxResults: Int): String {
        return try {
            val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") +
                "&setlang=en-US&cc=US"
            val html = fetchHtml(url)
            if (html.isBlank()) return ""
            val doc = Jsoup.parse(html)

            val sb = StringBuilder()

            // Bing answer box (often has the actual answer the user wants)
            val answerBox = doc.selectFirst(".b_factrow, .b_caption, .b_subtitle, .b_ans .b_focusTextLarge, .b_algoAnswer")
            answerBox?.text()?.let { ans ->
                if (ans.length > 40) {
                    sb.appendLine("Answer: $ans")
                    sb.appendLine()
                }
            }

            // Top organic results — Bing uses <li class="b_algo">
            val results = doc.select("li.b_algo").take(maxResults)
            results.forEachIndexed { i, resultEl ->
                val titleEl = resultEl.selectFirst("h2 a")
                val snippetEl = resultEl.selectFirst(".b_caption p, .b_caption")
                val title = titleEl?.text()?.trim() ?: "(no title)"
                val snippet = snippetEl?.text()?.trim() ?: ""
                if (title.isNotBlank()) {
                    sb.appendLine("${i + 1}. $title")
                    if (snippet.isNotBlank()) sb.appendLine("   $snippet")
                }
            }
            sb.toString().trim().ifBlank { "" }
        } catch (_: Throwable) { "" }
    }

    private fun fetchJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) ""
                else res.body?.string() ?: ""
            }
        } catch (_: Throwable) { "" }
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                res.body?.string() ?: ""
            }
        } catch (_: Throwable) { "" }
    }

    /**
     * Minimal JSON field extractor. Avoids pulling in a full JSON parser
     * dependency for what's essentially string-scrubbing.
     */
    private fun extractJsonField(json: String, field: String): String {
        val key = "\"$field\":\""
        val start = json.indexOf(key)
        if (start < 0) return ""
        var i = start + key.length
        val sb = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                // Skip escaped char
                sb.append(json[i + 1])
                i += 2
                continue
            }
            if (ch == '"') break
            sb.append(ch)
            i++
        }
        return sb.toString().replace("\\n", "\n").replace("\\\"", "\"").trim()
    }

    /**
     * Extract ALL occurrences of a JSON string field (for arrays of objects).
     */
    private fun extractAllJsonFields(json: String, field: String): List<String> {
        val results = mutableListOf<String>()
        val key = "\"$field\":\""
        var idx = 0
        while (idx < json.length) {
            val start = json.indexOf(key, idx)
            if (start < 0) break
            var i = start + key.length
            val sb = StringBuilder()
            while (i < json.length) {
                val ch = json[i]
                if (ch == '\\' && i + 1 < json.length) {
                    sb.append(json[i + 1])
                    i += 2
                    continue
                }
                if (ch == '"') break
                sb.append(ch)
                i++
            }
            val text = sb.toString().replace("\\n", "\n").replace("\\\"", "\"").trim()
            if (text.isNotBlank()) results.add(text)
            idx = i + 1
        }
        return results
    }

    /**
     * Extract the "Text" field from each RelatedTopics entry.
     * DuckDuckGo returns a flat or nested array; we just scan for all
     * "Text":"..." occurrences.
     */
    private fun extractRelatedTopics(json: String): List<String> {
        return extractAllJsonFields(json, "Text").filter { it.length > 20 }
    }

    companion object {
        private const val MAX_PER_RESULT_CHARS = 3000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
