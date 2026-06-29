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
package com.handyai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * HandyAi theme system.
 *
 * The app ships with 5 hand-tuned themes the user can pick from the
 * Settings screen:
 *
 *   1. Cream Pastel    (light) — the original default. Warm cream
 *                              background + indigo/coral/mint/lavender
 *                              accents. Soft and friendly.
 *   2. Mint Bloom      (light) — fresh garden feel. Pale mint background
 *                              + emerald primary + pink/amber accents.
 *   3. Lavender Dream  (light) — dreamy floral. Pale lavender background
 *                              + purple primary + pink/indigo accents.
 *   4. Ocean Deep      (dark)  — aurora-over-ocean. Very dark navy +
 *                              bright cyan/violet/emerald accents.
 *   5. Sunset Noir     (dark)  — sunset-on-plum. Dark plum +
 *                              magenta/orange/rose/amber accents.
 *
 * Each theme bundles a Material3 [ColorScheme] (drives MaterialTheme
 * semantics — primary, surface, background, etc.) AND a [HandyAiPalette]
 * (extended accent colors used directly by chat bubbles, the send
 * button gradient, the doodle background, and status dots).
 *
 * WHY NOT "follow system"?
 * ========================
 * The previous implementation had a 3-way radio (System / Light /
 * Dark) that just toggled between two palettes (LightPalette and
 * DarkPalette). With 5 distinct color identities, "follow system"
 * no longer maps cleanly — the user picks *one* theme and that's
 * what they see regardless of system dark/light. This is the same
 * model used by Telegram, WhatsApp, and the Gmail mobile theme
 * picker.
 */

// ──────────────────────────────────────────────────────────────────────────
//  Public API
// ──────────────────────────────────────────────────────────────────────────

/**
 * Stable identifier for a HandyAi theme. Persisted to DataStore as a
 * string so adding new themes in the future doesn't break old installs
 * (unknown ids fall back to [CREAM]).
 */
enum class HandyAiThemeId(val id: String, val displayName: String, val isDark: Boolean) {
    CREAM("cream", "Cream Pastel", false),
    MINT_BLOOM("mint_bloom", "Mint Bloom", false),
    LAVENDER_DREAM("lavender_dream", "Lavender Dream", false),
    OCEAN_DEEP("ocean_deep", "Ocean Deep", true),
    SUNSET_NOIR("sunset_noir", "Sunset Noir", true);

    companion object {
        /** Resolve a persisted id string back to the enum. Unknown /
         *  null / empty values fall back to [CREAM] (the original
         *  default — existing users see no visual change on upgrade). */
        fun fromId(id: String?): HandyAiThemeId =
            entries.firstOrNull { it.id == id } ?: CREAM
    }
}

/**
 * A complete theme specification — the Material3 [ColorScheme] plus
 * the extended [HandyAiPalette] plus a few swatch colors used by the
 * Settings picker UI to give the user a preview of the theme's
 * personality.
 */
@Stable
data class HandyAiThemeSpec(
    val id: HandyAiThemeId,
    val colorScheme: ColorScheme,
    val palette: HandyAiPalette,
    /** 4–5 representative colors shown as small swatches in the
     *  Settings theme picker. Order: [primary, secondary, tertiary,
     *  background, surface] — gives the user a feel for the theme. */
    val swatches: List<Color>
)

/**
 * Extended palette — exposed via [LocalHandyAiPalette] so any
 * composable can grab a specific accent without digging through
 * MaterialTheme.colorScheme.
 *
 * WHY HAVE BOTH palette AND ColorScheme?
 * ======================================
 * Material3's ColorScheme has a fixed set of semantic roles
 * (primary, secondary, tertiary, error, surface, background, ...).
 * HandyAi needs MORE accents than that — the chat input bar uses
 * 5 distinct colors (indigo send, coral file-attach, lavender
 * image-attach, mint voice, sun mic). The extended palette carries
 * those without polluting ColorScheme with non-semantic names.
 */
