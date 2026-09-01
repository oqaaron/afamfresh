import Foundation

// ===========================================================================
// BULK LISTINGS + BULK ORDERS
//
// A separate marketplace from the main shop: vendors list surplus stock at a
// markdown, wholesalers list bulk lots at a quoted price. Customers browse and
// buy here; the goods are collected from the vendor rather than picked from
// shop inventory.
//
// ⚠️ SEPARATE ID SPACE. Bulk_orders.id and orders.orderid OVERLAP — Bulk order
// 41 and shop order 41 both exist and belong to different customers. Every
// call about a Bulk order must carry its type explicitly (OrderType.bulk for
// payment.php, "Bulk" for tracking.php). An id alone can resolve to someone
// else's job entirely.
//
// ⚠️ Field coverage here is good but not exhaustive — Bulk-listings.php
// returns a JOIN across Bulk_listings, items, and vendors, and I have modelled
// the columns confirmed in the Android models and the SQL. Unknown keys are
// ignored by JSONDecoder, so extra columns are harmless; if a field you need
// comes back nil, it needs adding here.
// ===========================================================================

struct BulkListing: Codable, Identifiable {
    @FlexibleIntOptional var idRaw: Int?
    var id: Int { idRaw ?? 0 }

    @FlexibleIntOptional var productId: Int?
    @FlexibleIntOptional var vendorId: Int?

    /// "goodie_bag" | "wholesale". Forced server-side from the seller's
    /// business_type on create, so this is authoritative, not a client guess.
    let listingType: String?

    /// pending | approved | rejected. Only approved listings are buyable.
    let status: String?

    @FlexibleDouble var originalPrice: Double
    @FlexibleDouble var discountPercent: Double
    @FlexibleDouble var discountedPrice: Double

    @FlexibleDouble var bulkQuantity: Double
    @FlexibleDouble var remainingQuantity: Double
    @FlexibleDouble var minOrderQuantity: Double

    let expiryDate: String?
    let listingDescription: String?
    /// excellent | good | fair
    let conditionRating: String?

    @FlexibleBool var pickupOnly: Bool?
    @FlexibleDouble var weightPerUnitKg: Double
    @FlexibleBool var isWeightBased: Bool?

    let adminNotes: String?
    let createdAt: String?

    // Joined columns, present on GET only.
    let productName: String?
    let category: String?
    let image: String?
    let unit: String?
    let businessName: String?
    let vendorLocation: String?
    let vendorFirstName: String?
    let vendorLastName: String?

    enum CodingKeys: String, CodingKey {
        case idRaw = "id"
        case status, category, image
        case productId = "product_id"
        case vendorId = "vendor_id"
        case listingType = "listing_type"
        case originalPrice = "original_price"
        case discountPercent = "discount_percent"
        case discountedPrice = "discounted_price"
        case bulkQuantity = "Bulk_quantity"
        case remainingQuantity = "remaining_quantity"
        case minOrderQuantity = "min_order_quantity"
        case expiryDate = "expiry_date"
        case listingDescription = "description"
        case conditionRating = "condition_rating"
        case pickupOnly = "pickup_only"
        case weightPerUnitKg = "weight_per_unit_kg"
        case isWeightBased = "is_weight_based"
        case adminNotes = "admin_notes"
        case createdAt = "created_at"
        case productName = "product_name"
        case unit = "quantitytype"
        case businessName = "business_name"
        case vendorLocation = "vendor_location"
        case vendorFirstName = "vendor_fname"
        case vendorLastName = "vendor_lname"
    }

    var displayTitle: String {
        productName.flatMap { $0.isEmpty ? nil : $0 } ?? "Bulk item"
    }

    /// Prefer the business name; fall back to the vendor's personal name.
    var vendorDisplayName: String? {
        if let business = businessName, !business.isEmpty { return business }
        let personal = [vendorFirstName, vendorLastName]
            .compactMap { $0 }
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespaces)
        return personal.isEmpty ? nil : personal
    }

    var isSoldOut: Bool { remainingQuantity <= 0 }
    var isApproved: Bool { status == "approved" }

    /// A wholesale offer rather than a surplus markdown.
    var isWholesale: Bool { listingType == "wholesale" }

    /// Only meaningful when a retail reference price was supplied. Surplus
    /// listings always carry both; check isWholesale if that distinction
    /// matters to the caller.
    var hasRetailReference: Bool {
        originalPrice > 0 && originalPrice > discountedPrice
    }

    /// Collected from the vendor rather than delivered — no delivery fee, and
    /// no rider is dispatched.
    var isPickupOnly: Bool { pickupOnly ?? false }
}

