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
     * @param preferCloud Kept for API compatibility but IGNORED for images
     *   as of v1.4.1. The user explicitly requested: "while extracting the
     *   text from image it is not able to extract full text which it was
     *   able to do so before, dont use the cloud api. just use the native
     *   text extractor which easily extracts texts quickly."
     *
     *   Reasoning:
     *     - Cloud BLIP returns a one-line caption ("A photo of a plant on
     *       a desk"), NOT the actual text in the image. For documents /
     *       screenshots / signs / chat captures, the user wants the full
     *       OCR text, which ML Kit returns natively in 100-500ms.
     *     - ML Kit's bundled Latin recognizer runs fully offline, no API
     *       key, no network latency.
     *     - Cloud analyzer still exists (CloudImageAnalyzer.kt) and is
     *       kept for potential future use, but is no longer called from
     *       the image extraction path. If you want cloud captions, call
     *       CloudImageAnalyzer directly from a different code path.
     *
     *   Documents (PDF/DOCX/etc.) always used on-device parsers and are
     *   unaffected by this change.
     */
    suspend fun extract(uri: Uri, displayName: String, preferCloud: Boolean = false): Result {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        android.util.Log.i(TAG, "Extracting: name=$displayName ext=$ext mime=$mime (preferCloud=$preferCloud IGNORED for images)")

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
            // ── v1.4.5: HYBRID CLOUD + ON-DEVICE IMAGE PIPELINE ──────
            //
            // The user reported: "ocr is not giving the correct results on
            // what is written in the image. sometimes it says there is no
            // image attached."
            //
            // Root causes:
            //   1. ML Kit's bundled Latin recognizer returns empty for
            //      stylized fonts, low-contrast screenshots, and non-Latin
            //      glyphs. When OCR returned empty, the prompt fell back to
            //      "no legible text was detected" + label list — and small
            //      LLMs deflected with "I cannot see the image".
            //   2. The previous v1.4.1 change completely disabled the cloud
            //      analyzer for images, leaving only ML Kit. That was a
            //      regression for screenshots / scanned docs where cloud
            //      OCR is dramatically better.
            //
            // v1.4.5 fix — run BOTH paths and MERGE the results:
            //   - On-device ML Kit OCR + labels (always — fast, offline)
            //   - CloudImageAnalyzer when online (BLIP-large caption +
            //     OCR.space cloud OCR fallback when ML Kit returns empty)
            //
            // The merged output explicitly says "AN IMAGE IS ATTACHED" and
            // lists every signal we have (caption + on-device OCR + cloud
            // OCR + labels), so the LLM can never deflect with "no image
            // attached" — it has multiple independent sources of image
            // content right there in the prompt.
            //
            // Failure handling: if either path fails, we use what we got
            // from the other. If both fail (offline + ML Kit crashed), we
            // surface a clear error marker.
            val onDeviceResult = if (imageAnalyzer != null) {
                android.util.Log.i(TAG, "Image: running on-device ML Kit OCR + labels")
                try {
                    imageAnalyzer.analyze(uri, displayName)
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "On-device image analysis failed for $displayName", t)
                    ImageAnalyzer.Result(
                        text = "[On-device image analysis error: ${t.message ?: t.javaClass.simpleName}]",
                        label = "image:$displayName",
                        hasContent = false
                    )
                }
            } else null

            // Try cloud analyzer when (a) it's configured AND (b) the user
            // is online AND (c) preferCloud is true. preferCloud is set to
            // internetEnabled.value by the caller, so this only fires when
            // the user has the internet toggle ON.
            val cloudResult = if (cloudImageAnalyzer != null && preferCloud) {
                android.util.Log.i(TAG, "Image: running cloud vision (BLIP-large + OCR.space)")
                try {
                    cloudImageAnalyzer.analyze(uri, displayName)
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "Cloud image analysis failed for $displayName: ${t.message}")
                    null
                }
            } else null

            // Merge the two into a single, comprehensive image-content block.
            val mergedText = mergeImageResults(displayName, onDeviceResult, cloudResult)
            val ir = ImageAnalyzer.Result(
                text = mergedText,
                label = "image:$displayName",
                hasContent = mergedText.length > 80  // heuristic: header alone is ~80 chars
            )

            android.util.Log.i(TAG,
                "Image analysis complete: ${ir.text.length} chars, " +
                "hasContent=${ir.hasContent}, " +
                "onDevice=${onDeviceResult != null}, " +
                "cloud=${cloudResult != null}")

            val cap = MAX_CHARS
            truncated = ir.text.length > cap
            text = if (truncated) ir.text.substring(0, cap) else ir.text
            label = ir.label
            method = if (cloudResult != null) "mlkit+cloud" else "mlkit-ocr"
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
     * Merge on-device + cloud image-analysis results into a single
     * comprehensive image-content block. v1.4.5.
     *
     * The output explicitly says "AN IMAGE IS ATTACHED" and lists every
     * signal we have, so the LLM can never deflect with "I can't see
     * the image" — it has multiple independent sources of image content
     * right there in the prompt.
     *
     * Sources merged (whichever are available):
     *   - On-device ML Kit OCR text (visible text in the image)
     *   - On-device ML Kit image labels (object/scene tags)
     *   - Cloud BLIP-large caption (natural-language scene description)
     *   - Cloud OCR.space text (high-accuracy cloud OCR — better than
     *     ML Kit for stylized fonts, low-contrast screenshots, non-Latin)
     *
     * Both OCR texts are included verbatim (with their source labeled)
     * so the LLM can cross-reference. Duplicates are tolerated — better
     * to repeat a line of text than miss it.
     */
    private fun mergeImageResults(
        displayName: String,
        onDevice: ImageAnalyzer.Result?,
        cloud: String?
    ): String {
        // Extract the on-device OCR text from the onDevice Result. The
        // ImageAnalyzer wraps its output in "---IMAGE CONTENT START---"
        // markers — we parse out the "Visible text:" line to get just
        // the OCR portion.
        val onDeviceOcr = onDevice?.text?.let { extractField(it, "Visible text:") } ?: ""
        // v1.4.6: "Scene labels:" replaces the old "What the image
        // shows:" label. We also extract the new "Detected objects"
        // block from the on-device analyzer.
        val onDeviceLabels = onDevice?.text?.let {
            extractField(it, "Scene labels:").ifBlank {
                extractField(it, "What the image shows:")
            }
        } ?: ""
        val onDeviceObjects = onDevice?.text?.let { extractBlock(it, "Detected objects") } ?: ""

        // Extract cloud caption + cloud OCR from the cloud analyzer's
        // output string. Same "---IMAGE CONTENT START---" wrapper format.
        val cloudCaption = cloud?.let { extractField(it, "Cloud-vision caption:") } ?: ""
        val cloudOcr = cloud?.let { extractField(it, "Cloud OCR text") } ?: ""

        val sb = StringBuilder()
        sb.appendLine("---IMAGE CONTENT START---")
        sb.appendLine("AN IMAGE IS ATTACHED to this chat. The image file is '$displayName'.")
        sb.appendLine("Below is the analyzed content of the image (from on-device ML Kit + cloud vision).")
        sb.appendLine("Read it carefully. NEVER say no image is attached — the image IS attached and its content is below.")
        sb.appendLine()

        // Cloud caption (best for "describe this photo" questions)
        if (cloudCaption.isNotBlank()) {
            sb.appendLine("Image description (cloud vision caption):")
            sb.appendLine(cloudCaption.trim())
            sb.appendLine()
        }

        // Cloud OCR (highest OCR accuracy — better than ML Kit for hard cases)
        if (cloudOcr.isNotBlank()) {
            sb.appendLine("Visible text in the image (cloud OCR — high accuracy):")
            sb.appendLine(cloudOcr.trim())
            sb.appendLine()
        }

        // On-device OCR (ML Kit) — fast, offline, but lower accuracy
        if (onDeviceOcr.isNotBlank() && !onDeviceOcr.startsWith("no legible")) {
            // Skip if it's identical to cloud OCR (avoid duplication)
            val normalizedOnDevice = onDeviceOcr.trim().replace(Regex("\\s+"), " ")
            val normalizedCloud = cloudOcr.trim().replace(Regex("\\s+"), " ")
            if (normalizedOnDevice != normalizedCloud) {
                sb.appendLine("Visible text in the image (on-device ML Kit OCR):")
                sb.appendLine(onDeviceOcr.trim())
                sb.appendLine()
            }
        }

        // v1.4.6: On-device object detection — countable objects with
        // spatial positions. Most useful signal for "what's in this
        // photo" questions when cloud caption is unavailable.
        if (onDeviceObjects.isNotBlank()) {
            sb.appendLine("Objects detected in the image (on-device ML Kit, with positions):")
            sb.appendLine(onDeviceObjects.trim())
            sb.appendLine()
        }

        // On-device labels (object/scene tags)
        if (onDeviceLabels.isNotBlank() && !onDeviceLabels.startsWith("no clear")) {
            sb.appendLine("Scene labels: $onDeviceLabels")
            sb.appendLine()
        }

        // If we got NOTHING useful, say so explicitly with strong language
        // — the LLM must not deflect with "I can't see the image". The
        // image IS attached; we just couldn't extract anything useful.
        if (cloudCaption.isBlank() && cloudOcr.isBlank() &&
            (onDeviceOcr.isBlank() || onDeviceOcr.startsWith("no legible")) &&
            (onDeviceLabels.isBlank() || onDeviceLabels.startsWith("no clear")) &&
            onDeviceObjects.isBlank()) {
            sb.appendLine("Note: No specific text or labels could be extracted from the image.")
            sb.appendLine("The image IS attached — if the user asks about it, say the image was attached but the analysis did not detect readable text or recognizable objects. Do NOT say no image was attached.")
        }

        sb.appendLine("---IMAGE CONTENT END---")
        return sb.toString()
    }

    /**
     * Extract a multi-line block following a header line. Used for the
     * "Detected objects" block which contains multiple indented bullet
     * lines rather than a single field value.
     *
     * Returns everything from the line AFTER the header up to the next
     * blank line or "---IMAGE CONTENT END---", with leading whitespace
     * preserved (so bullet indentation survives).
     */
    private fun extractBlock(text: String, header: String): String {
        val idx = text.indexOf(header)
        if (idx < 0) return ""
        // Find end of the header line
        val lineEnd = text.indexOf('\n', idx)
        if (lineEnd < 0) return ""
        val rest = text.substring(lineEnd + 1)
        // Block ends at the first blank line OR the end marker
        val endIdx = rest.indexOf("---IMAGE CONTENT END---")
        val candidate = if (endIdx >= 0) rest.substring(0, endIdx) else rest
        val blankIdx = candidate.indexOf("\n\n")
        return if (blankIdx >= 0) candidate.substring(0, blankIdx).trim()
        else candidate.trim()
    }

    /**
     * Extract the value of a labeled field from an ImageAnalyzer /
     * CloudImageAnalyzer output string. Both use the same format:
     *
     *   ---IMAGE CONTENT START---
     *   ...
     *   Field label: value here, possibly multi-line
     *   Next field: ...
     *   ...
     *   ---IMAGE CONTENT END---
     *
     * Returns the value text (everything between the label and the next
     * blank line or end-of-block), or "" if the label isn't found.
     */
    private fun extractField(text: String, label: String): String {
        val idx = text.indexOf(label)
        if (idx < 0) return ""
        val after = text.substring(idx + label.length).trimStart()
        // Read up to the next blank line OR the end marker
        val endIdx = after.indexOf("---IMAGE CONTENT END---")
        val blockEnd = if (endIdx >= 0) endIdx else after.length
        val block = after.substring(0, blockEnd)
        // Cut at the first blank line (next field starts after a blank line)
        val blankLineIdx = block.indexOf("\n\n")
        return if (blankLineIdx >= 0) block.substring(0, blankLineIdx).trim()
        else block.trim()
    }

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
