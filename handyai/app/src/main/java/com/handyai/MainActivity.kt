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
package com.handyai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handyai.ui.nav.HandyAiNavGraph
import com.handyai.ui.theme.HandyAiTheme
import com.handyai.ui.theme.HandyAiThemeId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = (application as HandyAiApp)
        setContent {
            // Observe the user's theme choice. Null = no theme picked yet,
            // which kicks off a one-time migration from the legacy
            // dark_theme boolean (v1.2.7 and earlier) to the new theme_id
            // string — users who had dark mode on are moved to OceanDeep,
            // everyone else stays on Cream (the original default).
            val themeIdRaw by app.settingsRepository.themeId.collectAsStateWithLifecycle(initialValue = null)
            val legacyDark by app.settingsRepository.darkThemeLegacy.collectAsStateWithLifecycle(initialValue = null)

            // Migration: if theme_id is unset but the legacy dark_theme
            // flag was explicitly set, persist the equivalent new theme.
            // This runs exactly once per upgraded install.
            LaunchedEffect(themeIdRaw, legacyDark) {
                if (themeIdRaw == null && legacyDark != null) {
                    // User had explicitly chosen light or dark in v1.2.7 —
                    // map to the closest new theme (dark → OceanDeep,
                    // light → Cream, the default).
                    val migrated = if (legacyDark == true) HandyAiThemeId.OCEAN_DEEP.id
                                   else HandyAiThemeId.CREAM.id
                    app.settingsRepository.setThemeId(migrated)
                }
            }

            // Resolve to enum. Null raw value (before migration writes)
            // falls back to CREAM, which is the original default.
            val themeId = HandyAiThemeId.fromId(themeIdRaw)

            HandyAiTheme(themeId = themeId) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HandyAiNavGraph()
                }
            }
        }
    }
}
