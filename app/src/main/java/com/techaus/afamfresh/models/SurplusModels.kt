package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

// ⚠️ INFERRED from ApiService.kt's vendor/surplus/listings.php field list
// (updateVendorSurplusListing's @Field names are confirmed from that file;
// the rest — id types, status values — are guessed).

data class SurplusListing(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("original_price") val originalPrice: Double,
    @SerializedName("price") val price: Double,
    @SerializedName("quantity") val quantity: Double,
    @SerializedName("unit") val unit: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("status") val status: String = "pending", // pending | approved | rejected | sold_out
    @SerializedName("image") val image: String? = null,
    @SerializedName("vendor_name") val vendorName: String? = null
)

data class SurplusListingsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("listings") val listings: List<SurplusListing>? = null,
    @SerializedName("error") val error: String? = null
)

data class SurplusListingResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("listing") val listing: SurplusListing? = null,
    @SerializedName("error") val error: String? = null
)
