package com.manga.fox

import android.accessibilityservice.AccessibilityService
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Switch
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
private const val PREF_LENS_ENABLED = "lens_enabled"
private const val MAX_CAPTURE_FRAMES = 120

object MangaFoxRuntime {
    @Volatile var status: String = "جاهز للترجمة"
    @Volatile var resultPages: List<String> = emptyList()
    @Volatile var sourcePages: List<String> = emptyList()
    @Volatile var resultTitle: String = "الفصل المترجم"
    @Volatile var lastError: String = ""
    @Volatile var progressPercent: Int = 0
    @Volatile var progressDetail: String = ""
}

class MainActivity : android.app.Activity() {
    private val dark = Color.rgb(10, 10, 13)
    private val panel = Color.rgb(25, 25, 31)
    private val gold = Color.rgb(255, 190, 65)
    private lateinit var statusText: TextView
    private lateinit var preferences: SharedPreferences
    private var fontPreview: TextView? = null
    private var fontStatusText: TextView? = null
    private var readingFullscreen = false
    private var pendingPdfFile: File? = null
    private val fontDownloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applySystemBars()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        }
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
        intro.addView(text("مهم: افتح الفصل في تطبيق قراءة المانجا أولاً، ثم ارجع إلى Manga Fox واضغط زر الترجمة. سيُخفى Manga Fox تلقائياً ليظهر قارئ المانجا قبل بدء التصوير.", 13f, Color.rgb(255, 205, 120)))
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
            if (service == null) {
                toast("فعّل خدمة Manga Fox أولاً")
            } else {
                service.startSingleCapture(900L)
                window.decorView.postDelayed({ moveTaskToBack(true) }, 120L)
            }
        })
        actions.addView(actionButton("ترجمة الفصل العمودي كاملاً", true) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) {
                toast("فعّل خدمة Manga Fox أولاً، ثم افتح فصل المانجا")
            } else {
                service.startFullChapterCapture(1300L)
                // Move Manga Fox out of the foreground before the first screenshot.
                // The previously opened manga reader becomes the active display.
                window.decorView.postDelayed({ moveTaskToBack(true) }, 120L)
            }
        })
        actions.addView(actionButton("إيقاف الالتقاط الحالي", false) {
            val service = MangaFoxAccessibilityService.instance
            if (service == null) toast("خدمة Manga Fox غير متصلة") else service.stopCaptureByUser()
        })
        actions.addView(actionButton("عرض آخر نتيجة مترجمة", false) { showResults() })
        root.addView(actions, matchWrap(dp(16)))

        addSettingsCard(root)

        val statusCard = card()
        statusCard.addView(text("الحالة", 16f, Color.WHITE, true))
        statusText = text("", 14f, Color.LTGRAY)
        statusCard.addView(statusText, matchWrap())
        root.addView(statusCard, matchWrap(dp(16)))

        root.addView(text("ملاحظة: الالتقاط الكامل يحتاج Android 11 أو أحدث. يجب أن يكون قارئ المانجا هو التطبيق المفتوح قبل الضغط على زر الترجمة. قد تمنع بعض تطبيقات القراءة تصوير الشاشة. النسخة الأولية تترجم مناطق النص المكتشفة وقد لا تمسح الكتابة من الخلفيات المعقدة بالكامل.", 12f, Color.GRAY).apply {
            setPadding(dp(4), dp(6), dp(4), dp(14))
        }, matchWrap())
        root.addView(text("MANGA FOX  ·  N5CR4", 12f, gold).apply { gravity = Gravity.CENTER }, matchWrap())
        root.addView(text("حقوقي · N5CR4  ↗", 13f, gold, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/N5CR4")))
                }.onFailure { toast("تعذر فتح تيليجرام") }
            }
        }, matchWrap())

        scroll.addView(root)
        setContentView(scroll)
        refreshStatus()
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
        val familyButton = actionButton("خط النظام: $familyName · تغيير", false) { }
        familyButton.setOnClickListener {
            val current = preferences.getString(PREF_FONT_FAMILY, "sans-serif")
            val currentIndex = fontFamilies.indexOfFirst { it.first == current }.let { if (it < 0) 0 else it }
            val next = fontFamilies[(currentIndex + 1) % fontFamilies.size]
            preferences.edit().remove(PREF_FONT_FILE).remove(PREF_FONT_LABEL)
                .putString(PREF_FONT_FAMILY, next.first).apply()
            familyButton.text = "خط النظام: ${next.second} · تغيير"
            updateFontPreview()
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
            downloadArabicFont("Amiri", "Amiri-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/amiri/Amiri%5Bwght%5D.ttf")
        }, matchWrap(dp(4)))
        settingsCard.addView(actionButton("تنزيل Tajawal · تجوال", false) {
            downloadArabicFont("Tajawal", "Tajawal-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal/Tajawal%5Bwght%5D.ttf")
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
        settingsCard.addView(text("فعّل العدسة لتظهر فوق التطبيقات الأخرى، أو أوقفها من هنا متى شئت. تختفي العدسة تلقائياً عند فتح Manga FOX.", 11f, Color.LTGRAY), matchWrap())
        val lensSwitch = Switch(this).apply {
            text = "إظهار العدسة العائمة"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = preferences.getBoolean(PREF_LENS_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(PREF_LENS_ENABLED, checked).apply()
                MangaFoxAccessibilityService.instance?.setLensEnabled(checked)
                toast(if (checked) "تم تفعيل العدسة العائمة" else "تم إيقاف العدسة العائمة")
            }
        }
        settingsCard.addView(lensSwitch, matchWrap(dp(8)))
        updateFontPreview()
        root.addView(settingsCard, matchWrap(dp(16)))
    }

    private fun openLensStudio() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "رمز العدسة مثل 狐 أو ◉ أو ✦"
            setText(preferences.getString(PREF_LENS_GLYPH, "狐"))
        }
        val colors = arrayOf("ذهبي", "بنفسجي", "سماوي", "وردي", "أبيض")
        val values = intArrayOf(Color.rgb(255, 190, 65), Color.rgb(177, 107, 255), Color.rgb(90, 190, 255), Color.rgb(255, 107, 176), Color.WHITE)
        var selected = values.indices.minByOrNull { index -> kotlin.math.abs(values[index] - preferences.getInt(PREF_LENS_COLOR, values[0])) } ?: 0
        val colorButton = Button(this).apply {
            isAllCaps = false
            text = "لون العدسة: ${colors[selected]} · تغيير"
            setOnClickListener {
                selected = (selected + 1) % colors.size
                text = "لون العدسة: ${colors[selected]} · تغيير"
                background = rounded(values[selected], 12)
                setTextColor(if (selected == 4) Color.BLACK else Color.WHITE)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(input, matchWrap(dp(8)))
            addView(colorButton, matchWrap())
        }
        colorButton.background = rounded(values[selected], 12)
        colorButton.setTextColor(if (selected == 4) Color.BLACK else Color.WHITE)
        AlertDialog.Builder(this)
            .setTitle("استوديو تصميم العدسة")
            .setMessage("اختر رمزاً ولوناً مخصصاً للعدسة الأساسية.")
            .setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ التصميم") { _, _ ->
                val glyph = input.text.toString().trim().take(3).ifBlank { "狐" }
                preferences.edit().putString(PREF_LENS_GLYPH, glyph).putInt(PREF_LENS_COLOR, values[selected]).apply()
                MangaFoxAccessibilityService.instance?.rebuildFloatingLenses()
                toast("تم حفظ تصميم العدسة")
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
                Typeface.createFromFile(temp)
                if (target.exists()) target.delete()
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
        root.addView(actionButton("إعادة تطبيق الخط والحجم المختار على هذه الترجمة", true) {
            val service = MangaFoxAccessibilityService.instance
            if (MangaFoxRuntime.sourcePages.isEmpty()) {
                toast("لا توجد صور أصلية محفوظة لهذه النتيجة. ترجم الفصل مجدداً بعد تحديث النسخة.")
            } else if (service == null) {
                toast("فعّل خدمة Manga Fox لإعادة تنسيق الترجمة")
            } else {
                service.retranslateLastResults()
                toast("بدأت إعادة تنسيق الصور بالخط والحجم الحاليين")
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
                val image = ZoomableImageView(this).apply {
                    setImageFile(filePath)
                    contentDescription = "الجزء المترجم ${index + 1} — كبّر بإصبعين"
                    setBackgroundColor(dark)
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
            }
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateVisiblePageImages(pageViews) }
            scroll.post { updateVisiblePageImages(pageViews) }
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun updateVisiblePageImages(pages: List<ZoomableImageView>) {
        val visibleBounds = android.graphics.Rect()
        pages.forEach { page ->
            if (page.getLocalVisibleRect(visibleBounds)) page.loadPage() else page.releasePage()
        }
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        MangaFoxRuntime.status = "خدمة العدسة متصلة"
        refreshLensAppFilter()
        // If Android/OEM stopped the service during a chapter capture, keep the
        // JPEG segments and recover them when the accessibility service returns.
        mainHandler.postDelayed({ recoverInterruptedCapture() }, 900L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        currentForegroundPackage = pkg
        refreshLensAppFilter()
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
                if (!segment.compress(Bitmap.CompressFormat.JPEG, 91, stream)) {
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
        MangaFoxRuntime.status = "بدء استخراج النصوص وترجمتها..."
        updateTranslationLens(0, "تهيئة الترجمة")
        if (note.isNotBlank()) MangaFoxRuntime.lastError = note

        try {
            worker.execute {
                val translatedFiles = ArrayList<String>()
                val newSourceFiles = ArrayList<String>()
                try {
                    workPages.forEachIndexed { index, file ->
                        MangaFoxRuntime.status = "تحليل وترجمة المقطع ${index + 1} من ${workPages.size}..."
                        updateTranslationLens((index * 100 / workPages.size).coerceIn(0, 99), "صورة ${index + 1} من ${workPages.size}")
                        val page = BitmapFactory.decodeFile(file.absolutePath)
                            ?: throw IllegalStateException("تعذر قراءة المقطع ${index + 1}")
                        var translated: Bitmap? = null
                        try {
                            if (!preserveInputs) {
                                val sourceCopy = File(filesDir, "mfox_source_${System.currentTimeMillis()}_${index}.jpg")
                                file.inputStream().use { input -> sourceCopy.outputStream().use { output -> input.copyTo(output) } }
                                newSourceFiles.add(sourceCopy.absolutePath)
                            }
                            val outputBitmap = translateBitmap(page) { fraction, detail ->
                                val aggregate = (((index + fraction.coerceIn(0f, 1f)) / workPages.size) * 100f).toInt().coerceIn(0, 99)
                                updateTranslationLens(aggregate, "${index + 1}/${workPages.size} · $detail")
                            }
                            translated = outputBitmap
                            val outputFile = File(cacheDir, "mfox_result_${System.currentTimeMillis()}_$index.jpg")
                            FileOutputStream(outputFile).use { stream ->
                                if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                                    throw IllegalStateException("تعذر حفظ نتيجة المقطع ${index + 1}")
                                }
                            }
                            translatedFiles.add(outputFile.absolutePath)
                            updateTranslationLens((((index + 1).toFloat() / workPages.size) * 100f).toInt().coerceIn(0, 99), "اكتملت الصورة ${index + 1} من ${workPages.size}")
                        } finally {
                            if (!page.isRecycled) page.recycle()
                            translated?.let { if (!it.isRecycled) it.recycle() }
                            if (!preserveInputs) runCatching { file.delete() }
                        }
                    }
                    val oldFiles = MangaFoxRuntime.resultPages
                    MangaFoxRuntime.resultPages = translatedFiles
                    oldFiles.forEach { runCatching { File(it).delete() } }
                    if (!preserveInputs) {
                        val oldSources = MangaFoxRuntime.sourcePages
                        MangaFoxRuntime.sourcePages = newSourceFiles.toList()
                        oldSources.forEach { runCatching { File(it).delete() } }
                    }
                    MangaFoxRuntime.resultTitle = title
                    MangaFoxRuntime.status = "اكتملت الترجمة: ${translatedFiles.size} مقطع"
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

    private data class TranslationLine(val bounds: Rect, val translated: String)

    private fun translateBitmap(source: Bitmap, onProgress: (Float, String) -> Unit = { _, _ -> }): Bitmap {
        val input = InputImage.fromBitmap(source, 0)
        onProgress(0.04f, "فحص الصورة")
        var best: Text? = runCatching { Tasks.await(latinRecognizer.process(input)) }.getOrNull()
        onProgress(0.18f, "التعرف على النصوص")
        fun hasEnoughReadableText(result: Text?): Boolean {
            val value = result?.text.orEmpty()
            return value.length >= 24 && value.count { it.isLetter() } >= 16
        }
        // Most English pages need only the Latin engine. Create/run the heavier
        // CJK engines sequentially, and only when the first pass found little text.
        if (!hasEnoughReadableText(best)) {
            val candidate = runCatching { Tasks.await(japaneseRecognizer.process(input)) }.getOrNull()
            if (candidate != null && candidate.text.length > (best?.text?.length ?: 0)) best = candidate
            onProgress(0.25f, "فحص النص الياباني")
        }
        if (!hasEnoughReadableText(best)) {
            val candidate = runCatching { Tasks.await(koreanRecognizer.process(input)) }.getOrNull()
            if (candidate != null && candidate.text.length > (best?.text?.length ?: 0)) best = candidate
            onProgress(0.30f, "فحص النص الكوري")
        }
        if (!hasEnoughReadableText(best)) {
            val candidate = runCatching { Tasks.await(chineseRecognizer.process(input)) }.getOrNull()
            if (candidate != null && candidate.text.length > (best?.text?.length ?: 0)) best = candidate
            onProgress(0.34f, "فحص النص الصيني")
        }
        val lines = best?.textBlocks?.flatMap { it.lines }?.filter { it.text.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) {
            onProgress(1f, "لا يوجد نص قابل للترجمة")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }
        onProgress(0.40f, "اكتشاف لغة النص")

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
            onProgress(0.48f, "تجهيز نموذج الترجمة")
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            val translatableLines = lines.filter { it.boundingBox != null }
            translatableLines.forEachIndexed { lineIndex, line ->
                val bounds = line.boundingBox
                if (bounds != null) {
                    val translated = runCatching { Tasks.await(translator.translate(line.text)) }.getOrDefault(line.text)
                    translatedLines.add(TranslationLine(Rect(bounds), translated))
                }
                val fraction = if (translatableLines.isEmpty()) 0.95f else 0.52f + (0.45f * (lineIndex + 1) / translatableLines.size)
                onProgress(fraction, "ترجمة النص ${lineIndex + 1}/${translatableLines.size}")
            }
        } finally {
            translator.close()
        }
        if (translatedLines.isEmpty()) {
            onProgress(1f, "اكتملت الصورة")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("تعذر نسخ الصورة للمعالجة")
        }
        onProgress(0.98f, "تنسيق النص العربي")
        val rendered = drawTranslatedLines(source, translatedLines)
        onProgress(1f, "اكتملت الصورة")
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
            .setMaxLines(4)
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

    fun setLensEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_LENS_ENABLED, enabled).apply()
        refreshLensAppFilter()
    }

    fun refreshLensAppFilter() {
        if (!this::windowManager.isInitialized) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_LENS_ENABLED, true)
        val foreground = currentForegroundPackage
        val shouldShow = enabled && !foreground.isNullOrBlank() && foreground != packageName
        if (shouldShow) {
            if (bubble == null) showFloatingBubble() else if (!bubbleWasAdded) restoreFloatingBubble()
        } else {
            removeFloatingBubble()
        }
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
        }.onFailure {
            bubble = null
            bubbleParams = null
            bubbleWasAdded = false
        }
    }

    private fun removeFloatingBubble() {
        val view = bubble
        if (view != null && bubbleWasAdded) runCatching { windowManager.removeView(view) }
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
