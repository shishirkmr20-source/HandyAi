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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.handyai.data.repo.AttachmentCache
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xssf.extractor.XSSFExcelExtractor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.hssf.extractor.ExcelExtractor
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.sl.usermodel.SlideShowFactory
import org.apache.poi.sl.extractor.SlideShowExtractor
import org.jsoup.Jsoup
import java.io.InputStream

/**
 * Extracts plain text from common file types so the on-device LLM can
 * "read" attached documents.
 *
 * Supported formats:
 *
 *   Plain text family (no parser needed):
 *     .txt, .md, .csv, .json, .log, .xml, .yaml, .yml, .tsv, .ini, .conf
 *
 *   Documents:
 *     .pdf   → PDFBox-Android, with a raw-stream regex fallback for
 *              PDFs where PDFBox silently returns empty (encrypted
 *              with empty-pwd OK, embedded fonts weird, scanned with
 *              a broken text layer, etc.). The fallback scrapes text
 *              from the PDF's BT...ET content streams directly.
 *     .docx  → Apache POI XWPF (paragraphs + tables)
 *     .doc   → Apache POI HWPF (legacy)
 *     .rtf   → simple regex stripper
 *     .odt   → zip + content.xml tag strip
 *
 *   Spreadsheets:
 *     .xlsx  → Apache POI XSSF
 *     .xls   → Apache POI HSSF
 *
 *   Presentations:
 *     .pptx  → Apache POI XSLF
 *     .ppt   → Apache POI HSLF
 *
 *   Web / markup:
 *     .html, .htm  → Jsoup
 *
 *   Images: routed to ImageAnalyzer (ML Kit OCR + image labeling)
 *
 * ── Caching ─────────────────────────────────────────────────────────
 * Extraction is expensive (PDFBox on a 50-page PDF can take 1–2s).
 * Results are cached in the AttachmentCache (Room table) keyed by
 * (URI, size, lastModified). On a cache hit we return immediately
 * without re-parsing. The cache is wiped on every app launch
 * (HandyAiApp.onCreate), so extracted text never persists across
 * sessions — the user wanted "once the app is closed the stored data
 * is deleted".
 */
