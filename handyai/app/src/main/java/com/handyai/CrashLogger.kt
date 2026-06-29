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

import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught-exception handler that writes crash details to a file
 * in the app's internal files directory.
 *
 * WHY THIS EXISTS
 * ───────────────
 * On-device LLM crashes are often NATIVE (SIGSEGV / SIGABRT from MediaPipe
 * or libllm.so). Such crashes bypass the JVM entirely — no try/catch can
 * intercept them, and Android's default behavior is to silently kill the
 * process with only a brief tombstone in logcat (which the user can't see
 * without ADB).
 *
 * For JVM-level crashes (OutOfMemoryError, IndexOutOfBoundsException,
 * NullPointerException, etc.), this handler captures:
 *   - Timestamp
 *   - Device model, Android version, ABI
 *   - App version
 *   - Heap status (used / max / native)
 *   - Full stack trace
 *
 * The user can then view this log from Settings → "Crash log" and share
 * it back for diagnosis. The most recent N crashes are kept; older ones
 * are auto-trimmed.
 *
 * LIMITATIONS
 * ───────────
 * This does NOT catch native crashes (SIGSEGV, SIGABRT). For those, the
 * process is killed before any Java code can run. To capture native
 * crashes, we'd need a breakpad integration — out of scope for now.
 * However, the LACK of a JVM crash log entry after an "app crashed"
 * report IS itself a signal that the crash was native (likely OOM-kill
 * or a MediaPipe native fault), which helps narrow the diagnosis.
 */
object CrashLogger {

    private const val MAX_LOGS_TO_KEEP = 5
    private const val LOG_DIR_NAME = "crash_logs"
    private const val CURRENT_LOG_NAME = "current_crash.txt"

    /**
     * Install the global uncaught-exception handler. Call this once from
     * [HandyAiApp.onCreate] before any other code runs.
     *
     * Chains with the existing default handler — after writing the crash
     * log, we delegate to the previous handler so Android's normal crash
     * dialog / process-restart behavior still occurs.
     */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(context, thread, throwable)
            } catch (_: Throwable) {
                // Never let the logger itself cause a secondary crash.
            }
            // Delegate to the previous handler so the process dies normally.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Path to the current crash log file (or null if no crash has been
     * recorded). Exposed so SettingsScreen can read + share it.
     */
    fun currentLogPath(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR_NAME).apply { mkdirs() }
        return File(dir, CURRENT_LOG_NAME)
    }

    /**
     * Returns the text of the current crash log, or null if no crash
     * has been recorded since install.
     */
    fun readCurrentLog(context: Context): String? {
        val f = currentLogPath(context)
        return if (f.exists() && f.length() > 0) f.readText() else null
    }

    /**
     * Delete the current crash log. Called when the user dismisses it
     * from Settings.
     */
    fun clearCurrentLog(context: Context) {
        runCatching { currentLogPath(context).delete() }
    }

    private fun writeCrashLog(context: Context, thread: Thread, t: Throwable) {
        val dir = File(context.filesDir, LOG_DIR_NAME).apply { mkdirs() }

        // Rotate: rename current → timestamped archive, keep only the latest N
        val current = File(dir, CURRENT_LOG_NAME)
        if (current.exists()) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date(current.lastModified()))
            runCatching { current.renameTo(File(dir, "crash_$ts.txt")) }
        }
        // Trim old archives
        dir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOGS_TO_KEEP)
            ?.forEach { runCatching { it.delete() } }

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("========================================")
        pw.println("HandyAi Crash Report")
        pw.println("========================================")
        pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        pw.println("Thread: ${thread.name} (id=${thread.id})")
        pw.println()
        pw.println("── Device ──────────────────────────────")
        pw.println("Manufacturer: ${Build.MANUFACTURER}")
        pw.println("Model: ${Build.MODEL}")
        pw.println("Device: ${Build.DEVICE}")
        pw.println("Product: ${Build.PRODUCT}")
        pw.println("Brand: ${Build.BRAND}")
        pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        pw.println()
        pw.println("── App ─────────────────────────────────")
        val pm = context.packageManager
        val pkgName = context.packageName
        val pkgInfo = pm.getPackageInfo(pkgName, 0)
        pw.println("Package: $pkgName")
        pw.println("Version: ${pkgInfo.versionName} (${pkgInfo.versionCode})")
        pw.println()
        pw.println("── Memory at crash ─────────────────────")
        val rt = Runtime.getRuntime()
        pw.println("JVM max heap: ${rt.maxMemory() / (1024 * 1024)} MB")
        pw.println("JVM used heap: ${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)} MB")
        pw.println("JVM free heap: ${rt.freeMemory() / (1024 * 1024)} MB")
        pw.println("JVM total: ${rt.totalMemory() / (1024 * 1024)} MB")
        try {
            val nativeHeap = Debug.getNativeHeapSize() / (1024 * 1024)
            val nativeUsed = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            pw.println("Native heap total: $nativeHeap MB")
            pw.println("Native heap used: $nativeUsed MB")
        } catch (_: Throwable) {
            pw.println("Native heap: (unavailable)")
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            pw.println("Device total RAM: ${mi.totalMem / (1024 * 1024)} MB")
            pw.println("Device avail RAM: ${mi.availMem / (1024 * 1024)} MB")
            pw.println("Low memory: ${mi.lowMemory}")
            pw.println("Memory class: ${am.memoryClass} MB")
            pw.println("Large memory class: ${am.largeMemoryClass} MB")
        } catch (_: Throwable) {
            pw.println("Device RAM: (unavailable)")
        }
        pw.println()
        pw.println("── Stack trace ─────────────────────────")
        t.printStackTrace(pw)
        pw.println()
        pw.println("── End of report ───────────────────────")
        pw.flush()

        runCatching { current.writeText(sw.toString()) }
    }
}
