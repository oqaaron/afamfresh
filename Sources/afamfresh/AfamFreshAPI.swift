import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// ===========================================================================
// Swift client for the AfamFresh PHP backend.
// Mirrors api/ApiClient.kt + api/ApiService.kt.
//
// SESSION HANDLING — the important part.
//
// The backend is PHP with session_start(). Authentication is TWO things
// working together:
//
//   1. a bearer `token` returned in the login JSON, and
//   2. the PHPSESSID cookie the server sets on the login response.
//
// auth.php's `me` / `logout` actions rely on the SERVER recognising the
// session, which is the cookie — not the token. CookieJarImpl.kt exists on
// Android precisely because dropping PHPSESSID makes the server treat each
// launch as a new visitor while the client still thinks it's signed in.
// That mismatch is the classic "randomly logged out" bug.
//
// On Apple platforms URLSession.shared persists cookies automatically via
// HTTPCookieStorage. On Linux that storage is in-memory only, so this file
// also saves/restores cookies itself for parity while testing.
// ===========================================================================

enum APIError: Error, CustomStringConvertible {
    /// 401 from our own backend. On login this means bad credentials;
    /// elsewhere it means the session lapsed.
    case unauthorized(String)
    case server(String)
    case transport(String)
    case decoding(String)

    var description: String {
        switch self {
        case .unauthorized(let m): return "Unauthorized: \(m)"
        case .server(let m): return "Server error: \(m)"
        case .transport(let m): return "Network error: \(m)"
        case .decoding(let m): return "Could not decode response: \(m)"
        }
    }
}

final class AfamFreshAPI {

    /// Live Render service. Apache's DocumentRoot is the repo's api/ directory,
    /// so endpoints under api/api/ are served at /api/ — hence the /api/ suffix
    /// and no /afamfresh/ segment. Matches base.url.release in build.gradle.kts.
    static let productionBaseURL = "https://afamfresh-backend.onrender.com/api/"

    /// Which app this is, sent as app_role / role. The server refuses an
    /// account whose account_type doesn't match. Match BuildConfig.APP_ROLE
    /// of whichever Android flavour you're pairing with.
    let appRole: String

    private let baseURL: String
    // .shared avoids a known swift-corelibs-foundation crash on Linux where a
    // custom URLSession can crash on deinit (_MultiHandle teardown race).
    private let session = URLSession.shared

    /// Returned by login. Stored in memory here; on iOS put it in the Keychain,
    /// not UserDefaults — it's a credential (Android uses SecurePrefs).
    private(set) var authToken: String?

    init(baseURL: String = AfamFreshAPI.productionBaseURL, appRole: String = "customer") {
        self.baseURL = baseURL.hasSuffix("/") ? baseURL : baseURL + "/"
        self.appRole = appRole
        restoreCookies()
    }

    // MARK: - Auth endpoints

    /// POST auth.php?action=login
    func login(email: String, password: String) async throws -> LoginResponse {
        let body = LoginRequest(email: email, password: password, appRole: appRole)
        let response: LoginResponse = try await post("auth.php", action: "login", jsonBody: body)
        if let token = response.token { self.authToken = token }
        persistCookies()
        return response
    }

    /// POST auth.php?action=register
    func register(name: String, email: String, password: String, mobile: String? = nil) async throws -> RegisterResponse {
        let body = RegisterRequest(name: name, email: email, password: password, mobile: mobile, role: appRole)
        return try await post("auth.php", action: "register", jsonBody: body)
    }

    /// GET auth.php?action=me — relies on the PHPSESSID cookie.
    func getCurrentUser() async throws -> UserResponse {
        try await get("auth.php", action: "me")
    }

    /// POST auth.php?action=logout
    func logout() async throws -> BaseResponse {
        let result: BaseResponse = try await post("auth.php", action: "logout", jsonBody: Optional<LoginRequest>.none)
        authToken = nil
        clearCookies()
        return result
    }

    /// POST auth.php?action=switch_role (form-encoded, matching Retrofit's @Field)
    func switchRole(to role: String) async throws -> RoleSwitchResponse {
        try await postForm("auth.php", action: "switch_role", fields: ["role": role])
    }

