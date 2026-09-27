package com.screenpro.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private val requestCapture = 1001
    private val requestMic = 1002
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 24)
            setBackgroundColor(0xFFF7F7FB.toInt())
        }
        val title = TextView(this).apply {
            text = "ScreenPro Recorder"
            textSize = 28f
            setTextColor(0xFF20212A.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Ready to record"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val record = Button(this).apply {
            text = "●  Start Recording"
            setOnClickListener { requestPermissionsAndCapture() }
        }
        val library = Button(this).apply {
            text = "My Recordings"
            setOnClickListener { showRecordings() }
        }
        val note = TextView(this).apply {
            text = "Records your screen with microphone audio. Android will ask you to approve each capture session."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 24, 8, 0)
        }
        page.addView(title)
        page.addView(status)
        page.addView(record)
        page.addView(library)
        page.addView(note)
        setContentView(page)
    }

    private fun requestPermissionsAndCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestMic)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), requestCapture)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == requestMic) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED && pendingStart) {
                pendingStart = false
                requestPermissionsAndCapture()
            } else {
                Toast.makeText(this, "Microphone permission is required for microphone audio.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Uses the compatible activity result callback for broad Android support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            status.text = "Recording started. Use the notification to stop."
            Toast.makeText(this, "ScreenPro is recording", Toast.LENGTH_SHORT).show()
        } else {
            status.text = "Screen capture permission was not granted."
        }
    }

    private fun showRecordings() {
        val items = RecordingStore.list(this)
        if (items.isEmpty()) {
            Toast.makeText(this, "No recordings found yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val chooser = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(items.first(), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(chooser) }
        catch (_: Exception) { Toast.makeText(this, "No video player is available.", Toast.LENGTH_LONG).show() }
    }
}
