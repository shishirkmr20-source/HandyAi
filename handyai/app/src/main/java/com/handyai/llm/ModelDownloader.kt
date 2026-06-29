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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads .task model files to app-private storage.
 *
 * Per-model progress tracking:
 *   Each model has its own [DownloadState] entry in [states]. Multiple
 *   downloads can run concurrently without overwriting each other's
 *   progress — the UI reads `states[spec.id]` to render each card's
 *   progress bar independently. This fixes the bug where starting a
 *   second download while one was running would cause the progress bar
 *   to jump between the two models.
 *
 * Each download runs on Dispatchers.IO. OkHttp's internal dispatcher
 * limits concurrent requests per host (default 5), so even if the user
 * kicks off 4 downloads at once, they proceed in parallel without
 * stampeding HuggingFace.
 */
class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient()

    /** Per-model download state. Keyed by [ModelSpec.id]. */
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /** Convenience accessor for a single model's state. */
    fun stateFor(modelId: String): DownloadState =
        _states.value[modelId] ?: DownloadState.Idle

    fun modelDir(): File = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun localPath(spec: ModelSpec): File = File(modelDir(), "${spec.id}.task")

    fun isDownloaded(spec: ModelSpec): Boolean = isValidModelFile(localPath(spec))

    suspend fun download(spec: ModelSpec) = withContext(Dispatchers.IO) {
        val target = localPath(spec)
        if (target.exists()) {
            // Validate existing file — if it's a corrupted/HTML stub, delete and re-download
            if (!isValidModelFile(target)) {
                target.delete()
            } else {
                updateState(spec.id, DownloadState.Done(spec.id, target.absolutePath))
                return@withContext
            }
        }
        try {
            updateState(spec.id, DownloadState.Downloading(spec.id, 0f))
            val req = Request.Builder().url(spec.downloadUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    updateState(spec.id, DownloadState.Error(spec.id, "HTTP ${resp.code}"))
                    return@withContext
                }
                // Reject HTML/text responses — HuggingFace sometimes returns an HTML
                // error page instead of the binary .task file. Saving that would cause
                // a native crash when MediaPipe tries to load it.
                val contentType = resp.header("Content-Type") ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    updateState(spec.id, DownloadState.Error(spec.id, "Server returned HTML page, not a model file. Try again."))
                    return@withContext
                }
                val body = resp.body ?: run {
                    updateState(spec.id, DownloadState.Error(spec.id, "Empty response body"))
                    return@withContext
                }
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                var read = 0L
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                updateState(spec.id,
                                    DownloadState.Downloading(spec.id, read.toFloat() / total))
                            }
                        }
                    }
                }
                // Post-download validation — catch truncated/corrupted downloads
                if (!isValidModelFile(target)) {
                    val actualSize = target.length()
                    target.delete()
                    updateState(spec.id, DownloadState.Error(
                        spec.id,
                        "Downloaded file is invalid (${actualSize} bytes). Try again."
                    ))
                    return@withContext
                }
                updateState(spec.id, DownloadState.Done(spec.id, target.absolutePath))
            }
        } catch (t: Throwable) {
            target.delete()
            updateState(spec.id, DownloadState.Error(spec.id, t.message ?: "Download failed"))
        }
    }

    /**
     * Quick sanity check on a downloaded model file:
     *   - Must be > 100 KB (smaller = HTML error page or empty)
     *   - Must not start with '<' (HTML/XML text, not binary .task)
     */
    private fun isValidModelFile(file: File): Boolean {
        if (!file.exists() || file.length() < 100 * 1024) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(8)
                val read = input.read(head)
                read >= 1 && head[0] != '<'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Clear the saved state for one model (e.g. after the user dismisses an error). */
    fun reset(modelId: String) {
        _states.update { it - modelId }
    }

    /** Clear all saved states. */
    fun resetAll() {
        _states.value = emptyMap()
    }

    private fun updateState(modelId: String, state: DownloadState) {
        _states.update { it + (modelId to state) }
    }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val modelId: String, val fraction: Float) : DownloadState
    data class Done(val modelId: String, val path: String) : DownloadState
    data class Error(val modelId: String, val message: String) : DownloadState
}
