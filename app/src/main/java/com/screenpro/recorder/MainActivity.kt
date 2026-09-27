
package com.screenpro.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var status: TextView
    private lateinit var timer: Chronometer
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var recordingsCount: TextView

    private var pendingStart = false
    private var recording = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingService.ACTION_STATE_CHANGED) {
                setRecording(
                    intent.getBooleanExtra(
                        RecordingService.EXTRA_IS_RECORDING,
                        false
                    )
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        recording = getSharedPreferences(
            "screenpro",
            MODE_PRIVATE
        ).getBoolean("recording", false)

        createUi()
        setRecording(recording)
        updateRecordingCount()
    }

    override fun onResume() {
        super.onResume()

        if (!receiverRegistered) {
            val filter = IntentFilter(RecordingService.ACTION_STATE_CHANGED)

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }

            receiverRegistered = true
        }

        setRecording(
            getSharedPreferences("screenpro", MODE_PRIVATE)
                .getBoolean("recording", recording)
        )

        updateRecordingCount()
    }

    override fun onPause() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        super.onPause()
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
        }

        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        scroll.addView(page)

        // TOP BAR
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = label("▶", 25, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFE52F45.toInt(), 17)
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }

        header.addView(logo)

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        brand.addView(label("ScreenPro", 23, 0xFF172033.toInt(), true))
        brand.addView(label("SCREEN RECORDER", 10, 0xFF7A8497.toInt(), true))

        header.addView(
            brand,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFF172033.toInt())
            background = rounded(Color.WHITE, 15)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showAppMenu() }
        }

        header.addView(menuButton)
        page.addView(header)

        // GREETING / INTRO
        val intro = label(
            "Your screen. Your story.",
            27,
            0xFF172033.toInt(),
            true
        )
        intro.setPadding(0, dp(28), 0, dp(6))
        page.addView(intro)

        page.addView(
            label(
                "Capture tutorials, gameplay, meetings and moments.",
                14,
                0xFF758095.toInt(),
                false
            )
        )

        // RECORDING STATUS WIDGET
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(0xFF172033.toInt(), 24)
            elevation = dp(4).toFloat()
        }

        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusHeader.addView(
            label("●  RECORDING STUDIO", 12, 0xFFBFC8D8.toInt(), true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val readyBadge = label("READY", 10, 0xFF198754.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(0xFFE3F5EB.toInt(), 30)
        }

        statusHeader.addView(readyBadge)
        statusCard.addView(statusHeader)

        timer = Chronometer(this).apply {
            text = "00:00"
            textSize = 43f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(4))
        }

        statusCard.addView(timer)

        status = label(
            "Ready when you are",
            13,
            0xFFBFC8D8.toInt(),
            false
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }

        statusCard.addView(status)

        startButton = Button(this).apply {
            text = "●   START RECORDING"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(0xFFE52F45.toInt(), 16)
            setOnClickListener {
                requestPermissionsAndCapture()
            }
        }

        statusCard.addView(
            startButton,
            LinearLayout.LayoutParams(-1, dp(58))
        )

        stopButton = Button(this).apply {
            text = "■   STOP RECORDING"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(0xFF344158.toInt(), 16)
            visibility = View.GONE
            setOnClickListener {
                startService(
                    Intent(
                        this@MainActivity,
                        RecordingService::class.java
                    ).setAction(RecordingService.ACTION_STOP)
                )

                Toast.makeText(
                    this@MainActivity,
                    "Stopping recording…",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        statusCard.addView(
            stopButton,
            LinearLayout.LayoutParams(-1, dp(58)).apply {
                topMargin = dp(10)
            }
        )

        page.addView(
            statusCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(25)
            }
        )

        // QUICK FEATURE WIDGETS
        page.addView(
            label("Quick features", 19, 0xFF172033.toInt(), true).apply {
                setPadding(0, dp(27), 0, dp(13))
            }
        )

        val featureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        featureRow.addView(
            featureCard("🎙", "Microphone", "Audio capture"),
            LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                rightMargin = dp(7)
            }
        )

        featureRow.addView(
            featureCard("▣", "HD Video", "Screen capture"),
            LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                leftMargin = dp(7)
            }
        )

        page.addView(featureRow)

        // RECORDINGS LIBRARY
        val libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(28), 0, dp(13))
        }

        libraryHeader.addView(
            label("My recordings", 19, 0xFF172033.toInt(), true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        libraryHeader.addView(
            label("VIEW ALL  ›", 12, 0xFFE52F45.toInt(), true).apply {
                setOnClickListener { showRecordings() }
            }
        )

        page.addView(libraryHeader)

        val libraryCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, 19)
            elevation = dp(2).toFloat()
            setOnClickListener { showRecordings() }
        }

        val libraryIcon = label("▶", 22, 0xFFE52F45.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFFFEDF0.toInt(), 15)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }

        libraryCard.addView(libraryIcon)

        val libraryText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }

        libraryText.addView(
            label("Recorded videos", 15, 0xFF172033.toInt(), true)
        )

        recordingsCount = label(
            "Browse your saved captures",
            12,
            0xFF7A8497.toInt(),
            false
        )

        libraryText.addView(recordingsCount)

        libraryCard.addView(
            libraryText,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        libraryCard.addView(
            label("›", 28, 0xFF7A8497.toInt(), false)
        )

        page.addView(libraryCard)

        page.addView(
            label(
                "Your videos are saved on this device.",
                12,
                0xFF8A94A6.toInt(),
                false
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(22), 0, 0)
            }
        )

        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun featureCard(
        icon: String,
        title: String,
        subtitle: String
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(12), dp(12))
            background = rounded(Color.WHITE, 18)
            elevation = dp(2).toFloat()
        }

        card.addView(label(icon, 24, 0xFFE52F45.toInt(), true))
        card.addView(
            label(title, 14, 0xFF172033.toInt(), true).apply {
                setPadding(0, dp(5), 0, dp(2))
            }
        )
        card.addView(label(subtitle, 11, 0xFF7A8497.toInt(), false))

        return card
    }

    private fun showAppMenu() {
        val options = arrayOf(
            "▶   My Recordings",
            "ⓘ   Recording Guide",
            "⚙   About ScreenPro"
        )

        AlertDialog.Builder(this)
            .setTitle("ScreenPro Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRecordings()
                    1 -> showRecordingGuide()
                    2 -> showAbout()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRecordingGuide() {
        AlertDialog.Builder(this)
            .setTitle("Recording Guide")
            .setMessage(
                "1. Tap Start Recording.\n\n" +
                "2. Allow microphone access if requested.\n\n" +
                "3. Confirm Android's screen-capture prompt.\n\n" +
                "4. Stop the recording when finished.\n\n" +
                "5. Open My Recordings to play saved videos."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("About ScreenPro")
            .setMessage(
                "ScreenPro Recorder\n\n" +
                "A simple screen recording app for capturing " +
                "your screen with microphone audio.\n\n" +
                "Version 1.0"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun updateRecordingCount() {
        if (!::recordingsCount.isInitialized) return

        val count = try {
            RecordingStore.list(this).size
        } catch (_: Exception) {
            0
        }

        recordingsCount.text =
            if (count == 1) "1 saved video"
            else "$count saved videos"
    }

    private fun setRecording(active: Boolean) {
        recording = active

        if (!::startButton.isInitialized) return

        startButton.visibility = if (active) View.GONE else View.VISIBLE
        stopButton.visibility = if (active) View.VISIBLE else View.GONE

        status.text = if (active) {
            "● Recording in progress"
        } else {
            "Ready when you are"
        }

        if (active) {
            timer.base = SystemClock.elapsedRealtime()
            timer.start()
        } else {
            timer.stop()
            timer.text = "00:00"
            updateRecordingCount()
        }
    }

    private fun requestPermissionsAndCapture() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1002
            )
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1003
            )
        }

        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            1001
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)

        if (requestCode == 1002) {
            if (
                results.isNotEmpty() &&
                results[0] == PackageManager.PERMISSION_GRANTED &&
                pendingStart
            ) {
                pendingStart = false
                requestPermissionsAndCapture()
            } else {
                pendingStart = false
                Toast.makeText(
                    this,
                    "Microphone permission is required for audio recording.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("Uses compatible activity result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != 1001) return

        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }

            Toast.makeText(
                this,
                "ScreenPro is starting…",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            status.text = "Screen capture permission was not granted."
        }
    }

    private fun showRecordings() {
        val items = RecordingStore.list(this)

        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No recordings yet")
                .setMessage(
                    "Your screen recordings will appear here after you stop a capture."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = items.mapIndexed { index, uri ->
            "Recording ${items.size - index}  •  " +
            (uri.lastPathSegment ?: "Video file")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Your recordings")
            .setItems(names) { _, which ->
                val play = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(items[which], "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                try {
                    startActivity(play)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "No video player is available.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun label(
        value: String,
        size: Int,
        color: Int,
        bold: Boolean
    ) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)

        if (bold) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
