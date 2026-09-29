package com.screenpro.recorder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
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
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.screenpro.recorder.START"
        const val ACTION_STOP = "com.screenpro.recorder.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.screenpro.recorder.TOGGLE_PAUSE"
        const val ACTION_STATE_CHANGED =
            "com.screenpro.recorder.ACTION_STATE_CHANGED"
        const val ACTION_SAVED = "com.screenpro.recorder.ACTION_SAVED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_SAVED = "saved"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenpro_recording"

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

        /** "mic", "internal", "both" or "none". */
        fun audioSource(ctx: Context): String {
            val prefs = ctx.getSharedPreferences("screenpro", MODE_PRIVATE)

            val saved = prefs.getString("audio_source", null)
                ?: if (prefs.getBoolean("microphone_enabled", true)) "mic" else "none"

            return if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                (saved == "internal" || saved == "both")
            ) {
                "mic"
            } else {
                saved
            }
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var tmpUri: Uri? = null
    private var tmpDescriptor: ParcelFileDescriptor? = null
    private var finalName = ""
    private var recWidth = 0
    private var recHeight = 0

    private var audioCapture: AudioCapture? = null
    private var audioFile: File? = null

    private var isRecording = false
    private var isPaused = false
    private var isStopping = false
    private var foregroundStarted = false
    private var shotInProgress = false

    private var windowManager: WindowManager? = null
    private var ball: FloatingBall? = null
    private var draw: DrawOverlay? = null

    private var countdownView: TextView? = null
    private var countdownRunnable: Runnable? = null

    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var firstShakeAt = 0L
    private var lastShakeAt = 0L
    private var shakeCount = 0

    private lateinit var projectionManager: MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            ball?.update(formatElapsed(elapsedMs()), isPaused)
            if (isRecording) mainHandler.postDelayed(this, 500)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            finishRecording()
        }
    }

    override fun onCreate() {
        super.onCreate()

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> finishRecording()
            ACTION_TOGGLE_PAUSE -> togglePause()
        }

        return START_NOT_STICKY
    }

    // START

    private fun handleStart(intent: Intent) {
        if (isRecording || isStopping || isCountingDown) return

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
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
            audioSource(this) != "none" &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast("Microphone permission is needed for audio. Allow it and try again.")
            sendRecordingState(false)
            stopSelf()
            return
        }

        try {
            startRecordingForeground()
            beginWithCountdown(resultCode, resultData)
        } catch (e: Exception) {
            e.printStackTrace()
            toast("Could not start: ${e.message}")
            finishRecording()
        }
    }

    private fun startRecordingForeground() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

            val source = audioSource(this)

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (source == "mic" || source == "both")
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        foregroundStarted = true
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
            toast("Could not start recording: ${e.message}")
            finishRecording()
        }
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        isStopping = false
        isPaused = false

        val prefs = getSharedPreferences("screenpro", MODE_PRIVATE)

        val source = audioSource(this)
        val useMic = source == "mic" || source == "both"
        val useInternal = source == "internal" || source == "both"
        val wantsAudio = useMic || useInternal

        val resolution = prefs.getString("resolution", "1080p") ?: "1080p"
        val mbps = prefs.getInt("bitrate_mbps", 8)
        val fps = prefs.getInt("fps", 30)

        val metrics = android.util.DisplayMetrics()

        @Suppress("DEPRECATION")
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val (width, height) = chooseVideoSize(
            metrics.widthPixels,
            metrics.heightPixels,
            resolution
        )

        recWidth = width
        recHeight = height

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        finalName = "ScreenPro_$stamp.mp4"

        // With audio, the video is saved under a temporary name first and then
        // joined with the audio track when recording ends.
        val entryName = if (wantsAudio) "ScreenPro_tmp_$stamp.mp4" else finalName

        val uri = insertVideoEntry(entryName)
        tmpUri = uri

        tmpDescriptor = contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("Could not open video file")

        mediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

        mediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(mbps * 1_000_000)
            setOutputFile(tmpDescriptor!!.fileDescriptor)
            prepare()
        }

        recorderSurface = mediaRecorder?.surface

        mediaProjection = projectionManager.getMediaProjection(
            resultCode,
            resultData
        ) ?: throw IllegalStateException("Could not start screen capture")

        mediaProjection?.registerCallback(projectionCallback, mainHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenProRecorder",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorderSurface,
            null,
            mainHandler
        ) ?: throw IllegalStateException("Could not create virtual display")

        if (wantsAudio) startAudio(useMic, useInternal)

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
        showFloatingBall()
        sendRecordingState(true)
    }

    private fun startAudio(useMic: Boolean, useInternal: Boolean) {
        val file = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")

        try {
            val capture = AudioCapture(
                useMic,
                useInternal,
                mediaProjection,
                file
            )

            capture.onError = { msg ->
                toast("Audio stopped: $msg")
            }

            capture.prepare()
            capture.start()

            audioCapture = capture
            audioFile = file
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            audioCapture = null
            audioFile = null
            toast("Audio unavailable: ${e.message}. Recording video only.")
        }
    }

    private fun chooseVideoSize(
        screenWidth: Int,
        screenHeight: Int,
        resolution: String
    ): Pair<Int, Int> {
        val longer = maxOf(screenWidth, screenHeight).toFloat()

        val target = when (resolution) {
            "720p" -> 1280f
            "1080p" -> 1920f
            "1440p" -> 2560f
            else -> longer
        }

        val scale = minOf(1f, target / longer)

        var width = ((screenWidth * scale).toInt() / 2) * 2
        var height = ((screenHeight * scale).toInt() / 2) * 2

        if (width < 2 || height < 2) {
            width = screenWidth
            height = screenHeight
        }

        return Pair(width, height)
    }

    private fun insertVideoEntry(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenPro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        return contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Could not create video file")
    }

    // PAUSE

    private fun togglePause() {
        val recorder = mediaRecorder ?: return

        if (!isRecording || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        try {
            if (isPaused) {
                recorder.resume()
                audioCapture?.resume()
                isPaused = false
                pausedTotal += SystemClock.elapsedRealtime() - pausedAt
                isPausedNow = false
            } else {
                recorder.pause()
                audioCapture?.pause()
                isPaused = true
                pausedAt = SystemClock.elapsedRealtime()
                isPausedNow = true
            }

            ball?.update(formatElapsed(elapsedMs()), isPaused)
            updateNotification()
            sendRecordingState(true)
        } catch (e: Exception) {
            e.printStackTrace()
            toast("Could not pause: ${e.message}")
        }
    }

    // STATE BROADCASTS

    private fun sendRecordingState(recording: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED).apply {
                setPackage(packageName)
                putExtra(EXTRA_IS_RECORDING, recording)
                putExtra(EXTRA_IS_PAUSED, isPaused)
            }
        )

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

    private fun sendSaved(saved: Boolean) {
        sendBroadcast(
            Intent(ACTION_SAVED).apply {
                setPackage(packageName)
                putExtra(EXTRA_SAVED, saved)
            }
        )
    }

    // OVERLAYS

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

    private fun showFloatingBall() {
        val enabled = getSharedPreferences("screenpro", MODE_PRIVATE)
            .getBoolean("floating_ball", true)

        val wm = windowManager

        if (!enabled || wm == null || !canOverlay()) return

        val b = FloatingBall(
            this,
            wm,
            overlayType(),
            onStop = { finishRecording() },
            onTogglePause = { togglePause() },
            onScreenshot = { takeScreenshot() },
            onBrush = { toggleBrush() }
        )

        b.show()
        ball = b

        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
    }

    private fun toggleBrush() {
        val wm = windowManager ?: return

        val overlay = draw ?: DrawOverlay(this, wm, overlayType()) {
            ball?.setBrushActive(false)
        }.also { draw = it }

        if (overlay.isShowing) {
            overlay.hide()
            ball?.setBrushActive(false)
        } else {
            overlay.show()
            ball?.setBrushActive(true)
            ball?.bringToFront()
        }
    }

    // SCREENSHOT
    //
    // Android 14 only allows one virtual display per screen-capture session,
    // so we briefly point the same display at an ImageReader, grab one frame,
    // then point it back at the video recorder.

    private fun takeScreenshot() {
        if (!isRecording || shotInProgress) return

        shotInProgress = true
        ball?.hideTemporarily()
        draw?.setToolbarVisible(false)

        mainHandler.postDelayed({ captureFrame() }, 220)
    }

    private fun captureFrame() {
        val display = virtualDisplay
        val recSurface = recorderSurface

        if (display == null || recSurface == null || !isRecording) {
            endScreenshot(null, "Screenshot failed")
            return
        }

        val reader = ImageReader.newInstance(
            recWidth,
            recHeight,
            PixelFormat.RGBA_8888,
            2
        )

        var finished = false

        fun finish(bitmap: Bitmap?, error: String?) {
            if (finished) return
            finished = true

            try {
                display.setSurface(recSurface)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                reader.close()
            } catch (_: Exception) {
            }

            endScreenshot(bitmap, error)
        }

        reader.setOnImageAvailableListener({ r ->
            if (finished) return@setOnImageAvailableListener

            val image = try {
                r.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            var bitmap: Bitmap? = null

            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * recWidth

                val full = Bitmap.createBitmap(
                    recWidth + rowPadding / pixelStride,
                    recHeight,
                    Bitmap.Config.ARGB_8888
                )

                full.copyPixelsFromBuffer(plane.buffer)

                bitmap = Bitmap.createBitmap(full, 0, 0, recWidth, recHeight)
                if (bitmap !== full) full.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }

            finish(bitmap, if (bitmap == null) "Screenshot failed" else null)
        }, mainHandler)

        try {
            display.setSurface(reader.surface)
        } catch (e: Exception) {
            e.printStackTrace()
            finish(null, "Screenshot failed")
            return
        }

        mainHandler.postDelayed({
            finish(null, "Screenshot failed, try again")
        }, 1500)
    }

    private fun endScreenshot(bitmap: Bitmap?, error: String?) {
        shotInProgress = false
        ball?.showAgain()
        draw?.setToolbarVisible(true)

        if (bitmap != null) {
            saveScreenshot(bitmap)
        } else if (error != null) {
            toast(error)
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        Thread {
            var ok = false

            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(Date())

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ScreenPro_$stamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenPro")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )

                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val done = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }

                        contentResolver.update(uri, done, null, null)
                    }

                    ok = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            bitmap.recycle()

            toast(
                if (ok) "Screenshot saved to Pictures/ScreenPro"
                else "Couldn't save screenshot"
            )
        }.start()
    }

    // COUNTDOWN

    private fun runCountdown(seconds: Int, onDone: () -> Unit) {
        val tv = TextView(this).apply {
            text = seconds.toString()
            textSize = 84f
            typeface = Fonts.bold(this@RecordingService)
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

    // SHAKE TO STOP

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
                    mainHandler.post { finishRecording() }
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

    // FINISH

    private fun finishRecording() {
        if (isStopping) return

        isStopping = true

        cancelCountdown()
        stopShakeDetection()
        mainHandler.removeCallbacks(tickRunnable)

        draw?.hide()
        draw = null
        ball?.remove()
        ball = null

        val wasRecording = isRecording
        var recordedOk = false

        if (wasRecording) {
            try {
                mediaRecorder?.stop()
                recordedOk = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        isRecording = false
        isPaused = false
        isActive = false
        isPausedNow = false

        val audio = audioCapture
        val audioPath = audioFile
        audioCapture = null
        audioFile = null

        try {
            audio?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        mediaRecorder = null
        recorderSurface = null

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
            tmpDescriptor?.close()
        } catch (_: Exception) {
        }

        tmpDescriptor = null

        val uri = tmpUri
        val name = finalName
        tmpUri = null

        sendRecordingState(false)
        updateNotification()

        Thread {
            val saved = finalizeOutput(uri, name, recordedOk, audio, audioPath)

            mainHandler.post {
                sendSaved(saved)
                removeForegroundNotification()
                stopSelf()
            }
        }.start()
    }

    private fun finalizeOutput(
        uri: Uri?,
        name: String,
        recordedOk: Boolean,
        audio: AudioCapture?,
        audioPath: File?
    ): Boolean {
        var saved = false

        try {
            if (uri == null) return false

            if (!recordedOk) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }

                return false
            }

            val audioReady = audio != null &&
                audio.usable &&
                audioPath != null &&
                audioPath.exists()

            if (audioReady) {
                var muxedUri: Uri? = null

                try {
                    muxedUri = insertVideoEntry(name)

                    val outPfd = contentResolver.openFileDescriptor(muxedUri, "rw")
                        ?: throw IllegalStateException("Could not open output")

                    val inPfd = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("Could not open recording")

                    try {
                        AvMuxer.mux(
                            inPfd.fileDescriptor,
                            audioPath!!.absolutePath,
                            outPfd.fileDescriptor
                        )
                    } finally {
                        inPfd.close()
                        outPfd.close()
                    }

                    publish(muxedUri, null)

                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }

                    saved = true
                } catch (e: Exception) {
                    e.printStackTrace()

                    muxedUri?.let {
                        try {
                            contentResolver.delete(it, null, null)
                        } catch (_: Exception) {
                        }
                    }

                    // Keep the video even if adding audio failed.
                    publish(uri, name)
                    saved = true
                    toast("Couldn't add audio to the video, saved without sound.")
                }
            } else {
                publish(uri, name)
                saved = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioPath?.delete()
        }

        return saved
    }

    private fun publish(uri: Uri, renameTo: String?) {
        val values = ContentValues()

        if (renameTo != null) {
            values.put(MediaStore.Video.Media.DISPLAY_NAME, renameTo)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
        }

        if (values.size() > 0) {
            contentResolver.update(uri, values, null, null)
        }
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

    // NOTIFICATION

    private fun updateNotification() {
        if (!foregroundStarted) return

        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

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
                    isStopping -> "Saving recording…"
                    isPaused -> "Recording paused"
                    isRecording -> "Your screen is being recorded"
                    else -> "Starting…"
                }
            )
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openIntent)

        if (isRecording && !isStopping) {
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

        if (!isStopping) {
            @Suppress("DEPRECATION")
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
            )
        }

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

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // HELPERS

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        finishRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
