
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
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var pendingStart = false
    private var recording = false

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
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter(
            RecordingService.ACTION_STATE_CHANGED
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        setRecording(
            getSharedPreferences("screenpro", MODE_PRIVATE)
                .getBoolean("recording", recording)
        )
    }

    override fun onPause() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }

        super.onPause()
    }

    private fun createUi() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F7FB.toInt())
        }

        val scroll = ScrollView(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
        }

        scroll.addView(page)

        // HEADER

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = label("▶", 27, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFE12F43.toInt(), 16)
            layoutParams = LinearLayout.LayoutParams(
                dp(54),
                dp(54)
            )
        }

        header.addView(logo)

        val branding = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        branding.addView(
            label("ScreenPro", 23, 0xFF182032.toInt(), true)
        )

        branding.addView(
            label("SCREEN RECORDER", 11, 0xFF778196.toInt(), true)
        )

        header.addView(
            branding,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(
            label("● READY", 11, 0xFF198754.toInt(), true).apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(0xFFE4F5EC.toInt(), 30)
            }
        )

        page.addView(header)

        // INTRODUCTION

        val heading = label(
            "Capture every moment.",
            27,
            0xFF182032.toInt(),
            true
        )

        heading.setPadding(0, dp(30), 0, dp(5))
        page.addView(heading)

        page.addView(
            label(
                "Record your screen with microphone audio.",
                14,
                0xFF778196.toInt(),
                false
            )
        )

        // RECORDING PANEL

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(25), dp(20), dp(24))
            background = rounded(Color.WHITE, 24)
            elevation = dp(3).toFloat()
        }

        panel.addView(
            label("▣", 46, 0xFFE12F43.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFFFFEFF1.toInt(), 22)

                layoutParams = LinearLayout.LayoutParams(
                    dp(92),
                    dp(92)
                ).apply {
                    bottomMargin = dp(17)
                }
            }
        )

        panel.addView(
            label(
                "00:00:00",
                34,
                0xFF182032.toInt(),
                true
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        status = label(
            "Ready when you are",
            14,
            0xFF778196.toInt(),
            false
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        }

        panel.addView(status)

        // START BUTTON

        startButton = Button(this).apply {
            text = "●   START RECORDING"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(0xFFE12F43.toInt(), 16)

            setOnClickListener {
                requestPermissionsAndCapture()
            }
        }

        panel.addView(
            startButton,
            LinearLayout.LayoutParams(-1, dp(58))
        )

        // STOP BUTTON

        stopButton = Button(this).apply {
            text = "■   STOP RECORDING"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(0xFF232D40.toInt(), 16)
            visibility = View.GONE

            setOnClickListener {
                startService(
                    Intent(
                        this@MainActivity,
                        RecordingService::class.java
                    ).setAction(RecordingService.ACTION_STOP)
                )

                setRecording(false)

                Toast.makeText(
                    this@MainActivity,
                    "Stopping recording…",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        panel.addView(
            stopButton,
            LinearLayout.LayoutParams(-1, dp(58)).apply {
                topMargin = dp(10)
            }
        )

        page.addView(
            panel,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(25)
            }
        )

        // RECORDINGS HEADER

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(28), 0, dp(12))
        }

        row.addView(
            label(
                "My recordings",
                20,
                0xFF182032.toInt(),
                true
            ),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        row.addView(
            label(
                "VIEW ALL  ›",
                12,
                0xFFE12F43.toInt(),
                true
            ).apply {
                setOnClickListener {
                    showRecordings()
                }
            }
        )

        page.addView(row)

        // VIDEO LIBRARY CARD

        val library = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, 18)

            setOnClickListener {
                showRecordings()
            }
        }

        library.addView(
            label("▶", 23, 0xFFE12F43.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFFFFEFF1.toInt(), 14)

                layoutParams = LinearLayout.LayoutParams(
                    dp(52),
                    dp(52)
                )
            }
        )

        val libraryCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }

        libraryCopy.addView(
            label(
                "Recorded videos",
                15,
                0xFF182032.toInt(),
                true
            )
        )

        libraryCopy.addView(
            label(
                "Browse and play your captures",
                12,
                0xFF778196.toInt(),
                false
            )
        )

        library.addView(
            libraryCopy,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        library.addView(
            label("›", 27, 0xFF778196.toInt(), false)
        )

        page.addView(library)

        page.addView(
            label(
                "Your recordings are saved on this device.",
                12,
                0xFF8792A5.toInt(),
                false
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(22), 0, 0)
            }
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, -1)
        )

        setContentView(root)
    }

    // UPDATE RECORDING INTERFACE

    private fun setRecording(active: Boolean) {

        recording = active

        if (!::startButton.isInitialized) return

        startButton.visibility =
            if (active) View.GONE else View.VISIBLE

        stopButton.visibility =
            if (active) View.VISIBLE else View.GONE

        status.text =
            if (active)
                "● Recording in progress — tap stop when finished"
            else
                "Ready when you are"
    }

    // PERMISSIONS AND SCREEN CAPTURE

    private fun requestPermissionsAndCapture() {

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
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
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
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
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            results
        )

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

            val service = Intent(
                this,
                RecordingService::class.java
            ).apply {
                action = RecordingService.ACTION_START

                putExtra(
                    RecordingService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    RecordingService.EXTRA_RESULT_DATA,
                    data
                )
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

    // RECORDINGS LIBRARY

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
            "Recording ${items.size - index}  •  ${uri.lastPathSegment ?: "Video file"}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Your recordings")
            .setItems(names) { _, which ->

                val play = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(items[which], "video/mp4")

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
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

    // UI HELPERS

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

    private fun rounded(
        color: Int,
        radius: Int
    ) = GradientDrawable().apply {

        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }
}
