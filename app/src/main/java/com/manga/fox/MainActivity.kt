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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val ACTION_SHOW_RESULTS = "com.manga.fox.SHOW_RESULTS"
private const val ACTION_STOP_CAPTURE = "com.manga.fox.STOP_CAPTURE"
private const val PREFS_NAME = "manga_fox_v2_preferences"
private const val PREF_FONT_SIZE = "font_size_sp"
private const val PREF_FONT_FAMILY = "font_family"
private const val PREF_FONT_BOLD = "font_bold"
private const val PREF_FONT_FILE = "font_file_path"
private const val PREF_FONT_LABEL = "font_file_label"
private const val PREF_FONT_THICKNESS = "font_thickness"
private const val REQUEST_PICK_FONT = 713
private const val REQUEST_SAVE_PDF = 714
private const val PREF_LENS_SIZE = "lens_size_dp"
private const val PREF_LENS_ALPHA = "lens_alpha"
private const val PREF_LENS_GLYPH = "lens_custom_glyph"
private const val PREF_LENS_COLOR = "lens_custom_color"
private const val PREF_SOURCE_LANGUAGE = "translation_source_language"
private const val PREF_ONBOARDING_SEEN = "onboarding_seen_v1"
private const val PREF_TRANSLATE_SFX = "translate_japanese_sfx"
private const val PREF_LEARNED_TRANSLATIONS = "learned_translation_dictionary"
private const val MAX_CAPTURE_FRAMES = 120

object MangaFoxRuntime {
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
}

