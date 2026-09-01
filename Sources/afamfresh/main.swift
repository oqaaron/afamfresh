import Foundation

// Read-only smoke test: catalogue, orders, addresses, Bulk marketplace, roles.
// Deliberately places no orders and starts no payments — see the bottom.

print("⏳ AfamFresh — customer flavour (role=\(CustomerFlavour.role))")

Task {
    let testEmail = "aaronokwim@gmail.com"
    let testPassword = "98761234"

    let api = CustomerAPI(environment: .production)

    // --- Sign in ------------------------------------------------------------
    do {
        let response = try await api.login(email: testEmail, password: testPassword)
        guard response.success, let user = response.user else {
            print("⚠️  Login rejected: \(response.error ?? "no reason given")")
            exit(1)
        }
        print("✅ \(user.name) (id=\(user.id), type=\(user.accountType ?? "—"))")
    } catch {
        print("❌ Login failed: \(error)")
        exit(1)
    }

    // --- Catalogue ----------------------------------------------------------
    do {
        let products = try await api.listProducts()
        print("✅ Catalogue: \(products.count) product(s)")
        for product in products.prefix(2) {
            print("   • \(product.name) — UGX \(Int(product.effectivePrice))")
        }
    } catch {
        print("⚠️  Catalogue: \(error)")
    }

    // --- Orders -------------------------------------------------------------
    do {
        let orders = try await api.listOrders()
        print("✅ Shop orders: \(orders.count)")
        for order in orders.prefix(2) {
            print("   • #\(order.id) \(order.status ?? "—") — UGX \(Int(order.totalAmount))")
        }
    } catch {
        print("⚠️  Orders: \(error)")
    }

    // --- Bulk marketplace ---------------------------------------------------
    do {
        let listings = try await api.listBulkListings()
        print("✅ Bulk listings: \(listings.count)")
        let wholesale = listings.filter { $0.isWholesale }.count
        let pickup = listings.filter { $0.isPickupOnly }.count
        print("   \(wholesale) wholesale, \(listings.count - wholesale) surplus, \(pickup) pickup-only")
        for listing in listings.prefix(3) {
            let vendor = listing.vendorDisplayName.map { " from \($0)" } ?? ""
            let soldOut = listing.isSoldOut ? " [sold out]" : ""
            print("   • \(listing.displayTitle)\(vendor) — UGX \(Int(listing.discountedPrice))\(soldOut)")
        }
    } catch {
        print("⚠️  Bulk listings: \(error)")
    }

    do {
        let bulkOrders = try await api.listBulkOrders()
        print("✅ Bulk orders: \(bulkOrders.count)")
        for order in bulkOrders.prefix(2) {
            print("   • #\(order.id) \(order.status ?? "—") — payable UGX \(Int(order.payableTotal))")
        }
    } catch {
        print("⚠️  Bulk orders: \(error)")
    }

    // --- Role gate ----------------------------------------------------------
    // What a Rider/Vendor app checks on launch. Asked here from a CUSTOMER
    // account, so the expected answer is a refusal explaining that this
    // account type cannot become a rider — that refusal IS the correct result.
    do {
        let status = try await api.core.roleStatus(for: "rider")
        print("✅ Role gate (rider): state=\(status.gateState.rawValue), can_request=\(status.canRequest ?? false)")
        if let reason = status.reason {
            print("   reason: \(reason)")
        }
    } catch {
        print("⚠️  Role status: \(error)")
    }

    // --- NOT enabled --------------------------------------------------------
    // createOrder / createBulkOrder place REAL orders and decrement real
    // stock. initiatePayment starts a REAL Pesapal transaction. Test those
    // against a local backend, or be ready to clean up in the admin panel.

    print("\nDone. Read-only — no orders placed, no payments started.")
    exit(0)
}

RunLoop.main.run()
