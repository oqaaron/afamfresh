package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * A delivery address the customer has saved.
 *
 * Field names carry @SerializedName already, even though this is currently
 * persisted locally as JSON in SharedPreferences. That is deliberate: when an
 * addresses endpoint exists, the same class serialises straight over the wire
 * with no changes here.
 *
 * [id] is generated on-device (a UUID) rather than by a server. A real backend
 * would assign its own ids; see AddressRepository for how that swap works.
 */
data class Address(
    @SerializedName("id") val id: String,

    /** What the customer calls it — "Home", "Work", "Mum's place". */
    @SerializedName("label") val label: String,

    @SerializedName("recipient_name") val recipientName: String,
    @SerializedName("phone") val phone: String,

    /** Neighbourhood / area, matching the `area` field checkout already sends. */
    @SerializedName("area") val area: String,

    /** Street address / directions. */
    @SerializedName("address_line") val addressLine: String,

    @SerializedName("is_default") val isDefault: Boolean = false,

    /**
     * Populated only if the address was pinned on the map. Kept nullable
     * because a typed-in address has no coordinates, and inventing them would
     * produce a wrong delivery quote.
     */
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null
) {
    /** One-line form for summaries and pickers. */
    val summary: String
        get() = listOf(addressLine, area).filter { it.isNotBlank() }.joinToString(", ")
}

data class AddressesResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("addresses") val addresses: List<Address>? = null,
    @SerializedName("error") val error: String? = null
)

data class SaveAddressResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("address") val address: Address? = null,
    @SerializedName("error") val error: String? = null
)
