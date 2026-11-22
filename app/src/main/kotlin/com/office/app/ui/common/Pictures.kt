package com.office.app.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.office.core.image.Tiff

/** Decodes pictures from documents: whatever Android can read, plus TIFF, which it cannot. */
object Pictures {

    /** [bytes] as a bitmap at most about [maxPx] wide (and high), or null when it is not a picture we can read. */
    fun decode(bytes: ByteArray, maxPx: Int = 1600): Bitmap? = runCatching {
        val limit = maxPx.coerceAtLeast(64)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= limit || bounds.outHeight / (sample * 2) >= limit) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } else {
            tiff(bytes, limit)
        }
    }.getOrNull()

    /** True when [bytes] is a picture only our own decoder can read. */
    fun needsConversion(bytes: ByteArray): Boolean = Tiff.isTiff(bytes)

    private fun tiff(bytes: ByteArray, limit: Int): Bitmap? {
        val img = Tiff.decode(bytes) ?: return null
        val full = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
        val scale = minOf(1f, limit * 2f / maxOf(img.width, img.height))
        if (scale >= 1f) return full
        return Bitmap.createScaledBitmap(full, (img.width * scale).toInt().coerceAtLeast(1), (img.height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it !== full) full.recycle() }
    }
}