@Stable
data class HandyAiPalette(
    val indigo: Color,
    val coral: Color,
    val mint: Color,
    val sun: Color,
    val lavender: Color,
    val userBubbleStart: Color,
    val userBubbleEnd: Color,
    val aiBubble: Color,
    val aiBubbleBorder: Color,
    val chatBackground: Color,
    val statusActive: Color,
    val statusPaused: Color,
    val statusDone: Color,
    val statusArchived: Color,
)

val LocalHandyAiPalette = androidx.compose.runtime.compositionLocalOf<HandyAiPalette> {
    error("No HandyAiPalette provided — wrap your composable in HandyAiTheme {}")
}

/** Vertical gradient used on user chat bubbles — gives a soft sense of depth. */
fun userBubbleBrush(p: HandyAiPalette): Brush =
    Brush.verticalGradient(listOf(p.userBubbleStart, p.userBubbleEnd))

/** Horizontal gradient used on the Send button — indigo → lavender. */
fun sendButtonBrush(p: HandyAiPalette): Brush =
    Brush.horizontalGradient(listOf(p.indigo, p.lavender))

/** Convenience accessor — `val palette = handyAiPalette()` inside any
 *  composable under HandyAiTheme. */
@Composable
fun handyAiPalette(): HandyAiPalette = LocalHandyAiPalette.current

// ──────────────────────────────────────────────────────────────────────────
//  Theme definitions
// ──────────────────────────────────────────────────────────────────────────

/** All five themes, in the order they should appear in the Settings picker. */
object HandyAiThemes {
    val all: List<HandyAiThemeSpec> = listOf(
        CreamPastel, MintBloom, LavenderDream, OceanDeep, SunsetNoir
    )

    fun byId(id: HandyAiThemeId): HandyAiThemeSpec =
        all.first { it.id == id }
}

// ── Theme 1: Cream Pastel (light, the original default) ───────────────────

private val CreamPastel = HandyAiThemeSpec(
    id = HandyAiThemeId.CREAM,
    colorScheme = lightColorScheme(
        primary = Color(0xFF4452D6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDDE3FF),
        onPrimaryContainer = Color(0xFF0E1340),
        secondary = Color(0xFFE55A4A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFD5CD),
        onSecondaryContainer = Color(0xFF3A0A00),
        tertiary = Color(0xFF2BA787),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBFF3E0),
        onTertiaryContainer = Color(0xFF00332A),
        background = Color(0xFFFDF6E8),
        onBackground = Color(0xFF1A1C25),
        surface = Color(0xFFFFFBF4),
        onSurface = Color(0xFF1A1C25),
        surfaceVariant = Color(0xFFE7EAF3),
        onSurfaceVariant = Color(0xFF444B5E),
        outline = Color(0xFF7B8298),
        outlineVariant = Color(0xFFD2D5E0),
        error = Color(0xFFE55A4A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFD5CD),
        onErrorContainer = Color(0xFF3A0A00),
    ),
    palette = HandyAiPalette(
        indigo = Color(0xFF6C7CFF),
        coral = Color(0xFFFF8A7A),
        mint = Color(0xFF5FD9B5),
        sun = Color(0xFFFFD166),
        lavender = Color(0xFFC8A8FF),
        userBubbleStart = Color(0xFFD9E2FF),
        userBubbleEnd = Color(0xFFB3C2FF),
        aiBubble = Color(0xFFFFFFFF),
        aiBubbleBorder = Color(0xFFE5E7F0),
        chatBackground = Color(0xFFFDF6E8),
        statusActive = Color(0xFF3FAE6A),
        statusPaused = Color(0xFFE2A53A),
        statusDone = Color(0xFF4A78D6),
        statusArchived = Color(0xFF8A8F99),
    ),
    swatches = listOf(
        Color(0xFF4452D6), Color(0xFFE55A4A), Color(0xFF2BA787),
        Color(0xFFFDF6E8), Color(0xFFFFFBF4)
    )
)

