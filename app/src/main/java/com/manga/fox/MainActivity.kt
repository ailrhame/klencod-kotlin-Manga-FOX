package com.manga.fox

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

enum class SrcLang(val label: String, val translateCode: String) {
    JAPANESE("مانجا يابانية", TranslateLanguage.JAPANESE),
    KOREAN("مانهوا كورية", TranslateLanguage.KOREAN),
    CHINESE("مانهوا صينية", TranslateLanguage.CHINESE),
    ENGLISH("كوميك إنجليزية", TranslateLanguage.ENGLISH)
}

// ============================================================
// [MAIN ACTIVITY]
// ============================================================
class MainActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var selectedLang = SrcLang.JAPANESE
    private val REQ_CODE = 9001
    private val REQ_NOTIFY = 9002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        
        buildUI()
    }

    private fun buildUI() {
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#090D14"))
            setPadding(48, 96, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "🦊 Manga FOX"
            setTextColor(Color.parseColor("#FFB800"))
            textSize = 32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "مترجم مانجا ومانهوا مباشر\nالتقط الشاشة وترجم النص فوراً"
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 48)
        })

        root.addView(TextView(this).apply {
            text = "اختر لغة المانجا:"
            setTextColor(Color.parseColor("#FFB800"))
            textSize = 16f
            setPadding(0, 24, 0, 16)
        })

        val langGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for ((index, lang) in SrcLang.values().withIndex()) {
            langGroup.addView(RadioButton(this).apply {
                id = index
                text = lang.label
                setTextColor(Color.WHITE)
                textSize = 18f
                isChecked = index == 0
                setPadding(0, 16, 0, 16)
            })
        }
        langGroup.setOnCheckedChangeListener { _, id -> selectedLang = SrcLang.values()[id] }
        root.addView(langGroup)

        root.addView(Button(this).apply {
            text = "بدء الترجمة العائمة"
            setTextColor(Color.BLACK)
            textSize = 18f
            setBackgroundColor(Color.parseColor("#FFB800"))
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                    Toast.makeText(this@MainActivity, "الرجاء السماح بالعرض فوق التطبيقات", Toast.LENGTH_LONG).show()
                } else {
                    val intent = projectionManager.createScreenCaptureIntent()
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_CODE)
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140).apply { topMargin = 48 })

        root.addView(TextView(this).apply {
            text = "عند أول استخدام، سيتم تحميل نموذج الترجمة (حوالي 30 ميجا، مرة واحدة فقط)."
            setTextColor(Color.GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                startOverlayService(resultCode, data)
            } else {
                Toast.makeText(this, "تم إلغاء التصوير", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("lang", selectedLang.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        finish()
    }
}

// ============================================================
// [OVERLAY SERVICE]
// ============================================================
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var overlayView: View? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var resultCode = 0
    private var resultData: Intent? = null
    private var srcLang = SrcLang.JAPANESE
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0
    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        resultData = intent?.getParcelableExtra("data")
        val langName = intent?.getStringExtra("lang") ?: SrcLang.JAPANESE.name
        srcLang = try { SrcLang.valueOf(langName) } catch (e: Exception) { SrcLang.JAPANESE }
        
        if (resultCode != 0 && resultData != null && mediaProjection == null) {
            setupProjection()
            createBubble()
        }
        return START_STICKY
    }

    private fun setupProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
        
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi = metrics.densityDpi
        
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaFox", screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun createBubble() {
        if (bubbleView != null) return
        val size = dp(64)
        val bubble = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFB800"))
                setStroke(dp(3), Color.WHITE)
            }
            elevation = dp(12).toFloat()
            
            addView(TextView(this@OverlayService).apply {
                text = "🦊"
                textSize = 28f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            })
            setOnClickListener { captureAndTranslate() }
        }

        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 300
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var isDragging = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        isDragging = false; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                        params.x = initialX + dx; params.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, params); return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) v.performClick(); return true
                    }
                }
                return false
            }
        })

        bubbleView = bubble
        windowManager.addView(bubbleView, params)
    }

    private fun captureAndTranslate() {
        if (isProcessing) return
        if (mediaProjection == null) { toast("خطأ: يرجى إعادة التشغيل"); return }
        isProcessing = true
        
        bubbleView?.visibility = View.GONE
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {}; overlayView = null }
        
        handler.postDelayed({
            val bitmap = captureScreen()
            if (bitmap == null) {
                isProcessing = false; bubbleView?.visibility = View.VISIBLE
                toast("فشل التقاط الشاشة"); return@postDelayed
            }
            processImage(bitmap)
        }, 300)
    }

    private fun captureScreen(): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            
            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            
            if (rowPadding > 0) Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight) else bmp
        } catch (e: Exception) { null }
    }

    private fun processImage(bitmap: Bitmap) {
        toast("جاري الترجمة... (قد يستغرق وقتاً)")
        
        Thread {
            try {
                val recognizer = createRecognizer()
                val image = InputImage.fromBitmap(bitmap, 0)
                val textResult = Tasks.await(recognizer.process(image))
                recognizer.close()
                
                if (textResult.textBlocks.isEmpty()) {
                    handler.post { toast("لم يتم العثور على نص"); isProcessing = false; bubbleView?.visibility = View.VISIBLE }
                    return@Thread
                }
                
                val translator = createTranslator()
                Tasks.await(translator.downloadModelIfNeeded())
                
                val translations = mutableListOf<Pair<Rect, String>>()
                for (block in textResult.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val text = block.text.trim()
                    if (text.isEmpty()) continue
                    try {
                        val translated = Tasks.await(translator.translate(text))
                        if (translated.isNotBlank() && translated != text) {
                            translations.add(Pair(box, translated))
                        }
                    } catch (e: Exception) {}
                }
                translator.close()
                
                handler.post {
                    if (translations.isEmpty()) {
                        toast("لم يتم ترجمة أي نص")
                        isProcessing = false; bubbleView?.visibility = View.VISIBLE
                    } else showTranslations(translations)
                }
            } catch (e: Exception) {
                handler.post {
                    toast("خطأ: ${e.message}")
                    isProcessing = false; bubbleView?.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun createRecognizer(): TextRecognizer {
        return when (srcLang) {
            SrcLang.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            SrcLang.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            SrcLang.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            SrcLang.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun createTranslator(): Translator {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang.translateCode)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        return Translation.getClient(options)
    }

    private fun showTranslations(translations: List<Pair<Rect, String>>) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener {
                try { windowManager.removeView(this) } catch (e: Exception) {}
                overlayView = null; isProcessing = false; bubbleView?.visibility = View.VISIBLE
            }
        }

        for ((box, text) in translations) {
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(Color.BLACK)
                textSize = 14f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#F5F5F5"))
                    setStroke(dp(2), Color.BLACK)
                }
                maxWidth = (box.width() * 3).coerceAtLeast(dp(120))
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = box.left.coerceAtLeast(0)
                topMargin = box.top.coerceAtLeast(0)
            }
            overlay.addView(tv, params)
        }

        val hint = TextView(this).apply {
            text = "🦊 اضغط في أي مكان للإغلاق"
            setTextColor(Color.parseColor("#FFB800"))
            textSize = 14f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#CC000000"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(80)
            }
        }
        overlay.addView(hint)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        overlayView = overlay
        bubbleView?.visibility = View.GONE
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("manga_fox_channel", "Manga FOX", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "manga_fox_channel")
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Manga FOX 🦊")
            .setContentText("اضغط على الثعلب لترجمة الشاشة")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { bubbleView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        try { overlayView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}