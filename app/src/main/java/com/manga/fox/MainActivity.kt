package com.manga.fox

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ═══════════════════════ CONSTANTS ═══════════════════════

internal const val ACTION_SHOW_RESULTS = "com.manga.fox.SHOW_RESULTS"
internal const val ACTION_STOP_CAPTURE = "com.manga.fox.STOP_CAPTURE"
internal const val PREFS_NAME = "manga_fox_v2_preferences"
internal const val PREF_FONT_SIZE = "font_size_sp"
internal const val PREF_FONT_FAMILY = "font_family"
internal const val PREF_FONT_BOLD = "font_bold"
internal const val PREF_FONT_FILE = "font_file_path"
internal const val PREF_FONT_LABEL = "font_file_label"
internal const val PREF_AUTO_FONT_FIT = "auto_font_fit_enabled"
internal const val PREF_RESULTS_EDIT_MODE = "results_edit_mode"
internal const val PREF_LENS_SIZE = "lens_size_dp"
internal const val PREF_LENS_ALPHA = "lens_alpha"
internal const val PREF_LENS_GLYPH = "lens_custom_glyph"
internal const val PREF_LENS_COLOR = "lens_custom_color"
internal const val PREF_SOURCE_LANGUAGE = "translation_source_language"
internal const val PREF_ONBOARDING_SEEN = "onboarding_seen_v1"
internal const val PREF_TRANSLATE_SFX = "translate_japanese_sfx"
internal const val PREF_LEARNED_TRANSLATIONS = "learned_translation_dictionary"
internal const val REQUEST_PICK_FONT = 713
internal const val REQUEST_SAVE_PDF = 714
internal const val REQUEST_EXPORT_PHANTOM_PROJECT = 715
internal const val REQUEST_IMPORT_PHANTOM_PROJECT = 716
internal const val MAX_CAPTURE_FRAMES = 120
internal const val MAX_IMPORT_BYTES = 600L * 1024L * 1024L
internal const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L

// ═══════════════════════ TRUSTED FONTS ═══════════════════════

private data class TrustedFont(val label: String, val sha256: String)

private val TRUSTED_FONTS: Map<String, TrustedFont> = mapOf(
    "NotoKufiArabic-Regular.ttf" to TrustedFont("Noto Kufi Arabic", ""),
    "NotoNaskhArabic-Regular.ttf" to TrustedFont("Noto Naskh Arabic", ""),
    "NotoSansArabic-Regular.ttf" to TrustedFont("Noto Sans Arabic", ""),
    "Amiri-Regular.ttf" to TrustedFont("Amiri", ""),
    "Tajawal-Regular.ttf" to TrustedFont("Tajawal", ""),
    "ReemKufi-Regular.ttf" to TrustedFont("Reem Kufi", ""),
    "DroidKufi-Bold.ttf" to TrustedFont("Droid Arabic Kufi Bold", "")
)

// ═══════════════════════ RUNTIME ═══════════════════════

object MangaFoxRuntime {
    private const val SESSION_MANIFEST = "mfox_session_v1.json"

