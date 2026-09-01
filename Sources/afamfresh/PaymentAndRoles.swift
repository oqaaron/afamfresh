import Foundation

// ===========================================================================
// PAYMENT + ROLES — shared across flavours, so these live on the core.
//
// Payment is shared because both shop orders and Bulk orders settle through
// the same endpoint. Roles is shared because it is the GATE the Rider and
// Vendor apps check on launch: installing one grants nothing until an admin
// approves the request.
// ===========================================================================

// MARK: - Order type
//
// ⚠️ Two different vocabularies for the same concept — do not interchange them.
// payment.php uses order_type: "shop" | "bulk".
// tracking.php and rider.php use source: "order" | "Bulk".
// The Android side keeps them as separate constants for exactly this reason.

enum OrderType: String {
    /// The `orders` table. The server's default.
    case shop
    /// The `Bulk_orders` table. Different columns, and a payable total split
    /// across two of them.
    case bulk

    /// The value tracking.php/rider.php expect for the same order.
    var trackingSource: String {
        switch self {
        case .shop: return "order"
        case .bulk: return "Bulk"
        }
    }
}

enum PaymentMethod: String {
    case mobileMoney = "mobile_money"
    case card
    /// Settles nothing now — marks the order pending_cash for collection on
    /// delivery. There is no Pesapal transaction to verify afterwards.
    case cash
}

// MARK: - Payment models

struct PaymentResponse: Codable {
    let success: Bool

    /// "paid", "pending", "failed", "pending_cash", "authorization_pending".
    let status: String?

    /// The only field that should gate showing "payment complete".
    let paid: Bool?

    let orderId: String?
    let transactionId: String?
    @FlexibleDouble var amount: Double
    let currency: String?

    /// Where to send the customer to pay. Present on a successful non-cash
    /// initiate. Both spellings are accepted because I could not confirm which
    /// key the live response uses — print the raw body on first run and drop
    /// whichever is nil.
    let redirectUrl: String?
    let paymentUrl: String?

    let message: String?
    let method: String?
    let description: String?

    /// UNAUTHENTICATED, ORDER_NOT_FOUND, PESAPAL_UNAVAILABLE,
    /// VERIFY_UNAVAILABLE, ORDER_CANCELLED, INVALID_AMOUNT, BAD_REQUEST.
    let errorCode: String?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case success, status, paid, amount, currency, message, method, description, error
        case orderId = "order_id"
        case transactionId = "transaction_id"
        case redirectUrl = "redirect_url"
        case paymentUrl = "payment_url"
        case errorCode = "error_code"
    }

    /// Whichever of the two URL keys the server actually sent.
    var checkoutURL: String? { redirectUrl ?? paymentUrl }

    /// True when the outcome is genuinely unknown rather than failed.
    /// Treat this as "retry", never as "payment failed" — telling a paying
    /// customer it failed is how you get paid twice.
    var isUnconfirmed: Bool {
        errorCode == "VERIFY_UNAVAILABLE" || errorCode == "PESAPAL_UNAVAILABLE"
    }
}

// MARK: - Roles models

/// Whether this account may use this app yet.
enum RoleGateState: String {
    case none
    case pending
    case approved
    case rejected

    init(raw: String?) {
        self = RoleGateState(rawValue: raw ?? "") ?? .none
    }
}

struct RoleStatusResponse: Codable {
    let success: Bool
    let role: String?
    /// approved | pending | rejected | none
    let state: String?
    let canRequest: Bool?
    /// Verbatim from canRequestRole() — the sentence to show when the button
    /// is unavailable, most usefully "you can only have one additional role".
    let reason: String?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case success, role, state, reason, error
        case canRequest = "can_request"
    }

    var gateState: RoleGateState { RoleGateState(raw: state) }
}

struct RoleRequestResponse: Codable {
    let success: Bool
    let state: String?
    let message: String?
    let error: String?
}

struct RoleRequestRecord: Codable, Identifiable {
    @FlexibleIntOptional var idRaw: Int?
    var id: Int { idRaw ?? 0 }
    let role: String?
    let status: String?
    let adminNotes: String?
    let createdAt: String?
    let processedAt: String?

    enum CodingKeys: String, CodingKey {
        case idRaw = "id"
        case role, status
        case adminNotes = "admin_notes"
        case createdAt = "created_at"
        case processedAt = "processed_at"
    }
}

struct MyRoleRequestsResponse: Codable {
    let success: Bool
    let requests: [RoleRequestRecord]?
    let error: String?
}

// MARK: - API surface

extension AfamFreshAPI {

    // MARK: Payment