struct BulkListingsResponse: Codable {
    /// The endpoint reports failures as {"error": "..."} with NO success flag,
    /// so this must default rather than be required.
    let success: Bool?
    let listings: [BulkListing]?
    let error: String?
}

struct BulkListingResponse: Codable {
    let success: Bool?
    let listing: BulkListing?
    let message: String?
    let error: String?
}

// MARK: - Bulk orders

struct BulkOrder: Codable, Identifiable {
    @FlexibleIntOptional var idRaw: Int?
    var id: Int { idRaw ?? 0 }

    @FlexibleIntOptional var listingId: Int?
    @FlexibleIntOptional var userId: Int?

    /// pending | confirmed | delivered | cancelled | refunded
    let status: String?
    /// paid | pending_cash | failed | authorization_pending
    let paymentStatus: String?
    let paymentMethod: String?

    /// Can be fractional — bulk items are often sold by weight.
    @FlexibleDouble var quantity: Double
    @FlexibleDouble var totalWeightKg: Double
    @FlexibleDouble var totalAmount: Double
    @FlexibleDouble var deliveryFee: Double

    let deliveryAddress: String?
    let deliveryArea: String?
    let recipientName: String?
    let recipientPhone: String?

    let scheduledDeliveryDate: String?
    let scheduledDeliverySlot: String?
    let orderNotes: String?

    let createdAt: String?
    let confirmedAt: String?
    let deliveredAt: String?
    let completedAt: String?
    @FlexibleBool var deliveryConfirmed: Bool?

    // Joined columns.
    let productName: String?
    let image: String?
    let businessName: String?
    let vendorLocation: String?
    @FlexibleBool var pickupOnly: Bool?

    enum CodingKeys: String, CodingKey {
        case idRaw = "id"
        case status, quantity, image
        case listingId = "listing_id"
        case userId = "user_id"
        case paymentStatus = "payment_status"
        case paymentMethod = "payment_method"
        case totalWeightKg = "total_weight_kg"
        case totalAmount = "total_amount"
        case deliveryFee = "delivery_fee"
        case deliveryAddress = "delivery_address"
        case deliveryArea = "delivery_area"
        case recipientName = "recipient_name"
        case recipientPhone = "recipient_phone"
        case scheduledDeliveryDate = "scheduled_delivery_date"
        case scheduledDeliverySlot = "scheduled_delivery_slot"
        case orderNotes = "order_notes"
        case createdAt = "created_at"
        case confirmedAt = "confirmed_at"
        case deliveredAt = "delivered_at"
        case completedAt = "completed_at"
        case deliveryConfirmed = "delivery_confirmed"
        case productName = "product_name"
        case businessName = "business_name"
        case vendorLocation = "vendor_location"
        case pickupOnly = "pickup_only"
    }

    /// The payable figure is goods PLUS delivery, split across two columns —
    /// unlike a shop order, where total_amount is the whole thing. The server
    /// computes this itself in BulkPayableTotal(); this mirrors it for display
    /// only, and must never be sent as an amount to charge.
    var payableTotal: Double { totalAmount + deliveryFee }

    var isPaid: Bool { paymentStatus == "paid" }
    var isCash: Bool { paymentStatus == "pending_cash" }
    var isCancelled: Bool {
        ["cancelled", "refunded"].contains((status ?? "").lowercased())
    }
    /// Already confirmed and rated — confirm_receipt refuses a second call.
    var isCompleted: Bool { completedAt != nil }
}

struct BulkOrdersResponse: Codable {
    let success: Bool?
    let orders: [BulkOrder]?
    let error: String?
}

struct CreateBulkOrderResponse: Codable {
    let success: Bool?
    @FlexibleIntOptional var orderId: Int?
    @FlexibleDouble var totalAmount: Double
    @FlexibleDouble var deliveryFee: Double
    let message: String?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case success, message, error
        case orderId = "order_id"
        case totalAmount = "total_amount"
        case deliveryFee = "delivery_fee"
    }
}
