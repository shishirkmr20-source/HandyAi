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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * ── DEDICATED WEATHER SERVICE (v1.4.8) ──────────────────────────────────────
 *
 * PROBLEM (v1.4.7 and earlier):
 *   When the user asked "temperature in Delhi" / "weather in Mumbai", the
 *   chat flow ran a generic [WebSearchService] query. Bing's answer box
 *   for weather queries is inconsistent — it sometimes returns Fahrenheit
 *   (because we send a US-locale UA), sometimes returns humidity / wind
 *   numbers alongside the temperature, and the small on-device LLM
 *   frequently picks the WRONG number and mislabels it as °C. Real-world
 *   report: app answered "140°C in Delhi" — clearly absurd.
 *
 * SOLUTION:
 *   Route weather/temperature queries through Open-Meteo instead — a free,
 *   no-API-key weather API that returns clean JSON with temperatures in
 *   Celsius by default. We:
 *     1. Detect weather/temperature intent + extract the city name from
 *        the user's query (regex, no LLM call).
 *     2. Geocode the city name via Open-Meteo's free geocoding endpoint
 *        (https://geocoding-api.open-meteo.com/v1/search?name=...).
 *     3. Fetch the current weather at that lat/lon from
 *        https://api.open-meteo.com/v1/forecast?current=temperature_2m,
 *        relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m
 *     4. Format a clean, structured summary that the LLM can quote verbatim
 *        — including the °C unit on every temperature so the model can't
 *        mislabel it.
 *
 * WHY OPEN-METEO:
 *   - Truly free, no API key, no rate-limit headaches for an indie app
 *   - Returns Celsius by default (we explicitly request metric units)
 *   - Stable JSON schema (current_weather block + weather_code mapping)
 *   - Geocoding API is also free and supports city-name disambiguation
 *
 * FALLBACK POLICY:
 *   If geocoding fails (unknown city, network error) OR the forecast
 *   endpoint fails, this service returns null — the caller should fall
 *   back to [WebSearchService] so the user still gets SOME answer.
 */
class WeatherService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Try to answer a weather/temperature question with clean data.
     *
     * @param userQuery  The raw user message (e.g. "what's the temperature in Delhi right now?").
     * @return A formatted weather summary, or null if the city couldn't be
     *         resolved or the API failed. The caller should fall back to
     *         generic web search on null.
     */
    suspend fun getWeather(userQuery: String): String? = withContext(Dispatchers.IO) {
        val city = extractCity(userQuery) ?: return@withContext null
        if (city.length < 2) return@withContext null

        // ── STEP 1: geocode the city name → lat/lon ──────────────────
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(city, "UTF-8") +
            "&count=1&language=en&format=json"
        val geoJson = try {
            fetchJson(geoUrl)
        } catch (_: Throwable) { null }
        if (geoJson.isNullOrBlank()) return@withContext null

        val lat = extractJsonField(geoJson, "latitude")
        val lon = extractJsonField(geoJson, "longitude")
        val resolvedName = extractJsonField(geoJson, "name").ifBlank { city }
        val country = extractJsonField(geoJson, "country")
        val admin1 = extractJsonField(geoJson, "admin1")  // state / province
        if (lat.isBlank() || lon.isBlank()) return@withContext null

        // ── STEP 2: fetch current weather ────────────────────────────
        // Open-Meteo returns Celsius by default — we DON'T request
        // fahrenheit= true, so every number coming back is already °C.
        val weatherUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "weather_code,wind_speed_10m,wind_direction_10m,surface_pressure" +
            "&timezone=auto"
        val wJson = try {
            fetchJson(weatherUrl)
        } catch (_: Throwable) { null }
        if (wJson.isNullOrBlank()) return@withContext null

        // ── STEP 3: parse + format ───────────────────────────────────
        // The "current" block is a flat object. We extract each field
        // individually — Open-Meteo returns values as numbers (no quotes).
        val temp = extractNumericField(wJson, "temperature_2m")
        val feelsLike = extractNumericField(wJson, "apparent_temperature")
        val humidity = extractNumericField(wJson, "relative_humidity_2m")
        val windSpeed = extractNumericField(wJson, "wind_speed_10m")
        val windDir = extractNumericField(wJson, "wind_direction_10m")
        val pressure = extractNumericField(wJson, "surface_pressure")
        val weatherCode = extractNumericField(wJson, "weather_code")

        if (temp.isBlank()) return@withContext null

        val locationLabel = buildString {
            append(resolvedName)
            if (admin1.isNotBlank() && admin1 != resolvedName) append(", ").append(admin1)
            if (country.isNotBlank()) append(", ").append(country)
        }

        buildString {
            appendLine("Live weather report (Open-Meteo):")
            appendLine("Location: $locationLabel")
            appendLine("Current temperature: ${temp}°C")
            if (feelsLike.isNotBlank()) {
                appendLine("Feels like: ${feelsLike}°C")
            }
            if (humidity.isNotBlank()) {
                appendLine("Humidity: ${humidity}%")
            }
            if (windSpeed.isNotBlank()) {
                val dir = if (windDir.isNotBlank()) windDirectionLabel(windDir.toIntOrNull()) else null
                appendLine("Wind: ${windSpeed} km/h${if (dir != null) " from $dir" else ""}")
            }
            if (pressure.isNotBlank()) {
                appendLine("Pressure: ${pressure} hPa")
            }
            if (weatherCode.isNotBlank()) {
                val desc = weatherCodeLabel(weatherCode.toIntOrNull())
                if (desc != null) appendLine("Conditions: $desc")
            }
            appendLine("Note: all temperatures are in degrees Celsius (°C). Report them with the °C unit.")
        }.trim().takeIf { it.isNotBlank() }
    }

    // ───────────────────────────────────────────────────────────────────────
    // CITY NAME EXTRACTION
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Pull a city name out of a natural-language weather query.
     *
     * Handles patterns like:
     *   - "temperature in Delhi"
     *   - "what's the weather in Mumbai"
     *   - "weather forecast for New York"
     *   - "how hot is it in Tokyo right now"
     *   - "Delhi temperature" / "Mumbai weather"
     *
     * Returns null if no recognizable city pattern is found.
     */
    internal fun extractCity(query: String): String? {
        val lower = query.lowercase().trim()

        // ── Pattern 1: "in <city>" / "for <city>" ─────────────────────
        // Capture words after the preposition until we hit a stopword or
        // punctuation. Handles multi-word cities like "New York", "Los
        // Angeles", "Rio de Janeiro".
        val prepRegex = Regex(
            """(?:temperature|weather|forecast|climate|how\s+(?:hot|cold|warm)|raining|snowing)\s+
               (?:in|of|for|at)\s+
               ([a-z][a-z\s,'-]{1,40})""".replace(Regex("""\s+"""), " "),
            RegexOption.IGNORE_CASE
        )
        prepRegex.find(query)?.let { m ->
            return cleanCity(m.groupValues[1])
        }

        // ── Pattern 2: "<city> temperature/weather" ───────────────────
        val suffixRegex = Regex(
            """([A-Z][a-zA-Z\s,'-]{1,40})\s+
               (?:temperature|weather|forecast|climate)""",
            RegexOption.IGNORE_CASE
        )
        suffixRegex.find(query)?.let { m ->
            return cleanCity(m.groupValues[1])
        }

        // ── Pattern 3: bare "weather" or "temperature" + a capitalized word ─
        // Last-resort heuristic: if the user said "weather" or "temperature"
        // and there's a capitalized word nearby, treat it as the city.
        if ("weather" in lower || "temperature" in lower || "forecast" in lower) {
            val capWordRegex = Regex("""\b([A-Z][a-zA-Z]{2,}(?:\s+[A-Z][a-zA-Z]{2,})?)\b""")
            capWordRegex.find(query)?.let { m ->
                val candidate = cleanCity(m.groupValues[1])
                // Reject obvious stopwords that might get capitalized by
                // autocorrect ("The", "What", etc.)
                if (candidate != null && candidate.lowercase() !in STOPWORDS) {
                    return candidate
                }
            }
        }

        return null
    }

    private fun cleanCity(raw: String): String? {
        // Strip trailing stopwords + punctuation
        var s = raw.trim().trimEnd(',', '.', '?', '!', ';', ':')
        // Remove trailing temporal qualifiers
        val trailingStops = listOf(
            " right now", " today", " tomorrow", " this week",
            " currently", " now", " please"
        )
        trailingStops.forEach { stop ->
            if (s.lowercase().endsWith(stop)) {
                s = s.substring(0, s.length - stop.length).trim()
            }
        }
        // Collapse internal whitespace
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s.ifBlank { null }
    }

    // ───────────────────────────────────────────────────────────────────────
    // WEATHER CODE MAPPING (WMO codes used by Open-Meteo)
    // ───────────────────────────────────────────────────────────────────────
    private fun weatherCodeLabel(code: Int?): String? {
        if (code == null) return null
        return when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45 -> "Fog"
            48 -> "Depositing rime fog"
            51 -> "Light drizzle"
            53 -> "Moderate drizzle"
            55 -> "Dense drizzle"
            56 -> "Light freezing drizzle"
            57 -> "Dense freezing drizzle"
            61 -> "Slight rain"
            63 -> "Moderate rain"
            65 -> "Heavy rain"
            66 -> "Light freezing rain"
            67 -> "Heavy freezing rain"
            71 -> "Slight snowfall"
            73 -> "Moderate snowfall"
            75 -> "Heavy snowfall"
            77 -> "Snow grains"
            80 -> "Slight rain showers"
            81 -> "Moderate rain showers"
            82 -> "Violent rain showers"
            85 -> "Slight snow showers"
            86 -> "Heavy snow showers"
            95 -> "Thunderstorm"
            96 -> "Thunderstorm with slight hail"
            99 -> "Thunderstorm with heavy hail"
            else -> null
        }
    }

    private fun windDirectionLabel(degrees: Int?): String? {
        if (degrees == null) return null
        val dirs = listOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
        return dirs[((degrees.toDouble() / 22.5).toInt() % 16 + 16) % 16]
    }

    // ───────────────────────────────────────────────────────────────────────
    // JSON HELPERS
    // ───────────────────────────────────────────────────────────────────────

    private fun fetchJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) ""
            else res.body?.string() ?: ""
        }
    }

    /**
     * Extract a string field from JSON. Same logic as in WebSearchService.
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
     * Extract a numeric field from JSON — handles both `"x": 42` and
     * `"x": 42.5` (no quotes around the value). Returns the raw number
     * string, or "" if not found.
     */
    private fun extractNumericField(json: String, field: String): String {
        // Look for `"field":` followed by optional whitespace + a number
        val key = "\"$field\""
        val idx = json.indexOf(key)
        if (idx < 0) return ""
        var i = idx + key.length
        // Skip ':' and whitespace
        while (i < json.length && (json[i] == ':' || json[i].isWhitespace())) i++
        // Read until we hit a non-numeric character (excluding '.' and '-')
        val sb = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch.isDigit() || ch == '.' || ch == '-' || ch == '+') {
                sb.append(ch)
                i++
            } else break
        }
        return sb.toString().trim()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** Words that should NOT be treated as city names even if capitalized. */
        private val STOPWORDS = setOf(
            "the", "what", "how", "when", "where", "why", "who",
            "today", "tomorrow", "yesterday", "now", "currently",
            "right", "this", "that", "those", "these",
            "weather", "temperature", "forecast", "climate",
            "is", "are", "was", "were", "will", "would", "could",
            "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday"
        )

        /**
         * Quick check: does this query look like a weather/temperature
         * question? Used by the chat flow to decide whether to invoke
         * the weather service at all (before falling back to web search).
         */
        fun isWeatherQuery(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("weather") ||
                lower.contains("temperature") ||
                lower.contains("forecast") ||
                lower.contains("how hot") ||
                lower.contains("how cold") ||
                lower.contains("how warm") ||
                lower.contains("humidity")
        }
    }
}
