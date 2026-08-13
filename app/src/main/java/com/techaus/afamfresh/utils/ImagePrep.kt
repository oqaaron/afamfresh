package com.techaus.afamfresh.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Downsamples a picked image to JPEG bytes suitable for upload.
 *
 * A third copy of the same routine already in AuthRepository.prepareAvatarBytes
 * and RiderRepository.prepareProofBytes — extracted here because a third
 * caller (the customer's delivery-confirmation photo) made three inline
 * copies one too many. The other two are left as they are rather than
 * migrated, to avoid touching working upload paths for a naming exercise.
 *
 * A bounds-only decode with [BitmapFactory.Options.inJustDecodeBounds] always
 * returns null — that is not a stream-open failure, so the two decode passes
 * check stream-nullability separately from the decode result.
 */
suspend fun prepareJpegBytes(context: Context, uri: Uri, maxEdge: Int = 1024): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decodeStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val bitmap = decodeStream.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null

            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bitmap.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.e("ImagePrep", "prepareJpegBytes failed: ${e.message}", e)
            null
        }
    }