class MainActivity : android.app.Activity() {
    private val dark = Color.rgb(10, 10, 13)
    private val panel = Color.rgb(25, 25, 31)
    private val gold = Color.rgb(255, 190, 65)
    private lateinit var preferences: SharedPreferences
    private var fontPreview: TextView? = null
    private var fontStatusText: TextView? = null
    private var readingFullscreen = false
    private var pendingPdfFile: File? = null
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
        override fun run() {
            if (!homeVisible) return
            refreshHomeIndicators()
            homeUiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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
        listOf("translation" to "ترجمة", "settings" to "إعدادات", "library" to "مكتبة").forEach { (key, label) ->
            val tab = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setTextColor(if (selectedHomeTab == key) Color.BLACK else Color.LTGRAY)
                background = rounded(if (selectedHomeTab == key) gold else panel, 12)
                setOnClickListener {
                    selectedHomeTab = key
                    updateHomeTabs(tabs)
                    renderHomeTab()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            }
            tabs.addView(tab)
        }
        root.addView(tabs, matchWrap(dp(12)))
        homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(homeContent, matchWrap())
        root.addView(TextView(this).apply {
            text = "MANGA FOX  ·  N5CR4"
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
        val keys = listOf("translation", "settings", "library")
        val labels = listOf("ترجمة", "إعدادات", "مكتبة")
        tabs.removeAllViews()
        keys.forEachIndexed { index, key ->
            tabs.addView(Button(this).apply {
                text = labels[index]
                textSize = 13f
                isAllCaps = false
                setTextColor(if (selectedHomeTab == key) Color.BLACK else Color.LTGRAY)
                background = rounded(if (selectedHomeTab == key) gold else panel, 12)
                setOnClickListener {
                    selectedHomeTab = key
                    updateHomeTabs(tabs)
                    renderHomeTab()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
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

    private fun addLibraryTab(root: LinearLayout) {
        val library = card()
        library.addView(text("مكتبة النتائج", 19f, gold, true))
        library.addView(text("آخر فصل مترجم: ${MangaFoxRuntime.resultTitle}", 13f, Color.LTGRAY))
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
        settingsCard.addView(text("عاين شكل الحروف مباشرة، وغيّر الحجم والسماكة ونوع الخط. يمكنك تنزيل خطوط عربية أو استيراد ملف TTF / OTF من هاتفك.", 12f, Color.LTGRAY))
        settingsCard.addView(text("ملاحظة: النتائج المحفوظة كصور لا تتغير تلقائياً؛ بعد تعديل الخط افتح النتيجة واضغط «إعادة تطبيق الخط والحجم» لإعادة إخراجها.", 11f, gold))

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
        addSettingSlider(
            settingsCard, "سماكة الحروف في المعاينة والترجمة", 0, 10,
            preferences.getInt(PREF_FONT_THICKNESS, 2), " / 10"
        ) { value ->
            preferences.edit().putInt(PREF_FONT_THICKNESS, value).apply()
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
        preview.paint.style = Paint.Style.FILL_AND_STROKE
        preview.paint.strokeWidth = prefs.getInt(PREF_FONT_THICKNESS, 2).coerceIn(0, 10) / 10f
        preview.invalidate()
        fontStatusText?.text = prefs.getString(PREF_FONT_LABEL, null)?.let { "الخط المحدد: $it" }
            ?: "الخط الحالي: ${prefs.getString(PREF_FONT_FAMILY, "sans-serif")}"
    }

    private fun pickFontFromDevice() {
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
        if (requestCode != REQUEST_PICK_FONT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
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
                .putString(PREF_FONT_LABEL, displayName).apply()
            updateFontPreview()
            toast("تم استيراد الخط وتفعيله: $displayName")
        }.onFailure { toast("تعذر استيراد الخط: ${it.localizedMessage ?: "ملف غير صالح"}") }
    }

    private fun exportResultsAsPdf() {
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

    private fun showResults() {
        setReadingFullscreen(true)
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
        root.addView(actionButton("تنزيل النتيجة كملف PDF", true) { exportResultsAsPdf() }, matchWrap(dp(8)))
        root.addView(actionButton("تطبيق الخط والحجم الجديدين دون إعادة OCR", true) {
            val service = MangaFoxAccessibilityService.instance
            if (MangaFoxRuntime.sourcePages.isEmpty() || MangaFoxRuntime.translationLayerFiles.isEmpty()) {
                toast("طبقات الترجمة غير متوفرة لهذه النتيجة؛ أعد ترجمة الفصل أولاً")
            } else if (service == null) {
                toast("فعّل خدمة Manga Fox لإعادة تنسيق الطبقات")
            } else {
                service.rerenderLastResultsFromLayers { showResults() }
            }
        }, matchWrap(dp(8)))
        root.addView(actionButton("إعادة OCR وترجمة الصور الأصلية من جديد", false) {
            MangaFoxAccessibilityService.instance?.retranslateLastResults()
                ?: toast("فعّل خدمة Manga Fox لإعادة المعالجة")
        }, matchWrap(dp(8)))
        root.addView(text("تقرير جودة OCR والترجمة", 15f, gold, true), matchWrap())
        root.addView(text("${MangaFoxRuntime.qualitySummary}\nالكتل التي فشلت لا تُرسم كنص أصلي على أنها ترجمة؛ تبقى الصورة كما هي ويظهر سبب الفشل في هذه القائمة.", 12f, Color.LTGRAY), matchWrap(dp(6)))
        root.addView(actionButton("عرض عينات النصوص غير المترجمة / إعادة المحاولة", false) {
            val samples = MangaFoxRuntime.failedBlockSamples.take(25)
            if (samples.isEmpty()) {
                AlertDialog.Builder(this).setTitle("النصوص التي تعذرت ترجمتها")
                    .setMessage("لا توجد عينات فشل مسجلة حالياً.")
                    .setPositiveButton("إغلاق", null).show()
            } else {
                AlertDialog.Builder(this).setTitle("اختر نصاً لتصحيح ترجمته")
                    .setItems(samples.toTypedArray()) { _, which -> showLearnedTranslationDialog(samples[which]) }
                    .setNeutralButton("إعادة معالجة الفصل") { _, _ ->
                        MangaFoxAccessibilityService.instance?.retranslateLastResults()
                            ?: toast("خدمة Manga Fox غير متصلة")
                    }
                    .setNegativeButton("إغلاق", null).show()
            }
        }, matchWrap(dp(8)))

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
                    contentDescription = "الجزء المترجم ${index + 1} — كبّر بإصبعين، واضغط مطولاً للمقارنة مع الأصل"
                    setBackgroundColor(dark)
                    setOnLongClickListener {
                        if (originalPath == null) {
                            toast("الصورة الأصلية غير متوفرة لهذا الجزء")
                        } else {
                            showingOriginal = !showingOriginal
                            setImageFile(if (showingOriginal) originalPath else filePath)
                            loadPage()
                            toast(if (showingOriginal) "عرض الصورة الأصلية · اضغط مطولاً للعودة للترجمة" else "عرض الترجمة العربية")
                        }
                        true
                    }
                }
                val displayWidth = resources.displayMetrics.widthPixels - dp(24)
                val displayHeight = (sourceHeight * (displayWidth.toFloat() / sourceWidth)).toInt().coerceAtLeast(1)
                root.addView(image, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, displayHeight
                ).apply {
                    // Every captured strip begins exactly where the previous one ends.
                    // A layout margin here creates a visible artificial manga gutter.
                    bottomMargin = 0
                })
                pageViews.add(image)
                if (originalPath != null) {
                    root.addView(actionButton("إعادة OCR لمنطقة من هذا الجزء", false) {
                        openRegionSelection(originalPath, filePath)
                    }, matchWrap(dp(6)))
                }
                if (MangaFoxRuntime.translationLayerFiles.getOrNull(index)?.let { File(it).isFile } == true) {
                    root.addView(actionButton("تعديل ترجمة فقاعات هذا الجزء دون OCR", false) {
                        editTranslationLayer(index)
                    }, matchWrap(dp(8)))
                }
            }
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateVisiblePageImages(pageViews) }
            scroll.post { updateVisiblePageImages(pageViews) }
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun editTranslationLayer(pageIndex: Int) {
        val path = MangaFoxRuntime.translationLayerFiles.getOrNull(pageIndex)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            toast("طبقات هذا الجزء غير متوفرة")
            return
        }
        val layers = runCatching { org.json.JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull()
        if (layers == null || layers.length() == 0) {
            toast("لم تُحفظ فقاعات مترجمة لهذا الجزء")
            return
        }
        val labels = Array(layers.length()) { index ->
            val item = layers.optJSONObject(index)
            val source = item?.optString("source").orEmpty().take(46)
            val translated = item?.optString("translated").orEmpty().take(46)
            "${index + 1}. $source  ←  $translated"
        }
        AlertDialog.Builder(this)
            .setTitle("اختر فقاعة لتعديل ترجمتها")
            .setItems(labels) { _, selected ->
                val item = layers.optJSONObject(selected) ?: return@setItems
                val edit = EditText(this).apply {
                    setText(item.optString("translated"))
                    hint = "اكتب الترجمة العربية"
                    minLines = 2
                    textSize = 16f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                AlertDialog.Builder(this)
                    .setTitle("تعديل الفقاعة ${selected + 1}")
                    .setMessage("النص الأصلي: ${item.optString("source")}")
                    .setView(edit)
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حفظ وتحديث الصورة") { _, _ ->
                        val corrected = edit.text.toString().trim()
                        if (corrected.isBlank()) {
                            toast("لا يمكن حفظ ترجمة فارغة")
                        } else {
                            item.put("translated", corrected)
                            runCatching {
                                file.writeText(layers.toString(), Charsets.UTF_8)
                                val sourceText = item.optString("source").trim()
                                if (sourceText.isNotBlank()) {
                                    val dictionary = runCatching {
                                        org.json.JSONObject(preferences.getString(PREF_LEARNED_TRANSLATIONS, "{}") ?: "{}")
                                    }.getOrElse { org.json.JSONObject() }
                                    dictionary.put(sourceText, corrected)
                                    preferences.edit().putString(PREF_LEARNED_TRANSLATIONS, dictionary.toString()).apply()
                                }
                            }
                                .onSuccess {
                                    val service = MangaFoxAccessibilityService.instance
                                    if (service == null) toast("تم حفظ التصحيح في القاموس؛ فعّل الخدمة لإعادة رسمه")
                                    else service.rerenderLastResultsFromLayers { showResults() }
                                }
                                .onFailure { toast("تعذر حفظ التصحيح: ${it.localizedMessage ?: "خطأ"}") }
                        }
                    }.show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun updateVisiblePageImages(pages: List<ZoomableImageView>) {
        val visibleBounds = android.graphics.Rect()
        pages.forEach { page ->
            if (page.getLocalVisibleRect(visibleBounds)) page.loadPage() else page.releasePage()
        }
    }

    private fun refreshStatus() = refreshHomeIndicators()

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

private class RegionSelectionView(context: Context) : View(context) {
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

private class ZoomableImageView(context: Context) : ImageView(context) {
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
        val fit = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        val left = (width - d.intrinsicWidth * fit) / 2f
        val top = (height - d.intrinsicHeight * fit) / 2f
        drawMatrix.reset()
        drawMatrix.setScale(fit, fit)
        drawMatrix.postTranslate(left, top)
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

private fun resolveArabicTypeface(prefs: SharedPreferences): Typeface {
    val style = if (prefs.getBoolean(PREF_FONT_BOLD, true)) Typeface.BOLD else Typeface.NORMAL
    val customPath = prefs.getString(PREF_FONT_FILE, null)
    if (!customPath.isNullOrBlank()) {
        val custom = runCatching {
            val file = File(customPath)
            if (file.isFile) Typeface.createFromFile(file) else null
        }.getOrNull()
        if (custom != null) return Typeface.create(custom, style)
    }
    val family = prefs.getString(PREF_FONT_FAMILY, "sans-serif") ?: "sans-serif"
    return Typeface.create(family, style)
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
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var hostActivityResumed: Boolean = false
    private var captureRunning = false
    private var fullChapterMode = false
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
    @Volatile private var lastOcrTranslationLines: List<TranslationLine> = emptyList()

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
        translateSavedSegments(workPages, note, if (workPages.size > 1) "الفصل العمودي" else "الصفحة الحالية")
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
                // Keep the manually retried region in the editable layer file as well.
                runCatching {
                    val pageIndex = MangaFoxRuntime.resultPages.indexOfFirst {
                        File(it).absolutePath == File(resultPath).absolutePath
                    }
                    val layerPath = MangaFoxRuntime.translationLayerFiles.getOrNull(pageIndex)
                    val layerFile = layerPath?.let { File(it) }
                    if (pageIndex >= 0 && layerFile != null && layerFile.isFile) {
                        val saved = org.json.JSONArray(layerFile.readText(Charsets.UTF_8))
                        val refreshed = ArrayList<org.json.JSONObject>()
                        for (i in 0 until saved.length()) {
                            val record = saved.optJSONObject(i) ?: continue
                            val oldRect = Rect(record.optInt("left"), record.optInt("top"), record.optInt("right"), record.optInt("bottom"))
                            val replaced = lastOcrTranslationLines.any { line ->
                                val newRect = Rect(line.bounds).apply { offset(safe.left, safe.top) }
                                val overlap = Rect(oldRect)
                                if (!overlap.intersect(newRect)) false
                                else {
                                    val intersection = overlap.width().toFloat() * overlap.height()
                                    val union = oldRect.width().toFloat() * oldRect.height() + newRect.width().toFloat() * newRect.height() - intersection
                                    union > 0f && intersection / union > 0.5f
                                }
                            }
                            if (!replaced) refreshed.add(record)
                        }
                        lastOcrTranslationLines.forEach { line ->
                            val rect = Rect(line.bounds).apply { offset(safe.left, safe.top) }
                            refreshed.add(org.json.JSONObject()
                                .put("left", rect.left).put("top", rect.top)
                                .put("right", rect.right).put("bottom", rect.bottom)
                                .put("source", line.sourceText).put("translated", line.translated)
                                .put("language", line.language))
                        }
                        val outputLayers = org.json.JSONArray()
                        refreshed.forEach { outputLayers.put(it) }
                        layerFile.writeText(outputLayers.toString(), Charsets.UTF_8)
                    }
                }
                output = BitmapFactory.decodeFile(resultPath) ?: original!!.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(output!!).drawBitmap(translatedCrop!!, safe.left.toFloat(), safe.top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
                val target = File(resultPath)
                val temp = File(target.parentFile, target.nameWithoutExtension + "_region.tmp.jpg")
                FileOutputStream(temp).use { stream ->
                    if (!output!!.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IllegalStateException("تعذر حفظ المنطقة بعد الترجمة")
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
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
        if (sources.isEmpty() || sources.size != targets.size || sources.size != layers.size ||
            sources.any { !it.isFile } || targets.any { !it.isFile } || layers.any { !it.isFile }) {
            toast("طبقات الترجمة أو الصور الأصلية غير مكتملة؛ أعد ترجمة الفصل")
            return
        }
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
                        original = BitmapFactory.decodeFile(sources[index].absolutePath)
                            ?: throw IllegalStateException("تعذر قراءة الصورة الأصلية ${index + 1}")
                        val json = org.json.JSONArray(layers[index].readText(Charsets.UTF_8))
                        val records = ArrayList<TranslationLine>(json.length())
                        for (recordIndex in 0 until json.length()) {
                            val item = json.optJSONObject(recordIndex) ?: continue
                            val rect = Rect(
                                item.optInt("left"), item.optInt("top"),
                                item.optInt("right"), item.optInt("bottom")
                            )
                            if (rect.width() > 0 && rect.height() > 0) {
                                records.add(TranslationLine(
                                    rect, item.optString("translated"),
                                    item.optString("source"), item.optString("language", "und")
                                ))
                            }
                        }
                        rendered = drawTranslatedLines(original!!, records)
                        val target = targets[index]
                        val temp = File(target.parentFile, target.nameWithoutExtension + "_layers.tmp.jpg")
                        FileOutputStream(temp).use { stream ->
                            if (!rendered!!.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                                throw IllegalStateException("تعذر حفظ تنسيق الصورة ${index + 1}")
                            }
                        }
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
        // Bound memory for unusually tall reader captures while preserving the complete strip.
        val globalScale = min(1f, 7000f / naturalTotalHeight.toFloat())
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
        MangaFoxRuntime.status = "بدء استخراج النصوص وترجمتها..."
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
                            lastOcrTranslationLines.forEach { line ->
                                layerArray.put(org.json.JSONObject()
                                    .put("left", line.bounds.left).put("top", line.bounds.top)
                                    .put("right", line.bounds.right).put("bottom", line.bounds.bottom)
                                    .put("source", line.sourceText).put("translated", line.translated)
                                    .put("language", line.language))
                            }
                            FileOutputStream(layerFile).use { stream ->
                                stream.write(layerArray.toString().toByteArray(Charsets.UTF_8))
                            }
                            newLayerFiles.add(layerFile.absolutePath)
                            val outputFile = File(cacheDir, "mfox_result_${System.currentTimeMillis()}_$index.jpg")
                            FileOutputStream(outputFile).use { stream ->
                                if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                                    throw IllegalStateException("تعذر حفظ نتيجة المقطع ${index + 1}")
                                }
                            }
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
        val language: String = "und"
    )
    private data class OcrCandidate(val bounds: Rect?, val text: String, val engineLanguage: String)
    private data class BlockForTranslation(val bounds: Rect, val text: String, val language: String)

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
            lineParts.sortedByDescending { it.boundingBox?.centerX() ?: 0f }
                .joinToString("") { it.text.trim() }
        } else {
            lineParts.joinToString(" ") { it.text.trim() }.ifBlank { block.text.trim() }
        }
        return OcrCandidate(boundsAtSource, phrase.trim(), engine)
    }

    private fun collectCandidates(result: Text?, engine: String, scale: Float = 1f): List<OcrCandidate> =
        result?.textBlocks.orEmpty().map { candidateFromBlock(it, engine, scale) }.filter { it.text.isNotBlank() }

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

    private fun mergeOcrCandidates(input: List<OcrCandidate>): List<OcrCandidate> {
        val merged = ArrayList<OcrCandidate>()
        input.forEach { candidate ->
            val bounds = candidate.bounds
            if (bounds == null) {
                merged.add(candidate)
                return@forEach
            }
            val overlapIndex = merged.indexOfFirst { existing ->
                existing.bounds?.let { intersectionOverUnion(it, bounds) > 0.50f } == true
            }
            if (overlapIndex < 0) merged.add(candidate)
            else {
                val old = merged[overlapIndex]
                val newScore = candidate.text.count { it.isLetterOrDigit() }
                val oldScore = old.text.count { it.isLetterOrDigit() }
                if (newScore > oldScore || (newScore == oldScore && candidate.text.length > old.text.length)) {
                    merged[overlapIndex] = candidate
                }
            }
        }
        return merged.sortedWith(compareBy<OcrCandidate> { it.bounds?.top ?: Int.MAX_VALUE }.thenBy { it.bounds?.left ?: Int.MAX_VALUE })
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
        val row = IntArray(width)
        for (y in 0 until height) {
            gray.getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) histogram[Color.red(pixel).coerceIn(0, 255)]++
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
            for (y in 0 until height) {
                gray.getPixels(row, 0, width, 0, y, width, 1)
                for (x in row.indices) {
                    val value = ((Color.red(row[x]) - low) * 255f / range).roundToInt().coerceIn(0, 255)
                    row[x] = Color.rgb(value, value, value)
                }
                gray.setPixels(row, 0, width, 0, y, width, 1)
            }
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
            val regions = ArrayList<Pair<Int, Rect>>()
            val minArea = max(24, (total * 0.00045f).toInt())
            val maxArea = (total * 0.22f).toInt()

            fun isBright(index: Int): Boolean {
                val x = index % sampleWidth
                val y = index / sampleWidth
                val color = sample.getPixel(x, y)
                return (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100 >= 218
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
                var touchesEdge = false
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % sampleWidth
                    val y = index / sampleWidth
                    area++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (x <= 0 || y <= 0 || x >= sampleWidth - 1 || y >= sampleHeight - 1) touchesEdge = true

                    val left = if (x > 0) index - 1 else -1
                    val right = if (x < sampleWidth - 1) index + 1 else -1
                    val up = if (y > 0) index - sampleWidth else -1
                    val down = if (y < sampleHeight - 1) index + sampleWidth else -1
                    if (left >= 0 && !visited[left]) { visited[left] = true; if (isBright(left)) queue[tail++] = left }
                    if (right >= 0 && !visited[right]) { visited[right] = true; if (isBright(right)) queue[tail++] = right }
                    if (up >= 0 && !visited[up]) { visited[up] = true; if (isBright(up)) queue[tail++] = up }
                    if (down >= 0 && !visited[down]) { visited[down] = true; if (isBright(down)) queue[tail++] = down }
                }
                if (touchesEdge || area < minArea || area > maxArea) continue
                val width = maxX - minX + 1
                val height = maxY - minY + 1
                if (width < 14 || height < 10) continue
                val ratio = width.toFloat() / height.toFloat()
                if (ratio !in 0.28f..4.6f) continue
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
                if (selected.none { intersectionOverUnion(it, rect) > 0.55f }) selected.add(rect)
            }
            return selected.take(6)
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
                engines.forEach { engine ->
                    val result = runCatching { Tasks.await(engine.second.process(input)) }.getOrNull()
                    collectCandidates(result, engine.first, enlarge).forEach { candidate ->
                        val shifted = candidate.bounds?.let { Rect(it).apply { offset(region.left, region.top) } }
                        found.add(candidate.copy(bounds = shifted))
                    }
                }
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
            onProgress(0.36f, "إعادة OCR داخل الفقاعة ${index + 1}/${regions.size}")
        }
        return found
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

    private fun languageForBlock(text: String, engineHint: String, forced: String): String {
        val letters = text.filter { it.isLetter() }
        val arabic = countChars(letters) { it in '\u0600'..'\u06FF' }
        if (arabic >= 2 && arabic >= letters.length / 2) return "ar"
        val hangul = countChars(text) { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }
        val kana = countChars(text) { it in '\u3040'..'\u30FF' }
        if (hangul > 0) return "ko"
        if (kana > 0) return "ja"
        if (forced in setOf("ja", "en", "ko", "zh")) return forced
        if (engineHint in setOf("ja", "en", "ko", "zh") && engineHint != "und") {
            if (engineHint == "ja" && countChars(text) { it in '\u4E00'..'\u9FFF' } > 0) return "ja"
            if (engineHint == "ko" || engineHint == "zh") return engineHint
            if (engineHint == "en" && countChars(text) { it in '\u4E00'..'\u9FFF' } == 0) return "en"
        }
        val identified = runCatching { Tasks.await(languageIdentifier.identifyLanguage(text.take(500))) }.getOrDefault("und")
        val normalized = identified.lowercase().substringBefore('-')
        if (normalized in setOf("ja", "en", "ko", "zh")) return normalized
        return if (countChars(text) { it in '\u4E00'..'\u9FFF' } >= 2) "zh" else "en"
    }

    private fun correctLikelyOcrText(value: String, language: String): String {
        var corrected = value.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
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

    private fun learnedTranslationFor(source: String): String? {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_LEARNED_TRANSLATIONS, "{}") ?: "{}"
        return runCatching { org.json.JSONObject(raw).optString(source.trim()).takeIf { it.isNotBlank() } }.getOrNull()
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
        val translator = translatorFor(languageCode)
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        return Tasks.await(translator.translate(sourceText))
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
        lastOcrTranslationLines = emptyList()
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
        if (preBubbleMerge.size < 3 || preBubbleChars < 30) {
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
        val candidates = mergeOcrCandidates(allCandidates)
        if (candidates.isEmpty()) {
            updateQualityCounts(0, 0, 0, 0, emptyList())
            onProgress(1f, "لم يكتشف OCR أي نص واضح")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }
        if (isLikelyAdPage(candidates)) {
            updateQualityCounts(candidates.size, 0, 0, candidates.size, emptyList())
            onProgress(1f, "تم تخطي صفحة يُرجّح أنها إعلان أو شاشة انتقال للفصل")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }

        onProgress(0.40f, "تحديد لغة كل كتلة نصية بصورة مستقلة")
        val blocks = ArrayList<BlockForTranslation>()
        val failedSamples = ArrayList<String>()
        var skippedCount = 0
        candidates.forEach { candidate ->
            val code = languageForBlock(candidate.text, candidate.engineLanguage, forcedLanguage)
            if (code == "ar") {
                skippedCount++ // Arabic-only blocks stay untouched; other languages on this page continue.
                return@forEach
            }
            val bounds = candidate.bounds
            if (bounds == null || bounds.width() < 3 || bounds.height() < 3) {
                skippedCount++ // No safe rendering position was returned by any OCR engine.
                if (candidate.text.isNotBlank()) failedSamples.add("بلا إحداثيات · ${candidate.text.take(90)}")
                return@forEach
            }
            blocks.add(BlockForTranslation(Rect(bounds), candidate.text, code))
        }
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
                val normalizedSource = correctLikelyOcrText(item.text, item.language)
                val learned = learnedTranslationFor(item.text) ?: learnedTranslationFor(normalizedSource)
                val sfx = if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_TRANSLATE_SFX, false)) {
                    dictionarySfxTranslation(item.text)
                } else null
                var translatedRaw: String? = learned ?: sfx
                if (translatedRaw == null) {
                    translatedRaw = runCatching { translateWithLanguage(normalizedSource, item.language) }.getOrNull()
                    val firstClean = translatedRaw?.let { cleanArabicTranslation(it) }.orEmpty()
                    if (firstClean.isBlank() || firstClean.equals(item.text, ignoreCase = true)) {
                        // If one source model failed or merely echoed OCR, retry this block with a language-ID suggestion and alternate script model.
                        for (retryCode in retryLanguageCandidates(normalizedSource, item.language)) {
                            val retried = runCatching { translateWithLanguage(normalizedSource, retryCode) }.getOrNull()
                            if (!retried.isNullOrBlank()) {
                                val cleanedRetry = cleanArabicTranslation(retried)
                                if (cleanedRetry.isNotBlank() && !cleanedRetry.equals(item.text, ignoreCase = true)) {
                                    translatedRaw = retried
                                    break
                                }
                            }
                        }
                    }
                }
                val translated = translatedRaw?.let { cleanArabicTranslation(it) }.orEmpty()
                if (translated.isBlank() || translated.equals(item.text, ignoreCase = true)) {
                    failedCount++
                    failedSamples.add(item.text.take(100))
                } else {
                    translatedBlocks.add(TranslationLine(Rect(item.bounds), translated, item.text, item.language))
                }
            } catch (_: Exception) {
                failedCount++
                failedSamples.add(item.text.take(100))
            }
            onProgress(0.45f + (0.48f * (index + 1) / blocks.size), "ترجمة الكتلة ${index + 1}/${blocks.size}")
        }
        updateQualityCounts(candidates.size, translatedBlocks.size, failedCount, skippedCount, failedSamples)
        if (translatedBlocks.isEmpty()) {
            onProgress(1f, "لم تنجح ترجمة أي كتلة؛ راجع تقرير الجودة")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }
        lastOcrTranslationLines = translatedBlocks.map {
            TranslationLine(Rect(it.bounds), it.translated, it.sourceText, it.language)
        }
        onProgress(0.98f, "إخراج النص العربي بجودة عالية")
        val rendered = drawTranslatedLines(source, translatedBlocks)
        onProgress(1f, "اكتملت الصورة · ${translatedBlocks.size} مترجمة · $failedCount فاشلة · $skippedCount متروكة")
        return rendered
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
            backgroundPaint.color = sampleBubbleColor(source, rect)
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val preferredSp = prefs.getInt(PREF_FONT_SIZE, 22).coerceIn(10, 72)
        val preferredPx = preferredSp * resources.displayMetrics.density
        var size = min((rect.height() * 0.88f).coerceAtLeast(10f), preferredPx)
        size = size.coerceIn(10f, 64f)
        var layout = buildArabicLayout(text, maxWidth, size)
        while (layout.height > rect.height() && size > 7f) {
            size -= 1f
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 22)
            textSize = size
            typeface = resolveArabicTypeface(prefs)
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = prefs.getInt(PREF_FONT_THICKNESS, 2).coerceIn(0, 10) / 10f
        }
        return android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
            .build()
    }

    private fun thirtyPx(): Float = 30f * resources.displayMetrics.density

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
                "Manga Fox Translator. اضغط لترجمة الجزء، واضغط مطولاً لترجمة الفصل"
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
                contentDescription = "Manga Fox Translator. اضغط لترجمة الجزء، واضغط مطولاً لترجمة الفصل"
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

    fun rebuildFloatingLenses() {
        if (!this::windowManager.isInitialized) return
        removeFloatingBubble()
        bubble = null
        bubbleParams = null
        bubbleWasAdded = false
        refreshLensAppFilter()
    }

    fun ensureFloatingBubble() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) refreshLensAppFilter()
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
            contentDescription = "Manga Fox Translator. اضغط لترجمة الجزء، واضغط مطولاً لترجمة الفصل"
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
                        if (event.eventTime - event.downTime > 550L) startFullChapterCapture()
                        else startSingleCapture()
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
