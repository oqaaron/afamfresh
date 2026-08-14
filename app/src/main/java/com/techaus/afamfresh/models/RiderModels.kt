package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * Wire models for `api/rider.php`.
 *
 * EVERY field here is nullable with a null default, deliberately. Gson bypasses
 * Kotlin constructors and allocates with Unsafe, so a non-null field whose JSON
 * key is absent lands as null at runtime and blows up at first touch — the trap
 * that crashed login via `roles` and disabled Add to Cart via `stock_qty`.
 * Nullable makes "the server did not say" representable, and the derived
 * helpers below give the UI a non-null value to render.
 */

/** The signed-in rider's own record, from `?action=me`. */
data class RiderProfile(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("vehicle_type") val vehicleType: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("is_online") val isOnline: Boolean? = null,
    @SerializedName("avg_rating") val avgRating: Double? = null,
    @SerializedName("total_ratings") val totalRatings: Int? = null,
    @SerializedName("delivered_today") val deliveredToday: Int? = null,
    @SerializedName("active_count") val activeCount: Int? = null
) {
    val online: Boolean get() = isOnline ?: (status == "online")
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Rider"
    val vehicleOrDash: String get() = vehicleType?.takeIf { it.isNotBlank() } ?: "—"
}

data class RiderProfileResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("rider") val rider: RiderProfile? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * One assigned delivery.
 *
 * [status] is the rider-facing state from `rider_assignments`; [orderStatus] is
 * the customer-facing label on `orders`. They are written together server-side
 * and are not independently editable here.
 */
data class Delivery(
    @SerializedName("assignment_id") val assignmentId: Int? = null,
    @SerializedName("order_id") val orderId: Int? = null,

    /**
     * Which table [orderId] refers to: "order" for the shop, "Bulk" for a
     * bulk Bulk order collected from a vendor.
     *
     * Must be sent back on every call about this delivery. The two id spaces
     * OVERLAP — shop order 41 and Bulk order 41 both exist — so an id alone
     * can resolve to a different customer's job entirely.
     *
     * Defaults to "order" so a response from an older server, which does not
     * send this field, still behaves exactly as it did.
     */
    @SerializedName("source") val source: String = "order",

    @SerializedName("status") val status: String? = null,
    @SerializedName("order_status") val orderStatus: String? = null,
    @SerializedName("payment_status") val paymentStatus: String? = null,
    @SerializedName("customer_name") val customerName: String? = null,
    @SerializedName("customer_phone") val customerPhone: String? = null,
    @SerializedName("area") val area: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("dest_lat") val destLat: Double? = null,
    @SerializedName("dest_lng") val destLng: Double? = null,
    @SerializedName("total_amount") val totalAmount: Double? = null,
    @SerializedName("delivery_fee") val deliveryFee: Double? = null,
    @SerializedName("assigned_at") val assignedAt: String? = null,
    @SerializedName("delivered_at") val deliveredAt: String? = null,
    @SerializedName("ordertime") val orderTime: String? = null,
    @SerializedName("scheduled_date") val scheduledDate: String? = null,
    @SerializedName("scheduled_slot") val scheduledSlot: String? = null,
    @SerializedName("has_proof_photo") val hasProofPhoto: Boolean? = null,
    @SerializedName("proof_photo_url") val proofPhotoUrl: String? = null,

    /**
     * Where the rider COLLECTS from. Null for shop orders, which all leave the
     * warehouse; a Bulk job is collected from the vendor's own premises, and
     * a rider sent to the warehouse for one would find nothing there.
     */
    @SerializedName("pickup_address") val pickupAddress: String? = null,

    /** Set on collection-only Bulk orders. Detail view only. */
    @SerializedName("pickup_code") val pickupCode: String? = null,

    @SerializedName("items") val items: List<DeliveryItem>? = null
) {
    val customerOrDash: String get() = customerName?.takeIf { it.isNotBlank() } ?: "Customer"
    val addressOrDash: String get() = address?.takeIf { it.isNotBlank() } ?: "No address given"

    /** A bulk Bulk job rather than an ordinary shop delivery. */
    val isBulk: Boolean get() = source == "Bulk"

    /** Human label for the rider's own progress. */
    val stageLabel: String
        get() = when (status) {
            "assigned" -> "Assigned"
            "picked_up" -> "Picked up"
            "on_way" -> "On the way"
            "delivered" -> "Delivered"
            else -> status?.replaceFirstChar { it.uppercase() } ?: "Assigned"
        }

    val isDelivered: Boolean get() = status == "delivered"

    /**
     * The next stage, or null when finished.
     *
     * Mirrors the server's forward-only flow in `api/rider.php`; the server is
     * still the authority and refuses anything out of order, so this only
     * decides what the button offers.
     */
    val nextStage: String?
        get() = when (status) {
            "assigned" -> "picked_up"
            "picked_up" -> "on_way"
            "on_way" -> "delivered"
            else -> null
        }

    val nextStageLabel: String?
        get() = when (nextStage) {
            "picked_up" -> "Mark picked up"
            "on_way" -> "Start delivery"
            "delivered" -> "Mark delivered"
            else -> null
        }
}

