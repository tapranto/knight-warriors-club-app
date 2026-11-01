package com.knightwarriorsclub.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The member's personal app background (Pranto, 2026-11-01): the club
 * lantern-forest artwork is the default for everyone; a member may pick
 * their own picture, which is copied into the app's private storage and
 * lives ONLY on this phone — never uploaded, never synced, and deleting
 * it restores the club default.
 */
object BackgroundStore {
    private const val FILE_NAME = "custom_bg.jpg"
    private const val MAX_DIM = 1920

    fun customFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasCustom(context: Context): Boolean = customFile(context).exists()

    /** Downscale + save the picked image as this phone's background. */
    suspend fun saveFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w > MAX_DIM || h > MAX_DIM) {
                sample *= 2
                w /= 2
                h /= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap: Bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching false
            FileOutputStream(customFile(context)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            true
        }.getOrDefault(false)
    }

    /** Back to the club default picture. */
    fun clear(context: Context) {
        runCatching { customFile(context).delete() }
    }

    /** Decode the saved custom background for display (null = use default). */
    fun loadBitmap(context: Context): Bitmap? {
        val f = customFile(context)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }
}