class FileTextExtractor(
    private val context: Context,
    private val imageAnalyzer: ImageAnalyzer? = null,
    private val cloudImageAnalyzer: CloudImageAnalyzer? = null,
    private val cache: AttachmentCache? = null
) {

    data class Result(
        val text: String,
        val label: String,
        val mimeHint: String,
        val charsTruncated: Boolean
    )

    /** Returns true if the file extension / mime hints at an image
     *  that we can OCR + label on-device. */
    fun isImage(displayName: String, mime: String? = null): Boolean {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS || (mime?.startsWith("image/") == true)
    }

    /**
     * Extract text from [uri].
     *
     * @param preferCloud When true (user is online + has internet enabled),
     *   image attachments are routed through [CloudImageAnalyzer] first —
     *   HuggingFace BLIP produces a natural-language description instead
     *   of just ML Kit labels. If the cloud call fails or [preferCloud]
     *   is false, falls back to the on-device [ImageAnalyzer]. Documents
     *   (PDF/DOCX/...) always use the on-device extractors — they're
     *   already excellent and don't benefit from cloud processing.
     */
    suspend fun extract(uri: Uri, displayName: String, preferCloud: Boolean = false): Result {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        android.util.Log.i(TAG, "Extracting: name=$displayName ext=$ext mime=$mime")

        // ---- Cache lookup ----------------------------------------------------
        // Read (size, lastModified) fingerprint from the ContentResolver.
        // On a hit, return the cached text immediately — no parsing.
        val (sizeBytes, lastModified) = fingerprint(uri)
        if (cache != null && sizeBytes >= 0) {
            val hit = cache.get(uri.toString(), sizeBytes, lastModified)
            if (hit != null) {
                android.util.Log.i(TAG, "Cache HIT: ${hit.text.length} chars, method=${hit.method}")
                return Result(
                    text = hit.text,
                    label = hit.label,
                    mimeHint = mime,
                    charsTruncated = hit.truncated
                )
            }
        }

        // ---- Image path ----
        // When the user is online + has internet enabled, try the cloud
        // vision model (BLIP) first — it produces a natural-language
        // description instead of just ML Kit labels. If cloud fails or
        // we're offline, fall back to the on-device ML Kit analyzer.
        // Documents (PDF/DOCX/etc.) skip this path entirely — on-device
        // extraction is already excellent for them.
        var method = "unknown"
        // Initialize to defaults so Kotlin's definite-assignment analysis
        // is satisfied — the image branch has nested if/else where the
        // compiler can't prove every path assigns all three. The defaults
        // are overwritten in every real code path below.
        var text: String = ""
        var label: String = ""
        var truncated: Boolean = false

        if (isImage(displayName, mime)) {
            // ── CLOUD VISION (online) ─────────────────────────────────
            // Try the cloud analyzer first when available + requested.
            // On any failure (network, rate-limit, parse), we fall through
            // to the on-device path so the user always gets SOME result.
            var cloudUsed = false
            if (preferCloud && cloudImageAnalyzer != null) {
                android.util.Log.i(TAG, "Trying cloud image analyzer (BLIP) for $displayName")
                val cloudText = try {
                    cloudImageAnalyzer.analyze(uri, displayName)
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "Cloud image analyzer threw: ${t.message}")
                    null
                }
                if (cloudText != null && cloudText.isNotBlank()) {
                    val cap = MAX_CHARS
                    truncated = cloudText.length > cap
                    text = if (truncated) cloudText.substring(0, cap) else cloudText
                    label = "image:$displayName"
                    method = "cloud-blip"
                    cloudUsed = true
                    android.util.Log.i(TAG, "Cloud image analysis OK: ${text.length} chars")
                }
            }

            // ── ON-DEVICE FALLBACK (ML Kit OCR + labels) ─────────────
            if (!cloudUsed) {
                if (imageAnalyzer != null) {
                    android.util.Log.i(TAG, "Routing to on-device ImageAnalyzer (OCR + labeling)")
                    val ir = try {
                        imageAnalyzer.analyze(uri, displayName)
                    } catch (t: Throwable) {
                        android.util.Log.e(TAG, "On-device image analysis failed for $displayName", t)
                        return Result(
                            text = "[Image analysis error: ${t.message ?: t.javaClass.simpleName}]",
                            label = "image:$displayName",
                            mimeHint = mime,
                            charsTruncated = false
                        )
                    }
                    android.util.Log.i(TAG, "On-device image analysis OK: ${ir.text.length} chars, hasContent=${ir.hasContent}")
                    val cap = MAX_CHARS
                    truncated = ir.text.length > cap
                    text = if (truncated) ir.text.substring(0, cap) else ir.text
                    label = ir.label
                    method = "mlkit-ocr"
                } else {
                    // No analyzer configured at all — surface a clear error
                    text = "[Image analysis not configured — cannot extract content from this image]"
                    label = "image:$displayName"
                    truncated = false
                    method = "none"
                }
            }
        } else {
            val raw = when (ext) {
                // Plain text family
                "txt", "md", "csv", "json", "log", "xml", "yaml", "yml",
                "tsv", "ini", "conf", "text", "markdown" -> {
                    method = "plain-text"
                    readTextStream(uri)
                }

                // PDF — PDFBox with raw-stream fallback
                "pdf" -> {
                    val (t, m) = readPdfWithFallback(uri)
                    method = m
                    t
                }

                // Word
                "docx" -> { method = "poi-docx"; readDocx(uri) }
                "doc"  -> { method = "poi-doc";  readDoc(uri) }

                "rtf"  -> { method = "rtf-regex";  readRtf(uri) }
                "odt"  -> { method = "odt-zip";    readOdt(uri) }

                // Excel
                "xlsx" -> { method = "poi-xlsx"; readXlsx(uri) }
                "xls"  -> { method = "poi-xls";  readXls(uri) }

                // PowerPoint
                "pptx" -> { method = "poi-pptx"; readPptx(uri) }
                "ppt"  -> { method = "poi-ppt";  readPpt(uri) }

                // HTML
                "html", "htm" -> { method = "jsoup"; readHtml(uri) }

                // Unknown — best-effort: try as text, then give up
                else -> {
                    method = "fallback-text"
                    readTextStream(uri).ifBlank {
                        "[Unknown file type: .$ext — could not extract text. Try saving as .txt, .pdf, .docx, .html, or .csv]"
                    }
                }
            }

            val cap = MAX_CHARS
            truncated = raw.length > cap
            text = if (truncated) raw.substring(0, cap) else raw
            label = "file:$displayName"
        }

        android.util.Log.i(
            TAG,
            "Extraction complete: ${text.length} chars (raw=${text.length}, truncated=$truncated, method=$method)" +
                if (text.startsWith("[")) " — marker: ${text.take(120)}" else ""
        )

        // ---- Cache store ----------------------------------------------------
        // Only cache successful extractions — skip error markers so the
        // next attempt actually retries instead of returning the same
        // "[PDF parse error: ...]" forever.
        if (cache != null && sizeBytes >= 0 && !looksLikeError(text)) {
            try {
                cache.put(
                    uri = uri.toString(),
                    displayName = displayName,
                    mime = mime,
                    sizeBytes = sizeBytes,
                    lastModified = lastModified,
                    text = text,
                    label = label,
                    truncated = truncated,
                    method = method
                )
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Cache put failed (non-fatal): ${t.message}")
            }
        }

        return Result(
            text = text,
            label = label,
            mimeHint = mime,
            charsTruncated = truncated
        )
    }

    private fun looksLikeError(text: String): Boolean =
        text.contains("parse error", ignoreCase = true) ||
            text.contains("could not extract", ignoreCase = true) ||
            text.contains("Unknown file type", ignoreCase = true) ||
            text.contains("analysis error", ignoreCase = true)

    /**
     * Read (size, lastModified) for a content URI. Used as a cache key.
     * Returns (-1, -1) if the resolver can't tell us — in which case
     * we skip the cache (always re-extract).
     */
    private fun fingerprint(uri: Uri): Pair<Long, Long> {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val size = c.getColumnIndex(OpenableColumns.SIZE).let { i ->
                    if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else -1L
                }
                val lm = c.getColumnIndex("last_modified").let { i ->
                    if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else -1L
                }
                size to lm
            } ?: (-1L to -1L)
        } catch (_: Throwable) {
            -1L to -1L
        }
    }

    private fun readTextStream(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    /**
     * PDF extraction with two fallbacks:
     *   1. PDFBox-Android (works for ~90% of PDFs)
     *   2. Raw-stream regex scrape (catches PDFs where PDFBox returns
     *      empty but the text actually lives in BT...ET blocks —
     *      common with PDFs from older generators, scanned PDFs with
     *      a partial text layer, etc.)
     *
     * Returns (text, method-used).
     */
    private fun readPdfWithFallback(uri: Uri): Pair<String, String> {
        // Try 1: PDFBox
        var pdfboxText = ""
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    pdfboxText = PDFTextStripper().getText(doc)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "PDFBox parse failed for $uri", t)
        }
        if (pdfboxText.isNotBlank() && pdfboxText.length > 20) {
            return pdfboxText to "pdfbox"
        }

        // Try 2: Raw stream scrape
        android.util.Log.i(TAG, "PDFBox returned empty/short, trying raw stream scrape")
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return (pdfboxText.ifBlank { "[PDF parse error: stream unreadable]" } to "pdfbox-empty")
            val scraped = scrapePdfTextStreams(bytes)
            if (scraped.isNotBlank() && scraped.length > 5) {
                android.util.Log.i(TAG, "Raw stream scrape recovered ${scraped.length} chars")
                return scraped to "pdf-stream-scrape"
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Stream scrape failed for $uri", t)
        }

        // Both failed
        val err = if (pdfboxText.isEmpty()) {
            "[PDF parse error: PDFBox returned empty and stream scrape found no text. " +
                "The PDF may be a pure image scan (no text layer) — try attaching an image instead, " +
                "or convert the PDF to .docx/.txt.]"
        } else {
            "[PDF parse error: ${pdfboxText.take(150)}]"
        }
        return err to "pdf-failed"
    }

    /**
     * Scrapes text out of a PDF's content streams without using a real
     * PDF parser. PDFs store visible text inside `(...) Tj` and
     * `[...] TJ` operators within BT...ET blocks. We pull the strings
     * out with a regex and unescape the common PDF string escapes.
     *
     * This is the same fallback strategy the FastAPI server used
     * (with `re.findall(rb"\((?:[^()\\]|\\.)*\)", data)`), reimplemented
     * in Kotlin. It catches the cases where PDFBox's layout engine
     * returns nothing but the text IS in the file.
     */
    private fun scrapePdfTextStreams(data: ByteArray): String {
        // Match (... ) groups, allowing escaped parens/backslashes inside.
        // PDF spec: inside a literal string, \( \) \\ are the escapes.
        val pattern = Regex("""\((?:[^()\\]|\\.)*\)""")
        val matches = pattern.findAll(data.toString(Charsets.ISO_8859_1))
        val sb = StringBuilder()
        for (m in matches) {
            var s = m.value
            // Strip the wrapping parens
            s = s.substring(1, s.length - 1)
            // Unescape
            s = s.replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
            // Filter out garbage (binary-looking strings)
            if (s.length >= 2 && s.any { it.isLetterOrDigit() }) {
                sb.append(s).append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun readDocx(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                XWPFDocument(input).use { doc ->
                    XWPFWordExtractor(doc).text
                }
            } ?: ""
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "DOCX parse failed for $uri", t)
            "[DOCX parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    private fun readDoc(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                HWPFDocument(input).use { doc ->
                    WordExtractor(doc).text
                }
            } ?: ""
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "DOC parse failed for $uri", t)
            "[DOC parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    /**
     * RTF — Rich Text Format. Minimal regex stripper.
     */
    private fun readRtf(uri: Uri): String {
        return try {
            val raw = readTextStream(uri)
            if (raw.isBlank()) return ""
            var text = raw
            text = Regex("\\\\u-?\\d+\\??").replace(text, "")
            text = Regex("\\\\[a-zA-Z]+-?\\d* ?").replace(text, "")
            text = Regex("\\\\[^a-zA-Z]").replace(text, "")
            text = text.replace("{", "").replace("}", "")
            text = Regex("\\s+").replace(text, " ").trim()
            text
        } catch (t: Throwable) {
            "[RTF parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    /**
     * ODT (OpenDocument Text) is a zip with content.xml inside.
     */
    private fun readOdt(uri: Uri): String {
        return try {
            val sb = StringBuilder()
            java.util.zip.ZipInputStream(context.contentResolver.openInputStream(uri)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        val text = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser()).text()
                        sb.append(text)
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            sb.toString().ifBlank {
                "[ODT parse error: no content.xml found in archive]"
            }
        } catch (t: Throwable) {
            "[ODT parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    private fun readXlsx(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                XSSFWorkbook(input).use { wb ->
                    XSSFExcelExtractor(wb).text
                }
            } ?: ""
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "XLSX parse failed for $uri", t)
            "[XLSX parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    private fun readXls(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                HSSFWorkbook(input).use { wb ->
                    ExcelExtractor(wb).text
                }
            } ?: ""
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "XLS parse failed for $uri", t)
            "[XLS parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    private fun readPptx(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val show = SlideShowFactory.create(input)
                try {
                    SlideShowExtractor(show).text
                } finally {
                    try { show.close() } catch (_: Throwable) {}
                }
            } ?: ""
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "PPTX parse failed for $uri", t)
            "[PPTX parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    private fun readPpt(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val show = SlideShowFactory.create(input)
                try {
                    SlideShowExtractor(show).text
                } finally {
                    try { show.close() } catch (_: Throwable) {}
                }
            } ?: ""
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "PPT parse failed for $uri", t)
            "[PPT parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    private fun readHtml(uri: Uri): String {
        return try {
            val raw = readTextStream(uri)
            if (raw.isBlank()) return ""
            val doc = Jsoup.parse(raw)
            doc.select("script,style,noscript,iframe,nav,footer,header,aside,form").remove()
            val main = doc.selectFirst("article, main, [role=main], .content, #content") ?: doc
            val text = main.text()
            if (text.length < 100) doc.text() else text
        } catch (t: Throwable) {
            "[HTML parse error: ${t.message ?: t.javaClass.simpleName}]"
        }
    }

    companion object {
        private const val TAG = "HandyAi/FileExtractor"
        const val MAX_CHARS = 16_000

        /** File extensions routed to ImageAnalyzer (ML Kit OCR + labels). */
        private val IMAGE_EXTS = setOf(
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif"
        )
    }
}
