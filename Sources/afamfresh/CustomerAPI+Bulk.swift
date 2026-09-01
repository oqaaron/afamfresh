import Foundation

// ===========================================================================
// CUSTOMER × BULK MARKETPLACE
//
// The customer side only: browsing listings and placing/tracking Bulk orders.
// Creating a listing is a VENDOR action and belongs in a future VendorAPI —
// Bulk-listings.php refuses it unless the caller is a verified vendor, so
// putting it here would be a method that can never succeed.
//
// An extension rather than more lines in CustomerAPI.swift, so the core
// customer surface stays readable and this can be lifted out wholesale if the
// marketplace is ever split into its own module.
// ===========================================================================

extension CustomerAPI {

    // MARK: - Browsing

    /// GET Bulk-listings.php — the marketplace.
    ///
    /// Filter client-side with `.isApproved` and `.isSoldOut` unless the server
    /// is already excluding those; the endpoint's default scope isn't something
    /// I could confirm, so check the first response before trusting it.
    func listBulkListings(
        vendorId: Int? = nil,
        listingType: String? = nil,
        limit: Int = 50,
        offset: Int = 0
    ) async throws -> [BulkListing] {
        var query: [String: String] = [
            "limit": String(limit),
            "offset": String(offset)
        ]
        if let vendorId = vendorId { query["vendor_id"] = String(vendorId) }
        if let listingType = listingType { query["listing_type"] = listingType }

        let response: BulkListingsResponse = try await core.request(
            "Bulk-listings.php", query: query
        )
        // Failures come back as {"error": "..."} with no success flag, so a
        // present error is the reliable signal here — not success == false.
        if let error = response.error {
            throw APIError.server(error)
        }
        return response.listings ?? []
    }

    /// Surplus markdowns only — excludes wholesale lots.
    func surplusListings() async throws -> [BulkListing] {
        try await listBulkListings().filter { !$0.isWholesale && $0.isApproved && !$0.isSoldOut }
    }

    /// Wholesale lots only.
    func wholesaleListings() async throws -> [BulkListing] {
        try await listBulkListings().filter { $0.isWholesale && $0.isApproved && !$0.isSoldOut }
    }

    // MARK: - Ordering

    /// POST Bulk-orders.php
    ///
    /// ⚠️ Sends no user_id. The server takes the buyer from the session —
    /// it used to read user_id from the body, which meant anyone could place
    /// an order in someone else's name, against their account, to their
    /// delivery address.
    ///
    /// `quantity` may be fractional for weight-based listings. The server
    /// reserves stock against the listing inside a transaction, and an
    /// abandoned checkout is released after ~30 minutes by
    /// releaseStaleBulkReservations().
    ///
    /// An out-of-service-area pin is refused before the transaction opens, so
    /// an impossible order never touches stock.
    func createBulkOrder(
        listingId: Int,
        quantity: Double,
        deliveryAddress: String,
        deliveryArea: String,
        recipientName: String? = nil,
        recipientPhone: String? = nil,
        deliveryLat: Double? = nil,
        deliveryLng: Double? = nil,
        scheduledDate: String? = nil,
        scheduledSlot: String? = nil,
        notes: String? = nil
    ) async throws -> CreateBulkOrderResponse {
        var body: [String: Any] = [
            "listing_id": listingId,
            "quantity": quantity,
            "delivery_address": deliveryAddress,
            "delivery_area": deliveryArea
        ]
        // Left absent rather than sent empty when unknown: rider_dispatch.php
        // COALESCEs to the buyer, and that fallback should live in one place.
        if let name = recipientName, !name.isEmpty { body["recipient_name"] = name }
        if let phone = recipientPhone, !phone.isEmpty { body["recipient_phone"] = phone }
        if let lat = deliveryLat { body["delivery_lat"] = lat }
        if let lng = deliveryLng { body["delivery_lng"] = lng }
        if let date = scheduledDate { body["scheduled_delivery_date"] = date }
        if let slot = scheduledSlot { body["scheduled_delivery_slot"] = slot }
        if let notes = notes { body["order_notes"] = notes }

        return try await core.requestRawBody("Bulk-orders.php", body: body)
    }

    /// GET Bulk-orders.php — the signed-in customer's Bulk orders.
    func listBulkOrders(status: String? = nil, limit: Int = 50, offset: Int = 0) async throws -> [BulkOrder] {
        var query: [String: String] = [
            "limit": String(limit),
            "offset": String(offset)
        ]
        if let status = status { query["status"] = status }

        let response: BulkOrdersResponse = try await core.request("Bulk-orders.php", query: query)
        if let error = response.error { throw APIError.server(error) }
        return response.orders ?? []
    }

    /// POST Bulk-orders.php?action=confirm_receipt (form-encoded)
    ///
    /// The customer's confirm-and-rate step, allowed only after the order is
    /// marked delivered and only once — a second call is refused.
    ///
    /// Ratings are 1–5. The photo upload the Android version supports is a
    /// multipart field and isn't wired up here; add it when a screen needs it.
    func confirmBulkReceipt(
        orderId: Int,
        rating: Int? = nil,
        speedRating: Int? = nil,
        professionalismRating: Int? = nil,
        packagingRating: Int? = nil,
        feedback: String? = nil,
        emojiReaction: String? = nil
    ) async throws -> BaseResponse {
        if let rating = rating, !(1...5).contains(rating) {
            throw APIError.server("Rating must be between 1 and 5")
        }

        var fields: [String: String] = ["order_id": String(orderId)]
        if let rating = rating { fields["rating"] = String(rating) }
        if let speed = speedRating { fields["rating_speed"] = String(speed) }
        if let prof = professionalismRating { fields["rating_professionalism"] = String(prof) }
        if let pack = packagingRating { fields["rating_packaging"] = String(pack) }
        if let feedback = feedback, !feedback.isEmpty { fields["feedback"] = feedback }
        // An unrecognised emoji is silently dropped server-side, not an error.
        if let emoji = emojiReaction, !emoji.isEmpty { fields["emoji_reaction"] = emoji }

        return try await core.requestForm(
            "Bulk-orders.php", action: "confirm_receipt", fields: fields
        )
    }

    // MARK: - Payment passthroughs
    //
    // Convenience wrappers so a checkout screen doesn't have to remember which
    // OrderType a given order is. Getting this wrong charges the wrong order:
    // the shop and Bulk id spaces overlap.

    func payForShopOrder(
        orderId: Int,
        method: PaymentMethod = .mobileMoney,
        email: String? = nil,
        phone: String? = nil
    ) async throws -> PaymentResponse {
        try await core.initiatePayment(
            orderId: orderId, orderType: .shop, method: method, email: email, phone: phone
        )
    }

    func payForBulkOrder(
        orderId: Int,
        method: PaymentMethod = .mobileMoney,
        email: String? = nil,
        phone: String? = nil
    ) async throws -> PaymentResponse {
        try await core.initiatePayment(
            orderId: orderId, orderType: .bulk, method: method, email: email, phone: phone
        )
    }

    /// Poll after the customer returns from the payment page.
    /// See AfamFreshAPI.pollPaymentUntilSettled for why returning from a
    /// WebView is not evidence of payment.
    func confirmPayment(
        orderId: Int,
        orderType: OrderType = .shop
    ) async throws -> PaymentResponse {
        try await core.pollPaymentUntilSettled(orderId: orderId, orderType: orderType)
    }
}
