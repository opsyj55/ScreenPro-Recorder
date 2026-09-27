package com.screenpro.recorder

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.screenpro.recorder.START"
        const val ACTION_STOP = "com.screenpro.recorder.STOP"
        const val ACTION_STATE_CHANGED =
            "com.screenpro.recorder.ACTION_STATE_CHANGED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_IS_PAUSED = "is_paused"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenpro_recording"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    private var isRecording = false
    private var isPaused = false
    private var isStopping = false
    private var foregroundStarted = false

    private var windowManager: WindowManager? = null
    private var floatingWidget: View? = null
    private var pauseButton: Button? = null
    private var widgetParams: WindowManager.LayoutParams? = null

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWidgetX = 0
    private var initialWidgetY = 0

    private lateinit var projectionManager: MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            finishRecording()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_START -> handleStart(intent)

            ACTION_STOP -> {
                finishRecording()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isRecording || isStopping) return

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            sendRecordingState(false)
            stopSelf()
            return
        }

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Microphone permission is only required when mic audio is enabled.
            val micEnabled = getSharedPreferences(
                "screenpro",
                MODE_PRIVATE
            ).getBoolean("microphone_enabled", true)

            if (micEnabled) {
                sendRecordingState(false)
                stopSelf()
                return
            }
        }

        try {
            startRecordingForeground()
            startRecording(resultCode, resultData)
        } catch (e: Exception) {
            e.printStackTrace()
            finishRecording()
            stopSelf()
        }
    }

    private fun startRecordingForeground() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val micEnabled = getSharedPreferences(
                    "screenpro",
                    MODE_PRIVATE
                ).getBoolean("microphone_enabled", true)

                if (micEnabled) {
                    serviceType =
                        serviceType or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }

            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        foregroundStarted = true
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent
    ) {
        isStopping = false
        isPaused = false

        val prefs = getSharedPreferences(
            "screenpro",
            MODE_PRIVATE
        )

        val micEnabled = prefs.getBoolean(
            "microphone_enabled",
            true
        )

        val quality = prefs.getString(
            "video_quality",
            "HD"
        ) ?: "HD"

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        val dimensions = chooseVideoSize(
            screenWidth,
            screenHeight,
            quality
        )

        val width = dimensions.first
        val height = dimensions.second

        val filename = "ScreenPro_" +
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date()) + ".mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "Movies/ScreenPro"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        outputUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw IllegalStateException("Could not create video file")

        outputDescriptor =
            contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: throw IllegalStateException("Could not open video file")

        mediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

        mediaRecorder?.apply {
            if (micEnabled) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }

            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (micEnabled) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
            }

            setVideoSize(width, height)
            setVideoFrameRate(30)

            val videoBitrate = when (quality) {
                "Standard" -> 3_000_000
                "Maximum" -> 8_000_000
                else -> 5_000_000
            }

            setVideoEncodingBitRate(videoBitrate)
            setOutputFile(outputDescriptor!!.fileDescriptor)
            prepare()
        }

        mediaProjection = projectionManager.getMediaProjection(
            resultCode,
            resultData
        ) ?: throw IllegalStateException("Could not start screen capture")

        mediaProjection?.registerCallback(
            projectionCallback,
            mainHandler
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenProRecorder",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null,
            mainHandler
        ) ?: throw IllegalStateException("Could not create virtual display")

        mediaRecorder?.start()

        isRecording = true
        isPaused = false

        showFloatingWidget()
        sendRecordingState(true)
    }

    private fun chooseVideoSize(
        screenWidth: Int,
        screenHeight: Int,
        quality: String
    ): Pair<Int, Int> {

        val longer = maxOf(screenWidth, screenHeight).toFloat()
        val shorter = minOf(screenWidth, screenHeight).toFloat()

        val targetLongSide = when (quality) {
            "Standard" -> 1280f
            "HD" -> 1920f
            else -> longer
        }

        val scale = minOf(1f, targetLongSide / longer)

        var width = (screenWidth * scale).toInt()
        var height = (screenHeight * scale).toInt()

        // MediaRecorder requires even dimensions for common H.264 encoders.
        width = (width / 2) * 2
        height = (height / 2) * 2

        if (width < 2 || height < 2) {
            width = screenWidth
            height = screenHeight
        }

        return Pair(width, height)
    }

    private fun sendRecordingState(recording: Boolean) {
        val stateIntent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RECORDING, recording)
            putExtra(EXTRA_IS_PAUSED, isPaused)
        }

        sendBroadcast(stateIntent)
    }

    // FLOATING WIDGET

    private fun showFloatingWidget() {
        if (!isRecording || floatingWidget != null) return

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            Toast.makeText(
                this,
                "Allow ScreenPro to display over other apps for floating controls.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = rounded(0xEE172033.toInt(), 18)
                elevation = dp(8).toFloat()
            }

            val dragHandle = TextView(this).apply {
                text = "⋮⋮"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }

            panel.addView(dragHandle)

            pauseButton = Button(this).apply {
                text = "Ⅱ"
                textSize = 19f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(0xFF344158.toInt(), 12)
                setOnClickListener {
                    togglePause()
                }
            }

            panel.addView(
                pauseButton,
                LinearLayout.LayoutParams(dp(54), dp(48)).apply {
                    bottomMargin = dp(7)
                }
            )

            val stopButton = Button(this).apply {
                text = "■"
                textSize = 19f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(0xFFE52F45.toInt(), 12)
                setOnClickListener {
                    finishRecording()
                    stopSelf()
                }
            }

            panel.addView(
                stopButton,
                LinearLayout.LayoutParams(dp(54), dp(48))
            )

            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = dp(12)
                y = 0
            }

            val dragListener = View.OnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        initialWidgetX = params.x
                        initialWidgetY = params.y
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialWidgetX -
                            (event.rawX - initialTouchX).toInt()

                        params.y = initialWidgetY +
                            (event.rawY - initialTouchY).toInt()

                        try {
                            windowManager?.updateViewLayout(panel, params)
                        } catch (_: Exception) {
                        }

                        true
                    }

                    else -> false
                }
            }

            dragHandle.setOnTouchListener(dragListener)

            windowManager?.addView(panel, params)

            floatingWidget = panel
            widgetParams = params

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun togglePause() {
        val recorder = mediaRecorder ?: return

        if (!isRecording || isStopping) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (isPaused) {
                    recorder.resume()
                    isPaused = false
                    pauseButton?.text = "Ⅱ"
                    Toast.makeText(
                        this,
                        "Recording resumed",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    recorder.pause()
                    isPaused = true
                    pauseButton?.text = "▶"
                    Toast.makeText(
                        this,
                        "Recording paused",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                sendRecordingState(true)
            } else {
                Toast.makeText(
                    this,
                    "Pause is not supported on this Android version.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                this,
                "Unable to change recording state.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun removeFloatingWidget() {
        val widget = floatingWidget ?: return

        try {
            windowManager?.removeView(widget)
        } catch (_: Exception) {
        }

        floatingWidget = null
        pauseButton = null
        widgetParams = null
    }

    private fun finishRecording() {
        if (isStopping) return

        isStopping = true

        val wasRecording = isRecording
        var successfullySaved = false

        if (wasRecording) {
            try {
                mediaRecorder?.stop()
                successfullySaved = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        isRecording = false
        isPaused = false

        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        val projection = mediaProjection
        mediaProjection = null

        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        try {
            outputDescriptor?.close()
        } catch (_: Exception) {
        }

        outputDescriptor = null

        outputUri?.let { uri ->
            try {
                if (successfullySaved) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }

                        contentResolver.update(uri, values, null, null)
                    }
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        outputUri = null

        removeFloatingWidget()
        sendRecordingState(false)
        removeForegroundNotification()
    }

    private fun removeForegroundNotification() {
        if (!foregroundStarted) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        foregroundStarted = false
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenPro Recorder")
            .setContentText("Your screen is being recorded")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Recording",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScreenPro Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen recording controls"
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    override fun onDestroy() {
        finishRecording()
        removeFloatingWidget()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
