package com.manga.fox

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
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
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val ACTION_SHOW_RESULTS = "com.manga.fox.SHOW_RESULTS"
private const val MAX_CAPTURE_FRAMES = 24

object MangaFoxRuntime {
    @Volatile var status: String = "جاهز للترجمة"
    @Volatile var resultPages: List<Bitmap> = emptyList()
    @Volatile var resultTitle: String = "الفصل المترجم"
    @Volatile var lastError: String = ""
}

class MainActivity : android.app.Activity() {
    private val dark = Color.rgb(10, 10, 13)
    private val panel = Color.rgb(25, 25, 31)
    private val gold = Color.rgb(255, 190, 65)
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (intent?.action == ACTION_SHOW_RESULTS) showResults() else showHome()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_SHOW_RESULTS) showResults()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshStatus()
        MangaFoxAccessibilityService.instance?.ensureFloatingBubble()
    }

    private fun applySystemBars() {
        window.statusBarColor = dark
        window.navigationBarColor = dark
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    private fun showHome() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(dark)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(30))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val brand = TextView(this).apply {
            text = "MANGA FOX"
            textSize = 31f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(gold)
            gravity = Gravity.CENTER
        }
        root.addView(brand, matchWrap())

        val subtitle = TextView(this).apply {
            text = "ترجمة المانجا والمانهوا أثناء القراءة"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        root.addView(subtitle, matchWrap())

        val intro = card()
        intro.addView(text("ترجمة الفصل العمودي بالكامل", 20f, gold, true))
        intro.addView(text("تلتقط Manga Fox لقطات متتابعة أثناء النزول، وتدمجها كصفحات متسلسلة، ثم تتعرف على النصوص وتترجمها إلى العربية داخل نسخة طويلة قابلة للتمرير.", 14f, Color.LTGRAY))
        intro.addView(text("تعمل المعالجة على الجهاز بعد تنزيل نماذج اللغات. يلزم اتصال بالإنترنت لتنزيل النموذج أول مرة.", 13f, Color.rgb(195, 185, 165)))
        root.addView(intro, matchWrap(dp(18)))

        val permissions = card()
        permissions.addView(text("الإعداد الأولي", 18f, Color.WHITE, true))
        permissions.addView(actionButton("1 · تفعيل خدمة Manga Fox", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        permissions.addView(actionButton("2 · السماح بالنافذة العائمة", false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "إذن النافذة العائمة مفعّل", Toast.LENGTH_SHORT).show()
            }
        })
        permissions.addView(text("بعد تفعيل الإذنين، ارجع إلى تطبيق المانجا. ستظهر عدسة Manga Fox العائمة.", 13f, Color.LTGRAY))
        root.addView(permissions, matchWrap(dp(16)))

        val actions = card()
        actions.addView(text("الترجمة", 18f, Color.WHITE, true))
        actions.addView(actionButton("ترجمة الشاشة الحالية", true) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("فعّل خدمة Manga Fox أولاً") else service.startSingleCapture()
        })
        actions.addView(actionButton("ترجمة الفصل العمودي كاملاً", true) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("فعّل خدمة Manga Fox أولاً، ثم افتح فصل المانجا") else service.startFullChapterCapture()
        })
        actions.addView(actionButton("عرض آخر نتيجة مترجمة", false) { showResults() })
        root.addView(actions, matchWrap(dp(16)))

        val statusCard = card()
        statusCard.addView(text("الحالة", 16f, Color.WHITE, true))
        statusText = text("", 14f, Color.LTGRAY)
        statusCard.addView(statusText, matchWrap())
        root.addView(statusCard, matchWrap(dp(16)))

        root.addView(text("ملاحظة: الالتقاط الكامل يحتاج Android 11 أو أحدث. قد تمنع بعض تطبيقات القراءة تصوير الشاشة. النسخة الأولية تترجم مناطق النص المكتشفة وقد لا تمسح الكتابة من الخلفيات المعقدة بالكامل.", 12f, Color.GRAY).apply {
            setPadding(dp(4), dp(6), dp(4), dp(14))
        }, matchWrap())
        root.addView(text("MANGA FOX  ·  N5CR4", 12f, gold).apply { gravity = Gravity.CENTER }, matchWrap())

        scroll.addView(root)
        setContentView(scroll)
        refreshStatus()
    }

    private fun showResults() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(dark)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(18), dp(12), dp(24))
        }
        root.addView(text("MANGA FOX · الترجمة العربية", 21f, gold, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }, matchWrap())
        root.addView(actionButton("العودة إلى الرئيسية", false) { showHome() }, matchWrap(dp(10)))

        val pages = MangaFoxRuntime.resultPages
        if (pages.isEmpty()) {
            root.addView(text(
                if (MangaFoxRuntime.lastError.isNotBlank()) MangaFoxRuntime.lastError else "لا توجد نتيجة بعد. افتح فصلاً واضغط العدسة العائمة مطولاً لترجمة الفصل كاملاً.",
                15f,
                Color.LTGRAY
            ).apply { setPadding(dp(12), dp(30), dp(12), dp(30)) }, matchWrap())
        } else {
            root.addView(text("${pages.size} مقطع مترجم · ${MangaFoxRuntime.resultTitle}", 13f, Color.LTGRAY).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(10))
            }, matchWrap())
            pages.forEachIndexed { index, bitmap ->
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = "الجزء المترجم ${index + 1}"
                    setBackgroundColor(Color.WHITE)
                }
                root.addView(image, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3) })
            }
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun refreshStatus() {
        val access = isAccessibilityEnabled()
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        statusText.text = "خدمة الوصول: ${if (access) "مفعّلة" else "غير مفعّلة"}\nالنافذة العائمة: ${if (overlay) "مسموح بها" else "تحتاج إذناً"}\nالحالة الحالية: ${MangaFoxRuntime.status}"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/${MangaFoxAccessibilityService::class.java.name}") ||
            enabled.contains("$packageName/.MangaFoxAccessibilityService")
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(panel, 18)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.RIGHT
        setPadding(0, dp(5), 0, dp(6))
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = rounded(if (primary) gold else Color.rgb(50, 49, 57), 13)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(7)
            bottomMargin = dp(3)
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun matchWrap(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (bottom > 0) bottomMargin = bottom
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

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
    private var captureRunning = false
    private var fullChapterMode = false
    private var captureCount = 0
    private var previousFrame: Bitmap? = null
    private val capturedSegments = ArrayList<Bitmap>()
    private var bubbleWasAdded = false

    private val recognizersDelegate = lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        )
    }
    private val recognizers: List<TextRecognizer> by recognizersDelegate
    private val languageIdentifierDelegate = lazy { LanguageIdentification.getClient() }
    private val languageIdentifier by languageIdentifierDelegate

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        MangaFoxRuntime.status = "خدمة العدسة متصلة"
        showFloatingBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeFloatingBubble()
        instance = null
        previousFrame?.recycle()
        previousFrame = null
        capturedSegments.forEach { if (!it.isRecycled) it.recycle() }
        capturedSegments.clear()
        if (recognizersDelegate.isInitialized()) recognizers.forEach { runCatching { it.close() } }
        if (languageIdentifierDelegate.isInitialized()) runCatching { languageIdentifier.close() }
        worker.shutdown()
        super.onDestroy()
    }

    fun startSingleCapture() {
        if (captureRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("الالتقاط التلقائي يحتاج Android 11 أو أحدث")
            return
        }
        captureRunning = true
        fullChapterMode = false
        capturedSegments.forEach { if (!it.isRecycled) it.recycle() }
        capturedSegments.clear()
        MangaFoxRuntime.status = "التقاط الشاشة الحالية..."
        removeFloatingBubble()
        mainHandler.postDelayed({
            requestScreenshot { bitmap, error ->
                if (bitmap == null) {
                    finishCaptureWithError(error ?: "تعذر التقاط الشاشة")
                } else {
                    val segment = cropContent(bitmap, 0f)
                    bitmap.recycle()
                    capturedSegments.clear()
                    capturedSegments.add(segment)
                    finishCaptureAndTranslate()
                }
            }
        }, 250L)
    }

    fun startFullChapterCapture() {
        if (captureRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("ترجمة الفصل الكامل تحتاج Android 11 أو أحدث")
            return
        }
        captureRunning = true
        fullChapterMode = true
        captureCount = 0
        previousFrame?.recycle()
        previousFrame = null
        capturedSegments.forEach { if (!it.isRecycled) it.recycle() }
        capturedSegments.clear()
        MangaFoxRuntime.status = "بدء التقاط الفصل العمودي..."
        removeFloatingBubble()
        mainHandler.postDelayed({ captureNextFrame() }, 350L)
    }

    private fun captureNextFrame() {
        if (!captureRunning || !fullChapterMode) return
        if (captureCount >= MAX_CAPTURE_FRAMES) {
            finishCaptureAndTranslate("تم الوصول إلى الحد الآمن (24 لقطة)، وهذه نتيجة جزئية إذا كان الفصل أطول")
            return
        }
        requestScreenshot { bitmap, error ->
            if (!captureRunning) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                if (capturedSegments.isNotEmpty()) {
                    finishCaptureAndTranslate("توقف الالتقاط: ${error ?: "تعذر أخذ لقطة أخرى"}")
                } else {
                    finishCaptureWithError(error ?: "تعذر التقاط الشاشة")
                }
                return@requestScreenshot
            }

            val previous = previousFrame
            if (previous != null && looksUnchanged(previous, bitmap)) {
                bitmap.recycle()
                finishCaptureAndTranslate("وصل الالتقاط إلى نهاية المحتوى أو لم يعد هناك تمرير جديد")
                return@requestScreenshot
            }

            val overlap = if (previous == null) 0f else 0.27f
            capturedSegments.add(cropContent(bitmap, overlap))
            previousFrame?.recycle()
            previousFrame = bitmap
            captureCount++
            MangaFoxRuntime.status = "التقاط الفصل: $captureCount من $MAX_CAPTURE_FRAMES"

            val height = bitmap.height.toFloat()
            val path = Path().apply {
                moveTo(bitmap.width * 0.52f, height * 0.84f)
                lineTo(bitmap.width * 0.52f, height * 0.18f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 520L))
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            if (!dispatched) {
                finishCaptureAndTranslate("تعذر تمرير التطبيق تلقائياً؛ تمت معالجة الأجزاء التي التقطت")
            } else {
                mainHandler.postDelayed({ captureNextFrame() }, 1250L)
            }
        }
    }

    private fun requestScreenshot(callback: (Bitmap?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null, "يلزم Android 11 أو أحدث")
            return
        }
        val screenshotCallback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                var bitmap: Bitmap? = null
                try {
                    val buffer: HardwareBuffer = screenshot.hardwareBuffer
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                } catch (e: Exception) {
                    callback(null, "تعذر تجهيز لقطة الشاشة: ${e.localizedMessage ?: "خطأ غير معروف"}")
                    return
                }
                if (bitmap == null) callback(null, "أعاد النظام لقطة شاشة فارغة") else callback(bitmap, null)
            }

            override fun onFailure(errorCode: Int) {
                val message = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "هذا التطبيق يمنع تصوير الشاشة (نافذة آمنة)"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "لا توجد صلاحية التقاط للشاشة"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "الالتقاط سريع جداً؛ أعد المحاولة"
                    else -> "فشل التقاط الشاشة، رمز الخطأ: $errorCode"
                }
                callback(null, message)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val activeWindow = windows.firstOrNull {
                    it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                if (activeWindow != null) {
                    takeScreenshotOfWindow(activeWindow.id, mainExecutor, screenshotCallback)
                } else {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, screenshotCallback)
                }
            } else {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, screenshotCallback)
            }
        } catch (e: Exception) {
            callback(null, "تعذر بدء الالتقاط: ${e.localizedMessage ?: "خطأ نظام"}")
        }
    }

    private fun cropContent(source: Bitmap, overlapFraction: Float): Bitmap {
        val top = (source.height * 0.035f).toInt().coerceAtMost(source.height - 2)
        val bottom = (source.height * 0.965f).toInt().coerceAtLeast(top + 1)
        val contentHeight = bottom - top
        val cut = (contentHeight * overlapFraction).toInt().coerceIn(0, contentHeight - 1)
        val cropped = Bitmap.createBitmap(source, 0, top + cut, source.width, contentHeight - cut)
        val maxWidth = 900
        if (cropped.width <= maxWidth) return cropped
        val targetHeight = (cropped.height * (maxWidth.toFloat() / cropped.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, targetHeight, true)
        cropped.recycle()
        return scaled
    }

    private fun looksUnchanged(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val a = Bitmap.createScaledBitmap(first, 28, 56, true)
        val b = Bitmap.createScaledBitmap(second, 28, 56, true)
        var difference = 0L
        for (y in 0 until 56) {
            for (x in 0 until 28) {
                val ca = a.getPixel(x, y)
                val cb = b.getPixel(x, y)
                difference += abs(Color.red(ca) - Color.red(cb))
                difference += abs(Color.green(ca) - Color.green(cb))
                difference += abs(Color.blue(ca) - Color.blue(cb))
            }
        }
        a.recycle()
        b.recycle()
        val average = difference.toDouble() / (28.0 * 56.0 * 3.0)
        return average < 1.35
    }

    private fun finishCaptureAndTranslate(note: String = "") {
        if (!captureRunning) return
        captureRunning = false
        fullChapterMode = false
        previousFrame?.recycle()
        previousFrame = null
        restoreFloatingBubble()
        if (capturedSegments.isEmpty()) {
            finishCaptureWithError("لم يتم التقاط أي جزء من الفصل")
            return
        }
        val workPages = ArrayList(capturedSegments)
        capturedSegments.clear()
        MangaFoxRuntime.status = "بدء استخراج النصوص وترجمتها..."
        if (note.isNotBlank()) MangaFoxRuntime.lastError = note

        worker.execute {
            val translatedPages = ArrayList<Bitmap>()
            try {
                workPages.forEachIndexed { index, page ->
                    MangaFoxRuntime.status = "تحليل وترجمة المقطع ${index + 1} من ${workPages.size}..."
                    translatedPages.add(translateBitmap(page))
                    if (!page.isRecycled) page.recycle()
                }
                MangaFoxRuntime.resultPages = translatedPages
                MangaFoxRuntime.resultTitle = if (workPages.size > 1) "الفصل العمودي" else "الصفحة الحالية"
                MangaFoxRuntime.status = "اكتملت الترجمة: ${translatedPages.size} مقطع"
                MangaFoxRuntime.lastError = note
                mainHandler.post {
                    val notified = notifyResultsReady()
                    toast(if (notified) "اكتملت الترجمة العربية. اضغط إشعار Manga Fox لعرض الفصل" else "اكتملت الترجمة. افتح Manga Fox ثم اختر عرض آخر نتيجة مترجمة")
                }
            } catch (e: Exception) {
                workPages.forEach { if (!it.isRecycled) it.recycle() }
                translatedPages.forEach { if (!it.isRecycled) it.recycle() }
                MangaFoxRuntime.resultPages = emptyList()
                MangaFoxRuntime.lastError = "حدث خطأ أثناء الترجمة: ${e.localizedMessage ?: "خطأ غير معروف"}"
                MangaFoxRuntime.status = "تعذرت الترجمة"
                mainHandler.post { toast(MangaFoxRuntime.lastError) }
            }
        }
    }

    private fun finishCaptureWithError(message: String) {
        captureRunning = false
        fullChapterMode = false
        previousFrame?.recycle()
        previousFrame = null
        capturedSegments.forEach { if (!it.isRecycled) it.recycle() }
        capturedSegments.clear()
        MangaFoxRuntime.status = "تعذر الالتقاط"
        MangaFoxRuntime.lastError = message
        restoreFloatingBubble()
        toast(message)
    }

    private data class TranslationLine(val bounds: Rect, val translated: String)

    private fun translateBitmap(source: Bitmap): Bitmap {
        val input = InputImage.fromBitmap(source, 0)
        val recognizedResults = recognizers.mapNotNull { recognizer ->
            runCatching { Tasks.await(recognizer.process(input)) }.getOrNull()
        }
        val best = recognizedResults.maxByOrNull { result -> result.text.length }
        val lines = best?.textBlocks?.flatMap { it.lines }?.filter { it.text.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) return source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")

        val joinedText = lines.joinToString(" ") { it.text }.take(2500)
        val detected = runCatching { Tasks.await(languageIdentifier.identifyLanguage(joinedText)) }.getOrDefault("en")
        var sourceLanguage = TranslateLanguage.fromLanguageTag(detected)
        if (sourceLanguage == null || sourceLanguage == TranslateLanguage.ARABIC) {
            sourceLanguage = TranslateLanguage.ENGLISH
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val translator: Translator = Translation.getClient(options)
        val translatedLines = ArrayList<TranslationLine>()
        try {
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            lines.forEach { line ->
                val bounds = line.boundingBox
                if (bounds != null) {
                    val translated = runCatching { Tasks.await(translator.translate(line.text)) }.getOrDefault(line.text)
                    translatedLines.add(TranslationLine(Rect(bounds), translated))
                }
            }
        } finally {
            translator.close()
        }
        if (translatedLines.isEmpty()) return source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        return drawTranslatedLines(source, translatedLines)
    }

    private fun drawTranslatedLines(source: Bitmap, translatedLines: List<TranslationLine>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("تعذر إنشاء صورة الترجمة")
        val canvas = Canvas(output)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        translatedLines.forEach { item ->
            val rect = Rect(item.bounds).apply {
                val margin = max(3, height() / 10)
                inset(-margin, -margin)
                left = left.coerceAtLeast(0)
                top = top.coerceAtLeast(0)
                right = right.coerceAtMost(output.width)
                bottom = bottom.coerceAtMost(output.height)
            }
            if (rect.width() < 8 || rect.height() < 8) return@forEach
            backgroundPaint.color = sampleBubbleColor(output, rect)
            canvas.drawRoundRect(
                rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
                5f, 5f, backgroundPaint
            )
            drawArabicText(canvas, item.translated, rect)
        }
        return output
    }

    private fun sampleBubbleColor(bitmap: Bitmap, rect: Rect): Int {
        val points = arrayOf(
            rect.left to (rect.top - 2).coerceAtLeast(0),
            rect.right - 1 to (rect.top - 2).coerceAtLeast(0),
            rect.left to (rect.bottom + 1).coerceAtMost(bitmap.height - 1),
            rect.right - 1 to (rect.bottom + 1).coerceAtMost(bitmap.height - 1)
        )
        var red = 0
        var green = 0
        var blue = 0
        points.forEach { (x, y) ->
            val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
        }
        red = red / points.size
        green = green / points.size
        blue = blue / points.size
        val brightness = (red + green + blue) / 3
        return if (brightness < 105) Color.rgb(245, 245, 238) else Color.rgb(
            ((red * 3 + 245) / 4).coerceIn(0, 255),
            ((green * 3 + 245) / 4).coerceIn(0, 255),
            ((blue * 3 + 238) / 4).coerceIn(0, 255)
        )
    }

    private fun drawArabicText(canvas: Canvas, text: String, rect: Rect) {
        val horizontalPadding = max(4, rect.width() / 20)
        val maxWidth = (rect.width() - horizontalPadding * 2).coerceAtLeast(12)
        var size = (rect.height() * 0.66f).coerceIn(10f,  thirtyPx())
        var layout = buildArabicLayout(text, maxWidth, size)
        while (layout.height > rect.height() && size > 9f) {
            size -= 1.5f
            layout = buildArabicLayout(text, maxWidth, size)
        }
        val x = rect.left + horizontalPadding
        val y = rect.top + ((rect.height() - layout.height) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(x.toFloat(), y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildArabicLayout(value: String, width: Int, size: Float): android.text.StaticLayout {
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 22)
            textSize = size
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        return android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .setMaxLines(4)
            .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
            .build()
    }

    private fun thirtyPx(): Float = 30f * resources.displayMetrics.density

    fun ensureFloatingBubble() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) showFloatingBubble()
    }

    private fun showFloatingBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        if (bubbleWasAdded || !this::windowManager.isInitialized) return
        val view = TextView(this).apply {
            text = "狐"
            textSize = 23f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(255, 190, 65))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            contentDescription = "Manga Fox Translator. اضغط لترجمة الجزء، واضغط مطولاً لترجمة الفصل"
        }
        val params = WindowManager.LayoutParams(
            dp(58), dp(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = (resources.displayMetrics.heightPixels * 0.36f).toInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (event.eventTime - event.downTime > 550L) startFullChapterCapture()
                        else startSingleCapture()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        bubble = view
        bubbleParams = params
        runCatching {
            windowManager.addView(view, params)
            bubbleWasAdded = true
        }.onFailure {
            bubble = null
            bubbleParams = null
            bubbleWasAdded = false
        }
    }

    private fun removeFloatingBubble() {
        val view = bubble ?: return
        if (bubbleWasAdded) runCatching { windowManager.removeView(view) }
        bubbleWasAdded = false
    }

    private fun restoreFloatingBubble() {
        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                if (bubble != null && !bubbleWasAdded) {
                    runCatching { windowManager.addView(bubble, bubbleParams) }.onSuccess { bubbleWasAdded = true }
                } else if (bubble == null) {
                    showFloatingBubble()
                }
            }
        }, 250L)
    }

    private fun notifyResultsReady(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "manga_fox_results"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "نتائج Manga Fox", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_RESULTS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            401,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Manga Fox")
            .setContentText("اكتملت الترجمة العربية · اضغط لعرض الفصل")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(401, notification)
            return true
        }
        return false
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
