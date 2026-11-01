package com.manga.fox

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

// ============================================================
// [MAIN ACTIVITY]
// ============================================================
class MainActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startOverlayService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "تم إلغاء تصوير الشاشة", Toast.LENGTH_SHORT).show()
            }
        }

        buildUI()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#090D14"))
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "Manga FOX 🦊"
            setTextColor(Color.parseColor("#FFB800"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val desc = TextView(this).apply {
            text = "مترجم المانجا والمانهوا العائم.\nاضغط على الزر لتفعيل الفقاعة السحرية."
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 64)
        }

        val startBtn = Button(this).apply {
            text = "بدء الترجمة"
            setTextColor(Color.BLACK)
            textSize = 18f
            setBackgroundColor(Color.parseColor("#FFB800"))
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                    Toast.makeText(this@MainActivity, "الرجاء السماح بالعرض فوق التطبيقات للعودة", Toast.LENGTH_LONG).show()
                } else {
                    requestScreenCapture()
                }
            }
        }

        root.addView(title)
        root.addView(desc)
        root.addView(startBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140))
        setContentView(root)
    }

    private fun requestScreenCapture() {
        val intent = projectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
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
    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        resultData = intent?.getParcelableExtra("data")
        
        if (resultCode != 0 && resultData != null) {
            setupMediaProjection()
        }
        return START_STICKY
    }

    private fun setupMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
        
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaFoxCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun createBubble() {
        val size = dp(64)
        val bubble = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFB800"))
                setStroke(dp(4), Color.WHITE)
            }
            elevation = dp(12).toFloat()
            
            val icon = TextView(this@OverlayService).apply {
                text = "🦊"
                textSize = 28f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            }
            addView(icon)
            
            setOnClickListener { translateScreen() }
        }

        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 500
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) v.performClick()
                        return true
                    }
                }
                return false
            }
        })

        bubbleView = bubble
        windowManager.addView(bubbleView, params)
    }

    private fun translateScreen() {
        if (mediaProjection == null) {
            Toast.makeText(this, "خطأ: يرجى إعادة تشغيل التطبيق", Toast.LENGTH_SHORT).show()
            return
        }
        
        bubbleView?.visibility = View.GONE
        
        // تأخير بسيط لإخفاء الزر
        Handler(Looper.getMainLooper()).postDelayed({
            showTranslationOverlay()
        }, 300)
    }

    private fun showTranslationOverlay() {
        if (overlayView != null) return

        // بناء واجهة الترجمة (فقاعات حوار)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000")) // خلفية سوداء شفافة
            setOnClickListener { 
                windowManager.removeView(this)
                overlayView = null
                bubbleView?.visibility = View.VISIBLE
            }
        }

        // فقاعة 1
        val bubble1 = TextView(this).apply {
            text = "ماذا تفعل هنا؟!"
            setTextColor(Color.BLACK)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = createBubbleDrawable()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 200
            }
        }

        // فقاعة 2
        val bubble2 = TextView(this).apply {
            text = "لقد جاء دوري لأحميك!"
            setTextColor(Color.BLACK)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = createBubbleDrawable()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = 300
                leftMargin = 60
            }
        }

        val hint = TextView(this).apply {
            text = "Manga FOX · اضغط في أي مكان للإغلاق"
            setTextColor(Color.parseColor("#FFB800"))
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 100
            }
        }

        overlay.addView(bubble1)
        overlay.addView(bubble2)
        overlay.addView(hint)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlay, params)
        overlayView = overlay
    }

    private fun createBubbleDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                dp(24).toFloat(), dp(24).toFloat(),
                dp(24).toFloat(), dp(24).toFloat(),
                dp(24).toFloat(), dp(24).toFloat(),
                dp(0).toFloat(), dp(0).toFloat()
            )
            setColor(Color.WHITE)
            setStroke(dp(3), Color.BLACK)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "manga_fox_channel",
                "Manga FOX Translation",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "manga_fox_channel")
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Manga FOX يعمل")
            .setContentText("اضغط على الثعلب 🦊 لترجمة الشاشة")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        overlayView?.let { windowManager.removeView(it) }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}