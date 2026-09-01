import Foundation

// ===========================================================================
// CUSTOMER FLAVOUR API
//
// Sits ON TOP of AfamFreshAPI (the shared core) rather than replacing it.
// Auth, session and User handling stay in core because all three flavours
// need them identically; only the endpoints below are customer-specific.
//
// A RiderAPI or VendorAPI would be a sibling of this file — same shape,
// different endpoints — with nothing in core changing. That's the whole point
// of the split.
//
// Note the server gates these itself: orders.php calls
// requireAccountType($dbh, $user_id, 'customer'), so signing in with a rider
// account and calling listOrders() gets refused server-side. Don't rely on the
// client to enforce it.
// ===========================================================================

final class CustomerAPI {
    /// The shared core — auth, session, cookies live here.
    let core: AfamFreshAPI

    init(environment: Environment = .production) {
        self.core = AfamFreshAPI(
            baseURL: environment.baseURL,
            appRole: CustomerFlavour.role
        )
    }

    // Convenience passthroughs so callers don't reach into core for auth.
    func login(email: String, password: String) async throws -> LoginResponse {
        try await core.login(email: email, password: password)
    }

    func register(name: String, email: String, password: String, mobile: String? = nil) async throws -> RegisterResponse {
        try await core.register(name: name, email: email, password: password, mobile: mobile)
    }

    func currentUser() async throws -> UserResponse {
        try await core.getCurrentUser()
    }

    func logout() async throws -> BaseResponse {
        try await core.logout()
    }

    // MARK: - Products

    /// GET products.php?action=list
    /// Returns only the catalogue: vendor-owned and unapproved rows are
    /// excluded server-side, the same rule checkout enforces.
    func listProducts() async throws -> [Product] {
        let response: ProductsResponse = try await core.request("products.php", action: "list")
        guard response.success else {
            throw APIError.server(response.error ?? "Could not load products")
        }
        return response.products ?? []
    }

    /// GET products.php?action=detail&id=…
    func productDetail(id: Int) async throws -> Product? {
        let response: ProductResponse = try await core.request(
            "products.php", action: "detail", query: ["id": String(id)]
        )
        guard response.success else {
            throw APIError.server(response.error ?? "Could not load product")
        }
        return response.product
    }

    /// Client-side filter helpers matching what the Android home screen shows.
    func homepageProducts() async throws -> [Product] {
        try await listProducts().filter { $0.isOnHomepage }
    }

    func weeklyDeals() async throws -> [Product] {
        try await listProducts().filter { $0.isWeeklyDeal }
    }

    // MARK: - Orders

    /// GET orders.php?action=list — the signed-in customer's order history.
    func listOrders() async throws -> [Order] {
        let response: OrdersResponse = try await core.request("orders.php", action: "list")
        guard response.success else {
            throw APIError.server(response.error ?? "Could not load orders")
        }
        return response.orders ?? []
    }

    /// GET orders.php?action=detail&id=… — includes line items.
    func orderDetail(id: Int) async throws -> Order? {
        let response: OrderResponse = try await core.request(
            "orders.php", action: "detail", query: ["id": String(id)]
        )
        guard response.success else {
            throw APIError.server(response.error ?? "Could not load order")
        }
        return response.order
    }

    /// POST orders.php?action=create
    ///
    /// ⚠️ Deliberately sends ONLY product_id and quantity per line. The total,
    /// delivery cost and per-item prices are NOT sent and would be ignored:
    /// the endpoint computes all of them from the database. It used to trust
    /// the client, which meant a crafted POST could buy real products at any
    /// price it claimed. Don't reintroduce those fields.
    func createOrder(
        lines: [CartLine],
        deliveryAddress: String,
        area: String,
        mobile: String,
        recipientName: String? = nil,
        deliveryLat: Double? = nil,
        deliveryLng: Double? = nil,
        scheduledDate: String? = nil,
        scheduledSlot: String? = nil,
        notes: String? = nil
    ) async throws -> CreateOrderResponse {
        var body: [String: Any] = [
            "items": lines.map { ["product_id": $0.productId, "quantity": $0.quantity] },
            "delivery_address": deliveryAddress,
            "area": area,
            "mobile": mobile
        ]
        if let recipientName = recipientName { body["recipient_name"] = recipientName }
        if let lat = deliveryLat { body["delivery_lat"] = lat }
        if let lng = deliveryLng { body["delivery_lng"] = lng }
        if let d = scheduledDate { body["scheduled_delivery_date"] = d }
        if let s = scheduledSlot { body["scheduled_delivery_slot"] = s }
        if let n = notes { body["order_notes"] = n }

        return try await core.requestRawBody("orders.php", action: "create", body: body)
    }

    /// POST orders.php?action=cancel — allowed only while the order is still
    /// editable (see Order.isEditable). The server re-checks.
    func cancelOrder(id: Int) async throws -> BaseResponse {
        try await core.requestRawBody("orders.php", action: "cancel", body: ["order_id": id])
    }

    // MARK: - Addresses

    /// GET addresses.php?action=list
    func listAddresses() async throws -> [Address] {
        let response: AddressesResponse = try await core.request("addresses.php", action: "list")
        guard response.success else {
            throw APIError.server(response.error ?? "Could not load addresses")
        }
        return response.addresses ?? []
    }

    /// POST addresses.php?action=create|update.
    /// A blank id means create — the server assigns the real one, which is why
    /// the Android repository returns "" from newId().
    func saveAddress(_ address: Address) async throws -> SaveAddressResponse {
        let action = address.id.isEmpty ? "create" : "update"
        var body: [String: Any] = [
            "label": address.label,
            "recipient_name": address.recipientName,
            "phone": address.phone,
            "area": address.area,
            "address_line": address.addressLine,
            "is_default": address.isDefault
        ]
        if !address.id.isEmpty { body["id"] = address.id }
        if let lat = address.lat { body["lat"] = lat }
        if let lng = address.lng { body["lng"] = lng }

        return try await core.requestRawBody("addresses.php", action: action, body: body)
    }

    func deleteAddress(id: String) async throws -> BaseResponse {
        try await core.requestRawBody("addresses.php", action: "delete", body: ["id": id])
    }

    /// Marks one default and clears the flag on all others, server-side.
    func setDefaultAddress(id: String) async throws -> BaseResponse {
        try await core.requestRawBody("addresses.php", action: "set_default", body: ["id": id])
    }
}