// ── Theme 2: Mint Bloom (light, fresh garden) ─────────────────────────────
//
//  Pale mint background, emerald primary, pink + amber accents.
//  Inspired by early-spring gardens — green leaves, pink blossoms,
//  yellow pollen. Reads as calm and optimistic.

private val MintBloom = HandyAiThemeSpec(
    id = HandyAiThemeId.MINT_BLOOM,
    colorScheme = lightColorScheme(
        primary = Color(0xFF10B981),       // emerald 500
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC5F4DC),
        onPrimaryContainer = Color(0xFF003924),
        secondary = Color(0xFFEC4899),       // pink 500
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFD6E5),
        onSecondaryContainer = Color(0xFF3E001F),
        tertiary = Color(0xFFF59E0B),       // amber 500
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE0B0),
        onTertiaryContainer = Color(0xFF2C1A00),
        background = Color(0xFFF0FAF5),       // very pale mint
        onBackground = Color(0xFF0E1F17),
        surface = Color(0xFFF5FFFA),       // white-mint
        onSurface = Color(0xFF0E1F17),
        surfaceVariant = Color(0xFFD9ECE2),
        onSurfaceVariant = Color(0xFF3F4E45),
        outline = Color(0xFF6F8077),
        outlineVariant = Color(0xFFBFD3C8),
        error = Color(0xFFE11D48),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    ),
    palette = HandyAiPalette(
        indigo = Color(0xFF10B981),       // emerald (drives send button)
        coral = Color(0xFFF43F5E),       // rose (file attach)
        mint = Color(0xFF34D399),       // light emerald (voice)
        sun = Color(0xFFFCD34D),       // amber (mic)
        lavender = Color(0xFFC8B6FF),       // soft purple (image attach)
        userBubbleStart = Color(0xFFC5F4DC),
        userBubbleEnd = Color(0xFF86E3B5),
        aiBubble = Color(0xFFFFFFFF),
        aiBubbleBorder = Color(0xFFD9ECE2),
        chatBackground = Color(0xFFF0FAF5),
        statusActive = Color(0xFF10B981),
        statusPaused = Color(0xFFF59E0B),
        statusDone = Color(0xFF0EA5E9),
        statusArchived = Color(0xFF7C8B82),
    ),
    swatches = listOf(
        Color(0xFF10B981), Color(0xFFEC4899), Color(0xFFF59E0B),
        Color(0xFFF0FAF5), Color(0xFFF5FFFA)
    )
)

// ── Theme 3: Lavender Dream (light, dreamy floral) ────────────────────────
//
//  Pale lavender background, purple primary, pink + indigo accents.
//  Inspired by a lavender field at dusk — soft purples, rosy pinks,
//  a hint of twilight indigo. Reads as dreamy and creative.

