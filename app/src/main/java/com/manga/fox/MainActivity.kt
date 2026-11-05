package com.manga.fox

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.Manifest
import android.app.Notification
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.provider.OpenableColumns
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
import android.speech.tts.TextToSpeech
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
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.HttpURLConnection
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream



// ============================================================
// MERGED FROM: MangaFox_V3_0_3_MainActivity.kt
// ============================================================

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
internal const val REQUEST_PICK_FONT = 713
internal const val REQUEST_SAVE_PDF = 714
internal const val REQUEST_EXPORT_PHANTOM_PROJECT = 715
internal const val REQUEST_IMPORT_PHANTOM_PROJECT = 716
internal const val PREF_LENS_SIZE = "lens_size_dp"
internal const val PREF_LENS_ALPHA = "lens_alpha"
internal const val PREF_LENS_GLYPH = "lens_custom_glyph"
internal const val PREF_LENS_COLOR = "lens_custom_color"
internal const val PREF_SOURCE_LANGUAGE = "translation_source_language"
internal const val PREF_ONBOARDING_SEEN = "onboarding_seen_v1"
internal const val PREF_TRANSLATE_SFX = "translate_japanese_sfx"
internal const val PREF_LEARNED_TRANSLATIONS = "learned_translation_dictionary"
internal const val PREF_AI_ZH_ENABLED = "ai_chinese_translation_enabled"
internal const val PREF_AI_ENDPOINT = "ai_translation_endpoint"
internal const val PREF_AI_MODEL = "ai_translation_model"
internal const val PREF_AI_SECRET_CIPHER = "ai_translation_secret_cipher"
internal const val PREF_AI_SECRET_IV = "ai_translation_secret_iv"
internal const val MAX_CAPTURE_FRAMES = 120

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
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var pendingSpeechText: String = ""
    private var homeVisible = false
    private val homeUiHandler = Handler(Looper.getMainLooper())
    private val homeUiTick = object : Runnable {
        override fun run() {
            if (!homeVisible) return
            refreshHomeIndicators()
            homeUiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        MangaFoxRuntime.restore(this)
        applySystemBars()
        handleIntent(intent)
        // Notification permission is requested from the onboarding checklist so the user sees why it is needed.
        homeUiHandler.postDelayed({ maybeShowOnboarding() }, 450L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(source: Intent?) {
        when (source?.action) {
            ACTION_SHOW_RESULTS -> showResults()
            ACTION_STOP_CAPTURE -> {
                showHome()
                MangaFoxAccessibilityService.instance?.stopCaptureByUser()
                    ?: toast("خدمة Manga Fox غير متصلة؛ أعد تفعيلها ثم حاول مرة أخرى")
            }
            else -> showHome()
        }
    }

    override fun onResume() {
        super.onResume()
        homeVisible = true
        homeUiHandler.removeCallbacks(homeUiTick)
        homeUiHandler.post(homeUiTick)
        refreshHomeIndicators()
        MangaFoxAccessibilityService.instance?.onHostActivityResumed()
    }

    override fun onPause() {
        homeVisible = false
        homeUiHandler.removeCallbacks(homeUiTick)
        MangaFoxAccessibilityService.instance?.onHostActivityPaused()
        super.onPause()
    }

    override fun onDestroy() {
        homeUiHandler.removeCallbacks(homeUiTick)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    /** Reads the saved Arabic translation layers in page and bubble order. */
    private fun speakResultsAloud() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
            toast("تم إيقاف القراءة الصوتية")
            return
        }
        val extracted = ArrayList<String>()
        MangaFoxRuntime.translationLayerFiles.forEach { path ->
            val file = path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile } ?: return@forEach
            val layers = runCatching { org.json.JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return@forEach
            for (index in 0 until layers.length()) {
                val translated = layers.optJSONObject(index)?.optString("translated", "")?.trim().orEmpty()
                if (translated.isNotBlank()) extracted.add(translated)
            }
        }
        val speechText = extracted.joinToString(". ")
        if (speechText.isBlank()) {
            toast("لا توجد طبقات ترجمة نصية لهذه النتيجة. أعد OCR والترجمة أولاً")
            return
        }
        pendingSpeechText = speechText
        val existing = textToSpeech
        if (existing != null && textToSpeechReady) {
            speakTextInChunks(speechText)
            return
        }
        if (existing == null) {
            textToSpeechReady = false
            textToSpeech = TextToSpeech(this) { status ->
                runOnUiThread {
                    val engine = textToSpeech
                    if (status != TextToSpeech.SUCCESS || engine == null) {
                        textToSpeechReady = false
                        toast("تعذر تشغيل محرك القراءة الصوتية على هذا الجهاز")
                        return@runOnUiThread
                    }
                    val languageStatus = engine.setLanguage(Locale("ar"))
                    if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                        languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                        textToSpeechReady = false
                        toast("محرك الصوت لا يدعم العربية حالياً؛ ثبّت بيانات الصوت العربي")
                        return@runOnUiThread
                    }
                    textToSpeechReady = true
                    speakTextInChunks(pendingSpeechText)
                }
            }
        } else {
            toast("يجري تجهيز محرك القراءة الصوتية")
        }
    }

    private fun speakTextInChunks(value: String) {
        val engine = textToSpeech ?: return
        if (!textToSpeechReady) return
        val chunks = ArrayList<String>()
        var remaining = value.trim()
        while (remaining.length > 3000) {
            var cut = remaining.lastIndexOf(' ', 3000).takeIf { it > 1200 } ?: 3000
            val sentenceCut = remaining.lastIndexOfAny(charArrayOf('.', '؟', '!', '؛'), cut)
            if (sentenceCut > 1200) cut = sentenceCut + 1
            chunks.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotBlank()) chunks.add(remaining)
        engine.stop()
        chunks.forEachIndexed { index, chunk ->
            val queue = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, queue, null, "mfox_read_$index")
        }
        toast("بدأت القراءة الصوتية للترجمة العربية")
    }

    private fun applySystemBars() {
        window.statusBarColor = dark
        window.navigationBarColor = dark
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    private fun setReadingFullscreen(enabled: Boolean) {
        readingFullscreen = enabled
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility = if (enabled) {
                (View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        if (enabled) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        } else {
            applySystemBars()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && readingFullscreen) setReadingFullscreen(true)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (readingFullscreen) showHome() else super.onBackPressed()
    }

    private fun showHome() {
        setReadingFullscreen(false)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(dark)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "MANGA FOX"
            textSize = 29f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(gold)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "ترجمة المانجا والمانهوا أثناء القراءة"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(16))
        }, matchWrap())

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(panel, 16)
        }
        val tabsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        listOf(
            "translation" to "ترجمة", "studio" to "استوديو", "quality" to "الجودة",
            "knowledge" to "المعجم", "settings" to "الإعدادات", "library" to "المكتبة"
        ).forEach { (key, label) ->
            val tab = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                setTextColor(if (selectedHomeTab == key) Color.BLACK else Color.LTGRAY)
                background = rounded(if (selectedHomeTab == key) gold else panel, 12)
                setOnClickListener {
                    selectedHomeTab = key
                    updateHomeTabs(tabs)
                    renderHomeTab()
                }
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(46)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            }
            tabs.addView(tab)
        }
        root.addView(tabsScroll, matchWrap(dp(12)))
        homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(homeContent, matchWrap())
        root.addView(TextView(this).apply {
            text = "MANGA FOX  ·  N5CR4  ·  محرك 3.0.6b"
            textSize = 12f
            setTextColor(gold)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "حقوقي · N5CR4  ↗"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(gold)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/N5CR4"))) }
                    .onFailure { toast("تعذر فتح تيليجرام") }
            }
        }, matchWrap())
        scroll.addView(root)
        setContentView(scroll)
        renderHomeTab()
        refreshHomeIndicators()
    }

    private fun updateHomeTabs(tabs: LinearLayout) {
        val items = listOf(
            "translation" to "ترجمة", "studio" to "استوديو", "quality" to "الجودة",
            "knowledge" to "المعجم", "settings" to "الإعدادات", "library" to "المكتبة"
        )
        tabs.removeAllViews()
        items.forEach { (key, label) ->
            tabs.addView(Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                setTextColor(if (selectedHomeTab == key) Color.BLACK else Color.LTGRAY)
                background = rounded(if (selectedHomeTab == key) gold else panel, 12)
                setOnClickListener {
                    selectedHomeTab = key
                    updateHomeTabs(tabs)
                    renderHomeTab()
                }
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(46)).apply {
                    marginStart = dp(3); marginEnd = dp(3)
                }
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
        intro.addView(text("التقط لقطات متتابعة أثناء القراءة، ثم اجمع النصوص من أكثر من محرك OCR وحدد لغة كل كتلة بشكل مستقل قبل ترجمتها إلى العربية.", 13f, Color.LTGRAY))
        intro.addView(text("تعمل المعالجة على الجهاز بعد تنزيل نماذج اللغات. يلزم الإنترنت لتنزيل النموذج أول مرة.", 12f, Color.rgb(195, 185, 165)))
        root.addView(intro, matchWrap(dp(12)))
        root.addView(actionButton("☰  لوحة FOX الجانبية السريعة", false) {
            showQuickSidePanel()
        }, matchWrap(dp(12)))

        val onboarding = card()
        onboarding.addView(text("دليل التفعيل والتحقق التلقائي", 17f, Color.WHITE, true))
        onboarding.addView(text("فعّل الأذونات التالية. تتحدث علاماتها تلقائياً عند الرجوع إلى التطبيق.", 12f, Color.LTGRAY))
        accessPermissionText = text("", 13f, Color.LTGRAY)
        overlayPermissionText = text("", 13f, Color.LTGRAY)
        notificationPermissionText = text("", 13f, Color.LTGRAY)
        onboarding.addView(accessPermissionText, matchWrap())
        onboarding.addView(actionButton("الخطوة 1 · فتح إعدادات خدمة إمكانية الوصول", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap(dp(5)))
        onboarding.addView(overlayPermissionText, matchWrap())
        onboarding.addView(actionButton("الخطوة 2 · السماح بالظهور فوق التطبيقات", false) {
            openOverlayPermission()
        }, matchWrap(dp(5)))
        onboarding.addView(notificationPermissionText, matchWrap())
        onboarding.addView(actionButton("الخطوة 3 · تفعيل الإشعارات", false) {
            requestNotificationPermission()
        }, matchWrap(dp(5)))
        root.addView(onboarding, matchWrap(dp(12)))

        val actions = card()
        actions.addView(text("الترجمة", 18f, Color.WHITE, true))
        actions.addView(actionButton("ترجمة الشاشة الحالية", true) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("فعّل خدمة Manga Fox أولاً") else {
                service.startSingleCapture(900L)
                window.decorView.postDelayed({ moveTaskToBack(true) }, 120L)
            }
        }, matchWrap(dp(5)))
        actions.addView(actionButton("ترجمة الفصل العمودي كاملاً", true) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("فعّل خدمة Manga Fox أولاً، ثم افتح فصل المانجا") else {
                service.startFullChapterCapture(1300L)
                window.decorView.postDelayed({ moveTaskToBack(true) }, 120L)
            }
        }, matchWrap(dp(5)))
        actions.addView(actionButton("التقاط الفصل دون ترجمة · PDF", false) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("فعّل خدمة Manga Fox أولاً، ثم افتح فصل المانجا") else {
                service.startFullChapterCaptureOnly(1300L)
                window.decorView.postDelayed({ moveTaskToBack(true) }, 120L)
            }
        }, matchWrap(dp(5)))
        actions.addView(actionButton("إيقاف الالتقاط الحالي", false) {
            MangaFoxAccessibilityService.instance?.let { it.stopCaptureByUser() } ?: toast("خدمة Manga Fox غير متصلة")
        }, matchWrap(dp(5)))
        actions.addView(actionButton("عرض آخر نتيجة مترجمة", false) { showResults() }, matchWrap())
        root.addView(actions, matchWrap(dp(12)))

        val progressCard = card()
        progressCard.addView(text("تقدم المعالجة داخل التطبيق", 16f, Color.WHITE, true))
        progressText = text("", 12f, Color.LTGRAY)
        progressCard.addView(progressText, matchWrap(dp(5)))
        homeProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = MangaFoxRuntime.progressPercent.coerceIn(0, 100)
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

    /** A compact, app-native side drawer with one-tap capture and export actions. */
    private fun showQuickSidePanel() {
        val dialog = android.app.Dialog(this)
        val panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(Color.rgb(20, 20, 25))
        }
        panelView.addView(text("FOX · لوحة جانبية", 20f, gold, true), matchWrap(dp(12)))
        panelView.addView(text("اختصارات سريعة دون التنقل بين التبويبات.", 12f, Color.LTGRAY), matchWrap(dp(12)))
        fun item(label: String, action: () -> Unit) {
            panelView.addView(actionButton(label, false) { dialog.dismiss(); action() }, matchWrap(dp(7)))
        }
        item("التقاط الشاشة الحالية · ترجمة") {
            MangaFoxAccessibilityService.instance?.startSingleCapture(250L) ?: toast("فعّل خدمة Manga FOX أولاً")
            moveTaskToBack(true)
        }
        item("التقاط الفصل · ترجمة") {
            MangaFoxAccessibilityService.instance?.startFullChapterCapture(500L) ?: toast("فعّل خدمة Manga FOX أولاً")
            moveTaskToBack(true)
        }
        item("التقاط الفصل · دون ترجمة") {
            MangaFoxAccessibilityService.instance?.startFullChapterCaptureOnly(500L) ?: toast("فعّل خدمة Manga FOX أولاً")
            moveTaskToBack(true)
        }
        item("إيقاف الالتقاط") { MangaFoxAccessibilityService.instance?.stopCaptureByUser() }
        item("مركز الالتقاط") { showCaptureHub() }
        item("فتح النتائج") { showResults() }
        item("تصدير PDF") { exportResultsAsPdf() }
        item("إعدادات العدسة") { selectedHomeTab = "settings"; showHome() }
        item("إغلاق") {}
        dialog.setContentView(panelView)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(320), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.START)
            attributes = attributes.apply { dimAmount = 0.55f; flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND }
        }
        dialog.show()
        dialog.window?.setLayout(dp(320), WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun appStorageBytes(): Long {
        return runCatching {
            fun size(file: File): Long = if (!file.exists()) 0L else if (file.isFile) file.length()
                else file.listFiles()?.sumOf(::size) ?: 0L
            size(filesDir) + size(cacheDir)
        }.getOrDefault(0L)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun showStorageAnalyzer() {
        val root = File(filesDir, ".")
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val total = files.sumOf { it.length() }
        val images = files.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.sumOf { it.length() }
        val pdf = files.filter { it.extension.equals("pdf", true) }.sumOf { it.length() }
        val archives = files.filter { it.extension.equals("zip", true) }.sumOf { it.length() }
        AlertDialog.Builder(this).setTitle("تحليل التخزين · FOX")
            .setMessage("إجمالي ملفات التطبيق: ${formatBytes(total)}\nصور: ${formatBytes(images)}\nPDF: ${formatBytes(pdf)}\nZIP: ${formatBytes(archives)}\nعدد الملفات: ${files.size}")
            .setPositiveButton("حسنًا", null).show()
    }

    private fun showCaptureHub() {
        val pages = MangaFoxRuntime.resultPages
        if (pages.isEmpty()) { toast("لا توجد صفحات في الجلسة الحالية"); return }
        val labels = pages.mapIndexed { index, path ->
            val file = File(path)
            "${(index + 1).toString().padStart(3, '0')} · ${file.name} · ${formatBytes(file.length())}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Capture Hub · ${pages.size} صفحة")
            .setItems(labels) { _, index ->
                val pageFile = File(pages[index])
                val bitmap = BitmapFactory.decodeFile(pageFile.absolutePath)
                if (bitmap == null) toast("تعذر فتح الصفحة")
                else {
                    val preview = ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.BLACK)
                    }
                    AlertDialog.Builder(this).setTitle("صفحة ${index + 1}")
                        .setView(preview).setPositiveButton("إغلاق") { _, _ -> bitmap.recycle() }
                        .setNeutralButton("فتح النتائج") { _, _ -> bitmap.recycle(); showResults() }
                        .show()
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun addLibraryTab(root: LinearLayout) {
        val library = card()
        library.addView(text("مكتبة النتائج · Capture Hub", 19f, gold, true))
        library.addView(text("آخر فصل: ${MangaFoxRuntime.resultTitle}", 13f, Color.LTGRAY))
        val captureHub = card()
        captureHub.addView(text("مركز الالتقاط", 16f, gold, true))
        captureHub.addView(text("الصفحات: ${MangaFoxRuntime.resultPages.size}  •  الوضع: ${if (MangaFoxRuntime.captureOnlyResult) "صور أصلية بلا ترجمة" else "نتيجة معالجة"}", 13f, Color.LTGRAY))
        captureHub.addView(text("المساحة التقريبية لملفات التطبيق: ${formatBytes(appStorageBytes())}", 12f, Color.rgb(190,190,200)))
        captureHub.addView(actionButton("معاينة قائمة الصفحات", true) { showCaptureHub() }, matchWrap(dp(5)))
        captureHub.addView(actionButton("تحليل مساحة التخزين", false) { showStorageAnalyzer() }, matchWrap())
        root.addView(captureHub, matchWrap(dp(10)))
        library.addView(text("عدد المقاطع: ${MangaFoxRuntime.resultPages.size}", 13f, Color.LTGRAY))
        library.addView(text("تقرير الجودة: ${MangaFoxRuntime.qualitySummary}", 13f, Color.rgb(235, 210, 160)))
        library.addView(actionButton("فتح آخر نتيجة", true) { showResults() }, matchWrap(dp(6)))
        library.addView(actionButton("تصدير آخر نتيجة إلى PDF", false) {
            if (MangaFoxRuntime.resultPages.isEmpty()) toast("لا توجد نتيجة لتصديرها") else exportResultsAsPdf()
        }, matchWrap(dp(6)))
        library.addView(actionButton("تحديث الخط والحجم دون OCR", false) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("خدمة Manga Fox غير متصلة") else service.rerenderLastResultsFromLayers { showResults() }
        }, matchWrap(dp(6)))
        library.addView(actionButton("إعادة OCR والترجمة من الصور الأصلية", false) {
            MangaFoxAccessibilityService.instance?.retranslateLastResults()
                ?: toast("خدمة Manga Fox غير متصلة")
        }, matchWrap(dp(6)))
        library.addView(actionButton("نسخ احتياطي كامل للمشروع (الصور + الطبقات + المعجم)", true) {
            launchProjectExport()
        }, matchWrap(dp(6)))
        library.addView(actionButton("استعادة مشروع من نسخة احتياطية", false) {
            launchProjectImport()
        }, matchWrap(dp(6)))
        library.addView(actionButton("فحص جودة الفصل قبل التصدير", false) { showQualityAudit() }, matchWrap(dp(6)))
        library.addView(actionButton("مراجعة الكتل التي فشلت", false) {
            val samples = MangaFoxRuntime.failedBlockSamples.take(25)
            if (samples.isEmpty()) {
                AlertDialog.Builder(this).setTitle("عينات فشل OCR / الترجمة")
                    .setMessage("لا توجد عينات فشل مسجلة.").setPositiveButton("إغلاق", null).show()
            } else {
                AlertDialog.Builder(this).setTitle("اختر النص لتصحيح ترجمته")
                    .setItems(samples.toTypedArray()) { _, which -> showLearnedTranslationDialog(samples[which]) }
                    .setNeutralButton("إعادة معالجة الفصل") { _, _ ->
                        MangaFoxAccessibilityService.instance?.retranslateLastResults() ?: toast("خدمة Manga Fox غير متصلة")
                    }.setNegativeButton("إغلاق", null).show()
            }
        }, matchWrap())
        root.addView(library, matchWrap(dp(12)))
        root.addView(text("تُحفظ نتائج الجلسة الحالية داخل التطبيق. احفظ نسخة PDF للاحتفاظ بالفصل خارج التطبيق.", 12f, Color.GRAY), matchWrap())
    }

    private fun addPhantomStudioTab(root: LinearLayout) {
        val header = card()
        header.addView(text("PHANTOM STUDIO · استوديو مانجا FOX", 19f, gold, true))
        header.addView(text("مساحة موحدة للتحرير، إدارة المشروع، أنماط الكتابة، الترميم، والمعالجة الجماعية. لا تُغيّر الصورة الأصلية؛ تعديلاتك تحفظ كطبقات مستقلة.", 13f, Color.LTGRAY))
        root.addView(header, matchWrap(dp(10)))

        val project = card()
        project.addView(text("إدارة المشروع", 16f, Color.WHITE, true))
        project.addView(actionButton("نسخ احتياطي كامل للمشروع", true) { launchProjectExport() }, matchWrap(dp(5)))
        project.addView(actionButton("استعادة مشروع من ZIP", false) { launchProjectImport() }, matchWrap(dp(5)))
        project.addView(actionButton("فتح نتائج الفصل الحالية", false) { showResults() }, matchWrap(dp(5)))
        project.addView(actionButton("تصدير PDF متصل الصفحات", false) { exportResultsAsPdf() }, matchWrap())
        root.addView(project, matchWrap(dp(10)))

        val editing = card()
        editing.addView(text("محرر الصفحات والطبقات", 16f, Color.WHITE, true))
        editing.addView(text("يمكنك تحديد منطقة يدوياً، إضافة فقاعة أو صندوق سرد، مسح نص، تعديل اتجاهه، وتحريك الطبقة. زر التراجع/الإعادة يظهر أسفل كل صفحة في شاشة النتائج.", 12f, Color.LTGRAY))
        editing.addView(actionButton("فتح محرر آخر فصل", true) {
            if (MangaFoxRuntime.resultPages.isEmpty()) toast("لا توجد صفحات محررة بعد") else showResults()
        }, matchWrap(dp(5)))
        editing.addView(actionButton("إعادة تطبيق الخط والحجم دون OCR", false) {
            MangaFoxAccessibilityService.instance?.rerenderLastResultsFromLayers { showResults() }
                ?: toast("خدمة Manga FOX غير متصلة")
        }, matchWrap())
        root.addView(editing, matchWrap(dp(10)))

        val processing = card()
        processing.addView(text("المعالجة الجماعية والمؤثرات", 16f, Color.WHITE, true))
        processing.addView(text("ترجمة الفصل المتتابع متاحة من تبويب ترجمة. تفعيل ترجمة المؤثرات الصوتية يضيف قاموس SFX المعروف إلى المعالجة؛ الكلمات غير المعروفة تظل بحاجة للمراجعة.", 12f, Color.LTGRAY))
        val sfx = CheckBox(this).apply {
            text = "ترجمة المؤثرات الصوتية اليابانية والكورية المعروفة"
            setTextColor(Color.LTGRAY)
            isChecked = preferences.getBoolean(PREF_TRANSLATE_SFX, false)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean(PREF_TRANSLATE_SFX, checked).apply() }
        }
        processing.addView(sfx, matchWrap(dp(5)))
        processing.addView(actionButton("إعادة ترجمة الفصل من الصور الأصلية", false) {
            MangaFoxAccessibilityService.instance?.retranslateLastResults() ?: toast("خدمة Manga FOX غير متصلة")
        }, matchWrap())
        root.addView(processing, matchWrap(dp(10)))

        val styles = card()
        styles.addView(text("أنماط الترجمة المحفوظة", 16f, Color.WHITE, true))
        styles.addView(text("احفظ إعدادات الخط الحالية كقالب ثم أعد تطبيقها على الفصول القادمة.", 12f, Color.LTGRAY))
        styles.addView(actionButton("حفظ الإعدادات الحالية كقالب", true) { saveStyleProfileDialog() }, matchWrap(dp(5)))
        styles.addView(actionButton("تطبيق قالب محفوظ", false) { applyStyleProfileDialog() }, matchWrap())
        root.addView(styles, matchWrap(dp(10)))

        val ai = card()
        ai.addView(text("حزم نماذج الذكاء المحلي", 16f, Color.WHITE, true))
        ai.addView(text("محرك ML Kit الحالي يقرأ اللغات التي توفرها recognizers المضافة. لإضافة تعرف عربي متخصص نحتاج تضمين نموذج Arabic recognition مع كاشف النص وSDK الاستدلال. لم تُضمّن أوزان نموذج غير موجودة داخل التطبيق.", 12f, Color.LTGRAY))
        ai.addView(actionButton("تفاصيل حزمة OCR العربية واللغات", false) { showOcrModelInfo() }, matchWrap())
        root.addView(ai, matchWrap(dp(10)))
    }

    private fun addQualityTab(root: LinearLayout) {
        val panel = card()
        panel.addView(text("FOX QUALITY GUARD", 19f, gold, true))
        panel.addView(text("فحص تلقائي لإحداثيات الطبقات، التداخلات، النصوص الفارغة، والطبقات التي قد تحتفظ بحروف لاتينية. الفحص البنيوي لا يستبدل مراجعة الصورة ولا يدّعي فهم كل كلمة داخل الرسم.", 13f, Color.LTGRAY))
        panel.addView(actionButton("فحص الفصل الحالي الآن", true) { showQualityAudit() }, matchWrap(dp(6)))
        panel.addView(text("ملخص المحرك الحالي: ${MangaFoxRuntime.qualitySummary}", 12f, Color.rgb(235, 210, 160)), matchWrap())
        root.addView(panel, matchWrap(dp(10)))
        root.addView(text("الصفحة التي تحتوي خطأً حرجاً يجب مراجعتها قبل التصدير. تحذيرات النص اللاتيني مجرد إشارة مراجعة وليست دليلاً قاطعاً على فشل الترجمة؛ قد تكون اسماً أو مؤثراً صوتياً.", 12f, Color.LTGRAY), matchWrap())
    }

    private fun addKnowledgeTab(root: LinearLayout) {
        val glossary = MangaFoxKnowledgeStore(this).glossary()
        val characters = MangaFoxKnowledgeStore(this).characters()
        val dictionaryCard = card()
        dictionaryCard.addView(text("قاموس المصطلحات الموحد", 17f, gold, true))
        dictionaryCard.addView(text("احفظ ترجمة ثابتة لعبارة كاملة لتستخدمها في الفصول التالية. التطابق الكامل يُطبّق كترجمة محفوظة؛ لا يتم استبدال أجزاء الكلمات عشوائياً.", 12f, Color.LTGRAY))
        dictionaryCard.addView(text("عدد المصطلحات: ${glossary.size}", 13f, Color.WHITE))
        dictionaryCard.addView(actionButton("إضافة / إدارة المصطلحات", true) { manageGlossary() }, matchWrap())
        root.addView(dictionaryCard, matchWrap(dp(10)))

        val characterCard = card()
        characterCard.addView(text("ذاكرة الشخصيات وأسلوب الحوار", 17f, gold, true))
        characterCard.addView(text("احفظ أسماء الشخصيات ونبرة كلامها وملاحظات الضمائر. هذه ذاكرة مرجعية محلية للمترجم والمراجع، ولا تدّعي تغيير أسلوب محرك ML Kit الآلي.", 12f, Color.LTGRAY))
        characterCard.addView(text("عدد ملفات الشخصيات: ${characters.size}", 13f, Color.WHITE))
        characterCard.addView(actionButton("إضافة / إدارة الشخصيات", true) { manageCharacters() }, matchWrap())
        root.addView(characterCard, matchWrap(dp(10)))
        root.addView(actionButton("تصدير المشروع مع المعجم وذاكرة الشخصيات", false) { launchProjectExport() }, matchWrap())
    }

    private fun showQualityAudit() {
        if (MangaFoxRuntime.resultPages.isEmpty()) { toast("لا توجد صفحات لفحصها"); return }
        val report = MangaFoxQualityGuard.inspect(MangaFoxRuntime.resultPages, MangaFoxRuntime.translationLayerFiles)
        val message = report.toArabicText()
        AlertDialog.Builder(this)
            .setTitle("FOX QUALITY GUARD · ${report.errorCount} خطأ · ${report.warningCount} تحذير")
            .setMessage(message)
            .setPositiveButton("مفهوم", null)
            .setNeutralButton("فتح النتائج") { _, _ -> showResults() }
            .show()
    }

    private fun manageGlossary() {
        val store = MangaFoxKnowledgeStore(this)
        val entries = store.glossary()
        val labels = entries.map { "${it.source}  ←  ${it.arabic}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("قاموس المصطلحات · ${entries.size}")
            .setItems(labels) { _, which ->
                val entry = entries[which]
                AlertDialog.Builder(this).setTitle(entry.source)
                    .setMessage("الترجمة: ${entry.arabic}\n${entry.note}")
                    .setPositiveButton("حذف") { _, _ -> store.deleteGlossaryEntry(entry.source); renderHomeTab() }
                    .setNeutralButton("تعديل") { _, _ -> editGlossaryEntry(entry) }
                    .setNegativeButton("إغلاق", null).show()
            }
            .setPositiveButton("إضافة مصطلح") { _, _ -> editGlossaryEntry(null) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun editGlossaryEntry(initial: MangaFoxKnowledgeStore.GlossaryEntry?) {
        val source = EditText(this).apply { hint = "المصطلح الأصلي"; setText(initial?.source.orEmpty()) }
        val arabic = EditText(this).apply { hint = "المقابل العربي"; setText(initial?.arabic.orEmpty()) }
        val note = EditText(this).apply { hint = "ملاحظة اختيارية"; setText(initial?.note.orEmpty()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)); addView(source); addView(arabic); addView(note) }
        AlertDialog.Builder(this).setTitle("مصطلح موحد").setView(box)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val a = source.text.toString().trim(); val b = arabic.text.toString().trim()
                if (a.isBlank() || b.isBlank()) toast("أدخل المصطلح والترجمة")
                else { MangaFoxKnowledgeStore(this).saveGlossaryEntry(MangaFoxKnowledgeStore.GlossaryEntry(a, b, note.text.toString().trim())); renderHomeTab(); toast("تم حفظ المصطلح") }
            }.show()
    }

    private fun manageCharacters() {
        val store = MangaFoxKnowledgeStore(this)
        val entries = store.characters()
        AlertDialog.Builder(this).setTitle("ذاكرة الشخصيات · ${entries.size}")
            .setItems(entries.map { "${it.name} · ${it.voice}" }.toTypedArray()) { _, which ->
                val item = entries[which]
                AlertDialog.Builder(this).setTitle(item.name).setMessage("النبرة: ${item.voice}\nملاحظات: ${item.notes}")
                    .setPositiveButton("حذف") { _, _ -> store.deleteCharacter(item.name); renderHomeTab() }
                    .setNeutralButton("تعديل") { _, _ -> editCharacter(item) }.setNegativeButton("إغلاق", null).show()
            }
            .setPositiveButton("إضافة شخصية") { _, _ -> editCharacter(null) }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun editCharacter(initial: MangaFoxKnowledgeStore.CharacterEntry?) {
        val name = EditText(this).apply { hint = "اسم الشخصية"; setText(initial?.name.orEmpty()) }
        val voice = EditText(this).apply { hint = "نبرة الكلام: رسمية / ساخرة / حادة..."; setText(initial?.voice.orEmpty()) }
        val notes = EditText(this).apply { hint = "الضمائر والملاحظات والسياق"; minLines = 3; setText(initial?.notes.orEmpty()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)); addView(name); addView(voice); addView(notes) }
        AlertDialog.Builder(this).setTitle("بطاقة شخصية").setView(box)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isBlank()) toast("اسم الشخصية مطلوب") else {
                    MangaFoxKnowledgeStore(this).saveCharacter(MangaFoxKnowledgeStore.CharacterEntry(n, voice.text.toString().trim(), notes.text.toString().trim()))
                    renderHomeTab(); toast("تم حفظ بطاقة الشخصية")
                }
            }.show()
    }

    private fun saveStyleProfileDialog() {
        val input = EditText(this).apply { hint = "اسم القالب: رومانسي / أكشن / سرد..." }
        AlertDialog.Builder(this).setTitle("حفظ نمط الترجمة").setMessage("سيتم حفظ حجم الخط واسمه ووزنه من إعدادات التطبيق الحالية.")
            .setView(input).setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ القالب") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) { toast("اكتب اسماً للقالب"); return@setPositiveButton }
                val json = runCatching { org.json.JSONObject(preferences.getString("mfox_style_profiles_v1", "{}") ?: "{}") }.getOrElse { org.json.JSONObject() }
                json.put(name, org.json.JSONObject()
                    .put("fontSize", preferences.getInt(PREF_FONT_SIZE, 22))
                    .put("fontFamily", preferences.getString(PREF_FONT_FAMILY, "sans-serif"))
                    .put("fontBold", preferences.getBoolean(PREF_FONT_BOLD, false))
                    .put("fontFile", preferences.getString(PREF_FONT_FILE, ""))
                    .put("autoFit", preferences.getBoolean(PREF_AUTO_FONT_FIT, true)))
                preferences.edit().putString("mfox_style_profiles_v1", json.toString()).apply()
                toast("تم حفظ قالب $name")
            }.show()
    }

    private fun applyStyleProfileDialog() {
        val json = runCatching { org.json.JSONObject(preferences.getString("mfox_style_profiles_v1", "{}") ?: "{}") }.getOrElse { org.json.JSONObject() }
        val names = ArrayList<String>()
        val keyIterator = json.keys()
        while (keyIterator.hasNext()) names.add(keyIterator.next())
        if (names.isEmpty()) { toast("لا توجد قوالب محفوظة بعد"); return }
        AlertDialog.Builder(this).setTitle("اختر قالب الترجمة").setItems(names.toTypedArray()) { _, which ->
            val item = json.optJSONObject(names[which]) ?: return@setItems
            preferences.edit()
                .putInt(PREF_FONT_SIZE, item.optInt("fontSize", 22).coerceIn(10, 72))
                .putString(PREF_FONT_FAMILY, item.optString("fontFamily", "sans-serif"))
                .putBoolean(PREF_FONT_BOLD, item.optBoolean("fontBold", false))
                .putString(PREF_FONT_FILE, item.optString("fontFile").takeIf { it.isNotBlank() })
                .putBoolean(PREF_AUTO_FONT_FIT, item.optBoolean("autoFit", true))
                .apply()
            updateFontPreview()
            toast("تم تطبيق القالب. أعد إخراج الطبقات لتحديث الصفحات الحالية.")
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun showLayerHistoryDialog(pageIndex: Int) {
        val pair = readLayerArray(pageIndex)
        if (pair == null) { toast("لا توجد طبقات قابلة للتراجع"); return }
        val (file, _) = pair
        AlertDialog.Builder(this).setTitle("سجل تعديلات الصفحة ${pageIndex + 1}")
            .setItems(arrayOf("تراجع عن آخر تعديل", "إعادة آخر تعديل متراجع عنه")) { _, which ->
                val history = MangaFoxLayerHistory(this)
                val changed = if (which == 0) history.undo(pageIndex, file) else history.redo(pageIndex, file)
                if (changed) {
                    MangaFoxRuntime.persist(this)
                    MangaFoxAccessibilityService.instance?.rerenderLastResultsFromLayers { showResults() }
                        ?: run { toast("تمت استعادة ملف الطبقات؛ افتح خدمة Manga FOX لإعادة الرسم") }
                } else toast(if (which == 0) "لا توجد خطوة سابقة للتراجع" else "لا توجد خطوة لإعادتها")
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun launchProjectExport() {
        if (MangaFoxRuntime.resultPages.isEmpty()) { toast("لا يوجد مشروع لحفظه"); return }
        toast("جارٍ تجهيز نسخة المشروع...")
        fontDownloadExecutor.execute {
            var archive: File? = null
            try {
                val ready = File(cacheDir, "mfox_phantom_${System.currentTimeMillis()}.zip")
                archive = ready
                ready.outputStream().use { MangaFoxProjectArchive.export(this, it) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) { ready.delete(); return@runOnUiThread }
                    pendingProjectArchive = ready
                    val title = MangaFoxRuntime.resultTitle.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(48).ifBlank { "MangaFox_Project" }
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "${title}_PHANTOM.zip")
                    }
                    runCatching { startActivityForResult(intent, REQUEST_EXPORT_PHANTOM_PROJECT) }
                        .onFailure { pendingProjectArchive = null; ready.delete(); toast("تعذر فتح نافذة حفظ الملف") }
                }
            } catch (error: Exception) {
                archive?.delete()
                runOnUiThread { toast("فشل تجهيز النسخة: ${error.localizedMessage ?: "خطأ"}") }
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

    private fun showOcrModelInfo() {
        AlertDialog.Builder(this).setTitle("حزم OCR متعددة اللغات")
            .setMessage("الأساس الحالي: ML Kit للغات التي تغطيها محركات المشروع. التوسعة العربية المقترحة تعتمد PP-OCRv5 Arabic recognition مع كاشف النص وSDK Android/ONNX.\n\nهذه النسخة لا تحتوي ملفات أوزان ONNX ولا تدّعي تشغيل OCR عربي متخصص بعد؛ لا بد من تنزيل ملفات النماذج الرسمية والتحقق من ترخيصها وحجمها ثم دمج مرحلة الاستدلال واختبارها على صور المانجا.")
            .setPositiveButton("مفهوم", null).show()
    }

    private fun maybeShowOnboarding() {
        if (isFinishing || intent?.action == ACTION_SHOW_RESULTS || preferences.getBoolean(PREF_ONBOARDING_SEEN, false)) return
        AlertDialog.Builder(this)
            .setTitle("مرحباً بك في Manga Fox")
            .setMessage("قبل أول ترجمة، فعّل خدمة إمكانية الوصول وإذن الظهور فوق التطبيقات، ثم ارجع إلى تبويب ترجمة للتحقق من حالتهما. العدسة ستبقى ظاهرة ما دامت الخدمة والإذن يعملان.")
            .setPositiveButton("عرض خطوات التفعيل") { _, _ ->
                preferences.edit().putBoolean(PREF_ONBOARDING_SEEN, true).apply()
                selectedHomeTab = "translation"
                showHome()
            }
            .setNegativeButton("لاحقاً") { _, _ -> preferences.edit().putBoolean(PREF_ONBOARDING_SEEN, true).apply() }
            .show()
    }

    private fun openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else toast("إذن الظهور فوق التطبيقات مفعّل")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        } else toast("إذن الإشعارات مفعّل أو غير مطلوب على هذا الإصدار")
    }

    private fun refreshHomeIndicators() {
        val access = isAccessibilityEnabled()
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        accessPermissionText?.text = "${if (access) "✓" else "○"} خدمة إمكانية الوصول: ${if (access) "مفعّلة" else "غير مفعّلة"}"
        overlayPermissionText?.text = "${if (overlay) "✓" else "○"} الظهور فوق التطبيقات: ${if (overlay) "مسموح" else "يحتاج إذناً"}"
        notificationPermissionText?.text = "${if (notifications) "✓" else "○"} الإشعارات: ${if (notifications) "مفعّلة" else "غير مفعّلة"}"
        statusText?.text = "خدمة الوصول: ${if (access) "مفعّلة" else "غير مفعّلة"}\n" +
            "النافذة العائمة: ${if (overlay) "مسموح بها" else "تحتاج إذناً"}\n" +
            "الحالة الحالية: ${MangaFoxRuntime.status}"
        homeProgress?.progress = MangaFoxRuntime.progressPercent.coerceIn(0, 100)
        progressText?.text = "${MangaFoxRuntime.progressPercent.coerceIn(0, 100)}% · ${MangaFoxRuntime.progressDetail.ifBlank { MangaFoxRuntime.status }}"
    }

    private fun addSettingsCard(root: LinearLayout) {
        val settingsCard = card()
        settingsCard.addView(text("إعدادات القراءة والترجمة", 18f, Color.WHITE, true))
        settingsCard.addView(text("عاين شكل الحروف مباشرة، وغيّر الحجم ونوع الخط ووزنه الحقيقي عبر نمط الخط. يمكنك تنزيل خطوط عربية أو استيراد ملف TTF / OTF من هاتفك.", 12f, Color.LTGRAY))
        settingsCard.addView(text("ملاحظة: بعد تعديل الخط افتح النتيجة واضغط «إعادة تطبيق الخط والحجم» لإعادة إخراجها من الطبقات دون تشغيل OCR.", 11f, gold))

        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(242, 239, 230), 14)
        }
        previewPanel.addView(TextView(this).apply {
            text = "معاينة الخط"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }, matchWrap())
        fontPreview = TextView(this).apply {
            text = "هذا مثال لمعاينة الخط العربي\nالقراءة والترجمة في Manga Fox"
            textSize = preferences.getInt(PREF_FONT_SIZE, 22).toFloat()
            setTextColor(Color.rgb(25, 25, 28))
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            minHeight = dp(82)
        }
        previewPanel.addView(fontPreview, matchWrap())
        settingsCard.addView(previewPanel, matchWrap(dp(10)))

        addSettingSlider(
            settingsCard, "حجم الخط العربي", 10, 72,
            preferences.getInt(PREF_FONT_SIZE, 22), " sp"
        ) { value ->
            preferences.edit().putInt(PREF_FONT_SIZE, value).apply()
            updateFontPreview()
        }
        val fontFamilies = listOf(
            "sans-serif" to "نسخي حديث",
            "serif" to "تقليدي",
            "sans-serif-medium" to "متوسط",
            "sans-serif-condensed" to "مضغوط",
            "casual" to "يدوي"
        )
        val currentFamily = preferences.getString(PREF_FONT_FAMILY, "sans-serif") ?: "sans-serif"
        val familyName = fontFamilies.firstOrNull { it.first == currentFamily }?.second ?: "خط مخصص"
        val familyButton = actionButton("اختيار خط النظام: $familyName", false) { }
        familyButton.setOnClickListener {
            val labels = fontFamilies.map { it.second }.toTypedArray()
            AlertDialog.Builder(this).setTitle("اختر نوع الخط")
                .setItems(labels) { _, which ->
                    val selected = fontFamilies[which]
                    preferences.edit().remove(PREF_FONT_FILE).remove(PREF_FONT_LABEL)
                        .putString(PREF_FONT_FAMILY, selected.first).apply()
                    familyButton.text = "اختيار خط النظام: ${selected.second}"
                    updateFontPreview()
                }.show()
        }
        settingsCard.addView(familyButton, matchWrap(dp(4)))

        val boldButton = actionButton(
            if (preferences.getBoolean(PREF_FONT_BOLD, true)) "نمط الخط: عريض ✓" else "نمط الخط: عادي", false
        ) { }
        boldButton.setOnClickListener {
            val enabled = !preferences.getBoolean(PREF_FONT_BOLD, true)
            preferences.edit().putBoolean(PREF_FONT_BOLD, enabled).apply()
            boldButton.text = if (enabled) "نمط الخط: عريض ✓" else "نمط الخط: عادي"
            updateFontPreview()
        }
        settingsCard.addView(boldButton, matchWrap(dp(4)))

        settingsCard.addView(text("خطوط عربية قابلة للتنزيل", 14f, gold, true).apply {
            setPadding(0, dp(12), 0, dp(4))
        }, matchWrap())
        settingsCard.addView(actionButton("تنزيل Noto Kufi Arabic · كوفي", false) {
            downloadArabicFont(
                "Noto Kufi Arabic", "NotoKufiArabic-Regular.ttf",
                "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io/fonts/NotoKufiArabic/unhinted/ttf/NotoKufiArabic-Regular.ttf"
            )
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Noto Naskh Arabic · نسخي", false) {
            downloadArabicFont(
                "Noto Naskh Arabic", "NotoNaskhArabic-Regular.ttf",
                "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io/fonts/NotoNaskhArabic/hinted/ttf/NotoNaskhArabic-Regular.ttf"
            )
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Noto Sans Arabic · حديث", false) {
            downloadArabicFont(
                "Noto Sans Arabic", "NotoSansArabic-Regular.ttf",
                "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io/fonts/NotoSansArabic/unhinted/ttf/NotoSansArabic-Regular.ttf"
            )
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Amiri · أميري كلاسيكي", false) {
            downloadArabicFont("Amiri", "Amiri-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/amiri/Amiri-Regular.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Droid Arabic Kufi Bold · كوفي عريض", false) {
            downloadDroidArabicKufiBold()
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("استيراد GE SS Two Bold (نسخة مرخّصة)", false) {
            AlertDialog.Builder(this)
                .setTitle("استيراد GE SS Two Bold")
                .setMessage("هذا خط تجاري من Boutros. استورد ملف TTF/OTF إذا كنت تملك ترخيص استخدامه. لن يضمّن التطبيق نسخة غير مرخّصة من الخط.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("اختيار ملف الخط") { _, _ -> pickFontFromDevice("GE SS Two Bold · ملف المستخدم") }
                .show()
        }, matchWrap(dp(4)))
        settingsCard.addView(text("الملاءمة التلقائية للنص داخل الفقاعة", 14f, gold, true).apply {
            setPadding(0, dp(12), 0, dp(3))
        }, matchWrap())
        val autoFitCheck = CheckBox(this).apply {
            text = "اختر أكبر حجم واضح يدخل بالكامل داخل الفقاعة"
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = preferences.getBoolean(PREF_AUTO_FONT_FIT, true)
            buttonTintList = android.content.res.ColorStateList.valueOf(gold)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(PREF_AUTO_FONT_FIT, checked).apply()
                toast(if (checked) "الملاءمة التلقائية مفعّلة" else "تم إيقاف الملاءمة التلقائية؛ سيُستخدم الحجم المحدد")
            }
        }
        settingsCard.addView(autoFitCheck, matchWrap(dp(2)))
        settingsCard.addView(text("الحجم في الأعلى هو الحد الأقصى عند تفعيل الملاءمة. يقيس التطبيق عرض الأسطر وارتفاعها مع تشكيل العربية، ثم يختار أكبر قياس ينجح داخل مساحة النص. إذا كان الكلام طويلاً جداً فسيظل بحاجة إلى تعديل يدوي أو توسيع منطقة النص.", 11f, Color.LTGRAY), matchWrap(dp(8)))
        settingsCard.addView(actionButton("تنزيل Tajawal · تجوال", false) {
            downloadArabicFont("Tajawal", "Tajawal-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal/Tajawal-Regular.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Reem Kufi · ريم كوفي", false) {
            downloadArabicFont("Reem Kufi", "ReemKufi-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/reemkufi/ReemKufi%5Bwght%5D.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("استيراد خط من الهاتف · TTF / OTF / TTC", true) {
            pickFontFromDevice()
        }, matchWrap(dp(4)))
        val fontStatus = text(
            preferences.getString(PREF_FONT_LABEL, null)?.let { "الخط المحدد: $it" } ?: "الخط الحالي: $familyName",
            12f, Color.LTGRAY
        )
        fontStatusText = fontStatus
        settingsCard.addView(fontStatus, matchWrap())

        settingsCard.addView(text("لغة النص الأصلي", 14f, gold, true).apply {
            setPadding(0, dp(12), 0, dp(3))
        }, matchWrap())
        settingsCard.addView(text("الوضع التلقائي يحاول اكتشاف اللغة. عند الترجمة غير الدقيقة اختر لغة المانجا يدوياً.", 11f, Color.LTGRAY), matchWrap())
        val sourceLanguages = listOf(
            "auto" to "تلقائي",
            "ja" to "اليابانية · مانجا",
            "en" to "الإنجليزية",
            "ko" to "الكورية · مانهوا",
            "zh" to "الصينية · مانها"
        )
        fun sourceLanguageLabel(code: String): String = sourceLanguages.firstOrNull { it.first == code }?.second ?: "تلقائي"
        val sourceLanguageButton = actionButton(
            "اختيار لغة النص: ${sourceLanguageLabel(preferences.getString(PREF_SOURCE_LANGUAGE, "auto") ?: "auto")}",
            false
        ) { }
        sourceLanguageButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("اختر لغة النص الأصلي")
                .setItems(sourceLanguages.map { it.second }.toTypedArray()) { _, which ->
                    val selected = sourceLanguages[which]
                    preferences.edit().putString(PREF_SOURCE_LANGUAGE, selected.first).apply()
                    sourceLanguageButton.text = "اختيار لغة النص: ${selected.second}"
                    toast("تم ضبط لغة النص: ${selected.second}")
                }.show()
        }
        settingsCard.addView(sourceLanguageButton, matchWrap(dp(4)))
        addChineseAiSettings(settingsCard)
        val sfxOption = CheckBox(this).apply {
            text = "ترجمة المؤثرات اليابانية الشائعة (SFX) عند اكتشافها"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            buttonTintList = android.content.res.ColorStateList.valueOf(gold)
            isChecked = preferences.getBoolean(PREF_TRANSLATE_SFX, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(PREF_TRANSLATE_SFX, checked).apply()
            }
        }
        settingsCard.addView(sfxOption, matchWrap(dp(4)))
        settingsCard.addView(text("يمكنك اختيار عينة من تقرير الفشل وحفظ ترجمتك المصححة في قاموس محلي؛ سيُعاد استخدامها تلقائياً عند معالجة نفس النص.", 11f, Color.LTGRAY), matchWrap(dp(4)))

        addSettingSlider(
            settingsCard, "حجم العدسة العائمة", 42, 96,
            preferences.getInt(PREF_LENS_SIZE, 58), " dp"
        ) { value ->
            preferences.edit().putInt(PREF_LENS_SIZE, value).apply()
            MangaFoxAccessibilityService.instance?.applyLensSettings()
        }
        addSettingSlider(
            settingsCard, "شفافية العدسة", 35, 100,
            preferences.getInt(PREF_LENS_ALPHA, 100), "%"
        ) { value ->
            preferences.edit().putInt(PREF_LENS_ALPHA, value).apply()
            MangaFoxAccessibilityService.instance?.applyLensSettings()
        }
        settingsCard.addView(text("العدسة العائمة", 14f, gold, true).apply {
            setPadding(0, dp(12), 0, dp(3))
        }, matchWrap())
        settingsCard.addView(actionButton("فتح استوديو تصميم العدسة", true) {
            openLensStudio()
        }, matchWrap(dp(4)))
        settingsCard.addView(text("العدسة العائمة تعمل دائماً وتبقى ظاهرة فوق التطبيقات، بما فيها Manga FOX، ما دامت خدمة إمكانية الوصول وإذن الظهور فوق التطبيقات مفعّلين.", 11f, Color.LTGRAY), matchWrap(dp(6)))
        updateFontPreview()
        root.addView(settingsCard, matchWrap(dp(16)))
    }

    private fun addChineseAiSettings(root: LinearLayout) {
        root.addView(text("محرك الترجمة الذكي للصينية · MAX", 16f, gold, true).apply {
            setPadding(0, dp(14), 0, dp(3))
        }, matchWrap())
        root.addView(text("يُستخدم نموذج الذكاء الاصطناعي للنص الصيني، بينما يبقى Google ML Kit محركاً محلياً احتياطياً. أدخل بيانات مزود متوافق مع واجهة OpenAI Chat Completions.", 11f, Color.LTGRAY), matchWrap(dp(5)))

        val endpoint = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            hint = "رابط API — يجب أن يبدأ بـ https://"
            setText(preferences.getString(PREF_AI_ENDPOINT, "https://api.openai.com/v1/chat/completions"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(Color.rgb(38, 38, 46), 10)
        }
        val model = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            hint = "اسم النموذج"
            setText(preferences.getString(PREF_AI_MODEL, "gpt-4o-mini"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(Color.rgb(38, 38, 46), 10)
        }
        val apiKey = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            hint = if (MangaFoxSecureSecretStore.get(this@MainActivity).isNullOrBlank()) "مفتاح API" else "مفتاح API محفوظ ومشفّر — أدخل بديلاً للتغيير"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(Color.rgb(38, 38, 46), 10)
        }
        root.addView(endpoint, matchWrap(dp(6)))
        root.addView(model, matchWrap(dp(6)))
        root.addView(apiKey, matchWrap(dp(6)))

        val enabled = CheckBox(this).apply {
            text = "تفعيل الذكاء الاصطناعي للصينية مع الرجوع للمحرك المحلي عند الفشل"
            textSize = 12f
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(gold)
            isChecked = preferences.getBoolean(PREF_AI_ZH_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(PREF_AI_ZH_ENABLED, checked).apply()
                toast(if (checked) "تم تفعيل مسار AI للصينية" else "تم إيقاف AI؛ سيعمل المحرك المحلي")
            }
        }
        root.addView(enabled, matchWrap(dp(5)))

        fun saveConfig(showMessage: Boolean): Boolean {
            val url = endpoint.text.toString().trim()
            val modelName = model.text.toString().trim()
            if (!url.startsWith("https://", true) || modelName.isBlank()) {
                if (showMessage) toast("أدخل رابط HTTPS صحيحاً واسم النموذج")
                return false
            }
            preferences.edit().putString(PREF_AI_ENDPOINT, url).putString(PREF_AI_MODEL, modelName).apply()
            val newKey = apiKey.text.toString().trim()
            if (newKey.isNotBlank()) {
                val stored = runCatching { MangaFoxSecureSecretStore.put(this@MainActivity, newKey) }.isSuccess
                if (!stored) {
                    if (showMessage) toast("تعذر تشفير المفتاح على هذا الجهاز؛ لم يُحفظ")
                    return false
                }
                apiKey.setText("")
                apiKey.hint = "مفتاح API محفوظ ومشفّر — أدخل بديلاً للتغيير"
            }
            if (showMessage) toast("حُفظت إعدادات محرك MAX داخل التطبيق")
            return true
        }

        root.addView(actionButton("حفظ إعدادات API", true) { saveConfig(true) }, matchWrap(dp(3)))
        root.addView(actionButton("حذف مفتاح API المحفوظ", false) {
            MangaFoxSecureSecretStore.clear(this)
            toast("تم حذف مفتاح API المشفّر من بيانات التطبيق")
        }, matchWrap(dp(3)))
        root.addView(actionButton("اختبار اتصال API", false) {
            if (!saveConfig(false)) return@actionButton
            val secret = MangaFoxSecureSecretStore.get(this)
            if (secret.isNullOrBlank()) {
                toast("أدخل مفتاح API أولاً ثم احفظه")
                return@actionButton
            }
            toast("جارٍ اختبار الاتصال…")
            fontDownloadExecutor.execute {
                val result = runCatching {
                    MangaFoxAiTranslator.translate(
                        this, "你是谁？", emptyList(), emptyList(), testRequest = true
                    )
                }
                runOnUiThread {
                    if (result.isSuccess && result.getOrNull().orEmpty().isNotBlank()) {
                        toast("نجح اتصال API. استجابة الاختبار: ${result.getOrNull()?.take(90)}")
                    } else {
                        toast("فشل اختبار API: ${result.exceptionOrNull()?.message?.take(180) ?: "استجابة فارغة"}")
                    }
                }
            }
        }, matchWrap(dp(4)))
        root.addView(text("تنبيه: نصوص الصفحات ستُرسل إلى مزود API عند تفعيل هذا الخيار. لا تضع مفتاحاً لا تملك حق استخدامه. يتطلب الاتصال إذن INTERNET في AndroidManifest.xml.", 10f, Color.LTGRAY), matchWrap(dp(4)))
    }

    private fun showLearnedTranslationDialog(sourceText: String) {
        val input = EditText(this).apply {
            setText("")
            hint = "اكتب الترجمة العربية الصحيحة"
            textSize = 16f
            minLines = 2
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("تصحيح ترجمة محفوظ")
            .setMessage("النص الأصلي:\n$sourceText")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ في القاموس المحلي") { _, _ ->
                val corrected = input.text.toString().trim()
                if (corrected.isBlank()) {
                    toast("اكتب الترجمة أولاً")
                } else {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    val dictionary = runCatching { org.json.JSONObject(prefs.getString(PREF_LEARNED_TRANSLATIONS, "{}") ?: "{}") }
                        .getOrElse { org.json.JSONObject() }
                    dictionary.put(sourceText.trim(), corrected)
                    prefs.edit().putString(PREF_LEARNED_TRANSLATIONS, dictionary.toString()).apply()
                    toast("حُفظ التصحيح وسيُستخدم تلقائياً مع النص نفسه")
                }
            }.show()
    }

    private fun openLensStudio() {
        val colors = arrayOf("ذهبي", "بنفسجي", "سماوي", "وردي", "أبيض")
        val values = intArrayOf(
            Color.rgb(255, 190, 65), Color.rgb(177, 107, 255),
            Color.rgb(90, 190, 255), Color.rgb(255, 107, 176), Color.WHITE
        )
        var selected = values.indices.minByOrNull { index ->
            kotlin.math.abs(values[index] - preferences.getInt(PREF_LENS_COLOR, values[0]))
        } ?: 0
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "رمز العدسة مثل 狐 أو ◉ أو ✦"
            setText(preferences.getString(PREF_LENS_GLYPH, "狐"))
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val preview = TextView(this).apply {
            text = input.text.toString().ifBlank { "狐" }
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = rounded(values[selected], 48)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            elevation = dp(5).toFloat()
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                preview.text = s?.toString()?.trim()?.take(3).orEmpty().ifBlank { "狐" }
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val colorButton = Button(this).apply {
            isAllCaps = false
            text = "اختيار لون العدسة: ${colors[selected]}"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("اختر لون العدسة")
                    .setItems(colors) { _, which ->
                        selected = which
                        text = "اختيار لون العدسة: ${colors[selected]}"
                        background = rounded(values[selected], 12)
                        setTextColor(if (selected == 4) Color.BLACK else Color.WHITE)
                        preview.background = rounded(values[selected], 48)
                    }.show()
            }
        }
        colorButton.background = rounded(values[selected], 12)
        colorButton.setTextColor(if (selected == 4) Color.BLACK else Color.WHITE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(preview, LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            })
            addView(input, matchWrap(dp(8)))
            addView(colorButton, matchWrap(dp(8)))
        }
        AlertDialog.Builder(this)
            .setTitle("استوديو تصميم العدسة")
            .setMessage("خصص الرمز واللون وشاهد معاينة مباشرة قبل الحفظ.")
            .setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ التصميم") { _, _ ->
                val glyph = input.text.toString().trim().take(3).ifBlank { "狐" }
                preferences.edit().putString(PREF_LENS_GLYPH, glyph)
                    .putInt(PREF_LENS_COLOR, values[selected]).apply()
                MangaFoxAccessibilityService.instance?.applyLensSettings()
                toast("تم حفظ تصميم العدسة وتطبيقه")
            }.show()
    }

    private fun updateFontPreview() {
        val preview = fontPreview ?: return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val size = prefs.getInt(PREF_FONT_SIZE, 22).coerceIn(10, 72)
        preview.textSize = size.toFloat()
        preview.typeface = resolveArabicTypeface(prefs)
        preview.paint.style = Paint.Style.FILL
        preview.paint.strokeWidth = 0f
        preview.invalidate()
        fontStatusText?.text = prefs.getString(PREF_FONT_LABEL, null)?.let { "الخط المحدد: $it" }
            ?: "الخط الحالي: ${prefs.getString(PREF_FONT_FAMILY, "sans-serif")}"
    }

    private fun pickFontFromDevice(friendlyLabel: String? = null) {
        requestedFontLabel = friendlyLabel
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf",
                "application/octet-stream"
            ))
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_FONT) }
            .onFailure { toast("تعذر فتح مدير الملفات") }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SAVE_PDF) {
            val prepared = pendingPdfFile
            pendingPdfFile = null
            if (resultCode != RESULT_OK || data?.data == null || prepared == null || !prepared.isFile) {
                prepared?.delete()
                if (resultCode != RESULT_OK) toast("تم إلغاء حفظ ملف PDF")
                return
            }
            val destination = data.data ?: run { prepared.delete(); return }
            fontDownloadExecutor.execute {
                try {
                    val output = contentResolver.openOutputStream(destination, "w")
                        ?: throw IllegalStateException("تعذر فتح ملف الوجهة")
                    output.use { out -> prepared.inputStream().use { input -> input.copyTo(out) } }
                    prepared.delete()
                    runOnUiThread { toast("تم حفظ ملف PDF بنجاح") }
                } catch (e: Exception) {
                    prepared.delete()
                    runOnUiThread { toast("تعذر حفظ PDF: ${e.localizedMessage ?: "خطأ في الملف"}") }
                }
            }
            return
        }
        if (requestCode == REQUEST_EXPORT_PHANTOM_PROJECT) {
            val prepared = pendingProjectArchive
            pendingProjectArchive = null
            val destination = data?.data
            if (resultCode != RESULT_OK || destination == null || prepared == null || !prepared.isFile) {
                prepared?.delete()
                if (resultCode != RESULT_OK) toast("تم إلغاء النسخ الاحتياطي")
                return
            }
            fontDownloadExecutor.execute {
                runCatching {
                    val output = contentResolver.openOutputStream(destination, "w") ?: error("تعذر فتح ملف الوجهة")
                    output.use { out -> prepared.inputStream().use { it.copyTo(out) } }
                }.onSuccess { runOnUiThread { toast("اكتمل حفظ نسخة المشروع الاحتياطية") } }
                    .onFailure { error -> runOnUiThread { toast("تعذر حفظ النسخة: ${error.localizedMessage ?: "خطأ"}") } }
                prepared.delete()
            }
            return
        }
        if (requestCode == REQUEST_IMPORT_PHANTOM_PROJECT) {
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { if (resultCode != RESULT_OK) toast("تم إلغاء الاستيراد"); return }
            toast("جارٍ فحص النسخة واستعادة المشروع...")
            fontDownloadExecutor.execute {
                runCatching {
                    val input = contentResolver.openInputStream(uri) ?: error("تعذر فتح ملف النسخة")
                    input.use { MangaFoxProjectArchive.restore(this, it) }
                }.onSuccess {
                    runOnUiThread { toast("تم استعادة المشروع والطبقات والمعجم"); showResults() }
                }.onFailure { error -> runOnUiThread { toast("فشل الاستيراد: ${error.localizedMessage ?: "نسخة غير صالحة"}") } }
            }
            return
        }
        if (requestCode != REQUEST_PICK_FONT) return
        if (resultCode != RESULT_OK) { requestedFontLabel = null; return }
        val uri = data?.data ?: run { requestedFontLabel = null; return }
        val friendlyFontLabel = requestedFontLabel
        requestedFontLabel = null
        runCatching {
            val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "font.ttf"
            val extension = displayName.substringAfterLast('.', "").lowercase()
            require(extension in setOf("ttf", "otf", "ttc")) { "اختر ملف خط TTF أو OTF أو TTC" }
            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val target = File(fontDir, "import_${System.currentTimeMillis()}_$safeName")
            val inputStream = requireNotNull(contentResolver.openInputStream(uri)) { "تعذر قراءة ملف الخط" }
            inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        require(total <= 15L * 1024L * 1024L) { "حجم الخط أكبر من 15 ميغابايت" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            Typeface.createFromFile(target)
            preferences.edit().putString(PREF_FONT_FILE, target.absolutePath)
                .putString(PREF_FONT_LABEL, if (friendlyFontLabel.isNullOrBlank()) displayName else "$friendlyFontLabel ($displayName)")
                .apply()
            updateFontPreview()
            toast("تم استيراد الخط وتفعيله: ${friendlyFontLabel ?: displayName}")
        }.onFailure { toast("تعذر استيراد الخط: ${it.localizedMessage ?: "ملف غير صالح"}") }
    }

    private fun exportResultsAsPdf() {
        if (MangaFoxRuntime.captureOnlyResult) {
            exportResultsAsPdfAfterAudit()
            return
        }
        val audit = MangaFoxQualityGuard.inspect(MangaFoxRuntime.resultPages, MangaFoxRuntime.translationLayerFiles)
        if (audit.errorCount > 0 || audit.warningCount > 0) {
            AlertDialog.Builder(this)
                .setTitle("FOX QUALITY GUARD · مراجعة قبل التصدير")
                .setMessage(audit.toArabicText() + "\n\nهل تريد المتابعة إلى تصدير PDF رغم التحذيرات؟")
                .setNegativeButton("العودة للإصلاح", null)
                .setPositiveButton("متابعة التصدير") { _, _ -> exportResultsAsPdfAfterAudit() }
                .show()
            return
        }
        exportResultsAsPdfAfterAudit()
    }

    private fun exportResultsAsPdfAfterAudit() {
        val images = MangaFoxRuntime.resultPages.map { File(it) }
            .filter { it.isFile && it.length() > 0L }
        if (images.isEmpty()) {
            toast("لا توجد نتيجة مترجمة لتصديرها")
            return
        }
        toast("جارٍ تجهيز ملف PDF متصل الصفحات...")
        fontDownloadExecutor.execute {
            var prepared: File? = null
            try {
                val readyFile = File(cacheDir, "mfox_export_${System.currentTimeMillis()}.pdf")
                prepared = readyFile
                createContinuousPdf(images, readyFile)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        readyFile.delete()
                        return@runOnUiThread
                    }
                    pendingPdfFile = readyFile
                    val title = MangaFoxRuntime.resultTitle
                        .replace(Regex("[^\\p{L}\\p{N} _-]"), "_")
                        .trim().take(60).ifBlank { "Manga_Fox_Result" }
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_TITLE, "$title.pdf")
                    }
                    try {
                        startActivityForResult(intent, REQUEST_SAVE_PDF)
                    } catch (e: Exception) {
                        pendingPdfFile = null
                        readyFile.delete()
                        toast("لا يوجد تطبيق لحفظ ملف PDF")
                    }
                }
            } catch (e: Exception) {
                prepared?.delete()
                runOnUiThread { toast("تعذر إنشاء PDF: ${e.localizedMessage ?: "خطأ في الصور"}") }
            }
        }
    }

    /**
     * Writes all translated strips as one uninterrupted vertical stream.
     * The image sequence continues at the exact y-coordinate where the prior
     * image ended; only the physical PDF page boundary divides the output.
     */
    private fun createContinuousPdf(images: List<File>, outputFile: File) {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        var page: PdfDocument.Page? = null
        var yOnPage = 0f
        var pageNumber = 0

        fun startPageIfNeeded() {
            if (page == null) {
                pageNumber++
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdf.startPage(info)
                yOnPage = 0f
                page!!.canvas.drawColor(Color.WHITE)
            }
        }

        fun finishPage() {
            val current = page ?: return
            pdf.finishPage(current)
            page = null
            yOnPage = 0f
        }

        try {
            for (file in images) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IllegalStateException("تعذر فتح صورة ${file.name}")
                try {
                    val scale = pageWidth.toFloat() / bitmap.width.toFloat()
                    var consumedSourceY = 0f
                    while (consumedSourceY < bitmap.height - 0.01f) {
                        startPageIfNeeded()
                        val availableOnPage = pageHeight.toFloat() - yOnPage
                        val remainingSource = bitmap.height.toFloat() - consumedSourceY
                        val sourceChunkHeight = min(remainingSource, availableOnPage / scale)
                        if (sourceChunkHeight <= 0.01f) {
                            finishPage()
                            continue
                        }
                        val sourceTop = consumedSourceY.toInt().coerceIn(0, bitmap.height - 1)
                        val sourceBottom = (consumedSourceY + sourceChunkHeight)
                            .toInt().coerceIn(sourceTop + 1, bitmap.height)
                        val actualSourceHeight = (sourceBottom - sourceTop).toFloat()
                        val destHeight = actualSourceHeight * scale
                        val src = Rect(0, sourceTop, bitmap.width, sourceBottom)
                        val dst = RectF(0f, yOnPage, pageWidth.toFloat(), yOnPage + destHeight)
                        page!!.canvas.drawBitmap(bitmap, src, dst, paint)
                        consumedSourceY = sourceBottom.toFloat()
                        yOnPage += destHeight
                        if (yOnPage >= pageHeight - 0.5f) finishPage()
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            if (page != null) finishPage()
            if (pageNumber == 0) throw IllegalStateException("لا توجد صور صالحة في النتيجة")
            FileOutputStream(outputFile).use { pdf.writeTo(it) }
        } finally {
            runCatching { page?.let { pdf.finishPage(it) } }
            pdf.close()
        }
    }

    private fun downloadArabicFont(label: String, fileName: String, sourceUrl: String) {
        toast("بدأ تنزيل $label")
        fontDownloadExecutor.execute {
            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val target = File(fontDir, fileName)
            val temp = File(fontDir, "$fileName.part.${fileName.substringAfterLast('.', "ttf")}")
            try {
                val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "MangaFox-Android")
                }
                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > 15L * 1024L * 1024L) throw IllegalStateException("ملف الخط كبير جداً")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (!temp.isFile || temp.length() < 4096L) throw IllegalStateException("ملف الخط غير مكتمل أو ليس ملف خط صالحاً")
                // Parsing the actual font before activation prevents corrupt or HTML error pages being selected.
                Typeface.createFromFile(temp)
                if (target.exists() && !target.delete()) throw IllegalStateException("تعذر استبدال ملف الخط السابق")
                if (!temp.renameTo(target)) throw IllegalStateException("تعذر حفظ الخط")
                runOnUiThread {
                    preferences.edit().putString(PREF_FONT_FILE, target.absolutePath)
                        .putString(PREF_FONT_LABEL, label).apply()
                    updateFontPreview()
                    toast("تم تنزيل $label وتفعيله")
                }
            } catch (e: Exception) {
                temp.delete()
                runOnUiThread { toast("فشل تنزيل الخط: ${e.localizedMessage ?: "تحقق من الإنترنت"}") }
            }
        }
    }
    /** Downloads Droid Kufi Bold from the Android Open Source Project Gitiles endpoint.
     * Gitiles serves binary blobs as Base64 when format=TEXT is requested.
     */
    private fun downloadDroidArabicKufiBold() {
        val source = "https://android.googlesource.com/platform/frameworks/base/+/e7beae3/data/fonts/DroidKufi-Bold.ttf?format=TEXT"
        toast("جارٍ تنزيل Droid Arabic Kufi Bold من مصدر Android المفتوح")
        fontDownloadExecutor.execute {
            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val target = File(fontDir, "DroidKufi-Bold.ttf")
            val temp = File(fontDir, "DroidKufi-Bold.ttf.part")
            try {
                val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "MangaFox-Android")
                }
                val encoded = try {
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 24 * 1024 * 1024) { "استجابة مصدر الخط كبيرة جداً" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } finally { connection.disconnect() }
                val ascii = encoded.toString(Charsets.US_ASCII).filterNot { it.isWhitespace() }
                val fontBytes = android.util.Base64.decode(ascii, android.util.Base64.DEFAULT)
                require(fontBytes.size in 4096..(15 * 1024 * 1024)) { "حجم ملف الخط غير صالح" }
                FileOutputStream(temp).use { it.write(fontBytes) }
                // Validate the TTF before replacing an existing font.
                val parsed = Typeface.createFromFile(temp)
                require(parsed != null) { "تعذر قراءة ملف الخط" }
                if (target.exists() && !target.delete()) error("تعذر استبدال الخط القديم")
                if (!temp.renameTo(target)) error("تعذر حفظ الخط الجديد")
                runOnUiThread {
                    preferences.edit().putString(PREF_FONT_FILE, target.absolutePath)
                        .putString(PREF_FONT_LABEL, "Droid Arabic Kufi Bold · Apache-2.0")
                        .putBoolean(PREF_FONT_BOLD, true).apply()
                    updateFontPreview()
                    toast("تم تنزيل Droid Arabic Kufi Bold وتفعيله")
                }
            } catch (e: Exception) {
                temp.delete()
                runOnUiThread { toast("تعذر تنزيل الخط: ${e.localizedMessage ?: "تحقق من الاتصال"}") }
            }
        }
    }

    private fun addSettingSlider(
        parent: LinearLayout, label: String, minValue: Int, maxValue: Int,
        initialValue: Int, suffix: String, onChanged: (Int) -> Unit
    ) {
        val labelView = text("$label: ${initialValue.coerceIn(minValue, maxValue)}$suffix", 13f, Color.LTGRAY, true)
        parent.addView(labelView, matchWrap())
        val slider = SeekBar(this).apply {
            max = maxValue - minValue
            progress = initialValue.coerceIn(minValue, maxValue) - minValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = minValue + progress
                    labelView.text = "$label: $value$suffix"
                    if (fromUser) onChanged(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        parent.addView(slider, matchWrap(dp(8)))
    }

    private fun openRegionSelection(sourcePath: String, resultPath: String) {
        val original = BitmapFactory.decodeFile(sourcePath)
        if (original == null) {
            toast("تعذر فتح الصورة الأصلية لتحديد المنطقة")
            return
        }
        val maxWidth = min(resources.displayMetrics.widthPixels - dp(56), dp(360)).coerceAtLeast(dp(220))
        var viewWidth = maxWidth
        var viewHeight = (original.height * (viewWidth.toFloat() / original.width)).toInt().coerceAtLeast(dp(180))
        if (viewHeight > dp(620)) {
            viewHeight = dp(620)
            viewWidth = (original.width * (viewHeight.toFloat() / original.height)).toInt().coerceAtLeast(dp(180))
        }
        val image = ImageView(this).apply {
            setImageBitmap(original)
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
        }
        val selector = RegionSelectionView(this)
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(viewWidth, viewHeight))
            addView(selector, FrameLayout.LayoutParams(viewWidth, viewHeight))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(text("اسحب إصبعك لرسم مستطيل حول الفقاعة أو النص الذي لم تتم ترجمته.", 12f, Color.LTGRAY), matchWrap(dp(8)))
            addView(frame, LinearLayout.LayoutParams(viewWidth, viewHeight))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("إعادة OCR لمنطقة محددة")
            .setView(container)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إعادة القراءة والترجمة", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val area = selector.toSourceRect(original.width, original.height)
                if (area == null || area.width() < 8 || area.height() < 8) {
                    toast("حدد منطقة أكبر حول النص أولاً")
                    return@setOnClickListener
                }
                val service = MangaFoxAccessibilityService.instance
                if (service == null) {
                    toast("خدمة Manga Fox غير متصلة")
                    return@setOnClickListener
                }
                dialog.dismiss()
                service.retryManualRegion(sourcePath, resultPath, area) { success, message ->
                    toast(message)
                    if (success) showResults()
                }
            }
        }
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            if (!original.isRecycled) original.recycle()
        }
        dialog.show()
    }

    private fun decodeComparisonPreview(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1100 || bounds.outHeight / sample > 1800) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun showBeforeAfterComparison(originalPath: String, resultPath: String) {
        val loading = ProgressBar(this).apply { isIndeterminate = true }
        val loadingDialog = AlertDialog.Builder(this).setTitle("إعداد المقارنة").setView(loading).setNegativeButton("إلغاء", null).show()
        fontDownloadExecutor.execute {
            var original: Bitmap? = null
            var translated: Bitmap? = null
            try {
                original = decodeComparisonPreview(originalPath)
                translated = decodeComparisonPreview(resultPath)
                val originalBitmap = original
                val translatedBitmap = translated
                runOnUiThread {
                    loadingDialog.dismiss()
                    if (isFinishing || isDestroyed || originalBitmap == null || translatedBitmap == null) {
                        originalBitmap?.recycle(); translatedBitmap?.recycle()
                        if (!isFinishing && !isDestroyed) toast("تعذر إنشاء معاينة المقارنة")
                        return@runOnUiThread
                    }
                    val frame = FrameLayout(this).apply {
                        setBackgroundColor(Color.BLACK)
                        clipToPadding = true
                        clipChildren = true
                    }
                    val beforeView = ImageView(this).apply {
                        setImageBitmap(originalBitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "الصورة الأصلية"
                    }
                    val afterView = ImageView(this).apply {
                        setImageBitmap(translatedBitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "الصورة المترجمة"
                    }
                    frame.addView(beforeView, FrameLayout.LayoutParams(-1, dp(420)))
                    frame.addView(afterView, FrameLayout.LayoutParams(-1, dp(420)))
                    val label = text("اسحب المؤشر: 0٪ الأصل · 100٪ الترجمة", 12f, Color.LTGRAY)
                    val slider = SeekBar(this).apply {
                        max = 100
                        progress = 100
                        progressTintList = android.content.res.ColorStateList.valueOf(gold)
                        thumbTintList = android.content.res.ColorStateList.valueOf(gold)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                                afterView.alpha = value / 100f
                                label.text = "${100 - value}٪ الأصل · $value٪ الترجمة"
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                        })
                    }
                    val panel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(8), dp(12), dp(4))
                        addView(frame, LinearLayout.LayoutParams(-1, dp(420)))
                        addView(label, matchWrap(dp(6)))
                        addView(slider, matchWrap())
                    }
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("مقارنة الأصل والترجمة")
                        .setView(panel)
                        .setPositiveButton("إغلاق", null)
                        .create()
                    dialog.setOnDismissListener {
                        beforeView.setImageDrawable(null)
                        afterView.setImageDrawable(null)
                        if (!originalBitmap.isRecycled) originalBitmap.recycle()
                        if (!translatedBitmap.isRecycled) translatedBitmap.recycle()
                    }
                    dialog.show()
                }
            } catch (error: Exception) {
                original?.let { if (!it.isRecycled) it.recycle() }
                translated?.let { if (!it.isRecycled) it.recycle() }
                runOnUiThread {
                    loadingDialog.dismiss()
                    toast("تعذرت المقارنة: ${error.localizedMessage ?: "خطأ في الصورة"}")
                }
            }
        }
    }

    private fun showResults() {
        setReadingFullscreen(true)
        val editMode = preferences.getBoolean(PREF_RESULTS_EDIT_MODE, false)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(dark)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            // In reading mode the page stack is edge-to-edge: no horizontal/vertical
            // container padding, child margins, separators, or card spacing.
            setPadding(if (editMode) dp(12) else 0, if (editMode) dp(8) else 0,
                if (editMode) dp(12) else 0, if (editMode) dp(12) else 0)
            setBackgroundColor(Color.BLACK)
        }
        val pages = MangaFoxRuntime.resultPages
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun compactButton(label: String, action: () -> Unit): Button = actionButton(label, false, action).apply {
            textSize = 11f
            minHeight = dp(44)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3); topMargin = dp(3); bottomMargin = dp(5)
            }
        }
        topBar.addView(compactButton("الرئيسية") { showHome() })
        topBar.addView(compactButton("PDF") { exportResultsAsPdf() })
        topBar.addView(compactButton("صوت") { speakResultsAloud() })
        topBar.addView(compactButton(if (editMode) "معاينة متصلة" else "أدوات التحرير") {
            preferences.edit().putBoolean(PREF_RESULTS_EDIT_MODE, !editMode).apply()
            showResults()
        })
        root.addView(topBar, matchWrap(dp(4)))

        root.addView(text(
            if (editMode) "MANGA FOX · وضع التحرير" else "MANGA FOX · المعاينة المتصلة",
            16f, gold, true
        ).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(5)) }, matchWrap())

        if (editMode) {
            root.addView(text("${pages.size} صورة · ${MangaFoxRuntime.resultTitle}", 12f, Color.LTGRAY).apply {
                gravity = Gravity.CENTER
            }, matchWrap(dp(5)))
            root.addView(actionButton("تطبيق الخط والملاءمة الجديدة دون إعادة OCR", true) {
                val service = MangaFoxAccessibilityService.instance
                if (MangaFoxRuntime.sourcePages.isEmpty()) toast("الصور الأصلية غير متوفرة لهذه النتيجة")
                else if (service == null) toast("فعّل خدمة Manga Fox لإعادة تنسيق الطبقات")
                else service.rerenderLastResultsFromLayers { showResults() }
            }, matchWrap(dp(5)))
            root.addView(actionButton("إعادة OCR وترجمة الصور الأصلية من جديد", false) {
                MangaFoxAccessibilityService.instance?.retranslateLastResults()
                    ?: toast("فعّل خدمة Manga Fox لإعادة المعالجة")
            }, matchWrap(dp(5)))
            root.addView(text("FOX QUALITY GUARD\n${MangaFoxRuntime.qualitySummary}", 12f, Color.LTGRAY), matchWrap(dp(7)))
            root.addView(actionButton("عرض النصوص التي فشلت ترجمتها", false) {
                val samples = MangaFoxRuntime.failedBlockSamples.take(25)
                if (samples.isEmpty()) AlertDialog.Builder(this).setTitle("نتائج الفشل")
                    .setMessage("لا توجد عينات فشل مسجلة حالياً.").setPositiveButton("إغلاق", null).show()
                else AlertDialog.Builder(this).setTitle("اختر نصاً لتصحيح ترجمته")
                    .setItems(samples.toTypedArray()) { _, which -> showLearnedTranslationDialog(samples[which]) }
                    .setNegativeButton("إغلاق", null).show()
            }, matchWrap(dp(8)))
        } else {
            root.addView(text("${pages.size} صورة متصلة · ${MangaFoxRuntime.resultTitle}\nاضغط مطولاً على الصورة للتبديل بين الأصل والترجمة. أدوات التحرير مخفية حتى لا تقطع القراءة.", 11f, Color.LTGRAY).apply {
                gravity = Gravity.CENTER
            }, matchWrap(dp(8)))
        }

        if (pages.isEmpty()) {
            root.addView(text(
                if (MangaFoxRuntime.lastError.isNotBlank()) MangaFoxRuntime.lastError
                else "لا توجد نتيجة بعد. افتح فصلاً واضغط العدسة العائمة، ثم اختر ترجمة الفصل العمودي كاملاً.",
                15f, Color.LTGRAY
            ).apply { setPadding(dp(12), dp(30), dp(12), dp(30)) }, matchWrap())
        } else {
            val pageViews = ArrayList<ZoomableImageView>()
            pages.forEachIndexed { index, filePath ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, bounds)
                val sourceWidth = bounds.outWidth.coerceAtLeast(1)
                val sourceHeight = bounds.outHeight.coerceAtLeast(1)
                val originalPath = MangaFoxRuntime.sourcePages.getOrNull(index)?.takeIf { File(it).isFile }
                var showingOriginal = false
                val image = ZoomableImageView(this).apply {
                    setImageFile(filePath)
                    contentDescription = "الصورة ${index + 1} من ${pages.size} — معاينة متصلة"
                    setPadding(0, 0, 0, 0)
                    minimumHeight = 0
                    minimumWidth = 0
                    setBackgroundColor(Color.BLACK)
                    setOnLongClickListener {
                        if (originalPath == null) toast("الصورة الأصلية غير متوفرة لهذه الصفحة")
                        else {
                            showingOriginal = !showingOriginal
                            setImageFile(if (showingOriginal) originalPath else filePath)
                            loadPage()
                            toast(if (showingOriginal) "الأصل · اضغط مطولاً للعودة" else "الترجمة العربية")
                        }
                        true
                    }
                }
                val displayWidth = if (editMode) resources.displayMetrics.widthPixels - dp(24)
                    else resources.displayMetrics.widthPixels
                val displayHeight = kotlin.math.ceil(sourceHeight * (displayWidth.toFloat() / sourceWidth)).toInt().coerceAtLeast(1)
                root.addView(image, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, displayHeight
                ).apply {
                    // Zero margins in preview mode: captured strips remain edge-to-edge.
                    bottomMargin = 0; topMargin = 0
                })
                pageViews.add(image)

                if (editMode) {
                    root.addView(text("الصورة ${index + 1} / ${pages.size}", 11f, gold, true), matchWrap(dp(3)))
                    if (originalPath != null) {
                        root.addView(actionButton("مقارنة الأصل والترجمة بمزلاج", false) {
                            showBeforeAfterComparison(originalPath, filePath)
                        }, matchWrap(dp(4)))
                        root.addView(actionButton("إعادة OCR لمنطقة من هذه الصورة", false) {
                            openRegionSelection(originalPath, filePath)
                        }, matchWrap(dp(4)))
                    }
                    root.addView(actionButton("إدارة الفقاعات والنصوص والطبقات", false) {
                        editTranslationLayer(index)
                    }, matchWrap(dp(4)))
                    root.addView(actionButton("تراجع / إعادة آخر تعديل", false) {
                        showLayerHistoryDialog(index)
                    }, matchWrap(dp(4)))
                    root.addView(actionButton("حذف هذه الصورة من الفصل", false) {
                        AlertDialog.Builder(this)
                            .setTitle("حذف الصورة ${index + 1}؟")
                            .setMessage("سيتم حذف الصورة المترجمة وأصلها وطبقاتها. لا يمكن التراجع عن حذف الصورة.")
                            .setNegativeButton("إلغاء", null)
                            .setPositiveButton("حذف الصورة") { _, _ ->
                                MangaFoxRuntime.removePage(this, index)
                                showResults()
                                toast("تم حذف الصورة من الفصل")
                            }.show()
                    }, matchWrap(dp(10)))
                }
            }
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateVisiblePageImages(pageViews) }
            scroll.post { updateVisiblePageImages(pageViews) }
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun layerFileForPage(pageIndex: Int): File? {
        if (pageIndex !in MangaFoxRuntime.resultPages.indices) return null
        val current = MangaFoxRuntime.translationLayerFiles.getOrNull(pageIndex)
            ?.takeIf { it.isNotBlank() }?.let { path -> File(path) }?.takeIf { it.isFile }
        if (current != null) return current
        val created = File(filesDir, "mfox_layers_editor_${System.currentTimeMillis()}_$pageIndex.json")
        runCatching { created.writeText(org.json.JSONArray().toString(), Charsets.UTF_8) }.getOrElse { return null }
        val updated = MangaFoxRuntime.translationLayerFiles.toMutableList()
        while (updated.size < MangaFoxRuntime.resultPages.size) updated.add("")
        updated[pageIndex] = created.absolutePath
        MangaFoxRuntime.translationLayerFiles = updated
        MangaFoxRuntime.persist(this)
        return created
    }

    private fun readLayerArray(pageIndex: Int): Pair<File, org.json.JSONArray>? {
        val file = layerFileForPage(pageIndex) ?: return null
        val array = runCatching { org.json.JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        return file to array
    }

    private fun saveLayerChanges(file: File, layers: org.json.JSONArray, refresh: Boolean = true) {
        runCatching {
            val historyPageIndex = MangaFoxRuntime.translationLayerFiles.indexOf(file.absolutePath)
            if (historyPageIndex >= 0) MangaFoxLayerHistory(this).push(historyPageIndex, file)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(layers.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            MangaFoxRuntime.persist(this)
        }.onSuccess {
            if (refresh) {
                val service = MangaFoxAccessibilityService.instance
                if (service == null) toast("تم حفظ الطبقة. افتح الخدمة لإعادة رسم الصورة")
                else service.rerenderLastResultsFromLayers { showResults() }
            }
        }.onFailure { toast("تعذر حفظ الطبقة: ${it.localizedMessage ?: "خطأ في الملف"}") }
    }

    private fun editTranslationLayer(pageIndex: Int) {
        val pair = readLayerArray(pageIndex)
        if (pair == null) {
            toast("تعذر فتح طبقات هذا الجزء")
            return
        }
        val (file, layers) = pair
        val visibleIndexes = (0 until layers.length()).filter { index ->
            val item = layers.optJSONObject(index)
            item != null && item.has("left") && item.has("top")
        }
        val labels = visibleIndexes.map { index ->
            val item = layers.optJSONObject(index)
            val translated = item?.optString("translated").orEmpty().take(50)
            val source = item?.optString("source").orEmpty().take(35)
            val mode = when {
                item?.optBoolean("eraseOnly", false) == true -> " · مسح"
                item?.optBoolean("vertical", false) == true -> " · عمودي"
                else -> ""
            }
            "${index + 1}. ${translated.ifBlank { source.ifBlank { "طبقة يدوية" } }}$mode"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("إدارة طبقات الجزء ${pageIndex + 1}")
            .setItems(labels) { _, selected ->
                val actualIndex = visibleIndexes.getOrNull(selected) ?: return@setItems
                val item = layers.optJSONObject(actualIndex) ?: return@setItems
                val actions = arrayOf(
                    "تعديل النص العربي",
                    if (item.optBoolean("vertical", false)) "تحويل إلى أفقي" else "عرض العربية عمودياً من الأعلى للأسفل",
                    "إخفاء النص الأصلي داخل المنطقة (مسح)",
                    "تغيير موضع وحجم المنطقة",
                    "تبديل شكل الفقاعة (بيضاوي / مستطيل)",
                    "حذف هذه الطبقة"
                )
                AlertDialog.Builder(this)
                    .setTitle("الطبقة ${actualIndex + 1}")
                    .setItems(actions) { _, action ->
                        when (action) {
                            0 -> {
                                val edit = EditText(this).apply {
                                    setText(item.optString("translated"))
                                    hint = "اكتب الترجمة العربية"
                                    minLines = 2
                                    textSize = 16f
                                    setPadding(dp(10), dp(8), dp(10), dp(8))
                                }
                                AlertDialog.Builder(this).setTitle("تعديل النص")
                                    .setMessage("النص الأصلي: ${item.optString("source")}")
                                    .setView(edit).setNegativeButton("إلغاء", null)
                                    .setPositiveButton("حفظ") { _, _ ->
                                        val corrected = edit.text.toString().trim()
                                        if (corrected.isBlank()) {
                                            toast("النص فارغ؛ استخدم خيار المسح بدلاً من ذلك")
                                        } else {
                                            item.put("translated", corrected).put("eraseOnly", false)
                                            val sourceText = item.optString("source").trim()
                                            if (sourceText.isNotBlank()) {
                                                val dictionary = runCatching {
                                                    org.json.JSONObject(preferences.getString(PREF_LEARNED_TRANSLATIONS, "{}") ?: "{}")
                                                }.getOrElse { org.json.JSONObject() }
                                                dictionary.put(normalizeDictionaryKey(sourceText), corrected)
                                                preferences.edit().putString(PREF_LEARNED_TRANSLATIONS, dictionary.toString()).apply()
                                            }
                                            saveLayerChanges(file, layers)
                                        }
                                    }.show()
                            }
                            1 -> {
                                item.put("vertical", !item.optBoolean("vertical", false))
                                saveLayerChanges(file, layers)
                            }
                            2 -> {
                                AlertDialog.Builder(this).setTitle("مسح النص داخل الفقاعة؟")
                                    .setMessage("سيُغطّى النص الأصلي داخل المستطيل المحدد بلون قريب من الخلفية، دون حذف بقية الصورة.")
                                    .setNegativeButton("إلغاء", null)
                                    .setPositiveButton("مسح النص") { _, _ ->
                                        item.put("eraseOnly", true).put("translated", "")
                                        saveLayerChanges(file, layers)
                                    }.show()
                            }
                            3 -> openLayerRegionSelection(pageIndex, actualIndex, false)
                            4 -> {
                                item.put("shape", if (item.optString("shape", "round") == "oval") "rect" else "oval")
                                saveLayerChanges(file, layers)
                            }
                            5 -> AlertDialog.Builder(this).setTitle("حذف الطبقة؟")
                                .setMessage("سيتم حذف هذه الطبقة من قائمة التعديلات. لحذف النص الأصلي من الصورة استخدم أداة المسح.")
                                .setNegativeButton("إلغاء", null)
                                .setPositiveButton("حذف") { _, _ ->
                                    val updated = org.json.JSONArray()
                                    for (i in 0 until layers.length()) if (i != actualIndex) updated.put(layers.optJSONObject(i))
                                    saveLayerChanges(file, updated)
                                }.show()
                        }
                    }.setNegativeButton("إغلاق", null).show()
            }
            .setPositiveButton("إضافة فقاعة / مستطيل") { _, _ -> openLayerRegionSelection(pageIndex, null, false) }
            .setNeutralButton("مسح نص من الصورة") { _, _ -> openLayerRegionSelection(pageIndex, null, true) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    /** Selects an editable rectangle on the original page, then adds an overlay/mask or resizes one. */
    private fun openLayerRegionSelection(pageIndex: Int, layerIndex: Int?, eraseOnly: Boolean) {
        val sourcePath = MangaFoxRuntime.sourcePages.getOrNull(pageIndex)
        val original = sourcePath?.let { BitmapFactory.decodeFile(it) }
        if (original == null) {
            toast("الصورة الأصلية غير متوفرة")
            return
        }
        val maxWidth = min(resources.displayMetrics.widthPixels - dp(56), dp(360)).coerceAtLeast(dp(220))
        var viewWidth = maxWidth
        var viewHeight = (original.height * (viewWidth.toFloat() / original.width)).toInt().coerceAtLeast(dp(180))
        if (viewHeight > dp(620)) {
            viewHeight = dp(620)
            viewWidth = (original.width * (viewHeight.toFloat() / original.height)).toInt().coerceAtLeast(dp(180))
        }
        val image = ImageView(this).apply { setImageBitmap(original); scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.BLACK) }
        val selector = RegionSelectionView(this)
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(viewWidth, viewHeight))
            addView(selector, FrameLayout.LayoutParams(viewWidth, viewHeight))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(text("اسحب لتحديد حدود الفقاعة أو المستطيل. يمكنك إعادة السحب لتعديل الحجم والموضع.", 12f, Color.LTGRAY), matchWrap(dp(8)))
            addView(frame, LinearLayout.LayoutParams(viewWidth, viewHeight))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (layerIndex != null) "تغيير موضع وحجم الطبقة" else if (eraseOnly) "تحديد نص للمسح" else "إضافة فقاعة أو مستطيل")
            .setView(container).setNegativeButton("إلغاء", null)
            .setPositiveButton("متابعة", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rect = selector.toSourceRect(original.width, original.height)
                if (rect == null || rect.width() < 8 || rect.height() < 8) {
                    toast("حدد منطقة أكبر قليلاً")
                    return@setOnClickListener
                }
                dialog.dismiss()
                val pair = readLayerArray(pageIndex)
                if (pair == null) {
                    toast("تعذر فتح ملف الطبقات")
                    return@setOnClickListener
                }
                val (file, layers) = pair
                if (layerIndex != null) {
                    val item = layers.optJSONObject(layerIndex)
                    if (item == null) return@setOnClickListener
                    item.put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
                    saveLayerChanges(file, layers)
                } else if (eraseOnly) {
                    layers.put(org.json.JSONObject()
                        .put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
                        .put("source", "").put("translated", "").put("language", "manual")
                        .put("vertical", false).put("eraseOnly", true).put("shape", "rect"))
                    saveLayerChanges(file, layers)
                } else {
                    val input = EditText(this).apply {
                        hint = "النص العربي داخل المنطقة"
                        minLines = 2
                        textSize = 16f
                    }
                    val vertical = CheckBox(this).apply { text = "عرض النص العربي عمودياً من الأعلى إلى الأسفل" }
                    val oval = CheckBox(this).apply { text = "شكل بيضاوي (فقاعة)؛ اتركه دون تحديد للمستطيل" }
                    val content = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(8), dp(16), dp(4))
                        addView(input, matchWrap(dp(8)))
                        addView(vertical, matchWrap(dp(4)))
                        addView(oval, matchWrap())
                    }
                    AlertDialog.Builder(this).setTitle("محتوى الطبقة الجديدة")
                        .setView(content).setNegativeButton("إلغاء", null)
                        .setPositiveButton("إضافة وحفظ") { _, _ ->
                            val value = input.text.toString().trim()
                            if (value.isBlank()) {
                                toast("اكتب نصاً أو اختر مسح النص من الصورة")
                            } else {
                                layers.put(org.json.JSONObject()
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
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            if (!original.isRecycled) original.recycle()
        }
        dialog.show()
    }

    private fun normalizeDictionaryKey(source: String): String =
        source.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun updateVisiblePageImages(pages: List<ZoomableImageView>) {
        val visibleBounds = android.graphics.Rect()
        pages.forEach { page ->
            if (page.getLocalVisibleRect(visibleBounds)) page.loadPage() else page.releasePage()
        }
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

internal fun resolveArabicTypeface(prefs: SharedPreferences): Typeface {
    val boldPreferred = prefs.getBoolean(PREF_FONT_BOLD, true)
    val customPath = prefs.getString(PREF_FONT_FILE, null)
    if (!customPath.isNullOrBlank()) {
        val custom = runCatching {
            val file = File(customPath)
            if (file.isFile) Typeface.createFromFile(file) else null
        }.getOrNull()
        if (custom != null) {
            // OVERLAP FIX: never synthesize bold on a custom font. Most downloaded fonts
            // are Regular weight; Typeface.create(custom, Typeface.BOLD) then applies FAUX
            // bold, and on many OEM ROMs (Infinix/XOS especially) StaticLayout measures the
            // text narrower than the synthetically-bolded glyphs are drawn — so words pile
            // on top of each other. The default sans-serif bold is a REAL bold face, which
            // is exactly why the default font never overlapped. Use the stored font as-is;
            // for bold custom output pick a true-bold file (e.g. Droid Arabic Kufi Bold).
            return custom
        }
    }
    val style = if (boldPreferred) Typeface.BOLD else Typeface.NORMAL
    val family = prefs.getString(PREF_FONT_FAMILY, "sans-serif") ?: "sans-serif"
    return Typeface.create(family, style)
}


// ============================================================
// MERGED FROM: MangaFox_V3_0_3_MangaFoxAccessibilityService.kt
// ============================================================

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
    // Separate overlay for the lens action menu. It is kept independent from the
    // bubble so the menu can be dismissed without recreating or moving the lens.
    private var floatingOptionsView: View? = null
    private var floatingOptionsParams: WindowManager.LayoutParams? = null
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var hostActivityResumed: Boolean = false
    private var captureRunning = false
    private var fullChapterMode = false
    private var captureOnlyMode = false
    private var captureCount = 0
    private var unchangedFrameCount = 0
    private var screenshotRetryCount = 0
    private var gestureFailureCount = 0
    @Volatile private var translationRunning = false
    private var previousFrame: Bitmap? = null
    private val capturedSegments = ArrayList<File>()
    private var bubbleWasAdded = false

    // Recognizers are created on demand. Keeping all four ML Kit engines alive
    // from the beginning increased the chance of memory pressure on long chapters.
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
    // Each worker thread owns its OCR output. A crop retry can never overwrite another page's layer list.
    private val lastOcrTranslationLines = ThreadLocal.withInitial<List<TranslationLine>> { emptyList() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Apply the service capabilities at runtime as well as in the generated XML.
        // This makes window/package events available on OEM builds that ignore part
        // of the static accessibility configuration.
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 80L
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
        }
        MangaFoxRuntime.status = "خدمة العدسة متصلة"
        syncForegroundPackage()
        // Re-check several times because some Android skins expose the active window late.
        mainHandler.postDelayed({ syncForegroundPackage() }, 300L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 800L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 1500L)
        mainHandler.postDelayed({
            syncForegroundPackage()
            if (!hostActivityResumed && currentForegroundPackage.isNullOrBlank()) {
                currentForegroundPackage = "__external__"
                refreshLensAppFilter()
            }
        }, 1900L)
        refreshLensAppFilter()
        mainHandler.postDelayed({ recoverInterruptedCapture() }, 900L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (pkg != null && pkg != currentForegroundPackage) {
            currentForegroundPackage = pkg
            refreshLensAppFilter()
        }
    }

    private fun syncForegroundPackage() {
        val activePackage = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (activePackage != null && activePackage != currentForegroundPackage) {
            currentForegroundPackage = activePackage
            refreshLensAppFilter()
        }
    }

    fun onHostActivityResumed() {
        hostActivityResumed = true
        currentForegroundPackage = packageName
        refreshLensAppFilter()
    }

    fun onHostActivityPaused() {
        hostActivityResumed = false
        // Let the next app become active, then query the accessibility root.
        mainHandler.postDelayed({ syncForegroundPackage() }, 250L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 650L)
        mainHandler.postDelayed({
            syncForegroundPackage()
            // rootInActiveWindow can be null on some Infinix/XOS builds. The
            // Activity lifecycle is a reliable fallback: paused means external UI.
            if (!hostActivityResumed && currentForegroundPackage == packageName) {
                currentForegroundPackage = "__external__"
                refreshLensAppFilter()
            } else if (!hostActivityResumed && currentForegroundPackage.isNullOrBlank()) {
                currentForegroundPackage = "__external__"
                refreshLensAppFilter()
            }
        }, 1100L)
        mainHandler.postDelayed({ syncForegroundPackage() }, 1800L)
    }
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeFloatingBubble()
        if (instance === this) instance = null
        previousFrame?.recycle()
        previousFrame = null
        clearCaptureNotification()
        if (captureRunning && capturedSegments.isNotEmpty()) {
            // Do not erase completed screenshots if the system tears down the
            // accessibility service. They can be translated after reconnection.
            MangaFoxRuntime.status = "انقطعت خدمة الالتقاط؛ تم الاحتفاظ بـ ${capturedSegments.size} مقطع للاستعادة"
            MangaFoxRuntime.lastError = "أوقف النظام خدمة Manga Fox أثناء الالتقاط. أعد تفعيل الخدمة لاستعادة المقاطع المحفوظة."
        } else if (!translationRunning) {
            capturedSegments.forEach { runCatching { it.delete() } }
            capturedSegments.clear()
        }
        if (!translationRunning) closeRecognitionResources()
        worker.shutdown()
        super.onDestroy()
    }

    private fun closeRecognitionResources() {
        if (latinRecognizerDelegate.isInitialized()) runCatching { latinRecognizer.close() }
        if (japaneseRecognizerDelegate.isInitialized()) runCatching { japaneseRecognizer.close() }
        if (koreanRecognizerDelegate.isInitialized()) runCatching { koreanRecognizer.close() }
        if (chineseRecognizerDelegate.isInitialized()) runCatching { chineseRecognizer.close() }
        if (languageIdentifierDelegate.isInitialized()) runCatching { languageIdentifier.close() }
        translatorCache.values.forEach { translator -> runCatching { translator.close() } }
        translatorCache.clear()
    }

    fun startSingleCapture(startDelayMs: Long = 250L) {
        if (captureRunning || translationRunning) {
            toast("هناك عملية ترجمة أو التقاط قيد التنفيذ")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("الالتقاط التلقائي يحتاج Android 11 أو أحدث")
            return
        }
        captureRunning = true
        fullChapterMode = false
        capturedSegments.forEach { runCatching { it.delete() } }
        capturedSegments.clear()
        MangaFoxRuntime.status = "التقاط الشاشة الحالية..."
        removeFloatingBubble()
        mainHandler.postDelayed({
            requestScreenshot { bitmap, error ->
                if (bitmap == null) {
                    finishCaptureWithError(error ?: "تعذر التقاط الشاشة")
                } else {
                    capturedSegments.forEach { runCatching { it.delete() } }
                    capturedSegments.clear()
                    runCatching { capturedSegments.add(storeCaptureSegment(bitmap, 0)) }
                        .onSuccess { finishCaptureAndTranslate() }
                        .onFailure { finishCaptureWithError("تعذر حفظ لقطة الشاشة: ${it.localizedMessage ?: "خطأ تخزين"}") }
                    bitmap.recycle()
                }
            }
        }, startDelayMs.coerceIn(200L, 5000L))
    }

    fun startFullChapterCapture(startDelayMs: Long = 450L) {
        if (captureRunning || translationRunning) {
            toast("هناك عملية ترجمة أو التقاط قيد التنفيذ")
            return
        }
        val pendingRecovery = pendingCaptureFiles().isNotEmpty()
        if (pendingRecovery) {
            recoverInterruptedCapture()
            toast("عثر Manga Fox على مقاطع محفوظة من جلسة سابقة؛ بدأت استعادتها أولاً")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("ترجمة الفصل الكامل تحتاج Android 11 أو أحدث")
            return
        }
        captureRunning = true
        fullChapterMode = true
        captureOnlyMode = false
        captureCount = 0
        unchangedFrameCount = 0
        screenshotRetryCount = 0
        gestureFailureCount = 0
        previousFrame?.recycle()
        previousFrame = null
        capturedSegments.forEach { runCatching { it.delete() } }
        capturedSegments.clear()
        MangaFoxRuntime.status = "بدء التقاط الفصل العمودي..."
        removeFloatingBubble()
        notifyCaptureProgress()
        mainHandler.postDelayed({ captureNextFrame() }, startDelayMs.coerceIn(250L, 5000L))
    }

    fun startFullChapterCaptureOnly(startDelayMs: Long = 450L) {
        if (captureRunning || translationRunning) {
            toast("هناك عملية ترجمة أو التقاط قيد التنفيذ")
            return
        }
        if (pendingCaptureFiles().isNotEmpty()) {
            recoverInterruptedCapture()
            toast("توجد مقاطع محفوظة من جلسة سابقة؛ تتم استعادتها أولاً")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("التقاط الفصل الكامل يحتاج Android 11 أو أحدث")
            return
        }
        captureRunning = true
        fullChapterMode = true
        captureOnlyMode = true
        captureCount = 0
        unchangedFrameCount = 0
        screenshotRetryCount = 0
        gestureFailureCount = 0
        previousFrame?.recycle()
        previousFrame = null
        capturedSegments.forEach { runCatching { it.delete() } }
        capturedSegments.clear()
        MangaFoxRuntime.status = "بدء التقاط الفصل دون ترجمة..."
        removeFloatingBubble()
        notifyCaptureProgress()
        mainHandler.postDelayed({ captureNextFrame() }, startDelayMs.coerceIn(250L, 5000L))
    }

    private fun captureNextFrame() {
        if (!captureRunning || !fullChapterMode) return
        if (captureCount >= MAX_CAPTURE_FRAMES) {
            finishCaptureAndTranslate("وصل الالتقاط إلى حد الأمان (${MAX_CAPTURE_FRAMES} لقطة). يمكنك بدء التقاط آخر لاستكمال بقية الفصل.")
            return
        }
        requestScreenshot { bitmap, error ->
            if (!captureRunning) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                screenshotRetryCount++
                if (screenshotRetryCount < 3 && captureRunning) {
                    MangaFoxRuntime.status = "تعذر التصوير مؤقتاً؛ إعادة المحاولة ${screenshotRetryCount}/2"
                    notifyCaptureProgress()
                    mainHandler.postDelayed({ captureNextFrame() }, 900L * screenshotRetryCount)
                } else if (capturedSegments.isNotEmpty()) {
                    finishCaptureAndTranslate("توقف الالتقاط بعد محاولات التصوير: ${error ?: "تعذر أخذ لقطة أخرى"}")
                } else {
                    finishCaptureWithError(error ?: "تعذر التقاط الشاشة بعد 3 محاولات")
                }
                return@requestScreenshot
            }
            screenshotRetryCount = 0

            val displayWidth = bitmap.width
            val displayHeight = bitmap.height
            val comparisonFrame = try {
                createComparisonFrame(bitmap)
            } catch (e: Exception) {
                bitmap.recycle()
                finishCaptureWithError("تعذر تجهيز إطار المقارنة: ${e.localizedMessage ?: "نقص ذاكرة"}")
                return@requestScreenshot
            }
            val previous = previousFrame
            if (previous != null && looksUnchanged(previous, comparisonFrame)) {
                comparisonFrame.recycle()
                bitmap.recycle()
                unchangedFrameCount++
                MangaFoxRuntime.status = "لم يتغير المحتوى؛ محاولة التمرير ${unchangedFrameCount}/3..."
                notifyCaptureProgress()
                if (unchangedFrameCount >= 3) {
                    finishCaptureAndTranslate("وصل الالتقاط إلى نهاية المحتوى بعد 3 محاولات تمرير دون تغير")
                } else {
                    dispatchNextSwipe(displayWidth, displayHeight)
                }
                return@requestScreenshot
            }

            unchangedFrameCount = 0
            val overlapSmall = if (previous == null) 0 else findVerticalOverlap(previous, comparisonFrame)
            val overlapPx = if (overlapSmall <= 0) 0 else
                ((overlapSmall.toFloat() / comparisonFrame.height) * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
            try {
                capturedSegments.add(storeCaptureSegment(bitmap, overlapPx))
            } catch (e: Exception) {
                comparisonFrame.recycle()
                bitmap.recycle()
                finishCaptureAndTranslate("تعذر حفظ جزء من الفصل: ${e.localizedMessage ?: "خطأ تخزين"}")
                return@requestScreenshot
            }
            previousFrame?.recycle()
            previousFrame = comparisonFrame
            captureCount++
            MangaFoxRuntime.status = "التقاط الفصل: $captureCount لقطة · الحد الوقائي $MAX_CAPTURE_FRAMES"
            notifyCaptureProgress()
            dispatchNextSwipe(displayWidth, displayHeight)
            // The compressed JPEG segment is now on disk; release the full-size
            // display bitmap and keep only its small comparison copy in RAM.
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun dispatchNextSwipe(displayWidth: Int, displayHeight: Int) {
        if (!captureRunning || !fullChapterMode) return
        val width = displayWidth.toFloat()
        val height = displayHeight.toFloat()
        val path = Path().apply {
            moveTo(width * 0.52f, height * 0.84f)
            lineTo(width * 0.52f, height * 0.39f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 720L))
            .build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                gestureFailureCount = 0
                mainHandler.postDelayed({ captureNextFrame() }, 1450L)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                gestureFailureCount++
                if (gestureFailureCount >= 3) {
                    finishCaptureAndTranslate("تعذر تمرير صفحة القراءة بعد 3 محاولات؛ تمت معالجة المقاطع التي جُمعت")
                } else {
                    MangaFoxRuntime.status = "أُلغيت حركة التمرير؛ إعادة المحاولة ${gestureFailureCount}/3"
                    mainHandler.postDelayed({ captureNextFrame() }, 900L)
                }
            }
        }
        val dispatched = dispatchGesture(gesture, callback, mainHandler)
        if (!dispatched) {
            gestureFailureCount++
            if (gestureFailureCount >= 3) {
                finishCaptureAndTranslate("رفض النظام تنفيذ التمرير؛ تمت معالجة المقاطع التي جُمعت")
            } else {
                mainHandler.postDelayed({ captureNextFrame() }, 900L)
            }
        }
    }

    fun stopCaptureByUser() {
        if (!captureRunning) {
            toast("لا توجد عملية التقاط نشطة")
            return
        }
        if (capturedSegments.isEmpty()) finishCaptureWithError("تم إيقاف الالتقاط قبل التقاط أي جزء")
        else finishCaptureAndTranslate("أوقف المستخدم الالتقاط يدوياً؛ تمت ترجمة الأجزاء التي جُمعت")
    }

    private fun requestScreenshot(callback: (Bitmap?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null, "يلزم Android 11 أو أحدث")
            return
        }

        val delivered = AtomicBoolean(false)
        val timeout = Runnable {
            if (delivered.compareAndSet(false, true)) {
                callback(null, "انتهت مهلة تصوير الشاشة؛ لم يستجب النظام")
            }
        }
        mainHandler.postDelayed(timeout, 9000L)
        fun deliver(bitmap: Bitmap?, error: String?) {
            if (delivered.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                callback(bitmap, error)
            } else {
                bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        }

        val screenshotCallback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                var buffer: HardwareBuffer? = null
                var hardwareBitmap: Bitmap? = null
                var result: Bitmap? = null
                var failure: String? = null
                try {
                    val screenshotBuffer = screenshot.hardwareBuffer
                    buffer = screenshotBuffer
                    hardwareBitmap = Bitmap.wrapHardwareBuffer(screenshotBuffer, screenshot.colorSpace)
                    result = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    if (result == null) failure = "أعاد النظام لقطة شاشة فارغة"
                } catch (e: Exception) {
                    failure = "تعذر تجهيز لقطة الشاشة: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally {
                    runCatching { hardwareBitmap?.recycle() }
                    runCatching { buffer?.close() }
                }
                deliver(result, failure)
            }

            override fun onFailure(errorCode: Int) {
                val message = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "هذا التطبيق يمنع تصوير الشاشة (نافذة آمنة)"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "لا توجد صلاحية التقاط للشاشة"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "الالتقاط سريع جداً؛ أعد المحاولة"
                    else -> "فشل التقاط الشاشة، رمز الخطأ: $errorCode"
                }
                deliver(null, message)
            }
        }

        try {
            // Capture the complete display, not a cropped active window. Gesture
            // coordinates are display coordinates too, so both now share one space.
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, screenshotCallback)
        } catch (e: Exception) {
            deliver(null, "تعذر بدء الالتقاط: ${e.localizedMessage ?: "خطأ نظام"}")
        }
    }

    private fun createComparisonFrame(source: Bitmap): Bitmap {
        val maxWidth = 720
        if (source.width <= maxWidth) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("تعذر إنشاء نسخة المقارنة")
        }
        val scaledHeight = (source.height * (maxWidth.toFloat() / source.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, scaledHeight, true)
    }

    /**
     * Finds how many pixels at the top of the new frame repeat the bottom of the
     * previous frame. If a reliable match cannot be found, returns zero so the
     * app prefers a harmless duplicate strip over deleting real artwork.
     */
    private fun findVerticalOverlap(previous: Bitmap, current: Bitmap): Int {
        if (previous.width != current.width || previous.height != current.height) return 0
        val h = previous.height
        // A 45% swipe normally leaves about 55% overlap; the wider range allows
        // for momentum and readers that clamp or accelerate scroll gestures.
        val minOverlap = (h * 0.24f).toInt().coerceAtLeast(24)
        val maxOverlap = (h * 0.78f).toInt().coerceAtMost(h - 24)
        if (maxOverlap <= minOverlap) return 0

        val sampleRows = 80
        val sampleColumns = 48
        val xPositions = IntArray(sampleColumns) { column ->
            ((column + 0.5f) * previous.width / sampleColumns).toInt()
                .coerceIn(0, previous.width - 1)
        }
        val candidates = ArrayList<Pair<Int, Double>>(maxOverlap - minOverlap + 1)

        for (overlap in minOverlap..maxOverlap) {
            var total = 0L
            var samples = 0
            for (row in 0 until sampleRows) {
                val offset = ((row + 0.5f) * overlap / sampleRows).toInt()
                val previousY = (h - overlap + offset).coerceIn(0, h - 1)
                val currentY = offset.coerceIn(0, h - 1)
                for (x in xPositions) {
                    val a = previous.getPixel(x, previousY)
                    val b = current.getPixel(x, currentY)
                    val dr = abs(Color.red(a) - Color.red(b))
                    val dg = abs(Color.green(a) - Color.green(b))
                    val db = abs(Color.blue(a) - Color.blue(b))
                    val grayA = (Color.red(a) * 3 + Color.green(a) * 6 + Color.blue(a)) / 10
                    val grayB = (Color.red(b) * 3 + Color.green(b) * 6 + Color.blue(b)) / 10
                    total += dr + dg + db + abs(grayA - grayB)
                    samples += 4
                }
            }
            candidates.add(overlap to (total.toDouble() / samples.coerceAtLeast(1)))
        }

        val sorted = candidates.sortedBy { it.second }
        val best = sorted.firstOrNull() ?: return 0
        // Ignore nearby candidates when judging confidence: they are simply the
        // shoulders of the same match. Compare against a genuinely different offset.
        val secondDistinct = sorted.firstOrNull { abs(it.first - best.first) >= 12 }
        if (best.second > 18.0) return 0
        if (secondDistinct != null && secondDistinct.second - best.second < 1.2) return 0

        // Cut the measured overlap exactly. V2.3 left 24px / ~1.8% uncut on
        // purpose, which produced a visible repeated strip at every join.
        return best.first.coerceIn(0, h - 1)
    }

    private fun cropContent(source: Bitmap, overlapTopPx: Int): Bitmap {
        // Do not trim fixed percentages from screen edges: panels and speech bubbles
        // may legitimately touch them. Only remove verified repeated rows.
        val cut = overlapTopPx.coerceIn(0, source.height - 1)
        val cropped = Bitmap.createBitmap(source, 0, cut, source.width, source.height - cut)
        val maxWidth = 1080
        if (cropped.width <= maxWidth) return cropped
        val targetHeight = (cropped.height * (maxWidth.toFloat() / cropped.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, targetHeight, true)
        cropped.recycle()
        return scaled
    }

    private fun storeCaptureSegment(source: Bitmap, overlapTopPx: Int): File {
        val segment = cropContent(source, overlapTopPx)
        val file = File(filesDir, "mfox_capture_${System.currentTimeMillis()}_${captureCount}_${capturedSegments.size}.jpg")
        try {
            FileOutputStream(file).use { stream ->
                if (!segment.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                    throw IllegalStateException("تعذر ضغط صورة الالتقاط")
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            if (!segment.isRecycled) segment.recycle()
        }
        return file
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
        return average < 2.3
    }

    private fun finishCaptureAndTranslate(note: String = "") {
        if (!captureRunning) return
        captureRunning = false
        fullChapterMode = false
        clearCaptureNotification()
        previousFrame?.recycle()
        previousFrame = null
        restoreFloatingBubble()
        if (capturedSegments.isEmpty()) {
            finishCaptureWithError("لم يتم التقاط أي جزء من الفصل")
            return
        }
        val workPages = ArrayList(capturedSegments)
        capturedSegments.clear()
        if (captureOnlyMode) {
            captureOnlyMode = false
            worker.execute {
                try {
                    val saved = workPages.mapIndexed { index, source ->
                        val target = File(filesDir, "mfox_result_capture_${System.currentTimeMillis()}_$index.jpg")
                        if (!source.renameTo(target)) source.copyTo(target, overwrite = true)
                        source.delete()
                        target.absolutePath
                    }
                    Handler(Looper.getMainLooper()).post {
                        MangaFoxRuntime.sourcePages = saved
                        MangaFoxRuntime.resultPages = saved
                        MangaFoxRuntime.translationLayerFiles = saved.map { "" }
                        MangaFoxRuntime.resultTitle = "فصل ملتقط دون ترجمة"
                        MangaFoxRuntime.captureOnlyResult = true
                        MangaFoxRuntime.qualitySummary = "صور أصلية دون OCR أو ترجمة"
                        MangaFoxRuntime.progressPercent = 100
                        MangaFoxRuntime.progressDetail = "اكتمل الالتقاط: ${saved.size} صورة"
                        MangaFoxRuntime.status = "تم التقاط الفصل دون ترجمة؛ يمكنك تصديره PDF"
                        MangaFoxRuntime.persist(this)
                        toast("اكتمل التقاط ${saved.size} صورة دون ترجمة")
                        val host = applicationContext
                        // Open the result screen through the existing activity action.
                        startActivity(Intent(host, MainActivity::class.java).apply {
                            action = ACTION_SHOW_RESULTS
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                } catch (e: Exception) {
                    workPages.forEach { runCatching { it.delete() } }
                    Handler(Looper.getMainLooper()).post {
                        finishCaptureWithError("تعذر تجهيز الصور: ${e.localizedMessage ?: "خطأ"}")
                    }
                }
            }
        } else {
            translateSavedSegments(workPages, note, if (workPages.size > 1) "الفصل العمودي" else "الصفحة الحالية")
        }
    }

    private fun pendingCaptureFiles(): List<File> {
        return listOf(filesDir, cacheDir)
            .flatMap { directory -> directory.listFiles()?.asList().orEmpty() }
            .filter { it.isFile && it.name.startsWith("mfox_capture_") && it.length() > 0L }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name }
    }

    private fun recoverInterruptedCapture() {
        if (captureRunning || translationRunning || instance !== this) return
        val pending = pendingCaptureFiles()
        if (pending.isEmpty()) return
        MangaFoxRuntime.status = "استعادة ${pending.size} مقطع محفوظ بعد انقطاع الخدمة..."
        translateSavedSegments(
            ArrayList(pending),
            "تمت استعادة المقاطع المحفوظة بعد إعادة تشغيل خدمة الالتقاط.",
            "الفصل المستعاد"
        )
    }

    fun retryManualRegion(sourcePath: String, resultPath: String, selectedRect: Rect, completed: (Boolean, String) -> Unit) {
        if (captureRunning || translationRunning) {
            completed(false, "هناك عملية أخرى قيد التنفيذ")
            return
        }
        translationRunning = true
        MangaFoxRuntime.progressPercent = 0
        MangaFoxRuntime.progressDetail = "قراءة المنطقة المحددة"
        updateTranslationLens(0, "قراءة المنطقة المحددة")
        worker.execute {
            var original: Bitmap? = null
            var crop: Bitmap? = null
            var translatedCrop: Bitmap? = null
            var output: Bitmap? = null
            try {
                original = BitmapFactory.decodeFile(sourcePath) ?: throw IllegalStateException("تعذر فتح الصورة الأصلية")
                val safe = Rect(selectedRect).apply {
                    left = left.coerceIn(0, original!!.width - 1)
                    top = top.coerceIn(0, original!!.height - 1)
                    right = right.coerceIn(left + 1, original!!.width)
                    bottom = bottom.coerceIn(top + 1, original!!.height)
                }
                crop = Bitmap.createBitmap(original!!, safe.left, safe.top, safe.width(), safe.height())
                translatedCrop = translateBitmap(crop!!) { fraction, detail ->
                    updateTranslationLens((fraction * 95f).toInt(), "منطقة محددة · $detail")
                }
                if (lastOcrTranslationLines.get().isEmpty()) {
                    throw IllegalStateException("لم يُكتشف نص قابل للترجمة داخل المنطقة المحددة")
                }
                // The layer update is mandatory: never report success while only the JPG was changed.
                val pageIndex = MangaFoxRuntime.resultPages.indexOfFirst {
                    File(it).absolutePath == File(resultPath).absolutePath
                }
                if (pageIndex < 0) throw IllegalStateException("تعذر ربط المنطقة بالصفحة الأصلية")
                while (MangaFoxRuntime.translationLayerFiles.size < MangaFoxRuntime.resultPages.size) {
                    MangaFoxRuntime.translationLayerFiles = MangaFoxRuntime.translationLayerFiles + ""
                }
                val layerPath = MangaFoxRuntime.translationLayerFiles.getOrNull(pageIndex)
                val layerFile = layerPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                    ?.takeIf { it.isFile }
                    ?: File(filesDir, "mfox_layers_manual_${System.currentTimeMillis()}_$pageIndex.json")
                val saved = if (layerFile.isFile) {
                    org.json.JSONArray(layerFile.readText(Charsets.UTF_8))
                } else {
                    org.json.JSONArray()
                }
                val refreshed = ArrayList<org.json.JSONObject>()
                for (i in 0 until saved.length()) {
                    val record = saved.optJSONObject(i) ?: continue
                    val oldRect = Rect(record.optInt("left"), record.optInt("top"), record.optInt("right"), record.optInt("bottom"))
                    val replaced = lastOcrTranslationLines.get().any { line ->
                        val newRect = Rect(line.bounds).apply { offset(safe.left, safe.top) }
                        val overlap = Rect(oldRect)
                        if (!overlap.intersect(newRect)) false else {
                            val intersection = overlap.width().toFloat() * overlap.height()
                            val union = oldRect.width().toFloat() * oldRect.height() + newRect.width().toFloat() * newRect.height() - intersection
                            val smaller = min(oldRect.width() * oldRect.height(), newRect.width() * newRect.height()).coerceAtLeast(1)
                            union > 0f && (intersection / union > 0.28f || intersection / smaller > 0.58f)
                        }
                    }
                    if (!replaced) refreshed.add(record)
                }
                lastOcrTranslationLines.get().forEach { line ->
                    val rect = Rect(line.bounds).apply { offset(safe.left, safe.top) }
                    val layer = org.json.JSONObject()
                        .put("left", rect.left).put("top", rect.top)
                        .put("right", rect.right).put("bottom", rect.bottom)
                        .put("source", line.sourceText).put("translated", line.translated)
                        .put("language", line.language).put("vertical", line.vertical)
                        .put("eraseOnly", line.eraseOnly).put("shape", line.shape)
                    line.containerBounds?.let { container ->
                        val mappedContainer = Rect(container).apply { offset(safe.left, safe.top) }
                        layer.put("containerLeft", mappedContainer.left)
                            .put("containerTop", mappedContainer.top)
                            .put("containerRight", mappedContainer.right)
                            .put("containerBottom", mappedContainer.bottom)
                    }
                    refreshed.add(layer)
                }
                val outputLayers = org.json.JSONArray()
                refreshed.forEach { outputLayers.put(it) }
                val layerTemp = File(layerFile.parentFile, layerFile.name + ".tmp")
                layerTemp.writeText(outputLayers.toString(), Charsets.UTF_8)
                if (!layerTemp.renameTo(layerFile)) {
                    layerTemp.copyTo(layerFile, overwrite = true)
                    layerTemp.delete()
                }
                MangaFoxRuntime.translationLayerFiles = MangaFoxRuntime.translationLayerFiles.toMutableList().also {
                    it[pageIndex] = layerFile.absolutePath
                }
                MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                output = BitmapFactory.decodeFile(resultPath) ?: original!!.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(output!!).drawBitmap(translatedCrop!!, safe.left.toFloat(), safe.top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
                val target = File(resultPath)
                val temp = File(target.parentFile, target.nameWithoutExtension + "_region.tmp." + target.extension)
                writeRenderedBitmap(output!!, temp)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                mainHandler.post {
                    translationRunning = false
                    MangaFoxRuntime.progressPercent = 100
                    MangaFoxRuntime.progressDetail = "اكتملت إعادة قراءة المنطقة"
                    updateTranslationLens(100, MangaFoxRuntime.progressDetail)
                    completed(true, "اكتملت إعادة قراءة المنطقة وترجمتها")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    translationRunning = false
                    completed(false, "تعذرت إعادة ترجمة المنطقة: ${e.localizedMessage ?: "خطأ غير معروف"}")
                }
            } finally {
                listOf(original, crop, translatedCrop, output).forEach { bitmap ->
                    bitmap?.let { if (!it.isRecycled) it.recycle() }
                }
            }
        }
    }

    /** Re-render saved per-bubble translation layers with the current font settings, without OCR. */
    fun rerenderLastResultsFromLayers(onComplete: (() -> Unit)? = null) {
        if (translationRunning || captureRunning) {
            toast("انتظر انتهاء العملية الحالية")
            return
        }
        val sources = MangaFoxRuntime.sourcePages.map { File(it) }
        val targets = MangaFoxRuntime.resultPages.map { File(it) }
        val layers = MangaFoxRuntime.translationLayerFiles.map { File(it) }
        if (sources.isEmpty() || sources.size != targets.size ||
            sources.any { !it.isFile } || targets.any { !it.isFile }) {
            toast("الصور الأصلية أو النتائج غير مكتملة؛ لا يمكن إعادة التنسيق")
            return
        }
        // A missing layer file is recoverable: create an empty editable layer for that page.
        val normalizedLayers = ArrayList<String>(sources.size)
        sources.indices.forEach { index ->
            // `layers` already contains File objects; do not treat an entry as a String path.
            val existing: File? = layers.getOrNull(index)
            val usable = existing?.takeIf { it.isFile }
                ?: File(filesDir, "mfox_layers_recovered_${System.currentTimeMillis()}_$index.json").apply {
                    writeText(org.json.JSONArray().toString(), Charsets.UTF_8)
                }
            normalizedLayers.add(usable.absolutePath)
        }
        MangaFoxRuntime.translationLayerFiles = normalizedLayers
        MangaFoxRuntime.persist(this)
        translationRunning = true
        MangaFoxRuntime.progressPercent = 0
        MangaFoxRuntime.progressDetail = "إعادة تنسيق الطبقات المحفوظة دون OCR"
        updateTranslationLens(0, MangaFoxRuntime.progressDetail)
        worker.execute {
            try {
                sources.indices.forEach { index ->
                    var original: Bitmap? = null
                    var rendered: Bitmap? = null
                    try {
                        val json = org.json.JSONArray(File(normalizedLayers[index]).readText(Charsets.UTF_8))
                        // Never use a previously rendered result as the next render base.
                        // This prevents translated glyphs from accumulating after edits/font changes.
                        val basePath = sources[index].absolutePath
                        original = BitmapFactory.decodeFile(basePath)
                            ?: throw IllegalStateException("تعذر قراءة الصورة الأساسية ${index + 1}")
                        val records = ArrayList<TranslationLine>(json.length())
                        for (recordIndex in 0 until json.length()) {
                            val item = json.optJSONObject(recordIndex) ?: continue
                            if (!item.has("left") || !item.has("top") || !item.has("right") || !item.has("bottom")) continue
                            val rect = Rect(
                                item.optInt("left"), item.optInt("top"),
                                item.optInt("right"), item.optInt("bottom")
                            )
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
                                    ).takeIf { it.width() > 8 && it.height() > 8 } else null
                                ))
                            }
                        }
                        rendered = drawTranslatedLines(original!!, records)
                        val target = targets[index]
                        val temp = File(target.parentFile, target.nameWithoutExtension + "_layers.tmp." + target.extension)
                        writeRenderedBitmap(rendered!!, temp)
                        if (!temp.renameTo(target)) {
                            temp.copyTo(target, overwrite = true)
                            temp.delete()
                        }
                        val percent = (((index + 1).toFloat() / sources.size) * 100f).toInt()
                        updateTranslationLens(percent.coerceAtMost(99), "إعادة تنسيق الطبقات ${index + 1}/${sources.size}")
                    } finally {
                        original?.let { if (!it.isRecycled) it.recycle() }
                        rendered?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
                mainHandler.post {
                    translationRunning = false
                    MangaFoxRuntime.progressPercent = 100
                    MangaFoxRuntime.progressDetail = "تم تطبيق إعدادات الخط دون تشغيل OCR"
                    MangaFoxRuntime.status = MangaFoxRuntime.progressDetail
                    MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                    updateTranslationLens(100, MangaFoxRuntime.progressDetail)
                    mainHandler.postDelayed({ if (!translationRunning) resetLensProgress() }, 3500L)
                    notifyResultsReady()
                    toast("تم تحديث الخط والحجم دون إعادة OCR")
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    translationRunning = false
                    MangaFoxRuntime.lastError = "تعذرت إعادة تنسيق الطبقات: ${e.localizedMessage ?: "خطأ غير معروف"}"
                    MangaFoxRuntime.progressDetail = "تعذرت إعادة تنسيق الطبقات"
                    updateTranslationLens(-1, MangaFoxRuntime.progressDetail)
                    toast(MangaFoxRuntime.lastError)
                }
            }
        }
    }

    fun retranslateLastResults() {
        if (translationRunning || captureRunning) {
            toast("انتظر انتهاء العملية الحالية")
            return
        }
        val originals = MangaFoxRuntime.sourcePages.map { File(it) }.filter { it.isFile && it.length() > 0L }
        if (originals.isEmpty()) {
            toast("الصور الأصلية غير متوفرة؛ أعد التقاط الفصل")
            return
        }
        translateSavedSegments(ArrayList(originals), "أعيد تطبيق إعدادات الخط الحالية.", MangaFoxRuntime.resultTitle, preserveInputs = true)
    }

    private fun combineVerticalBitmaps(parts: List<Bitmap>): Bitmap {
        require(parts.isNotEmpty()) { "لا توجد صور للدمج" }
        if (parts.size == 1) return parts.first()
        val naturalWidth = parts.maxOf { it.width }.coerceAtLeast(1)
        val naturalHeights = parts.map { (it.height * (naturalWidth.toFloat() / it.width)).roundToInt().coerceAtLeast(1) }
        val naturalTotalHeight = naturalHeights.sum().coerceAtLeast(1)
        // Use the device heap budget to avoid allocating a huge vertical bitmap on low-memory phones.
        val heapBudgetMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt().coerceAtLeast(256)
        val profile = MangaFoxPerformanceAdvisor.forDevice(heapBudgetMb, Runtime.getRuntime().availableProcessors())
        val maxStripHeight = profile.maxImageSide * 2
        val globalScale = minOf(
            1f,
            profile.maxImageSide.toFloat() / naturalWidth.toFloat(),
            maxStripHeight.toFloat() / naturalTotalHeight.toFloat()
        )
        val width = (naturalWidth * globalScale).roundToInt().coerceAtLeast(1)
        val outputHeights = parts.map { (it.height * (width.toFloat() / it.width)).roundToInt().coerceAtLeast(1) }
        val height = outputHeights.sum().coerceAtLeast(1)
        val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var top = 0
        parts.forEachIndexed { index, bitmap ->
            val bottom = top + outputHeights[index]
            canvas.drawBitmap(bitmap, null, Rect(0, top, width, bottom), paint)
            top = bottom
        }
        return combined
    }

    private fun translateSavedSegments(
        workPages: ArrayList<File>,
        note: String,
        title: String,
        preserveInputs: Boolean = false
    ) {
        if (workPages.isEmpty() || translationRunning) return
        translationRunning = true
        MangaFoxRuntime.progressPercent = 0
        MangaFoxRuntime.progressDetail = "تهيئة الترجمة"
        MangaFoxRuntime.detectedBlocks = 0
        MangaFoxRuntime.translatedBlocks = 0
        MangaFoxRuntime.failedBlocks = 0
        MangaFoxRuntime.skippedBlocks = 0
        MangaFoxRuntime.failedBlockSamples = emptyList()
        MangaFoxRuntime.qualitySummary = "جارٍ تحليل النصوص..."
        MangaFoxRuntime.status = "محرك v3.0.6b — بدء استخراج النصوص وترجمتها..."
        updateTranslationLens(0, "تهيئة الترجمة")
        if (note.isNotBlank()) MangaFoxRuntime.lastError = note

        try {
            worker.execute {
                val translatedFiles = ArrayList<String>()
                val newSourceFiles = ArrayList<String>()
                val newLayerFiles = ArrayList<String>()
                val groupSize = if (preserveInputs) 1 else 2
                val groups = workPages.chunked(groupSize)
                try {
                    groups.forEachIndexed { index, groupFiles ->
                        MangaFoxRuntime.status = "دمج وتحليل المقطع ${index + 1} من ${groups.size}..."
                        updateTranslationLens((index * 100 / groups.size).coerceIn(0, 99), "دمج OCR للجزأين ${index + 1}/${groups.size}")
                        val decodedParts = ArrayList<Bitmap>(groupFiles.size)
                        var page: Bitmap? = null
                        var translated: Bitmap? = null
                        try {
                            groupFiles.forEach { file ->
                                decodedParts.add(
                                    BitmapFactory.decodeFile(file.absolutePath)
                                        ?: throw IllegalStateException("تعذر قراءة ${file.name}")
                                )
                            }
                            page = combineVerticalBitmaps(decodedParts)
                            val mergedPage = page ?: throw IllegalStateException("تعذر دمج لقطات الفصل")
                            if (!preserveInputs) {
                                val sourceCopy = File(filesDir, "mfox_source_${System.currentTimeMillis()}_${index}.jpg")
                                FileOutputStream(sourceCopy).use { stream ->
                                    if (!mergedPage.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                                        throw IllegalStateException("تعذر حفظ الصورة الأصلية المدمجة")
                                    }
                                }
                                newSourceFiles.add(sourceCopy.absolutePath)
                            }
                            val outputBitmap = translateBitmap(mergedPage) { fraction, detail ->
                                val aggregate = (((index + fraction.coerceIn(0f, 1f)) / groups.size) * 100f).toInt().coerceIn(0, 99)
                                updateTranslationLens(aggregate, "${index + 1}/${groups.size} · $detail")
                            }
                            translated = outputBitmap
                            val layerFile = File(filesDir, "mfox_layers_${System.currentTimeMillis()}_$index.json")
                            val layerArray = org.json.JSONArray()
                            lastOcrTranslationLines.get().forEach { line ->
                                layerArray.put(org.json.JSONObject()
                                    .put("left", line.bounds.left).put("top", line.bounds.top)
                                    .put("right", line.bounds.right).put("bottom", line.bounds.bottom)
                                    .put("source", line.sourceText).put("translated", line.translated)
                                    .put("language", line.language).put("vertical", line.vertical)
                                    .put("eraseOnly", line.eraseOnly).put("shape", line.shape).also { layer ->
                                        line.containerBounds?.let { container ->
                                            layer.put("containerLeft", container.left)
                                                .put("containerTop", container.top)
                                                .put("containerRight", container.right)
                                                .put("containerBottom", container.bottom)
                                        }
                                    })
                            }
                            FileOutputStream(layerFile).use { stream ->
                                stream.write(layerArray.toString().toByteArray(Charsets.UTF_8))
                            }
                            newLayerFiles.add(layerFile.absolutePath)
                            // PNG prevents JPEG ringing/gray halos around freshly repainted speech balloons.
                            val outputFile = File(filesDir, "mfox_result_${System.currentTimeMillis()}_$index.png")
                            writeRenderedBitmap(outputBitmap, outputFile)
                            translatedFiles.add(outputFile.absolutePath)
                            updateTranslationLens((((index + 1).toFloat() / groups.size) * 100f).toInt().coerceIn(0, 99), "اكتمل المقطع ${index + 1} من ${groups.size}")
                        } finally {
                            page?.let { merged ->
                                if (decodedParts.none { it === merged } && !merged.isRecycled) merged.recycle()
                            }
                            translated?.let { if (!it.isRecycled) it.recycle() }
                            decodedParts.forEach { if (!it.isRecycled) it.recycle() }
                            if (!preserveInputs) groupFiles.forEach { runCatching { it.delete() } }
                        }
                    }
                    val oldFiles = MangaFoxRuntime.resultPages
                    val oldLayers = MangaFoxRuntime.translationLayerFiles
                    MangaFoxRuntime.resultPages = translatedFiles
                    MangaFoxRuntime.translationLayerFiles = newLayerFiles.toList()
                    oldFiles.forEach { runCatching { File(it).delete() } }
                    oldLayers.forEach { runCatching { File(it).delete() } }
                    if (!preserveInputs) {
                        val oldSources = MangaFoxRuntime.sourcePages
                        MangaFoxRuntime.sourcePages = newSourceFiles.toList()
                        oldSources.forEach { runCatching { File(it).delete() } }
                    }
                    MangaFoxRuntime.resultTitle = title
                    MangaFoxRuntime.qualitySummary = "اكتُشف ${MangaFoxRuntime.detectedBlocks} · تُرجم ${MangaFoxRuntime.translatedBlocks} · فشل ${MangaFoxRuntime.failedBlocks} · تُرك ${MangaFoxRuntime.skippedBlocks}"
                    MangaFoxRuntime.status = "اكتملت الترجمة: ${translatedFiles.size} صورة مدمجة · ${MangaFoxRuntime.qualitySummary}"
                    MangaFoxRuntime.lastError = note
                    MangaFoxRuntime.persist(this@MangaFoxAccessibilityService)
                    MangaFoxRuntime.progressPercent = 100
                    MangaFoxRuntime.progressDetail = "اكتملت الترجمة"
                    mainHandler.post {
                        translationRunning = false
                        updateTranslationLens(100, "اكتملت الترجمة")
                        mainHandler.postDelayed({ if (!translationRunning) resetLensProgress() }, 5000L)
                        if (instance !== this@MangaFoxAccessibilityService) closeRecognitionResources()
                        val notified = notifyResultsReady()
                        toast(if (notified) "اكتملت الترجمة العربية. اضغط إشعار Manga Fox لعرض الفصل" else "اكتملت الترجمة. افتح Manga Fox ثم اختر عرض آخر نتيجة مترجمة")
                    }
                } catch (e: Exception) {
                    if (!preserveInputs) workPages.forEach { runCatching { it.delete() } }
                    newSourceFiles.forEach { runCatching { File(it).delete() } }
                    newLayerFiles.forEach { runCatching { File(it).delete() } }
                    translatedFiles.forEach { runCatching { File(it).delete() } }
                    MangaFoxRuntime.lastError = "حدث خطأ أثناء الترجمة: ${e.localizedMessage ?: "خطأ غير معروف"}"
                    MangaFoxRuntime.status = "تعذرت الترجمة"
                    MangaFoxRuntime.progressDetail = "تعذرت الترجمة"
                    mainHandler.post {
                        translationRunning = false
                        updateTranslationLens(-1, "تعذرت الترجمة")
                        mainHandler.postDelayed({ resetLensProgress() }, 4000L)
                        if (instance !== this@MangaFoxAccessibilityService) closeRecognitionResources()
                        toast(MangaFoxRuntime.lastError)
                    }
                }
            }
        } catch (e: Exception) {
            translationRunning = false
            workPages.forEach { runCatching { it.delete() } }
            finishCaptureWithError("تعذر تشغيل عامل الترجمة: ${e.localizedMessage ?: "خطأ داخلي"}")
        }
    }

    private fun finishCaptureWithError(message: String) {
        captureRunning = false
        fullChapterMode = false
        clearCaptureNotification()
        previousFrame?.recycle()
        previousFrame = null
        capturedSegments.forEach { runCatching { it.delete() } }
        capturedSegments.clear()
        MangaFoxRuntime.status = "تعذر الالتقاط"
        MangaFoxRuntime.lastError = message
        restoreFloatingBubble()
        toast(message)
    }

    private data class TranslationLine(
        val bounds: Rect,
        val translated: String,
        val sourceText: String = "",
        val language: String = "und",
        val vertical: Boolean = false,
        val eraseOnly: Boolean = false,
        val shape: String = "round",
        /** Bubble interior used for typesetting; source bounds remain the erase-only area. */
        val containerBounds: Rect? = null
    )
    private data class OcrCandidate(
        val bounds: Rect?, val text: String, val engineLanguage: String,
        val containerBounds: Rect? = null, val bubbleId: Int? = null
    )
    private data class BlockForTranslation(
        val bounds: Rect, val text: String, val language: String,
        val containerBounds: Rect? = null,
        val mergedLines: Int = 1
    )

    private fun countChars(text: String, predicate: (Char) -> Boolean): Int = text.count(predicate)

    private fun languageForCode(code: String): String = when (code) {
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "zh" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    private fun boxForBlock(block: Text.TextBlock): Rect? {
        block.boundingBox?.let { return Rect(it) }
        val boxes = block.lines.mapNotNull { it.boundingBox }
        if (boxes.isEmpty()) return null
        return Rect(boxes.first()).apply { boxes.drop(1).forEach { union(it) } }
    }

    private fun candidateFromBlock(block: Text.TextBlock, engine: String, scale: Float = 1f): OcrCandidate {
        val boundsAtSource = boxForBlock(block)?.let { box ->
            if (scale == 1f) Rect(box) else Rect(
                (box.left / scale).roundToInt(), (box.top / scale).roundToInt(),
                (box.right / scale).roundToInt(), (box.bottom / scale).roundToInt()
            )
        }
        val lineParts = block.lines.filter { it.text.isNotBlank() }
        val phrase = if (boundsAtSource != null && boundsAtSource.height() > boundsAtSource.width() * 2.2f && lineParts.isNotEmpty()) {
            // Vertical Japanese columns are read from right to left, then top to bottom.
            lineParts.sortedByDescending { it.boundingBox?.centerX() ?: 0 }
                .joinToString("") { it.text.trim() }
        } else {
            lineParts.joinToString(" ") { it.text.trim() }.ifBlank { block.text.trim() }
        }
        return OcrCandidate(boundsAtSource, phrase.trim(), engine)
    }

    /**
     * Prefer line-sized OCR boxes over a whole TextBlock. A block may contain Arabic and
     * Latin on adjacent lines; assigning one language and one large rectangle to it was
     * the main source of mixed-script overlaps seen in translated pages.
     */
    private fun collectCandidates(result: Text?, engine: String, scale: Float = 1f): List<OcrCandidate> {
        fun mapBounds(raw: Rect?): Rect? {
            if (raw == null) return null
            return if (scale == 1f) Rect(raw) else Rect(
                (raw.left / scale).roundToInt(), (raw.top / scale).roundToInt(),
                (raw.right / scale).roundToInt(), (raw.bottom / scale).roundToInt()
            )
        }
        return result?.textBlocks.orEmpty().flatMap { block ->
            val blockBounds = boxForBlock(block)
            val hasVerticalCjk = blockBounds != null &&
                blockBounds.height() > blockBounds.width() * 2.2f &&
                countChars(block.text) { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' } > 0
            if (hasVerticalCjk) {
                listOf(candidateFromBlock(block, engine, scale))
            } else {
                val lines = block.lines.mapNotNull { line ->
                    val rawBounds = line.boundingBox ?: line.elements.mapNotNull { it.boundingBox }
                        .reduceOrNull { acc, rect -> Rect(acc).apply { union(rect) } }
                    val text = line.text.trim()
                    if (text.isBlank()) null else OcrCandidate(mapBounds(rawBounds), text, engine)
                }
                if (lines.isNotEmpty()) lines else listOf(candidateFromBlock(block, engine, scale))
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0, right - left) * max(0, bottom - top)
        if (intersection <= 0) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun candidateQuality(candidate: OcrCandidate): Int {
        val text = candidate.text
        val letters = text.count { it.isLetterOrDigit() }
        if (letters < 2) return -1000
        val arabic = text.count { isArabicChar(it) }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val cjk = text.count { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' || it in '\uAC00'..'\uD7AF' }
        val replacement = text.count { it == '\uFFFD' || it == '?' }
        // One OCR line mixing Arabic with Latin/CJK is almost always broken
        // recognition noise; such lines used to win the merge and produced the
        // garbled mixed-script translations visible in the screenshots.
        val mixedScriptPenalty = if (arabic > 0 && latin + cjk > 0) 60 else 0
        val areaPenalty = candidate.bounds?.let { (it.width().coerceAtLeast(1) * it.height().coerceAtLeast(1)) / 12000 } ?: 0
        return letters * 5 + arabic * 2 - replacement * 12 - mixedScriptPenalty - areaPenalty.coerceAtMost(40)
    }

    private fun mergeOcrCandidates(input: List<OcrCandidate>): List<OcrCandidate> {
        val merged = ArrayList<OcrCandidate>()
        fun overlaps(a: Rect, b: Rect): Boolean {
            val left = max(a.left, b.left)
            val top = max(a.top, b.top)
            val right = min(a.right, b.right)
            val bottom = min(a.bottom, b.bottom)
            val intersection = max(0, right - left) * max(0, bottom - top)
            if (intersection <= 0) return false
            val smallerArea = min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
            val containedOverlap = intersection.toFloat() / smallerArea.toFloat()
            return intersectionOverUnion(a, b) >= 0.45f || containedOverlap >= 0.80f
        }
        input.asSequence()
            .filter { it.text.isNotBlank() && it.bounds?.let { box -> box.width() > 2 && box.height() > 2 } == true }
            .sortedByDescending { candidateQuality(it) }
            .forEach { candidate ->
                val box = candidate.bounds ?: return@forEach
                val conflicts = merged.filter { old -> old.bounds?.let { overlaps(it, box) } == true }
                if (conflicts.isEmpty()) {
                    merged.add(candidate)
                } else {
                    // Collapse every overlapping duplicate at once; replacing only the first
                    // collision allowed a third OCR engine's near-identical box to survive.
                    val group = conflicts + candidate
                    val winner = group.maxByOrNull { candidateQuality(it) } ?: candidate
                    val bubbleMetadata = group.firstOrNull { it.containerBounds != null }
                    merged.removeAll { old -> old.bounds?.let { overlaps(it, box) } == true }
                    merged.add(winner.copy(
                        containerBounds = bubbleMetadata?.containerBounds?.let(::Rect),
                        bubbleId = bubbleMetadata?.bubbleId
                    ))
                }
            }
        // Deterministically remove residual collisions caused by chained/partially intersecting boxes.
        val nonOverlapping = ArrayList<OcrCandidate>()
        merged.sortedByDescending { candidateQuality(it) }.forEach { candidate ->
            val box = candidate.bounds ?: return@forEach
            if (nonOverlapping.none { it.bounds?.let { accepted -> overlaps(accepted, box) } == true }) {
                nonOverlapping.add(candidate)
            }
        }
        // Manga panels are read top-to-bottom, then right-to-left within a visual row.
        return nonOverlapping.sortedWith(
            compareBy<OcrCandidate> { it.bounds?.top ?: Int.MAX_VALUE }
                .thenByDescending { it.bounds?.right ?: Int.MIN_VALUE }
        )
    }

    private fun createOcrVariant(source: Bitmap, inverted: Boolean): Pair<Bitmap, Float> {
        val largest = max(source.width, source.height).coerceAtLeast(1)
        val scale = min(if (largest < 1800) 1.65f else 1.25f, 3600f / largest.toFloat()).coerceAtLeast(1f)
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val transform = Matrix().apply { setScale(width.toFloat() / source.width, height.toFloat() / source.height) }
        val scaled = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
        val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val contrast = if (inverted) -1.30f else 1.30f
        val offset = if (inverted) 287f else -32f
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(ColorMatrix().apply { setSaturation(0f) })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(gray).drawBitmap(scaled, 0f, 0f, paint)
        if (!scaled.isRecycled) scaled.recycle()

        // Percentile histogram stretch improves faded lettering without allocating
        // a second full-size pixel buffer (only one scanline is kept in memory).
        val histogram = IntArray(256)
        // Work in 96-row bands: far fewer Bitmap JNI calls than one row at a time,
        // while avoiding a second full-resolution IntArray that could cost 70+ MB.
        val bandHeight = min(96, height).coerceAtLeast(1)
        val band = IntArray(width * bandHeight)
        for (top in 0 until height step bandHeight) {
            val rows = min(bandHeight, height - top)
            gray.getPixels(band, 0, width, 0, top, width, rows)
            val used = width * rows
            for (i in 0 until used) histogram[Color.red(band[i]).coerceIn(0, 255)]++
        }
        val totalPixels = width * height
        val lowTarget = (totalPixels * 0.01f).toInt()
        val highTarget = (totalPixels * 0.99f).toInt()
        var cumulative = 0
        var low = 0
        var high = 255
        for (value in 0..255) {
            cumulative += histogram[value]
            if (cumulative >= lowTarget) { low = value; break }
        }
        cumulative = 0
        for (value in 0..255) {
            cumulative += histogram[value]
            if (cumulative >= highTarget) { high = value; break }
        }
        if (high > low + 2) {
            val range = (high - low).toFloat()
            for (top in 0 until height step bandHeight) {
                val rows = min(bandHeight, height - top)
                gray.getPixels(band, 0, width, 0, top, width, rows)
                val used = width * rows
                for (i in 0 until used) {
                    val value = ((Color.red(band[i]) - low) * 255f / range).roundToInt().coerceIn(0, 255)
                    band[i] = Color.rgb(value, value, value)
                }
                gray.setPixels(band, 0, width, 0, top, width, rows)
            }
        }
        return gray to scale
    }

    /**
     * Otsu-binarized OCR input for decorative/outlined/faded Latin lettering.
     * The longest side is capped at 2400 px and pixels are processed in bands
     * so a full-page threshold pass does not require a second full-size IntArray.
     */
    private fun createBinarizedOcrVariant(source: Bitmap): Pair<Bitmap, Float> {
        val largest = max(source.width, source.height).coerceAtLeast(1)
        val scale = min(1.45f, 2400f / largest.toFloat()).coerceAtLeast(0.01f)
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val transform = Matrix().apply { setScale(width.toFloat() / source.width, height.toFloat() / source.height) }
        val scaled = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
        val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val grayscale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(gray).drawBitmap(scaled, 0f, 0f, grayscale)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()

        val histogram = IntArray(256)
        val bandHeight = min(64, height).coerceAtLeast(1)
        val band = IntArray(width * bandHeight)
        for (top in 0 until height step bandHeight) {
            val rows = min(bandHeight, height - top)
            gray.getPixels(band, 0, width, 0, top, width, rows)
            for (i in 0 until width * rows) histogram[Color.red(band[i]).coerceIn(0, 255)]++
        }
        val total = width.toLong() * height.toLong()
        var sum = 0.0
        for (v in 0..255) sum += v.toDouble() * histogram[v]
        var sumBackground = 0.0
        var weightBackground = 0L
        var bestVariance = -1.0
        var threshold = 145
        for (v in 0..255) {
            weightBackground += histogram[v].toLong()
            if (weightBackground == 0L) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0L) break
            sumBackground += v.toDouble() * histogram[v]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground
            val delta = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground.toDouble() * delta * delta
            if (variance > bestVariance) { bestVariance = variance; threshold = v }
        }
        // Keep anti-aliased glyph edges readable: the threshold is softened slightly
        // rather than turning every gray edge pixel into a hard white boundary.
        val cutoff = (threshold - 8).coerceIn(0, 255)
        for (top in 0 until height step bandHeight) {
            val rows = min(bandHeight, height - top)
            gray.getPixels(band, 0, width, 0, top, width, rows)
            for (i in 0 until width * rows) {
                val value = Color.red(band[i])
                band[i] = if (value <= cutoff) Color.BLACK else Color.WHITE
            }
            gray.setPixels(band, 0, width, 0, top, width, rows)
        }
        return gray to scale
    }

    /**
     * Finds enclosed bright regions that resemble speech-balloon interiors.
     * This is a lightweight heuristic (not a trained balloon detector): it runs
     * on a bounded thumbnail and returns only compact, enclosed components.
     */
    private fun detectBrightBubbleRegions(source: Bitmap): List<Rect> {
        val largest = max(source.width, source.height).coerceAtLeast(1)
        val scale = min(1f, min(1000f / largest.toFloat(), kotlin.math.sqrt(900_000f / (source.width.toFloat() * source.height.toFloat()))))
            .coerceAtLeast(0.01f)
        val sampleWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val sampleHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val sample = Bitmap.createScaledBitmap(source, sampleWidth, sampleHeight, true)
        try {
            val total = sampleWidth * sampleHeight
            val visited = BooleanArray(total)
            val queue = IntArray(total)
            val pixels = IntArray(total)
            sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
            val regions = ArrayList<Pair<Int, Rect>>()
            val minArea = max(24, (total * 0.00045f).toInt())
            val maxArea = (total * 0.22f).toInt()

            fun isBright(index: Int): Boolean {
                val color = pixels[index]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val luminance = (red * 30 + green * 59 + blue * 11) / 100
                val chroma = max(red, max(green, blue)) - min(red, min(green, blue))
                // Includes white/gray and pale pastel speech balloons, while avoiding
                // treating every saturated colorful drawing as a balloon interior.
                return luminance >= 198 && (chroma <= 82 || luminance >= 232)
            }

            for (start in 0 until total) {
                if (visited[start]) continue
                visited[start] = true
                if (!isBright(start)) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                var minX = sampleWidth
                var minY = sampleHeight
                var maxX = -1
                var maxY = -1
                var area = 0
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % sampleWidth
                    val y = index / sampleWidth
                    area++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y

                    val left = if (x > 0) index - 1 else -1
                    val right = if (x < sampleWidth - 1) index + 1 else -1
                    val up = if (y > 0) index - sampleWidth else -1
                    val down = if (y < sampleHeight - 1) index + sampleWidth else -1
                    if (left >= 0 && !visited[left]) { visited[left] = true; if (isBright(left)) queue[tail++] = left }
                    if (right >= 0 && !visited[right]) { visited[right] = true; if (isBright(right)) queue[tail++] = right }
                    if (up >= 0 && !visited[up]) { visited[up] = true; if (isBright(up)) queue[tail++] = up }
                    if (down >= 0 && !visited[down]) { visited[down] = true; if (isBright(down)) queue[tail++] = down }
                }
                // Edge-touching regions are valid: manga bubbles are often clipped by a page edge.
                if (area < minArea || area > maxArea) continue
                val width = maxX - minX + 1
                val height = maxY - minY + 1
                if (width < 14 || height < 10) continue
                val ratio = width.toFloat() / height.toFloat()
                if (ratio !in 0.18f..6.0f) continue
                val padX = max(3, width / 14)
                val padY = max(3, height / 14)
                val left = (minX - padX).coerceAtLeast(0)
                val top = (minY - padY).coerceAtLeast(0)
                val right = (maxX + 1 + padX).coerceAtMost(sampleWidth)
                val bottom = (maxY + 1 + padY).coerceAtMost(sampleHeight)
                val mapped = Rect(
                    (left / scale).roundToInt().coerceIn(0, source.width - 1),
                    (top / scale).roundToInt().coerceIn(0, source.height - 1),
                    (right / scale).roundToInt().coerceIn(1, source.width),
                    (bottom / scale).roundToInt().coerceIn(1, source.height)
                )
                if (mapped.width() > 12 && mapped.height() > 12) regions.add(area to mapped)
            }
            val selected = ArrayList<Rect>()
            regions.sortedByDescending { it.first }.forEach { (_, rect) ->
                val overlapsExisting = selected.any { previous ->
                    val left = max(previous.left, rect.left)
                    val top = max(previous.top, rect.top)
                    val right = min(previous.right, rect.right)
                    val bottom = min(previous.bottom, rect.bottom)
                    val intersection = max(0, right - left) * max(0, bottom - top)
                    val smaller = min(previous.width() * previous.height(), rect.width() * rect.height()).coerceAtLeast(1)
                    intersectionOverUnion(previous, rect) > 0.40f || intersection.toFloat() / smaller > 0.70f
                }
                if (!overlapsExisting) selected.add(rect)
            }
            return selected.take(28)
        } finally {
            if (!sample.isRecycled && sample !== source) sample.recycle()
        }
    }

    private fun recognizeBubbleRegions(
        source: Bitmap,
        engines: List<Pair<String, TextRecognizer>>,
        onProgress: (Float, String) -> Unit
    ): List<OcrCandidate> {
        val regions = detectBrightBubbleRegions(source)
        if (regions.isEmpty()) return emptyList()
        val found = ArrayList<OcrCandidate>()
        regions.forEachIndexed { index, region ->
            val regionMaxSide = max(region.width(), region.height()).toFloat().coerceAtLeast(1f)
            // Enlarge small bubbles, but downscale oversized regions before allocation to cap memory.
            val enlarge = if (regionMaxSide < 900f) {
                min(1.8f, 1450f / regionMaxSide)
            } else {
                min(1f, 1600f / regionMaxSide)
            }.coerceAtLeast(0.12f)
            val transform = Matrix().apply { setScale(enlarge, enlarge) }
            val crop = runCatching {
                Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height(), transform, true)
            }.getOrNull() ?: return@forEachIndexed
            try {
                val input = InputImage.fromBitmap(crop, 0)
                var latinCharacters = 0
                engines.forEach { engine ->
                    val result = runCatching { Tasks.await(engine.second.process(input)) }.getOrNull()
                    val local = collectCandidates(result, engine.first, enlarge)
                    if (engine.first == "en") latinCharacters = local.sumOf { item -> item.text.count { it.isLetter() } }
                    local.forEach { candidate ->
                        val shifted = candidate.bounds?.let { Rect(it).apply { offset(region.left, region.top) } }
                        found.add(candidate.copy(
                            bounds = shifted,
                            containerBounds = Rect(region),
                            bubbleId = index
                        ))
                    }
                }
                // Artistic fonts often fail on the raw crop but become legible after
                // binarization. Retry only Latin here to bound the additional work.
                if (engines.any { it.first == "en" } && latinCharacters < 8) {
                    val (binary, binaryScale) = createBinarizedOcrVariant(crop)
                    try {
                        val retry = runCatching {
                            Tasks.await(latinRecognizer.process(InputImage.fromBitmap(binary, 0)))
                        }.getOrNull()
                        collectCandidates(retry, "en", enlarge * binaryScale).forEach { candidate ->
                            val shifted = candidate.bounds?.let { Rect(it).apply { offset(region.left, region.top) } }
                            found.add(candidate.copy(bounds = shifted, containerBounds = Rect(region), bubbleId = index))
                        }
                    } finally {
                        if (!binary.isRecycled) binary.recycle()
                    }
                }
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
            onProgress(0.36f, "إعادة OCR داخل الفقاعة ${index + 1}/${regions.size}")
        }
        return found
    }

    /**
     * Associate OCR boxes with a detected bright balloon only when the balloon clearly
     * contains the box. A shared balloon is not reused as a full-size text area when it
     * contains multiple OCR blocks, preventing translations from colliding.
     */
    private fun attachSingleBlockBubbleContainers(
        source: Bitmap, candidates: List<OcrCandidate>
    ): List<OcrCandidate> {
        if (candidates.isEmpty()) return candidates
        val regions = detectBrightBubbleRegions(source)
        if (regions.isEmpty()) return candidates
        val associated = candidates.map { candidate ->
            val box = candidate.bounds ?: return@map candidate
            if (candidate.containerBounds != null) return@map candidate
            val centerX = box.centerX()
            val centerY = box.centerY()
            val match = regions.withIndex().filter { (_, region) ->
                val regionArea = region.width().toLong() * region.height().toLong()
                val pageArea = source.width.toLong() * source.height.toLong()
                val touchesBorder = region.left <= 1 || region.top <= 1 ||
                    region.right >= source.width - 1 || region.bottom >= source.height - 1
                // A big bright region touching the page edge is usually the white margin
                // itself (BFS connects edge bubbles to the margin), not a real balloon.
                val giantMarginComponent = touchesBorder && regionArea > pageArea * 2L / 100L
                region.contains(box.left, box.top, box.right, box.bottom) &&
                    !giantMarginComponent &&
                    regionArea <= pageArea * 6L / 100L &&
                    regionArea <= box.width().toLong() * box.height().toLong() * 6L &&
                    region.width() <= box.width() * 4f &&
                    region.height() <= box.height() * 4f &&
                    region.width() >= box.width() * 1.22f &&
                    region.height() >= box.height() * 1.22f
            }.minByOrNull { (_, region) -> region.width().toLong() * region.height().toLong() }
            if (match == null) candidate
            else candidate.copy(containerBounds = Rect(match.value), bubbleId = match.index)
        }
        val counts = associated.mapNotNull { it.bubbleId }.groupingBy { it }.eachCount()
        return associated.map { candidate ->
            val id = candidate.bubbleId
            if (id != null && counts[id] == 1) candidate
            else candidate.copy(containerBounds = null, bubbleId = null)
        }
    }

    private fun rotatedJapaneseCandidates(source: Bitmap): List<OcrCandidate> {
        val rotate = Matrix().apply { setRotate(90f) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotate, true)
        return try {
            val result = runCatching {
                Tasks.await(japaneseRecognizer.process(InputImage.fromBitmap(rotated, 0)))
            }.getOrNull()
            collectCandidates(result, "ja").mapNotNull { candidate ->
                val b = candidate.bounds ?: return@mapNotNull candidate
                // Coordinates of a clockwise-rotated HxW image mapped back to the original W×H image.
                val mapped = Rect(
                    b.top.coerceIn(0, source.width - 1),
                    (source.height - b.right).coerceIn(0, source.height - 1),
                    b.bottom.coerceIn(1, source.width),
                    (source.height - b.left).coerceIn(1, source.height)
                )
                candidate.copy(bounds = mapped)
            }
        } finally {
            if (!rotated.isRecycled) rotated.recycle()
        }
    }

    private fun translatorFor(code: String): Translator {
        return translatorCache.getOrPut(code) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(languageForCode(code))
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build()
            )
        }
    }

    private fun isArabicChar(ch: Char): Boolean =
        ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' ||
            ch in '\u08A0'..'\u08FF' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'

    private fun hasArabicLetters(value: String): Boolean =
        value.any { isArabicChar(it) && it.isLetter() }

    private fun hasForeignLetters(value: String): Boolean = value.any { it.isLetter() && !isArabicChar(it) }

    private fun foreignLetterCount(value: String): Int =
        value.count { it.isLetter() && !isArabicChar(it) }

    /** True when the translation still contains Latin letter runs, i.e. ML Kit returned OCR noise. */
    private fun hasUntranslatedLatinRuns(value: String, minRun: Int = 4): Boolean {
        var run = 0
        for (ch in value) {
            if (ch in 'A'..'Z' || ch in 'a'..'z') {
                run++
                if (run >= minRun) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /** Reject outputs that retain almost all foreign letters, even if the line also contains Arabic. */
    private fun isUsefulArabicTranslation(source: String, translated: String): Boolean {
        if (translated.isBlank()) return false
        if (!hasForeignLetters(source)) return hasArabicLetters(translated)
        if (!hasArabicLetters(translated)) return false
        val sourceForeign = foreignLetterCount(source)
        val outputForeign = foreignLetterCount(translated)
        // Long dialogues legitimately keep names (Xianlun, Yaoyao...) in Latin;
        // a flat 68% cap used to reject many valid long translations.
        val allowedRatio = if (source.length > 120) 0.45f else 0.68f
        return sourceForeign == 0 || outputForeign < max(2, (sourceForeign * allowedRatio).roundToInt())
    }

    private fun languageForBlock(text: String, engineHint: String, forced: String): String {
        val letters = text.filter { it.isLetter() }
        val arabic = countChars(letters) { isArabicChar(it) }
        val hangul = countChars(text) { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }
        val kana = countChars(text) { it in '\u3040'..'\u30FF' }
        val cjk = countChars(text) { it in '\u4E00'..'\u9FFF' }
        val latin = countChars(text) { it in 'A'..'Z' || it in 'a'..'z' }
        // Arabic-only lines are retained. Mixed Arabic/foreign lines must not be skipped.
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

    /** Translate foreign-script runs while preserving Arabic already present in the OCR line. */
    private fun translateMixedTextPreservingArabic(source: String, language: String): String {
        val output = StringBuilder()
        val segment = StringBuilder()
        var segmentIsArabic: Boolean? = null
        fun flush() {
            if (segment.isEmpty()) return
            val raw = segment.toString()
            val kind = segmentIsArabic
            if (kind == true || raw.none { it.isLetterOrDigit() }) {
                output.append(raw)
            } else {
                val trimmed = raw.trim()
                val leading = raw.takeWhile { it.isWhitespace() }
                val trailing = raw.takeLastWhile { it.isWhitespace() }
                val translated = if (trimmed.any { it.isLetterOrDigit() }) {
                    runCatching { translateWithLanguage(trimmed, language) }.getOrDefault(trimmed)
                } else trimmed
                output.append(leading).append(translated).append(trailing)
            }
            segment.clear()
            segmentIsArabic = null
        }
        source.forEach { ch ->
            val script = when {
                ch.isLetter() && ch in '\u0600'..'\u06FF' -> true
                ch.isLetterOrDigit() -> false
                else -> null
            }
            if (script != null && segmentIsArabic != null && script != segmentIsArabic) flush()
            if (script != null && segmentIsArabic == null) segmentIsArabic = script
            segment.append(ch)
        }
        flush()
        return output.toString()
    }

    private fun softenAllCaps(value: String): String {
        val letters = value.filter { it in 'A'..'Z' || it in 'a'..'z' }
        if (letters.length < 4 || letters.count { it.isUpperCase() } < letters.length * 0.8) return value
        return value.lowercase()
            .replace(Regex("\\bi\\b"), "I")
            .replace(Regex("(^|[.!?]\\s+)([a-z])")) { it.groupValues[1] + it.groupValues[2].uppercase() }
    }

    private fun correctLikelyOcrText(value: String, language: String): String {
        var corrected = value.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        if (language == "en") {
            corrected = corrected.replace(Regex("(?i)\\bl\\s*['’]m\\b"), "I'm")
            corrected = corrected.replace(Regex("(?<=[A-Za-z])0(?=[A-Za-z])"), "O")
            corrected = corrected.replace(Regex("(?<=[A-Za-z])1(?=[A-Za-z])"), "I")
        }
        return corrected
    }

    private fun cleanArabicTranslation(value: String): String {
        var cleaned = value.replace('\u0640', ' ')
            .replace(Regex("\\s+"), " ")
            .replace(Regex("([،,؟.!])\\1+"), "$1")
            .replace(Regex("\\s+([،.!؟])"), "$1")
            .replace(Regex("([,])\\s*"), "، ")
            .replace('?', '؟')
            .trim()
        cleaned = cleaned.replace(Regex("(?iu)\\b([\\p{L}]{2,})(?:\\s+\\1\\b)+"), "$1")
        return cleaned.trim()
    }

    private fun updateQualityCounts(detected: Int, translated: Int, failed: Int, skipped: Int, failedSamples: List<String>) {
        MangaFoxRuntime.detectedBlocks += detected
        MangaFoxRuntime.translatedBlocks += translated
        MangaFoxRuntime.failedBlocks += failed
        MangaFoxRuntime.skippedBlocks += skipped
        if (failedSamples.isNotEmpty()) {
            MangaFoxRuntime.failedBlockSamples = (MangaFoxRuntime.failedBlockSamples + failedSamples).take(30)
        }
        MangaFoxRuntime.qualitySummary = "اكتُشف ${MangaFoxRuntime.detectedBlocks} · تُرجم ${MangaFoxRuntime.translatedBlocks} · فشل ${MangaFoxRuntime.failedBlocks} · تُرك ${MangaFoxRuntime.skippedBlocks}"
    }

    private fun normalizeDictionaryKey(source: String): String =
        source.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun learnedTranslationFor(source: String): String? {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_LEARNED_TRANSLATIONS, "{}") ?: "{}"
        return runCatching {
            val dictionary = org.json.JSONObject(raw)
            val key = normalizeDictionaryKey(source)
            dictionary.optString(key).takeIf { it.isNotBlank() }
                ?: dictionary.keys().asSequence().firstOrNull { normalizeDictionaryKey(it) == key }
                    ?.let { dictionary.optString(it).takeIf { value -> value.isNotBlank() } }
        }.getOrNull()
    }

    private fun dictionarySfxTranslation(source: String): String? {
        val normalized = source.filterNot { it.isWhitespace() || it in "。、・!！?？…,." }
        val dictionary = linkedMapOf(
            "ゴゴゴ" to "ررررر…",
            "ドキドキ" to "دقات قلب!",
            "キラキラ" to "بريق!",
            "ガシャン" to "تحطّم!",
            "ガーン" to "يا للصدمة!",
            "ドン" to "دُووم!",
            "バン" to "بانغ!",
            "ザッ" to "خطوة سريعة!"
        )
        return dictionary.entries.firstOrNull { normalized.contains(it.key) }?.value
    }

    private fun isLikelyAdPage(candidates: List<OcrCandidate>): Boolean {
        val content = candidates.joinToString(" ") { it.text.lowercase() }
        val strong = listOf("advertisement", "sponsored", "download our app", "install our app", "buy now", "広告", "アプリをダウンロード", "광고", "앱 다운로드", "广告")
        val soft = listOf("next chapter", "read more", "follow us", "support the author", "join our discord", "subscribe", "次の話", "次の章", "다음 화", "다음 회", "下一章", "继续阅读")
        val strongHits = strong.count { content.contains(it) }
        val softHits = soft.count { content.contains(it) }
        return strongHits > 0 || (softHits >= 2 && candidates.size <= 12)
    }

    private fun translateWithLanguage(sourceText: String, languageCode: String): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (languageCode == "zh" && prefs.getBoolean(PREF_AI_ZH_ENABLED, true)) {
            val apiKey = MangaFoxSecureSecretStore.get(this)
            if (!apiKey.isNullOrBlank()) {
                val knowledge = MangaFoxKnowledgeStore(this)
                val aiResult = runCatching {
                    MangaFoxAiTranslator.translate(this, sourceText, knowledge.glossary(), knowledge.characters())
                }.getOrNull()
                if (!aiResult.isNullOrBlank() && isUsefulArabicTranslation(sourceText, aiResult) &&
                    !hasUntranslatedLatinRuns(aiResult, 5)) return cleanArabicTranslation(aiResult)
                // A rejected/failed AI result never replaces the original; local ML Kit remains the fallback.
            }
        }
        val translator = translatorFor(languageCode)
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        return Tasks.await(translator.translate(sourceText))
    }

    /** ML Kit quality drops on long comic strings; translate sentence-sized chunks instead. */
    private fun translateInChunks(sourceText: String, languageCode: String, maxChunk: Int = 240): String {
        if (sourceText.length <= maxChunk) return translateWithLanguage(sourceText, languageCode)
        val sentences = sourceText.split(Regex("(?<=[.!?؟…])\\s+")).filter { it.isNotBlank() }
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) { chunks.add(current.toString().trim()); current.clear() }
        }
        for (sentence in sentences) {
            if (current.length + sentence.length + 1 > maxChunk) flush()
            if (sentence.length > maxChunk) {
                var rest = sentence.trim()
                while (rest.length > maxChunk) {
                    val cut = rest.lastIndexOf(' ', maxChunk).takeIf { it > 60 } ?: maxChunk
                    chunks.add(rest.substring(0, cut))
                    rest = rest.substring(cut).trim()
                }
                current.append(rest).append(' ')
            } else {
                current.append(sentence).append(' ')
            }
        }
        flush()
        if (chunks.isEmpty()) return translateWithLanguage(sourceText, languageCode)
        return chunks.joinToString(" ") { chunk ->
            runCatching { translateWithLanguage(chunk, languageCode) }.getOrDefault(chunk)
        }
    }

    /** Last-resort re-read of one failed block using enhanced variants of its own crop. */
    private fun retranslateBlockWithEnhancedOcr(source: Bitmap, item: BlockForTranslation, language: String): String? {
        val b = item.bounds
        if (b.width() < 24 || b.height() < 12) return null
        val left = b.left.coerceIn(0, source.width - 1)
        val top = b.top.coerceIn(0, source.height - 1)
        val width = b.right.coerceIn(left + 1, source.width) - left
        val height = b.bottom.coerceIn(top + 1, source.height) - top
        if (width < 24 || height < 12) return null
        val crop = runCatching { Bitmap.createBitmap(source, left, top, width, height) }.getOrNull() ?: return null
        try {
            val recognizer = when (language) {
                "ja" -> japaneseRecognizer
                "ko" -> koreanRecognizer
                "zh" -> chineseRecognizer
                else -> latinRecognizer
            }
            val variants = listOf(
                runCatching { createBinarizedOcrVariant(crop) }.getOrNull(),
                runCatching { createOcrVariant(crop, inverted = false) }.getOrNull()
            )
            var bestText: String? = null
            var bestScore = 0
            for (entry in variants) {
                if (entry == null) continue
                val (variant, _) = entry
                try {
                    val result = runCatching {
                        Tasks.await(recognizer.process(InputImage.fromBitmap(variant, 0)))
                    }.getOrNull()
                    val read = result?.text?.trim().orEmpty()
                    if (read.length < 4) continue
                    val letters = read.count { it.isLetter() }
                    val weird = read.count { !(it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?…'\"-—:;()«»") }
                    val score = letters * 2 - weird * 5
                    if (score > bestScore) { bestScore = score; bestText = read }
                } finally {
                    if (!variant.isRecycled) variant.recycle()
                }
            }
            val read = bestText ?: return null
            val cleaned = correctLikelyOcrText(read.replace(Regex("\\s+"), " "), language)
            if (cleaned.length < 3 || cleaned.equals(item.text, ignoreCase = true)) return null
            return runCatching { translateInChunks(cleaned, language) }.getOrNull()
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun retryLanguageCandidates(sourceText: String, current: String): List<String> {
        val detected = runCatching {
            Tasks.await(languageIdentifier.identifyLanguage(sourceText.take(500)))
        }.getOrDefault("und").lowercase().substringBefore('-')
        val alternate = when (current) {
            "ja" -> "zh"
            "zh" -> "ja"
            "ko" -> "en"
            else -> "ja"
        }
        return listOf(detected, alternate, "en", "ja", "ko", "zh")
            .filter { it in setOf("en", "ja", "ko", "zh") && it != current }
            .distinct()
    }

    private fun translateBitmap(source: Bitmap, onProgress: (Float, String) -> Unit = { _, _ -> }): Bitmap {
        lastOcrTranslationLines.set(emptyList())
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val forcedLanguage = prefs.getString(PREF_SOURCE_LANGUAGE, "auto") ?: "auto"
        onProgress(0.03f, "تهيئة التعرف على النص")
        val engines: List<Pair<String, TextRecognizer>> = when (forcedLanguage) {
            "ja" -> listOf("ja" to japaneseRecognizer)
            "en" -> listOf("en" to latinRecognizer)
            "ko" -> listOf("ko" to koreanRecognizer)
            "zh" -> listOf("zh" to chineseRecognizer)
            else -> listOf("en" to latinRecognizer, "ja" to japaneseRecognizer, "ko" to koreanRecognizer, "zh" to chineseRecognizer)
        }
        fun recognizeAll(bitmap: Bitmap, scale: Float): List<OcrCandidate> {
            val image = InputImage.fromBitmap(bitmap, 0)
            val found = ArrayList<OcrCandidate>()
            engines.forEachIndexed { index, engine ->
                onProgress(0.06f + index * 0.055f, "OCR متعدد المحركات · ${index + 1}/${engines.size}")
                val result = runCatching { Tasks.await(engine.second.process(image)) }.getOrNull()
                found.addAll(collectCandidates(result, engine.first, scale))
            }
            return found
        }

        val allCandidates = ArrayList<OcrCandidate>()
        allCandidates.addAll(recognizeAll(source, 1f))
        // OCR robustness for non-standard Latin lettering: run an independent
        // contrast pass even when other scripts were recognized successfully.
        if (forcedLanguage == "auto" || forcedLanguage == "en") {
            val existingLatinChars = allCandidates.asSequence().filter { it.engineLanguage == "en" }
                .sumOf { it.text.count { ch -> ch in 'A'..'Z' || ch in 'a'..'z' } }
            if (forcedLanguage == "auto" || forcedLanguage == "en") {
                onProgress(0.20f, "فحص الخطوط اللاتينية المزخرفة بعد تحسين التباين")
                val (latinEnhanced, latinScale) = createOcrVariant(source, inverted = false)
                try {
                    val retry = runCatching {
                        Tasks.await(latinRecognizer.process(InputImage.fromBitmap(latinEnhanced, 0)))
                    }.getOrNull()
                    allCandidates.addAll(collectCandidates(retry, "en", latinScale))
                } finally {
                    if (!latinEnhanced.isRecycled) latinEnhanced.recycle()
                }
                val recoveredLatin = allCandidates.asSequence().filter { it.engineLanguage == "en" }
                    .sumOf { it.text.count { ch -> ch in 'A'..'Z' || ch in 'a'..'z' } }
                if (forcedLanguage == "auto" || forcedLanguage == "en") {
                    onProgress(0.235f, "محاولة قراءة الخطوط المزخرفة بالعتبة التكيفية العامة")
                    val (binary, binaryScale) = createBinarizedOcrVariant(source)
                    try {
                        val retry = runCatching {
                            Tasks.await(latinRecognizer.process(InputImage.fromBitmap(binary, 0)))
                        }.getOrNull()
                        allCandidates.addAll(collectCandidates(retry, "en", binaryScale))
                    } finally {
                        if (!binary.isRecycled) binary.recycle()
                    }
                }
            }
        }
        val firstMerged = mergeOcrCandidates(allCandidates)
        val foundChars = firstMerged.sumOf { it.text.count { character -> character.isLetterOrDigit() } }
        if (firstMerged.size < 2 || foundChars < 18) {
            // Retry low-yield pages with an enlarged grayscale/high-contrast image.
            onProgress(0.27f, "إعادة قراءة النصوص الصغيرة بعد التكبير ورفع التباين")
            val (enhanced, scale) = createOcrVariant(source, inverted = false)
            try { allCandidates.addAll(recognizeAll(enhanced, scale)) } finally { enhanced.recycle() }
            var combined = mergeOcrCandidates(allCandidates)
            if (combined.size < 2 || combined.sumOf { it.text.length } < 18) {
                onProgress(0.34f, "محاولة ثانية للصورة المعكوسة")
                val (inverted, invertedScale) = createOcrVariant(source, inverted = true)
                try { allCandidates.addAll(recognizeAll(inverted, invertedScale)) } finally { inverted.recycle() }
            }
        }
        val preBubbleMerge = mergeOcrCandidates(allCandidates)
        val preBubbleChars = preBubbleMerge.sumOf { it.text.count { character -> character.isLetterOrDigit() } }
        val tallSparsePage = source.height >= 2400 && source.height > source.width * 2 && preBubbleMerge.size < 20
        val lowCoveragePage = preBubbleChars < 180 && preBubbleMerge.size < 14
        if (preBubbleMerge.size < 6 || preBubbleChars < 100 || tallSparsePage || lowCoveragePage) {
            onProgress(0.355f, "فحص مناطق الفقاعات الفاتحة وإعادة OCR لكل منطقة")
            allCandidates.addAll(recognizeBubbleRegions(source, engines) { fraction, detail ->
                onProgress(fraction, detail)
            })
        }
        val beforeRotation = mergeOcrCandidates(allCandidates)
        val verticalJapanese = beforeRotation.any { candidate ->
            val b = candidate.bounds
            b != null && b.height() > b.width() * 2.2f && countChars(candidate.text) { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' } > 0
        }
        if ((verticalJapanese || beforeRotation.size < 2) && forcedLanguage in setOf("auto", "ja")) {
            onProgress(0.37f, "محاولة قراءة الأعمدة اليابانية بعد تدويرها")
            allCandidates.addAll(rotatedJapaneseCandidates(source))
        }
        val mergedCandidates = mergeOcrCandidates(allCandidates)
        val candidates = attachSingleBlockBubbleContainers(source, mergedCandidates)
        // v3.0.6 — balloons the full-page OCR missed entirely (outlined/artistic lettering,
        // faint text): re-read only bright regions that contain no candidate at all.
        val missedCandidates = ArrayList<OcrCandidate>()
        detectBrightBubbleRegions(source).take(12).forEachIndexed { regionIndex, region ->
            val regionRect = Rect(region)
            val covered = candidates.any { c ->
                c.bounds?.let { rb -> regionRect.contains(rb.centerX(), rb.centerY()) } == true
            }
            if (covered) return@forEachIndexed
            val maxSide = max(region.width(), region.height()).coerceAtLeast(1)
            val enlarge = (if (maxSide < 900) min(1.8f, 1450f / maxSide) else min(1f, 1600f / maxSide)).coerceAtLeast(0.12f)
            val left = region.left.coerceIn(0, source.width - 1)
            val top = region.top.coerceIn(0, source.height - 1)
            val w = region.right.coerceIn(left + 1, source.width) - left
            val h = region.bottom.coerceIn(top + 1, source.height) - top
            if (w < 30 || h < 20) return@forEachIndexed
            val crop = runCatching {
                Bitmap.createBitmap(source, left, top, w, h, Matrix().apply { setScale(enlarge, enlarge) }, true)
            }.getOrNull() ?: return@forEachIndexed
            try {
                val regionEngines: List<Pair<String, TextRecognizer>> = when (forcedLanguage) {
                    "ja" -> listOf("ja" to japaneseRecognizer)
                    "ko" -> listOf("ko" to koreanRecognizer)
                    "zh" -> listOf("zh" to chineseRecognizer)
                    "en" -> listOf("en" to latinRecognizer)
                    else -> listOf("en" to latinRecognizer, "zh" to chineseRecognizer, "ja" to japaneseRecognizer)
                }
                val variants = listOf(
                    runCatching { createOcrVariant(crop, inverted = false) }.getOrNull(),
                    runCatching { createBinarizedOcrVariant(crop) }.getOrNull()
                )
                for (variantEntry in variants) {
                    if (variantEntry == null) continue
                    val (variant, variantScale) = variantEntry
                    try {
                        for (engine in regionEngines) {
                            val result = runCatching {
                                Tasks.await(engine.second.process(InputImage.fromBitmap(variant, 0)))
                            }.getOrNull()
                            collectCandidates(result, engine.first, enlarge * variantScale).forEach { cand ->
                                val shifted = cand.bounds?.let { Rect(it).apply { offset(left, top) } }
                                if (shifted != null && regionRect.contains(shifted.centerX(), shifted.centerY())) {
                                    missedCandidates.add(cand.copy(bounds = shifted, containerBounds = regionRect, bubbleId = regionIndex))
                                }
                            }
                        }
                    } finally {
                        if (!variant.isRecycled) variant.recycle()
                    }
                }
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        val candidatesAfterMissed: List<OcrCandidate> = if (missedCandidates.isEmpty()) candidates
            else attachSingleBlockBubbleContainers(source, mergeOcrCandidates(candidates + missedCandidates))
        if (candidatesAfterMissed.isEmpty()) {
            updateQualityCounts(0, 0, 0, 0, emptyList())
            onProgress(1f, "لم يكتشف OCR أي نص واضح")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }
        if (isLikelyAdPage(candidatesAfterMissed)) {
            updateQualityCounts(candidates.size, 0, 0, candidates.size, emptyList())
            onProgress(1f, "تم تخطي صفحة يُرجّح أنها إعلان أو شاشة انتقال للفصل")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }

        onProgress(0.40f, "تحديد لغة كل كتلة نصية بصورة مستقلة")
        val blocks = ArrayList<BlockForTranslation>()
        val failedSamples = ArrayList<String>()
        var skippedCount = 0
        // Balloon-aware paragraph clustering. Explicitly different bubble IDs are never merged.
        // Bright-region detection is computed once and reused for the center-in-same-bubble rule.
        val candList = candidatesAfterMissed
        val brightRegionsForJoin = detectBrightBubbleRegions(source)
            .filter { region ->
                val pageArea = source.width.toLong() * source.height.toLong()
                region.width().toLong() * region.height().toLong() <= pageArea * 6L / 100L
            }
        val parent = IntArray(candList.size) { it }
        fun findRoot(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val t = parent[c]; parent[c] = r; c = t }
            return r
        }
        fun join(a: Int, b: Int) { val ra = findRoot(a); val rb = findRoot(b); if (ra != rb) parent[ra] = rb }
        fun horizontalOverlap(a: Rect, b: Rect): Float {
            val left = max(a.left, b.left)
            val right = min(a.right, b.right)
            if (right <= left) return 0f
            return (right - left).toFloat() / max(1, min(a.width(), b.width()))
        }
        fun shareSmallBubble(a: Rect, b: Rect): Boolean = brightRegionsForJoin.any { region ->
            val area = region.width().toLong() * region.height().toLong()
            val pageArea = source.width.toLong() * source.height.toLong()
            area <= pageArea * 6L / 100L &&
                region.contains(a.centerX(), a.centerY()) && region.contains(b.centerX(), b.centerY())
        }
        for (i in candList.indices) {
            val a = candList[i].bounds ?: continue
            for (j in i + 1 until candList.size) {
                val b = candList[j].bounds ?: continue
                val idA = candList[i].bubbleId
                val idB = candList[j].bubbleId
                if (idA != null && idB != null && idA != idB) continue
                val sameKnownBubble = idA != null && idA == idB
                val gap = when {
                    b.top >= a.bottom -> b.top - a.bottom
                    a.top >= b.bottom -> a.top - b.bottom
                    else -> 0
                }
                val closeLines = horizontalOverlap(a, b) >= 0.55f &&
                    gap <= 0.45f * max(a.height(), b.height())
                val sameBalloon = sameKnownBubble || shareSmallBubble(a, b) ||
                    (idA == null && idB == null && closeLines)
                if (sameBalloon) join(i, j)
            }
        }
        val clusters = LinkedHashMap<Int, ArrayList<OcrCandidate>>()
        candList.forEachIndexed { i, c -> clusters.getOrPut(findRoot(i)) { ArrayList() }.add(c) }
        fun isLikelyWatermark(text: String, bounds: Rect): Boolean {
            val compact = text.trim().replace(Regex("\\s+"), "")
            val known = setOf("快看", "快看漫画", "KuaiKan", "Kuaikan", "kuaikan", "快看漫画网")
            if (known.any { compact.equals(it, ignoreCase = true) }) return true
            val cjkCount = compact.count { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }
            val shortCjk = cjkCount in 1..3 && compact.length <= 4
            if (!shortCjk) return false
            val nearLeft = bounds.left < source.width * 0.16f
            val nearRight = bounds.right > source.width * 0.84f
            val nearTop = bounds.top < source.height * 0.12f
            val nearBottom = bounds.bottom > source.height * 0.86f
            return (nearLeft || nearRight) && (nearTop || nearBottom)
        }
        fun emitBlock(list: List<OcrCandidate>) {
            val withBounds = list.mapNotNull { c -> c.bounds?.let { Rect(it) to c } }
            if (withBounds.isEmpty()) {
                skippedCount++
                val t = list.firstOrNull()?.text.orEmpty()
                if (t.isNotBlank()) failedSamples.add("بلا إحداثيات · ${t.take(90)}")
                return
            }
            val union = Rect(withBounds.first().first)
            withBounds.drop(1).forEach { union.union(it.first) }
            if (union.width() < 3 || union.height() < 3) { skippedCount++; return }
            val joined = withBounds.sortedBy { it.first.top }
                .joinToString(" ") { it.second.text.trim() }
                .replace(Regex("\\s+"), " ").trim()
            if (joined.isBlank()) { skippedCount++; return }
            if (isLikelyWatermark(joined, union)) { skippedCount++; return }
            val code = languageForBlock(joined, withBounds.first().second.engineLanguage, forcedLanguage)
            if (code == "ar") { skippedCount++; return }
            val pageArea = source.width.toLong() * source.height.toLong()
            val unionArea = union.width().toLong() * union.height().toLong().coerceAtLeast(1L)
            val container = list.firstNotNullOfOrNull { candidate ->
                candidate.containerBounds?.let { candidateBox ->
                    val safe = Rect(candidateBox)
                    val area = safe.width().toLong() * safe.height().toLong()
                    if (safe.contains(union) && area <= pageArea * 6L / 100L && area <= unionArea * 6L) safe else null
                }
            }
            blocks.add(BlockForTranslation(union, joined, code, container, withBounds.size))
        }
        clusters.values.forEach { emitBlock(it) }
        if (blocks.isEmpty()) {
            updateQualityCounts(candidates.size, 0, 0, skippedCount, failedSamples)
            onProgress(1f, "لم توجد كتل أجنبية ذات إحداثيات صالحة")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }

        val translatedBlocks = ArrayList<TranslationLine>()
        var failedCount = 0
        blocks.forEachIndexed { index, item ->
            try {
                onProgress(0.43f + (0.50f * index / blocks.size), "تجهيز نموذج ${item.language} ثم ترجمة الكتلة ${index + 1}/${blocks.size}")
                val normalizedSource = softenAllCaps(correctLikelyOcrText(item.text, item.language))
                val learned = learnedTranslationFor(item.text) ?: learnedTranslationFor(normalizedSource)
                val sfx = if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_TRANSLATE_SFX, false)) {
                    dictionarySfxTranslation(item.text)
                } else null
                val glossaryOverride = MangaFoxKnowledgeStore(this).exactGlossaryTranslation(normalizedSource)
                var translatedRaw: String? = learned ?: sfx ?: glossaryOverride
                if (translatedRaw == null) {
                    val mixed = hasArabicLetters(normalizedSource) && hasForeignLetters(normalizedSource)
                    translatedRaw = runCatching {
                        if (mixed) translateMixedTextPreservingArabic(normalizedSource, item.language)
                        else translateInChunks(normalizedSource, item.language)
                    }.getOrNull()
                    val firstClean = translatedRaw?.let { cleanArabicTranslation(it) }.orEmpty()
                    val needsArabicOutput = hasForeignLetters(normalizedSource)
                    if (firstClean.isBlank() || firstClean.equals(item.text, ignoreCase = true) ||
                        (needsArabicOutput && !isUsefulArabicTranslation(normalizedSource, firstClean))) {
                        // Retry not only on empty output: reject a model that simply echoes Latin OCR.
                        for (retryCode in retryLanguageCandidates(normalizedSource, item.language)) {
                            val retried = runCatching {
                                if (mixed) translateMixedTextPreservingArabic(normalizedSource, retryCode)
                                else translateInChunks(normalizedSource, retryCode)
                            }.getOrNull()
                            if (!retried.isNullOrBlank()) {
                                val cleanedRetry = cleanArabicTranslation(retried)
                                if (cleanedRetry.isNotBlank() && !cleanedRetry.equals(item.text, ignoreCase = true) &&
                                    !hasUntranslatedLatinRuns(cleanedRetry) &&
                                    (!needsArabicOutput || isUsefulArabicTranslation(normalizedSource, cleanedRetry))) {
                                    translatedRaw = retried
                                    break
                                }
                            }
                        }
                    }
                }
                val translated = translatedRaw?.let { cleanArabicTranslation(it) }.orEmpty()
                val sourceNeedsArabic = hasForeignLetters(item.text)
                val junkLatin = hasUntranslatedLatinRuns(translated) &&
                    learned == null && sfx == null && glossaryOverride == null
                if (translated.isBlank() ||
                    (translated.equals(item.text, ignoreCase = true) && item.text.length > 8) ||
                    junkLatin ||
                    (sourceNeedsArabic && !isUsefulArabicTranslation(item.text, translated))) {
                    // Text-heavy bubbles usually carry decorative lettering the full-page
                    // OCR pass misreads. Re-read just this region with enhanced variants
                    // and translate the cleaner reading before declaring failure.
                    val enhancedRaw = if (learned == null && sfx == null && glossaryOverride == null)
                        retranslateBlockWithEnhancedOcr(source, item, item.language) else null
                    val enhanced = enhancedRaw?.let { cleanArabicTranslation(it) }.orEmpty()
                    if (enhanced.isNotBlank() && !enhanced.equals(item.text, ignoreCase = true) &&
                        !hasUntranslatedLatinRuns(enhanced) &&
                        (!sourceNeedsArabic || isUsefulArabicTranslation(item.text, enhanced))) {
                        translatedBlocks.add(TranslationLine(
                            Rect(item.bounds), enhanced, item.text, item.language,
                            vertical = item.mergedLines <= 1 && MangaFoxLayoutEngine.shouldUseVertical(item.bounds),
                            containerBounds = item.containerBounds?.let(::Rect)
                        ))
                    } else {
                        failedCount++
                        failedSamples.add(item.text.take(100))
                    }
                } else {
                    translatedBlocks.add(TranslationLine(
                        Rect(item.bounds), translated, item.text, item.language,
                        vertical = item.mergedLines <= 1 && MangaFoxLayoutEngine.shouldUseVertical(item.bounds),
                        containerBounds = item.containerBounds?.let(::Rect)
                    ))
                }
            } catch (_: Exception) {
                val rescued = runCatching { retranslateBlockWithEnhancedOcr(source, item, item.language) }.getOrNull()
                val rescuedClean = rescued?.let { cleanArabicTranslation(it) }.orEmpty()
                if (rescuedClean.isNotBlank() && !hasUntranslatedLatinRuns(rescuedClean)) {
                    translatedBlocks.add(TranslationLine(
                        Rect(item.bounds), rescuedClean, item.text, item.language,
                        vertical = item.mergedLines <= 1 && MangaFoxLayoutEngine.shouldUseVertical(item.bounds),
                        containerBounds = item.containerBounds?.let(::Rect)
                    ))
                } else {
                    failedCount++
                    failedSamples.add(item.text.take(100))
                }
            }
            onProgress(0.45f + (0.48f * (index + 1) / blocks.size), "ترجمة الكتلة ${index + 1}/${blocks.size}")
        }
        updateQualityCounts(candidatesAfterMissed.size, translatedBlocks.size, failedCount, skippedCount, failedSamples)
        if (translatedBlocks.isEmpty()) {
            onProgress(1f, "لم تنجح ترجمة أي كتلة؛ راجع تقرير الجودة")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }
        lastOcrTranslationLines.set(translatedBlocks.map {
            TranslationLine(
                Rect(it.bounds), it.translated, it.sourceText, it.language,
                vertical = MangaFoxLayoutEngine.shouldUseVertical(it.bounds),
                containerBounds = it.containerBounds?.let(::Rect)
            )
        })
        onProgress(0.98f, "إخراج النص العربي بجودة عالية")
        val rendered = drawTranslatedLines(source, translatedBlocks)
        onProgress(1f, "اكتملت الصورة · ${translatedBlocks.size} مترجمة · $failedCount فاشلة · $skippedCount متروكة")
        return rendered
    }

    /** Keeps new rendered pages lossless while preserving JPEG compatibility for older sessions. */
    private fun writeRenderedBitmap(bitmap: Bitmap, target: File) {
        val png = target.extension.equals("png", ignoreCase = true)
        FileOutputStream(target).use { stream ->
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (png) 100 else 96
            if (!bitmap.compress(format, quality, stream)) throw IllegalStateException("تعذر حفظ الصورة المعالجة")
        }
    }

    /** Conservative local glyph eraser. It never fills the whole OCR rectangle. */
    private fun eraseTextPixels(bitmap: Bitmap, area: Rect) {
        val r = Rect(area)
        if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.width() < 5 || r.height() < 5) return
        val w = r.width(); val h = r.height()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, r.left, r.top, w, h)
        val marginX = max(1, w / 18); val marginY = max(1, h / 14)
        val buckets = HashMap<Int, MutableList<Int>>()
        for (y in marginY until (h - marginY).coerceAtLeast(marginY + 1) step max(1, h / 28)) {
            for (x in marginX until (w - marginX).coerceAtLeast(marginX + 1) step max(1, w / 28)) {
                val c = pixels[y * w + x]
                val key = ((Color.red(c) shr 4) shl 8) or ((Color.green(c) shr 4) shl 4) or (Color.blue(c) shr 4)
                buckets.getOrPut(key) { ArrayList() }.add(c)
            }
        }
        val dominant = buckets.values.maxByOrNull { it.size } ?: return
        if (dominant.size < 4) return
        val br = dominant.map { Color.red(it) }.sorted()[dominant.size / 2]
        val bg = dominant.map { Color.green(it) }.sorted()[dominant.size / 2]
        val bb = dominant.map { Color.blue(it) }.sorted()[dominant.size / 2]
        val out = pixels.copyOf()
        // Restrict detection to the central 88% so balloon borders are not touched.
        val left = max(1, w / 16); val right = min(w - 1, w - left)
        val top = max(1, h / 14); val bottom = min(h - 1, h - top)
        for (y in top until bottom) for (x in left until right) {
            val idx = y * w + x; val c = pixels[idx]
            val dr = abs(Color.red(c) - br); val dg = abs(Color.green(c) - bg); val db = abs(Color.blue(c) - bb)
            val distance = dr + dg + db
            // Only remove near-neutral/dark ink against a stable local dominant fill.
            val luminance = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            val baseLum = (br * 30 + bg * 59 + bb * 11) / 100
            val likelyInk = distance >= 95 && abs(baseLum - luminance) >= 42
            if (likelyInk) {
                // Feather a one-pixel antialias fringe, not a 5x5 rectangle.
                out[idx] = Color.rgb(br, bg, bb)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in left until right || ny !in top until bottom) continue
                    val ni = ny * w + nx; val n = pixels[ni]
                    val nd = abs(Color.red(n) - br) + abs(Color.green(n) - bg) + abs(Color.blue(n) - bb)
                    if (nd in 45..150) out[ni] = Color.rgb(br, bg, bb)
                }
            }
        }
        bitmap.setPixels(out, 0, w, r.left, r.top, w, h)
    }

    private fun drawTranslatedLines(source: Bitmap, translatedLines: List<TranslationLine>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("تعذر إنشاء صورة الترجمة")
        val canvas = Canvas(output)
        val renderItems = translatedLines.mapNotNull { item ->
            val eraseRect = Rect(item.bounds).apply {
                val padX = max(2, width() / 16)
                val padY = max(2, height() / 12)
                inset(-padX, -padY)
                left = left.coerceIn(0, output.width)
                top = top.coerceIn(0, output.height)
                right = right.coerceIn(0, output.width)
                bottom = bottom.coerceIn(0, output.height)
            }
            val trustedContainer = item.containerBounds?.let { candidate ->
                val area = candidate.width().toLong() * candidate.height().toLong()
                val textArea = item.bounds.width().toLong() * item.bounds.height().toLong().coerceAtLeast(1L)
                val pageArea = output.width.toLong() * output.height.toLong()
                Rect(candidate).takeIf { it.contains(item.bounds) && area <= pageArea * 6L / 100L && area <= textArea * 6L }
            }
            val layoutRect = (trustedContainer ?: Rect(eraseRect)).apply {
                // Stay inside the detected balloon outline; OCR text bounds remain the erase area.
                if (trustedContainer != null) {
                    inset(max(4, width() / 14), max(4, height() / 14))
                    // HARD CLAMP: typesetting must stay near the OCR text box. Bright-region
                    // detection can return near-page-sized rectangles (white margins connect
                    // to bubbles); without this clamp Arabic text was drawn far outside the balloon.
                    val maxW = max(48, (eraseRect.width() * 2.2f).roundToInt())
                    val maxH = max(48, (eraseRect.height() * 3.0f).roundToInt())
                    val cx = eraseRect.centerX()
                    val cy = eraseRect.centerY()
                    val allowed = Rect(
                        (cx - maxW / 2).coerceIn(0, output.width),
                        (cy - maxH / 2).coerceIn(0, output.height),
                        (cx + maxW / 2).coerceIn(0, output.width),
                        (cy + maxH / 2).coerceIn(0, output.height)
                    )
                    val intersection = Rect(this)
                    if (intersection.intersect(allowed)) set(intersection) else set(eraseRect)
                }
                left = left.coerceIn(0, output.width)
                top = top.coerceIn(0, output.height)
                right = right.coerceIn(0, output.width)
                bottom = bottom.coerceIn(0, output.height)
            }
            if (eraseRect.width() < 8 || eraseRect.height() < 8 ||
                layoutRect.width() < 8 || layoutRect.height() < 8) null
            else Triple(item, eraseRect, layoutRect)
        }
        // Pass 1: erase every source text region first. This avoids a later bubble's
        // background rectangle overwriting Arabic text drawn for an earlier bubble.
        renderItems.forEach { (item, eraseRect, _) ->
            // Never paint a rectangular/oval plate over artwork. Remove only high-contrast
            // glyph pixels inside the OCR box, preserving balloon outlines and tails.
            eraseTextPixels(output, eraseRect)
        }
        // Pass 2: draw text only after all backgrounds are ready; clipping keeps
        // long words/layouts from spilling into neighboring bubbles.
        renderItems.forEach { (item, _, layoutRect) ->
            if (!item.eraseOnly && item.translated.isNotBlank()) {
                canvas.save()
                canvas.clipRect(layoutRect)
                drawArabicText(canvas, item.translated, layoutRect, item.vertical)
                canvas.restore()
            }
        }
        return output
    }

    /** Uses the dominant pixels around a speech balloon; dark/colorful fills are not forced to white. */
    private fun sampleBubbleColor(bitmap: Bitmap, rect: Rect): Int =
        MangaFoxBubbleRepair.sampleBackground(bitmap, rect)

    private fun drawArabicText(canvas: Canvas, text: String, rect: Rect, vertical: Boolean = false) {
        if (vertical) {
            drawArabicTextTopToBottom(canvas, text, rect)
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val insetX = max(4, rect.width() / 18)
        val insetY = max(3, rect.height() / 14)
        val availableWidth = (rect.width() - insetX * 2).coerceAtLeast(12)
        val availableHeight = (rect.height() - insetY * 2).coerceAtLeast(12)
        val maxPx = min(
            prefs.getInt(PREF_FONT_SIZE, 22).coerceIn(10, 72) * density,
            (availableHeight * 0.78f).coerceAtLeast(8f * density)
        )
        val automatic = prefs.getBoolean(PREF_AUTO_FONT_FIT, true)
        val layout = if (automatic) {
            findLargestArabicLayout(text, availableWidth, availableHeight, maxPx, 7.5f * density)
        } else {
            buildArabicLayout(text, availableWidth, maxPx)
        }
        val x = rect.left + insetX
        val y = rect.top + ((rect.height() - layout.height) / 2f).coerceAtLeast(insetY.toFloat())
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(x.toFloat(), y)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Binary-searches the largest type size whose shaped RTL layout fits both dimensions. */
    private fun findLargestArabicLayout(
        value: String, width: Int, height: Int, maxSizePx: Float, minSizePx: Float
    ): android.text.StaticLayout {
        val lower = min(minSizePx, maxSizePx).coerceAtLeast(5f)
        var low = lower
        var high = maxSizePx.coerceAtLeast(lower)
        var best = buildArabicLayout(value, width, lower)
        var bestFits = best.height <= height && (0 until best.lineCount).all { best.getLineWidth(it) <= width + 1f }
        repeat(14) {
            val middle = (low + high) / 2f
            val candidate = buildArabicLayout(value, width, middle)
            val fits = candidate.height <= height && (0 until candidate.lineCount).all {
                candidate.getLineWidth(it) <= width + 1f
            }
            if (fits) { best = candidate; bestFits = true; low = middle } else high = middle
        }
        // Keep the complete text at the minimum readable size if exceptionally long copy
        // cannot fully fit; the caller clips to the speech region rather than spilling outside.
        if (!bestFits) return buildArabicLayout(value, width, lower)
        return best
    }

    /** Stacks Arabic words from top to bottom while preserving each word's connected shaping. */
    private fun drawArabicTextTopToBottom(canvas: Canvas, value: String, rect: Rect) {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val maxWidth = (rect.width() - 10).coerceAtLeast(10)
        val maxHeight = (rect.height() - 10).coerceAtLeast(10)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val configured = prefs.getInt(PREF_FONT_SIZE, 22).coerceIn(10, 72) * density
        var chosenSize = min(configured, maxWidth * 0.90f).coerceAtLeast(7f * density)
        var layouts = emptyList<android.text.StaticLayout>()
        var heights = emptyList<Int>()
        val autoFit = prefs.getBoolean(PREF_AUTO_FONT_FIT, true)
        val floor = 7.5f * density
        for (attempt in 0 until if (autoFit) 36 else 1) {
            val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(20, 20, 22); typeface = resolveArabicTypeface(prefs)
                textSize = chosenSize; style = Paint.Style.FILL
            }
            layouts = words.map { word ->
                android.text.StaticLayout.Builder.obtain(word, 0, word.length, paint, maxWidth)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(android.text.TextDirectionHeuristics.RTL)
                    .setIncludePad(true)
                    .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .build()
            }
            heights = layouts.map { it.height }
            val total = heights.sum()
            if (!autoFit || total <= maxHeight || chosenSize <= floor) break
            chosenSize = max(floor, chosenSize - max(0.5f * density, (chosenSize - floor) / 8f))
        }
        val totalHeight = heights.sum()
        val spacing = max(1, (maxHeight - totalHeight) / (words.size + 1))
        var y = rect.top + spacing.toFloat()
        canvas.save()
        canvas.clipRect(rect)
        layouts.forEachIndexed { index, layout ->
            canvas.save()
            canvas.translate(rect.left + (rect.width() - layout.width) / 2f, y)
            layout.draw(canvas)
            canvas.restore()
            y += heights[index] + spacing
        }
        canvas.restore()
    }

    private fun buildArabicLayout(value: String, width: Int, size: Float): android.text.StaticLayout {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 22)
            textSize = size
            typeface = resolveArabicTypeface(prefs)
            style = Paint.Style.FILL
            strokeWidth = 0f
        }
        return android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .setIncludePad(true)
            .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(0f, 0.96f)
            .build()
    }

    private fun updateTranslationLens(percent: Int, detail: String) {
        if (percent >= 0) MangaFoxRuntime.progressPercent = percent.coerceIn(0, 100)
        MangaFoxRuntime.progressDetail = detail
        if (percent >= 0) MangaFoxRuntime.status = "ترجمة الصور: ${percent.coerceIn(0, 100)}% · $detail"
        mainHandler.post {
            val view = bubble ?: return@post
            val current = if (translationRunning) MangaFoxRuntime.progressPercent else if (percent >= 100) 100 else percent
            view.text = when {
                percent < 0 -> "!"
                translationRunning -> "${current.coerceIn(0, 99)}%"
                current >= 100 -> "✓"
                else -> "狐"
            }
            view.textSize = if (translationRunning) 13f else 25f
            view.contentDescription = if (translationRunning) {
                "Manga Fox: ترجمة الصور ${current}%، $detail"
            } else if (percent < 0) {
                "Manga Fox: حدث خطأ أثناء الترجمة"
            } else {
                "Manga Fox Translator. اضغط لفتح خيارات الترجمة واسحب لتحريك العدسة"
            }
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
                contentDescription = "Manga Fox Translator. اضغط لفتح خيارات الترجمة واسحب لتحريك العدسة"
            }
        }
    }

    fun applyLensSettings() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val size = prefs.getInt(PREF_LENS_SIZE, 58).coerceIn(42, 96)
        params.width = dp(size)
        params.height = dp(size)
        view.textSize = (size * 0.40f).coerceIn(18f, 38f)
        view.alpha = prefs.getInt(PREF_LENS_ALPHA, 100).coerceIn(35, 100) / 100f
        view.text = prefs.getString(PREF_LENS_GLYPH, "狐") ?: "狐"
        view.background = lensBackground(prefs.getInt(PREF_LENS_COLOR, Color.rgb(255, 190, 65)))
        if (bubbleWasAdded) runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun lensBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
    }

    /** Keep the single overlay attached regardless of which app/window is in front. */
    fun refreshLensAppFilter() {
        if (!this::windowManager.isInitialized) return
        val overlayAllowed = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        if (!overlayAllowed) {
            MangaFoxRuntime.status = "يلزم السماح بالظهور فوق التطبيقات"
            MangaFoxRuntime.lastError = "افتح إعدادات Android واسمح لـ Manga FOX بالظهور فوق التطبيقات الأخرى."
            return
        }
        // Idempotent: accessibility events and Activity lifecycle callbacks must never
        // remove/recreate an already attached view, otherwise the lens visibly flickers.
        if (bubbleWasAdded && bubble != null) return
        showFloatingBubble()
    }

    /** Opens the action menu. A lens tap must never start capture directly. */
    private fun showFloatingOptions() {
        if (!this::windowManager.isInitialized) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("اسمح لـ Manga FOX بالظهور فوق التطبيقات لفتح قائمة العدسة")
            return
        }
        removeFloatingOptions()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 24, 31))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(190, 145, 65))
            }
            elevation = dp(18).toFloat()
        }
        panel.addView(TextView(this).apply {
            text = "MANGA FOX  ·  خيارات العدسة"
            textSize = 17f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.rgb(255, 198, 92))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(this).apply {
            text = "اختر العملية التي تريد تنفيذها"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))

        fun addAction(title: String, detail: String, accent: Boolean = false, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(9), dp(13), dp(9))
                background = GradientDrawable().apply {
                    setColor(if (accent) Color.rgb(76, 57, 29) else Color.rgb(37, 37, 47))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), if (accent) Color.rgb(210, 164, 77) else Color.rgb(65, 65, 78))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    removeFloatingOptions()
                    action()
                }
            }
            item.addView(TextView(this).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, -2))
            item.addView(TextView(this).apply {
                text = detail
                textSize = 11f
                setTextColor(Color.LTGRAY)
            }, LinearLayout.LayoutParams(-1, -2))
            panel.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
            })
        }

        addAction("ترجمة الشاشة الحالية", "التقاط الشاشة الظاهرة ثم تحليل النصوص وترجمتها", true) {
            startSingleCapture(500L)
        }
        addAction("ترجمة الفصل العمودي كاملاً", "تمرير الفصل والتقاط صفحاته ثم ترجمتها بأعلى جودة متاحة") {
            startFullChapterCapture(800L)
        }
        addAction("التقاط الفصل دون ترجمة", "حفظ صور الفصل الأصلية لتصديرها أو معالجتها لاحقاً") {
            startFullChapterCaptureOnly(800L)
        }
        addAction("إيقاف الالتقاط الحالي", "إيقاف الالتقاط والاحتفاظ بالمقاطع التي جُمعت") {
            stopCaptureByUser()
        }
        addAction("عرض آخر نتيجة مترجمة", "فتح الصور والطبقات والترجمة المحفوظة") {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = ACTION_SHOW_RESULTS
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }.onFailure { toast("تعذر فتح آخر نتيجة: ${it.localizedMessage ?: "خطأ"}") }
        }

        val close = TextView(this).apply {
            text = "إغلاق"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 198, 92))
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(2))
            setOnClickListener { removeFloatingOptions() }
        }
        panel.addView(close, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(panel, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val params = WindowManager.LayoutParams(
            min(dp(330), (resources.displayMetrics.widthPixels * 0.90f).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0f
        }
        floatingOptionsView = scroll
        floatingOptionsParams = params
        runCatching { windowManager.addView(scroll, params) }
            .onFailure {
                floatingOptionsView = null
                floatingOptionsParams = null
                toast("تعذر فتح قائمة العدسة. تحقق من إذن الظهور فوق التطبيقات")
            }
    }

    private fun removeFloatingOptions() {
        val view = floatingOptionsView
        if (view != null && this::windowManager.isInitialized) {
            runCatching { windowManager.removeView(view) }
        }
        floatingOptionsView = null
        floatingOptionsParams = null
    }

    private fun showFloatingBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        if (bubbleWasAdded || !this::windowManager.isInitialized) return
        val lensPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lensSize = lensPrefs.getInt(PREF_LENS_SIZE, 58).coerceIn(42, 96)
        val lensAlpha = lensPrefs.getInt(PREF_LENS_ALPHA, 100).coerceIn(35, 100) / 100f
        val view = TextView(this).apply {
            text = lensPrefs.getString(PREF_LENS_GLYPH, "狐") ?: "狐"
            textSize = (lensSize * 0.40f).coerceIn(18f, 38f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = lensBackground(lensPrefs.getInt(PREF_LENS_COLOR, Color.rgb(255, 190, 65)))
            elevation = dp(8).toFloat()
            alpha = lensAlpha
            contentDescription = "Manga Fox Translator. اضغط لفتح خيارات الترجمة واسحب لتحريك العدسة"
        }
        val params = WindowManager.LayoutParams(
            dp(lensSize), dp(lensSize),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lensPrefs.getInt("lens_x", dp(12))
            y = lensPrefs.getInt("lens_y", (resources.displayMetrics.heightPixels * 0.36f).toInt())
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
                    if (moved) {
                        lensPrefs.edit().putInt("lens_x", params.x).putInt("lens_y", params.y).apply()
                    } else {
                        // A tap only opens the menu. Capture begins exclusively after
                        // the user selects one of the menu actions.
                        showFloatingOptions()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        if (translationRunning) {
            view.text = "${MangaFoxRuntime.progressPercent.coerceIn(0, 99)}%"
            view.textSize = 13f
            view.contentDescription = "Manga Fox: ترجمة الصور ${MangaFoxRuntime.progressPercent}%"
        }
        bubble = view
        bubbleParams = params
        runCatching {
            windowManager.addView(view, params)
            bubbleWasAdded = true
            MangaFoxRuntime.status = "العدسة العائمة ظاهرة"
        }.onFailure { error ->
            bubble = null
            bubbleParams = null
            bubbleWasAdded = false
            MangaFoxRuntime.status = "تعذر إظهار العدسة: ${error.javaClass.simpleName}"
            MangaFoxRuntime.lastError = "تعذر إنشاء النافذة العائمة. تحقق من إذن الظهور فوق التطبيقات الأخرى. ${error.message ?: ""}"
        }
    }

    private fun removeFloatingBubble() {
        removeFloatingOptions()
        val view = bubble
        if (view != null && bubbleWasAdded) runCatching { windowManager.removeView(view) }
        // Drop the detached View reference. Keeping it made the re-enable path
        // call restoreFloatingBubble(), which only scheduled another refresh and
        // never re-attached or recreated the overlay.
        bubbleWasAdded = false
        bubble = null
        bubbleParams = null
    }

    private fun restoreFloatingBubble() {
        // Compatibility hook for older call sites. Visibility is no longer tied to foreground app.
        refreshLensAppFilter()
    }

    private fun notifyCaptureProgress() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "manga_fox_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "التقاط Manga Fox", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val stopPending = PendingIntent.getActivity(
            this, 402, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Manga Fox · التقاط الفصل")
            .setContentText("${captureCount} لقطة ملتقطة · اضغط زر إيقاف الالتقاط في الإشعار")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف الالتقاط", stopPending)
            .build()
        runCatching { manager.notify(402, notification) }
    }

    private fun clearCaptureNotification() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(402)
        }
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

// PHANTOM CORE INLINE: kept in this file so source-only GitHub uploads cannot omit engine classes.


// ============================================================
// MERGED FROM: MangaFox_V3_0_3_MangaFoxEngines.kt
// ============================================================

/** Persistent glossary and character bible. This is local-only and never uploads page text. */
class MangaFoxKnowledgeStore(context: Context) {
    private val prefs = context.getSharedPreferences("mfox_phantom_knowledge_v1", Context.MODE_PRIVATE)
    private val lock = Any()

    data class GlossaryEntry(val source: String, val arabic: String, val note: String = "")
    data class CharacterEntry(val name: String, val voice: String, val notes: String = "")

    fun glossary(): List<GlossaryEntry> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs.getString(KEY_GLOSSARY, "[]")) }.getOrElse { JSONArray() }
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { obj ->
                val source = obj.optString("source").trim()
                val arabic = obj.optString("arabic").trim()
                if (source.isBlank() || arabic.isBlank()) null else GlossaryEntry(source, arabic, obj.optString("note"))
            }
        }
    }

    fun saveGlossaryEntry(entry: GlossaryEntry) = synchronized(lock) {
        val values = glossary().filterNot { it.source.equals(entry.source, true) }.toMutableList()
        values.add(entry)
        val array = JSONArray()
        values.forEach { array.put(JSONObject().put("source", it.source).put("arabic", it.arabic).put("note", it.note)) }
        prefs.edit().putString(KEY_GLOSSARY, array.toString()).apply()
    }

    fun deleteGlossaryEntry(source: String) = synchronized(lock) {
        val array = JSONArray()
        glossary().filterNot { it.source.equals(source, true) }.forEach {
            array.put(JSONObject().put("source", it.source).put("arabic", it.arabic).put("note", it.note))
        }
        prefs.edit().putString(KEY_GLOSSARY, array.toString()).apply()
    }

    fun characters(): List<CharacterEntry> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs.getString(KEY_CHARACTERS, "[]")) }.getOrElse { JSONArray() }
        (0 until array.length()).mapNotNull { i ->
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
    }

    fun deleteCharacter(name: String) = synchronized(lock) {
        val array = JSONArray()
        characters().filterNot { it.name.equals(name, true) }.forEach {
            array.put(JSONObject().put("name", it.name).put("voice", it.voice).put("notes", it.notes))
        }
        prefs.edit().putString(KEY_CHARACTERS, array.toString()).apply()
    }

    /** Exact whole-line entries are safe overrides; partial terms are deliberately not blindly substituted. */
    fun exactGlossaryTranslation(source: String): String? = glossary().firstOrNull {
        normalize(it.source) == normalize(source)
    }?.arabic

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
            val source = item.optString("source").trim().take(180)
            val arabic = item.optString("arabic").trim().take(300)
            if (source.isNotBlank() && arabic.isNotBlank()) safeGlossary.put(JSONObject().put("source", source).put("arabic", arabic).put("note", item.optString("note").take(300)))
        }
        val safeCharacters = JSONArray()
        for (i in 0 until characterArray.length()) {
            val item = characterArray.optJSONObject(i) ?: continue
            val name = item.optString("name").trim().take(100)
            if (name.isNotBlank()) safeCharacters.put(JSONObject().put("name", name).put("voice", item.optString("voice").take(300)).put("notes", item.optString("notes").take(1000)))
        }
        prefs.edit().putString(KEY_GLOSSARY, safeGlossary.toString()).putString(KEY_CHARACTERS, safeCharacters.toString()).apply()
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    companion object {
        private const val KEY_GLOSSARY = "glossary_json"
        private const val KEY_CHARACTERS = "characters_json"
    }
}