    /// POST payment.php?action=initiate
    ///
    /// ⚠️ Sends NO amount. The server reads the payable total from the order
    /// row and ignores anything the client sends — a client-supplied amount
    /// once let a customer pay 100 UGX for a 100,000 UGX order.
    ///
    /// For `.cash` this settles immediately as pending_cash with no Pesapal
    /// transaction. For the others, follow `checkoutURL` and then poll
    /// `verifyPayment` — do not treat reaching the callback URL as payment.
    func initiatePayment(
        orderId: Int,
        orderType: OrderType = .shop,
        method: PaymentMethod = .mobileMoney,
        email: String? = nil,
        phone: String? = nil
    ) async throws -> PaymentResponse {
        var body: [String: Any] = [
            "order_id": orderId,
            "payment_method": method.rawValue
        ]
        if let email = email { body["email"] = email }
        if let phone = phone { body["phone"] = phone }

        return try await requestRawBody(
            "payment.php",
            action: "initiate",
            query: ["order_type": orderType.rawValue],
            body: body
        )
    }

    /// POST payment.php?action=verify — asks the server to re-check with
    /// Pesapal's GetTransactionStatus.
    ///
    /// Pass whichever identifier you have: the app usually knows the order id,
    /// a payment-page callback knows the tracking id.
    ///
    /// A 502 / VERIFY_UNAVAILABLE means UNKNOWN, not failed. Retry rather than
    /// reporting failure — see `PaymentResponse.isUnconfirmed`.
    func verifyPayment(
        orderId: Int? = nil,
        transactionId: String? = nil,
        orderType: OrderType = .shop
    ) async throws -> PaymentResponse {
        var fields: [String: String] = [:]
        if let orderId = orderId { fields["order_id"] = String(orderId) }
        if let transactionId = transactionId { fields["transaction_id"] = transactionId }

        guard !fields.isEmpty else {
            throw APIError.server("verifyPayment needs an orderId or a transactionId")
        }

        return try await requestForm(
            "payment.php",
            action: "verify",
            query: ["order_type": orderType.rawValue],
            fields: fields
        )
    }

    /// Polls until the payment settles or the attempt budget runs out.
    ///
    /// Mirrors PaymentConfirmingScreen on Android: mobile-money approval
    /// happens on the customer's handset seconds after they leave the payment
    /// page, so returning from a WebView is not evidence of payment.
    ///
    /// Returns the last response. Check `.paid`; if the result is still
    /// unsettled, send the customer to their order list rather than back to
    /// checkout — never nudge someone who may have paid into paying twice.
    func pollPaymentUntilSettled(
        orderId: Int,
        orderType: OrderType = .shop,
        attempts: Int = 10,
        intervalSeconds: Double = 3
    ) async throws -> PaymentResponse {
        var last: PaymentResponse?

        for attempt in 0..<attempts {
            do {
                let response = try await verifyPayment(orderId: orderId, orderType: orderType)
                last = response
                if response.paid == true { return response }
                if response.status == "failed" || response.status == "pending_cash" {
                    return response
                }
            } catch {
                // Transport failure mid-poll is not a payment failure.
                if attempt == attempts - 1 { throw error }
            }
            try? await Task.sleep(nanoseconds: UInt64(intervalSeconds * 1_000_000_000))
        }

        guard let last = last else {
            throw APIError.server("Payment status could not be confirmed")
        }
        return last
    }

    // MARK: Roles

    /// GET roles.php?action=status&role=…
    ///
    /// What a Rider or Vendor app asks on launch: may this account use this app
    /// yet? One call answers approved/pending/rejected/never-asked, so the app
    /// never has to infer state from a failed workspace call.
    ///
    /// Valid roles: rider, vendor, wholesaler. "user" is the baseline nobody
    /// requests, and passing it answers "Unknown role."
    func roleStatus(for role: String) async throws -> RoleStatusResponse {
        try await request("roles.php", action: "status", query: ["role": role])
    }

    /// POST roles.php?action=request (form-encoded)
    ///
    /// An account can only ever request the role it REGISTERED for: the server
    /// compares against users.account_type and refuses a mismatch. A customer
    /// account cannot become a rider — that needs a separate registration in
    /// the Rider app.
    func requestRole(_ role: String) async throws -> RoleRequestResponse {
        try await requestForm("roles.php", action: "request", fields: ["role": role])
    }

    /// GET roles.php?action=my_requests — full history including rejections,
    /// which are only visible here (status alone reports "rejected" without
    /// the admin's note).
    func myRoleRequests() async throws -> [RoleRequestRecord] {
        let response: MyRoleRequestsResponse = try await request("roles.php", action: "my_requests")
        guard response.success else {
            throw APIError.server(response.error ?? "Could not load role requests")
        }
        return response.requests ?? []
    }
}
