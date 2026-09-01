import Foundation

// ===========================================================================
// CUSTOMER FLAVOUR MODELS
// Mirrors models/Product.kt and the row shapes api/api/orders.php returns.
//
// ⚠️ items.price is VARCHAR(50) in the schema, so it may arrive as "12500"
// rather than 12500. Gson coerces silently; Swift's JSONDecoder does not, and
// a throw here kills the WHOLE product list. FlexibleDouble handles it.
// (Worth flagging to whoever owns the DB: that column wants DECIMAL(12,2).
// One row entered as "12,500" would still break both platforms.)
// ===========================================================================

/// Decodes a number that may arrive as a JSON number or a quoted string.
@propertyWrapper
struct FlexibleDouble: Codable {
    var wrappedValue: Double

    init(wrappedValue: Double) { self.wrappedValue = wrappedValue }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let d = try? c.decode(Double.self) { wrappedValue = d }
        else if let s = try? c.decode(String.self) {
            // Tolerate thousands separators and stray unit suffixes rather
            // than throwing and taking out the entire list.
            let cleaned = s.filter { $0.isNumber || $0 == "." || $0 == "-" }
            wrappedValue = Double(cleaned) ?? 0
        }
        else { wrappedValue = 0 }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(wrappedValue)
    }
}

/// Same, for integer columns that may be quoted.
@propertyWrapper
struct FlexibleIntOptional: Codable {
    var wrappedValue: Int?

    init(wrappedValue: Int?) { self.wrappedValue = wrappedValue }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { wrappedValue = nil }
        else if let i = try? c.decode(Int.self) { wrappedValue = i }
        else if let s = try? c.decode(String.self) { wrappedValue = Int(s) }
        else { wrappedValue = nil }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(wrappedValue)
    }
}

// MARK: - Product

/// api/products.php does SELECT * FROM items and json_encodes the rows with no
/// normalisation, so this shape IS the `items` table.
struct Product: Codable, Identifiable {
    @FlexibleIntOptional var idRaw: Int?
    var id: Int { idRaw ?? 0 }

    var name: String
    var description: String?
    var category: String?
    @FlexibleDouble var price: Double

    /// The raw items.image column — a bare filename like "apple.png".
    /// Not loadable on its own; use imageUrl to display.
    var image: String?

    /// Absolute URL built server-side by includes/product_image.php.
    /// Null when the file is missing from disk, which is most of the current
    /// catalogue — the signal to draw a placeholder, not to retry a 404.
    var imageUrl: String?

    /// Numeric part of the pack size, e.g. "500" — VARCHAR(11) upstream.
    var quantity: String?
    /// Unit of sale: "Kg", "Grams", "Unit". Column is `quantitytype`.
    var unit: String?

    @FlexibleDouble var discountPercent: Double
    @FlexibleIntOptional var stockQty: Int?
    var freshnessDate: String?

    // enum('YES','NO') flags — kept as strings because that's the column type.
    var weeklyDeal: String?
    var offer: String?
    var homepage: String?

    enum CodingKeys: String, CodingKey {
        case idRaw = "id"
        case name, description, category, price, image, quantity, offer, homepage
        case imageUrl = "image_url"
        case unit = "quantitytype"
        case discountPercent = "discount"
        case stockQty = "stock_qty"
        case freshnessDate = "freshness_date"
        case weeklyDeal = "weekly_deal"
    }

    /// Stock isn't tracked yet — every catalogue row sits at the column default
    /// of 999 — so an unreported quantity must NOT block the sale. Only an
    /// explicit 0 or less counts as a stockout. Getting this wrong on Android
    /// disabled Add to Cart across the whole catalogue.
    var inStock: Bool { (stockQty ?? Int.max) > 0 }

    var isWeeklyDeal: Bool { weeklyDeal?.isYes ?? false }
    var isOffer: Bool { offer?.isYes ?? false }
    var isOnHomepage: Bool { homepage?.isYes ?? false }

