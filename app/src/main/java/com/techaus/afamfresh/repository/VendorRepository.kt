package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.AddVendorProductRequest
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.CreateSurplusListingRequest
import com.techaus.afamfresh.models.CreateVendorProductResponse
import com.techaus.afamfresh.models.VendorCatalogueProduct
import com.techaus.afamfresh.models.VendorCatalogueResponse
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.models.SurplusListingResponse
import com.techaus.afamfresh.models.SurplusListingsResponse
import com.techaus.afamfresh.models.SurplusOrder
import com.techaus.afamfresh.models.UpdateSurplusListingRequest
import com.techaus.afamfresh.models.UpdateVendorProfileRequest
import com.techaus.afamfresh.models.UpdateVendorProfileResponse
import com.techaus.afamfresh.models.SurplusOrdersResponse
import com.techaus.afamfresh.models.VendorProduct
import com.techaus.afamfresh.models.VendorProfile
import com.techaus.afamfresh.models.VendorProfileResponse
import com.techaus.afamfresh.models.VendorProductsResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

/**
 * Wraps the vendor-facing endpoints.
 *
 * These endpoints take an explicit `user_id` / `vendor_id`, which is why every
 * method here takes an id, unlike the customer repositories. The caller must
 * pass the signed-in user's id; see VendorViewModel.
 *
 * They DO now read the PHP session, and the id must match it. It previously did
 * not: user_id was taken on trust, so any vendor's earnings, commission rate and
 * orders could be read by changing the number. The id is still sent because the
 * server accepts it when it agrees with the session — but it is no longer what
 * decides whose records come back.
 *
 * Previously this class called `vendor/products.php`, `vendor/orders.php` and
 * `vendor/surplus/listings.php`, none of which exist — every vendor screen was
 * silently 404ing.
 */
