package com.screenpro.recorder

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records microphone and/or internal (playback) audio to an AAC .m4a file.
 * Pause simply stops feeding samples, so it stays in sync with the video.
 */
class AudioCapture(
    private val useMic: Boolean,
    private val useInternal: Boolean,
    private val projection: MediaProjection?,
    private val outFile: File
) {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val CHUNK_FRAMES = 1024

        fun internalSupported(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    var onError: ((String) -> Unit)? = null

    private val channels = if (useInternal) 2 else 1

    private var mic: AudioRecord? = null
    private var internal: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var thread: Thread? = null
    private var totalFrames = 0L
    private var wroteSamples = false

    @Volatile private var running = false
    @Volatile private var paused = false

    val usable: Boolean
        get() = muxerStarted && wroteSamples

    fun prepare() {
        try {
            if (useMic) mic = buildMic()
            if (useInternal) internal = buildInternal()

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                channels
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }

            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c

            muxer = MediaMuxer(
                outFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    fun start() {
        try {
            startRecords()
        } catch (e: Exception) {
            release()
            throw e
        }

        running = true

        thread = Thread({ loop() }, "ScreenProAudio").also { it.start() }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        running = false

        try {
            thread?.join(3000)
        } catch (_: Exception) {
        }

        try {
            if (codec != null) {
                queue(ShortArray(0), 0, true)
                drain(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        release()
    }

    // SETUP

    @SuppressLint("MissingPermission")
    private fun buildMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuf <= 0) {
            throw IllegalStateException("Microphone is not supported on this device")
        }

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_FRAMES * 2 * 4)
        )

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException(
                "Microphone could not be opened (permission denied or busy)"
            )
        }

        return rec
    }

    @SuppressLint("MissingPermission")
    @TargetApi(Build.VERSION_CODES.Q)
    private fun buildInternal(): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Internal audio needs Android 10 or newer")
        }

        val proj = projection
            ?: throw IllegalStateException("Screen capture is not ready")

        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(CHUNK_FRAMES * 2 * 2 * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Internal audio could not be opened")
        }

        return rec
    }

    private fun startRecords() {
        mic?.startRecording()
        internal?.startRecording()

        val m = mic
        if (m != null && m.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Microphone is being used by another app")
        }

        val i = internal
        if (i != null && i.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Internal audio could not start")
        }
    }

    private fun stopRecords() {
        try {
            mic?.stop()
        } catch (_: Exception) {
        }

        try {
            internal?.stop()
        } catch (_: Exception) {
        }
    }

    // CAPTURE LOOP

    private fun loop() {
        val micBuf = ShortArray(CHUNK_FRAMES)
        val intBuf = ShortArray(CHUNK_FRAMES * 2)
        val mixed = ShortArray(CHUNK_FRAMES * 2)
        var active = true

        try {
            while (running) {
                if (paused) {
                    if (active) {
                        stopRecords()
                        active = false
                    }

                    Thread.sleep(40)
                    continue
                }

                if (!active) {
                    startRecords()
                    active = true
                }

                mic?.let { readFully(it, micBuf, CHUNK_FRAMES) }
                internal?.let { readFully(it, intBuf, CHUNK_FRAMES * 2) }

                if (channels == 1) {
                    queue(micBuf, CHUNK_FRAMES, false)
                } else {
                    val hasMic = mic != null
                    val hasInt = internal != null

                    for (i in 0 until CHUNK_FRAMES) {
                        var l = 0
                        var r = 0

                        if (hasInt) {
                            l = intBuf[2 * i].toInt()
                            r = intBuf[2 * i + 1].toInt()
                        }

                        if (hasMic) {
                            val m = micBuf[i].toInt()
                            l += m
                            r += m
                        }

                        mixed[2 * i] = clamp(l)
                        mixed[2 * i + 1] = clamp(r)
                    }

                    queue(mixed, CHUNK_FRAMES, false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            running = false
            onError?.invoke(e.message ?: "audio error")
        } finally {
            stopRecords()
        }
    }

    private fun readFully(rec: AudioRecord, buf: ShortArray, count: Int) {
        var offset = 0

        while (offset < count && running && !paused) {
            val n = rec.read(buf, offset, count - offset)

            if (n < 0) {
                throw IllegalStateException("Audio read failed ($n)")
            }

            offset += n
        }

        while (offset < count) {
            buf[offset++] = 0
        }
    }

    private fun clamp(v: Int): Short =
        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    // ENCODING

    private fun queue(pcm: ShortArray, frames: Int, eos: Boolean) {
        val c = codec ?: return
        val index = c.dequeueInputBuffer(20_000)

        if (index >= 0) {
            val buffer = c.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.order(ByteOrder.nativeOrder())

            val bytes = if (eos) 0 else frames * channels * 2

            if (!eos) {
                buffer.asShortBuffer().put(pcm, 0, frames * channels)
            }

            val pts = totalFrames * 1_000_000L / SAMPLE_RATE

            c.queueInputBuffer(
                index,
                0,
                bytes,
                pts,
                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            )

            if (!eos) totalFrames += frames
        }

        if (!eos) drain(false)
    }

    private fun drain(untilEos: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var idleTries = 0

        while (true) {
            val index = c.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    idleTries++
                    if (idleTries > 200) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }

                index >= 0 -> {
                    idleTries = 0
                    val out = c.getOutputBuffer(index)

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }

                    if (info.size > 0 && muxerStarted && out != null) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, out, info)
                        wroteSamples = true
                    }

                    c.releaseOutputBuffer(index, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }

        try {
            codec?.release()
        } catch (_: Exception) {
        }

        codec = null

        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Exception) {
        }

        try {
            muxer?.release()
        } catch (_: Exception) {
        }

        muxer = null

        try {
            mic?.release()
        } catch (_: Exception) {
        }

        try {
            internal?.release()
        } catch (_: Exception) {
        }

        mic = null
        internal = null
    }
}

/** Combines a video-only mp4 with an audio-only m4a into one mp4. */
object AvMuxer {
    fun mux(videoFd: FileDescriptor, audioPath: String, outFd: FileDescriptor) {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false

        try {
            videoEx.setDataSource(videoFd)
            audioEx.setDataSource(audioPath)

            val vIndex = findTrack(videoEx, "video/")
            val aIndex = findTrack(audioEx, "audio/")

            if (vIndex < 0 || aIndex < 0) {
                throw IllegalStateException("Missing audio or video track")
            }

            videoEx.selectTrack(vIndex)
            audioEx.selectTrack(aIndex)

            muxer = MediaMuxer(outFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vTrack = muxer.addTrack(videoEx.getTrackFormat(vIndex))
            val aTrack = muxer.addTrack(audioEx.getTrackFormat(aIndex))

            muxer.start()
            started = true

            copy(videoEx, muxer, vTrack)
            copy(audioEx, muxer, aTrack)
        } finally {
            try {
                if (started) muxer?.stop()
            } catch (_: Exception) {
            }

            try {
                muxer?.release()
            } catch (_: Exception) {
            }

            videoEx.release()
            audioEx.release()
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)

            if (mime != null && mime.startsWith(prefix)) return i
        }

        return -1
    }

    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        while (true) {
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)

            if (size < 0) break

            info.offset = 0
            info.size = size
            info.presentationTimeUs = ex.sampleTime
            info.flags =
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }

            muxer.writeSampleData(track, buffer, info)
            ex.advance()
        }
    }
}