private val LavenderDream = HandyAiThemeSpec(
    id = HandyAiThemeId.LAVENDER_DREAM,
    colorScheme = lightColorScheme(
        primary = Color(0xFF9333EA),       // purple 600
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEFD6FF),
        onPrimaryContainer = Color(0xFF2E004E),
        secondary = Color(0xFFEC4899),       // pink 500
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFD6E5),
        onSecondaryContainer = Color(0xFF3E001F),
        tertiary = Color(0xFF6366F1),       // indigo 500
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDDE0FF),
        onTertiaryContainer = Color(0xFF000A66),
        background = Color(0xFFF5F0FF),       // very pale lavender
        onBackground = Color(0xFF1D1430),
        surface = Color(0xFFFAF5FF),       // white-lavender
        onSurface = Color(0xFF1D1430),
        surfaceVariant = Color(0xFFE7DFF5),
        onSurfaceVariant = Color(0xFF49405E),
        outline = Color(0xFF7A708A),
        outlineVariant = Color(0xFFCBC0DC),
        error = Color(0xFFE11D48),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    ),
    palette = HandyAiPalette(
        indigo = Color(0xFFA855F7),       // purple 500 (drives send button)
        coral = Color(0xFFF472B6),       // pink 400 (file attach)
        mint = Color(0xFF2DD4BF),       // teal 400 (voice)
        sun = Color(0xFFFCD34D),       // amber 300 (mic)
        lavender = Color(0xFFC4B5FD),       // violet 300 (image attach)
        userBubbleStart = Color(0xFFEFD6FF),
        userBubbleEnd = Color(0xFFCB9BFF),
        aiBubble = Color(0xFFFFFFFF),
        aiBubbleBorder = Color(0xFFE7DFF5),
        chatBackground = Color(0xFFF5F0FF),
        statusActive = Color(0xFF10B981),
        statusPaused = Color(0xFFF59E0B),
        statusDone = Color(0xFF6366F1),
        statusArchived = Color(0xFF8B8198),
    ),
    swatches = listOf(
        Color(0xFF9333EA), Color(0xFFEC4899), Color(0xFF6366F1),
        Color(0xFFF5F0FF), Color(0xFFFAF5FF)
    )
)

// ── Theme 4: Ocean Deep (dark, aurora over ocean) ─────────────────────────
//
//  Very dark navy background, bright cyan primary, violet + emerald
//  accents. Inspired by an aurora over a midnight ocean — deep blues
//  with electric neon highlights. Reads as dramatic and modern.

private val OceanDeep = HandyAiThemeSpec(
    id = HandyAiThemeId.OCEAN_DEEP,
    colorScheme = darkColorScheme(
        primary = Color(0xFF22D3EE),       // cyan 400
        onPrimary = Color(0xFF003640),
        primaryContainer = Color(0xFF0E7490),
        onPrimaryContainer = Color(0xFFB5F4FF),
        secondary = Color(0xFFA78BFA),       // violet 400
        onSecondary = Color(0xFF1E0F4A),
        secondaryContainer = Color(0xFF4C1D95),
        onSecondaryContainer = Color(0xFFE0CCFF),
        tertiary = Color(0xFF34D399),       // emerald 400
        onTertiary = Color(0xFF00382B),
        tertiaryContainer = Color(0xFF065F46),
        onTertiaryContainer = Color(0xFFBFF3DD),
        background = Color(0xFF0B1426),       // very dark navy
        onBackground = Color(0xFFE2E8F0),
        surface = Color(0xFF0F1B33),       // dark navy
        onSurface = Color(0xFFE2E8F0),
        surfaceVariant = Color(0xFF1B2845),
        onSurfaceVariant = Color(0xFFB0BFD8),
        outline = Color(0xFF6F81A3),
        outlineVariant = Color(0xFF38456A),
        error = Color(0xFFFB7185),
        onError = Color(0xFF4A0010),
        errorContainer = Color(0xFF8B1F2C),
        onErrorContainer = Color(0xFFFFD9DE),
    ),
    palette = HandyAiPalette(
        indigo = Color(0xFF22D3EE),       // cyan (drives send button)
        coral = Color(0xFFFB7185),       // rose 400 (file attach)
        mint = Color(0xFF34D399),       // emerald (voice)
        sun = Color(0xFFFCD34D),       // amber (mic)
        lavender = Color(0xFFA78BFA),       // violet (image attach)
        userBubbleStart = Color(0xFF0E7490),
        userBubbleEnd = Color(0xFF155E75),
        aiBubble = Color(0xFF0F1B33),
        aiBubbleBorder = Color(0xFF1B2845),
        chatBackground = Color(0xFF0B1426),
        statusActive = Color(0xFF34D399),
        statusPaused = Color(0xFFFCD34D),
        statusDone = Color(0xFF22D3EE),
        statusArchived = Color(0xFF6F81A3),
    ),
    swatches = listOf(
        Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFF34D399),
        Color(0xFF0B1426), Color(0xFF0F1B33)
    )
)