class VendorRepository(
    private val apiService: ApiService
) {
    /**
     * Looks up the vendor record for a signed-in user, which is the only way to
     * obtain the `vendor_id` the listing and order endpoints filter on.
     *
     * Returns null in the callback when the user is not a vendor — the endpoint
     * replies {"error":"Vendor not found"} in that case, which is an expected
     * outcome rather than a failure.
     */
    fun getVendorProfile(userId: Int, callback: (VendorProfile?, ApiError?) -> Unit) {
        apiService.getVendorProfile(userId = userId)
            .enqueueApi<VendorProfileResponse>("VendorRepo", "getVendorProfile") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.vendor, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * Saves the vendor's business details.
     *
     * Reports back whether the account is verified, because that is what
     * decides if they can list products yet — saving details does not grant it,
     * an admin does.
     */
    fun updateVendorProfile(
        request: UpdateVendorProfileRequest,
        callback: (isVerified: Boolean, message: String?, error: ApiError?) -> Unit
    ) {
        apiService.updateVendorProfile(body = request)
            .enqueueApi<UpdateVendorProfileResponse>("VendorRepo", "updateVendorProfile") { body, error ->
                when {
                    error != null -> callback(false, null, error)
                    body?.success == true -> callback(body.isVerified, body.message, null)
                    else -> callback(false, null, ApiError.reported(body?.error))
                }
            }
    }

    /** The vendor's own products, every status — pending and rejected included. */
    fun getMyVendorProducts(callback: (List<VendorCatalogueProduct>?, ApiError?) -> Unit) {
        apiService.getMyVendorProducts()
            .enqueueApi<VendorCatalogueResponse>("VendorRepo", "getMyVendorProducts") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.products ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * Creates a product owned by this vendor, always as pending.
     *
     * The photo is optional: the server accepts the fields without one, and a
     * vendor with no photo to hand should not be blocked from submitting.
     */
    fun createVendorProduct(
        name: String,
        category: String,
        price: String,
        description: String,
        quantityType: String,
        image: okhttp3.MultipartBody.Part?,
        callback: (message: String?, error: ApiError?) -> Unit
    ) {
        fun text(value: String) =
            okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), value)

        apiService.createVendorProduct(
            name = text(name),
            category = text(category),
            price = text(price),
            description = text(description),
            quantityType = text(quantityType),
            image = image
        ).enqueueApi<CreateVendorProductResponse>("VendorRepo", "createVendorProduct") { body, error ->
            when {
                error != null -> callback(null, error)
                body?.success == true -> callback(body.message, null)
                else -> callback(null, ApiError.reported(body?.error))
            }
        }
    }

    /**
     * Adds a catalogue item to the vendor's inventory, or updates its price and
     * stock if already present — the endpoint upserts, so callers need not
     * check first.
     */
    fun addVendorProduct(
        productId: Int,
        stockQuantity: Int,
        price: Double?,
        callback: (Boolean, ApiError?) -> Unit
    ) {
        apiService.addVendorProduct(
            AddVendorProductRequest(
                productId = productId,
                stockQuantity = stockQuantity,
                price = price
            )
        ).enqueueApi<BaseResponse>("VendorRepo", "addVendorProduct") { body, error ->
            when {
                error != null -> callback(false, error)
                body?.success == true -> callback(true, null)
                else -> callback(false, ApiError.reported(body?.error))
            }
        }
    }

    /** Removes a product from the vendor's inventory. */
    fun removeVendorProduct(productId: Int, callback: (Boolean, ApiError?) -> Unit) {
        apiService.removeVendorProduct(productId = productId)
            .enqueueApi<BaseResponse>("VendorRepo", "removeVendorProduct") { body, error ->
                when {
                    error != null -> callback(false, error)
                    body?.success == true -> callback(true, null)
                    else -> callback(false, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * A vendor's own surplus listings.
     *
     * Note the server also filters out anything sold out or past its expiry, so
     * this cannot show a vendor their expired listings — and `status` selects
     * exactly one state, so pending and approved need separate calls.
     */
    fun getListings(
        vendorId: Int,
        status: String = "approved",
        callback: (List<SurplusListing>?, ApiError?) -> Unit
    ) {
        apiService.getVendorSurplusListings(vendorId = vendorId, status = status)
            .enqueueApi<SurplusListingsResponse>("VendorRepo", "getListings") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.listings ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * Submits a new surplus listing. The server sets status='pending' and
     * computes the discounted price itself, so no price is sent.
     *
     * Rejects locally on the 30–70% discount rule rather than making a round
     * trip to be told the same thing.
     */
    fun createListing(
        request: CreateSurplusListingRequest,
        callback: (SurplusListing?, ApiError?) -> Unit
    ) {
        if (request.discountPercent !in CreateSurplusListingRequest.DISCOUNT_RANGE) {
            callback(null, ApiError.Reported("Discount must be between 30% and 70%."))
            return
        }

        apiService.createVendorSurplusListing(listing = request)
            .enqueueApi<SurplusListingResponse>("VendorRepo", "createListing") { body, error ->
                when {
                    error != null -> callback(null, error)
                    // The server re-selects and returns the new row, but as a
                    // bare `surplus_listings` record with no joins — so
                    // productName/businessName are null here even though the
                    // list endpoint populates them.
                    body?.success == true -> callback(body.listing, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * The server can only change status, remaining_quantity and admin_notes —
     * not price, quantity or expiry, which is what the old signature accepted
     * and silently dropped.
     */
    fun updateListingQuantity(
        listingId: Int,
        remainingQuantity: Int,
        callback: (Boolean, ApiError?) -> Unit
    ) {
        apiService.updateVendorSurplusListing(
            UpdateSurplusListingRequest(
                listingId = listingId,
                remainingQuantity = remainingQuantity
            )
        ).enqueueApi<BaseResponse>("VendorRepo", "updateListingQuantity") { body, error ->
            when {
                error != null -> callback(false, error)
                body?.success == true -> callback(true, null)
                else -> callback(false, ApiError.reported(body?.error))
            }
        }
    }

    /** Soft delete — the server sets status='cancelled'. */
    fun deleteListing(listingId: Int, callback: (Boolean, ApiError?) -> Unit) {
        apiService.deleteVendorSurplusListing(listingId = listingId)
            .enqueueApi<BaseResponse>("VendorRepo", "deleteListing") { body, error ->
                when {
                    error != null -> callback(false, error)
                    body?.success == true -> callback(true, null)
                    else -> callback(false, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * Surplus orders for this vendor. These are [SurplusOrder]s, not catalogue
     * [com.techaus.afamfresh.models.Order]s — the backend exposes no per-vendor
     * view of ordinary orders.
     */
    fun getVendorOrders(vendorId: Int, callback: (List<SurplusOrder>?, ApiError?) -> Unit) {
        apiService.getVendorOrders(vendorId = vendorId)
            .enqueueApi<SurplusOrdersResponse>("VendorRepo", "getVendorOrders") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.orders ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /** Takes the USER id — the endpoint resolves the vendor from it. */
    fun getVendorProducts(userId: Int, callback: (List<VendorProduct>?, ApiError?) -> Unit) {
        apiService.getVendorProducts(userId = userId)
            .enqueueApi<VendorProductsResponse>("VendorRepo", "getVendorProducts") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.products ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }
}
