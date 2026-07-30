package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * ✅ VERIFIED against `api/surplus-listings.php` and the `surplus_listings`
 * table. The previous version of this file was inferred from the app's own
 * (wrong) ApiService field list, and not one of its names existed on the wire:
 *
 *   title        -> product_name      (joined from items.name)
 *   price        -> discounted_price
 *   quantity     -> surplus_quantity / remaining_quantity
 *   unit         -> quantitytype      (joined from items)
 *   expires_at   -> expiry_date
 *   vendor_name  -> business_name     (joined from vendors)
 *
 * The endpoint does `SELECT sl.*` plus a join, so these are raw column names.
 */
data class SurplusListing(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("vendor_id") val vendorId: Int = 0,
    @SerializedName("product_id") val productId: Int = 0,

    @SerializedName("original_price") val originalPrice: Double = 0.0,
    @SerializedName("discount_percent") val discountPercent: Double = 0.0,
    @SerializedName("discounted_price") val discountedPrice: Double = 0.0,

    @SerializedName("surplus_quantity") val surplusQuantity: Int = 0,
    @SerializedName("remaining_quantity") val remainingQuantity: Int = 0,

    /** datetime, e.g. "2026-08-04 18:00:00" — NOT an ISO-8601 instant. */
    @SerializedName("expiry_date") val expiryDate: String? = null,

    /** goodie_bag | final_days | bulk */
    @SerializedName("listing_type") val listingType: String? = null,

    /** pending | approved | rejected | cancelled */
    @SerializedName("status") val status: String = "pending",

    @SerializedName("description") val description: String? = null,

    /** excellent | good | fair */
    @SerializedName("condition_rating") val conditionRating: String? = null,

    @SerializedName("pickup_only") val pickupOnly: Boolean = false,
    @SerializedName("weight_per_unit_kg") val weightPerUnitKg: Double = 1.0,
    @SerializedName("is_weight_based") val isWeightBased: Boolean = true,
    @SerializedName("admin_notes") val adminNotes: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,

    // ----- joined columns, present on GET only -----
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("quantitytype") val unit: String? = null,
    @SerializedName("business_name") val businessName: String? = null,
    @SerializedName("vendor_location") val vendorLocation: String? = null,
    @SerializedName("vendor_fname") val vendorFirstName: String? = null,
    @SerializedName("vendor_lname") val vendorLastName: String? = null
) {
    /** What the UI should show as the listing's heading. */
    val displayTitle: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Surplus item"

    /** Prefer the business name; fall back to the vendor's personal name. */
    val vendorDisplayName: String?
        get() = businessName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(vendorFirstName, vendorLastName)
                .joinToString(" ").trim().takeIf { it.isNotBlank() }

    val isSoldOut: Boolean get() = remainingQuantity <= 0
    val isApproved: Boolean get() = status == "approved"
}

data class SurplusListingsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("listings") val listings: List<SurplusListing>? = null,
    // The endpoint reports failures as {"error": "..."} with no success flag,
    // so `success` must default to false rather than being non-null-required.
    @SerializedName("error") val error: String? = null
)

data class SurplusListingResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("listing") val listing: SurplusListing? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Body for POST `surplus-listings.php`. Read field-for-field from the PHP.
 *
 * `user_id` — not vendor_id: the endpoint looks the vendor up from the user and
 * requires `is_verified = TRUE`.
 *
 * The server rejects `discount_percent` outside 30–70 inclusive, and computes
 * `discounted_price` itself, so the app must not send a price.
 */
data class CreateSurplusListingRequest(
    @SerializedName("user_id") val userId: Int,
    @SerializedName("product_id") val productId: Int,
    @SerializedName("original_price") val originalPrice: Double,
    @SerializedName("discount_percent") val discountPercent: Double,
    @SerializedName("surplus_quantity") val surplusQuantity: Int,
    /** "YYYY-MM-DD HH:MM:SS" */
    @SerializedName("expiry_date") val expiryDate: String,
    @SerializedName("listing_type") val listingType: String = "goodie_bag",
    @SerializedName("description") val description: String = "",
    @SerializedName("condition_rating") val conditionRating: String = "good",
    @SerializedName("pickup_only") val pickupOnly: Boolean = false,
    @SerializedName("weight_per_unit_kg") val weightPerUnitKg: Double = 1.0,
    @SerializedName("is_weight_based") val isWeightBased: Boolean = true
) {
    companion object {
        val DISCOUNT_RANGE = 30.0..70.0
    }
}

/**
 * Body for PUT `surplus-listings.php`.
 *
 * ⚠️ The endpoint only updates status, remaining_quantity and admin_notes — it
 * cannot edit price, quantity or expiry, which is what the app's old
 * `updateVendorSurplusListing` tried to send.
 *
 * ⚠️ SECURITY: the PHP performs no ownership check, so any authenticated caller
 * can set any listing's status — including approving their own. This is
 * reported to the backend owner; the app only ever sends `remaining_quantity`.
 */
data class UpdateSurplusListingRequest(
    @SerializedName("listing_id") val listingId: Int,
    @SerializedName("remaining_quantity") val remainingQuantity: Int? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("admin_notes") val adminNotes: String? = null
)
