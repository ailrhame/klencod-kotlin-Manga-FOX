package com.manga.fox

import android.content.Context
import android.os.StatFs
import android.os.Debug
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** @N5CR4 — Qwen GGUF local inference bridge using the prebuilt llama-android AAR. */
object MangaFoxLocalLlm {
    private const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf?download=true"
    private const val MIN_MODEL_BYTES = 900_000_000L
    private val busy = AtomicBoolean(false)

    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedModel: LlamaModel? = null
    @Volatile private var lastLoadFailure: String = "لم تبدأ محاولة تحميل النموذج بعد"
    @Volatile private var lastInferenceMillis: Long = -1L
    @Volatile private var lastInferenceBubbleCount: Int = 0
    @Volatile private var lastInferenceWindowCount: Int = 0
    @Volatile private var lastInferenceError: String = "لا توجد عملية ترجمة مسجلة بعد"

    fun lastLoadError(): String = lastLoadFailure

    /** Reports process PSS (native + managed memory) and most recent local inference metrics. */
    fun diagnosticsSummary(context: Context): String {
        val pssMb = runCatching { Debug.getPss().toDouble() / 1024.0 }.getOrDefault(0.0)
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / (1024.0 * 1024.0)
        val modelMb = modelFile(context).takeIf { it.isFile }?.length()?.toDouble()?.div(1024.0 * 1024.0) ?: 0.0
        val lastTime = if (lastInferenceMillis >= 0L) "${lastInferenceMillis}ms" else "غير متاح"
        return "ذاكرة العملية PSS: ${String.format(java.util.Locale.US, "%.0f", pssMb)}MB · Heap: ${String.format(java.util.Locale.US, "%.0f", heapUsedMb)}MB · حجم النموذج: ${String.format(java.util.Locale.US, "%.0f", modelMb)}MB\n" +
            "آخر ترجمة: $lastTime · الفقاعات: $lastInferenceBubbleCount · الدفعات: $lastInferenceWindowCount\n" +
            "حالة المحرك: ${if (isLoaded()) "محمّل" else "غير محمّل"} · ${lastInferenceError.take(120)}"
    }
    fun nativeStatus(): String = when {
        loadedModel != null -> "محرك llama.cpp جاهز · النموذج محمّل"
        else -> "مكتبة llama-android مضافة؛ النموذج غير محمّل"
    }

    fun modelFile(context: Context): File = File(context.filesDir, "ai_models/$MODEL_FILE")

