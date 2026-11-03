package com.spektrafilm.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlin.math.max
import kotlin.math.roundToInt

object ImageIo {
    private val RAW_EXT = setOf("dng", "nef", "nrw", "cr2", "cr3", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "srw", "3fr", "iiq", "rwl", "mrw", "kdc", "erf", "mos")

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    fun isRaw(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        if (mime.contains("dng") || mime.contains("raw")) return true
        val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        return name.substringAfterLast('.', "").lowercase() in RAW_EXT
    }

    /**
     * Decode [uri] as an sRGB ARGB_8888 software bitmap (EXIF rotation applied).
     * If [maxEdge] > 0 the long edge is downscaled to at most that many pixels.
     */
    fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (maxEdge in 1 until long) {
                val s = maxEdge.toDouble() / long
                decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
    }

    /** Detached file descriptor for native RAW decoding (ownership passes to the caller). */
    fun openFd(context: Context, uri: Uri): Int =
        context.contentResolver.openFileDescriptor(uri, "r")?.detachFd() ?: error("cannot open file")

    /** Save as JPEG into Pictures/Spektrafilm; returns the new content Uri. */
    fun saveJpeg(context: Context, bitmap: Bitmap, displayName: String, quality: Int = 95): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Spektrafilm")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            val out = resolver.openOutputStream(uri) ?: error("cannot open output stream")
            out.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "JPEG encode failed" } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }
}