    var hasDiscount: Bool { discountPercent > 0 }

    /// What the customer actually pays. Mirrors the server's own calculation in
    /// orders.php, so the charge matches what the listing displayed.
    var effectivePrice: Double {
        hasDiscount ? price * (1 - discountPercent / 100) : price
    }

    /// e.g. "500 Grams", or just "Kg" when no quantity is recorded.
    var packLabel: String? {
        let q = quantity?.trimmingCharacters(in: .whitespaces)
            .nonEmpty.flatMap { $0 == "0" ? nil : $0 }
        let u = unit?.trimmingCharacters(in: .whitespaces).nonEmpty
        if let q = q, let u = u { return "\(q) \(u)" }
        return u ?? q
    }
}

private extension String {
    var isYes: Bool { trimmingCharacters(in: .whitespaces).uppercased() == "YES" }
    var nonEmpty: String? { isEmpty ? nil : self }
}

struct ProductsResponse: Codable {
    let success: Bool
    let products: [Product]?
    let error: String?
}

struct ProductResponse: Codable {
    let success: Bool
    let product: Product?
    let error: String?
}

// MARK: - Cart (client-side only)
//
// There is no cart endpoint. The cart lives on the device and is turned into
// an order at checkout — and note that orders.php trusts ONLY product_id and
// quantity from the client. Price, delivery fee and the total are all computed
// server-side, because a raw POST with a fabricated total once bought real
// products at any price the request claimed.

struct CartLine: Codable, Identifiable {
    let productId: Int
    var quantity: Int
    /// Kept for display only. Never sent as the price to charge.
    let productName: String
    let unitPrice: Double

    var id: Int { productId }
    var lineTotal: Double { unitPrice * Double(quantity) }
}

/// The only thing the server accepts per line.
struct OrderLineRequest: Codable {
    let productId: Int
    let quantity: Int

    enum CodingKeys: String, CodingKey {
        case productId = "product_id"
        case quantity
    }
}

// MARK: - Orders

struct OrderItem: Codable {
    @FlexibleIntOptional var productId: Int?
    var productName: String?
    @FlexibleIntOptional var quantity: Int?
    @FlexibleDouble var price: Double

    enum CodingKeys: String, CodingKey {
        case productId = "product_id"
        case productName = "product_name"
        case quantity, price
    }
}

/// Rows from `orders`, as mapped by orders.php.
/// `status` is free text (varchar, not an enum): "Received", "Awaiting
/// Payment", "Preparing", "Out for Delivery", "Delivered", "Cancelled", etc.
struct Order: Codable, Identifiable {
    // ⚠️ These are the keys the ENDPOINT emits, not the `orders` table columns.
    // mapOrderRow() in orders.php deliberately normalises the legacy column
    // names so clients don't have to learn them:
    //     orderid      -> id      (and cast to a STRING)
    //     total_amount -> total
    //     ordertime    -> created_at
    // Modelling the raw columns instead is what broke decoding here.
    @FlexibleIntOptional var idRaw: Int?
    var id: Int { idRaw ?? 0 }

    var status: String?
    var currentStatus: String?
    var paymentStatus: String?
    @FlexibleDouble var total: Double
    var createdAt: String?

    var fname: String?
    var lname: String?
    var mobile: String?
    var area: String?
    var address: String?
    var deliveryAddress: String?

    @FlexibleDouble var deliveryFee: Double
    @FlexibleDouble var serviceFee: Double
    @FlexibleDouble var insuranceFee: Double
    @FlexibleDouble var processingFee: Double
    @FlexibleDouble var smallOrderSurcharge: Double

    var scheduledDeliveryDate: String?
    var scheduledDeliverySlot: String?
    var cancelledAt: String?
    var deliveredAt: String?

    /// The RIDER's attestation that they delivered it — not the customer's
    /// confirmation. Used together with completedAt to decide whether to offer
    /// "Confirm & Rate": delivered by the rider, not yet confirmed by you.
    @FlexibleBool var deliveryConfirmed: Bool?
    var completedAt: String?
    @FlexibleIntOptional var customerRating: Int?

