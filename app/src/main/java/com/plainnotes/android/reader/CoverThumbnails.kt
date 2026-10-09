package com.plainnotes.android.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

internal object CoverThumbnails {
    private val cache by lazy {
        object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }

    /** Called on IO, sampled to display size instead of decoding full-resolution covers. */
    fun load(path: String, width: Int, height: Int): Bitmap? {
        val key = "$path:${File(path).lastModified()}:$width:$height"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        require(targetWidth > 0 && targetHeight > 0)
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }
}