    @Synchronized fun persist(context: Context) {
        runCatching {
            val root = JSONObject()
                .put("title", resultTitle)
                .put("quality", qualitySummary)
                .put("sources", JSONArray(sourcePages))
                .put("results", JSONArray(resultPages))
                .put("layers", JSONArray(translationLayerFiles))
            val target = File(context.filesDir, SESSION_MANIFEST)
            val temp = File(context.filesDir, "$SESSION_MANIFEST.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        }
    }

    @Synchronized fun restore(context: Context) {
        runCatching {
            val manifest = File(context.filesDir, SESSION_MANIFEST)
            if (!manifest.isFile) {
                fun indexed(files: List<File>, prefix: String): List<File> = files
                    .filter { it.isFile && it.name.startsWith(prefix) && it.length() > 0L }
                    .sortedBy { it.name.substringAfterLast('_').substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
                val legacyResults = indexed(
                    context.filesDir.listFiles()?.toList().orEmpty() + context.cacheDir.listFiles()?.toList().orEmpty(),
                    "mfox_result_")
                val legacySources = indexed(context.filesDir.listFiles()?.toList().orEmpty(), "mfox_source_")
                val legacyLayers = indexed(context.filesDir.listFiles()?.toList().orEmpty(), "mfox_layers_")
                val count = minOf(legacyResults.size, legacySources.size)
                if (count > 0) {
                    resultPages = legacyResults.take(count).map { it.absolutePath }
                    sourcePages = legacySources.take(count).map { it.absolutePath }
                    translationLayerFiles = (0 until count).map { legacyLayers.getOrNull(it)?.absolutePath.orEmpty() }
                    resultTitle = "الفصل السابق"
                    persist(context)
                }
                return
            }
            if (manifest.length() > MAX_MANIFEST_BYTES) return
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            fun stringList(key: String): List<String> {
                val array = root.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).map { i -> array.optString(i, "") }
            }
            val sources = stringList("sources"); val results = stringList("results"); val layers = stringList("layers")
            val count = minOf(sources.size, results.size)
            sourcePages = sources.take(count)
            resultPages = results.take(count)
            translationLayerFiles = (0 until count).map { layers.getOrNull(it).orEmpty() }
            resultTitle = root.optString("title", "الفصل المترجم")
            qualitySummary = root.optString("quality", qualitySummary)
            if (resultPages.none { File(it).isFile }) {
                sourcePages = emptyList(); resultPages = emptyList(); translationLayerFiles = emptyList()
            }
        }
    }

    @Synchronized fun removePage(context: Context, index: Int) {
        if (index !in resultPages.indices) return
        val removed = listOfNotNull(resultPages.getOrNull(index), sourcePages.getOrNull(index), translationLayerFiles.getOrNull(index))
        resultPages = resultPages.toMutableList().also { it.removeAt(index) }
        if (index in sourcePages.indices) sourcePages = sourcePages.toMutableList().also { it.removeAt(index) }
        if (index in translationLayerFiles.indices) translationLayerFiles = translationLayerFiles.toMutableList().also { it.removeAt(index) }
        removed.forEach { path -> runCatching { File(path).delete() } }
        persist(context)
    }

    @Synchronized fun gcOrphans(context: Context) {
        runCatching {
            val live = (resultPages + sourcePages + translationLayerFiles).toSet()
            val cutoff = System.currentTimeMillis() - 7L * 86400_000L
            context.filesDir.listFiles()?.forEach { file ->
                val name = file.name
                val managed = name.startsWith("mfox_result_") || name.startsWith("mfox_source_") ||
                    name.startsWith("mfox_layers_") || name.startsWith("mfox_capture_")
                if (managed && file.absolutePath !in live && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    @Volatile var status: String = "جاهز للترجمة"
    @Volatile var resultPages: List<String> = emptyList()
    @Volatile var sourcePages: List<String> = emptyList()
    @Volatile var resultTitle: String = "الفصل المترجم"
    @Volatile var lastError: String = ""
    @Volatile var progressPercent: Int = 0
    @Volatile var progressDetail: String = ""
    @Volatile var detectedBlocks: Int = 0
    @Volatile var translatedBlocks: Int = 0
    @Volatile var failedBlocks: Int = 0
    @Volatile var skippedBlocks: Int = 0
    @Volatile var qualitySummary: String = "لم تبدأ ترجمة بعد"
    @Volatile var failedBlockSamples: List<String> = emptyList()
    @Volatile var translationLayerFiles: List<String> = emptyList()
}

// ═══════════════════════ GLOSSARY CACHE ═══════════════════════

object MangaFoxGlossaryCache {
    @Volatile private var cachedRaw: String? = null
    @Volatile private var parsed: Map<String, String> = emptyMap()

    fun lookup(context: Context, normalizedKey: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_LEARNED_TRANSLATIONS, "{}") ?: "{}"
        if (raw !== cachedRaw) {
            val fresh = runCatching {
                val json = JSONObject(raw)
                val map = HashMap<String, String>(json.length() * 2)
                val it = json.keys()
                while (it.hasNext()) {
                    val key = it.next(); val value = json.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) map[key] = value
                }
                map
            }.getOrElse { emptyMap() }
            parsed = fresh; cachedRaw = raw
        }
        return parsed[normalizedKey]
    }

    fun put(context: Context, normalizedKey: String, arabic: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = runCatching { JSONObject(prefs.getString(PREF_LEARNED_TRANSLATIONS, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        json.put(normalizedKey, arabic)
        prefs.edit().putString(PREF_LEARNED_TRANSLATIONS, json.toString()).apply()
        cachedRaw = null
    }

    fun invalidate() { cachedRaw = null; parsed = emptyMap() }

    fun normalizeKey(source: String): String {
        val stripped = Normalizer.normalize(source, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return stripped.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }
}

// ═══════════════════════ KNOWLEDGE STORE ═══════════════════════

class MangaFoxKnowledgeStore(context: Context) {
    private val prefs = context.getSharedPreferences("mfox_phantom_knowledge_v1", Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile private var cachedGlossary: List<GlossaryEntry>? = null
    @Volatile private var cachedGlossaryMap: Map<String, String>? = null
    @Volatile private var cachedCharacters: List<CharacterEntry>? = null

    data class GlossaryEntry(val source: String, val arabic: String, val note: String = "")
    data class CharacterEntry(val name: String, val voice: String, val notes: String = "")

    fun glossary(): List<GlossaryEntry> = synchronized(lock) {
        cachedGlossary ?: readGlossaryRaw().also { cachedGlossary = it }
    }
    private fun readGlossaryRaw(): List<GlossaryEntry> {
        val array = runCatching { JSONArray(prefs.getString(KEY_GLOSSARY, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { obj ->
                val s = obj.optString("source").trim(); val a = obj.optString("arabic").trim()
                if (s.isBlank() || a.isBlank()) null else GlossaryEntry(s, a, obj.optString("note"))
            }
        }
    }
    private fun glossaryMap(): Map<String, String> = synchronized(lock) {
        cachedGlossaryMap ?: run {
            val map = HashMap<String, String>()
            glossary().forEach { map[normalize(it.source)] = it.arabic }
            map.also { cachedGlossaryMap = it }
        }
    }
    fun saveGlossaryEntry(entry: GlossaryEntry) = synchronized(lock) {
        val values = glossary().filterNot { it.source.equals(entry.source, true) }.toMutableList()
        values.add(entry)
        val array = JSONArray()
        values.forEach { array.put(JSONObject().put("source", it.source).put("arabic", it.arabic).put("note", it.note)) }
        prefs.edit().putString(KEY_GLOSSARY, array.toString()).apply()
        cachedGlossary = values; cachedGlossaryMap = null
    }
    fun deleteGlossaryEntry(source: String) = synchronized(lock) {
        val array = JSONArray()
        val remaining = glossary().filterNot { it.source.equals(source, true) }
        remaining.forEach { array.put(JSONObject().put("source", it.source).put("arabic", it.arabic).put("note", it.note)) }
        prefs.edit().putString(KEY_GLOSSARY, array.toString()).apply()
        cachedGlossary = remaining; cachedGlossaryMap = null
    }
    fun characters(): List<CharacterEntry> = synchronized(lock) {
        cachedCharacters ?: readCharactersRaw().also { cachedCharacters = it }
    }
    private fun readCharactersRaw(): List<CharacterEntry> {
        val array = runCatching { JSONArray(prefs.getString(KEY_CHARACTERS, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { obj ->
                val name = obj.optString("name").trim()
                if (name.isBlank()) null else CharacterEntry(name, obj.optString("voice"), obj.optString("notes"))
            }
        }
    }
    fun saveCharacter(entry: CharacterEntry) = synchronized(lock) {
        val values = characters().filterNot { it.name.equals(entry.name, true) }.toMutableList()
        values.add(entry)
        val array = JSONArray()
        values.forEach { array.put(JSONObject().put("name", it.name).put("voice", it.voice).put("notes", it.notes)) }
        prefs.edit().putString(KEY_CHARACTERS, array.toString()).apply()
        cachedCharacters = values
    }
    fun deleteCharacter(name: String) = synchronized(lock) {
        val array = JSONArray()
        val remaining = characters().filterNot { it.name.equals(name, true) }
        remaining.forEach { array.put(JSONObject().put("name", it.name).put("voice", it.voice).put("notes", it.notes)) }
        prefs.edit().putString(KEY_CHARACTERS, array.toString()).apply()
        cachedCharacters = remaining
    }
    fun exactGlossaryTranslation(source: String): String? = glossaryMap()[normalize(source)]
    fun exportJson(): String = JSONObject()
        .put("glossary", JSONArray().also { a -> glossary().forEach { a.put(JSONObject().put("source", it.source).put("arabic", it.arabic).put("note", it.note)) } })
        .put("characters", JSONArray().also { a -> characters().forEach { a.put(JSONObject().put("name", it.name).put("voice", it.voice).put("notes", it.notes)) } })
        .toString()
    fun importJson(json: String) = synchronized(lock) {
        val root = JSONObject(json)
        val glossaryArray = root.optJSONArray("glossary") ?: JSONArray()
        val characterArray = root.optJSONArray("characters") ?: JSONArray()
        val safeGlossary = JSONArray()
        for (i in 0 until glossaryArray.length()) {
            val item = glossaryArray.optJSONObject(i) ?: continue
            val s = item.optString("source").trim().take(180); val a = item.optString("arabic").trim().take(300)
            if (s.isNotBlank() && a.isNotBlank()) safeGlossary.put(JSONObject().put("source", s).put("arabic", a).put("note", item.optString("note").take(300)))
        }
        val safeCharacters = JSONArray()
        for (i in 0 until characterArray.length()) {
            val item = characterArray.optJSONObject(i) ?: continue
            val name = item.optString("name").trim().take(100)
            if (name.isNotBlank()) safeCharacters.put(JSONObject().put("name", name).put("voice", item.optString("voice").take(300)).put("notes", item.optString("notes").take(1000)))
        }
        prefs.edit().putString(KEY_GLOSSARY, safeGlossary.toString()).putString(KEY_CHARACTERS, safeCharacters.toString()).apply()
        cachedGlossary = null; cachedGlossaryMap = null; cachedCharacters = null
    }
    private fun normalize(value: String) = value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    companion object {
        private const val KEY_GLOSSARY = "glossary_json"
        private const val KEY_CHARACTERS = "characters_json"
    }
}

// ═══════════════════════ QUALITY GUARD ═══════════════════════

data class MangaFoxQualityFinding(val severity: String, val page: Int, val message: String)
data class MangaFoxPageAudit(
    val page: Int, val layerCount: Int, val overlapCount: Int, val outOfBoundsCount: Int,
    val untranslatedCount: Int, val emptyTextCount: Int, val findings: List<MangaFoxQualityFinding>)
data class MangaFoxQualityReport(val pages: List<MangaFoxPageAudit>) {
    val errorCount get() = pages.sumOf { page -> page.findings.count { it.severity == "خطأ" } }
    val warningCount get() = pages.sumOf { page -> page.findings.count { it.severity != "خطأ" } }
    val inspectedLayers get() = pages.sumOf { it.layerCount }
    fun toArabicText(): String = buildString {
        append("فحص بنيوي للطبقات: ${pages.size} صفحة · $inspectedLayers طبقة\n")
        append("مشاكل حرجة: $errorCount · تحذيرات نصية: $warningCount\n\n")
        pages.filter { it.findings.isNotEmpty() }.forEach { page ->
            append("الصفحة ${page.page}:\n")
            page.findings.take(14).forEach { append("• [${it.severity}] ${it.message}\n") }
            append('\n')
        }
        if (pages.all { it.findings.isEmpty() })
            append("لم تُكتشف مشاكل بنيوية. راجع الصورة يدوياً للتأكد من OCR.")
        else
            append("الفحص البنيوي لا يستبدل مراجعة الصورة.")
    }
}

object MangaFoxQualityGuard {
    fun inspect(resultPaths: List<String>, layerPaths: List<String>): MangaFoxQualityReport {
        val audits = resultPaths.indices.map { pageIndex ->
            val findings = ArrayList<MangaFoxQualityFinding>()
            val imagePath = resultPaths.getOrNull(pageIndex).orEmpty()
            val imageFile = File(imagePath)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (imageFile.isFile) BitmapFactory.decodeFile(imagePath, options)
            val width = options.outWidth; val height = options.outHeight
            val layerPath = layerPaths.getOrNull(pageIndex).orEmpty()
            val layerFile = File(layerPath)
            val array = if (layerFile.isFile) runCatching { JSONArray(layerFile.readText(Charsets.UTF_8)) }.getOrNull() else null
            val layers = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
                .filterNot { it.optBoolean("_mfoxKeepFlattenedBase", false) }
            var outside = 0; var overlap = 0; var untranslated = 0; var empty = 0
            val rects = ArrayList<Pair<Rect, Int>>()
            layers.forEachIndexed { index, layer ->
                val left = layer.optInt("left", -1); val top = layer.optInt("top", -1)
                val right = layer.optInt("right", -1); val bottom = layer.optInt("bottom", -1)
                if (left < 0 || top < 0 || right <= left || bottom <= top ||
                    (width > 0 && right > width) || (height > 0 && bottom > height)) {
                    outside++
                    findings.add(MangaFoxQualityFinding("خطأ", pageIndex + 1, "الطبقة ${index + 1} تتجاوز حدود الصورة"))
                } else {
                    val rect = Rect(left, top, right, bottom)
                    rects.forEach { (other, otherIndex) ->
                        val intersection = Rect(rect)
                        if (intersection.intersect(other)) {
                            val intersectionArea = intersection.width().toLong() * intersection.height().toLong()
                            val smallerArea = minOf(rect.width().toLong() * rect.height(), other.width().toLong() * other.height())
                            if (smallerArea > 0L && intersectionArea.toDouble() / smallerArea >= 0.34) {
                                overlap++
                                val severity = if (intersectionArea.toDouble() / smallerArea >= 0.82) "خطأ" else "تحذير"
                                findings.add(MangaFoxQualityFinding(severity, pageIndex + 1, "تداخل بين الطبقتين ${otherIndex + 1} و${index + 1}"))
                            }
                        }
                    }
                    rects.add(rect to index)
                }
                if (!layer.optBoolean("eraseOnly", false)) {
                    val translated = layer.optString("translated").trim()
                    val source = layer.optString("source").trim()
                    if (translated.isBlank()) {
                        empty++
                        findings.add(MangaFoxQualityFinding("خطأ", pageIndex + 1, "الطبقة ${index + 1} بلا ترجمة"))
                    } else {
                        fun isArabic(ch: Char): Boolean = ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' ||
                            ch in '\u08A0'..'\u08FF' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'
                        val sourceForeign = source.any { it.isLetter() && !isArabic(it) }
                        val outForeign = translated.count { it.isLetter() && !isArabic(it) }
                        if (sourceForeign && outForeign >= 3) {
                            untranslated++
                            findings.add(MangaFoxQualityFinding("مراجعة", pageIndex + 1, "أحرف أجنبية متبقية في الطبقة ${index + 1}"))
                        }
                    }
                }
            }
            if (!imageFile.isFile) findings.add(MangaFoxQualityFinding("خطأ", pageIndex + 1, "صورة النتيجة مفقودة"))
            if (!layerFile.isFile) findings.add(MangaFoxQualityFinding("مراجعة", pageIndex + 1, "ملف الطبقات مفقود"))
            MangaFoxPageAudit(pageIndex + 1, layers.size, overlap, outside, untranslated, empty, findings)
        }
        return MangaFoxQualityReport(audits)
    }
}

// ═══════════════════════ LAYER HISTORY ═══════════════════════

class MangaFoxLayerHistory(private val context: Context) {
    private fun folder(page: Int, kind: String) = File(context.filesDir, "mfox_phantom_history/page_${page + 1}/$kind").apply { mkdirs() }

    @Synchronized fun push(page: Int, current: File) {
        if (page < 0 || !current.isFile || current.length() > 4L * 1024L * 1024L) return
        runCatching {
            val target = File(folder(page, "undo"), "${System.currentTimeMillis()}.json")
            current.copyTo(target, overwrite = true)
            folder(page, "redo").listFiles()?.forEach { it.delete() }
            folder(page, "undo").listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        }
    }

    @Synchronized fun undo(page: Int, current: File): Boolean = move(page, current, "undo", "redo")
    @Synchronized fun redo(page: Int, current: File): Boolean = move(page, current, "redo", "undo")

    private fun move(page: Int, current: File, from: String, to: String): Boolean = runCatching {
        val candidates = folder(page, from).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()
        val snapshot = candidates.firstOrNull() ?: return false
        if (current.isFile) current.copyTo(File(folder(page, to), "${System.currentTimeMillis()}.json"), overwrite = true)
        val text = snapshot.readText(Charsets.UTF_8)
        val temp = File(current.parentFile, current.name + ".history.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(current)) { temp.copyTo(current, overwrite = true); temp.delete() }
        snapshot.delete()
        true
    }.getOrDefault(false)
}

// ═══════════════════════ PROJECT ARCHIVE ═══════════════════════

object MangaFoxProjectArchive {
    fun export(context: Context, output: OutputStream) {
        val root = JSONObject()
            .put("format", "MANGAFOX_PHANTOM_PROJECT").put("version", 1)
            .put("title", MangaFoxRuntime.resultTitle)
        val sources = JSONArray(); val results = JSONArray(); val layers = JSONArray()
        MangaFoxRuntime.resultPages.forEachIndexed { index, resultPath ->
            val source = File(MangaFoxRuntime.sourcePages.getOrNull(index).orEmpty())
            val result = File(resultPath)
            val layer = File(MangaFoxRuntime.translationLayerFiles.getOrNull(index).orEmpty())
            sources.put(if (source.isFile) "source/$index${source.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".jpg"}" else "")
            results.put(if (result.isFile) "result/$index${result.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".jpg"}" else "")
            layers.put(if (layer.isFile) "layers/$index.json" else "")
        }
        root.put("sources", sources).put("results", results).put("layers", layers).put("quality", MangaFoxRuntime.qualitySummary)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(root.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            for (i in 0 until sources.length()) { val e = sources.optString(i); if (e.isNotBlank()) putFile(zip, e, File(MangaFoxRuntime.sourcePages[i])) }
            for (i in 0 until results.length()) { val e = results.optString(i); if (e.isNotBlank()) putFile(zip, e, File(MangaFoxRuntime.resultPages[i])) }
            for (i in 0 until layers.length()) { val e = layers.optString(i); if (e.isNotBlank()) putFile(zip, e, File(MangaFoxRuntime.translationLayerFiles[i])) }
            zip.putNextEntry(ZipEntry("knowledge.json"))
            zip.write(MangaFoxKnowledgeStore(context).exportJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun restore(context: Context, input: InputStream) {
        val temporary = File(context.filesDir, "mfox_phantom_import_${System.currentTimeMillis()}").apply { mkdirs() }
        var consumed = 0L; var importedRootForRollback: File? = null; var importCommitted = false
        val seenEntries = HashSet<String>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(seenEntries.add(name)) { "النسخة تحتوي ملفاً مكرراً: $name" }
                    require(!entry.isDirectory && !name.startsWith("/") && !name.contains("..") &&
                        (name == "project.json" || name == "knowledge.json" ||
                            name.matches(Regex("(source|result)/[0-9]+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)) ||
                            name.matches(Regex("layers/[0-9]+\\.json")))) { "ملف مشروع غير آمن: $name" }
                    val target = File(temporary, name)
                    require(target.canonicalPath.startsWith(temporary.canonicalPath + File.separator)) { "مسار غير آمن" }
                    target.parentFile?.mkdirs()
                    var entryBytes = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = zip.read(buffer); if (read <= 0) break
                            entryBytes += read; consumed += read
                            require(consumed <= MAX_IMPORT_BYTES) { "النسخة أكبر من الحد" }
                            require(entryBytes <= 200L * 1024L * 1024L) { "ملف واحد يتجاوز 200MB: $name" }
                            out.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = File(temporary, "project.json")
            require(manifest.isFile && manifest.length() <= 2L * 1024L * 1024L) { "project.json مفقود" }
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            require(root.optString("format") == "MANGAFOX_PHANTOM_PROJECT") { "صيغة غير معروفة" }
            val importedRoot = File(context.filesDir, "mfox_phantom_projects/import_${System.currentTimeMillis()}").apply { mkdirs() }
            importedRootForRollback = importedRoot
            val sourceNames = root.optJSONArray("sources") ?: JSONArray()
            val resultNames = root.optJSONArray("results") ?: JSONArray()
            val layerNames = root.optJSONArray("layers") ?: JSONArray()
            val count = minOf(resultNames.length(), sourceNames.length())
            require(count in 1..2000) { "عدد صفحات غير صالح" }
            val newSources = ArrayList<String>(count); val newResults = ArrayList<String>(count); val newLayers = ArrayList<String>(count)
            for (i in 0 until count) {
                fun copyEntry(name: String, folder: String): String {
                    if (name.isBlank()) return ""
                    val validName = when (folder) {
                        "source", "result" -> name.matches(Regex("$folder/[0-9]+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE))
                        "layers" -> name.matches(Regex("layers/[0-9]+\\.json"))
                        else -> false
                    }
                    require(validName) { "مرجع غير صالح" }
                    val from = File(temporary, name)
                    require(from.canonicalPath.startsWith(temporary.canonicalPath + File.separator) && from.isFile) { "ملف مفقود" }
                    val to = File(importedRoot, name).apply { parentFile?.mkdirs() }
                    from.copyTo(to, overwrite = true)
                    return to.absolutePath
                }
                newSources.add(copyEntry(sourceNames.optString(i), "source"))
                newResults.add(copyEntry(resultNames.optString(i), "result"))
                newLayers.add(copyEntry(layerNames.optString(i), "layers"))
            }
            require(newResults.any { it.isNotBlank() && File(it).isFile }) { "لا صور نتائج صالحة" }
            MangaFoxRuntime.sourcePages = newSources
            MangaFoxRuntime.resultPages = newResults
            MangaFoxRuntime.translationLayerFiles = newLayers
            MangaFoxRuntime.resultTitle = root.optString("title", "مشروع مستورد")
            MangaFoxRuntime.qualitySummary = root.optString("quality", "تم الاستيراد")
            File(temporary, "knowledge.json").takeIf { it.isFile }?.let { knowledge ->
                runCatching { MangaFoxKnowledgeStore(context).importJson(knowledge.readText(Charsets.UTF_8)) }
            }
            MangaFoxRuntime.persist(context)
            importCommitted = true
        } finally {
            temporary.deleteRecursively()
            if (!importCommitted) importedRootForRollback?.deleteRecursively()
        }
    }

    private fun putFile(zip: ZipOutputStream, entryName: String, file: File) {
        if (!file.isFile || file.length() > MAX_IMPORT_BYTES) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}

// ═══════════════════════ BUBBLE REPAIR ═══════════════════════

object MangaFoxBubbleRepair {
    fun sampleInteriorColor(bitmap: Bitmap, balloon: Rect, excludedText: Rect): Int {
        if (bitmap.width < 2 || bitmap.height < 2) return Color.WHITE
        val safe = Rect(balloon).apply {
            inset(maxOf(2, width() / 10), maxOf(2, height() / 10))
            left = left.coerceIn(0, bitmap.width - 1); top = top.coerceIn(0, bitmap.height - 1)
            right = right.coerceIn(left + 1, bitmap.width); bottom = bottom.coerceIn(top + 1, bitmap.height)
        }
        val excluded = Rect(excludedText).apply { inset(-maxOf(3, width() / 5), -maxOf(3, height() / 5)) }
        val ringThickness = maxOf(2, minOf(balloon.width(), balloon.height()) / 20)
        val rings = listOf(
            Rect(balloon.left, balloon.top, balloon.right, (balloon.top + ringThickness).coerceAtMost(bitmap.height)),
            Rect(balloon.left, (balloon.bottom - ringThickness).coerceAtLeast(0), balloon.right, balloon.bottom),
            Rect(balloon.left, balloon.top, (balloon.left + ringThickness).coerceAtMost(bitmap.width), balloon.bottom),
            Rect((balloon.right - ringThickness).coerceAtLeast(0), balloon.top, balloon.right, balloon.bottom)
        )
        val samples = ArrayList<Int>(500)
        rings.forEach { ring ->
            val r = Rect(ring).apply {
                left = left.coerceIn(0, bitmap.width - 1); top = top.coerceIn(0, bitmap.height - 1)
                right = right.coerceIn(left + 1, bitmap.width); bottom = bottom.coerceIn(top + 1, bitmap.height)
            }
            val stepX = maxOf(1, r.width() / 24); val stepY = maxOf(1, r.height() / 24)
            var y = r.top
            while (y < r.bottom) {
                var x = r.left
                while (x < r.right) {
                    if (!excluded.contains(x, y)) samples.add(bitmap.getPixel(x, y))
                    x += stepX
                }
                y += stepY
            }
        }
        if (samples.size < 8) {
            val stepX = maxOf(1, safe.width() / 20); val stepY = maxOf(1, safe.height() / 20)
            var y = safe.top
            while (y < safe.bottom) {
                var x = safe.left
                while (x < safe.right) {
                    if (!excluded.contains(x, y)) samples.add(bitmap.getPixel(x, y))
                    x += stepX
                }
                y += stepY
            }
        }
        if (samples.isEmpty()) return Color.WHITE
        val buckets = HashMap<Int, MutableList<Int>>()
        samples.forEach { color ->
            val key = ((Color.red(color) shr 5) shl 6) or ((Color.green(color) shr 5) shl 3) or (Color.blue(color) shr 5)
            buckets.getOrPut(key) { ArrayList() }.add(color)
        }
        val dominant = buckets.values.maxByOrNull { it.size }.orEmpty()
        val chosen = if (dominant.size >= maxOf(6, samples.size / 10)) dominant else samples
        fun median(channel: (Int) -> Int): Int = chosen.map(channel).sorted()[chosen.size / 2]
        return Color.rgb(median { Color.red(it) }, median { Color.green(it) }, median { Color.blue(it) })
    }

    fun sampleBackground(bitmap: Bitmap, area: Rect): Int {
        if (bitmap.width < 2 || bitmap.height < 2) return Color.WHITE
        val left = area.left.coerceIn(0, bitmap.width - 1); val right = (area.right - 1).coerceIn(left, bitmap.width - 1)
        val top = area.top.coerceIn(0, bitmap.height - 1); val bottom = (area.bottom - 1).coerceIn(top, bitmap.height - 1)
        val gap = maxOf(2, minOf(area.width(), area.height()) / 12)
        val colors = ArrayList<Int>(48); val steps = 12
        for (i in 0..steps) {
            val x = left + ((right - left) * i / steps)
            val y = top + ((bottom - top) * i / steps)
            val topY = (top - gap).coerceAtLeast(0); val bottomY = (bottom + gap).coerceAtMost(bitmap.height - 1)
            val leftX = (left - gap).coerceAtLeast(0); val rightX = (right + gap).coerceAtMost(bitmap.width - 1)
            colors.add(bitmap.getPixel(x, topY)); colors.add(bitmap.getPixel(x, bottomY))
            colors.add(bitmap.getPixel(leftX, y)); colors.add(bitmap.getPixel(rightX, y))
        }
        fun lum(c: Int) = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        val bright = colors.filter { lum(it) >= 218 }
        val source = if (bright.size >= colors.size * 0.68f) bright else colors
        fun median(channel: (Int) -> Int): Int = source.map(channel).sorted()[source.size / 2]
        val r = median { Color.red(it) }; val g = median { Color.green(it) }; val b = median { Color.blue(it) }
        val chroma = maxOf(r, g, b) - minOf(r, g, b); val brightness = (r * 30 + g * 59 + b * 11) / 100
        return when {
            bright.size >= colors.size * 0.72f && brightness >= 228 && chroma <= 28 -> Color.WHITE
            brightness >= 242 && chroma <= 12 -> Color.rgb(250, 250, 250)
            else -> Color.rgb(r, g, b)
        }
    }
}

// ═══════════════════════ LAYOUT ENGINE ═══════════════════════

object MangaFoxLayoutEngine {
    fun shouldUseVertical(bounds: Rect, userChoice: String = "auto"): Boolean = when (userChoice) {
        "vertical" -> true; "horizontal" -> false
        else -> bounds.height() > bounds.width() * 1.42f
    }
    fun fittingFontSize(preferredPx: Float, rectWidth: Int, rectHeight: Int, minimumPx: Float = 7f): Float {
        val safeWidth = rectWidth.coerceAtLeast(8).toFloat(); val safeHeight = rectHeight.coerceAtLeast(8).toFloat()
        return minOf(preferredPx, safeWidth * 0.82f, safeHeight * 0.72f).coerceAtLeast(minimumPx)
    }
}

object MangaFoxPerformanceAdvisor {
    data class Profile(val name: String, val maxImageSide: Int, val workerCount: Int, val highContrastPass: Boolean)
    fun forDevice(availableMemoryMb: Int, cpuCores: Int): Profile = when {
        availableMemoryMb < 900 -> Profile("توفير الذاكرة", 1800, 1, false)
        availableMemoryMb < 1800 -> Profile("متوازن", 2400, 1, true)
        else -> Profile("دقة مرتفعة", 3200, minOf(2, cpuCores.coerceAtLeast(1)), true)
    }
}

// ═══════════════════════ REGION SELECTION VIEW ═══════════════════════

class RegionSelectionView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 190, 65); style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 65); style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private var startX = 0f; private var startY = 0f
    private val selection = RectF()
    private var hasSelection = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hasSelection) { canvas.drawRect(selection, fillPaint); canvas.drawRect(selection, borderPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x.coerceIn(0f, width.toFloat()); startY = event.y.coerceIn(0f, height.toFloat())
                selection.set(startX, startY, startX, startY); hasSelection = true; invalidate(); return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val x = event.x.coerceIn(0f, width.toFloat()); val y = event.y.coerceIn(0f, height.toFloat())
                selection.set(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { hasSelection = false; invalidate(); return true }
        }
        return true
    }

    fun toSourceRect(sourceWidth: Int, sourceHeight: Int): Rect? {
        if (!hasSelection || width <= 0 || height <= 0) return null
        val rect = Rect(
            (selection.left / width * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1),
            (selection.top / height * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1),
            (selection.right / width * sourceWidth).roundToInt().coerceIn(1, sourceWidth),
            (selection.bottom / height * sourceHeight).roundToInt().coerceIn(1, sourceHeight))
        return rect.takeIf { it.width() > 0 && it.height() > 0 }
    }
}

// ═══════════════════════ ZOOMABLE IMAGE VIEW ═══════════════════════

class ZoomableImageView(context: Context) : ImageView(context) {
    private val drawMatrix = Matrix()
    private val displayedRect = RectF()
    private var userScale = 1f
    private var lastX = 0f; private var lastY = 0f; private var dragging = false
    private var imageFilePath: String? = null
    private var loadedBitmap: Bitmap? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (userScale * detector.scaleFactor).coerceIn(1f, 5f)
            val factor = next / userScale; userScale = next
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            fixBounds(); imageMatrix = drawMatrix
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX; isClickable = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); resetBaseMatrix()
    }
    fun setImageFile(path: String) { imageFilePath = path; releasePage() }
    fun loadPage() {
        if (loadedBitmap != null) return
        val path = imageFilePath ?: return
        val bmp = BitmapFactory.decodeFile(path) ?: return
        loadedBitmap = bmp; super.setImageBitmap(bmp); post { resetBaseMatrix() }
    }
    fun releasePage() {
        val old = loadedBitmap; loadedBitmap = null; super.setImageDrawable(null)
        old?.let { if (!it.isRecycled) it.recycle() }
        userScale = 1f; drawMatrix.reset()
    }
    override fun onDetachedFromWindow() { releasePage(); super.onDetachedFromWindow() }

    private fun resetBaseMatrix() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val sx = width.toFloat() / d.intrinsicWidth.toFloat()
        val sy = height.toFloat() / d.intrinsicHeight.toFloat()
        drawMatrix.reset(); drawMatrix.setScale(sx, sy); userScale = 1f; imageMatrix = drawMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                dragging = userScale > 1.01f
                parent?.requestDisallowInterceptTouchEvent(dragging); return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                if (userScale > 1.01f && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    drawMatrix.postTranslate(dx, dy); fixBounds(); imageMatrix = drawMatrix; dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                lastX = event.x; lastY = event.y; return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (userScale <= 1.01f) parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked != MotionEvent.ACTION_POINTER_UP && userScale <= 1.01f) resetBaseMatrix()
                return true
            }
        }
        return true
    }

    private fun fixBounds() {
        val d = drawable ?: return
        displayedRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(displayedRect)
        var dx = 0f; var dy = 0f
        if (displayedRect.width() <= width) dx = width / 2f - displayedRect.centerX()
        else if (displayedRect.left > 0f) dx = -displayedRect.left
        else if (displayedRect.right < width) dx = width - displayedRect.right
        if (displayedRect.height() <= height) dy = height / 2f - displayedRect.centerY()
        else if (displayedRect.top > 0f) dy = -displayedRect.top
        else if (displayedRect.bottom < height) dy = height - displayedRect.bottom
        drawMatrix.postTranslate(dx, dy)
    }
}

// ═══════════════════════ TYPEFACE (top-level, MainActivity preview) ═══════════════════════

internal fun resolveArabicTypeface(prefs: SharedPreferences): Typeface {
    val style = if (prefs.getBoolean(PREF_FONT_BOLD, true)) Typeface.BOLD else Typeface.NORMAL
    val customPath = prefs.getString(PREF_FONT_FILE, null)
    if (!customPath.isNullOrBlank()) {
        val custom = runCatching {
            val file = File(customPath)
            if (file.isFile) Typeface.createFromFile(file) else null
        }.getOrNull()
        if (custom != null) {
            if (style == Typeface.BOLD && custom.isBold) return custom
            return Typeface.create(custom, style)
        }
    }
    val family = prefs.getString(PREF_FONT_FAMILY, "sans-serif") ?: "sans-serif"
    return Typeface.create(family, style)
}

// ═══════════════════════ MAIN ACTIVITY ═══════════════════════

class MainActivity : android.app.Activity() {
    private val dark = Color.rgb(10, 10, 13)
    private val panel = Color.rgb(25, 25, 31)
    private val gold = Color.rgb(255, 190, 65)
    private lateinit var preferences: SharedPreferences
    private var fontPreview: TextView? = null
    private var fontStatusText: TextView? = null
    private var requestedFontLabel: String? = null
    private var readingFullscreen = false
    private var pendingPdfFile: File? = null
    private var pendingProjectArchive: File? = null
    private val fontDownloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var selectedHomeTab = "translation"
    private var homeContent: LinearLayout? = null
    private var statusText: TextView? = null
    private var progressText: TextView? = null
    private var homeProgress: ProgressBar? = null
    private var accessPermissionText: TextView? = null
    private var overlayPermissionText: TextView? = null
    private var notificationPermissionText: TextView? = null
    private var homeVisible = false
    private val homeUiHandler = Handler(Looper.getMainLooper())
    private val homeUiTick = object : Runnable {
        override fun run() { if (!homeVisible) return; refreshHomeIndicators(); homeUiHandler.postDelayed(this, 500L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        MangaFoxRuntime.restore(this); MangaFoxRuntime.gcOrphans(this)
        applySystemBars(); handleIntent(intent)
        homeUiHandler.postDelayed({ maybeShowOnboarding() }, 450L)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    private fun handleIntent(source: Intent?) {
        when (source?.action) {
            ACTION_SHOW_RESULTS -> showResults()
            ACTION_STOP_CAPTURE -> {
                showHome()
                MangaFoxAccessibilityService.instance?.stopCaptureByUser()
                    ?: toast("خدمة Manga Fox غير متصلة")
            }
            else -> showHome()
        }
    }

    override fun onResume() {
        super.onResume(); homeVisible = true
        homeUiHandler.removeCallbacks(homeUiTick); homeUiHandler.post(homeUiTick)
        refreshHomeIndicators(); MangaFoxAccessibilityService.instance?.onHostActivityResumed()
    }
    override fun onPause() {
        homeVisible = false; homeUiHandler.removeCallbacks(homeUiTick)
        MangaFoxAccessibilityService.instance?.onHostActivityPaused(); super.onPause()
    }

    private fun applySystemBars() {
        window.statusBarColor = dark; window.navigationBarColor = dark
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false
        }
    }

    private fun setReadingFullscreen(enabled: Boolean) {
        readingFullscreen = enabled
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled)
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        else View.SYSTEM_UI_FLAG_VISIBLE
        if (enabled) { window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK } else applySystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); if (hasFocus && readingFullscreen) setReadingFullscreen(true)
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (readingFullscreen) showHome() else super.onBackPressed() }

    // ═══════ HOME / TABS ═══════

    private fun showHome() {
        setReadingFullscreen(false)
        val scroll = ScrollView(this).apply { setBackgroundColor(dark); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "MANGA FOX"; textSize = 29f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(gold); gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "ترجمة المانجا والمانهوا أثناء القراءة"
            textSize = 14f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(16))
        }, matchWrap())
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(4), dp(4), dp(4), dp(4)); background = rounded(panel, 16)
        }
        val tabsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs, android.widget.FrameLayout.LayoutParams(-2, -2))
        }
        listOf(
            "translation" to "ترجمة", "studio" to "استوديو", "quality" to "الجودة",
            "knowledge" to "المعجم", "settings" to "الإعدادات", "library" to "المكتبة"
        ).forEach { (key, label) ->
            tabs.addView(Button(this).apply {
                text = label; textSize = 12f; isAllCaps = false; minWidth = 0
                setTextColor(if (selectedHomeTab == key) Color.BLACK else Color.LTGRAY)
                background = rounded(if (selectedHomeTab == key) gold else panel, 12)
                setOnClickListener { selectedHomeTab = key; updateHomeTabs(tabs); renderHomeTab() }
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(46)).apply { marginStart = dp(3); marginEnd = dp(3) }
            })
        }
        root.addView(tabsScroll, matchWrap(dp(12)))
        homeContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(homeContent, matchWrap())
        root.addView(TextView(this).apply {
            text = "MANGA FOX  ·  N5CR4"; textSize = 12f; setTextColor(gold)
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(4))
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "حقوقي · N5CR4  ↗"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(gold); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8))
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/N5CR4"))) }
                    .onFailure { toast("تعذر فتح تيليجرام") }
            }
        }, matchWrap())
        scroll.addView(root); setContentView(scroll)
        renderHomeTab(); refreshHomeIndicators()
    }

    private fun updateHomeTabs(tabs: LinearLayout) {
        val items = listOf(
            "translation" to "ترجمة", "studio" to "استوديو", "quality" to "الجودة",
            "knowledge" to "المعجم", "settings" to "الإعدادات", "library" to "المكتبة")
        tabs.removeAllViews()
        items.forEach { (key, label) ->
            tabs.addView(Button(this).apply {
                text = label; textSize = 12f; isAllCaps = false; minWidth = 0
                setTextColor(if (selectedHomeTab == key) Color.BLACK else Color.LTGRAY)
                background = rounded(if (selectedHomeTab == key) gold else panel, 12)
                setOnClickListener { selectedHomeTab = key; updateHomeTabs(tabs); renderHomeTab() }
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(46)).apply { marginStart = dp(3); marginEnd = dp(3) }
            })
        }
    }

    private fun renderHomeTab() {
        val content = homeContent ?: return
        content.removeAllViews()
        when (selectedHomeTab) {
            "settings" -> addSettingsCard(content)
            "library" -> addLibraryTab(content)
            "studio" -> addPhantomStudioTab(content)
            "quality" -> addQualityTab(content)
            "knowledge" -> addKnowledgeTab(content)
            else -> addTranslationTab(content)
        }
        refreshHomeIndicators()
    }

    private fun addTranslationTab(root: LinearLayout) {
        val intro = card()
        intro.addView(text("ترجمة الفصل العمودي بالكامل", 19f, gold, true))
        intro.addView(text("التقط لقطات متتابعة، ثم اجمع النصوص من أكثر من محرك OCR وحدد لغة كل كتلة قبل ترجمتها.", 13f, Color.LTGRAY))
        intro.addView(text("تعمل المعالجة على الجهاز بعد تنزيل النماذج. يلزم الإنترنت أول مرة.", 12f, Color.rgb(195, 185, 165)))
        root.addView(intro, matchWrap(dp(12)))
        val onboarding = card()
        onboarding.addView(text("دليل التفعيل والتحقق التلقائي", 17f, Color.WHITE, true))
        accessPermissionText = text("", 13f, Color.LTGRAY)
        overlayPermissionText = text("", 13f, Color.LTGRAY)
        notificationPermissionText = text("", 13f, Color.LTGRAY)
        onboarding.addView(accessPermissionText, matchWrap())
        onboarding.addView(actionButton("الخطوة 1 · فتح إعدادات خدمة إمكانية الوصول", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap(dp(5)))
        onboarding.addView(overlayPermissionText, matchWrap())
        onboarding.addView(actionButton("الخطوة 2 · السماح بالظهور فوق التطبيقات", false) { openOverlayPermission() }, matchWrap(dp(5)))
        onboarding.addView(notificationPermissionText, matchWrap())
        onboarding.addView(actionButton("الخطوة 3 · تفعيل الإشعارات", false) { requestNotificationPermission() }, matchWrap(dp(5)))
        root.addView(onboarding, matchWrap(dp(12)))
        val actions = card()
        actions.addView(text("الترجمة", 18f, Color.WHITE, true))
        actions.addView(actionButton("ترجمة الشاشة الحالية", true) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("فعّل خدمة Manga Fox أولاً") else {
                service.startSingleCapture(900L); window.decorView.postDelayed({ moveTaskToBack(true) }, 120L)
            }
        }, matchWrap(dp(5)))
        actions.addView(actionButton("ترجمة الفصل العمودي كاملاً", true) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("فعّل خدمة Manga Fox، ثم افتح فصل المانجا") else {
                service.startFullChapterCapture(1300L); window.decorView.postDelayed({ moveTaskToBack(true) }, 120L)
            }
        }, matchWrap(dp(5)))
        actions.addView(actionButton("إيقاف الالتقاط الحالي", false) {
            MangaFoxAccessibilityService.instance?.let { it.stopCaptureByUser() } ?: toast("خدمة غير متصلة")
        }, matchWrap(dp(5)))
        actions.addView(actionButton("عرض آخر نتيجة مترجمة", false) { showResults() }, matchWrap())
        root.addView(actions, matchWrap(dp(12)))
        val progressCard = card()
        progressCard.addView(text("تقدم المعالجة", 16f, Color.WHITE, true))
        progressText = text("", 12f, Color.LTGRAY)
        progressCard.addView(progressText, matchWrap(dp(5)))
        homeProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = MangaFoxRuntime.progressPercent.coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(gold)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(55, 55, 62))
        }
        progressCard.addView(homeProgress, matchWrap(dp(8)))
        root.addView(progressCard, matchWrap(dp(12)))
        val statusCard = card()
        statusCard.addView(text("حالة التطبيق", 16f, Color.WHITE, true))
        statusText = text("", 13f, Color.LTGRAY)
        statusCard.addView(statusText, matchWrap())
        root.addView(statusCard, matchWrap(dp(10)))
    }

    private fun addLibraryTab(root: LinearLayout) {
        val library = card()
        library.addView(text("مكتبة النتائج", 19f, gold, true))
        library.addView(text("آخر فصل: ${MangaFoxRuntime.resultTitle}", 13f, Color.LTGRAY))
        library.addView(text("عدد المقاطع: ${MangaFoxRuntime.resultPages.size}", 13f, Color.LTGRAY))
        library.addView(text("تقرير الجودة: ${MangaFoxRuntime.qualitySummary}", 13f, Color.rgb(235, 210, 160)))
        library.addView(actionButton("فتح آخر نتيجة", true) { showResults() }, matchWrap(dp(6)))
        library.addView(actionButton("تصدير PDF متصل", false) {
            if (MangaFoxRuntime.resultPages.isEmpty()) toast("لا توجد نتيجة") else exportResultsAsPdf()
        }, matchWrap(dp(6)))
        library.addView(actionButton("تحديث الخط دون OCR", false) {
            MangaFoxAccessibilityService.instance?.rerenderLastResultsFromLayers { showResults() }
                ?: toast("خدمة غير متصلة")
        }, matchWrap(dp(6)))
        library.addView(actionButton("إعادة OCR من الأصل", false) {
            MangaFoxAccessibilityService.instance?.retranslateLastResults() ?: toast("خدمة غير متصلة")
        }, matchWrap(dp(6)))
        library.addView(actionButton("نسخ احتياطي كامل", true) { launchProjectExport() }, matchWrap(dp(6)))
        library.addView(actionButton("استعادة من ZIP", false) { launchProjectImport() }, matchWrap(dp(6)))
        library.addView(actionButton("فحص الجودة", false) { showQualityAudit() }, matchWrap(dp(6)))
        library.addView(actionButton("مراجعة الكتل الفاشلة", false) {
            val samples = MangaFoxRuntime.failedBlockSamples.take(25)
            if (samples.isEmpty()) AlertDialog.Builder(this).setTitle("عينات فشل")
                .setMessage("لا توجد عينات فشل مسجلة.").setPositiveButton("إغلاق", null).show()
            else AlertDialog.Builder(this).setTitle("اختر نصاً لتصحيح ترجمته")
                .setItems(samples.toTypedArray()) { _, which -> showLearnedTranslationDialog(samples[which]) }
                .setNeutralButton("إعادة معالجة") { _, _ ->
                    MangaFoxAccessibilityService.instance?.retranslateLastResults() ?: toast("خدمة غير متصلة")
                }.setNegativeButton("إغلاق", null).show()
        }, matchWrap())
        root.addView(library, matchWrap(dp(12)))
    }

    private fun addPhantomStudioTab(root: LinearLayout) {
        val header = card()
        header.addView(text("PHANTOM STUDIO", 19f, gold, true))
        header.addView(text("تحرير، إدارة مشروع، أنماط كتابة، ومعالجة جماعية.", 13f, Color.LTGRAY))
        root.addView(header, matchWrap(dp(10)))
        val project = card()
        project.addView(text("إدارة المشروع", 16f, Color.WHITE, true))
        project.addView(actionButton("نسخ احتياطي كامل", true) { launchProjectExport() }, matchWrap(dp(5)))
        project.addView(actionButton("استعادة من ZIP", false) { launchProjectImport() }, matchWrap(dp(5)))
        project.addView(actionButton("فتح النتائج الحالية", false) { showResults() }, matchWrap(dp(5)))
        project.addView(actionButton("تصدير PDF", false) { exportResultsAsPdf() }, matchWrap())
        root.addView(project, matchWrap(dp(10)))
        val editing = card()
        editing.addView(text("محرر الصفحات والطبقات", 16f, Color.WHITE, true))
        editing.addView(actionButton("فتح المحرر", true) {
            if (MangaFoxRuntime.resultPages.isEmpty()) toast("لا توجد صفحات بعد") else showResults()
        }, matchWrap(dp(5)))
        editing.addView(actionButton("إعادة تطبيق الخط دون OCR", false) {
            MangaFoxAccessibilityService.instance?.rerenderLastResultsFromLayers { showResults() }
                ?: toast("خدمة غير متصلة")
        }, matchWrap())
        root.addView(editing, matchWrap(dp(10)))
        val processing = card()
        processing.addView(text("معالجة ومؤثرات", 16f, Color.WHITE, true))
        val sfx = CheckBox(this).apply {
            text = "ترجمة المؤثرات اليابانية والكورية المعروفة"
            setTextColor(Color.LTGRAY); isChecked = preferences.getBoolean(PREF_TRANSLATE_SFX, false)
            setOnCheckedChangeListener { _, c -> preferences.edit().putBoolean(PREF_TRANSLATE_SFX, c).apply() }
        }
        processing.addView(sfx, matchWrap(dp(5)))
        processing.addView(actionButton("إعادة ترجمة الفصل من الأصل", false) {
            MangaFoxAccessibilityService.instance?.retranslateLastResults() ?: toast("خدمة غير متصلة")
        }, matchWrap())
        root.addView(processing, matchWrap(dp(10)))
        val styles = card()
        styles.addView(text("أنماط الترجمة", 16f, Color.WHITE, true))
        styles.addView(actionButton("حفظ قالب", true) { saveStyleProfileDialog() }, matchWrap(dp(5)))
        styles.addView(actionButton("تطبيق قالب", false) { applyStyleProfileDialog() }, matchWrap())
        root.addView(styles, matchWrap(dp(10)))
    }

    private fun addQualityTab(root: LinearLayout) {
        val panel = card()
        panel.addView(text("FOX QUALITY GUARD", 19f, gold, true))
        panel.addView(text("فحص إحداثيات، تداخلات، نصوص فارغة، وأحرف أجنبية متبقية.", 13f, Color.LTGRAY))
        panel.addView(actionButton("فحص الفصل الآن", true) { showQualityAudit() }, matchWrap(dp(6)))
        panel.addView(text("ملخص: ${MangaFoxRuntime.qualitySummary}", 12f, Color.rgb(235, 210, 160)), matchWrap())
        root.addView(panel, matchWrap(dp(10)))
    }

    private fun addKnowledgeTab(root: LinearLayout) {
        val store = MangaFoxKnowledgeStore(this)
        val glossary = store.glossary(); val characters = store.characters()
        val dictCard = card()
        dictCard.addView(text("قاموس المصطلحات", 17f, gold, true))
        dictCard.addView(text("عدد المصطلحات: ${glossary.size}", 13f, Color.WHITE))
        dictCard.addView(actionButton("إدارة المصطلحات", true) { manageGlossary() }, matchWrap())
        root.addView(dictCard, matchWrap(dp(10)))
        val charCard = card()
        charCard.addView(text("ذاكرة الشخصيات", 17f, gold, true))
        charCard.addView(text("عدد البطاقات: ${characters.size}", 13f, Color.WHITE))
        charCard.addView(actionButton("إدارة الشخصيات", true) { manageCharacters() }, matchWrap())
        root.addView(charCard, matchWrap(dp(10)))
        root.addView(actionButton("تصدير المشروع", false) { launchProjectExport() }, matchWrap())
    }

    private fun showQualityAudit() {
        if (MangaFoxRuntime.resultPages.isEmpty()) { toast("لا توجد صفحات"); return }
        val report = MangaFoxQualityGuard.inspect(MangaFoxRuntime.resultPages, MangaFoxRuntime.translationLayerFiles)
        AlertDialog.Builder(this)
            .setTitle("QUALITY GUARD · ${report.errorCount} خطأ · ${report.warningCount} تحذير")
            .setMessage(report.toArabicText()).setPositiveButton("مفهوم", null)
            .setNeutralButton("فتح النتائج") { _, _ -> showResults() }.show()
    }

    private fun manageGlossary() {
        val store = MangaFoxKnowledgeStore(this); val entries = store.glossary()
        val labels = entries.map { "${it.source}  ←  ${it.arabic}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("قاموس · ${entries.size}")
            .setItems(labels) { _, which ->
                val entry = entries[which]
                AlertDialog.Builder(this).setTitle(entry.source).setMessage("الترجمة: ${entry.arabic}\n${entry.note}")
                    .setPositiveButton("حذف") { _, _ -> store.deleteGlossaryEntry(entry.source); renderHomeTab() }
                    .setNeutralButton("تعديل") { _, _ -> editGlossaryEntry(entry) }
                    .setNegativeButton("إغلاق", null).show()
            }.setPositiveButton("إضافة") { _, _ -> editGlossaryEntry(null) }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun editGlossaryEntry(initial: MangaFoxKnowledgeStore.GlossaryEntry?) {
        val s = EditText(this).apply { hint = "المصطلح"; setText(initial?.source.orEmpty()) }
        val a = EditText(this).apply { hint = "الترجمة"; setText(initial?.arabic.orEmpty()) }
        val n = EditText(this).apply { hint = "ملاحظة"; setText(initial?.note.orEmpty()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(s); addView(a); addView(n)
        }
        AlertDialog.Builder(this).setTitle("مصطلح").setView(box).setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val sv = s.text.toString().trim(); val av = a.text.toString().trim()
                if (sv.isBlank() || av.isBlank()) toast("أدخل المصطلح والترجمة")
                else { MangaFoxKnowledgeStore(this).saveGlossaryEntry(MangaFoxKnowledgeStore.GlossaryEntry(sv, av, n.text.toString().trim())); renderHomeTab() }
            }.show()
    }

    private fun manageCharacters() {
        val store = MangaFoxKnowledgeStore(this); val entries = store.characters()
        AlertDialog.Builder(this).setTitle("شخصيات · ${entries.size}")
            .setItems(entries.map { "${it.name} · ${it.voice}" }.toTypedArray()) { _, which ->
                val item = entries[which]
                AlertDialog.Builder(this).setTitle(item.name).setMessage("النبرة: ${item.voice}\n${item.notes}")
                    .setPositiveButton("حذف") { _, _ -> store.deleteCharacter(item.name); renderHomeTab() }
                    .setNeutralButton("تعديل") { _, _ -> editCharacter(item) }.setNegativeButton("إغلاق", null).show()
            }.setPositiveButton("إضافة") { _, _ -> editCharacter(null) }.setNegativeButton("إغلاق", null).show()
    }

    private fun editCharacter(initial: MangaFoxKnowledgeStore.CharacterEntry?) {
        val n = EditText(this).apply { hint = "الاسم"; setText(initial?.name.orEmpty()) }
        val v = EditText(this).apply { hint = "النبرة"; setText(initial?.voice.orEmpty()) }
        val no = EditText(this).apply { hint = "ملاحظات"; minLines = 3; setText(initial?.notes.orEmpty()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(n); addView(v); addView(no)
        }
        AlertDialog.Builder(this).setTitle("شخصية").setView(box).setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val name = n.text.toString().trim()
                if (name.isBlank()) toast("الاسم مطلوب")
                else { MangaFoxKnowledgeStore(this).saveCharacter(MangaFoxKnowledgeStore.CharacterEntry(name, v.text.toString().trim(), no.text.toString().trim())); renderHomeTab() }
            }.show()
    }

    private fun saveStyleProfileDialog() {
        val input = EditText(this).apply { hint = "اسم القالب" }
        AlertDialog.Builder(this).setTitle("حفظ نمط").setView(input).setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) { toast("اكتب اسماً"); return@setPositiveButton }
                val json = runCatching { JSONObject(preferences.getString("mfox_style_profiles_v1", "{}") ?: "{}") }.getOrElse { JSONObject() }
                json.put(name, JSONObject()
                    .put("fontSize", preferences.getInt(PREF_FONT_SIZE, 22))
                    .put("fontFamily", preferences.getString(PREF_FONT_FAMILY, "sans-serif"))
                    .put("fontBold", preferences.getBoolean(PREF_FONT_BOLD, false))
                    .put("fontFile", preferences.getString(PREF_FONT_FILE, ""))
                    .put("autoFit", preferences.getBoolean(PREF_AUTO_FONT_FIT, true)))
                preferences.edit().putString("mfox_style_profiles_v1", json.toString()).apply()
                toast("حُفظ القالب")
            }.show()
    }

    private fun applyStyleProfileDialog() {
        val json = runCatching { JSONObject(preferences.getString("mfox_style_profiles_v1", "{}") ?: "{}") }.getOrElse { JSONObject() }
        val names = ArrayList<String>(); val it = json.keys()
        while (it.hasNext()) names.add(it.next())
        if (names.isEmpty()) { toast("لا قوالب"); return }
        AlertDialog.Builder(this).setTitle("اختر قالباً").setItems(names.toTypedArray()) { _, which ->
            val item = json.optJSONObject(names[which]) ?: return@setItems
            preferences.edit()
                .putInt(PREF_FONT_SIZE, item.optInt("fontSize", 22).coerceIn(10, 72))
                .putString(PREF_FONT_FAMILY, item.optString("fontFamily", "sans-serif"))
                .putBoolean(PREF_FONT_BOLD, item.optBoolean("fontBold", false))
                .putString(PREF_FONT_FILE, item.optString("fontFile").takeIf { s -> s.isNotBlank() })
                .putBoolean(PREF_AUTO_FONT_FIT, item.optBoolean("autoFit", true)).apply()
            updateFontPreview(); toast("طُبّق القالب")
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun showLayerHistoryDialog(pageIndex: Int) {
        val pair = readLayerArray(pageIndex) ?: run { toast("لا توجد طبقات"); return }
        val (file, _) = pair
        AlertDialog.Builder(this).setTitle("سجل الصفحة ${pageIndex + 1}")
            .setItems(arrayOf("تراجع", "إعادة")) { _, which ->
                val history = MangaFoxLayerHistory(this)
                val changed = if (which == 0) history.undo(pageIndex, file) else history.redo(pageIndex, file)
                if (changed) {
                    MangaFoxRuntime.persist(this)
                    MangaFoxAccessibilityService.instance?.rerenderLastResultsFromLayers { showResults() }
                        ?: run { toast("افتح الخدمة لإعادة الرسم") }
                } else toast(if (which == 0) "لا يوجد تراجع" else "لا توجد إعادة")
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun launchProjectExport() {
        if (MangaFoxRuntime.resultPages.isEmpty()) { toast("لا مشروع"); return }
        toast("جارٍ تجهيز ZIP...")
        fontDownloadExecutor.execute {
            var archive: File? = null
            try {
                val ready = File(cacheDir, "mfox_phantom_${System.currentTimeMillis()}.zip"); archive = ready
                ready.outputStream().use { MangaFoxProjectArchive.export(this, it) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) { ready.delete(); return@runOnUiThread }
                    pendingProjectArchive = ready
                    val title = MangaFoxRuntime.resultTitle.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(48).ifBlank { "MangaFox_Project" }
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "${title}_PHANTOM.zip")
                    }
                    runCatching { startActivityForResult(intent, REQUEST_EXPORT_PHANTOM_PROJECT) }
                        .onFailure { pendingProjectArchive = null; ready.delete(); toast("تعذر فتح النافذة") }
                }
            } catch (error: Exception) {
                archive?.delete(); runOnUiThread { toast("فشل: ${error.localizedMessage ?: "خطأ"}") }
            }
        }
    }

    private fun launchProjectImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT_PHANTOM_PROJECT) }
            .onFailure { toast("تعذر فتح مدير الملفات") }
    }

    private fun maybeShowOnboarding() {
        if (isFinishing || intent?.action == ACTION_SHOW_RESULTS || preferences.getBoolean(PREF_ONBOARDING_SEEN, false)) return
        AlertDialog.Builder(this).setTitle("مرحباً في Manga Fox")
            .setMessage("فعّل خدمة إمكانية الوصول وإذن الظهور فوق التطبيقات، ثم ارجع للتحقق من حالتهما.")
            .setPositiveButton("خطوات التفعيل") { _, _ ->
                preferences.edit().putBoolean(PREF_ONBOARDING_SEEN, true).apply()
                selectedHomeTab = "translation"; showHome()
            }.setNegativeButton("لاحقاً") { _, _ -> preferences.edit().putBoolean(PREF_ONBOARDING_SEEN, true).apply() }.show()
    }

    private fun openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } else toast("إذن مفعّل")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        } else toast("إذن مفعّل أو غير مطلوب")
    }

    private fun refreshHomeIndicators() {
        val access = isAccessibilityEnabled()
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        accessPermissionText?.text = "${if (access) "✓" else "○"} إمكانية الوصول: ${if (access) "مفعّلة" else "غير مفعّلة"}"
        overlayPermissionText?.text = "${if (overlay) "✓" else "○"} الظهور فوق التطبيقات: ${if (overlay) "مسموح" else "يحتاج إذناً"}"
        notificationPermissionText?.text = "${if (notifications) "✓" else "○"} الإشعارات: ${if (notifications) "مفعّلة" else "غير مفعّلة"}"
        statusText?.text = "الوصول: ${if (access) "مفعّلة" else "غير مفعّلة"}\n" +
            "النافذة: ${if (overlay) "مسموح" else "يحتاج إذناً"}\n" +
            "الحالة: ${MangaFoxRuntime.status}"
        homeProgress?.progress = MangaFoxRuntime.progressPercent.coerceIn(0, 100)
        progressText?.text = "${MangaFoxRuntime.progressPercent.coerceIn(0, 100)}% · ${MangaFoxRuntime.progressDetail.ifBlank { MangaFoxRuntime.status }}"
    }

    // ═══════ SETTINGS ═══════

    private fun addSettingsCard(root: LinearLayout) {
        val settingsCard = card()
        settingsCard.addView(text("إعدادات القراءة والترجمة", 18f, Color.WHITE, true))
        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(242, 239, 230), 14)
        }
        previewPanel.addView(TextView(this).apply {
            text = "معاينة الخط"; textSize = 11f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
        }, matchWrap())
        fontPreview = TextView(this).apply {
            text = "هذا مثال لمعاينة الخط العربي\nالقراءة والترجمة في Manga Fox"
            textSize = preferences.getInt(PREF_FONT_SIZE, 22).toFloat()
            setTextColor(Color.rgb(25, 25, 28)); gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(4), dp(8), dp(4), dp(8)); minHeight = dp(82)
        }
        previewPanel.addView(fontPreview, matchWrap())
        settingsCard.addView(previewPanel, matchWrap(dp(10)))
        addSettingSlider(settingsCard, "حجم الخط العربي", 10, 72, preferences.getInt(PREF_FONT_SIZE, 22), " sp") { v ->
            preferences.edit().putInt(PREF_FONT_SIZE, v).apply(); updateFontPreview()
        }
        val fontFamilies = listOf(
            "sans-serif" to "نسخي حديث", "serif" to "تقليدي", "sans-serif-medium" to "متوسط",
            "sans-serif-condensed" to "مضغوط", "casual" to "يدوي")
        val currentFamily = preferences.getString(PREF_FONT_FAMILY, "sans-serif") ?: "sans-serif"
        val familyName = fontFamilies.firstOrNull { it.first == currentFamily }?.second ?: "مخصص"
        val familyButton = actionButton("خط النظام: $familyName", false) { }
        familyButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("اختر الخط")
                .setItems(fontFamilies.map { it.second }.toTypedArray()) { _, which ->
                    val selected = fontFamilies[which]
                    preferences.edit().remove(PREF_FONT_FILE).remove(PREF_FONT_LABEL)
                        .putString(PREF_FONT_FAMILY, selected.first).apply()
                    familyButton.text = "خط النظام: ${selected.second}"; updateFontPreview()
                }.show()
        }
        settingsCard.addView(familyButton, matchWrap(dp(4)))
        val boldButton = actionButton(
            if (preferences.getBoolean(PREF_FONT_BOLD, true)) "نمط: عريض ✓" else "نمط: عادي", false) { }
        boldButton.setOnClickListener {
            val enabled = !preferences.getBoolean(PREF_FONT_BOLD, true)
            preferences.edit().putBoolean(PREF_FONT_BOLD, enabled).apply()
            boldButton.text = if (enabled) "نمط: عريض ✓" else "نمط: عادي"; updateFontPreview()
        }
        settingsCard.addView(boldButton, matchWrap(dp(4)))
        settingsCard.addView(text("خطوط عربية", 14f, gold, true).apply { setPadding(0, dp(12), 0, dp(4)) }, matchWrap())
        settingsCard.addView(actionButton("تنزيل Noto Kufi Arabic", false) {
            downloadArabicFont("Noto Kufi Arabic", "NotoKufiArabic-Regular.ttf",
                "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io/fonts/NotoKufiArabic/unhinted/ttf/NotoKufiArabic-Regular.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Noto Naskh Arabic", false) {
            downloadArabicFont("Noto Naskh Arabic", "NotoNaskhArabic-Regular.ttf",
                "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io/fonts/NotoNaskhArabic/hinted/ttf/NotoNaskhArabic-Regular.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Noto Sans Arabic", false) {
            downloadArabicFont("Noto Sans Arabic", "NotoSansArabic-Regular.ttf",
                "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io/fonts/NotoSansArabic/unhinted/ttf/NotoSansArabic-Regular.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Amiri", false) {
            downloadArabicFont("Amiri", "Amiri-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/amiri/Amiri-Regular.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Droid Kufi Bold", false) { downloadDroidArabicKufiBold() }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Tajawal", false) {
            downloadArabicFont("Tajawal", "Tajawal-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal/Tajawal-Regular.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Reem Kufi", false) {
            downloadArabicFont("Reem Kufi", "ReemKufi-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/reemkufi/ReemKufi%5Bwght%5D.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("استيراد TTF / OTF / TTC", true) { pickFontFromDevice() }, matchWrap(dp(4)))
        val fontStatus = text(
            preferences.getString(PREF_FONT_LABEL, null)?.let { "الخط: $it" } ?: "الخط الحالي: $familyName",
            12f, Color.LTGRAY)
        fontStatusText = fontStatus; settingsCard.addView(fontStatus, matchWrap())
        val autoFitCheck = CheckBox(this).apply {
            text = "ملاءمة تلقائية داخل الفقاعة"
            setTextColor(Color.WHITE); textSize = 13f
            isChecked = preferences.getBoolean(PREF_AUTO_FONT_FIT, true)
            buttonTintList = android.content.res.ColorStateList.valueOf(gold)
            setOnCheckedChangeListener { _, c -> preferences.edit().putBoolean(PREF_AUTO_FONT_FIT, c).apply() }
        }
        settingsCard.addView(autoFitCheck, matchWrap(dp(2)))
        settingsCard.addView(text("لغة النص الأصلي", 14f, gold, true).apply { setPadding(0, dp(12), 0, dp(3)) }, matchWrap())
        val sourceLanguages = listOf(
            "auto" to "تلقائي", "ja" to "اليابانية", "en" to "الإنجليزية",
            "ko" to "الكورية", "zh" to "الصينية")
        fun langLabel(code: String): String = sourceLanguages.firstOrNull { it.first == code }?.second ?: "تلقائي"
        val sourceLanguageButton = actionButton(
            "لغة النص: ${langLabel(preferences.getString(PREF_SOURCE_LANGUAGE, "auto") ?: "auto")}", false) { }
        sourceLanguageButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("اختر اللغة")
                .setItems(sourceLanguages.map { it.second }.toTypedArray()) { _, which ->
                    val selected = sourceLanguages[which]
                    preferences.edit().putString(PREF_SOURCE_LANGUAGE, selected.first).apply()
                    sourceLanguageButton.text = "لغة النص: ${selected.second}"
                    toast("اللغة: ${selected.second}")
                }.show()
        }
        settingsCard.addView(sourceLanguageButton, matchWrap(dp(4)))
        val sfxOption = CheckBox(this).apply {
            text = "ترجمة SFX اليابانية"
            textSize = 13f; setTextColor(Color.LTGRAY)
            buttonTintList = android.content.res.ColorStateList.valueOf(gold)
            isChecked = preferences.getBoolean(PREF_TRANSLATE_SFX, false)
            setOnCheckedChangeListener { _, c -> preferences.edit().putBoolean(PREF_TRANSLATE_SFX, c).apply() }
        }
        settingsCard.addView(sfxOption, matchWrap(dp(4)))
        addSettingSlider(settingsCard, "حجم العدسة", 42, 96, preferences.getInt(PREF_LENS_SIZE, 58), " dp") { v ->
            preferences.edit().putInt(PREF_LENS_SIZE, v).apply(); MangaFoxAccessibilityService.instance?.applyLensSettings()
        }
        addSettingSlider(settingsCard, "شفافية العدسة", 35, 100, preferences.getInt(PREF_LENS_ALPHA, 100), "%") { v ->
            preferences.edit().putInt(PREF_LENS_ALPHA, v).apply(); MangaFoxAccessibilityService.instance?.applyLensSettings()
        }
        settingsCard.addView(actionButton("استوديو العدسة", true) { openLensStudio() }, matchWrap(dp(4)))
        updateFontPreview(); root.addView(settingsCard, matchWrap(dp(16)))
    }

    private fun showLearnedTranslationDialog(sourceText: String) {
        val input = EditText(this).apply {
            setText(""); hint = "الترجمة العربية"; textSize = 16f; minLines = 2
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        AlertDialog.Builder(this).setTitle("تصحيح ترجمة")
            .setMessage("الأصل:\n$sourceText").setView(input).setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ في القاموس") { _, _ ->
                val corrected = input.text.toString().trim()
                if (corrected.isBlank()) toast("اكتب الترجمة") else {
                    MangaFoxGlossaryCache.put(this, MangaFoxGlossaryCache.normalizeKey(sourceText.trim()), corrected)
                    toast("حُفظ التصحيح")
                }
            }.show()
    }

    private fun openLensStudio() {
        val colors = arrayOf("ذهبي", "بنفسجي", "سماوي", "وردي", "أبيض")
        val values = intArrayOf(Color.rgb(255, 190, 65), Color.rgb(177, 107, 255),
            Color.rgb(90, 190, 255), Color.rgb(255, 107, 176), Color.WHITE)
        var selected = values.indices.minByOrNull { abs(values[it] - preferences.getInt(PREF_LENS_COLOR, values[0])) } ?: 0
        val input = EditText(this).apply {
            setSingleLine(true); hint = "رمز: 狐 أو ◉"
            setText(preferences.getString(PREF_LENS_GLYPH, "狐")); textSize = 20f; gravity = Gravity.CENTER
        }
        val preview = TextView(this).apply {
            text = input.text.toString().ifBlank { "狐" }; textSize = 34f
            gravity = Gravity.CENTER; setTextColor(Color.BLACK); background = rounded(values[selected], 48)
            setPadding(dp(18), dp(12), dp(18), dp(12)); elevation = dp(5).toFloat()
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                preview.text = s?.toString()?.trim()?.take(3).orEmpty().ifBlank { "狐" }
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val colorButton = Button(this).apply {
            isAllCaps = false; text = "اللون: ${colors[selected]}"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("اختر اللون")
                    .setItems(colors) { _, which ->
                        selected = which; text = "اللون: ${colors[selected]}"
                        background = rounded(values[selected], 12)
                        setTextColor(if (selected == 4) Color.BLACK else Color.WHITE)
                        preview.background = rounded(values[selected], 48)
                    }.show()
            }
        }
        colorButton.background = rounded(values[selected], 12)
        colorButton.setTextColor(if (selected == 4) Color.BLACK else Color.WHITE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(preview, LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
            })
            addView(input, matchWrap(dp(8))); addView(colorButton, matchWrap(dp(8)))
        }
        AlertDialog.Builder(this).setTitle("استوديو العدسة").setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val glyph = input.text.toString().trim().take(3).ifBlank { "狐" }
                preferences.edit().putString(PREF_LENS_GLYPH, glyph).putInt(PREF_LENS_COLOR, values[selected]).apply()
                MangaFoxAccessibilityService.instance?.applyLensSettings()
                toast("حُفظ التصميم")
            }.show()
    }

    private fun updateFontPreview() {
        val preview = fontPreview ?: return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        preview.textSize = prefs.getInt(PREF_FONT_SIZE, 22).coerceIn(10, 72).toFloat()
        preview.typeface = resolveArabicTypeface(prefs)
        preview.paint.style = Paint.Style.FILL; preview.paint.strokeWidth = 0f
        preview.invalidate()
        fontStatusText?.text = prefs.getString(PREF_FONT_LABEL, null)?.let { "الخط: $it" }
            ?: "الخط الحالي: ${prefs.getString(PREF_FONT_FAMILY, "sans-serif")}"
    }

    private fun pickFontFromDevice(friendlyLabel: String? = null) {
        requestedFontLabel = friendlyLabel
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_FONT) }.onFailure { toast("تعذر فتح الملفات") }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        if (expectedHex.isBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) { val n = input.read(buf); if (n <= 0) break; digest.update(buf, 0, n) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedHex, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun downloadArabicFont(label: String, fileName: String, sourceUrl: String) {
        val trusted = TRUSTED_FONTS[fileName]
        if (trusted == null) { toast("خط غير مدرج: $fileName"); return }
        toast("بدأ تنزيل $label")
        fontDownloadExecutor.execute {
            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val target = File(fontDir, fileName)
            val temp = File(fontDir, "$fileName.part.${fileName.substringAfterLast('.', "ttf")}")
            try {
                val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 30000; requestMethod = "GET"
                    setRequestProperty("User-Agent", "MangaFox-Android")
                }
                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(8192); var total = 0L
                            while (true) {
                                val read = input.read(buffer); if (read <= 0) break
                                total += read
                                if (total > 15L * 1024L * 1024L) throw IllegalStateException("ملف كبير")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } finally { connection.disconnect() }
                if (!temp.isFile || temp.length() < 4096L) throw IllegalStateException("ملف ناقص")
                if (!verifySha256(temp, trusted.sha256)) {
                    temp.delete(); throw IllegalStateException("فشل SHA-256")
                }
                Typeface.createFromFile(temp)
                if (target.exists() && !target.delete()) throw IllegalStateException("تعذر استبدال")
                if (!temp.renameTo(target)) throw IllegalStateException("تعذر حفظ")
                runOnUiThread {
                    preferences.edit().putString(PREF_FONT_FILE, target.absolutePath)
                        .putString(PREF_FONT_LABEL, label).apply()
                    updateFontPreview(); toast("تم $label")
                }
            } catch (e: Exception) {
                temp.delete(); runOnUiThread { toast("فشل: ${e.localizedMessage ?: "تحقق من الشبكة"}") }
            }
        }
    }

    private fun downloadDroidArabicKufiBold() {
        val source = "https://android.googlesource.com/platform/frameworks/base/+/e7beae3/data/fonts/DroidKufi-Bold.ttf?format=TEXT"
        val trusted = TRUSTED_FONTS["DroidKufi-Bold.ttf"] ?: run { toast("خط غير مدرج"); return }
        toast("جارٍ تنزيل Droid Kufi Bold")
        fontDownloadExecutor.execute {
            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val target = File(fontDir, "DroidKufi-Bold.ttf")
            val temp = File(fontDir, "DroidKufi-Bold.ttf.part")
            try {
                val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 30000; requestMethod = "GET"
                    setRequestProperty("User-Agent", "MangaFox-Android")
                }
                val encoded = try {
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            total += n
                            require(total <= 24 * 1024 * 1024) { "استجابة كبيرة" }
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                } finally { connection.disconnect() }
                val ascii = encoded.toString(Charsets.US_ASCII).filterNot { it.isWhitespace() }
                val bytes = Base64.decode(ascii, Base64.DEFAULT)
                require(bytes.size in 4096..(15 * 1024 * 1024)) { "حجم غير صالح" }
                FileOutputStream(temp).use { it.write(bytes) }
                if (!verifySha256(temp, trusted.sha256)) {
                    temp.delete(); throw IllegalStateException("فشل SHA-256")
                }
                Typeface.createFromFile(temp)
                if (target.exists() && !target.delete()) error("تعذر استبدال")
                if (!temp.renameTo(target)) error("تعذر حفظ")
                runOnUiThread {
                    preferences.edit().putString(PREF_FONT_FILE, target.absolutePath)
                        .putString(PREF_FONT_LABEL, "Droid Arabic Kufi Bold · Apache-2.0")
                        .putBoolean(PREF_FONT_BOLD, true).apply()
                    updateFontPreview(); toast("تم Droid Kufi")
                }
            } catch (e: Exception) {
                temp.delete(); runOnUiThread { toast("فشل: ${e.localizedMessage ?: "تحقق من الاتصال"}") }
            }
        }
    }

    private fun addSettingSlider(
        parent: LinearLayout, label: String, minValue: Int, maxValue: Int,
        initialValue: Int, suffix: String, onChanged: (Int) -> Unit) {
        val labelView = text("$label: ${initialValue.coerceIn(minValue, maxValue)}$suffix", 13f, Color.LTGRAY, true)
        parent.addView(labelView, matchWrap())
        val slider = SeekBar(this).apply {
            max = maxValue - minValue; progress = initialValue.coerceIn(minValue, maxValue) - minValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = minValue + progress; labelView.text = "$label: $v$suffix"
                    if (fromUser) onChanged(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        parent.addView(slider, matchWrap(dp(8)))
    }

    private fun openRegionSelection(sourcePath: String, resultPath: String) {
        val original = BitmapFactory.decodeFile(sourcePath)
        if (original == null) { toast("تعذر فتح الأصل"); return }
        val maxWidth = min(resources.displayMetrics.widthPixels - dp(56), dp(360)).coerceAtLeast(dp(220))
        var viewWidth = maxWidth
        var viewHeight = (original.height * (viewWidth.toFloat() / original.width)).toInt().coerceAtLeast(dp(180))
        if (viewHeight > dp(620)) {
            viewHeight = dp(620); viewWidth = (original.width * (viewHeight.toFloat() / original.height)).toInt().coerceAtLeast(dp(180))
        }
        val image = ImageView(this).apply { setImageBitmap(original); scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.BLACK) }
        val selector = RegionSelectionView(this)
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(viewWidth, viewHeight))
            addView(selector, FrameLayout.LayoutParams(viewWidth, viewHeight))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(text("اسحب لتحديد منطقة.", 12f, Color.LTGRAY), matchWrap(dp(8)))
            addView(frame, LinearLayout.LayoutParams(viewWidth, viewHeight))
        }
        val dialog = AlertDialog.Builder(this).setTitle("منطقة محددة")
            .setView(container).setNegativeButton("إلغاء", null).setPositiveButton("متابعة", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val area = selector.toSourceRect(original.width, original.height)
                if (area == null || area.width() < 8 || area.height() < 8) { toast("منطقة صغيرة"); return@setOnClickListener }
                val service = MangaFoxAccessibilityService.instance
                if (service == null) { toast("خدمة غير متصلة"); return@setOnClickListener }
                dialog.dismiss()
                service.retryManualRegion(sourcePath, resultPath, area) { ok, msg -> toast(msg); if (ok) showResults() }
            }
        }
        dialog.setOnDismissListener { image.setImageDrawable(null); if (!original.isRecycled) original.recycle() }
        dialog.show()
    }

    private fun decodeComparisonPreview(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1100 || bounds.outHeight / sample > 1800) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun showBeforeAfterComparison(originalPath: String, resultPath: String) {
        val loading = ProgressBar(this).apply { isIndeterminate = true }
        val loadingDialog = AlertDialog.Builder(this).setTitle("إعداد المقارنة").setView(loading).setNegativeButton("إلغاء", null).show()
        fontDownloadExecutor.execute {
            var original: Bitmap? = null; var translated: Bitmap? = null
            try {
                original = decodeComparisonPreview(originalPath); translated = decodeComparisonPreview(resultPath)
                val ob = original; val tb = translated
                runOnUiThread {
                    loadingDialog.dismiss()
                    if (isFinishing || isDestroyed || ob == null || tb == null) {
                        ob?.recycle(); tb?.recycle()
                        if (!isFinishing && !isDestroyed) toast("تعذر إعداد المقارنة")
                        return@runOnUiThread
                    }
                    val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipToPadding = true; clipChildren = true }
                    val beforeView = ImageView(this).apply { setImageBitmap(ob); scaleType = ImageView.ScaleType.FIT_CENTER }
                    val afterView = ImageView(this).apply { setImageBitmap(tb); scaleType = ImageView.ScaleType.FIT_CENTER }
                    frame.addView(beforeView, FrameLayout.LayoutParams(-1, dp(420)))
                    frame.addView(afterView, FrameLayout.LayoutParams(-1, dp(420)))
                    val label = text("تلاشٍ: 0٪ = الأصل · 100٪ = الترجمة", 12f, Color.LTGRAY)
                    val slider = SeekBar(this).apply {
                        max = 100; progress = 100
                        progressTintList = android.content.res.ColorStateList.valueOf(gold)
                        thumbTintList = android.content.res.ColorStateList.valueOf(gold)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                                afterView.alpha = value / 100f
                                label.text = when (value) { 0 -> "الأصل فقط"; 100 -> "الترجمة فقط"; else -> "شفافية $value%" }
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                        })
                    }
                    val panel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4))
                        addView(frame, LinearLayout.LayoutParams(-1, dp(420)))
                        addView(label, matchWrap(dp(6))); addView(slider, matchWrap())
                    }
                    val dialog = AlertDialog.Builder(this).setTitle("مقارنة").setView(panel).setPositiveButton("إغلاق", null).create()
                    dialog.setOnDismissListener {
                        beforeView.setImageDrawable(null); afterView.setImageDrawable(null)
                        if (!ob.isRecycled) ob.recycle(); if (!tb.isRecycled) tb.recycle()
                    }
                    dialog.show()
                }
            } catch (error: Exception) {
                original?.let { if (!it.isRecycled) it.recycle() }
                translated?.let { if (!it.isRecycled) it.recycle() }
                runOnUiThread { loadingDialog.dismiss(); toast("تعذرت المقارنة: ${error.localizedMessage ?: "خطأ"}") }
            }
        }
    }

    private fun showResults() {
        setReadingFullscreen(true)
        val editMode = preferences.getBoolean(PREF_RESULTS_EDIT_MODE, false)
        val scroll = ScrollView(this).apply { setBackgroundColor(dark); isFillViewport = true; clipToPadding = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(if (editMode) dp(12) else 0, if (editMode) dp(8) else 0,
                if (editMode) dp(12) else 0, if (editMode) dp(12) else 0)
            setBackgroundColor(Color.BLACK)
        }
        val pages = MangaFoxRuntime.resultPages
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun compactButton(label: String, action: () -> Unit): Button = actionButton(label, false, action).apply {
            textSize = 11f; minHeight = dp(44); minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3); topMargin = dp(3); bottomMargin = dp(5)
            }
        }
        topBar.addView(compactButton("الرئيسية") { showHome() })
        topBar.addView(compactButton("PDF") { exportResultsAsPdf() })
        topBar.addView(compactButton(if (editMode) "معاينة" else "تحرير") {
            preferences.edit().putBoolean(PREF_RESULTS_EDIT_MODE, !editMode).apply(); showResults()
        })
        root.addView(topBar, matchWrap(dp(4)))
        root.addView(text(if (editMode) "MANGA FOX · تحرير" else "MANGA FOX · معاينة", 16f, gold, true)
            .apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(5)) }, matchWrap())
        if (editMode) {
            root.addView(text("${pages.size} صورة · ${MangaFoxRuntime.resultTitle}", 12f, Color.LTGRAY)
                .apply { gravity = Gravity.CENTER }, matchWrap(dp(5)))
            root.addView(actionButton("تطبيق الخط دون OCR", true) {
                val service = MangaFoxAccessibilityService.instance
                if (MangaFoxRuntime.sourcePages.isEmpty()) toast("الصور الأصلية مفقودة")
                else if (service == null) toast("فعّل الخدمة")
                else service.rerenderLastResultsFromLayers { showResults() }
            }, matchWrap(dp(5)))
            root.addView(actionButton("إعادة OCR والترجمة", false) {
                MangaFoxAccessibilityService.instance?.retranslateLastResults() ?: toast("فعّل الخدمة")
            }, matchWrap(dp(5)))
            root.addView(text("QUALITY\n${MangaFoxRuntime.qualitySummary}", 12f, Color.LTGRAY), matchWrap(dp(7)))
            root.addView(actionButton("عرض النصوص الفاشلة", false) {
                val samples = MangaFoxRuntime.failedBlockSamples.take(25)
                if (samples.isEmpty()) AlertDialog.Builder(this).setTitle("لا فشل")
                    .setMessage("لا عينات فشل.").setPositiveButton("إغلاق", null).show()
                else AlertDialog.Builder(this).setTitle("اختر نصاً")
                    .setItems(samples.toTypedArray()) { _, which -> showLearnedTranslationDialog(samples[which]) }
                    .setNegativeButton("إغلاق", null).show()
            }, matchWrap(dp(8)))
        } else {
            root.addView(text("${pages.size} صورة · ${MangaFoxRuntime.resultTitle}\nاضغط مطولاً للتبديل بين الأصل والترجمة.",
                11f, Color.LTGRAY).apply { gravity = Gravity.CENTER }, matchWrap(dp(8)))
        }
        if (pages.isEmpty()) {
            root.addView(text(
                if (MangaFoxRuntime.lastError.isNotBlank()) MangaFoxRuntime.lastError
                else "لا نتيجة. افتح فصلاً واضغط العدسة مطولاً.",
                15f, Color.LTGRAY
            ).apply { setPadding(dp(12), dp(30), dp(12), dp(30)) }, matchWrap())
        } else {
            val pageViews = ArrayList<ZoomableImageView>()
            pages.forEachIndexed { index, filePath ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, bounds)
                val sw = bounds.outWidth.coerceAtLeast(1); val sh = bounds.outHeight.coerceAtLeast(1)
                val originalPath = MangaFoxRuntime.sourcePages.getOrNull(index)?.takeIf { File(it).isFile }
                var showingOriginal = false
                val image = ZoomableImageView(this).apply {
                    setImageFile(filePath)
                    contentDescription = "صورة ${index + 1} من ${pages.size}"
                    setPadding(0, 0, 0, 0); minimumHeight = 0; minimumWidth = 0; setBackgroundColor(Color.BLACK)
                    setOnLongClickListener {
                        if (originalPath == null) toast("الأصل مفقود")
                        else {
                            showingOriginal = !showingOriginal
                            setImageFile(if (showingOriginal) originalPath else filePath); loadPage()
                            toast(if (showingOriginal) "الأصل · اضغط مطولاً للعودة" else "الترجمة")
                        }; true
                    }
                }
                val dw = if (editMode) resources.displayMetrics.widthPixels - dp(24) else resources.displayMetrics.widthPixels
                val dh = ceil(sh * (dw.toFloat() / sw)).toInt().coerceAtLeast(1)
                root.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dh)
                    .apply { bottomMargin = 0; topMargin = 0 })
                pageViews.add(image)
                if (editMode) {
                    root.addView(text("صورة ${index + 1} / ${pages.size}", 11f, gold, true), matchWrap(dp(3)))
                    if (originalPath != null) {
                        root.addView(actionButton("مقارنة", false) { showBeforeAfterComparison(originalPath, filePath) }, matchWrap(dp(4)))
                        root.addView(actionButton("إعادة OCR لمنطقة", false) { openRegionSelection(originalPath, filePath) }, matchWrap(dp(4)))
                    }
                    root.addView(actionButton("إدارة الطبقات", false) { editTranslationLayer(index) }, matchWrap(dp(4)))
                    root.addView(actionButton("سجل التراجع", false) { showLayerHistoryDialog(index) }, matchWrap(dp(4)))
                    root.addView(actionButton("حذف الصورة", false) {
                        AlertDialog.Builder(this).setTitle("حذف الصورة ${index + 1}؟")
                            .setMessage("سيُحذف الملف والطبقات.")
                            .setNegativeButton("إلغاء", null)
                            .setPositiveButton("حذف") { _, _ ->
                                MangaFoxRuntime.removePage(this, index); showResults(); toast("تم الحذف")
                            }.show()
                    }, matchWrap(dp(10)))
                }
            }
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateVisiblePageImages(pageViews) }
            scroll.post { updateVisiblePageImages(pageViews) }
        }
        scroll.addView(root); setContentView(scroll)
    }

    private fun layerFileForPage(pageIndex: Int): File? {
        if (pageIndex !in MangaFoxRuntime.resultPages.indices) return null
        val current = MangaFoxRuntime.translationLayerFiles.getOrNull(pageIndex)
            ?.takeIf { it.isNotBlank() }?.let { path -> File(path) }?.takeIf { it.isFile }
        if (current != null) return current
        val created = File(filesDir, "mfox_layers_editor_${System.currentTimeMillis()}_$pageIndex.json")
        val originalPath = MangaFoxRuntime.sourcePages.getOrNull(pageIndex).orEmpty()
        val marker = JSONArray().put(JSONObject().put("_mfoxKeepFlattenedBase", true).put("_basePath", originalPath))
        runCatching { created.writeText(marker.toString(), Charsets.UTF_8) }.getOrElse { return null }
        val updated = MangaFoxRuntime.translationLayerFiles.toMutableList()
        while (updated.size < MangaFoxRuntime.resultPages.size) updated.add("")
        updated[pageIndex] = created.absolutePath
        MangaFoxRuntime.translationLayerFiles = updated; MangaFoxRuntime.persist(this)
        return created
    }

    private fun readLayerArray(pageIndex: Int): Pair<File, JSONArray>? {
        val file = layerFileForPage(pageIndex) ?: return null
        val array = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        return file to array
    }

    private fun saveLayerChanges(file: File, layers: JSONArray, refresh: Boolean = true) {
        runCatching {
            val hi = MangaFoxRuntime.translationLayerFiles.indexOf(file.absolutePath)
            if (hi >= 0) MangaFoxLayerHistory(this).push(hi, file)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(layers.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
            MangaFoxRuntime.persist(this)
        }.onSuccess {
            if (refresh) {
                val service = MangaFoxAccessibilityService.instance
                if (service == null) toast("حُفظ. افتح الخدمة للرسم")
                else service.rerenderLastResultsFromLayers { showResults() }
            }
        }.onFailure { toast("تعذر الحفظ: ${it.localizedMessage ?: "خطأ"}") }
    }

    private fun editTranslationLayer(pageIndex: Int) {
        val pair = readLayerArray(pageIndex) ?: run { toast("لا طبقات"); return }
        val (file, layers) = pair
        val visibleIndexes = (0 until layers.length()).filter { idx ->
            layers.optJSONObject(idx)?.optBoolean("_mfoxKeepFlattenedBase", false) != true
        }
        val labels = visibleIndexes.map { idx ->
            val item = layers.optJSONObject(idx)
            val t = item?.optString("translated").orEmpty().take(50)
            val s = item?.optString("source").orEmpty().take(35)
            val mode = when {
                item?.optBoolean("eraseOnly", false) == true -> " · مسح"
                item?.optBoolean("vertical", false) == true -> " · عمودي"
                else -> ""
            }
            "${idx + 1}. ${t.ifBlank { s.ifBlank { "طبقة" } }}$mode"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("طبقات ${pageIndex + 1}")
            .setItems(labels) { _, selected ->
                val ai = visibleIndexes.getOrNull(selected) ?: return@setItems
                val item = layers.optJSONObject(ai) ?: return@setItems
                val actions = arrayOf("تعديل النص",
                    if (item.optBoolean("vertical", false)) "أفقي" else "عمودي",
                    "مسح النص", "تغيير الموضع", "تبديل الشكل", "حذف")
                AlertDialog.Builder(this).setTitle("طبقة ${ai + 1}")
                    .setItems(actions) { _, action ->
                        when (action) {
                            0 -> {
                                val edit = EditText(this).apply {
                                    setText(item.optString("translated")); hint = "الترجمة العربية"
                                    minLines = 2; textSize = 16f
                                    setPadding(dp(10), dp(8), dp(10), dp(8))
                                }
                                AlertDialog.Builder(this).setTitle("تعديل النص")
                                    .setMessage("الأصل: ${item.optString("source")}")
                                    .setView(edit).setNegativeButton("إلغاء", null)
                                    .setPositiveButton("حفظ") { _, _ ->
                                        val c = edit.text.toString().trim()
                                        if (c.isBlank()) toast("استخدم المسح")
                                        else {
                                            item.put("translated", c).put("eraseOnly", false)
                                            val s = item.optString("source").trim()
                                            if (s.isNotBlank()) MangaFoxGlossaryCache.put(this, MangaFoxGlossaryCache.normalizeKey(s), c)
                                            saveLayerChanges(file, layers)
                                        }
                                    }.show()
                            }
                            1 -> { item.put("vertical", !item.optBoolean("vertical", false)); saveLayerChanges(file, layers) }
                            2 -> AlertDialog.Builder(this).setTitle("مسح النص؟")
                                .setMessage("سيُغطى النص الأصلي بلون الخلفية.")
                                .setNegativeButton("إلغاء", null)
                                .setPositiveButton("مسح") { _, _ ->
                                    item.put("eraseOnly", true).put("translated", ""); saveLayerChanges(file, layers)
                                }.show()
                            3 -> openLayerRegionSelection(pageIndex, ai, false)
                            4 -> {
                                item.put("shape", if (item.optString("shape", "round") == "oval") "rect" else "oval")
                                saveLayerChanges(file, layers)
                            }
                            5 -> AlertDialog.Builder(this).setTitle("حذف الطبقة؟")
                                .setNegativeButton("إلغاء", null)
                                .setPositiveButton("حذف") { _, _ ->
                                    val updated = JSONArray()
                                    for (i in 0 until layers.length()) if (i != ai) updated.put(layers.optJSONObject(i))
                                    saveLayerChanges(file, updated)
                                }.show()
                        }
                    }.setNegativeButton("إغلاق", null).show()
            }.setPositiveButton("إضافة فقاعة") { _, _ -> openLayerRegionSelection(pageIndex, null, false) }
            .setNeutralButton("مسح نص") { _, _ -> openLayerRegionSelection(pageIndex, null, true) }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun openLayerRegionSelection(pageIndex: Int, layerIndex: Int?, eraseOnly: Boolean) {
        val sourcePath = MangaFoxRuntime.sourcePages.getOrNull(pageIndex)
        val original = sourcePath?.let { BitmapFactory.decodeFile(it) }
        if (original == null) { toast("الأصل مفقود"); return }
        val maxWidth = min(resources.displayMetrics.widthPixels - dp(56), dp(360)).coerceAtLeast(dp(220))
        var viewWidth = maxWidth
        var viewHeight = (original.height * (viewWidth.toFloat() / original.width)).toInt().coerceAtLeast(dp(180))
        if (viewHeight > dp(620)) {
            viewHeight = dp(620); viewWidth = (original.width * (viewHeight.toFloat() / original.height)).toInt().coerceAtLeast(dp(180))
        }
        val image = ImageView(this).apply { setImageBitmap(original); scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.BLACK) }
        val selector = RegionSelectionView(this)
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(viewWidth, viewHeight))
            addView(selector, FrameLayout.LayoutParams(viewWidth, viewHeight))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(text("اسحب لتحديد الحدود.", 12f, Color.LTGRAY), matchWrap(dp(8)))
            addView(frame, LinearLayout.LayoutParams(viewWidth, viewHeight))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (layerIndex != null) "تعديل الموضع" else if (eraseOnly) "مسح" else "إضافة")
            .setView(container).setNegativeButton("إلغاء", null).setPositiveButton("متابعة", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rect = selector.toSourceRect(original.width, original.height)
                if (rect == null || rect.width() < 8 || rect.height() < 8) { toast("منطقة صغيرة"); return@setOnClickListener }
                dialog.dismiss()
                val pair = readLayerArray(pageIndex) ?: run { toast("ملف الطبقات مفقود"); return@setOnClickListener }
                val (file, layers) = pair
                if (layerIndex != null) {
                    val item = layers.optJSONObject(layerIndex) ?: return@setOnClickListener
                    item.put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
                    saveLayerChanges(file, layers)
                } else if (eraseOnly) {
                    layers.put(JSONObject()
                        .put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
                        .put("source", "").put("translated", "").put("language", "manual")
                        .put("vertical", false).put("eraseOnly", true).put("shape", "rect"))
                    saveLayerChanges(file, layers)
                } else {
                    val input = EditText(this).apply { hint = "النص العربي"; minLines = 2; textSize = 16f }
                    val vertical = CheckBox(this).apply { text = "عمودي" }
                    val oval = CheckBox(this).apply { text = "بيضاوي" }
                    val content = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4))
                        addView(input, matchWrap(dp(8))); addView(vertical, matchWrap(dp(4))); addView(oval, matchWrap())
                    }
                    AlertDialog.Builder(this).setTitle("الطبقة الجديدة").setView(content)
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("حفظ") { _, _ ->
                            val value = input.text.toString().trim()
                            if (value.isBlank()) toast("اكتب نصاً")
                            else {
                                layers.put(JSONObject()
                                    .put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
                                    .put("source", "").put("translated", value).put("language", "manual")
                                    .put("vertical", vertical.isChecked).put("eraseOnly", false)
                                    .put("shape", if (oval.isChecked) "oval" else "rect"))
                                saveLayerChanges(file, layers)
                            }
                        }.show()
                }
            }
        }
        dialog.setOnDismissListener { image.setImageDrawable(null); if (!original.isRecycled) original.recycle() }
        dialog.show()
    }

    private fun updateVisiblePageImages(pages: List<ZoomableImageView>) {
        val visibleBounds = Rect()
        pages.forEach { page ->
            if (page.getLocalVisibleRect(visibleBounds)) page.loadPage() else page.releasePage()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/${MangaFoxAccessibilityService::class.java.name}") ||
            enabled.contains("$packageName/.MangaFoxAccessibilityService")
    }

    private fun exportResultsAsPdf() {
        val audit = MangaFoxQualityGuard.inspect(MangaFoxRuntime.resultPages, MangaFoxRuntime.translationLayerFiles)
        if (audit.errorCount > 0 || audit.warningCount > 0) {
            AlertDialog.Builder(this).setTitle("مراجعة قبل PDF")
                .setMessage(audit.toArabicText() + "\n\nمتابعة التصدير؟")
                .setNegativeButton("العودة", null)
                .setPositiveButton("متابعة") { _, _ -> exportResultsAsPdfAfterAudit() }.show()
            return
        }
        exportResultsAsPdfAfterAudit()
    }

    private fun exportResultsAsPdfAfterAudit() {
        val images = MangaFoxRuntime.resultPages.map { File(it) }.filter { it.isFile && it.length() > 0L }
        if (images.isEmpty()) { toast("لا صور"); return }
        toast("جارٍ تجهيز PDF...")
        fontDownloadExecutor.execute {
            var prepared: File? = null
            try {
                val ready = File(cacheDir, "mfox_export_${System.currentTimeMillis()}.pdf"); prepared = ready
                createContinuousPdf(images, ready)
                runOnUiThread {
                    if (isFinishing || isDestroyed) { ready.delete(); return@runOnUiThread }
                    pendingPdfFile = ready
                    val title = MangaFoxRuntime.resultTitle.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().take(60).ifBlank { "Manga_Fox_Result" }
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
                        putExtra(Intent.EXTRA_TITLE, "$title.pdf")
                    }
                    try { startActivityForResult(intent, REQUEST_SAVE_PDF) }
                    catch (e: Exception) { pendingPdfFile = null; ready.delete(); toast("لا تطبيق PDF") }
                }
            } catch (e: Exception) {
                prepared?.delete(); runOnUiThread { toast("تعذر PDF: ${e.localizedMessage ?: "خطأ"}") }
            }
        }
    }

    private fun createContinuousPdf(images: List<File>, outputFile: File) {
        val pdf = PdfDocument()
        try {
            val pageWidth = 595; val pageHeight = 842
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            var page: PdfDocument.Page? = null; var pageOpen = false; var yOnPage = 0f; var pageNumber = 0
            fun startPage() {
                if (pageOpen) return
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                yOnPage = 0f; page!!.canvas.drawColor(Color.WHITE); pageOpen = true
            }
            fun endPage() {
                if (!pageOpen) return
                page?.let { pdf.finishPage(it) }; page = null; yOnPage = 0f; pageOpen = false
            }
            for (file in images) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IllegalStateException("تعذر فتح ${file.name}")
                try {
                    val scale = pageWidth.toFloat() / bmp.width.toFloat()
                    var consumed = 0f
                    while (consumed < bmp.height - 0.01f) {
                        startPage()
                        val available = pageHeight.toFloat() - yOnPage
                        val remaining = bmp.height.toFloat() - consumed
                        val sourceChunkHeight = min(remaining, available / scale)
                        if (sourceChunkHeight <= 0.01f) { endPage(); continue }
                        val sourceTop = consumed.toInt().coerceIn(0, bmp.height - 1)
                        val sourceBottom = (consumed + sourceChunkHeight).toInt().coerceIn(sourceTop + 1, bmp.height)
                        val actualH = (sourceBottom - sourceTop).toFloat()
                        val destH = actualH * scale
                        page!!.canvas.drawBitmap(bmp,
                            Rect(0, sourceTop, bmp.width, sourceBottom),
                            RectF(0f, yOnPage, pageWidth.toFloat(), yOnPage + destH), paint)
                        consumed = sourceBottom.toFloat(); yOnPage += destH
                        if (yOnPage >= pageHeight - 0.5f) endPage()
                    }
                } finally { if (!bmp.isRecycled) bmp.recycle() }
            }
            endPage()
            if (pageNumber == 0) throw IllegalStateException("لا صور")
            FileOutputStream(outputFile).use { pdf.writeTo(it) }
        } finally { runCatching { pdf.close() } }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SAVE_PDF) {
            val prepared = pendingPdfFile; pendingPdfFile = null
            if (resultCode != RESULT_OK || data?.data == null || prepared == null || !prepared.isFile) {
                prepared?.delete(); if (resultCode != RESULT_OK) toast("أُلغي الحفظ"); return
            }
            val destination = data.data ?: run { prepared.delete(); return }
            fontDownloadExecutor.execute {
                try {
                    val out = contentResolver.openOutputStream(destination, "w") ?: throw IllegalStateException("تعذر الوجهة")
                    out.use { o -> prepared.inputStream().use { it.copyTo(o) } }
                    prepared.delete(); runOnUiThread { toast("حُفظ PDF") }
                } catch (e: Exception) {
                    prepared.delete(); runOnUiThread { toast("فشل الحفظ: ${e.localizedMessage ?: "خطأ"}") }
                }
            }
            return
        }
        if (requestCode == REQUEST_EXPORT_PHANTOM_PROJECT) {
            val prepared = pendingProjectArchive; pendingProjectArchive = null
            val destination = data?.data
            if (resultCode != RESULT_OK || destination == null || prepared == null || !prepared.isFile) {
                prepared?.delete(); if (resultCode != RESULT_OK) toast("أُلغي"); return
            }
            fontDownloadExecutor.execute {
                runCatching {
                    val out = contentResolver.openOutputStream(destination, "w") ?: error("تعذر الوجهة")
                    out.use { o -> prepared.inputStream().use { it.copyTo(o) } }
                }.onSuccess { runOnUiThread { toast("حُفظ النسخ الاحتياطي") } }
                    .onFailure { e -> runOnUiThread { toast("تعذر: ${e.localizedMessage ?: "خطأ"}") } }
                prepared.delete()
            }
            return
        }
        if (requestCode == REQUEST_IMPORT_PHANTOM_PROJECT) {
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { if (resultCode != RESULT_OK) toast("أُلغي"); return }
            toast("جارٍ الاستعادة...")
            fontDownloadExecutor.execute {
                runCatching {
                    val input = contentResolver.openInputStream(uri) ?: error("تعذر الملف")
                    input.use { MangaFoxProjectArchive.restore(this, it) }
                }.onSuccess {
                    MangaFoxGlossaryCache.invalidate()
                    runOnUiThread { toast("تمت الاستعادة"); showResults() }
                }.onFailure { e -> runOnUiThread { toast("فشل: ${e.localizedMessage ?: "نسخة غير صالحة"}") } }
            }
            return
        }
        if (requestCode != REQUEST_PICK_FONT) return
        if (resultCode != RESULT_OK) { requestedFontLabel = null; return }
        val uri = data?.data ?: run { requestedFontLabel = null; return }
        val friendlyFontLabel = requestedFontLabel; requestedFontLabel = null
        runCatching {
            val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            } ?: "font.ttf"
            val ext = displayName.substringAfterLast('.', "").lowercase()
            require(ext in setOf("ttf", "otf", "ttc")) { "اختر TTF/OTF/TTC" }
            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val target = File(fontDir, "import_${System.currentTimeMillis()}_$safe")
            val input = requireNotNull(contentResolver.openInputStream(uri)) { "تعذر القراءة" }
            input.use { i ->
                FileOutputStream(target).use { o ->
                    val buf = ByteArray(8192); var total = 0L
                    while (true) {
                        val r = i.read(buf); if (r <= 0) break
                        total += r
                        require(total <= 15L * 1024L * 1024L) { "الخط أكبر من 15MB" }
                        o.write(buf, 0, r)
                    }
                }
            }
            Typeface.createFromFile(target)
            preferences.edit().putString(PREF_FONT_FILE, target.absolutePath)
                .putString(PREF_FONT_LABEL, if (friendlyFontLabel.isNullOrBlank()) displayName else "$friendlyFontLabel ($displayName)")
                .apply()
            updateFontPreview(); toast("تم استيراد: ${friendlyFontLabel ?: displayName}")
        }.onFailure { toast("فشل الاستيراد: ${it.localizedMessage ?: "ملف غير صالح"}") }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(15), dp(16), dp(15)); background = rounded(panel, 18)
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.RIGHT
        setPadding(0, dp(5), 0, dp(6))
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = rounded(if (primary) gold else Color.rgb(50, 49, 57), 13)
        setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(7); bottomMargin = dp(3)
        }
    }
    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun matchWrap(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (bottom > 0) bottomMargin = bottom
        }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