data class DeliveryItem(
    @SerializedName("name") val name: String? = null,
    /**
     * Decimal, not whole units. A Bulk line is ordered in kilograms and can
     * be 20.5; as an Int, Gson truncated it and showed a rider a load lighter
     * than the one they have to carry.
     */
    @SerializedName("quantity") val quantity: Double? = null,
    @SerializedName("price") val price: Double? = null
) {
    /** [price] is per unit on both kinds of delivery, so this holds for both. */
    val lineTotal: Double get() = (price ?: 0.0) * (quantity ?: 0.0)

    /** Whole numbers without a trailing ".0"; decimals to one place. */
    val quantityLabel: String
        get() = (quantity ?: 0.0).let {
            if (it % 1.0 == 0.0) it.toLong().toString() else String.format("%.1f", it)
        }
}

data class DeliveriesResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("active") val active: List<Delivery>? = null,
    @SerializedName("history") val history: List<Delivery>? = null,
    @SerializedName("error") val error: String? = null
)

data class DeliveryDetailResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("delivery") val delivery: Delivery? = null,
    @SerializedName("error") val error: String? = null
)

data class DutyStatusResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("status") val status: String? = null,
    @SerializedName("is_online") val isOnline: Boolean? = null,
    @SerializedName("error") val error: String? = null
)

data class UpdateDeliveryStatusResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("status") val status: String? = null,
    @SerializedName("order_status") val orderStatus: String? = null,
    @SerializedName("error") val error: String? = null,
    // "CASH_NOT_CONFIRMED" tells the app to open the confirm-collection sheet
    // rather than showing this as a generic failure. See api/rider.php.
    @SerializedName("code") val code: String? = null
)

data class ProofUploadResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("proof_photo_url") val proofPhotoUrl: String? = null,
    @SerializedName("error") val error: String? = null
)

/** UI state for a rider action, mirroring [ProfileSaveState]. */
sealed class RiderActionState {
    object Idle : RiderActionState()
    object Working : RiderActionState()
    object Done : RiderActionState()
    data class Error(val message: String) : RiderActionState()

    /** The server refused to mark this delivered until cash is confirmed collected. */
    data class NeedsCashConfirm(val orderId: Int, val source: String, val amount: Double) : RiderActionState()
}

// =============================================================
// ROLE GATE
//
// The Rider and Vendor apps are separate installs, but installing one
// grants nothing: the account registers, files a request, and waits
// for an admin. These model api/roles.php, which is a thin wrapper
// over the workflow already in includes/roles.php.
// =============================================================

/** Whether this account may use this app yet. */
enum class RoleGateState {
    /** Never asked. */
    None,
    Pending,
    Approved,
    Rejected;

    companion object {
        /** Wire values are lowercase strings from api/roles.php. */
        fun from(raw: String?): RoleGateState = when (raw) {
            "approved" -> Approved
            "pending" -> Pending
            "rejected" -> Rejected
            else -> None
        }
    }
}

data class RoleStatusResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("role") val role: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("can_request") val canRequest: Boolean? = null,
    /** Verbatim from canRequestRole() — explains why the button is unavailable. */
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("error") val error: String? = null
)

data class RoleRequestResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("state") val state: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

// =============================================================
// EARNINGS & PAYOUTS
//
// A rider earns from the MILEAGE component of delivery_fee only —
// service fee, insurance and processing cover the platform's own
// costs. api/rider.php credits rider_earnings automatically the
// moment a delivery is marked delivered (see update_status), net of
// RIDER_COMMISSION_RATE (10%). These models are read-only views over
// that ledger; nothing here computes money client-side.
// =============================================================

data class EarningsSummary(
    @SerializedName("today") val today: Double? = null,
    @SerializedName("this_week") val thisWeek: Double? = null,
    @SerializedName("all_time") val allTime: Double? = null,
    @SerializedName("available") val available: Double? = null,
    @SerializedName("delivery_count") val deliveryCount: Int? = null,
    @SerializedName("commission_rate") val commissionRate: Double? = null
)

/** One completed, paid-for delivery in the earnings ledger. */
data class EarningsEntry(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("order_id") val orderId: Int? = null,

    /**
     * "order" or "Bulk". Two entries can share an order id, so this is what
     * tells them apart in the ledger — and a Bulk entry is paid from the
     * Bulk delivery fee rather than a mileage component.
     */
    @SerializedName("source") val source: String = "order",

    @SerializedName("customer_name") val customerName: String? = null,
    @SerializedName("mileage_fee") val mileageFee: Double? = null,
    @SerializedName("commission_rate") val commissionRate: Double? = null,
    @SerializedName("commission_amount") val commissionAmount: Double? = null,
    @SerializedName("net_earnings") val netEarnings: Double? = null,
    /**
     * True when [mileageFee] was recomputed from stored coordinates rather
     * than the fee actually charged at order time — orders placed before
     * mileage_fee started being persisted have no exact figure. Shown as a
     * small "estimated" label, never hidden, since it is real money.
     */
    @SerializedName("is_estimated") val isEstimated: Boolean? = null,
    @SerializedName("is_paid") val isPaid: Boolean? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val customerOrDash: String get() = customerName?.takeIf { it.isNotBlank() } ?: "Customer"
}

data class EarningsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("summary") val summary: EarningsSummary? = null,
    @SerializedName("history") val history: List<EarningsEntry>? = null,
    @SerializedName("error") val error: String? = null
)

data class PayoutRequest(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("requested_at") val requestedAt: String? = null,
    @SerializedName("processed_at") val processedAt: String? = null,
    @SerializedName("notes") val notes: String? = null
) {
    val isPending: Boolean get() = status == "pending"
}

data class RequestPayoutResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("error") val error: String? = null
)

data class MyPayoutsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("payouts") val payouts: List<PayoutRequest>? = null,
    @SerializedName("error") val error: String? = null
)
