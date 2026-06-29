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
package com.handyai.files

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Saves an image file from app-private storage to the public gallery
 * (Pictures/HandyAi) so the user can access it from any gallery app.
 *
 * BEHAVIOR
 * ========
 * - Android 10+ (API 29+): Uses MediaStore with scoped storage. No
 *   permission required. Image goes to `Pictures/HandyAi/`.
 * - Android 8/9 (API 26-28): Uses MediaStore with the legacy DATA
 *   column. Requires WRITE_EXTERNAL_STORAGE permission (declared in
 *   manifest with maxSdkVersion=28). If permission isn't granted,
 *   returns a Result.failure with a clear message.
 *
 * On success, returns the public URI (which can be used to share or
 * open the image). On failure, returns a Result with a user-friendly
 * error message.
 */
object SaveImageHelper {

    private const val TAG = "HandyAi/SaveImage"
    private const val GALLERY_DIR = "HandyAi"

    /**
     * Save the image at [sourcePath] to the public gallery.
     *
     * @param context Application or activity context.
     * @param sourcePath Absolute path to the source image (app-private storage).
     * @return Result.success(uri) on success, Result.failure with message on error.
     */
    fun saveToGallery(context: Context, sourcePath: String): Result<Uri> {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            return Result.failure(IllegalStateException("Image file not found: $sourcePath"))
        }

        // Read & decode the source to get MIME type + bytes
        val bytes = sourceFile.readBytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            return Result.failure(IllegalStateException("Image file is corrupt or unreadable"))
        }

        // Determine MIME type — we always save as PNG for consistency
        val mimeType = "image/png"
        val displayName = "handyai_${System.currentTimeMillis()}.png"

        return try {
            // Permission check for Android 8/9
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val granted = context.checkSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    return Result.failure(
                        IllegalStateException(
                            "Storage permission required to save images on Android ${Build.VERSION.RELEASE}. " +
                                "Grant storage permission in Settings → Apps → HandyAi."
                        )
                    )
                }
            }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Scoped storage: relative path under Pictures/
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_DIR")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    // Legacy: absolute path
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        GALLERY_DIR
                    )
                    if (!dir.exists()) dir.mkdirs()
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, File(dir, displayName).absolutePath)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(IllegalStateException("Failed to create MediaStore entry"))

            resolver.openOutputStream(uri)?.use { out ->
                // Re-encode as PNG to guarantee format consistency
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } ?: run {
                resolver.delete(uri, null, null)
                return Result.failure(IllegalStateException("Failed to open output stream for $uri"))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Mark the entry as complete — visible to other apps
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Log.i(TAG, "Saved image to gallery: $uri (${bytes.size} bytes source)")
            Result.success(uri)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save image to gallery", t)
            Result.failure(t)
        }
    }

    /**
     * Check whether the app currently has permission to save images to
     * the gallery on this device. Used by the UI to decide whether to
     * request permission before calling saveToGallery().
     */
    fun hasPermission(context: Context): Boolean {
        // Android 10+: scoped storage, no permission needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        // Android 8/9: need WRITE_EXTERNAL_STORAGE
        return context.checkSelfPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