// ═══════════════════════ ACCESSIBILITY SERVICE ═══════════════════════

class MangaFoxAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: MangaFoxAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var hostActivityResumed: Boolean = false
    private var captureRunning = false
    private var fullChapterMode = false
    private var captureCount = 0
    private var unchangedFrameCount = 0
    private var screenshotRetryCount = 0
    private var gestureFailureCount = 0
    private var adaptiveSwipeFraction = 0.45f
    private var lastScreenshotMs = 0L
    @Volatile private var translationRunning = false
    private var activeTranslationJob: Future<*>? = null
    private var previousFrame: Bitmap? = null
    private val capturedSegments = ArrayList<File>()
    private var bubbleWasAdded = false

    // ═══ Cache Typeface ═══
    @Volatile private var cachedTypefaceKey: String? = null
    @Volatile private var cachedTypeface: Typeface? = null

    private val latinRecognizerDelegate = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val latinRecognizer: TextRecognizer by latinRecognizerDelegate
    private val japaneseRecognizerDelegate = lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val japaneseRecognizer: TextRecognizer by japaneseRecognizerDelegate
    private val koreanRecognizerDelegate = lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val koreanRecognizer: TextRecognizer by koreanRecognizerDelegate
    private val chineseRecognizerDelegate = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val chineseRecognizer: TextRecognizer by chineseRecognizerDelegate
    private val languageIdentifierDelegate = lazy { LanguageIdentification.getClient() }
    private val languageIdentifier by languageIdentifierDelegate
    private val translatorCache = LinkedHashMap<String, Translator>()
    private val lastOcrTranslationLines = ThreadLocal.withInitial<List<TranslationLine>> { emptyList() }

    private val maxCaptureFrames: Int
        get() {
            val heapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
            return when { heapMb < 256 -> 80; heapMb < 512 -> 150; else -> 250 }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 80L
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
        }
        MangaFoxRuntime.status = "خدمة العدسة متصلة"
        syncForegroundPackage()
        mainHandler.postDelayed({ syncForegroundPackage() }, 300L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 800L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 1500L)
        refreshLensAppFilter()
        mainHandler.postDelayed({ recoverInterruptedCapture() }, 900L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (pkg != null && pkg != currentForegroundPackage) { currentForegroundPackage = pkg; refreshLensAppFilter() }
    }

    private fun syncForegroundPackage() {
        val activePackage = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (activePackage != null && activePackage != currentForegroundPackage) {
            currentForegroundPackage = activePackage; refreshLensAppFilter()
        }
    }

    fun onHostActivityResumed() {
        hostActivityResumed = true; currentForegroundPackage = packageName; refreshLensAppFilter()
    }

    fun onHostActivityPaused() {
        hostActivityResumed = false
        mainHandler.postDelayed({ syncForegroundPackage() }, 250L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 650L)
        mainHandler.postDelayed({
            syncForegroundPackage()
            if (!hostActivityResumed && currentForegroundPackage == packageName) {
                currentForegroundPackage = "__external__"; refreshLensAppFilter()
            } else if (!hostActivityResumed && currentForegroundPackage.isNullOrBlank()) {
                currentForegroundPackage = "__external__"; refreshLensAppFilter()
            }
        }, 1100L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 1800L)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeFloatingBubble()
        if (instance === this) instance = null
        previousFrame?.recycle(); previousFrame = null
        clearCaptureNotification()
        if (captureRunning && capturedSegments.isNotEmpty()) {
            MangaFoxRuntime.status = "انقطعت الخدمة؛ ${capturedSegments.size} مقطع محفوظ"
            MangaFoxRuntime.lastError = "أوقف النظام الخدمة أثناء الالتقاط. أعد التفعيل للاستعادة."
        } else if (!translationRunning) {
            capturedSegments.forEach { runCatching { it.delete() } }
            capturedSegments.clear(); closeRecognitionResources(); worker.shutdown()
        } else {
            activeTranslationJob?.let {
                mainHandler.postDelayed({
                    if (!translationRunning) { closeRecognitionResources(); worker.shutdown() }
                }, 120_000L)
            } ?: run { closeRecognitionResources(); worker.shutdown() }
        }
        super.onDestroy()
    }

    private fun closeRecognitionResources() {
        if (latinRecognizerDelegate.isInitialized()) runCatching { latinRecognizer.close() }
        if (japaneseRecognizerDelegate.isInitialized()) runCatching { japaneseRecognizer.close() }
        if (koreanRecognizerDelegate.isInitialized()) runCatching { koreanRecognizer.close() }
        if (chineseRecognizerDelegate.isInitialized()) runCatching { chineseRecognizer.close() }
        if (languageIdentifierDelegate.isInitialized()) runCatching { languageIdentifier.close() }
        translatorCache.values.forEach { runCatching { it.close() } }
        translatorCache.clear()
    }

    // ═══════ TYPEFACE CACHE (perf + concurrency) ═══════

    private fun resolvedTypeface(prefs: SharedPreferences): Typeface {
        val path = prefs.getString(PREF_FONT_FILE, null)
        val style = if (prefs.getBoolean(PREF_FONT_BOLD, true)) Typeface.BOLD else Typeface.NORMAL
        val key = "${path}|$style|${prefs.getString(PREF_FONT_FAMILY, "sans-serif")}"
        if (key == cachedTypefaceKey && cachedTypeface != null) return cachedTypeface!!
        val tf = resolveArabicTypeface(prefs)
        cachedTypefaceKey = key; cachedTypeface = tf
        return tf
    }

    private fun arabicPaintFor(size: Float, color: Int = Color.rgb(20, 20, 22)): TextPaint {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = resolvedTypeface(prefs)
            textSize = size
            this.color = color
            style = Paint.Style.FILL
            strokeWidth = 0f
        }
    }

    // ═══════ CAPTURE ═══════

    fun startSingleCapture(startDelayMs: Long = 250L) {
        if (captureRunning || translationRunning) { toast("عملية قيد التنفيذ"); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { toast("يلزم Android 11+"); return }
        captureRunning = true; fullChapterMode = false
        capturedSegments.forEach { runCatching { it.delete() } }
        capturedSegments.clear()
        MangaFoxRuntime.status = "التقاط..."; removeFloatingBubble()
        mainHandler.postDelayed({
            requestScreenshot { bitmap, error ->
                if (bitmap == null) finishCaptureWithError(error ?: "تعذر الالتقاط")
                else {
                    capturedSegments.forEach { runCatching { it.delete() } }
                    capturedSegments.clear()
                    runCatching { capturedSegments.add(storeCaptureSegment(bitmap, 0)) }
                        .onSuccess { finishCaptureAndTranslate() }
                        .onFailure { finishCaptureWithError("تعذر حفظ: ${it.localizedMessage ?: "خطأ"}") }
                    bitmap.recycle()
                }
            }
        }, startDelayMs.coerceIn(200L, 5000L))
    }

    fun startFullChapterCapture(startDelayMs: Long = 450L) {
        if (captureRunning || translationRunning) { toast("عملية قيد التنفيذ"); return }
        if (pendingCaptureFiles().isNotEmpty()) { recoverInterruptedCapture(); toast("مقاطع سابقة موجودة؛ استعادة"); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { toast("يلزم Android 11+"); return }
        captureRunning = true; fullChapterMode = true
        captureCount = 0; unchangedFrameCount = 0; screenshotRetryCount = 0; gestureFailureCount = 0
        adaptiveSwipeFraction = 0.45f
        previousFrame?.recycle(); previousFrame = null
        capturedSegments.forEach { runCatching { it.delete() } }
        capturedSegments.clear()
        MangaFoxRuntime.status = "بدء التقاط الفصل..."; removeFloatingBubble()
        notifyCaptureProgress()
        mainHandler.postDelayed({ captureNextFrame() }, startDelayMs.coerceIn(250L, 5000L))
    }

    private fun captureNextFrame() {
        if (!captureRunning || !fullChapterMode) return
        if (captureCount >= maxCaptureFrames) {
            finishCaptureAndTranslate("الحد الأقصى ($maxCaptureFrames)؛ يمكنك بدء آخر"); return
        }
        requestScreenshot { bitmap, error ->
            if (!captureRunning) { bitmap?.recycle(); return@requestScreenshot }
            if (bitmap == null) {
                screenshotRetryCount++
                if (screenshotRetryCount < 3 && captureRunning) {
                    MangaFoxRuntime.status = "إعادة المحاولة $screenshotRetryCount/2"
                    notifyCaptureProgress()
                    mainHandler.postDelayed({ captureNextFrame() }, 900L * screenshotRetryCount)
                } else if (capturedSegments.isNotEmpty()) finishCaptureAndTranslate("توقف: ${error ?: "تعذر"}")
                else finishCaptureWithError(error ?: "تعذر بعد 3 محاولات")
                return@requestScreenshot
            }
            screenshotRetryCount = 0
            val dw = bitmap.width; val dh = bitmap.height
            val cf = try { createComparisonFrame(bitmap) }
            catch (e: Exception) {
                bitmap.recycle(); finishCaptureWithError("تعذر المقارنة: ${e.localizedMessage ?: "ذاكرة"}"); return@requestScreenshot
            }
            val prev = previousFrame
            if (prev != null && looksUnchanged(prev, cf)) {
                cf.recycle(); bitmap.recycle()
                unchangedFrameCount++
                MangaFoxRuntime.status = "لم يتغير ($unchangedFrameCount/3)"; notifyCaptureProgress()
                if (unchangedFrameCount >= 3) finishCaptureAndTranslate("انتهى المحتوى")
                else dispatchNextSwipe(dw, dh)
                return@requestScreenshot
            }
            unchangedFrameCount = 0
            val overlapSmall = if (prev == null) 0 else findVerticalOverlap(prev, cf)
            val overlapPx = if (overlapSmall <= 0) 0 else
                ((overlapSmall.toFloat() / cf.height) * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
            if (overlapSmall > 0 && cf.height > 0) {
                val actualFraction = 1f - (overlapSmall.toFloat() / cf.height)
                adaptiveSwipeFraction = (actualFraction * 0.92f).coerceIn(0.30f, 0.62f)
            }
            try { capturedSegments.add(storeCaptureSegment(bitmap, overlapPx)) }
            catch (e: Exception) {
                cf.recycle(); bitmap.recycle(); finishCaptureAndTranslate("تعذر حفظ: ${e.localizedMessage ?: "خطأ"}"); return@requestScreenshot
            }
            previousFrame?.recycle(); previousFrame = cf
            captureCount++
            MangaFoxRuntime.status = "التقاط: $captureCount · حد $maxCaptureFrames"
            notifyCaptureProgress()
            dispatchNextSwipe(dw, dh)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun dispatchNextSwipe(displayWidth: Int, displayHeight: Int) {
        if (!captureRunning || !fullChapterMode) return
        val w = displayWidth.toFloat(); val h = displayHeight.toFloat()
        val startY = 0.84f; val endY = startY - adaptiveSwipeFraction
        val path = Path().apply {
            moveTo(w * 0.52f, h * startY); lineTo(w * 0.52f, h * endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 720L)).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                gestureFailureCount = 0
                mainHandler.postDelayed({ captureNextFrame() }, 1450L)
            }
            override fun onCancelled(g: GestureDescription) {
                gestureFailureCount++
                if (gestureFailureCount >= 3) finishCaptureAndTranslate("تعذر التمرير")
                else {
                    MangaFoxRuntime.status = "أُلغي التمرير ($gestureFailureCount/3)"
                    mainHandler.postDelayed({ captureNextFrame() }, 900L)
                }
            }
        }
        val dispatched = dispatchGesture(gesture, cb, mainHandler)
        if (!dispatched) {
            gestureFailureCount++
            if (gestureFailureCount >= 3) finishCaptureAndTranslate("رفض النظام التمرير")
            else mainHandler.postDelayed({ captureNextFrame() }, 900L)
        }
    }

    fun stopCaptureByUser() {
        if (!captureRunning) { toast("لا التقاط"); return }
        if (capturedSegments.isEmpty()) finishCaptureWithError("أُوقف قبل التقاط أي جزء")
        else finishCaptureAndTranslate("أوقفه المستخدم؛ الترجمة")
    }

    private fun requestScreenshot(callback: (Bitmap?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { callback(null, "Android 11+"); return }
        val sinceLast = SystemClock.elapsedRealtime() - lastScreenshotMs
        val wait = (1_100L - sinceLast).coerceAtLeast(0L)
        if (wait > 0) mainHandler.postDelayed({ performScreenshot(callback) }, wait)
        else performScreenshot(callback)
    }

    private fun performScreenshot(callback: (Bitmap?, String?) -> Unit) {
        lastScreenshotMs = SystemClock.elapsedRealtime()
        val delivered = AtomicBoolean(false)
        val timeout = Runnable {
            if (delivered.compareAndSet(false, true)) callback(null, "انتهت المهلة")
        }
        mainHandler.postDelayed(timeout, 9000L)
        fun deliver(bitmap: Bitmap?, error: String?) {
            if (delivered.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout); callback(bitmap, error)
            } else bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                var buffer: HardwareBuffer? = null; var hw: Bitmap? = null
                var result: Bitmap? = null; var failure: String? = null
                try {
                    val b = screenshot.hardwareBuffer; buffer = b
                    hw = Bitmap.wrapHardwareBuffer(b, screenshot.colorSpace)
                    result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    if (result == null) failure = "لقطة فارغة"
                } catch (e: Exception) { failure = "تعذر: ${e.localizedMessage ?: "خطأ"}" }
                finally { runCatching { hw?.recycle() }; runCatching { buffer?.close() } }
                deliver(result, failure)
            }
            override fun onFailure(errorCode: Int) {
                val msg = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "نافذة آمنة"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "لا صلاحية"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "سريع جداً"
                    else -> "فشل: $errorCode"
                }
                deliver(null, msg)
            }
        }
        try { takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, cb) }
        catch (e: Exception) { deliver(null, "تعذر البدء: ${e.localizedMessage ?: "خطأ"}") }
    }

    private fun createComparisonFrame(source: Bitmap): Bitmap {
        val maxWidth = 720
        if (source.width <= maxWidth) return source.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("تعذر إنشاء النسخة")
        val scaledHeight = (source.height * (maxWidth.toFloat() / source.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, scaledHeight, true)
    }

    /** IntArray بدل 1M getPixel — من 15s إلى 400ms. */
    private fun findVerticalOverlap(previous: Bitmap, current: Bitmap): Int {
        if (previous.width != current.width || previous.height != current.height) return 0
        val w = previous.width; val h = previous.height
        val prevPx = IntArray(w * h).also { previous.getPixels(it, 0, w, 0, 0, w, h) }
        val currPx = IntArray(w * h).also { current.getPixels(it, 0, w, 0, 0, w, h) }
        val minOverlap = (h * 0.24f).toInt().coerceAtLeast(24)
        val maxOverlap = (h * 0.78f).toInt().coerceAtMost(h - 24)
        if (maxOverlap <= minOverlap) return 0
        val sampleRows = 80; val sampleCols = 48
        val xs = IntArray(sampleCols) { ((it + 0.5f) * w / sampleCols).toInt().coerceIn(0, w - 1) }
        val rowOffsets = IntArray(sampleRows)
        var best = 0; var bestScore = Double.MAX_VALUE; var secondScore = Double.MAX_VALUE
        for (overlap in minOverlap..maxOverlap) {
            for (r in 0 until sampleRows) rowOffsets[r] = ((r + 0.5f) * overlap / sampleRows).toInt()
            var total = 0L; var samples = 0
            for (r in 0 until sampleRows) {
                val prevBase = (h - overlap + rowOffsets[r]) * w
                val currBase = rowOffsets[r] * w
                for (x in xs) {
                    val a = prevPx[prevBase + x]; val b = currPx[currBase + x]
                    total += abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                             abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                             abs((a and 0xFF) - (b and 0xFF))
                    samples += 3
                }
            }
            val score = total.toDouble() / samples.coerceAtLeast(1)
            if (score < bestScore) {
                if (best > 0 && abs(overlap - best) >= 12) secondScore = bestScore
                best = overlap; bestScore = score
            } else if (abs(overlap - best) >= 12 && score < secondScore) secondScore = score
        }
        return when {
            bestScore > 18.0 -> 0
            secondScore - bestScore < 1.2 -> 0
            else -> best
        }
    }

    private fun cropContent(source: Bitmap, overlapTopPx: Int): Bitmap {
        val rawCut = overlapTopPx.coerceIn(0, source.height - 1)
        val cut = if (rawCut > 8) (rawCut - 8).coerceAtLeast(0) else rawCut
        val cropped = Bitmap.createBitmap(source, 0, cut, source.width, source.height - cut)
        val maxWidth = 1080
        if (cropped.width <= maxWidth) return cropped
        val targetHeight = (cropped.height * (maxWidth.toFloat() / cropped.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, targetHeight, true)
        cropped.recycle(); return scaled
    }

    private fun storeCaptureSegment(source: Bitmap, overlapTopPx: Int): File {
        val segment = cropContent(source, overlapTopPx)
        val file = File(filesDir, "mfox_capture_${System.currentTimeMillis()}_${captureCount}_${capturedSegments.size}.jpg")
        try {
            FileOutputStream(file).use { stream ->
                if (!segment.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IllegalStateException("تعذر ضغط")
            }
        } catch (e: Exception) { file.delete(); throw e }
        finally { if (!segment.isRecycled) segment.recycle() }
        return file
    }

    private fun looksUnchanged(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val a = Bitmap.createScaledBitmap(first, 28, 56, true)
        val b = Bitmap.createScaledBitmap(second, 28, 56, true)
        var diff = 0L
        for (y in 0 until 56) for (x in 0 until 28) {
            val ca = a.getPixel(x, y); val cb = b.getPixel(x, y)
            diff += abs(Color.red(ca) - Color.red(cb))
            diff += abs(Color.green(ca) - Color.green(cb))
            diff += abs(Color.blue(ca) - Color.blue(cb))
        }
        a.recycle(); b.recycle()
        return diff.toDouble() / (28.0 * 56.0 * 3.0) < 2.3
    }

    private fun finishCaptureAndTranslate(note: String = "") {
        if (!captureRunning) return
        captureRunning = false; fullChapterMode = false
        clearCaptureNotification()
        previousFrame?.recycle(); previousFrame = null
        restoreFloatingBubble()
        if (capturedSegments.isEmpty()) { finishCaptureWithError("لا مقاطع"); return }
        val workPages = ArrayList(capturedSegments); capturedSegments.clear()
        translateSavedSegments(workPages, note, if (workPages.size > 1) "الفصل العمودي" else "الصفحة")
    }

    private fun pendingCaptureFiles(): List<File> {
        return listOf(filesDir, cacheDir)
            .flatMap { dir -> dir.listFiles()?.asList().orEmpty() }
            .filter { it.isFile && it.name.startsWith("mfox_capture_") && it.length() > 0L }
            .distinctBy { it.absolutePath }.sortedBy { it.name }
    }

    private fun recoverInterruptedCapture() {
        if (captureRunning || translationRunning || instance !== this) return
        val pending = pendingCaptureFiles()
        if (pending.isEmpty()) return
        MangaFoxRuntime.status = "استعادة ${pending.size} مقطع..."
        translateSavedSegments(ArrayList(pending), "استعادة.", "الفصل المستعاد")
    }

    fun retryManualRegion(sourcePath: String, resultPath: String, selectedRect: Rect, completed: (Boolean, String) -> Unit) {
        if (captureRunning || translationRunning) { completed(false, "عملية قيد التنفيذ"); return }
        translationRunning = true
        MangaFoxRuntime.progressPercent = 0
        MangaFoxRuntime.progressDetail = "قراءة المنطقة"
        updateTranslationLens(0, "قراءة المنطقة")
        activeTranslationJob = worker.submit {
            var original: Bitmap? = null; var crop: Bitmap? = null
            var translatedCrop: Bitmap? = null; var output: Bitmap? = null
            try {
                original = BitmapFactory.decodeFile(sourcePath) ?: throw IllegalStateException("تعذر الأصل")
                val safe = Rect(selectedRect).apply {
                    left = left.coerceIn(0, original!!.width - 1); top = top.coerceIn(0, original!!.height - 1)
                    right = right.coerceIn(left + 1, original!!.width); bottom = bottom.coerceIn(top + 1, original!!.height)
                }
                crop = Bitmap.createBitmap(original!!, safe.left, safe.top, safe.width(), safe.height())
                translatedCrop = translateBitmap(crop!!) { fraction, detail ->
                    updateTranslationLens((fraction * 95f).toInt(), "منطقة · $detail")
                }
                if (lastOcrTranslationLines.get().isEmpty()) throw IllegalStateException("لا نص")
                val pageIndex = MangaFoxRuntime.resultPages.indexOfFirst { File(it).absolutePath == File(resultPath).absolutePath }
                if (pageIndex < 0) throw IllegalStateException("تعذر الربط")
                while (MangaFoxRuntime.translationLayerFiles.size < MangaFoxRuntime.resultPages.size) {
                    MangaFoxRuntime.translationLayerFiles = MangaFoxRuntime.translationLayerFiles + ""
                }
                val layerPath = MangaFoxRuntime.translationLayerFiles.getOrNull(pageIndex)
                val layerFile = layerPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
                    ?: File(filesDir, "mfox_layers_manual_${System.currentTimeMillis()}_$pageIndex.json")
                val originalBasePath = MangaFoxRuntime.sourcePages.getOrNull(pageIndex).orEmpty()
                val saved = if (layerFile.isFile) JSONArray(layerFile.readText(Charsets.UTF_8))
                    else JSONArray().put(JSONObject().put("_mfoxKeepFlattenedBase", true).put("_basePath", originalBasePath))
                val refreshed = ArrayList<JSONObject>()
                for (i in 0 until saved.length()) {
                    val record = saved.optJSONObject(i) ?: continue
                    val oldRect = Rect(record.optInt("left"), record.optInt("top"), record.optInt("right"), record.optInt("bottom"))
                    val replaced = lastOcrTranslationLines.get().any { line ->
                        val newRect = Rect(line.bounds).apply { offset(safe.left, safe.top) }
                        val overlap = Rect(oldRect)
                        if (!overlap.intersect(newRect)) false
                        else {
                            val inter = overlap.width().toFloat() * overlap.height()
                            val union = oldRect.width().toFloat() * oldRect.height() + newRect.width().toFloat() * newRect.height() - inter
                            val smaller = min(oldRect.width() * oldRect.height(), newRect.width() * newRect.height()).coerceAtLeast(1)
                            union > 0f && (inter / union > 0.28f || inter / smaller > 0.58f)
                        }
                    }
                    if (!replaced) refreshed.add(record)
                }
                lastOcrTranslationLines.get().forEach { line ->
                    val rect = Rect(line.bounds).apply { offset(safe.left, safe.top) }
                    val layer = JSONObject()
                        .put("left", rect.left).put("top", rect.top)
                        .put("right", rect.right).put("bottom", rect.bottom)
                        .put("source", line.sourceText).put("translated", line.translated)
                        .put("language", line.language).put("vertical", line.vertical)
                        .put("eraseOnly", line.eraseOnly).put("shape", line.shape)
                    line.containerBounds?.let { c ->
                        val m = Rect(c).apply { offset(safe.left, safe.top) }
                        layer.put("containerLeft", m.left).put("containerTop", m.top)
                            .put("containerRight", m.right).put("containerBottom", m.bottom)
                    }
                    refreshed.add(layer)
                }
                val outputLayers = JSONArray(); refreshed.forEach { outputLayers.put(it) }
                val temp = File(layerFile.parentFile, layerFile.name + ".tmp")
                temp.writeText(outputLayers.toString(), Charsets.UTF_8)
                if (!temp.renameTo(layerFile)) { temp.copyTo(layerFile, overwrite = true); temp.delete() }
                MangaFoxRuntime.translationLayerFiles = MangaFoxRuntime.translationLayerFiles.toMutableList().also { it[pageIndex] = layerFile.absolutePath }
                MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                output = BitmapFactory.decodeFile(resultPath) ?: original!!.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(output!!).drawBitmap(translatedCrop!!, safe.left.toFloat(), safe.top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
                val target = File(resultPath)
                val tmp = File(target.parentFile, target.nameWithoutExtension + "_region.tmp." + target.extension)
                writeRenderedBitmap(output!!, tmp)
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                mainHandler.post {
                    translationRunning = false
                    MangaFoxRuntime.progressPercent = 100
                    MangaFoxRuntime.progressDetail = "اكتملت إعادة القراءة"
                    updateTranslationLens(100, MangaFoxRuntime.progressDetail)
                    completed(true, "اكتملت إعادة القراءة")
                }
            } catch (e: Exception) {
                mainHandler.post { translationRunning = false; completed(false, "تعذر: ${e.localizedMessage ?: "خطأ"}") }
            } finally {
                listOf(original, crop, translatedCrop, output).forEach { b -> b?.let { if (!it.isRecycled) it.recycle() } }
            }
        }
    }

    fun rerenderLastResultsFromLayers(onComplete: (() -> Unit)? = null) {
        if (translationRunning || captureRunning) { toast("انتظر"); return }
        val sources = MangaFoxRuntime.sourcePages.map { File(it) }
        val targets = MangaFoxRuntime.resultPages.map { File(it) }
        val layers = MangaFoxRuntime.translationLayerFiles.map { File(it) }
        if (sources.isEmpty() || sources.size != targets.size || sources.any { !it.isFile } || targets.any { !it.isFile }) {
            toast("الصور الأصلية أو النتائج ناقصة"); return
        }
        val normalized = ArrayList<String>(sources.size)
        sources.indices.forEach { idx ->
            val existing: File? = layers.getOrNull(idx)
            val usable = existing?.takeIf { it.isFile }
                ?: File(filesDir, "mfox_layers_recovered_${System.currentTimeMillis()}_$idx.json").apply {
                    val marker = JSONArray().put(JSONObject()
                        .put("_mfoxKeepFlattenedBase", true)
                        .put("_basePath", sources[idx].absolutePath))
                    writeText(marker.toString(), Charsets.UTF_8)
                }
            normalized.add(usable.absolutePath)
        }
        MangaFoxRuntime.translationLayerFiles = normalized
        MangaFoxRuntime.persist(this)
        translationRunning = true
        MangaFoxRuntime.progressPercent = 0
        MangaFoxRuntime.progressDetail = "إعادة تنسيق بدون OCR"
        updateTranslationLens(0, MangaFoxRuntime.progressDetail)
        activeTranslationJob = worker.submit {
            try {
                sources.indices.forEach { idx ->
                    var original: Bitmap? = null; var rendered: Bitmap? = null
                    try {
                        val json = JSONArray(File(normalized[idx]).readText(Charsets.UTF_8))
                        val explicitBase = json.optJSONObject(0)?.optString("_basePath")
                            ?.takeIf { it.isNotBlank() && File(it).isFile }
                        val basePath = explicitBase ?: sources[idx].absolutePath
                        original = BitmapFactory.decodeFile(basePath)
                            ?: throw IllegalStateException("تعذر ${idx + 1}")
                        val records = ArrayList<TranslationLine>(json.length())
                        for (ridx in 0 until json.length()) {
                            val item = json.optJSONObject(ridx) ?: continue
                            val rect = Rect(item.optInt("left"), item.optInt("top"), item.optInt("right"), item.optInt("bottom"))
                            if (rect.width() > 0 && rect.height() > 0) {
                                records.add(TranslationLine(
                                    rect, item.optString("translated"),
                                    item.optString("source"), item.optString("language", "und"),
                                    item.optBoolean("vertical", false),
                                    item.optBoolean("eraseOnly", false),
                                    item.optString("shape", "round"),
                                    if (item.has("containerLeft")) Rect(
                                        item.optInt("containerLeft"), item.optInt("containerTop"),
                                        item.optInt("containerRight"), item.optInt("containerBottom")
                                    ).takeIf { it.width() > 8 && it.height() > 8 } else null))
                            }
                        }
                        rendered = drawTranslatedLines(original!!, records)
                        val target = targets[idx]
                        val tmp = File(target.parentFile, target.nameWithoutExtension + "_layers.tmp." + target.extension)
                        writeRenderedBitmap(rendered!!, tmp)
                        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                        val percent = (((idx + 1).toFloat() / sources.size) * 100f).toInt()
                        updateTranslationLens(percent.coerceAtMost(99), "إعادة ${idx + 1}/${sources.size}")
                    } finally {
                        original?.let { if (!it.isRecycled) it.recycle() }
                        rendered?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
                mainHandler.post {
                    translationRunning = false
                    MangaFoxRuntime.progressPercent = 100
                    MangaFoxRuntime.progressDetail = "تم التطبيق دون OCR"
                    MangaFoxRuntime.status = MangaFoxRuntime.progressDetail
                    MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                    updateTranslationLens(100, MangaFoxRuntime.progressDetail)
                    mainHandler.postDelayed({ if (!translationRunning) resetLensProgress() }, 3500L)
                    notifyResultsReady(); toast("تم تحديث الخط دون OCR")
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    translationRunning = false
                    MangaFoxRuntime.lastError = "تعذر: ${e.localizedMessage ?: "خطأ"}"
                    MangaFoxRuntime.progressDetail = "تعذر التنسيق"
                    updateTranslationLens(-1, MangaFoxRuntime.progressDetail)
                    toast(MangaFoxRuntime.lastError)
                }
            }
        }
    }

    fun retranslateLastResults() {
        if (translationRunning || captureRunning) { toast("انتظر"); return }
        val originals = MangaFoxRuntime.sourcePages.map { File(it) }.filter { it.isFile && it.length() > 0L }
        if (originals.isEmpty()) { toast("الصور الأصلية مفقودة"); return }
        translateSavedSegments(ArrayList(originals), "إعادة تطبيق.", MangaFoxRuntime.resultTitle, preserveInputs = true)
    }

    private fun combineVerticalBitmaps(parts: List<Bitmap>): Bitmap {
        require(parts.isNotEmpty()) { "لا صور" }
        if (parts.size == 1) return parts.first()
        val naturalWidth = parts.maxOf { it.width }.coerceAtLeast(1)
        val naturalHeights = parts.map { (it.height * (naturalWidth.toFloat() / it.width)).roundToInt().coerceAtLeast(1) }
        val naturalTotalHeight = naturalHeights.sum().coerceAtLeast(1)
        val heapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt().coerceAtLeast(256)
        val profile = MangaFoxPerformanceAdvisor.forDevice(heapMb, Runtime.getRuntime().availableProcessors())
        val maxStripHeight = profile.maxImageSide * 2
        val globalScale = minOf(1f, profile.maxImageSide.toFloat() / naturalWidth.toFloat(),
            maxStripHeight.toFloat() / naturalTotalHeight.toFloat())
        val width = (naturalWidth * globalScale).roundToInt().coerceAtLeast(1)
        val outputHeights = parts.map { (it.height * (width.toFloat() / it.width)).roundToInt().coerceAtLeast(1) }
        val height = outputHeights.sum().coerceAtLeast(1)
        val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var top = 0
        parts.forEachIndexed { idx, bitmap ->
            val bottom = top + outputHeights[idx]
            canvas.drawBitmap(bitmap, null, Rect(0, top, width, bottom), paint); top = bottom
        }
        return combined
    }

    private fun translateSavedSegments(workPages: ArrayList<File>, note: String, title: String, preserveInputs: Boolean = false) {
        if (workPages.isEmpty() || translationRunning) return
        translationRunning = true
        MangaFoxRuntime.progressPercent = 0
        MangaFoxRuntime.progressDetail = "تهيئة"
        MangaFoxRuntime.detectedBlocks = 0; MangaFoxRuntime.translatedBlocks = 0
        MangaFoxRuntime.failedBlocks = 0; MangaFoxRuntime.skippedBlocks = 0
        MangaFoxRuntime.failedBlockSamples = emptyList()
        MangaFoxRuntime.qualitySummary = "تحليل..."
        MangaFoxRuntime.status = "استخراج وترجمة..."
        updateTranslationLens(0, "تهيئة")
        if (note.isNotBlank()) MangaFoxRuntime.lastError = note
        activeTranslationJob = worker.submit {
            val translatedFiles = ArrayList<String>()
            val newSources = ArrayList<String>()
            val newLayers = ArrayList<String>()
            try {
                workPages.forEachIndexed { index, pageFile ->
                    MangaFoxRuntime.status = "تحليل ${index + 1}/${workPages.size}..."
                    updateTranslationLens((index * 100 / workPages.size).coerceIn(0, 99), "OCR ${index + 1}/${workPages.size}")
                    var page: Bitmap? = null; var translated: Bitmap? = null
                    try {
                        page = BitmapFactory.decodeFile(pageFile.absolutePath)
                            ?: throw IllegalStateException("تعذر ${pageFile.name}")
                        val mergedPage = page!!
                        if (!preserveInputs) {
                            val sc = File(filesDir, "mfox_source_${System.currentTimeMillis()}_$index.jpg")
                            FileOutputStream(sc).use { stream ->
                                if (!mergedPage.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IllegalStateException("تعذر حفظ")
                            }
                            newSources.add(sc.absolutePath)
                        }
                        val outputBitmap = translateBitmap(mergedPage) { fraction, detail ->
                            val agg = (((index + fraction.coerceIn(0f, 1f)) / workPages.size) * 100f).toInt().coerceIn(0, 99)
                            updateTranslationLens(agg, "${index + 1}/${workPages.size} · $detail")
                        }
                        translated = outputBitmap
                        val layerFile = File(filesDir, "mfox_layers_${System.currentTimeMillis()}_$index.json")
                        val layerArray = JSONArray()
                        val originalBase = if (preserveInputs) pageFile.absolutePath else newSources.getOrNull(index).orEmpty()
                        layerArray.put(JSONObject().put("_mfoxKeepFlattenedBase", true).put("_basePath", originalBase))
                        lastOcrTranslationLines.get().forEach { line ->
                            val entry = JSONObject()
                                .put("left", line.bounds.left).put("top", line.bounds.top)
                                .put("right", line.bounds.right).put("bottom", line.bounds.bottom)
                                .put("source", line.sourceText).put("translated", line.translated)
                                .put("language", line.language).put("vertical", line.vertical)
                                .put("eraseOnly", line.eraseOnly).put("shape", line.shape)
                            line.containerBounds?.let { c ->
                                entry.put("containerLeft", c.left).put("containerTop", c.top)
                                    .put("containerRight", c.right).put("containerBottom", c.bottom)
                            }
                            layerArray.put(entry)
                        }
                        FileOutputStream(layerFile).use { stream -> stream.write(layerArray.toString().toByteArray(Charsets.UTF_8)) }
                        newLayers.add(layerFile.absolutePath)
                        val outputFile = File(filesDir, "mfox_result_${System.currentTimeMillis()}_$index.png")
                        writeRenderedBitmap(outputBitmap, outputFile)
                        translatedFiles.add(outputFile.absolutePath)
                        updateTranslationLens((((index + 1).toFloat() / workPages.size) * 100f).toInt().coerceIn(0, 99),
                            "اكتملت ${index + 1}")
                    } finally {
                        page?.let { if (!it.isRecycled) it.recycle() }
                        translated?.let { if (!it.isRecycled) it.recycle() }
                        if (!preserveInputs) runCatching { pageFile.delete() }
                    }
                }
                val oldFiles = MangaFoxRuntime.resultPages
                val oldLayers = MangaFoxRuntime.translationLayerFiles
                MangaFoxRuntime.resultPages = translatedFiles
                MangaFoxRuntime.translationLayerFiles = newLayers.toList()
                oldFiles.forEach { runCatching { File(it).delete() } }
                oldLayers.forEach { runCatching { File(it).delete() } }
                if (!preserveInputs) {
                    val oldSources = MangaFoxRuntime.sourcePages
                    MangaFoxRuntime.sourcePages = newSources.toList()
                    oldSources.forEach { runCatching { File(it).delete() } }
                }
                MangaFoxRuntime.resultTitle = title
                MangaFoxRuntime.qualitySummary =
                    "اكتُشف ${MangaFoxRuntime.detectedBlocks} · تُرجم ${MangaFoxRuntime.translatedBlocks} · فشل ${MangaFoxRuntime.failedBlocks} · تُرك ${MangaFoxRuntime.skippedBlocks}"
                MangaFoxRuntime.status = "اكتملت: ${translatedFiles.size} صورة · ${MangaFoxRuntime.qualitySummary}"
                MangaFoxRuntime.lastError = note
                MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                MangaFoxRuntime.progressPercent = 100
                MangaFoxRuntime.progressDetail = "اكتملت"
                mainHandler.post {
                    translationRunning = false
                    updateTranslationLens(100, "اكتملت")
                    mainHandler.postDelayed({ if (!translationRunning) resetLensProgress() }, 5000L)
                    val n = notifyResultsReady()
                    toast(if (n) "اكتملت العربية. اضغط الإشعار." else "اكتملت. افتح التطبيق → عرض النتيجة.")
                }
            } catch (e: Exception) {
                if (!preserveInputs) workPages.forEach { runCatching { it.delete() } }
                newSources.forEach { runCatching { File(it).delete() } }
                newLayers.forEach { runCatching { File(it).delete() } }
                translatedFiles.forEach { runCatching { File(it).delete() } }
                MangaFoxRuntime.lastError = "خطأ في الترجمة: ${e.localizedMessage ?: "خطأ"}"
                MangaFoxRuntime.status = "تعذرت"
                MangaFoxRuntime.progressDetail = "تعذرت"
                mainHandler.post {
                    translationRunning = false
                    updateTranslationLens(-1, "تعذرت")
                    mainHandler.postDelayed({ resetLensProgress() }, 4000L)
                    toast(MangaFoxRuntime.lastError)
                }
            }
        }
    }

    private fun finishCaptureWithError(message: String) {
        captureRunning = false; fullChapterMode = false; clearCaptureNotification()
        previousFrame?.recycle(); previousFrame = null
        capturedSegments.forEach { runCatching { it.delete() } }
        capturedSegments.clear()
        MangaFoxRuntime.status = "تعذر الالتقاط"
        MangaFoxRuntime.lastError = message
        restoreFloatingBubble(); toast(message)
    }

    // ═══════ DATA ═══════

    private data class TranslationLine(
        val bounds: Rect, val translated: String, val sourceText: String = "",
        val language: String = "und", val vertical: Boolean = false,
        val eraseOnly: Boolean = false, val shape: String = "round",
        val containerBounds: Rect? = null)
    private data class OcrCandidate(
        val bounds: Rect?, val text: String, val engineLanguage: String,
        val containerBounds: Rect? = null, val bubbleId: Int? = null)
    private data class BlockForTranslation(
        val bounds: Rect, val text: String, val language: String,
        val containerBounds: Rect? = null)

    // ═══════ HELPERS ═══════

    private fun countChars(text: String, predicate: (Char) -> Boolean): Int = text.count(predicate)
    private fun languageForCode(code: String): String = when (code) {
        "ja" -> TranslateLanguage.JAPANESE; "ko" -> TranslateLanguage.KOREAN
        "zh" -> TranslateLanguage.CHINESE; else -> TranslateLanguage.ENGLISH
    }
    private fun isArabicChar(ch: Char): Boolean =
        ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' ||
            ch in '\u08A0'..'\u08FF' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'
    private fun hasArabicLetters(v: String): Boolean = v.any { isArabicChar(it) && it.isLetter() }
    private fun hasForeignLetters(v: String): Boolean = v.any { it.isLetter() && !isArabicChar(it) }
    private fun foreignLetterCount(v: String): Int = v.count { it.isLetter() && !isArabicChar(it) }

    private fun hasUntranslatedLatinRuns(v: String, minRun: Int = 4): Boolean {
        var run = 0
        for (ch in v) {
            if (ch in 'A'..'Z' || ch in 'a'..'z') { run++; if (run >= minRun) return true }
            else run = 0
        }
        return false
    }

    private fun isUsefulArabicTranslation(source: String, translated: String): Boolean {
        if (translated.isBlank()) return false
        if (!hasForeignLetters(source)) return hasArabicLetters(translated)
        if (!hasArabicLetters(translated)) return false
        val srcF = foreignLetterCount(source); val outF = foreignLetterCount(translated)
        if (outF <= 3 && srcF >= outF) return true
        return srcF == 0 || outF < max(2, (srcF * 0.68f).roundToInt())
    }

    private fun languageForBlock(text: String, engineHint: String, forced: String): String {
        val letters = text.filter { it.isLetter() }
        val arabic = countChars(letters) { isArabicChar(it) }
        val hangul = countChars(text) { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }
        val kana = countChars(text) { it in '\u3040'..'\u30FF' }
        val cjk = countChars(text) { it in '\u4E00'..'\u9FFF' }
        val latin = countChars(text) { it in 'A'..'Z' || it in 'a'..'z' }
        if (arabic > 0 && hangul + kana + cjk + latin == 0) return "ar"
        if (hangul > 0) return "ko"
        if (kana > 0) return "ja"
        if (cjk > 0) return if (engineHint == "ja" || engineHint == "zh") engineHint else "zh"
        if (latin > 0) return if (forced in setOf("ja", "ko", "zh") && engineHint == forced) forced else "en"
        if (arabic >= 2 && !hasForeignLetters(text)) return "ar"
        if (forced in setOf("ja", "en", "ko", "zh")) return forced
        if (engineHint in setOf("ja", "en", "ko", "zh")) return engineHint
        val identified = runCatching { Tasks.await(languageIdentifier.identifyLanguage(text.take(500))) }.getOrDefault("und")
        val normalized = identified.lowercase().substringBefore('-')
        return if (normalized in setOf("ja", "en", "ko", "zh")) normalized else "en"
    }

    private fun translateMixedTextPreservingArabic(source: String, language: String): String {
        val output = StringBuilder(); val segment = StringBuilder()
        var segmentIsArabic: Boolean? = null
        fun flush() {
            if (segment.isEmpty()) return
            val raw = segment.toString()
            if (segmentIsArabic == true || raw.none { it.isLetterOrDigit() }) output.append(raw)
            else {
                val trimmed = raw.trim()
                val leading = raw.takeWhile { it.isWhitespace() }
                val trailing = raw.takeLastWhile { it.isWhitespace() }
                val translated = if (trimmed.any { it.isLetterOrDigit() }) {
                    runCatching { translateWithLanguage(trimmed, language) }.getOrDefault(trimmed)
                } else trimmed
                output.append(leading).append(translated).append(trailing)
            }
            segment.clear(); segmentIsArabic = null
        }
        source.forEach { ch ->
            val script = when {
                ch.isLetter() && isArabicChar(ch) -> true
                ch.isLetterOrDigit() -> false
                else -> null
            }
            if (script != null && segmentIsArabic != null && script != segmentIsArabic) flush()
            if (script != null && segmentIsArabic == null) segmentIsArabic = script
            segment.append(ch)
        }
        flush(); return output.toString()
    }

    private fun correctLikelyOcrText(value: String, language: String): String {
        var c = value.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        if (language == "en") {
            c = c.replace(Regex("(?i)\\bl\\s*['']m\\b"), "I'm")
            c = c.replace(Regex("(?<=[A-Za-z])0(?=[A-Za-z])"), "O")
            c = c.replace(Regex("(?<=[A-Za-z])1(?=[A-Za-z])"), "I")
        }
        return c
    }

    private fun cleanArabicTranslation(value: String): String {
        var c = value.replace('\u0640', ' ')
            .replace(Regex("\\s+"), " ")
            .replace(Regex("([،,؟.!])\\1+"), "$1")
            .replace(Regex("\\s+([،.!؟])"), "$1")
            .replace(Regex("([,])\\s*"), "، ")
            .replace('?', '؟').trim()
        c = c.replace(Regex("(?iu)\\b([\\p{L}]{2,})(?:\\s+\\1\\b)+"), "$1")
        return c.trim()
    }

    private fun updateQualityCounts(detected: Int, translated: Int, failed: Int, skipped: Int, failedSamples: List<String>) {
        MangaFoxRuntime.detectedBlocks += detected
        MangaFoxRuntime.translatedBlocks += translated
        MangaFoxRuntime.failedBlocks += failed
        MangaFoxRuntime.skippedBlocks += skipped
        if (failedSamples.isNotEmpty()) MangaFoxRuntime.failedBlockSamples = (MangaFoxRuntime.failedBlockSamples + failedSamples).take(30)
        MangaFoxRuntime.qualitySummary =
            "اكتُشف ${MangaFoxRuntime.detectedBlocks} · تُرجم ${MangaFoxRuntime.translatedBlocks} · فشل ${MangaFoxRuntime.failedBlocks} · تُرك ${MangaFoxRuntime.skippedBlocks}"
    }

    private fun learnedTranslationFor(source: String): String? {
        val key = MangaFoxGlossaryCache.normalizeKey(source)
        return MangaFoxGlossaryCache.lookup(this, key)
    }

    private fun dictionarySfxTranslation(source: String): String? {
        val normalized = source.filterNot { it.isWhitespace() || it in "。、・!！?？…,." }
        val dict = linkedMapOf(
            "ゴゴゴ" to "ررررر…", "ドキドキ" to "دقات قلب!", "キラキラ" to "بريق!",
            "ガシャン" to "تحطّم!", "ガーン" to "يا للصدمة!", "ドン" to "دُووم!",
            "バン" to "بانغ!", "ザッ" to "خطوة سريعة!")
        return dict.entries.firstOrNull { normalized.contains(it.key) }?.value
    }

    private fun isLikelyAdPage(candidates: List<OcrCandidate>): Boolean {
        val content = candidates.joinToString(" ") { it.text.lowercase() }
        val strong = listOf("advertisement", "sponsored", "download our app", "install our app", "buy now",
            "広告", "アプリをダウンロード", "광고", "앱 다운로드", "广告")
        val soft = listOf("next chapter", "read more", "follow us", "support the author", "join our discord",
            "subscribe", "次の話", "次の章", "다음 화", "다음 회", "下一章", "继续阅读")
        return strong.any { content.contains(it) } || (soft.count { content.contains(it) } >= 2 && candidates.size <= 12)
    }

    private fun translateWithLanguage(sourceText: String, languageCode: String): String {
        val t = translatorFor(languageCode)
        Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        return Tasks.await(t.translate(sourceText))
    }

    private fun retryLanguageCandidates(sourceText: String, current: String): List<String> {
        val detected = runCatching { Tasks.await(languageIdentifier.identifyLanguage(sourceText.take(500))) }
            .getOrDefault("und").lowercase().substringBefore('-')
        val alt = when (current) { "ja" -> "zh"; "zh" -> "ja"; "ko" -> "en"; else -> "ja" }
        return listOf(detected, alt, "en", "ja", "ko", "zh")
            .filter { it in setOf("en", "ja", "ko", "zh") && it != current }.distinct()
    }

    private fun translatorFor(code: String): Translator = translatorCache.getOrPut(code) {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(languageForCode(code))
            .setTargetLanguage(TranslateLanguage.ARABIC).build())
    }

    // ═══════ OCR ═══════

    private fun boxForBlock(block: Text.TextBlock): Rect? {
        block.boundingBox?.let { return Rect(it) }
        val boxes = block.lines.mapNotNull { it.boundingBox }
        if (boxes.isEmpty()) return null
        return Rect(boxes.first()).apply { boxes.drop(1).forEach { union(it) } }
    }

    private fun candidateFromBlock(block: Text.TextBlock, engine: String, scale: Float = 1f): OcrCandidate {
        val bounds = boxForBlock(block)?.let { b ->
            if (scale == 1f) Rect(b) else Rect(
                (b.left / scale).roundToInt(), (b.top / scale).roundToInt(),
                (b.right / scale).roundToInt(), (b.bottom / scale).roundToInt())
        }
        val lines = block.lines.filter { it.text.isNotBlank() }
        val phrase = if (bounds != null && bounds.height() > bounds.width() * 2.2f && lines.isNotEmpty())
            lines.sortedByDescending { it.boundingBox?.centerX() ?: 0 }.joinToString("") { it.text.trim() }
        else lines.joinToString(" ") { it.text.trim() }.ifBlank { block.text.trim() }
        return OcrCandidate(bounds, phrase.trim(), engine)
    }

    private fun collectCandidates(result: Text?, engine: String, scale: Float = 1f): List<OcrCandidate> {
        fun mapB(raw: Rect?): Rect? {
            if (raw == null) return null
            return if (scale == 1f) Rect(raw) else Rect(
                (raw.left / scale).roundToInt(), (raw.top / scale).roundToInt(),
                (raw.right / scale).roundToInt(), (raw.bottom / scale).roundToInt())
        }
        return result?.textBlocks.orEmpty().flatMap { block ->
            val bb = boxForBlock(block)
            val hasVerticalCjk = bb != null && bb.height() > bb.width() * 2.2f &&
                countChars(block.text) { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' } > 0
            if (hasVerticalCjk) listOf(candidateFromBlock(block, engine, scale))
            else {
                val lines = block.lines.mapNotNull { line ->
                    val rb = line.boundingBox ?: line.elements.mapNotNull { it.boundingBox }
                        .reduceOrNull { a, r -> Rect(a).apply { union(r) } }
                    val text = line.text.trim()
                    if (text.isBlank()) null else OcrCandidate(mapB(rb), text, engine)
                }
                if (lines.isNotEmpty()) lines else listOf(candidateFromBlock(block, engine, scale))
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
        val inter = max(0, r - l) * max(0, bo - t)
        if (inter <= 0) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0) 0f else inter.toFloat() / union.toFloat()
    }

    private fun candidateQuality(c: OcrCandidate): Int {
        val text = c.text
        val letters = text.count { it.isLetterOrDigit() }
        if (letters < 2) return -1000
        val arabic = text.count { isArabicChar(it) }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val cjk = text.count { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' || it in '\uAC00'..'\uD7AF' }
        val repl = text.count { it == '\uFFFD' || it == '?' }
        val minor = if (arabic > 0 && latin + cjk > 0) min(arabic, latin + cjk) else Int.MAX_VALUE
        val mixedPenalty = when {
            arabic == 0 || latin + cjk == 0 -> 0
            minor <= 2 -> 40
            else -> 0
        }
        val areaPenalty = c.bounds?.let { (it.width().coerceAtLeast(1) * it.height().coerceAtLeast(1)) / 12000 } ?: 0
        return letters * 5 + arabic * 2 - repl * 12 - mixedPenalty - areaPenalty.coerceAtMost(40)
    }

    private fun mergeOcrCandidates(input: List<OcrCandidate>): List<OcrCandidate> {
        val merged = ArrayList<OcrCandidate>()
        fun overlaps(a: Rect, b: Rect): Boolean {
            val l = max(a.left, b.left); val t = max(a.top, b.top)
            val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
            val inter = max(0, r - l) * max(0, bo - t)
            if (inter <= 0) return false
            val smaller = min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
            return intersectionOverUnion(a, b) >= 0.45f || inter.toFloat() / smaller >= 0.80f
        }
        input.asSequence()
            .filter { it.text.isNotBlank() && it.bounds?.let { b -> b.width() > 2 && b.height() > 2 } == true }
            .sortedByDescending { candidateQuality(it) }
            .forEach { c ->
                val box = c.bounds ?: return@forEach
                val conflicts = merged.filter { old -> old.bounds?.let { overlaps(it, box) } == true }
                if (conflicts.isEmpty()) merged.add(c)
                else {
                    val group = conflicts + c
                    val winner = group.maxByOrNull { candidateQuality(it) } ?: c
                    val bm = group.firstOrNull { it.containerBounds != null }
                    merged.removeAll { old -> old.bounds?.let { overlaps(it, box) } == true }
                    merged.add(winner.copy(
                        containerBounds = bm?.containerBounds?.let(::Rect),
                        bubbleId = bm?.bubbleId))
                }
            }
        val non = ArrayList<OcrCandidate>()
        merged.sortedByDescending { candidateQuality(it) }.forEach { c ->
            val box = c.bounds ?: return@forEach
            if (non.none { it.bounds?.let { a -> overlaps(a, box) } == true }) non.add(c)
        }
        return non.sortedWith(
            compareBy<OcrCandidate> { it.bounds?.top ?: Int.MAX_VALUE }
                .thenByDescending { it.bounds?.right ?: Int.MIN_VALUE })
    }

    private fun createOcrVariant(source: Bitmap, inverted: Boolean): Pair<Bitmap, Float> {
        val largest = max(source.width, source.height).coerceAtLeast(1)
        val scale = min(if (largest < 1800) 1.65f else 1.25f, 3600f / largest.toFloat()).coerceAtLeast(1f)
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val transform = Matrix().apply { setScale(w.toFloat() / source.width, h.toFloat() / source.height) }
        val scaled = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
        val gray = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val contrast = if (inverted) -1.30f else 1.30f
        val offset = if (inverted) 287f else -32f
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset, 0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset, 0f, 0f, 0f, 1f, 0f))
        matrix.postConcat(ColorMatrix().apply { setSaturation(0f) })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(gray).drawBitmap(scaled, 0f, 0f, paint)
        if (!scaled.isRecycled) scaled.recycle()
        val hist = IntArray(256)
        val bandHeight = min(96, h).coerceAtLeast(1)
        val band = IntArray(w * bandHeight)
        for (top in 0 until h step bandHeight) {
            val rows = min(bandHeight, h - top)
            gray.getPixels(band, 0, w, 0, top, w, rows)
            for (i in 0 until w * rows) hist[Color.red(band[i]).coerceIn(0, 255)]++
        }
        val total = w * h
        val lowT = (total * 0.01f).toInt(); val highT = (total * 0.99f).toInt()
        var cum = 0; var low = 0; var high = 255
        for (v in 0..255) { cum += hist[v]; if (cum >= lowT) { low = v; break } }
        cum = 0
        for (v in 0..255) { cum += hist[v]; if (cum >= highT) { high = v; break } }
        if (high > low + 2) {
            val range = (high - low).toFloat()
            for (top in 0 until h step bandHeight) {
                val rows = min(bandHeight, h - top)
                gray.getPixels(band, 0, w, 0, top, w, rows)
                for (i in 0 until w * rows) {
                    val value = ((Color.red(band[i]) - low) * 255f / range).roundToInt().coerceIn(0, 255)
                    band[i] = Color.rgb(value, value, value)
                }
                gray.setPixels(band, 0, w, 0, top, w, rows)
            }
        }
        return gray to scale
    }

    private fun createBinarizedOcrVariant(source: Bitmap): Pair<Bitmap, Float> {
        val largest = max(source.width, source.height).coerceAtLeast(1)
        val scale = min(1.45f, 2400f / largest.toFloat()).coerceAtLeast(0.01f)
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val transform = Matrix().apply { setScale(w.toFloat() / source.width, h.toFloat() / source.height) }
        val scaled = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
        val gray = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(gray).drawBitmap(scaled, 0f, 0f, grayPaint)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        val hist = IntArray(256)
        val bandHeight = min(64, h).coerceAtLeast(1)
        val band = IntArray(w * bandHeight)
        for (top in 0 until h step bandHeight) {
            val rows = min(bandHeight, h - top)
            gray.getPixels(band, 0, w, 0, top, w, rows)
            for (i in 0 until w * rows) hist[Color.red(band[i]).coerceIn(0, 255)]++
        }
        val total = w.toLong() * h.toLong()
        var sum = 0.0; for (v in 0..255) sum += v.toDouble() * hist[v]
        var sumB = 0.0; var wB = 0L
        var bestVar = -1.0; var threshold = 145
        for (v in 0..255) {
            wB += hist[v].toLong(); if (wB == 0L) continue
            val wF = total - wB; if (wF == 0L) break
            sumB += v.toDouble() * hist[v]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val delta = mB - mF
            val variance = wB.toDouble() * wF.toDouble() * delta * delta
            if (variance > bestVar) { bestVar = variance; threshold = v }
        }
        val cutoff = (threshold - 8).coerceIn(0, 255)
        for (top in 0 until h step bandHeight) {
            val rows = min(bandHeight, h - top)
            gray.getPixels(band, 0, w, 0, top, w, rows)
            for (i in 0 until w * rows) band[i] = if (Color.red(band[i]) <= cutoff) Color.BLACK else Color.WHITE
            gray.setPixels(band, 0, w, 0, top, w, rows)
        }
        return gray to scale
    }

    private fun detectBrightBubbleRegions(source: Bitmap): List<Rect> {
        val largest = max(source.width, source.height).coerceAtLeast(1)
        val scale = min(1f, min(1000f / largest.toFloat(), sqrt(900_000f / (source.width.toFloat() * source.height.toFloat())))).coerceAtLeast(0.01f)
        val sw = (source.width * scale).roundToInt().coerceAtLeast(1)
        val sh = (source.height * scale).roundToInt().coerceAtLeast(1)
        val sample = Bitmap.createScaledBitmap(source, sw, sh, true)
        try {
            val total = sw * sh
            val visited = BooleanArray(total); val queue = IntArray(total)
            val pixels = IntArray(total)
            sample.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val regions = ArrayList<Pair<Int, Rect>>()
            val minArea = max(24, (total * 0.00045f).toInt())
            val maxArea = (total * 0.22f).toInt()
            fun isBright(index: Int): Boolean {
                val c = pixels[index]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val lum = (r * 30 + g * 59 + b * 11) / 100
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                return lum >= 198 && (chroma <= 82 || lum >= 232)
            }
            for (start in 0 until total) {
                if (visited[start]) continue
                visited[start] = true
                if (!isBright(start)) continue
                var head = 0; var tail = 0; queue[tail++] = start
                var minX = sw; var minY = sh; var maxX = -1; var maxY = -1
                var area = 0
                while (head < tail) {
                    val idx = queue[head++]
                    val x = idx % sw; val y = idx / sw
                    area++
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    val l = if (x > 0) idx - 1 else -1
                    val r = if (x < sw - 1) idx + 1 else -1
                    val u = if (y > 0) idx - sw else -1
                    val d = if (y < sh - 1) idx + sw else -1
                    if (l >= 0 && !visited[l]) { visited[l] = true; if (isBright(l)) queue[tail++] = l }
                    if (r >= 0 && !visited[r]) { visited[r] = true; if (isBright(r)) queue[tail++] = r }
                    if (u >= 0 && !visited[u]) { visited[u] = true; if (isBright(u)) queue[tail++] = u }
                    if (d >= 0 && !visited[d]) { visited[d] = true; if (isBright(d)) queue[tail++] = d }
                }
                if (area < minArea || area > maxArea) continue
                val w = maxX - minX + 1; val hh = maxY - minY + 1
                if (w < 14 || hh < 10) continue
                val ratio = w.toFloat() / hh.toFloat()
                if (ratio !in 0.18f..6.0f) continue
                val padX = max(3, w / 14); val padY = max(3, hh / 14)
                val l = (minX - padX).coerceAtLeast(0); val t = (minY - padY).coerceAtLeast(0)
                val r = (maxX + 1 + padX).coerceAtMost(sw); val b = (maxY + 1 + padY).coerceAtMost(sh)
                val mapped = Rect(
                    (l / scale).roundToInt().coerceIn(0, source.width - 1),
                    (t / scale).roundToInt().coerceIn(0, source.height - 1),
                    (r / scale).roundToInt().coerceIn(1, source.width),
                    (b / scale).roundToInt().coerceIn(1, source.height))
                if (mapped.width() > 12 && mapped.height() > 12) regions.add(area to mapped)
            }
            val selected = ArrayList<Rect>()
            regions.sortedByDescending { it.first }.forEach { (_, rect) ->
                val overlapsExisting = selected.any { prev ->
                    val l = max(prev.left, rect.left); val t = max(prev.top, rect.top)
                    val r = min(prev.right, rect.right); val b = min(prev.bottom, rect.bottom)
                    val inter = max(0, r - l) * max(0, b - t)
                    val smaller = min(prev.width() * prev.height(), rect.width() * rect.height()).coerceAtLeast(1)
                    intersectionOverUnion(prev, rect) > 0.40f || inter.toFloat() / smaller > 0.70f
                }
                if (!overlapsExisting) selected.add(rect)
            }
            return selected.take(28)
        } finally { if (!sample.isRecycled && sample !== source) sample.recycle() }
    }

    private fun recognizeBubbleRegions(
        source: Bitmap, engines: List<Pair<String, TextRecognizer>>,
        regions: List<Rect>, onProgress: (Float, String) -> Unit): List<OcrCandidate> {
        if (regions.isEmpty()) return emptyList()
        val found = ArrayList<OcrCandidate>()
        regions.forEachIndexed { idx, region ->
            val rms = max(region.width(), region.height()).toFloat().coerceAtLeast(1f)
            val enlarge = if (rms < 900f) min(1.8f, 1450f / rms) else min(1f, 1600f / rms)
            val transform = Matrix().apply { setScale(enlarge, enlarge) }
            val crop = runCatching {
                Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height(), transform, true)
            }.getOrNull() ?: return@forEachIndexed
            try {
                val input = InputImage.fromBitmap(crop, 0)
                var latinChars = 0
                engines.forEach { engine ->
                    val result = runCatching { Tasks.await(engine.second.process(input)) }.getOrNull()
                    val local = collectCandidates(result, engine.first, enlarge)
                    if (engine.first == "en") latinChars = local.sumOf { item -> item.text.count { it.isLetter() } }
                    local.forEach { c ->
                        val shifted = c.bounds?.let { Rect(it).apply { offset(region.left, region.top) } }
                        found.add(c.copy(bounds = shifted, containerBounds = Rect(region), bubbleId = idx))
                    }
                }
                if (engines.any { it.first == "en" } && latinChars < 8) {
                    val (binary, bs) = createBinarizedOcrVariant(crop)
                    try {
                        val retry = runCatching { Tasks.await(latinRecognizer.process(InputImage.fromBitmap(binary, 0))) }.getOrNull()
                        collectCandidates(retry, "en", enlarge * bs).forEach { c ->
                            val shifted = c.bounds?.let { Rect(it).apply { offset(region.left, region.top) } }
                            found.add(c.copy(bounds = shifted, containerBounds = Rect(region), bubbleId = idx))
                        }
                    } finally { if (!binary.isRecycled) binary.recycle() }
                }
            } finally { if (!crop.isRecycled) crop.recycle() }
            onProgress(0.36f, "فقاعة ${idx + 1}/${regions.size}")
        }
        return found
    }

    private fun attachSingleBlockBubbleContainers(
        source: Bitmap, candidates: List<OcrCandidate>, regions: List<Rect>): List<OcrCandidate> {
        if (candidates.isEmpty() || regions.isEmpty()) return candidates
        val pageArea = source.width.toLong() * source.height.toLong()
        val regionAreas = regions.map { it.width().toLong() * it.height().toLong() }
        val regionTouchesBorder = regions.map { r ->
            r.left <= 1 || r.top <= 1 || r.right >= source.width - 1 || r.bottom >= source.height - 1
        }
        val associated = candidates.map { c ->
            val box = c.bounds ?: return@map c
            if (c.containerBounds != null) return@map c
            val match = regions.withIndex().filter { (idx, r) ->
                val ra = regionAreas[idx]
                val giantMargin = regionTouchesBorder[idx] && ra > pageArea * 2L / 100L
                r.contains(box.left, box.top, box.right, box.bottom) && !giantMargin &&
                    ra <= pageArea * 6L / 100L && r.width() <= box.width() * 4f &&
                    r.height() <= box.height() * 4f && r.width() >= box.width() * 1.22f &&
                    r.height() >= box.height() * 1.22f
            }.minByOrNull { (idx, _) -> regionAreas[idx] }
            if (match == null) c
            else c.copy(containerBounds = Rect(match.value), bubbleId = match.index)
        }
        val counts = associated.mapNotNull { it.bubbleId }.groupingBy { it }.eachCount()
        return associated.map { c ->
            val id = c.bubbleId
            if (id != null && counts[id] == 1) c else c.copy(containerBounds = null, bubbleId = null)
        }
    }

    private fun rotatedJapaneseCandidates(source: Bitmap): List<OcrCandidate> {
        val rotate = Matrix().apply { setRotate(90f) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotate, true)
        return try {
            val result = runCatching { Tasks.await(japaneseRecognizer.process(InputImage.fromBitmap(rotated, 0))) }.getOrNull()
            collectCandidates(result, "ja").mapNotNull { c ->
                val b = c.bounds ?: return@mapNotNull c
                val mapped = Rect(
                    b.top.coerceIn(0, source.width - 1),
                    (source.height - b.right).coerceIn(0, source.height - 1),
                    b.bottom.coerceIn(1, source.width),
                    (source.height - b.left).coerceIn(1, source.height))
                c.copy(bounds = mapped)
            }
        } finally { if (!rotated.isRecycled) rotated.recycle() }
    }

    // ═══════ MAIN TRANSLATE ═══════

    private fun translateBitmap(source: Bitmap, onProgress: (Float, String) -> Unit = { _, _ -> }): Bitmap {
        lastOcrTranslationLines.set(emptyList())
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val forcedLanguage = prefs.getString(PREF_SOURCE_LANGUAGE, "auto") ?: "auto"
        onProgress(0.03f, "تهيئة")
        val engines: List<Pair<String, TextRecognizer>> = when (forcedLanguage) {
            "ja" -> listOf("ja" to japaneseRecognizer); "en" -> listOf("en" to latinRecognizer)
            "ko" -> listOf("ko" to koreanRecognizer); "zh" -> listOf("zh" to chineseRecognizer)
            else -> listOf("en" to latinRecognizer, "ja" to japaneseRecognizer,
                "ko" to koreanRecognizer, "zh" to chineseRecognizer)
        }
        val cache = java.util.concurrent.atomic.AtomicReference<List<Rect>>(emptyList())
        fun regions(): List<Rect> {
            val e = cache.get()
            if (e.isNotEmpty()) return e
            val f = detectBrightBubbleRegions(source); cache.set(f); return f
        }
        fun recognizeAll(bitmap: Bitmap, scale: Float): List<OcrCandidate> {
            val image = InputImage.fromBitmap(bitmap, 0)
            val found = ArrayList<OcrCandidate>()
            engines.forEachIndexed { idx, engine ->
                onProgress(0.06f + idx * 0.055f, "OCR · ${idx + 1}/${engines.size}")
                val result = runCatching { Tasks.await(engine.second.process(image)) }.getOrNull()
                found.addAll(collectCandidates(result, engine.first, scale))
            }
            return found
        }
        val all = ArrayList<OcrCandidate>()
        all.addAll(recognizeAll(source, 1f))
        if (forcedLanguage == "auto" || forcedLanguage == "en") {
            val existLatin = all.asSequence().filter { it.engineLanguage == "en" }
                .sumOf { it.text.count { ch -> ch in 'A'..'Z' || ch in 'a'..'z' } }
            if (existLatin < 120) {
                onProgress(0.20f, "فحص خطوط مزخرفة")
                val (le, ls) = createOcrVariant(source, inverted = false)
                try {
                    val retry = runCatching { Tasks.await(latinRecognizer.process(InputImage.fromBitmap(le, 0))) }.getOrNull()
                    all.addAll(collectCandidates(retry, "en", ls))
                } finally { if (!le.isRecycled) le.recycle() }
                val rec = all.asSequence().filter { it.engineLanguage == "en" }
                    .sumOf { it.text.count { ch -> ch in 'A'..'Z' || ch in 'a'..'z' } }
                if (rec < 48) {
                    onProgress(0.235f, "عتبة تكيفية")
                    val (bin, bs) = createBinarizedOcrVariant(source)
                    try {
                        val retry = runCatching { Tasks.await(latinRecognizer.process(InputImage.fromBitmap(bin, 0))) }.getOrNull()
                        all.addAll(collectCandidates(retry, "en", bs))
                    } finally { if (!bin.isRecycled) bin.recycle() }
                }
            }
        }
        val firstMerged = mergeOcrCandidates(all)
        val foundChars = firstMerged.sumOf { it.text.count { c -> c.isLetterOrDigit() } }
        if (firstMerged.size < 2 || foundChars < 18) {
            onProgress(0.27f, "إعادة قراءة مكبّرة")
            val (e, s) = createOcrVariant(source, inverted = false)
            try { all.addAll(recognizeAll(e, s)) } finally { e.recycle() }
            val combined = mergeOcrCandidates(all)
            if (combined.size < 2 || combined.sumOf { it.text.length } < 18) {
                onProgress(0.34f, "محاولة معكوسة")
                val (i, is_) = createOcrVariant(source, inverted = true)
                try { all.addAll(recognizeAll(i, is_)) } finally { i.recycle() }
            }
        }
        val pre = mergeOcrCandidates(all)
        val preChars = pre.sumOf { it.text.count { c -> c.isLetterOrDigit() } }
        val tallSparse = source.height >= 2400 && source.height > source.width * 2 && pre.size < 20
        val lowCov = preChars < 180 && pre.size < 14
        if (pre.size < 6 || preChars < 100 || tallSparse || lowCov) {
            onProgress(0.355f, "فحص فقاعات")
            val rs = regions()
            all.addAll(recognizeBubbleRegions(source, engines, rs) { f, d -> onProgress(f, d) })
        }
        val beforeRot = mergeOcrCandidates(all)
        val vertJp = beforeRot.any { c ->
            val b = c.bounds
            b != null && b.height() > b.width() * 2.2f &&
                countChars(c.text) { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' } > 0
        }
        if ((vertJp || beforeRot.size < 2) && forcedLanguage in setOf("auto", "ja")) {
            onProgress(0.37f, "قراءة أعمدة يابانية مدوّرة")
            all.addAll(rotatedJapaneseCandidates(source))
        }
        val merged = mergeOcrCandidates(all)
        val candidates = attachSingleBlockBubbleContainers(source, merged, regions())
        if (candidates.isEmpty()) {
            updateQualityCounts(0, 0, 0, 0, emptyList())
            onProgress(1f, "لا نص")
            return source.copy(Bitmap.Config.ARGB_8888, true) ?: throw IllegalStateException("تعذر النسخ")
        }
        if (isLikelyAdPage(candidates)) {
            updateQualityCounts(candidates.size, 0, 0, candidates.size, emptyList())
            onProgress(1f, "إعلان")
            return source.copy(Bitmap.Config.ARGB_8888, true) ?: throw IllegalStateException("تعذر النسخ")
        }
        onProgress(0.40f, "تحديد لغة")
        val blocks = ArrayList<BlockForTranslation>()
        val failedSamples = ArrayList<String>(); var skipped = 0
        candidates.forEach { c ->
            val code = languageForBlock(c.text, c.engineLanguage, forcedLanguage)
            if (code == "ar") { skipped++; return@forEach }
            val b = c.bounds
            if (b == null || b.width() < 3 || b.height() < 3) {
                skipped++
                if (c.text.isNotBlank()) failedSamples.add("بلا إحداثيات · ${c.text.take(90)}")
                return@forEach
            }
            blocks.add(BlockForTranslation(Rect(b), c.text, code, c.containerBounds?.let(::Rect)))
        }
        if (blocks.isEmpty()) {
            updateQualityCounts(candidates.size, 0, 0, skipped, failedSamples)
            onProgress(1f, "لا كتل أجنبية")
            return source.copy(Bitmap.Config.ARGB_8888, true) ?: throw IllegalStateException("تعذر النسخ")
        }
        val translated = ArrayList<TranslationLine>(); var failed = 0
        val ks = MangaFoxKnowledgeStore(this)
        blocks.forEachIndexed { idx, item ->
            try {
                onProgress(0.43f + (0.50f * idx / blocks.size), "ترجمة ${idx + 1}/${blocks.size}")
                val norm = correctLikelyOcrText(item.text, item.language)
                val learned = learnedTranslationFor(item.text) ?: learnedTranslationFor(norm)
                val sfx = if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_TRANSLATE_SFX, false))
                    dictionarySfxTranslation(item.text) else null
                val gloss = ks.exactGlossaryTranslation(norm)
                var raw: String? = learned ?: sfx ?: gloss
                if (raw == null) {
                    val mixed = hasArabicLetters(norm) && hasForeignLetters(norm)
                    raw = runCatching {
                        if (mixed) translateMixedTextPreservingArabic(norm, item.language)
                        else translateWithLanguage(norm, item.language)
                    }.getOrNull()
                    val firstClean = raw?.let { cleanArabicTranslation(it) }.orEmpty()
                    val needAr = hasForeignLetters(norm)
                    if (firstClean.isBlank() || firstClean.equals(item.text, ignoreCase = true) ||
                        (needAr && !isUsefulArabicTranslation(norm, firstClean))) {
                        for (rc in retryLanguageCandidates(norm, item.language)) {
                            val retried = runCatching {
                                if (mixed) translateMixedTextPreservingArabic(norm, rc)
                                else translateWithLanguage(norm, rc)
                            }.getOrNull()
                            if (!retried.isNullOrBlank()) {
                                val cleanRetry = cleanArabicTranslation(retried)
                                if (cleanRetry.isNotBlank() && !cleanRetry.equals(item.text, ignoreCase = true) &&
                                    !hasUntranslatedLatinRuns(cleanRetry) &&
                                    (!needAr || isUsefulArabicTranslation(norm, cleanRetry))) {
                                    raw = retried; break
                                }
                            }
                        }
                    }
                }
                val out = raw?.let { cleanArabicTranslation(it) }.orEmpty()
                val needAr = hasForeignLetters(item.text)
                val junk = hasUntranslatedLatinRuns(out) && learned == null && sfx == null && gloss == null
                if (out.isBlank() ||
                    (out.equals(item.text, ignoreCase = true) && item.text.length > 8) ||
                    junk || (needAr && !isUsefulArabicTranslation(item.text, out))) {
                    failed++; failedSamples.add(item.text.take(100))
                } else {
                    translated.add(TranslationLine(
                        Rect(item.bounds), out, item.text, item.language,
                        vertical = MangaFoxLayoutEngine.shouldUseVertical(item.bounds),
                        containerBounds = item.containerBounds?.let(::Rect)))
                }
            } catch (_: Exception) { failed++; failedSamples.add(item.text.take(100)) }
            onProgress(0.45f + (0.48f * (idx + 1) / blocks.size), "ترجمة ${idx + 1}/${blocks.size}")
        }
        updateQualityCounts(candidates.size, translated.size, failed, skipped, failedSamples)
        if (translated.isEmpty()) {
            onProgress(1f, "لم تنجح ترجمة")
            return source.copy(Bitmap.Config.ARGB_8888, true) ?: throw IllegalStateException("تعذر النسخ")
        }
        lastOcrTranslationLines.set(translated.map {
            TranslationLine(
                Rect(it.bounds), it.translated, it.sourceText, it.language,
                vertical = MangaFoxLayoutEngine.shouldUseVertical(it.bounds),
                containerBounds = it.containerBounds?.let(::Rect))
        })
        onProgress(0.98f, "رسم")
        val rendered = drawTranslatedLines(source, translated)
        onProgress(1f, "اكتملت · ${translated.size} · فشل $failed · تُرك $skipped")
        return rendered
    }

    private fun writeRenderedBitmap(bitmap: Bitmap, target: File) {
        val png = target.extension.equals("png", ignoreCase = true)
        FileOutputStream(target).use { s ->
            val fmt = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val q = if (png) 100 else 96
            if (!bitmap.compress(fmt, q, s)) throw IllegalStateException("تعذر الحفظ")
        }
    }

    private fun drawTranslatedLines(source: Bitmap, translatedLines: List<TranslationLine>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("تعذر إنشاء صورة الترجمة")
        val canvas = Canvas(output)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val renderItems = translatedLines.mapNotNull { item ->
            val eraseRect = Rect(item.bounds).apply {
                val padX = max(3, width() / 12); val padY = max(3, height() / 8)
                inset(-padX, -padY)
                left = left.coerceIn(0, output.width); top = top.coerceIn(0, output.height)
                right = right.coerceIn(0, output.width); bottom = bottom.coerceIn(0, output.height)
            }
            val layoutRect = (item.containerBounds?.let(::Rect) ?: Rect(eraseRect)).apply {
                if (item.containerBounds != null) {
                    inset(max(3, width() / 12), max(3, height() / 12))
                    // ═══ تقييد أقل عدوانية: 1.6× و 2.2× بدل 2.4× و 3.2× ═══
                    val maxW = max(72, (eraseRect.width() * 1.6f).roundToInt())
                    val maxH = max(96, (eraseRect.height() * 2.2f).roundToInt())
                    val cx = eraseRect.centerX(); val cy = eraseRect.centerY()
                    val allowed = Rect(
                        (cx - maxW / 2).coerceIn(0, output.width), (cy - maxH / 2).coerceIn(0, output.height),
                        (cx + maxW / 2).coerceIn(0, output.width), (cy + maxH / 2).coerceIn(0, output.height))
                    val inter = Rect(this)
                    if (inter.intersect(allowed)) set(inter) else set(eraseRect)
                }
                left = left.coerceIn(0, output.width); top = top.coerceIn(0, output.height)
                right = right.coerceIn(0, output.width); bottom = bottom.coerceIn(0, output.height)
            }
            if (eraseRect.width() < 8 || eraseRect.height() < 8 || layoutRect.width() < 8 || layoutRect.height() < 8) null
            else Triple(item, eraseRect, layoutRect)
        }
        renderItems.forEach { (item, eraseRect, _) ->
            val bubble = item.containerBounds?.let { raw ->
                Rect(raw).apply {
                    inset(max(2, width() / 13), max(2, height() / 13))
                    left = left.coerceIn(0, output.width); top = top.coerceIn(0, output.height)
                    right = right.coerceIn(0, output.width); bottom = bottom.coerceIn(0, output.height)
                }.takeIf { it.width() >= 12 && it.height() >= 12 }
            }
            if (bubble != null) {
                bg.color = MangaFoxBubbleRepair.sampleInteriorColor(source, item.containerBounds!!, eraseRect)
                val br = RectF(bubble)
                val ratio = bubble.width().toFloat() / bubble.height().coerceAtLeast(1)
                when {
                    item.shape == "oval" || ratio in 0.82f..1.22f -> canvas.drawOval(br, bg)
                    item.shape == "rect" -> canvas.drawRoundRect(br, 4f, 4f, bg)
                    else -> {
                        val radius = min(bubble.width(), bubble.height()) * 0.24f
                        canvas.drawRoundRect(br, radius, radius, bg)
                    }
                }
            } else {
                val patch = Rect(eraseRect).apply {
                    left = left.coerceIn(0, output.width); top = top.coerceIn(0, output.height)
                    right = right.coerceIn(0, output.width); bottom = bottom.coerceIn(0, output.height)
                }
                bg.color = MangaFoxBubbleRepair.sampleBackground(source, patch)
                bg.isAntiAlias = false
                canvas.drawRect(RectF(patch), bg)
                bg.isAntiAlias = true
            }
        }
        renderItems.forEach { (item, _, layoutRect) ->
            if (!item.eraseOnly && item.translated.isNotBlank()) {
                canvas.save(); canvas.clipRect(layoutRect)
                drawArabicText(canvas, item.translated, layoutRect, item.vertical)
                canvas.restore()
            }
        }
        return output
    }

    private fun drawArabicText(canvas: Canvas, text: String, rect: Rect, vertical: Boolean = false) {
        if (vertical) { drawArabicTextTopToBottom(canvas, text, rect); return }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val insetX = max(4, rect.width() / 18)
        val insetY = max(3, rect.height() / 14)
        val availW = (rect.width() - insetX * 2).coerceAtLeast(12)
        val availH = (rect.height() - insetY * 2).coerceAtLeast(12)
        val maxPx = min(
            prefs.getInt(PREF_FONT_SIZE, 22).coerceIn(10, 72) * density,
            (availH * 0.78f).coerceAtLeast(8f * density))
        val automatic = prefs.getBoolean(PREF_AUTO_FONT_FIT, true)
        val layout = if (automatic) findLargestArabicLayout(text, availW, availH, maxPx, 7.5f * density)
            else buildArabicLayout(text, availW, maxPx)
        // ═══ coerce صارم: لا يتجاوز rect.bottom ═══
        val minY = rect.top + insetY
        val maxY = (rect.bottom - insetY - layout.height).coerceAtLeast(minY)
        val idealY = (rect.top + ((rect.height() - layout.height) / 2)).coerceIn(minY, maxY)
        val x = rect.left + insetX
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(x.toFloat(), idealY.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * يستخدم initialLayout.height بدل measureText للحصول على تقدير دقيق.
     * الفallback يتقلّص فعلياً تحت minSizePx بدل إرجاع layout لا يدخل.
     */
    private fun findLargestArabicLayout(
        value: String, width: Int, height: Int, maxSizePx: Float, minSizePx: Float): StaticLayout {
        val initialLayout = buildArabicLayout(value, width, minSizePx)
        val estimatedHeightAtMin = initialLayout.height.toFloat()
        val sizeCap = minSizePx * sqrt(height.toFloat() / estimatedHeightAtMin.coerceAtLeast(1f))
        val upper = min(maxSizePx, sizeCap).coerceAtLeast(minSizePx)
        var low = minSizePx
        var high = upper
        var best = buildArabicLayout(value, width, low)
        var bestFits = best.height <= height && (0 until best.lineCount).all { best.getLineWidth(it) <= width + 1f }
        repeat(8) {
            val mid = (low + high) / 2f
            val candidate = buildArabicLayout(value, width, mid)
            val fits = candidate.height <= height && (0 until candidate.lineCount).all {
                candidate.getLineWidth(it) <= width + 1f
            }
            if (fits) { best = candidate; bestFits = true; low = mid } else high = mid
        }
        // ═══ Fallback: يتقلص فعلياً تحت minSizePx ═══
        if (!bestFits) {
            var emergency = minSizePx - 0.5f
            while (emergency > 3f) {
                val candidate = buildArabicLayout(value, width, emergency)
                if (candidate.height <= height &&
                    (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= width + 1f }) {
                    return candidate
                }
                emergency -= 0.5f
            }
            return buildArabicLayout(value, width, 3f)
        }
        return best
    }

    private fun drawArabicTextTopToBottom(canvas: Canvas, value: String, rect: Rect) {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val maxWidth = (rect.width() - 10).coerceAtLeast(10)
        val maxHeight = (rect.height() - 10).coerceAtLeast(10)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val configured = prefs.getInt(PREF_FONT_SIZE, 22).coerceIn(10, 72) * density
        var chosen = min(configured, maxWidth * 0.90f).coerceAtLeast(7f * density)
        var layouts = emptyList<StaticLayout>(); var heights = emptyList<Int>()
        val autoFit = prefs.getBoolean(PREF_AUTO_FONT_FIT, true)
        val floor = 7.5f * density
        for (attempt in 0 until if (autoFit) 36 else 1) {
            val paint = arabicPaintFor(chosen)
            layouts = words.map { word ->
                StaticLayout.Builder.obtain(word, 0, word.length, paint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(true)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
            }
            heights = layouts.map { it.height }
            val total = heights.sum()
            if (!autoFit || total <= maxHeight || chosen <= floor) break
            chosen = max(floor, chosen - max(0.5f * density, (chosen - floor) / 8f))
        }
        val totalH = heights.sum()
        val spacing = max(1, (maxHeight - totalH) / (words.size + 1))
        var y = rect.top + spacing.toFloat()
        canvas.save(); canvas.clipRect(rect)
        layouts.forEachIndexed { idx, layout ->
            canvas.save(); canvas.translate(rect.left + (rect.width() - layout.width) / 2f, y)
            layout.draw(canvas); canvas.restore(); y += heights[idx] + spacing
        }
        canvas.restore()
    }

    private fun buildArabicLayout(value: String, width: Int, size: Float): StaticLayout {
        val paint = arabicPaintFor(size)
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(0f, 0.96f).build()
    }

    // ═══════ LENS ═══════

    private fun updateTranslationLens(percent: Int, detail: String) {
        if (percent >= 0) MangaFoxRuntime.progressPercent = percent.coerceIn(0, 100)
        MangaFoxRuntime.progressDetail = detail
        if (percent >= 0) MangaFoxRuntime.status = "ترجمة: ${percent.coerceIn(0, 100)}% · $detail"
        mainHandler.post {
            val view = bubble ?: return@post
            val current = if (translationRunning) MangaFoxRuntime.progressPercent else if (percent >= 100) 100 else percent
            view.text = when {
                percent < 0 -> "!"; translationRunning -> "${current.coerceIn(0, 99)}%"
                current >= 100 -> "✓"; else -> "狐"
            }
            view.textSize = if (translationRunning) 13f else 25f
            view.contentDescription = if (translationRunning) "ترجمة ${current}%، $detail"
                else if (percent < 0) "خطأ" else "اضغط للترجمة، مطولاً للفصل"
            val params = bubbleParams
            if (bubbleWasAdded && params != null) runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun resetLensProgress() {
        mainHandler.post {
            bubble?.apply {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                text = prefs.getString(PREF_LENS_GLYPH, "狐") ?: "狐"
                textSize = (prefs.getInt(PREF_LENS_SIZE, 58) * 0.40f).coerceIn(18f, 38f)
                contentDescription = "اضغط للترجمة، مطولاً للفصل"
            }
        }
    }

    fun applyLensSettings() {
        val view = bubble ?: return; val params = bubbleParams ?: return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val size = prefs.getInt(PREF_LENS_SIZE, 58).coerceIn(42, 96)
        params.width = dp(size); params.height = dp(size)
        view.textSize = (size * 0.40f).coerceIn(18f, 38f)
        view.alpha = prefs.getInt(PREF_LENS_ALPHA, 100).coerceIn(35, 100) / 100f
        view.text = prefs.getString(PREF_LENS_GLYPH, "狐") ?: "狐"
        view.background = lensBackground(prefs.getInt(PREF_LENS_COLOR, Color.rgb(255, 190, 65)))
        if (bubbleWasAdded) runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun lensBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
    }

    fun refreshLensAppFilter() {
        if (!this::windowManager.isInitialized) return
        val overlayAllowed = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        if (!overlayAllowed) {
            MangaFoxRuntime.status = "يلزم السماح بالظهور"
            MangaFoxRuntime.lastError = "افتح الإعدادات واسمح بالظهور فوق التطبيقات."
            return
        }
        if (bubbleWasAdded && bubble != null) return
        showFloatingBubble()
    }

    private fun showFloatingBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        if (bubbleWasAdded || !this::windowManager.isInitialized) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lensSize = prefs.getInt(PREF_LENS_SIZE, 58).coerceIn(42, 96)
        val lensAlpha = prefs.getInt(PREF_LENS_ALPHA, 100).coerceIn(35, 100) / 100f
        val view = TextView(this).apply {
            text = prefs.getString(PREF_LENS_GLYPH, "狐") ?: "狐"
            textSize = (lensSize * 0.40f).coerceIn(18f, 38f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.BLACK); gravity = Gravity.CENTER
            background = lensBackground(prefs.getInt(PREF_LENS_COLOR, Color.rgb(255, 190, 65)))
            elevation = dp(8).toFloat(); alpha = lensAlpha
            contentDescription = "اضغط للترجمة، مطولاً للفصل"
        }
        val params = WindowManager.LayoutParams(
            dp(lensSize), dp(lensSize),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("lens_x", dp(12))
            y = prefs.getInt("lens_y", (resources.displayMetrics.heightPixels * 0.36f).toInt())
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = startX + dx; params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("lens_x", params.x).putInt("lens_y", params.y).apply()
                    else if (event.eventTime - event.downTime > 550L) startFullChapterCapture()
                    else startSingleCapture(); true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        if (translationRunning) { view.text = "${MangaFoxRuntime.progressPercent.coerceIn(0, 99)}%"; view.textSize = 13f }
        bubble = view; bubbleParams = params
        runCatching {
            windowManager.addView(view, params); bubbleWasAdded = true
            MangaFoxRuntime.status = "العدسة ظاهرة"
        }.onFailure { e ->
            bubble = null; bubbleParams = null; bubbleWasAdded = false
            MangaFoxRuntime.status = "تعذر العدسة: ${e.javaClass.simpleName}"
            MangaFoxRuntime.lastError = "تحقق من إذن الظهور. ${e.message ?: ""}"
        }
    }

    private fun removeFloatingBubble() {
        val v = bubble
        if (v != null && bubbleWasAdded) runCatching { windowManager.removeView(v) }
        bubbleWasAdded = false; bubble = null; bubbleParams = null
    }

    private fun restoreFloatingBubble() { refreshLensAppFilter() }

    private fun notifyCaptureProgress() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cid = "manga_fox_capture"
        if (Build.VERSION.SDK_INT >= 26) m.createNotificationChannel(NotificationChannel(cid, "التقاط", NotificationManager.IMPORTANCE_LOW))
        val si = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val sp = PendingIntent.getActivity(this, 402, si, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, cid) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Manga Fox · التقاط")
            .setContentText("$captureCount لقطة")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", sp).build()
        runCatching { m.notify(402, n) }
    }

    private fun clearCaptureNotification() {
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(402) }
    }

    private fun notifyResultsReady(): Boolean {
        val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cid = "manga_fox_results"
        if (Build.VERSION.SDK_INT >= 26) m.createNotificationChannel(NotificationChannel(cid, "نتائج", NotificationManager.IMPORTANCE_DEFAULT))
        val oi = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_RESULTS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(this, 401, oi, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, cid) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Manga Fox").setContentText("اكتملت الترجمة · اضغط")
            .setContentIntent(pi).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            m.notify(401, n); return true
        }
        return false
    }

    private fun toast(message: String) { mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() } }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}