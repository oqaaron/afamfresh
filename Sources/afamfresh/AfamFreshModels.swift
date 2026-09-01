import Foundation

// ===========================================================================
// Wire models for the AfamFresh PHP backend.
// Mirrors app/src/main/java/com/techaus/afamfresh/models/AuthModels.kt
// field-for-field, so both platforms read the same payloads.
//
// ⚠️ Two decoding traps carried over from the Android side. Both are real
// bugs that already bit the Kotlin app, and Swift hits them the same way:
//
//  1. MySQL tinyint(1) columns come back as the INTEGERS 0/1, not JSON
//     booleans. Gson needed MySqlBooleanAdapter; JSONDecoder throws
//     typeMismatch and aborts the WHOLE response, not just that field.
//     -> FlexibleBool below.
//
//  2. users.id is int(11) in the schema but String in the Kotlin model, so
//     PHP may emit 42 or "42" depending on the code path.
//     -> FlexibleString below.
//
// Every optional field stays optional, for the same reason the Kotlin models
// are all-nullable: "the server didn't send it" must be representable.
// ===========================================================================

/// Decodes a JSON bool that may arrive as true/false, 0/1, or "0"/"1".
@propertyWrapper
struct FlexibleBool: Codable {
    var wrappedValue: Bool?

    init(wrappedValue: Bool?) { self.wrappedValue = wrappedValue }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { wrappedValue = nil }
        else if let b = try? c.decode(Bool.self) { wrappedValue = b }
        else if let i = try? c.decode(Int.self) { wrappedValue = (i != 0) }
        else if let s = try? c.decode(String.self) { wrappedValue = (s == "1" || s.lowercased() == "true") }
        else { wrappedValue = nil }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(wrappedValue)
    }
}

/// Decodes a value that may arrive as a JSON string or number.
@propertyWrapper
struct FlexibleString: Codable {
    var wrappedValue: String

    init(wrappedValue: String) { self.wrappedValue = wrappedValue }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let s = try? c.decode(String.self) { wrappedValue = s }
        else if let i = try? c.decode(Int.self) { wrappedValue = String(i) }
        else { wrappedValue = "" }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(wrappedValue)
    }
}

// MARK: - User

struct NotificationPrefs: Codable {
    var email: Bool?
    var push: Bool?
}

struct User: Codable {
    @FlexibleString var id: String
    var name: String
    var email: String
    var mobile: String?
    var fname: String?
    var lname: String?
    var area: String?
    var address: String?
    var avatarUrl: String?
    @FlexibleBool var hasPassword: Bool?
    @FlexibleBool var isGoogleAccount: Bool?
    var loyaltyPoints: Int?
    var notificationPreferences: NotificationPrefs?
    var roles: [String]?
    var currentRole: String?
    /// "customer", "rider", "vendor", "wholesaler" — fixed at registration.
    var accountType: String?

    enum CodingKeys: String, CodingKey {
        case id, name, email, mobile, fname, lname, area, address, roles
        case avatarUrl = "avatar_url"
        case hasPassword = "has_password"
        case isGoogleAccount = "is_google_account"
        case loyaltyPoints = "loyalty_points"
        case notificationPreferences = "notification_preferences"
        case currentRole = "current_role"
        case accountType = "account_type"
    }

    // Mirrors the Kotlin derived accessors, including the literal
    // "Not specified" sentinel that register writes into area/address.
    private static let notSpecified = "Not specified"

    var firstNameOrDerived: String {
        if let f = fname, !f.isEmpty { return f }
        return name.components(separatedBy: " ").first ?? name
    }

    var lastNameOrDerived: String {
        if let l = lname, !l.isEmpty { return l }
        let parts = name.components(separatedBy: " ")
        return parts.count > 1 ? parts.dropFirst().joined(separator: " ") : ""
    }

    var areaOrEmpty: String {
        guard let a = area, !a.isEmpty, a != Self.notSpecified else { return "" }
        return a
    }

    var addressOrEmpty: String {
        guard let a = address, !a.isEmpty, a != Self.notSpecified else { return "" }
        return a
    }

    var canChangePassword: Bool { hasPassword != false }
}

// MARK: - Requests

struct LoginRequest: Codable {
    let email: String
    let password: String
    /// Which app is asking. The server refuses an account whose account_type
    /// doesn't match, so a rider cannot sign in to the Customer app.
    let appRole: String

    enum CodingKeys: String, CodingKey {
        case email, password
        case appRole = "app_role"
    }
}

struct RegisterRequest: Codable {
    let name: String
    let email: String
    let password: String
    let mobile: String?
    /// Decides users.account_type — fixed for the life of the account.
    let role: String
}

// MARK: - Responses

struct LoginResponse: Codable {
    let success: Bool
    let token: String?
    let user: User?
    let error: String?
}

struct RegisterResponse: Codable {
    let success: Bool
    let user: User?
    let error: String?
}

struct UserResponse: Codable {
    let success: Bool
    let user: User?
    let error: String?
}

struct BaseResponse: Codable {
    let success: Bool
    let error: String?
}

struct RoleSwitchResponse: Codable {
    let success: Bool
    let currentRole: String?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case success, error
        case currentRole = "current_role"
    }
}
