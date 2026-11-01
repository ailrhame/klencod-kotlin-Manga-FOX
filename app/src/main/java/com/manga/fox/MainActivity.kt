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
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"))
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