    // MARK: - Generic request plumbing
    //
    // These two are the extension point flavour layers build on: CustomerAPI
    // (and later RiderAPI / VendorAPI) add endpoints by calling these rather
    // than by modifying this file.

    /// Typed GET for any endpoint. Adds ?action= and any extra query params.
    func request<R: Decodable>(
        _ endpoint: String,
        action: String? = nil,
        query: [String: String] = [:]
    ) async throws -> R {
        let (data, status) = try await send(method: "GET", endpoint: endpoint, action: action, query: query)
        try checkStatus(status, data: data)
        return try decode(data)
    }

    /// Typed POST with an arbitrary JSON body. Used where the payload is
    /// heterogeneous (nested item arrays, optional coordinates) and a Codable
    /// struct would be more ceremony than it's worth.
    func requestRawBody<R: Decodable>(
        _ endpoint: String,
        action: String? = nil,
        query: [String: String] = [:],
        body: [String: Any]
    ) async throws -> R {
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, status) = try await send(
            method: "POST", endpoint: endpoint, action: action, query: query,
            body: payload, contentType: "application/json"
        )
        try checkStatus(status, data: data)
        return try decode(data)
    }

    /// Typed POST, form-encoded. Matches Retrofit's @FormUrlEncoded/@Field —
    /// several PHP actions read $_POST rather than the JSON body, so the
    /// encoding is not interchangeable.
    func requestForm<R: Decodable>(
        _ endpoint: String,
        action: String? = nil,
        query: [String: String] = [:],
        fields: [String: String]
    ) async throws -> R {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let encoded = fields.map { key, value in
            "\(key)=\(value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value)"
        }.joined(separator: "&")

        let (data, status) = try await send(
            method: "POST", endpoint: endpoint, action: action, query: query,
            body: encoded.data(using: .utf8), contentType: "application/x-www-form-urlencoded"
        )
        try checkStatus(status, data: data)
        return try decode(data)
    }

    // Decoded as [String: Any] when you don't have a typed model yet — useful
    // for poking at an endpoint before writing its Swift model.

    func getRaw(_ endpoint: String, action: String? = nil, query: [String: String] = [:]) async throws -> [String: Any] {
        let (data, status) = try await send(method: "GET", endpoint: endpoint, action: action, query: query)
        try checkStatus(status, data: data)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func get<R: Decodable>(_ endpoint: String, action: String? = nil) async throws -> R {
        let (data, status) = try await send(method: "GET", endpoint: endpoint, action: action)
        try checkStatus(status, data: data)
        return try decode(data)
    }

    private func post<B: Encodable, R: Decodable>(_ endpoint: String, action: String?, jsonBody: B?) async throws -> R {
        var httpBody: Data? = nil
        if let jsonBody = jsonBody {
            httpBody = try JSONEncoder().encode(jsonBody)
        }
        let (data, status) = try await send(
            method: "POST", endpoint: endpoint, action: action,
            body: httpBody, contentType: "application/json"
        )
        try checkStatus(status, data: data)
        return try decode(data)
    }

    private func postForm<R: Decodable>(_ endpoint: String, action: String?, fields: [String: String]) async throws -> R {
        let encoded = fields.map { key, value in
            let v = value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? value
            return "\(key)=\(v)"
        }.joined(separator: "&")
        let (data, status) = try await send(
            method: "POST", endpoint: endpoint, action: action,
            body: encoded.data(using: .utf8), contentType: "application/x-www-form-urlencoded"
        )
        try checkStatus(status, data: data)
        return try decode(data)
    }

    func send(
        method: String,
        endpoint: String,
        action: String? = nil,
        query: [String: String] = [:],
        body: Data? = nil,
        contentType: String? = nil
    ) async throws -> (Data, Int) {
        var components = URLComponents(string: baseURL + endpoint)!
        var items = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        if let action = action {
            items.append(URLQueryItem(name: "action", value: action))
        }
        if !items.isEmpty { components.queryItems = items }

        guard let url = components.url else {
            throw APIError.transport("Could not build URL for \(endpoint)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30

        // Cookies handled manually below, so turn off the automatic path to
        // stop the two from fighting over the Cookie header.
        request.httpShouldHandleCookies = false
        if !cookies.isEmpty {
            let header = cookies.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
            request.setValue(header, forHTTPHeaderField: "Cookie")
        }

        if let token = authToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let contentType = contentType {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        request.httpBody = body

        do {
            let (data, response) = try await session.data(for: request)
            let http = response as? HTTPURLResponse
            captureCookies(from: http)
            return (data, http?.statusCode ?? 0)
        } catch {
            throw APIError.transport(error.localizedDescription)
        }
    }

    func checkStatus(_ status: Int, data: Data) throws {
        guard !(200...299).contains(status) else { return }
        let serverMessage = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? String
        if status == 401 {
            // On login this is "wrong email or password", NOT "session expired" —
            // there is no session yet. AuthRepository.kt maps it the same way.
            throw APIError.unauthorized(serverMessage ?? "Incorrect email or password.")
        }
        throw APIError.server(serverMessage ?? "HTTP \(status)")
    }

    func decode<R: Decodable>(_ data: Data) throws -> R {
        do {
            return try JSONDecoder().decode(R.self, from: data)
        } catch {
            let preview = String(data: data, encoding: .utf8)?.prefix(300) ?? ""
            throw APIError.decoding("\(error)\n--- raw response ---\n\(preview)")
        }
    }

    // MARK: - Cookie jar (PHPSESSID)
    //
    // Deliberately manual rather than leaning on HTTPCookieStorage.
    // swift-corelibs-foundation's cookie handling on Linux does not reliably
    // capture Set-Cookie the way Apple's does, so the session silently failed
    // to carry — auth.php?action=me answered "Not logged in" immediately after
    // a successful login. Parsing the header ourselves behaves the same on
    // Linux, macOS and iOS, which is the whole point of testing here.
    //
    // This is the Swift counterpart to CookieJarImpl.kt.

    /// name -> value. PHPSESSID is the one that matters.
    private var cookies: [String: String] = [:]

    private var cookieFile: URL {
        URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("afamfresh-cookies.json")
    }

    private func captureCookies(from response: HTTPURLResponse?) {
        guard let response = response else { return }

        // Case-insensitive: header casing varies between platforms and servers.
        let setCookieValues: [String] = response.allHeaderFields.compactMap { key, value in
            guard let name = key as? String,
                  name.lowercased() == "set-cookie",
                  let v = value as? String else { return nil }
            return v
        }

        for raw in setCookieValues {
            // Multiple cookies may arrive folded into one header. A blind split
            // on "," would break Expires dates ("Expires=Wed, 01 Jan ..."), so
            // rejoin any fragment that isn't itself a fresh "name=value" pair.
            var chunks: [String] = []
            for piece in raw.components(separatedBy: ",") {
                let trimmed = piece.trimmingCharacters(in: .whitespaces)
                let startsNewCookie = trimmed.prefix(while: { $0 != "=" })
                    .allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }
                    && trimmed.contains("=")

                if startsNewCookie || chunks.isEmpty {
                    chunks.append(trimmed)
                } else {
                    chunks[chunks.count - 1] += "," + piece
                }
            }

            for chunk in chunks {
                guard let pair = chunk.components(separatedBy: ";").first,
                      let eq = pair.firstIndex(of: "=") else { continue }
                let name = String(pair[pair.startIndex..<eq]).trimmingCharacters(in: .whitespaces)
                let value = String(pair[pair.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { continue }
                // An empty value is the server expiring the cookie.
                if value.isEmpty {
                    cookies.removeValue(forKey: name)
                } else {
                    cookies[name] = value
                }
            }
        }
    }

    private func persistCookies() {
        try? JSONSerialization.data(withJSONObject: cookies).write(to: cookieFile)
    }

    private func restoreCookies() {
        guard let data = try? Data(contentsOf: cookieFile),
              let stored = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] else { return }
        cookies = stored
    }

    private func clearCookies() {
        cookies.removeAll()
        try? FileManager.default.removeItem(at: cookieFile)
    }

    /// Diagnostic — shows which cookies the server actually set.
    var cookieNames: [String] { Array(cookies.keys) }
}
