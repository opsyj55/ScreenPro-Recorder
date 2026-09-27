package com.screenpro.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.screenpro.recorder.START"
        const val ACTION_STOP = "com.screenpro.recorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screenpro_recording"
        private const val NOTIFICATION_ID = 4102
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var output: RecordingStore.Output? = null
    private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> {
                val notification = buildNotification("Preparing screen recording")
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else startForeground(NOTIFICATION_ID, notification)
                beginCapture(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(intent: Intent) {
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_RESULT_DATA)
            if (data == null) throw IllegalStateException("Missing screen capture permission data")

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = getScreenMetrics()
            output = RecordingStore.create(this)

            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder = mediaRecorder
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(metrics.width, metrics.height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6_000_000)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
                val out = output!!
                if (out.uri != null) {
                    val descriptor = contentResolver.openFileDescriptor(out.uri, "w")
                        ?: throw IllegalStateException("Could not open output video")
                    setOutputFile(descriptor.fileDescriptor)
                    // Keep the descriptor alive until recorder stops.
                    outputDescriptor = descriptor
                } else {
                    setOutputFile(out.file!!.absolutePath)
                }
                prepare()
            }

            display = projection!!.createVirtualDisplay(
                "ScreenProCapture", metrics.width, metrics.height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, null
            )
            mediaRecorder.start()
            isRecording = true
            updateNotification("ScreenPro is recording")
        } catch (e: Exception) {
            updateNotification("Recording failed: ${e.message ?: "unknown error"}")
            stopRecording()
        }
    }

    private var outputDescriptor: android.os.ParcelFileDescriptor? = null

    private fun getScreenMetrics(): Metrics {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Metrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private data class Metrics(val width: Int, val height: Int, val densityDpi: Int)

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("ScreenPro Recorder")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(isRecording)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopRecording() {
        try {
            if (isRecording) recorder?.stop()
        } catch (_: RuntimeException) {
            output?.let { RecordingStore.delete(this, it) }
        } finally {
            isRecording = false
            try { display?.release() } catch (_: Exception) {}
            display = null
            try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
            recorder = null
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            try { outputDescriptor?.close() } catch (_: Exception) {}
            outputDescriptor = null
            output?.let { try { RecordingStore.publish(this, it) } catch (_: Exception) {} }
            output = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (isRecording) stopRecording()
        super.onDestroy()
    }
}
