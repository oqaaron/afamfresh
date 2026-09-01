import Foundation

// ===========================================================================
// FLAVOUR SYSTEM
//
// The Android app ships as three separate installs — Customer, Rider, Vendor —
// differing by BuildConfig.APP_ROLE. That single value decides:
//
//   * `app_role` on login and `role` on register
//   * users.account_type at registration, fixed for the life of the account
//   * which endpoints the server will even answer
//
// The server enforces this, not the client: api/orders.php calls
// requireAccountType($dbh, $user_id, 'customer'), so a rider's session gets
// refused there no matter what the app does. The three apps are a UX split,
// not a security boundary.
//
// This file is the iOS counterpart. Everything flavour-specific lives behind
// AppFlavour, so adding Rider or Vendor later means writing a new conformance
// and a new API surface — never editing AfamFreshAPI or the shared models.
//
// In Xcode each flavour becomes its own target/scheme sharing this code,
// mirroring the Android product flavours.
// ===========================================================================

protocol AppFlavour {
    /// Sent as `app_role` (login) and `role` (register).
    /// Must match users.account_type or the server refuses the account.
    static var role: String { get }

    /// Shown in UI and used for the User-Agent.
    static var displayName: String { get }

    /// Bundle id for the iOS target. Distinct per flavour, like Android's
    /// applicationIdSuffix.
    static var bundleId: String { get }
}

// MARK: - Flavours

enum CustomerFlavour: AppFlavour {
    static let role = "customer"
    static let displayName = "AfamFresh"
    static let bundleId = "com.techaus.afamfresh"
}

// Not built yet — declared so the shape is fixed and the compiler will tell
// you what a new flavour owes. Add the matching API surface when demand
// justifies it; nothing in Core or in CustomerAPI needs to change.
enum RiderFlavour: AppFlavour {
    static let role = "rider"
    static let displayName = "AfamFresh Rider"
    static let bundleId = "com.techaus.afamfresh.rider"
}

enum VendorFlavour: AppFlavour {
    static let role = "vendor"
    static let displayName = "AfamFresh Vendor"
    static let bundleId = "com.techaus.afamfresh.vendor"
}

// MARK: - Environment

enum Environment {
    /// Live Render service. Apache's DocumentRoot is the repo's api/ directory,
    /// so endpoints under api/api/ are served at /api/.
    case production
    /// Local dev server. Android debug uses 10.0.2.2 (the emulator's host
    /// alias); on a Mac talking to a local PHP server, localhost is correct.
    case local(host: String)

    var baseURL: String {
        switch self {
        case .production:
            return "https://afamfresh-backend.onrender.com/api/"
        case .local(let host):
            return "http://\(host)/afamfresh/api/"
        }
    }
}

// MARK: - Session store
//
// Where the auth token and PHPSESSID live. Android keeps both in SecurePrefs
// (EncryptedSharedPreferences) because PHPSESSID is itself a credential —
// anyone holding it can act as the signed-in user.
//
// The file-backed store below is for LINUX TESTING ONLY. On iOS, implement
// this protocol over the Keychain before shipping. Making it a protocol means
// that swap touches one type, not the networking layer.

protocol SessionStore {
    func save(token: String?)
    func loadToken() -> String?
    func save(cookies: [String: String])
    func loadCookies() -> [String: String]
    func clear()
}

/// Plaintext, temp-directory backed. Fine for validating against the live API
/// from a dev machine. NOT for production — see the note above.
final class FileSessionStore: SessionStore {
    private let tokenFile: URL
    private let cookieFile: URL

    init(namespace: String = "afamfresh") {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory())
        self.tokenFile = dir.appendingPathComponent("\(namespace)-token")
        self.cookieFile = dir.appendingPathComponent("\(namespace)-cookies.json")
    }

    func save(token: String?) {
        guard let token = token else {
            try? FileManager.default.removeItem(at: tokenFile)
            return
        }
        try? token.write(to: tokenFile, atomically: true, encoding: .utf8)
    }

    func loadToken() -> String? {
        try? String(contentsOf: tokenFile, encoding: .utf8)
    }

    func save(cookies: [String: String]) {
        try? JSONSerialization.data(withJSONObject: cookies).write(to: cookieFile)
    }

    func loadCookies() -> [String: String] {
        guard let data = try? Data(contentsOf: cookieFile),
              let stored = (try? JSONSerialization.jsonObject(with: data)) as? [String: String]
        else { return [:] }
        return stored
    }

    func clear() {
        try? FileManager.default.removeItem(at: tokenFile)
        try? FileManager.default.removeItem(at: cookieFile)
    }
}
