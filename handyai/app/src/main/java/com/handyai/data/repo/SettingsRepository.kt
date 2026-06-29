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
package com.handyai.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "handyai_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val INTERNET_ENABLED = booleanPreferencesKey("internet_enabled")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        // Legacy key from v1.2.6 and earlier — kept for one release so we
        // can migrate users who had dark mode on to a sensible dark theme.
        // Removed from the UI; new code uses THEME_ID instead.
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ACTIVE_MODEL_PATH = stringPreferencesKey("active_model_path")
        val ACTIVE_MODEL_NAME = stringPreferencesKey("active_model_name")
        // v1.4.7: separate active-model keys for the cloud vision and
        // image-gen engines. Each stores the catalog ModelSpec.id so the
        // app can re-resolve the spec on launch. null = use the catalog's
        // recommended default for that category.
        val ACTIVE_VISION_MODEL_ID = stringPreferencesKey("active_vision_model_id")
        val ACTIVE_IMGGEN_MODEL_ID = stringPreferencesKey("active_imggen_model_id")
        // Theme picker — string id of a HandyAiThemeId entry.
        // null/missing = CREAM (the original default — no visual change
        // on upgrade from v1.2.7 and earlier).
        val THEME_ID = stringPreferencesKey("theme_id")
    }

    val internetEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[INTERNET_ENABLED] ?: false }

    val ttsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[TTS_ENABLED] ?: false }

    // Legacy — exposed only for the one-time migration in MainActivity.
    // After migration this is no longer read; new code uses themeId.
    val darkThemeLegacy: Flow<Boolean?> =
        context.dataStore.data.map { it[DARK_THEME] }

    /**
     * The user's chosen theme id, or null if no theme has been picked
     * yet (which means: apply the migration logic in MainActivity, or
     * fall back to CREAM if there's nothing to migrate).
     */
    val themeId: Flow<String?> =
        context.dataStore.data.map { it[THEME_ID] }

    val activeModelPath: Flow<String?> =
        context.dataStore.data.map { it[ACTIVE_MODEL_PATH] }

    val activeModelName: Flow<String?> =
        context.dataStore.data.map { it[ACTIVE_MODEL_NAME] }

    /** v1.4.7: id of the cloud VLM the user picked on the Models page.
     *  null = use the catalog's recommended VISION model. */
    val activeVisionModelId: Flow<String?> =
        context.dataStore.data.map { it[ACTIVE_VISION_MODEL_ID] }

    /** v1.4.7: id of the cloud image-gen model the user picked on the
     *  Models page. null = use the catalog's recommended IMAGE_GEN model. */
    val activeImgGenModelId: Flow<String?> =
        context.dataStore.data.map { it[ACTIVE_IMGGEN_MODEL_ID] }

    suspend fun setInternet(enabled: Boolean) = context.dataStore.edit {
        it[INTERNET_ENABLED] = enabled
    }

    suspend fun setTts(enabled: Boolean) = context.dataStore.edit {
        it[TTS_ENABLED] = enabled
    }

    /** Persist the user's theme choice. Pass null to clear (revert to
     *  default = CREAM). */
    suspend fun setThemeId(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(THEME_ID) else it[THEME_ID] = id
    }

    suspend fun setActiveModel(path: String?, name: String?) = context.dataStore.edit {
        if (path == null) it.remove(ACTIVE_MODEL_PATH) else it[ACTIVE_MODEL_PATH] = path
        if (name == null) it.remove(ACTIVE_MODEL_NAME) else it[ACTIVE_MODEL_NAME] = name
    }

    /** v1.4.7: set the active cloud vision model. Pass null to revert to
     *  the catalog's recommended default. */
    suspend fun setActiveVisionModel(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(ACTIVE_VISION_MODEL_ID) else it[ACTIVE_VISION_MODEL_ID] = id
    }

    /** v1.4.7: set the active cloud image-gen model. Pass null to revert
     *  to the catalog's recommended default. */
    suspend fun setActiveImgGenModel(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(ACTIVE_IMGGEN_MODEL_ID) else it[ACTIVE_IMGGEN_MODEL_ID] = id
    }
}
