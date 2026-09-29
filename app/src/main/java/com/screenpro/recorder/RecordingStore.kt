package com.screenpro.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingStore {
    data class Output(val uri: Uri?, val file: File?, val displayName: String)

    fun create(context: Context): Output {
        val name = "ScreenPro_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenPro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create video in MediaStore")
            return Output(uri, null, name)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenPro").apply { mkdirs() }
        return Output(null, File(dir, name), name)
    }

    fun publish(context: Context, output: Output) {
        if (Build.VERSION.SDK_INT >= 29 && output.uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(output.uri, values, null, null)
        }
    }

    fun delete(context: Context, output: Output) {
        try {
            if (output.uri != null) context.contentResolver.delete(output.uri, null, null)
            output.file?.delete()
        } catch (_: Exception) {}
    }

    data class Item(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val dateAddedSec: Long
    )

    /** Saved recordings (newest first) with the details the library shows. */
    fun listItems(context: Context): List<Item> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH}=?"
        val args = arrayOf(Environment.DIRECTORY_MOVIES + "/ScreenPro/")
        val result = mutableListOf<Item>()

        context.contentResolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val date = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (c.moveToNext()) {
                result += Item(
                    Uri.withAppendedPath(collection, c.getLong(id).toString()),
                    c.getString(name) ?: "Recording",
                    c.getLong(size),
                    c.getLong(dur),
                    c.getLong(date)
                )
            }
        }

        return result
    }

    fun list(context: Context): List<Uri> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH}=?"
        val args = arrayOf(Environment.DIRECTORY_MOVIES + "/ScreenPro/")
        val result = mutableListOf<Uri>()
        context.contentResolver.query(collection, projection, selection, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                result += Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
            }
        }
        return result
    }
}