    /// Always present on list (possibly empty); populated on detail.
    var items: [OrderItem]?

    // Detail action only — mapOrderRow() doesn't include these; the detail
    // branch attaches them afterwards.
    var deliveryPerson: String?
    var estimatedDelivery: String?
    var destLat: Double?
    var destLng: Double?

    enum CodingKeys: String, CodingKey {
        case status, mobile, area, address, items, total
        case idRaw = "id"
        case currentStatus = "current_status"
        case paymentStatus = "payment_status"
        case createdAt = "created_at"
        case fname, lname
        case deliveryAddress = "delivery_address"
        case deliveryFee = "delivery_fee"
        case serviceFee = "service_fee"
        case insuranceFee = "insurance_fee"
        case processingFee = "processing_fee"
        case smallOrderSurcharge = "small_order_surcharge"
        case scheduledDeliveryDate = "scheduled_delivery_date"
        case scheduledDeliverySlot = "scheduled_delivery_slot"
        case cancelledAt = "cancelled_at"
        case deliveredAt = "delivered_at"
        case deliveryConfirmed = "delivery_confirmed"
        case completedAt = "completed_at"
        case customerRating = "customer_rating"
        case deliveryPerson = "delivery_person"
        case estimatedDelivery = "estimated_delivery"
        case destLat = "dest_lat"
        case destLng = "dest_lng"
    }

    /// Kept as an alias so callers reading `totalAmount` still work; the wire
    /// key is `total`.
    var totalAmount: Double { total }

    /// Matches isOrderEditable() in orders.php. Free-text status, so this
    /// matches a known-good list rather than excluding terminal values.
    var isEditable: Bool {
        let editable = ["received", "pending", "awaiting payment",
                        "awaiting confirmation", "preparing"]
        let s = (status ?? "").lowercased().trimmingCharacters(in: .whitespaces)
        return editable.contains(s)
    }

    var isCancelled: Bool { cancelledAt != nil }
    var isDelivered: Bool { deliveredAt != nil }

    /// Rider says delivered, customer hasn't confirmed yet.
    var awaitingCustomerConfirmation: Bool {
        (deliveryConfirmed ?? false) && completedAt == nil
    }
}

struct OrdersResponse: Codable {
    let success: Bool
    let orders: [Order]?
    let error: String?
}

struct OrderResponse: Codable {
    let success: Bool
    let order: Order?
    let error: String?
}

struct CreateOrderResponse: Codable {
    let success: Bool
    @FlexibleIntOptional var orderId: Int?
    @FlexibleDouble var total: Double
    let error: String?

    enum CodingKeys: String, CodingKey {
        case success, total, error
        case orderId = "order_id"
    }
}

// MARK: - Addresses
// api/addresses.php — list/create/update/delete/set_default, session-scoped to
// the signed-in customer. The server assigns ids; a blank id means "create".

struct Address: Codable, Identifiable {
    var id: String
    /// What the customer calls it — "Home", "Work".
    var label: String
    var recipientName: String
    var phone: String
    var area: String
    var addressLine: String
    var isDefault: Bool

    /// Set only when pinned on a map. Nullable because a typed-in address has
    /// no coordinates, and inventing them produces a wrong delivery quote.
    var lat: Double?
    var lng: Double?

    enum CodingKeys: String, CodingKey {
        case id, label, phone, area, lat, lng
        case recipientName = "recipient_name"
        case addressLine = "address_line"
        case isDefault = "is_default"
    }

    var summary: String {
        [addressLine, area].filter { !$0.isEmpty }.joined(separator: ", ")
    }
}

struct AddressesResponse: Codable {
    let success: Bool
    let addresses: [Address]?
    let error: String?
}

struct SaveAddressResponse: Codable {
    let success: Bool
    let address: Address?
    let error: String?
}
