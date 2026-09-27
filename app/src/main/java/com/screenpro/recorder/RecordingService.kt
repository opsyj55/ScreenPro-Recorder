package com.screenpro.recorder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.WindowManager
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

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenpro_recording"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    private var isRecording = false
    private var isStopping = false

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate() {
        super.onCreate()

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {
                val resultCode =
                    intent.getIntExtra(
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

                if (resultData == null ||
                    resultCode != Activity.RESULT_OK
                ) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startRecordingForeground()
                startRecording(resultCode, resultData)
            }

            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecordingForeground() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= 29) {
            var serviceType =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

            if (Build.VERSION.SDK_INT >= 30) {
                serviceType =
                    serviceType or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent
    ) {
        try {
            isStopping = false

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            val windowManager =
                getSystemService(Context.WINDOW_SERVICE) as WindowManager

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val filename = "ScreenPro_" +
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.US
                ).format(Date()) + ".mp4"

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

                if (Build.VERSION.SDK_INT >= 29) {
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
            ) ?: throw Exception("Could not create video file")

            outputDescriptor =
                contentResolver.openFileDescriptor(outputUri!!, "w")
                    ?: throw Exception("Could not open video file")

            mediaRecorder =
                if (Build.VERSION.SDK_INT >= 31) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5_000_000)

                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)

                setOutputFile(outputDescriptor!!.fileDescriptor)
                prepare()
            }

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                ) ?: throw Exception("Could not start screen capture")

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopRecording()
                        stopSelf()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            virtualDisplay =
                mediaProjection?.createVirtualDisplay(
                    "ScreenProRecorder",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder?.surface,
                    null,
                    null
                ) ?: throw Exception("Could not create virtual display")

            mediaRecorder?.start()

            isRecording = true
            sendRecordingState(true)

        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording()
            stopSelf()
        }
    }

    private fun sendRecordingState(recording: Boolean) {
        val stateIntent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RECORDING, recording)
        }

        sendBroadcast(stateIntent)
    }

    private fun stopRecording() {
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

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        virtualDisplay = null

        val projection = mediaProjection
        mediaProjection = null

        try {
            projection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            outputDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        outputDescriptor = null

        outputUri?.let { uri ->
            if (successfullySaved) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }

                    contentResolver.update(uri, values, null, null)
                }
            } else {
                contentResolver.delete(uri, null, null)
            }
        }

        outputUri = null

        if (wasRecording) {
            sendRecordingState(false)
        }
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

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