// ── Theme 5: Sunset Noir (dark, sunset on plum) ───────────────────────────
//
//  Dark plum background, magenta primary, orange + rose + amber
//  accents. Inspired by a sunset reflecting on a dark plum wall —
//  warm neons against deep burgundy. Reads as warm and nightlife-y.

private val SunsetNoir = HandyAiThemeSpec(
    id = HandyAiThemeId.SUNSET_NOIR,
    colorScheme = darkColorScheme(
        primary = Color(0xFFE879F9),       // fuchsia 400
        onPrimary = Color(0xFF3A0040),
        primaryContainer = Color(0xFF86198F),
        onPrimaryContainer = Color(0xFFFFD9FE),
        secondary = Color(0xFFFB923C),       // orange 400
        onSecondary = Color(0xFF3A1700),
        secondaryContainer = Color(0xFF9A3412),
        onSecondaryContainer = Color(0xFFFFDCBE),
        tertiary = Color(0xFFF43F5E),       // rose 500
        onTertiary = Color(0xFF3E001F),
        tertiaryContainer = Color(0xFF9F1239),
        onTertiaryContainer = Color(0xFFFFD9DE),
        background = Color(0xFF1A0F1F),       // very dark plum
        onBackground = Color(0xFFF3E5F5),
        surface = Color(0xFF241429),       // dark plum
        onSurface = Color(0xFFF3E5F5),
        surfaceVariant = Color(0xFF362036),
        onSurfaceVariant = Color(0xFFD6BCD9),
        outline = Color(0xFF9F7BA3),
        outlineVariant = Color(0xFF584058),
        error = Color(0xFFFB7185),
        onError = Color(0xFF4A0010),
        errorContainer = Color(0xFF8B1F2C),
        onErrorContainer = Color(0xFFFFD9DE),
    ),
    palette = HandyAiPalette(
        indigo = Color(0xFFE879F9),       // fuchsia (drives send button)
        coral = Color(0xFFFB923C),       // orange (file attach)
        mint = Color(0xFFFCD34D),       // amber (voice — warm theme needs warm "voice")
        sun = Color(0xFFFACC15),       // yellow (mic)
        lavender = Color(0xFFF472B6),       // pink (image attach)
        userBubbleStart = Color(0xFF86198F),
        userBubbleEnd = Color(0xFF5B0F66),
        aiBubble = Color(0xFF241429),
        aiBubbleBorder = Color(0xFF362036),
        chatBackground = Color(0xFF1A0F1F),
        statusActive = Color(0xFF34D399),
        statusPaused = Color(0xFFFCD34D),
        statusDone = Color(0xFFFB923C),
        statusArchived = Color(0xFF9F7BA3),
    ),
    swatches = listOf(
        Color(0xFFE879F9), Color(0xFFFB923C), Color(0xFFF43F5E),
        Color(0xFF1A0F1F), Color(0xFF241429)
    )
)

// ──────────────────────────────────────────────────────────────────────────
//  Theme entry composable
// ──────────────────────────────────────────────────────────────────────────

/**
 * Apply the given theme to the composition.
 *
 * Usage:
 *   HandyAiTheme(themeId = HandyAiThemeId.OCEAN_DEEP) { ... }
 *
 * The theme is resolved to a [HandyAiThemeSpec] which provides both
 * the Material3 [ColorScheme] (drives MaterialTheme semantics) and
 * the extended [HandyAiPalette] (drives HandyAi-specific accents).
 */
@Composable
fun HandyAiTheme(
    themeId: HandyAiThemeId = HandyAiThemeId.CREAM,
    content: @Composable () -> Unit
) {
    val spec = HandyAiThemes.byId(themeId)
    CompositionLocalProvider(LocalHandyAiPalette provides spec.palette) {
        MaterialTheme(
            colorScheme = spec.colorScheme,
            typography = HandyAiTypography,
            content = content
        )
    }
}
