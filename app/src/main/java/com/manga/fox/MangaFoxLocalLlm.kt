package com.manga.fox

import android.content.Context
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** @N5CR4 — Qwen GGUF local inference bridge. */
object MangaFoxLocalLlm {
    private const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf?download=true"
    private val busy = AtomicBoolean(false)
    @Volatile private var loadedPath: String? = null
    @Volatile private var nativeLibraryReady = false
    @Volatile private var nativeLibraryError: String? = null

    // Load the optional native engine only when the user explicitly loads Qwen.
    // Opening Settings must never crash just because the .so is absent or incompatible.
    @Synchronized private fun ensureNativeLibrary(): Boolean {
        if (nativeLibraryReady) return true
        return try {
            System.loadLibrary("mangallm")
            nativeLibraryReady = true
            nativeLibraryError = null
            true
        } catch (error: Throwable) {
            nativeLibraryError = error.javaClass.simpleName + ": " + (error.message ?: "native library unavailable")
            false
        }
    }

    fun nativeStatus(): String = nativeLibraryError ?: if (nativeLibraryReady) "جاهز" else "لم يتم تحميل المحرك بعد"

    private external fun nativeLoadModel(path: String, threads: Int, contextSize: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeRelease()

    fun modelFile(context: Context) = File(context.filesDir, "ai_models/$MODEL_FILE")
    fun isModelDownloaded(context: Context) = modelFile(context).let { it.isFile && it.length() > 900_000_000L }
    fun isLoaded() = loadedPath != null

    fun downloadModel(context: Context, onProgress: (Long, Long) -> Unit): File {
        val target = modelFile(context)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        if (StatFs(target.parentFile!!.absolutePath).availableBytes < 1_200_000_000L)
            throw IllegalStateException("المساحة الحرة غير كافية؛ وفر 1.2GB على الأقل")
        var offset = if (partial.exists()) partial.length() else 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000; readTimeout = 60000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MangaFox-Android/3.1")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        var expectedTotal = -1L
        try {
            val status = connection.responseCode
            if (offset > 0 && status != HttpURLConnection.HTTP_PARTIAL) { partial.delete(); offset = 0L }
            if (status !in 200..299) throw IllegalStateException("فشل تنزيل النموذج: HTTP $status")
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            val total = if (length > 0) length + offset else -1L
            expectedTotal = total
            connection.inputStream.use { input ->
                RandomAccessFile(partial, "rw").use { output ->
                    output.seek(offset); val buffer = ByteArray(1024 * 1024); var downloaded = offset; var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); downloaded += n
                        if (downloaded - lastReport >= 5L * 1024L * 1024L) { lastReport = downloaded; onProgress(downloaded, total) }
                    }
                    onProgress(downloaded, total)
                    output.fd.sync()
                }
            }
            if (partial.length() < 900_000_000L) throw IllegalStateException("ملف النموذج غير مكتمل (${partial.length()} bytes)")
            if (expectedTotal > 0 && partial.length() != expectedTotal)
                throw IllegalStateException("حجم النموذج لا يطابق الحجم المتوقع؛ أعد التنزيل")
            val header = ByteArray(4)
            RandomAccessFile(partial, "r").use { it.readFully(header) }
            if (!header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46)))
                throw IllegalStateException("الملف المستلم ليس GGUF صالحاً")
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IllegalStateException("تعذر تثبيت ملف النموذج")
            return target
        } finally { connection.disconnect() }
    }

    @Synchronized fun load(context: Context): Boolean {
        val file = modelFile(context)
        if (!file.isFile || file.length() < 900_000_000L) return false
        if (loadedPath == file.absolutePath) return true
        if (!ensureNativeLibrary()) return false
        if (nativeLibraryReady) nativeRelease()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val ok = nativeLoadModel(file.absolutePath, threads, 4096)
        loadedPath = if (ok) file.absolutePath else null
        return ok
    }

    data class Bubble(val id: Int, val language: String, val text: String)

    fun translatePageContext(context: Context, blocks: List<Bubble>,
        glossary: List<MangaFoxKnowledgeStore.GlossaryEntry>,
        characters: List<MangaFoxKnowledgeStore.CharacterEntry>): Map<Int, String> {
        require(blocks.isNotEmpty()) { "لا توجد فقاعات للترجمة" }
        check(busy.compareAndSet(false, true)) { "محرك الترجمة المحلي مشغول" }
        try {
            check(load(context)) { "حمّل نموذج Qwen المحلي أولاً" }
            val glossaryText = glossary.take(48).joinToString("\n") { "${it.source} = ${it.arabic} ${it.note}" }
            val characterText = characters.take(24).joinToString("\n") { "${it.name} | ${it.voice} | ${it.notes}" }
            val windows = ArrayList<List<Bubble>>(); var current = ArrayList<Bubble>(); var chars = 0
            for (bubble in blocks) {
                val cost = bubble.text.length + 48
                if (current.isNotEmpty() && (chars + cost > 3000 || current.size >= 24)) { windows.add(current); current = ArrayList(); chars = 0 }
                current.add(bubble); chars += cost
            }
            if (current.isNotEmpty()) windows.add(current)
            val result = LinkedHashMap<Int, String>()
            windows.forEachIndexed { wi, window ->
                val payload = JSONArray()
                window.forEach { payload.put(JSONObject().put("id", it.id).put("language", it.language).put("text", it.text)) }
                val system = """
أنت مترجم مانجا ومانهوا محترف إلى العربية الفصحى. اقرأ جميع الفقاعات في المجموعة كسياق حواري واحد.
- افهم المتحدث والمخاطَب والضمائر والنبرة واستمرار الجملة بين الفقاعات.
- أعد ترجمة مستقلة لكل id، ولا تدمج فقاعتين ولا تغيّر الترتيب.
- ثبّت الأسماء والألقاب والمصطلحات وفق المعجم.
- لا تخترع معلومات أو جنس شخصية لا يدل عليه النص، ولا تحذف نفياً أو شرطاً.
- استخدم عربية طبيعية موجزة تناسب فقاعة المانجا، لا ترجمة حرفية متخشبة.
- أخرج JSON فقط: {"translations":[{"id":0,"text":"..."}]} مع كل id مرة واحدة.
قاموس المصطلحات:
${glossaryText.ifBlank { "لا يوجد" }}
الشخصيات:
${characterText.ifBlank { "لا توجد بيانات" }}
                """.trimIndent()
                val prompt = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$payload<|im_end|>\n<|im_start|>assistant\n"
                val generated = nativeGenerate(prompt, max(320, window.sumOf { it.text.length } / 2 + 220)).trim()
                val jsonStart = generated.indexOf('{')
                val jsonEnd = generated.lastIndexOf('}')
                if (jsonStart < 0 || jsonEnd <= jsonStart)
                    throw IllegalStateException("المجموعة ${wi + 1}: لم يُرجع النموذج JSON صالحاً")
                val cleaned = generated.substring(jsonStart, jsonEnd + 1)
                val rows = JSONObject(cleaned).optJSONArray("translations") ?: throw IllegalStateException("المجموعة ${wi + 1}: استجابة بلا translations")
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optInt("id", -1); val value = row.optString("text").trim()
                    if (id >= 0 && value.isNotBlank()) result[id] = value
                }
                if (window.any { result[it.id].isNullOrBlank() }) throw IllegalStateException("المجموعة ${wi + 1}: ترجمة غير مكتملة")
            }
            return result
        } finally { busy.set(false) }
    }

    @Synchronized fun release() {
        if (nativeLibraryReady) runCatching { nativeRelease() }
        loadedPath = null
    }
}
