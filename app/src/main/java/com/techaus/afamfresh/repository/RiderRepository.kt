package com.techaus.afamfresh.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.Delivery
import com.techaus.afamfresh.models.EarningsEntry
import com.techaus.afamfresh.models.EarningsSummary
import com.techaus.afamfresh.models.PayoutRequest
import com.techaus.afamfresh.models.RiderProfile
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * Network layer for the rider section.
 *
 * Callbacks are `(result, error)` throughout, matching [AuthRepository], so the
 * ViewModel can tell "you're offline" apart from "the server said no" — the
 * distinction `enqueueApi` exists to preserve.
 *
 * Note the two-level result: a 200 response can still carry
 * `{"success":false,"error":"..."}`, and the server's message is the useful one
 * (e.g. "This account is not set up as a rider yet"). Transport errors fall back
 * to [ApiError.userMessage].
 */
class RiderRepository(
    private val api: ApiService,
    private val context: Context
) {

    private companion object {
        const val TAG = "RiderRepo"

        /**
         * Long edge for the uploaded proof photo, and JPEG quality.
         *
         * A modern phone camera frame is several MB and would be refused by the
         * server's 5 MB cap; this keeps the upload small enough to send over a
         * patchy connection while staying legible as evidence.
         */
        const val MAX_DIMEN = 1280
        const val JPEG_QUALITY = 80
    }

    fun loadProfile(onResult: (RiderProfile?, String?) -> Unit) {
        api.getRiderProfile().enqueueApi(TAG, "rider me") { body, error ->
            when {
                body?.success == true && body.rider != null -> onResult(body.rider, null)
                body != null -> onResult(null, body.error ?: "Could not load your rider profile.")
                else -> onResult(null, error?.userMessage ?: "Could not load your rider profile.")
            }
        }
    }

    fun loadDeliveries(onResult: (List<Delivery>?, List<Delivery>?, String?) -> Unit) {
        api.getDeliveries().enqueueApi(TAG, "deliveries") { body, error ->
            when {
                body?.success == true ->
                    onResult(body.active.orEmpty(), body.history.orEmpty(), null)
                body != null -> onResult(null, null, body.error ?: "Could not load your deliveries.")
                else -> onResult(null, null, error?.userMessage ?: "Could not load your deliveries.")
            }
        }
    }

    // `source` comes from the Delivery the rider tapped. It is not optional and
    // must never be guessed: shop order 41 and surplus order 41 both exist, so
    // the wrong value opens a different customer's job.
    fun loadDeliveryDetail(
        orderId: Int,
        source: String = "order",
        onResult: (Delivery?, String?) -> Unit
    ) {
        api.getDeliveryDetail(orderId = orderId, source = source)
            .enqueueApi(TAG, "delivery detail") { body, error ->
            when {
                body?.success == true && body.delivery != null -> onResult(body.delivery, null)
                body != null -> onResult(null, body.error ?: "Could not load that delivery.")
                else -> onResult(null, error?.userMessage ?: "Could not load that delivery.")
            }
        }
    }

    fun updateDeliveryStatus(
        orderId: Int,
        status: String,
        source: String = "order",
        onResult: (Boolean, String?) -> Unit
    ) {
        api.updateDeliveryStatus(orderId = orderId, status = status, source = source)
            .enqueueApi(TAG, "update status") { body, error ->
                when {
                    body?.success == true -> onResult(true, null)
                    body != null -> onResult(false, body.error ?: "Could not update the delivery.")
                    else -> onResult(false, error?.userMessage ?: "Could not update the delivery.")
                }
            }
    }

    fun setDutyStatus(online: Boolean, onResult: (Boolean, String?) -> Unit) {
        api.setDutyStatus(status = if (online) "online" else "offline")
            .enqueueApi(TAG, "duty status") { body, error ->
                when {
                    body?.success == true -> onResult(true, null)
                    body != null -> onResult(false, body.error ?: "Could not change your status.")
                    else -> onResult(false, error?.userMessage ?: "Could not change your status.")
                }
            }
    }

    /**
     * Posts one position fix. Silent by design — location updates fire
     * repeatedly while on duty, and a transient failure is not worth
     * interrupting the rider for.
     */
    fun postLocation(lat: Double, lng: Double, onResult: (Boolean) -> Unit = {}) {
        api.postRiderLocation(lat = lat, lng = lng).enqueueApi(TAG, "location") { body, _ ->
            onResult(body?.success == true)
        }
    }

    fun uploadProof(
        orderId: Int,
        bytes: ByteArray,
        source: String = "order",
        onResult: (String?, String?) -> Unit
    ) {
        val part = MultipartBody.Part.createFormData(
            "photo",
            "proof.jpg",
            bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        )
        val idPart = orderId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
        val sourcePart = source.toRequestBody("text/plain".toMediaTypeOrNull())

        api.uploadDeliveryProof(orderId = idPart, source = sourcePart, photo = part)
            .enqueueApi(TAG, "upload proof") { body, error ->
                when {
                    body?.success == true -> onResult(body.proofPhotoUrl, null)
                    body != null -> onResult(null, body.error ?: "Could not upload the photo.")
                    else -> onResult(null, error?.userMessage ?: "Could not upload the photo.")
                }
            }
    }

    fun loadEarnings(onResult: (EarningsSummary?, List<EarningsEntry>?, String?) -> Unit) {
        api.getRiderEarnings().enqueueApi(TAG, "earnings") { body, error ->
            when {
                body?.success == true -> onResult(body.summary, body.history.orEmpty(), null)
                body != null -> onResult(null, null, body.error ?: "Could not load your earnings.")
                else -> onResult(null, null, error?.userMessage ?: "Could not load your earnings.")
            }
        }
    }

    fun requestPayout(onResult: (Double?, String?) -> Unit) {
        api.requestPayout().enqueueApi(TAG, "request payout") { body, error ->
            when {
                body?.success == true -> onResult(body.amount, null)
                body != null -> onResult(null, body.error ?: "Could not request a payout.")
                else -> onResult(null, error?.userMessage ?: "Could not request a payout.")
            }
        }
    }

    fun loadPayouts(onResult: (List<PayoutRequest>?, String?) -> Unit) {
        api.getMyPayouts().enqueueApi(TAG, "my payouts") { body, error ->
            when {
                body?.success == true -> onResult(body.payouts.orEmpty(), null)
                body != null -> onResult(null, body.error ?: "Could not load your payouts.")
                else -> onResult(null, error?.userMessage ?: "Could not load your payouts.")
            }
        }
    }

    /**
     * Reads, downscales and JPEG-compresses a picked image, off the main thread.
     *
     * `inSampleSize` is chosen from the bounds-only pass first, so a large
     * photo is never fully decoded into memory just to be shrunk.
     */
    suspend fun prepareProofBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return@withContext null

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_DIMEN * 2) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@withContext null

            val scale = MAX_DIMEN.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }
}
