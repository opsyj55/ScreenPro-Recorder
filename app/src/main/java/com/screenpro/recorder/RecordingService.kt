package com.screenpro.recorder

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.os.SystemClock
import android.provider.MediaStore
import android.service.quicksettings.TileService
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

        const val ACTION_TOGGLE_PAUSE = "com.screenpro.recorder.TOGGLE_PAUSE"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenpro_recording"

        // Live state so the UI, tile and notification can always sync up.
        @Volatile var isActive = false
        @Volatile var isPausedNow = false
        @Volatile var isCountingDown = false

        private var startedAt = 0L
        private var pausedAt = 0L
        private var pausedTotal = 0L

        fun elapsedMs(): Long {
            if (!isActive) return 0L
            val ref = if (isPausedNow) pausedAt else SystemClock.elapsedRealtime()
            return (ref - startedAt - pausedTotal).coerceAtLeast(0L)
        }

        fun formatElapsed(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val sec = total % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
            } else {
                String.format(Locale.US, "%02d:%02d", m, sec)
            }
        }
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
    private var floatingWidget: LinearLayout? = null
    private var pauseButton: Button? = null
    private var widgetParams: WindowManager.LayoutParams? = null
    private var widgetCollapsed = false

    private var timerText: TextView? = null
    private var dotView: TextView? = null
    private var dotAnimator: ObjectAnimator? = null

    private var countdownView: TextView? = null
    private var countdownRunnable: Runnable? = null

    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var firstShakeAt = 0L
    private var lastShakeAt = 0L
    private var shakeCount = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            timerText?.text = formatElapsed(elapsedMs())
            if (isRecording) mainHandler.postDelayed(this, 500)
        }
    }

    private val collapseRunnable = Runnable {
        if (isRecording && !widgetCollapsed) collapseWidget()
    }

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

            ACTION_TOGGLE_PAUSE -> togglePause()
        }

        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isRecording || isStopping || isCountingDown) return

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

        val micEnabled = getSharedPreferences(
            "screenpro",
            MODE_PRIVATE
        ).getBoolean("microphone_enabled", true)

        if (
            micEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            sendRecordingState(false)
            stopSelf()
            return
        }

        try {
            startRecordingForeground()
            beginWithCountdown(resultCode, resultData)
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

        val metrics = android.util.DisplayMetrics()

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

        startedAt = SystemClock.elapsedRealtime()
        pausedAt = 0L
        pausedTotal = 0L
        isPausedNow = false
        isActive = true

        updateNotification()
        startShakeDetection()
        showFloatingWidget()
        sendRecordingState(true)
    }

    private fun chooseVideoSize(
        screenWidth: Int,
        screenHeight: Int,
        quality: String
    ): Pair<Int, Int> {
        val longer = maxOf(
            screenWidth,
            screenHeight
        ).toFloat()

        val targetLongSide = when (quality) {
            "Standard" -> 1280f
            "HD" -> 1920f
            else -> longer
        }

        val scale = minOf(1f, targetLongSide / longer)

        var width = (screenWidth * scale).toInt()
        var height = (screenHeight * scale).toInt()

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(
                    this,
                    ComponentName(this, RecordingTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }

    // COUNTDOWN, SHAKE, DOT, NOTIFICATION HELPERS

    private fun canOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            android.provider.Settings.canDrawOverlays(this)
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun beginWithCountdown(resultCode: Int, resultData: Intent) {
        val enabled = getSharedPreferences("screenpro", MODE_PRIVATE)
            .getBoolean("countdown_enabled", true)

        if (enabled && canOverlay()) {
            runCountdown(3) { startRecordingSafely(resultCode, resultData) }
        } else {
            startRecordingSafely(resultCode, resultData)
        }
    }

    private fun startRecordingSafely(resultCode: Int, resultData: Intent) {
        try {
            startRecording(resultCode, resultData)
        } catch (e: Exception) {
            e.printStackTrace()
            finishRecording()
            stopSelf()
        }
    }

    private fun runCountdown(seconds: Int, onDone: () -> Unit) {
        val tv = TextView(this).apply {
            text = seconds.toString()
            textSize = 84f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD172033.toInt())
            }
        }

        val size = dp(150)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager?.addView(tv, params)
        } catch (e: Exception) {
            e.printStackTrace()
            onDone()
            return
        }

        countdownView = tv
        isCountingDown = true
        var remaining = seconds

        val runnable = object : Runnable {
            override fun run() {
                if (isStopping) {
                    cancelCountdown()
                    return
                }

                remaining--

                if (remaining > 0) {
                    tv.text = remaining.toString()
                    tv.scaleX = 1.35f
                    tv.scaleY = 1.35f
                    tv.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
                    mainHandler.postDelayed(this, 1000)
                } else {
                    removeCountdownView()
                    isCountingDown = false
                    onDone()
                }
            }
        }

        countdownRunnable = runnable
        mainHandler.postDelayed(runnable, 1000)
    }

    private fun removeCountdownView() {
        val v = countdownView ?: return

        try {
            windowManager?.removeView(v)
        } catch (_: Exception) {
        }

        countdownView = null
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        removeCountdownView()
        isCountingDown = false
    }

    private fun startShakeDetection() {
        val enabled = getSharedPreferences("screenpro", MODE_PRIVATE)
            .getBoolean("shake_to_stop", false)

        if (!enabled || shakeListener != null) return

        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0] / SensorManager.GRAVITY_EARTH
                val gy = event.values[1] / SensorManager.GRAVITY_EARTH
                val gz = event.values[2] / SensorManager.GRAVITY_EARTH
                val g = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)

                if (g < 2.7f) return

                val now = SystemClock.elapsedRealtime()
                if (now - lastShakeAt < 150) return
                lastShakeAt = now

                if (now - firstShakeAt > 1200) {
                    firstShakeAt = now
                    shakeCount = 1
                } else {
                    shakeCount++
                }

                if (shakeCount >= 4 && isRecording && !isStopping) {
                    shakeCount = 0
                    mainHandler.post {
                        finishRecording()
                        stopSelf()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        sensorManager = sm
        shakeListener = listener
    }

    private fun stopShakeDetection() {
        shakeListener?.let { sensorManager?.unregisterListener(it) }
        shakeListener = null
        sensorManager = null
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.postDelayed(collapseRunnable, 3000)
    }

    private fun applyDotState() {
        val dot = dotView ?: return

        dotAnimator?.cancel()
        dotAnimator = null
        dot.alpha = 1f

        if (isPaused) {
            dot.setTextColor(0xFF8A94A6.toInt())
        } else {
            dot.setTextColor(0xFFFF4D5A.toInt())

            dotAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
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

            floatingWidget = panel
            widgetParams = params
            widgetCollapsed = false

            buildExpandedWidget(panel)
            windowManager?.addView(panel, params)

            mainHandler.removeCallbacks(tickRunnable)
            mainHandler.post(tickRunnable)

        } catch (e: Exception) {
            e.printStackTrace()
            floatingWidget = null
            widgetParams = null
        }
    }

    private fun buildExpandedWidget(panel: LinearLayout) {
        panel.removeAllViews()
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER
        panel.setPadding(dp(8), dp(10), dp(8), dp(10))
        panel.background = rounded(0xEE172033.toInt(), 18)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragHandle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(8))
        }

        header.addView(
            dragHandle,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val collapseButton = TextView(this).apply {
            text = "—"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), dp(8))
            setOnClickListener {
                collapseWidget()
            }
        }

        header.addView(collapseButton)
        panel.addView(header)

        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        val dot = TextView(this).apply {
            text = "●"
            textSize = 11f
            setTextColor(0xFFFF4D5A.toInt())
            setPadding(0, 0, dp(5), 0)
        }

        val timeView = TextView(this).apply {
            text = formatElapsed(elapsedMs())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        timerRow.addView(dot)
        timerRow.addView(timeView)
        panel.addView(timerRow)

        dotView = dot
        timerText = timeView

        pauseButton = Button(this).apply {
            text = if (isPaused) "▶" else "Ⅱ"
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

        val params = widgetParams
        if (params != null) {
            dragHandle.setOnTouchListener(
                createDragListener(panel, params)
            )
        }

        widgetCollapsed = false
        updateWidgetLayout()
        applyDotState()
        scheduleAutoCollapse()
    }

    private fun collapseWidget() {
        val panel = floatingWidget ?: return
        val params = widgetParams ?: return

        mainHandler.removeCallbacks(collapseRunnable)
        timerText = null

        panel.removeAllViews()
        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER
        panel.setPadding(0, 0, 0, 0)
        panel.background = rounded(0xEE172033.toInt(), 18)

        val tab = TextView(this).apply {
            text = "●"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF4D5A.toInt())
            background = rounded(0xEE172033.toInt(), 18)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        tab.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWidgetX = params.x
                    initialWidgetY = params.y
                    view.tag = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (
                        kotlin.math.abs(deltaX) > dp(5) ||
                        kotlin.math.abs(deltaY) > dp(5)
                    ) {
                        view.tag = true
                    }

                    params.x = initialWidgetX - deltaX.toInt()
                    params.y = initialWidgetY + deltaY.toInt()

                    try {
                        windowManager?.updateViewLayout(panel, params)
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    val wasDragged = view.tag as? Boolean ?: false

                    if (!wasDragged) {
                        expandWidget()
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        panel.addView(
            tab,
            LinearLayout.LayoutParams(dp(52), dp(52))
        )

        widgetCollapsed = true
        updateWidgetLayout()

        dotView = tab
        applyDotState()
    }

    private fun expandWidget() {
        val panel = floatingWidget ?: return
        buildExpandedWidget(panel)
    }

    private fun createDragListener(
        panel: LinearLayout,
        params: WindowManager.LayoutParams
    ): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scheduleAutoCollapse()
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWidgetX = params.x
                    initialWidgetY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    scheduleAutoCollapse()

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
    }

    private fun updateWidgetLayout() {
        val panel = floatingWidget ?: return
        val params = widgetParams ?: return

        try {
            windowManager?.updateViewLayout(panel, params)
        } catch (_: Exception) {
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
                    pausedTotal += SystemClock.elapsedRealtime() - pausedAt
                    isPausedNow = false
                    pauseButton?.text = "Ⅱ"

                    Toast.makeText(
                        this,
                        "Recording resumed",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    recorder.pause()
                    isPaused = true
                    pausedAt = SystemClock.elapsedRealtime()
                    isPausedNow = true
                    pauseButton?.text = "▶"

                    Toast.makeText(
                        this,
                        "Recording paused",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                applyDotState()
                updateNotification()
                scheduleAutoCollapse()
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

        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.removeCallbacks(tickRunnable)
        dotAnimator?.cancel()
        dotAnimator = null
        dotView = null
        timerText = null

        floatingWidget = null
        pauseButton = null
        widgetParams = null
        widgetCollapsed = false
    }

    // STOP AND SAVE RECORDING

    private fun finishRecording() {
        if (isStopping) return

        isStopping = true

        cancelCountdown()
        stopShakeDetection()
        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.removeCallbacks(tickRunnable)

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
        isActive = false
        isPausedNow = false

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

    // NOTIFICATION WITH PAUSE / STOP ACTIONS

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            flags
        )

        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            },
            flags
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_STOP
            },
            flags
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenPro Recorder")
            .setContentText(
                when {
                    isPaused -> "Recording paused"
                    isRecording -> "Your screen is being recorded"
                    else -> "Starting…"
                }
            )
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openIntent)

        if (isRecording) {
            builder
                .setWhen(System.currentTimeMillis() - elapsedMs())
                .setShowWhen(true)
                .setUsesChronometer(!isPaused)

            @Suppress("DEPRECATION")
            builder.addAction(
                if (isPaused) android.R.drawable.ic_media_play
                else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseIntent
            )
        }

        @Suppress("DEPRECATION")
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            stopIntent
        )

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScreenPro Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen recording status"
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