data class MangaFoxQualityFinding(val severity: String, val page: Int, val message: String)
data class MangaFoxPageAudit(
    val page: Int,
    val layerCount: Int,
    val overlapCount: Int,
    val outOfBoundsCount: Int,
    val untranslatedCount: Int,
    val emptyTextCount: Int,
    val findings: List<MangaFoxQualityFinding>
)
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
        if (pages.all { it.findings.isEmpty() }) append("لم تُكتشف مشاكل بنيوية في الطبقات. هذا لا يثبت صحة OCR أو الترجمة بصرياً؛ راجع الصورة يدوياً أيضاً.")
        else append("ملاحظة: هذا الفحص يتحقق من بيانات الطبقات والإحداثيات والتداخل. لا يستطيع وحده إثبات أن كل كلمة في الصورة تُرجمت.")
    }
}

/** Static quality gate for known structural failure modes: overlap, clipping, blank and likely untranslated layers. */
object MangaFoxQualityGuard {
    fun inspect(resultPaths: List<String>, layerPaths: List<String>): MangaFoxQualityReport {
        val audits = resultPaths.indices.map { pageIndex ->
            val findings = ArrayList<MangaFoxQualityFinding>()
            val imagePath = resultPaths.getOrNull(pageIndex).orEmpty()
            val imageFile = File(imagePath)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (imageFile.isFile) BitmapFactory.decodeFile(imagePath, options)
            val width = options.outWidth
            val height = options.outHeight
            val layerPath = layerPaths.getOrNull(pageIndex).orEmpty()
            val layerFile = File(layerPath)
            val array = if (layerFile.isFile) runCatching { JSONArray(layerFile.readText(Charsets.UTF_8)) }.getOrNull() else null
            val layers = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
                .filter { it.has("left") && it.has("top") && it.has("right") && it.has("bottom") }
            var outside = 0
            var overlap = 0
            var untranslated = 0
            var empty = 0
            val rects = ArrayList<Pair<Rect, Int>>()
            layers.forEachIndexed { index, layer ->
                val left = layer.optInt("left", -1)
                val top = layer.optInt("top", -1)
                val right = layer.optInt("right", -1)
                val bottom = layer.optInt("bottom", -1)
                if (left < 0 || top < 0 || right <= left || bottom <= top || (width > 0 && right > width) || (height > 0 && bottom > height)) {
                    outside++
                    findings.add(MangaFoxQualityFinding("خطأ", pageIndex + 1, "الطبقة ${index + 1} تتجاوز حدود الصورة أو تملك إحداثيات غير صالحة"))
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
                                findings.add(MangaFoxQualityFinding(severity, pageIndex + 1, "تداخل ملحوظ بين الطبقتين ${otherIndex + 1} و${index + 1}"))
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
                        findings.add(MangaFoxQualityFinding("خطأ", pageIndex + 1, "الطبقة ${index + 1} لا تحتوي نص ترجمة"))
                    } else {
                        fun isArabicLetter(ch: Char): Boolean = ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' ||
                            ch in '\u08A0'..'\u08FF' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'
                        val sourceHasForeign = source.any { it.isLetter() && !isArabicLetter(it) }
                        val outputForeignLetters = translated.count { it.isLetter() && !isArabicLetter(it) }
                        if (sourceHasForeign && outputForeignLetters >= 3) {
                            untranslated++
                            findings.add(MangaFoxQualityFinding("مراجعة", pageIndex + 1, "قد تحتوي الطبقة ${index + 1} على أحرف أجنبية متبقية؛ قد تكون اسماً أو مؤثراً صوتياً ويجب مراجعتها"))
                        }
                    }
                }
            }
            if (!imageFile.isFile) findings.add(MangaFoxQualityFinding("خطأ", pageIndex + 1, "ملف الصورة الناتجة غير موجود"))
            if (!layerFile.isFile) findings.add(MangaFoxQualityFinding("مراجعة", pageIndex + 1, "ملف الطبقات غير موجود؛ لا يمكن تدقيق العناصر القابلة للتحرير"))
            MangaFoxPageAudit(pageIndex + 1, layers.size, overlap, outside, untranslated, empty, findings)
        }
        return MangaFoxQualityReport(audits)
    }
}