    fun isModelDownloaded(context: Context): Boolean {
        val file = modelFile(context)
        if (!file.isFile || file.length() < MIN_MODEL_BYTES) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            }
        }.getOrDefault(false)
    }

    fun isLoaded(): Boolean = loadedModel != null && loadedPath != null

    /** Resumable download with GGUF header and final-size validation. */
    fun downloadModel(context: Context, onProgress: (Long, Long) -> Unit): File {
        val target = modelFile(context)
        val parent = target.parentFile ?: throw IllegalStateException("تعذر تحديد مجلد النموذج")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد النموذج")
        val partial = File(parent, target.name + ".part")
        if (StatFs(parent.absolutePath).availableBytes < 1_200_000_000L) {
            throw IllegalStateException("المساحة الحرة غير كافية؛ وفر 1.2GB على الأقل")
        }

        var offset = if (partial.isFile) partial.length() else 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MangaFox-Android/3.1")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            var status = connection.responseCode
            if (offset > 0L && status != HttpURLConnection.HTTP_PARTIAL) {
                partial.delete()
                offset = 0L
                connection.disconnect()
                return downloadModel(context, onProgress)
            }
            if (status !in 200..299) throw IllegalStateException("فشل تنزيل النموذج: HTTP $status")

            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val contentRange = connection.getHeaderField("Content-Range")
            val rangeTotal = contentRange?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            val expectedTotal = if (rangeTotal > 0L) rangeTotal else if (contentLength > 0L) contentLength + offset else -1L

            connection.inputStream.use { input ->
                RandomAccessFile(partial, "rw").use { output ->
                    if (offset == 0L) output.setLength(0L)
                    output.seek(offset)
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = offset
                    var lastReport = offset
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReport >= 5L * 1024L * 1024L) {
                            lastReport = downloaded
                            onProgress(downloaded, expectedTotal)
                        }
                    }
                    onProgress(downloaded, expectedTotal)
                    output.fd.sync()
                }
            }

            if (partial.length() < MIN_MODEL_BYTES) {
                throw IllegalStateException("ملف النموذج غير مكتمل (${partial.length()} bytes)")
            }
            if (expectedTotal > 0L && partial.length() != expectedTotal) {
                throw IllegalStateException("حجم النموذج لا يطابق الحجم المتوقع؛ أعد استكمال التنزيل")
            }
            RandomAccessFile(partial, "r").use { input ->
                val header = ByteArray(4)
                input.readFully(header)
                if (!header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
                    throw IllegalStateException("الملف المستلم ليس GGUF صالحاً")
                }
            }
            if (target.exists() && !target.delete()) throw IllegalStateException("تعذر استبدال ملف النموذج القديم")
            if (!partial.renameTo(target)) throw IllegalStateException("تعذر تثبيت ملف النموذج")
            return target
        } finally {
            connection.disconnect()
        }
    }

    /** Loads the local GGUF model through the AAR; caller should use a worker thread. */
    @Synchronized
    fun load(context: Context): Boolean {
        val file = modelFile(context)
        if (!file.isFile) {
            lastLoadFailure = "ملف النموذج غير موجود: ${file.absolutePath}"
            return false
        }
        if (file.length() < MIN_MODEL_BYTES || !isModelDownloaded(context)) {
            lastLoadFailure = "ملف GGUF غير مكتمل أو تالف؛ الحجم الحالي ${file.length()} bytes"
            return false
        }
        if (loadedPath == file.absolutePath && loadedModel != null) {
            lastLoadFailure = "النموذج محمّل مسبقاً"
            return true
        }

        return try {
            loadedModel?.let { runCatching { Llama.releaseModel(it) } }
            loadedModel = null
            loadedPath = null

            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val model = runBlocking {
                Llama.loadModel(
                    modelPath = file.absolutePath,
                    config = LlamaConfig(
                        contextSize = 2048,
                        threads = threads,
                        gpuLayers = 0,
                        temperature = 0.25f,
                        topP = 0.90f,
                        topK = 40,
                        seed = -1
                    )
                )
            }
            loadedModel = model
            loadedPath = file.absolutePath
            lastLoadFailure = "تم تحميل Qwen بنجاح عبر llama-android"
            true
        } catch (error: Throwable) {
            loadedModel = null
            loadedPath = null
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            lastLoadFailure = "فشل تحميل محرك Qwen: $detail"
            false
        }
    }

    data class Bubble(val id: Int, val language: String, val text: String)

    /** Synchronous facade retained so existing MainActivity call sites remain unchanged. */
    @Synchronized
    fun translatePageContext(
        context: Context,
        blocks: List<Bubble>,
        glossary: List<MangaFoxKnowledgeStore.GlossaryEntry>,
        characters: List<MangaFoxKnowledgeStore.CharacterEntry>
    ): Map<Int, String> {
        require(blocks.isNotEmpty()) { "لا توجد فقاعات للترجمة" }
        check(busy.compareAndSet(false, true)) { "محرك الترجمة المحلي مشغول" }
        val inferenceStartedNanos = System.nanoTime()
        try {
            check(load(context)) { lastLoadFailure }
            val model = loadedModel ?: throw IllegalStateException("لم يتم إنشاء جلسة Qwen")

            val mode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_QWEN_TRANSLATION_MODE, "balanced") ?: "balanced"
            val (windowCharLimit, bubbleLimit, glossaryLimit, characterLimit, tokenLimit) = when (mode) {
                "quick" -> listOf(1500, 12, 12, 8, 620)
                "precise" -> listOf(2250, 20, 32, 16, 950)
                else -> listOf(1950, 16, 24, 12, 820)
            }
            val glossaryText = glossary.take(glossaryLimit).joinToString("\n") {
                "${it.source.take(100)} = ${it.arabic.take(140)} ${it.note.take(100)}"
            }
            val characterText = characters.take(characterLimit).joinToString("\n") {
                "${it.name.take(80)} | ${it.voice.take(100)} | ${it.notes.take(120)}"
            }

            // The chosen profile changes only batching/context density; it never truncates source bubbles.
            val windows = ArrayList<List<Bubble>>()
            var current = ArrayList<Bubble>()
            var chars = 0
            for (bubble in blocks) {
                val cost = bubble.text.length + 48
                if (current.isNotEmpty() && (chars + cost > windowCharLimit || current.size >= bubbleLimit)) {
                    windows.add(current)
                    current = ArrayList()
                    chars = 0
                }
                current.add(bubble)
                chars += cost
            }
            if (current.isNotEmpty()) windows.add(current)

            val result = LinkedHashMap<Int, String>()
            runBlocking {
                windows.forEachIndexed { windowIndex, window ->
                    val payload = JSONArray()
                    window.forEach { bubble ->
                        payload.put(
                            JSONObject()
                                .put("id", bubble.id)
                                .put("language", bubble.language)
                                .put("text", bubble.text)
                        )
                    }

                    val systemPrompt = """
أنت مترجم مانجا ومانهوا محترف إلى العربية الفصحى. افهم المشهد والحوار قبل ترجمة كل فقاعة.
قواعد إلزامية:
- اقرأ الفقاعات في المجموعة كسياق حواري واحد، واربط الضمائر والمتحدث والمخاطَب وتسلسل الأحداث.
- أعد ترجمة مستقلة لكل id، ولا تدمج فقاعتين ولا تغيّر الترتيب.
- لا تترجم كلمة بكلمة؛ انقل المعنى المقصود بصياغة عربية طبيعية موجزة تناسب فقاعة المانجا.
- حافظ على النفي والشرط والسخرية والغضب والتردد والتلميحات بحسب السياق.
- فسّر التعابير الاصطلاحية والأمثال والشتائم والمزاح بحسب المقصود في المشهد، ولا تنقل بنيتها حرفياً إذا أفسدت المعنى.
- اجعل الحوار عربياً طبيعياً ومختصراً، مع الحفاظ على شخصية المتحدث ودرجة الرسمية والانفعال.
- ثبّت الأسماء والألقاب والمصطلحات وفق القاموس، ولا تخترع جنس الشخصية أو معلومات غير موجودة.
- عند غموض الضمير أو المتحدث، اختر الصياغة العربية الأقل افتراضاً ولا تضف معلومة من عندك.
- لا تضف شرحاً أو ملاحظات أو نصاً خارج JSON.
- أخرج JSON فقط بالشكل: {"translations":[{"id":0,"text":"الترجمة"}]}، وكل id مرة واحدة.

قاموس المصطلحات:
${glossaryText.ifBlank { "لا يوجد" }}

الشخصيات:
${characterText.ifBlank { "لا توجد بيانات" }}
                    """.trimIndent()

                    val maxTokens = max(320, (window.sumOf { it.text.length } / 2 + 220)).coerceAtMost(tokenLimit)
                    val generated = Llama.complete(
                        model = model,
                        prompt = payload.toString(),
                        systemPrompt = systemPrompt,
                        maxTokens = maxTokens
                    ).text.trim()

                    val jsonStart = generated.indexOf('{')
                    val jsonEnd = generated.lastIndexOf('}')
                    if (jsonStart < 0 || jsonEnd <= jsonStart) {
                        throw IllegalStateException("المجموعة ${windowIndex + 1}: لم يُرجع النموذج JSON صالحاً")
                    }
                    val parsed = JSONObject(generated.substring(jsonStart, jsonEnd + 1))
                    val rows = parsed.optJSONArray("translations")
                        ?: throw IllegalStateException("المجموعة ${windowIndex + 1}: استجابة بلا translations")
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        val id = row.optInt("id", -1)
                        val value = row.optString("text").trim()
                        if (id >= 0 && value.isNotBlank()) result[id] = value
                    }
                    if (window.any { result[it.id].isNullOrBlank() }) {
                        throw IllegalStateException("المجموعة ${windowIndex + 1}: ترجمة غير مكتملة")
                    }
                }
            }
            lastInferenceMillis = ((System.nanoTime() - inferenceStartedNanos) / 1_000_000L).coerceAtLeast(0L)
            lastInferenceBubbleCount = blocks.size
            lastInferenceWindowCount = windows.size
            lastInferenceError = "آخر ترجمة اكتملت بنجاح"
            return result
        } catch (error: Throwable) {
            lastInferenceMillis = ((System.nanoTime() - inferenceStartedNanos) / 1_000_000L).coerceAtLeast(0L)
            lastInferenceBubbleCount = blocks.size
            lastInferenceWindowCount = 0
            lastInferenceError = "تعذرت الترجمة: ${error.message ?: error.javaClass.simpleName}"
            throw IllegalStateException(error.message ?: "فشلت الترجمة المحلية", error)
        } finally {
            busy.set(false)
        }
    }

    @Synchronized
    fun release() {
        loadedModel?.let { runCatching { Llama.releaseModel(it) } }
        loadedModel = null
        loadedPath = null
    }
}