/** Per-page JSON snapshots for undo/redo. Snapshots are capped to avoid unlimited storage growth. */
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

/** Safe ZIP backup of source/result/layer files plus local terminology and character notes. */
object MangaFoxProjectArchive {
    private const val MAX_IMPORT_BYTES = 600L * 1024L * 1024L

    fun export(context: Context, output: OutputStream) {
        val root = JSONObject()
            .put("format", "MANGAFOX_PHANTOM_PROJECT")
            .put("version", 1)
            .put("title", MangaFoxRuntime.resultTitle)
        val sources = JSONArray(); val results = JSONArray(); val layers = JSONArray()
        // Keep every page index aligned even when an older project has a missing source or layer file.
        MangaFoxRuntime.resultPages.forEachIndexed { index, resultPath ->
            val source = File(MangaFoxRuntime.sourcePages.getOrNull(index).orEmpty())
            val result = File(resultPath)
            val layer = File(MangaFoxRuntime.translationLayerFiles.getOrNull(index).orEmpty())
            sources.put(if (source.isFile) "source/$index${source.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".jpg"}" else "")
            results.put(if (result.isFile) "result/$index${result.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".jpg"}" else "")
            layers.put(if (layer.isFile) "layers/$index.json" else "")
        }
        root.put("sources", sources).put("results", results).put("layers", layers)
            .put("quality", MangaFoxRuntime.qualitySummary)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(root.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            for (i in 0 until sources.length()) {
                val entry = sources.optString(i)
                if (entry.isNotBlank()) putFile(zip, entry, File(MangaFoxRuntime.sourcePages[i]))
            }
            for (i in 0 until results.length()) {
                val entry = results.optString(i)
                if (entry.isNotBlank()) putFile(zip, entry, File(MangaFoxRuntime.resultPages[i]))
            }
            for (i in 0 until layers.length()) {
                val entry = layers.optString(i)
                if (entry.isNotBlank()) putFile(zip, entry, File(MangaFoxRuntime.translationLayerFiles[i]))
            }
            zip.putNextEntry(ZipEntry("knowledge.json"))
            zip.write(MangaFoxKnowledgeStore(context).exportJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun restore(context: Context, input: InputStream) {
        val temporary = File(context.filesDir, "mfox_phantom_import_${System.currentTimeMillis()}").apply { mkdirs() }
        var consumed = 0L
        var importedRootForRollback: File? = null
        var importCommitted = false
        val seenEntries = HashSet<String>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(seenEntries.add(name)) { "النسخة تحتوي ملفاً مكرراً: $name" }
                    require(!entry.isDirectory && !name.startsWith("/") && !name.contains("..") &&
                        (name == "project.json" || name == "knowledge.json" || name.matches(Regex("(source|result)/[0-9]+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)) || name.matches(Regex("layers/[0-9]+\\.json")))) {
                        "ملف مشروع غير آمن أو غير مدعوم: $name"
                    }
                    val target = File(temporary, name)
                    require(target.canonicalPath.startsWith(temporary.canonicalPath + File.separator)) { "مسار استيراد غير آمن" }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            consumed += read
                            require(consumed <= MAX_IMPORT_BYTES) { "حجم النسخة الاحتياطية أكبر من الحد المسموح" }
                            out.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = File(temporary, "project.json")
            require(manifest.isFile && manifest.length() <= 2L * 1024L * 1024L) { "ملف project.json غير موجود أو أكبر من الحد المسموح" }
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            require(root.optString("format") == "MANGAFOX_PHANTOM_PROJECT") { "صيغة النسخة غير معروفة" }
            val importedRoot = File(context.filesDir, "mfox_phantom_projects/import_${System.currentTimeMillis()}").apply { mkdirs() }
            importedRootForRollback = importedRoot
            val sourceNames = root.optJSONArray("sources") ?: JSONArray()
            val resultNames = root.optJSONArray("results") ?: JSONArray()
            val layerNames = root.optJSONArray("layers") ?: JSONArray()
            val count = minOf(resultNames.length(), sourceNames.length())
            require(count in 1..2000) { "عدد صفحات النسخة غير صالح أو يتجاوز 2000 صفحة" }
            val newSources = ArrayList<String>(count)
            val newResults = ArrayList<String>(count)
            val newLayers = ArrayList<String>(count)
            for (i in 0 until count) {
                fun copyEntry(name: String, folder: String): String {
                    if (name.isBlank()) return ""
                    val validName = when (folder) {
                        "source", "result" -> name.matches(Regex("$folder/[0-9]+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE))
                        "layers" -> name.matches(Regex("layers/[0-9]+\\.json"))
                        else -> false
                    }
                    require(validName) { "مرجع ملف غير صالح" }
                    val from = File(temporary, name)
                    require(from.canonicalPath.startsWith(temporary.canonicalPath + File.separator) && from.isFile) { "ملف مفقود أو مساره غير آمن: $name" }
                    val to = File(importedRoot, name).apply { parentFile?.mkdirs() }
                    from.copyTo(to, overwrite = true)
                    return to.absolutePath
                }
                newSources.add(copyEntry(sourceNames.optString(i), "source"))
                newResults.add(copyEntry(resultNames.optString(i), "result"))
                newLayers.add(copyEntry(layerNames.optString(i), "layers"))
            }
            require(newResults.any { it.isNotBlank() && File(it).isFile }) { "النسخة لا تحتوي صور نتائج صالحة" }
            MangaFoxRuntime.sourcePages = newSources
            MangaFoxRuntime.resultPages = newResults
            MangaFoxRuntime.translationLayerFiles = newLayers
            MangaFoxRuntime.resultTitle = root.optString("title", "مشروع Manga FOX مستورد")
            MangaFoxRuntime.qualitySummary = root.optString("quality", "تم استيراد المشروع")
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


/** Dominant boundary-color sampler for flat speech balloons; avoids forcing dark/colorful areas to white. */
object MangaFoxBubbleRepair {
    /**
     * Samples the balloon face away from its outline and away from the source glyphs.
     * A quantized dominant-color estimate is more stable than sampling pixels just
     * outside an OCR rectangle, where the dark outline creates the former gray halo.
     */
    fun sampleInteriorColor(bitmap: android.graphics.Bitmap, balloon: Rect, excludedText: Rect): Int {
        if (bitmap.width < 2 || bitmap.height < 2) return android.graphics.Color.WHITE
        val safe = Rect(balloon).apply {
            inset(maxOf(2, width() / 10), maxOf(2, height() / 10))
            left = left.coerceIn(0, bitmap.width - 1)
            top = top.coerceIn(0, bitmap.height - 1)
            right = right.coerceIn(left + 1, bitmap.width)
            bottom = bottom.coerceIn(top + 1, bitmap.height)
        }
        val excluded = Rect(excludedText).apply {
            inset(-maxOf(3, width() / 5), -maxOf(3, height() / 5))
        }
        val stepX = maxOf(1, safe.width() / 24)
        val stepY = maxOf(1, safe.height() / 24)
        val samples = ArrayList<Int>(500)
        var y = safe.top
        while (y < safe.bottom) {
            var x = safe.left
            while (x < safe.right) {
                if (!excluded.contains(x, y)) samples.add(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        if (samples.size < 8) {
            // For a very large text box, recover the dominant color from the full
            // inner balloon. Text pixels are usually a minority in a speech bubble.
            var fy = safe.top
            while (fy < safe.bottom) {
                var fx = safe.left
                while (fx < safe.right) {
                    samples.add(bitmap.getPixel(fx, fy))
                    fx += stepX
                }
                fy += stepY
            }
        }
        if (samples.isEmpty()) return android.graphics.Color.WHITE

        val buckets = HashMap<Int, MutableList<Int>>()
        samples.forEach { color ->
            val key = ((android.graphics.Color.red(color) shr 5) shl 6) or
                ((android.graphics.Color.green(color) shr 5) shl 3) or
                (android.graphics.Color.blue(color) shr 5)
            buckets.getOrPut(key) { ArrayList() }.add(color)
        }
        val dominant = buckets.values.maxByOrNull { it.size }.orEmpty()
        val chosen = if (dominant.size >= maxOf(6, samples.size / 10)) dominant else samples
        fun median(channel: (Int) -> Int): Int = chosen.map(channel).sorted()[chosen.size / 2]
        val red = median { android.graphics.Color.red(it) }
        val green = median { android.graphics.Color.green(it) }
        val blue = median { android.graphics.Color.blue(it) }
        return android.graphics.Color.rgb(red, green, blue)
    }

    fun sampleBackground(bitmap: android.graphics.Bitmap, area: Rect): Int {
        if (bitmap.width < 2 || bitmap.height < 2) return android.graphics.Color.WHITE
        val left = area.left.coerceIn(0, bitmap.width - 1)
        val right = (area.right - 1).coerceIn(left, bitmap.width - 1)
        val top = area.top.coerceIn(0, bitmap.height - 1)
        val bottom = (area.bottom - 1).coerceIn(top, bitmap.height - 1)
        val gap = maxOf(2, minOf(area.width(), area.height()) / 12)
        val colors = ArrayList<Int>(48)
        val steps = 12
        for (i in 0..steps) {
            val x = left + ((right - left) * i / steps)
            val y = top + ((bottom - top) * i / steps)
            val topY = (top - gap).coerceAtLeast(0)
            val bottomY = (bottom + gap).coerceAtMost(bitmap.height - 1)
            val leftX = (left - gap).coerceAtLeast(0)
            val rightX = (right + gap).coerceAtMost(bitmap.width - 1)
            colors.add(bitmap.getPixel(x, topY))
            colors.add(bitmap.getPixel(x, bottomY))
            colors.add(bitmap.getPixel(leftX, y))
            colors.add(bitmap.getPixel(rightX, y))
        }
        fun lum(c: Int) = (android.graphics.Color.red(c) * 30 + android.graphics.Color.green(c) * 59 + android.graphics.Color.blue(c) * 11) / 100
        val bright = colors.filter { lum(it) >= 218 }
        val source = if (bright.size >= colors.size * 0.68f) bright else colors
        fun median(channel: (Int) -> Int): Int = source.map(channel).sorted()[source.size / 2]
        val r = median { android.graphics.Color.red(it) }
        val g = median { android.graphics.Color.green(it) }
        val b = median { android.graphics.Color.blue(it) }
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        val brightness = (r * 30 + g * 59 + b * 11) / 100
        return when {
            bright.size >= colors.size * 0.72f && brightness >= 228 && chroma <= 28 -> android.graphics.Color.WHITE
            brightness >= 242 && chroma <= 12 -> android.graphics.Color.rgb(250, 250, 250)
            else -> android.graphics.Color.rgb(r, g, b)
        }
    }
}

/** Conservative direction and fitting hints shared by the renderer and editor. */
object MangaFoxLayoutEngine {
    fun shouldUseVertical(bounds: Rect, userChoice: String = "auto"): Boolean = when (userChoice) {
        "vertical" -> true
        "horizontal" -> false
        else -> bounds.height() > bounds.width() * 1.42f
    }

    fun fittingFontSize(preferredPx: Float, rectWidth: Int, rectHeight: Int, minimumPx: Float = 7f): Float {
        val safeWidth = rectWidth.coerceAtLeast(8).toFloat()
        val safeHeight = rectHeight.coerceAtLeast(8).toFloat()
        return minOf(preferredPx, safeWidth * 0.82f, safeHeight * 0.72f).coerceAtLeast(minimumPx)
    }
}

/** Runtime profile helper; it never starts more than one CPU-heavy page job per configured worker. */
/** Encrypted API-secret storage backed by Android Keystore; the raw key is never written to a plain-text preference. */
internal object MangaFoxSecureSecretStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "manga_fox_ai_api_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Synchronized private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(context: Context, secret: String) {
        require(secret.isNotBlank()) { "مفتاح API فارغ" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_AI_SECRET_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(PREF_AI_SECRET_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun get(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val iv = Base64.decode(prefs.getString(PREF_AI_SECRET_IV, null), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString(PREF_AI_SECRET_CIPHER, null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(PREF_AI_SECRET_IV).remove(PREF_AI_SECRET_CIPHER).apply()
    }
}

/** OpenAI-compatible Chat Completions adapter specialized for Chinese-to-Arabic manhua dialogue. */
internal object MangaFoxAiTranslator {
    fun translate(
        context: Context,
        source: String,
        glossary: List<MangaFoxKnowledgeStore.GlossaryEntry>,
        characters: List<MangaFoxKnowledgeStore.CharacterEntry>,
        testRequest: Boolean = false
    ): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = MangaFoxSecureSecretStore.get(context)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("مفتاح API غير محفوظ")
        val endpoint = (prefs.getString(PREF_AI_ENDPOINT, "https://api.openai.com/v1/chat/completions")
            ?: "https://api.openai.com/v1/chat/completions").trim()
        require(endpoint.startsWith("https://", true)) { "يُسمح بعنوان HTTPS فقط" }
        val model = (prefs.getString(PREF_AI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini").trim()
        require(model.isNotBlank()) { "اسم النموذج فارغ" }

        val glossaryText = glossary.take(100).joinToString("\n") {
            "${it.source} = ${it.arabic}" + if (it.note.isBlank()) "" else " (${it.note})"
        }.ifBlank { "لا توجد مصطلحات محفوظة." }
        val characterText = characters.take(60).joinToString("\n") {
            "${it.name}" + if (it.voice.isBlank() && it.notes.isBlank()) "" else " | ${it.voice} | ${it.notes}"
        }.ifBlank { "لا توجد بيانات شخصيات محفوظة." }

        val systemPrompt = """
أنت مترجم مانها صينية محترف إلى العربية الفصحى. مهمتك نقل المعنى المقصود بدقة، لا الترجمة الحرفية العمياء.
قواعد إلزامية:
1) افهم تركيب الصينية المبسطة أو التقليدية، الضمائر المحذوفة، السياق، التلميح، السخرية، الأمثال والتعابير الاصطلاحية.
2) اكتب عربية فصحى طبيعية ومتماسكة تناسب حوار المانهوا، مع الحفاظ على نبرة المتحدث (تهديد، احترام، غضب، دهشة، مزاح...).
3) لا تضف أحداثاً أو معلومات غير موجودة، ولا تحذف معنى أو شرطاً أو نفياً أو درجة احترام.
4) ثبّت أسماء الشخصيات والأماكن والمهارات والألقاب وفق المعجم المرفق. لا تترجم الاسم العلم ترجمة وصفية.
5) إذا كان النص ناقصاً أو غامضاً، اختر أقرب معنى تدعمه الكلمات ولا تخترع سياقاً. حافظ على علامات الوقف والانفعال عند الحاجة.
6) أخرج الترجمة العربية فقط، دون شرح أو مقدمات أو علامات اقتباس زائدة. لا تكرر النص الصيني.
7) المؤثرات الصوتية تُصاغ عربياً بإيجاز بما يناسب المشهد، ولا تحولها إلى جملة حوار.

ذاكرة المصطلحات:
$glossaryText

ذاكرة الشخصيات ونبراتها:
$characterText
""".trimIndent()

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.15)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", if (testRequest) "你是谁？请用阿拉伯语回答。" else source)))
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 18000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            val json = JSONObject(response)
            val content = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.opt("content")
            val translated = when (content) {
                is String -> content.trim()
                is JSONArray -> (0 until content.length()).mapNotNull { i ->
                    content.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString(" ").trim()
                else -> ""
            }
            if (translated.isBlank()) throw IllegalStateException("أعاد API استجابة بلا نص مترجم")
            translated
        } finally {
            connection.disconnect()
        }
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


// ============================================================
// MERGED FROM: MangaFox_V3_0_3_MangaFoxImageViews.kt
// ============================================================

class RegionSelectionView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 190, 65)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 65)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private var startX = 0f
    private var startY = 0f
    private var selection = RectF()
    private var hasSelection = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hasSelection) {
            canvas.drawRect(selection, fillPaint)
            canvas.drawRect(selection, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x.coerceIn(0f, width.toFloat())
                startY = event.y.coerceIn(0f, height.toFloat())
                selection.set(startX, startY, startX, startY)
                hasSelection = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val x = event.x.coerceIn(0f, width.toFloat())
                val y = event.y.coerceIn(0f, height.toFloat())
                selection.set(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hasSelection = false
                invalidate()
                return true
            }
        }
        return true
    }

    fun toSourceRect(sourceWidth: Int, sourceHeight: Int): Rect? {
        if (!hasSelection || width <= 0 || height <= 0) return null
        val rect = Rect(
            (selection.left / width * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1),
            (selection.top / height * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1),
            (selection.right / width * sourceWidth).roundToInt().coerceIn(1, sourceWidth),
            (selection.bottom / height * sourceHeight).roundToInt().coerceIn(1, sourceHeight)
        )
        return rect.takeIf { it.width() > 0 && it.height() > 0 }
    }
}

class ZoomableImageView(context: Context) : ImageView(context) {
    private val drawMatrix = Matrix()
    private val displayedRect = RectF()
    private var userScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var imageFilePath: String? = null
    private var loadedBitmap: Bitmap? = null
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (userScale * detector.scaleFactor).coerceIn(1f, 5f)
            val factor = next / userScale
            userScale = next
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            fixBounds()
            imageMatrix = drawMatrix
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetBaseMatrix()
    }

    fun setImageFile(path: String) {
        imageFilePath = path
        releasePage()
    }

    fun loadPage() {
        if (loadedBitmap != null) return
        val path = imageFilePath ?: return
        val bitmap = BitmapFactory.decodeFile(path) ?: return
        loadedBitmap = bitmap
        super.setImageBitmap(bitmap)
        post { resetBaseMatrix() }
    }

    fun releasePage() {
        val old = loadedBitmap
        loadedBitmap = null
        super.setImageDrawable(null)
        old?.let { if (!it.isRecycled) it.recycle() }
        userScale = 1f
        drawMatrix.reset()
    }

    override fun onDetachedFromWindow() {
        releasePage()
        super.onDetachedFromWindow()
    }

    private fun resetBaseMatrix() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        // The page view is measured from the exact source aspect ratio. Scale X/Y
        // independently by final integer bounds so rounding cannot leave a one-pixel
        // black seam between consecutive chapter pages.
        val scaleX = width.toFloat() / d.intrinsicWidth.toFloat()
        val scaleY = height.toFloat() / d.intrinsicHeight.toFloat()
        drawMatrix.reset()
        drawMatrix.setScale(scaleX, scaleY)
        userScale = 1f
        imageMatrix = drawMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = userScale > 1.01f
                parent?.requestDisallowInterceptTouchEvent(dragging)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (userScale > 1.01f && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    drawMatrix.postTranslate(dx, dy)
                    fixBounds()
                    imageMatrix = drawMatrix
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                lastX = event.x
                lastY = event.y
                return true
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
        var dx = 0f
        var dy = 0f
        if (displayedRect.width() <= width) dx = width / 2f - displayedRect.centerX()
        else if (displayedRect.left > 0f) dx = -displayedRect.left
        else if (displayedRect.right < width) dx = width - displayedRect.right
        if (displayedRect.height() <= height) dy = height / 2f - displayedRect.centerY()
        else if (displayedRect.top > 0f) dy = -displayedRect.top
        else if (displayedRect.bottom < height) dy = height - displayedRect.bottom
        drawMatrix.postTranslate(dx, dy)
    }
}


// ============================================================
// MERGED FROM: MangaFox_V3_0_3_MangaFoxRuntime.kt
// ============================================================

object MangaFoxRuntime {
    private const val SESSION_MANIFEST = "mfox_session_v1.json"

    @Synchronized fun persist(context: Context) {
        runCatching {
            val root = org.json.JSONObject()
                .put("title", resultTitle)
                .put("quality", qualitySummary)
                .put("sources", org.json.JSONArray(sourcePages))
                .put("results", org.json.JSONArray(resultPages))
                .put("layers", org.json.JSONArray(translationLayerFiles))
                .put("captureOnly", captureOnlyResult)
            val target = File(context.filesDir, SESSION_MANIFEST)
            val temp = File(context.filesDir, "$SESSION_MANIFEST.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
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
                    (context.filesDir.listFiles()?.toList().orEmpty() + context.cacheDir.listFiles()?.toList().orEmpty()),
                    "mfox_result_"
                )
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
            val root = org.json.JSONObject(manifest.readText(Charsets.UTF_8))
            // Keep positional alignment: an empty layer path for page 2 must not
            // shift page 3's layer file into page 2 after process restoration.
            fun stringList(key: String): List<String> {
                val array = root.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).map { index -> array.optString(index, "") }
            }
            val sources = stringList("sources")
            val results = stringList("results")
            val layers = stringList("layers")
            val count = minOf(sources.size, results.size)
            sourcePages = sources.take(count)
            resultPages = results.take(count)
            translationLayerFiles = (0 until count).map { layers.getOrNull(it).orEmpty() }
            resultTitle = root.optString("title", "الفصل المترجم")
            qualitySummary = root.optString("quality", qualitySummary)
            captureOnlyResult = root.optBoolean("captureOnly", false)
            if (resultPages.none { File(it).isFile }) {
                sourcePages = emptyList()
                resultPages = emptyList()
                translationLayerFiles = emptyList()
            }
        }
    }

    @Synchronized fun removePage(context: Context, index: Int) {
        if (index !in resultPages.indices) return
        val removed = listOfNotNull(
            resultPages.getOrNull(index), sourcePages.getOrNull(index), translationLayerFiles.getOrNull(index)
        )
        resultPages = resultPages.toMutableList().also { it.removeAt(index) }
        if (index in sourcePages.indices) sourcePages = sourcePages.toMutableList().also { it.removeAt(index) }
        if (index in translationLayerFiles.indices) translationLayerFiles = translationLayerFiles.toMutableList().also { it.removeAt(index) }
        removed.forEach { path -> runCatching { File(path).delete() } }
        persist(context)
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
    // Per-page OCR/translation layers are stored separately from the rendered JPG.
    @Volatile var translationLayerFiles: List<String> = emptyList()
    @Volatile var captureOnlyResult: Boolean = false
}