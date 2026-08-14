package com.techaus.afamfresh.ui.screens

import android.net.Uri
import android.util.Log
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.models.RoleGateState
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.repository.DeliveryRepository
import com.techaus.afamfresh.ui.nav.FlavorRouteDeps
import com.techaus.afamfresh.ui.nav.flavorRoutes
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.ui.screens.SettingsScreen
import com.techaus.afamfresh.viewmodel.*

@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    productViewModel: ProductViewModel,
    orderViewModel: OrderViewModel,
    surplusViewModel: SurplusViewModel,
    cartViewModel: CartViewModel,
    checkoutViewModel: CheckoutViewModel,
    paymentViewModel: PaymentViewModel,
    deliveryResultViewModel: DeliveryResultViewModel,
    vendorViewModel: VendorViewModel,
    riderViewModel: RiderViewModel,
    roleGateViewModel: RoleGateViewModel,
    addressViewModel: AddressViewModel,
    notificationViewModel: NotificationViewModel,
    trackingViewModel: TrackingViewModel,
    deliveryRepository: DeliveryRepository,
    /** Order id from a tapped push notification, if the app was opened by one. */
    pendingOrderId: String? = null,
    /** "order" or "surplus" — which table [pendingOrderId] means. The two id
     *  spaces overlap, so this decides where a tap navigates; null (legacy
     *  payloads with no source) is treated the same as "order". */
    pendingOrderSource: String? = null,
    onPendingOrderHandled: () -> Unit = {},
    onLogout: () -> Unit,
    onProductClick: (Product) -> Unit,
    onBack: () -> Unit
) {
    // ===== Which app is this? =====
    //
    // Customer, Rider and Vendor are separate installs built from the same
    // source (see productFlavors in build.gradle.kts). APP_ROLE is fixed per
    // flavor, so the role is decided at build time and there is no switching.
    val appRole = BuildConfig.APP_ROLE

    // Shopping — catalogue, cart, checkout, orders, addresses, payment — is
    // registered ONLY in the Customer app. Those screens live in src/main and
    // so compile into every build, which meant a rider could still reach the
    // catalogue: Profile's back button navigated to "home", and the route was
    // there to receive it. Not registering the destinations removes the whole
    // class of that bug rather than patching each entry point.
    val isCustomerApp = appRole == "user"

    val gateState by roleGateViewModel.state.collectAsState()
    val gateChecked by roleGateViewModel.checked.collectAsState()

    // The Rider and Vendor apps must not open their workspace until the server
    // confirms the account holds the role. The customer app has no gate —
    // 'user' is every account's baseline.
    if (appRole != "user" && !(gateChecked && gateState == RoleGateState.Approved)) {
        RoleGateScreen(
            roleGateViewModel = roleGateViewModel,
            roleLabel = appRole.replaceFirstChar { it.uppercase() },
            onLogout = onLogout
        )
        return
    }

    val navController = rememberNavController()
    val currentRoute by navController.currentBackStackEntryAsState()
    val startRoute = when (appRole) {
        "rider" -> "rider_dashboard"
        "vendor" -> "vendor_dashboard"
        else -> "home"
    }
    val currentDestination = currentRoute?.destination?.route ?: startRoute

    val user by authViewModel.user.collectAsState()
    // vendorViewModel.listings is now collected inside the vendor flavor's
    // route file, where the only screen that reads it lives.
    val unreadNotifications by notificationViewModel.unreadCount.collectAsState()

    // Notifications are an authenticated endpoint, so this waits for a user
    // rather than firing from the ViewModel's init block.
    LaunchedEffect(user?.id) {
        if (user != null) notificationViewModel.refresh()
    }

    // The vendor endpoints identify the vendor from a query parameter rather
    // than the session, and they disagree about whether it is the user id or the
    // vendor id — so VendorViewModel has to resolve the vendor record from the
    // signed-in user before it can load anything. This replaces the old
    // `init { loadListings() }`, which ran before any user was known.
    //
    // start() is idempotent, so recomposition does not re-issue the request. A
    // non-vendor user simply gets the "not a vendor" error, which the vendor
    // screens already render.
    LaunchedEffect(user?.id) {
        user?.id?.toIntOrNull()?.let { vendorViewModel.start(it) }
    }

    // A push was tapped: jump straight to that order once, then clear the flag
    // so returning to home later does not re-navigate.
    LaunchedEffect(pendingOrderId, pendingOrderSource) {
        pendingOrderId?.let {
            // Guarded: "edit_order" is a customer route and is not registered
            // in the Rider or Vendor apps. Navigating to a route that does not
            // exist throws, so an order push landing on a rider's phone would
            // have crashed the app rather than being ignored.
            if (isCustomerApp) {
                if (pendingOrderSource == "surplus") {
                    // No per-item surplus deep link exists. "edit_order" is
                    // the SHOP order table — sending a surplus id there would
                    // either find nothing or, worse, silently open an
                    // unrelated shop order that happens to share the number.
                    // The list is the closest correct destination.
                    navController.navigate("surplus_orders")
                } else {
                    navController.navigate("edit_order/$it")
                }
            }
            onPendingOrderHandled()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                // Each app gets its own tabs. A rider has no cart and a vendor
                // has no surplus basket, so showing the customer bar in those
                // builds would offer screens their role cannot use.
                //
                // Labels are written out rather than derived from the route
                // name: capitalising "rider_dashboard" produced the tab label
                // "Rider_dashboard", underscore and all.
                val items = when (appRole) {
                    "rider" -> listOf(
                        Triple("rider_dashboard", Icons.Default.Home, "Home"),
                        Triple("rider_deliveries", Icons.Default.List, "Deliveries"),
                        Triple("rider_earnings", Icons.Default.Payments, "Earnings"),
                        Triple("profile", Icons.Default.Person, "Profile")
                    )
                    "vendor" -> listOf(
                        Triple("vendor_dashboard", Icons.Default.Home, "Home"),
                        Triple("vendor_orders", Icons.Default.List, "Orders"),
                        Triple("vendor_products", Icons.Default.ShoppingCart, "Products"),
                        // Vendors were being credited for delivered orders with
                        // no way to see the money or ask for it. The ledger and
                        // the whole payout chain were live long before this tab.
                        Triple("vendor_earnings", Icons.Default.Payments, "Earnings"),
                        Triple("profile", Icons.Default.Person, "Profile")
                    )
                    else -> listOf(
                        Triple("home", Icons.Default.Home, "Home"),
                        Triple("orders", Icons.Default.List, "Orders"),
                        Triple("cart", Icons.Default.ShoppingCart, "Cart"),
                        Triple("surplus", Icons.Default.ShoppingCart, "Surplus"),
                        Triple("profile", Icons.Default.Person, "Profile")
                    )
                }
                items.forEach { (route, icon, label) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = currentDestination == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            // Per flavor: the Rider app opens on deliveries, not the catalogue.
            startDestination = startRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ===== HOME =====
            if (isCustomerApp) composable("home") {
                HomeScreen(
                    userName = user?.name ?: "User",
                    currentRole = user?.currentRole ?: "user",
                    availableRoles = user?.roles ?: listOf("user"),
                    onRoleSwitch = { /* handled in profile */ },
                    onLogout = onLogout,
                    onProductClick = { product ->
                        onProductClick(product) // preserve caller's callback (e.g. logging in MainActivity)
                        navController.navigate("product_detail/${product.id}") // ✅ FIX: this route existed but was never reachable
                    },
                    onOrdersClick = { navController.navigate("orders") },
                    onProfileClick = { navController.navigate("profile") },
                    onSurplusClick = { navController.navigate("surplus") },
                    onCartClick = { navController.navigate("cart") },
                    productViewModel = productViewModel,
                    cartViewModel = cartViewModel,
                    unreadNotifications = unreadNotifications,
                    onNotificationsClick = { navController.navigate("notifications") }
                )
            }

            // ===== NOTIFICATIONS =====
            composable("notifications") {
                NotificationsScreen(
                    notificationViewModel = notificationViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenOrder = { orderId -> navController.navigate("edit_order/$orderId") }
                )
            }

            // ===== ORDERS =====
            if (isCustomerApp) composable("orders") {
                OrdersScreen(
                    orderViewModel = orderViewModel,
                    onBack = { navController.navigate("home") },
                    onEditOrder = { orderId ->
                        navController.navigate("edit_order/$orderId")
                    },
                    // "order" is tracking.php's vocabulary for a shop order —
                    // NOT payment.php's "shop". Two endpoints, two words.
                    onTrackOrder = { orderId ->
                        navController.navigate("track/$orderId/order")
                    },
                    onConfirmReceipt = { orderId ->
                        navController.navigate("confirm_receipt/order/$orderId")
                    }
                )
            }

            // ===== EDIT ORDER =====
            if (isCustomerApp) composable("edit_order/{orderId}") { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                EditOrderScreen(
                    orderId = orderId,
                    orderViewModel = orderViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // ===== CART =====
            if (isCustomerApp) composable("cart") {
                val cartItems by cartViewModel.cartItems.collectAsState()
                Log.d("MainScreen", "Cart screen items: ${cartItems.size}")
                CartScreen(
                    cartItems = cartItems,
                    onBack = { navController.navigate("home") },
                    onRemoveItem = { cartViewModel.removeFromCart(it) },
                    onUpdateQuantity = { item, qty -> cartViewModel.updateQuantity(item, qty) },
                    onCheckout = {
                        navController.navigate("checkout")
                    },
                    deliveryResultViewModel = deliveryResultViewModel
                )
            }

            // ===== SURPLUS =====
            if (isCustomerApp) composable("surplus") {
                SurplusScreen(
                    surplusViewModel = surplusViewModel,
                    onBack = { navController.navigate("home") },
                    onListingClick = { listing ->
                        navController.navigate("surplus_checkout/${listing.id}")
                    },
                    onMyOrdersClick = { navController.navigate("surplus_orders") }
                )
            }

            // ===== SURPLUS CHECKOUT =====
            //
            // Only the listing ID travels in the route. The listing itself is
            // looked up from the ViewModel, because a route argument survives
            // process death while an object reference does not — and passing a
            // whole listing through a URL means encoding a price the customer
            // could then edit.
            if (isCustomerApp) composable("surplus_checkout/{listingId}") { backStackEntry ->
                val listingId = backStackEntry.arguments?.getString("listingId")?.toIntOrNull()
                val listings by surplusViewModel.listings.collectAsState()
                val addresses by addressViewModel.addresses.collectAsState()

                // Held once resolved, rather than re-derived from the list on
                // every recomposition. Placing an order reloads the listings,
                // and buying the last of a listing removes it from that list —
                // which would replace the screen with "no longer available" in
                // the instant between placing the order and opening the payment
                // page.
                val listing = remember(listingId) { mutableStateOf<SurplusListing?>(null) }
                if (listing.value == null && listingId != null) {
                    listing.value = listings.find { it.id == listingId }
                }

                // The pin comes back through DeliveryResultViewModel, the same
                // channel the shop checkout uses. Only the drop-off half is
                // read: the pickup point for surplus is the vendor's premises,
                // which the server knows and the map does not.
                val pinned by deliveryResultViewModel.deliveryResult.collectAsState()

                SurplusCheckoutScreen(
                    listing = listing.value,
                    userId = user?.id?.toIntOrNull(),
                    userEmail = user?.email,
                    userPhone = user?.mobile,
                    defaultAddress = addresses.firstOrNull { it.isDefault } ?: addresses.firstOrNull(),
                    pinnedLat = pinned?.dropoffLat,
                    pinnedLng = pinned?.dropoffLng,
                    pinnedAddress = pinned?.dropoffAddress,
                    surplusViewModel = surplusViewModel,
                    paymentViewModel = paymentViewModel,
                    onBack = { navController.popBackStack() },
                    onPickLocation = {
                        navController.navigate("surplus_delivery_map/${listingId ?: 0}")
                    },
                    onPaymentRedirect = { paymentUrl, transactionId ->
                        navController.navigate(
                            "surplus_payment_webview/${Uri.encode(paymentUrl)}/${Uri.encode(transactionId)}"
                        )
                    },
                    // Cash on delivery: there is no payment page, and the order
                    // is already placed. Their order list is the only honest
                    // place to land — going back to checkout would look like it
                    // had not worked.
                    onOrderPlacedUnpaid = {
                        navController.navigate("surplus_orders") {
                            popUpTo("surplus") { inclusive = false }
                        }
                    }
                )
            }

            // ===== SURPLUS PAYMENT =====
            //
            // Separate routes from the shop's rather than a shared one with a
            // parameter: these differ in where they send the customer afterwards,
            // and in the order_type the verify call must carry. The screens
            // themselves are the same two composables.
            if (isCustomerApp) composable("surplus_payment_webview/{paymentUrl}/{transactionId}") { backStackEntry ->
                val paymentUrl = Uri.decode(backStackEntry.arguments?.getString("paymentUrl") ?: "")
                val transactionId = backStackEntry.arguments?.getString("transactionId") ?: ""

                PaymentWebViewScreen(
                    paymentUrl = paymentUrl,
                    transactionId = transactionId,
                    // Backing out of the payment page leaves a real, unpaid
                    // order behind. It is released server-side after 30 minutes,
                    // so the customer is returned to the marketplace rather than
                    // to a checkout form that would place a second one.
                    onBack = {
                        navController.navigate("surplus") {
                            popUpTo("surplus") { inclusive = true }
                        }
                    },
                    onCheckoutFinished = { trackingId ->
                        navController.navigate("surplus_payment_confirming/$trackingId") {
                            popUpTo("surplus") { inclusive = false }
                        }
                    }
                )
            }

            if (isCustomerApp) composable("surplus_payment_confirming/{trackingId}") { backStackEntry ->
                val trackingId = backStackEntry.arguments?.getString("trackingId") ?: ""
                PaymentConfirmingScreen(
                    trackingId = trackingId,
                    paymentViewModel = paymentViewModel,
                    orderType = ApiService.ORDER_TYPE_SURPLUS,
                    onPaid = {
                        navController.navigate("surplus_orders") {
                            popUpTo("surplus") { inclusive = false }
                        }
                    },
                    // Reuses this same order rather than sending the customer
                    // back to the marketplace, where buying again would create
                    // a second surplus_orders row — a fresh reservation against
                    // the listing while the first one sits dead until
                    // releaseStaleSurplusReservations() eventually cleans it up.
                    onFailed = { orderId, amount ->
                        navController.navigate(
                            "payment_retry/surplus/${orderId ?: ""}/${amount ?: 0.0}"
                        ) {
                            popUpTo("surplus") { inclusive = false }
                        }
                    },
                    // Unknown outcome sends them to their orders, never back to
                    // checkout: telling someone who HAS paid that it failed is
                    // how you get paid twice.
                    onUnconfirmed = {
                        navController.navigate("surplus_orders") {
                            popUpTo("surplus") { inclusive = false }
                        }
                    }
                )
            }

            // Retries payment on an order that already exists. Shared between
            // shop and surplus, keyed by orderType, since both hit the same
            // "failed mobile money → wants to pay differently" situation and
            // the fix is identical: call api/payment.php?action=initiate again
            // on the SAME order id, never api/orders.php?action=create again.
            if (isCustomerApp) composable("payment_retry/{orderType}/{orderId}/{amount}") { backStackEntry ->
                val orderType = backStackEntry.arguments?.getString("orderType") ?: ApiService.ORDER_TYPE_SHOP
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                val amount = backStackEntry.arguments?.getString("amount")?.toDoubleOrNull()
                val isSurplus = orderType == ApiService.ORDER_TYPE_SURPLUS

                PaymentRetryScreen(
                    orderId = orderId,
                    orderType = orderType,
                    amount = amount,
                    paymentViewModel = paymentViewModel,
                    onBack = {
                        if (isSurplus) {
                            navController.navigate("surplus_orders") { popUpTo("surplus") { inclusive = false } }
                        } else {
                            navController.navigate("orders") { popUpTo("home") { inclusive = false } }
                        }
                    },
                    onCashAccepted = {
                        if (isSurplus) {
                            navController.navigate("surplus_orders") { popUpTo("surplus") { inclusive = false } }
                        } else {
                            cartViewModel.clearCart()
                            navController.navigate("orders") { popUpTo("home") { inclusive = false } }
                        }
                    },
                    onRedirect = { paymentUrl, transactionId ->
                        val webviewRoute = if (isSurplus) "surplus_payment_webview" else "payment_webview"
                        navController.navigate(
                            "$webviewRoute/${Uri.encode(paymentUrl)}/${Uri.encode(transactionId)}"
                        )
                    }
                )
            }

            // ===== SURPLUS ORDERS =====
            if (isCustomerApp) composable("surplus_orders") {
                SurplusOrdersScreen(
                    surplusViewModel = surplusViewModel,
                    userId = user?.id?.toIntOrNull(),
                    onBack = { navController.navigate("surplus") },
                    onTrackOrder = { orderId -> navController.navigate("track/$orderId/surplus") },
                    onConfirmReceipt = { orderId ->
                        navController.navigate("confirm_receipt/surplus/$orderId")
                    }
                )
            }

            // ===== CONFIRM RECEIPT (shop and surplus) =====
            //
            // Reached from a "Confirm & Rate" button offered once the rider has
            // uploaded proof of delivery — see Order.needsReceiptConfirmation /
            // SurplusOrder.needsReceiptConfirmation. "order" / "surplus" is
            // tracking.php's vocabulary already used by the track/... route.
            if (isCustomerApp) composable("confirm_receipt/{orderType}/{orderId}") { backStackEntry ->
                val orderType = backStackEntry.arguments?.getString("orderType") ?: "order"
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                ConfirmReceiptScreen(
                    orderId = orderId,
                    orderType = orderType,
                    userId = user?.id?.toIntOrNull(),
                    orderViewModel = orderViewModel,
                    surplusViewModel = surplusViewModel,
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() }
                )
            }

            // ===== PROFILE =====
            composable("profile") {
                ProfileScreen(
                    authViewModel = authViewModel,
                    onLogout = onLogout,
                    // Back goes to THIS app's start screen. Hardcoding "home"
                    // was how a rider ended up in the shop.
                    onBack = { navController.navigate(startRoute) },
                    // Shopping actions are hidden entirely outside the
                    // Customer app, so the menu cannot offer a dead route.
                    showCustomerActions = isCustomerApp,
                    onOrdersClick = { navController.navigate("orders") },
                    onAddressesClick = { navController.navigate("addresses") },
                    onSettingsClick = { navController.navigate("settings") }, // ✅ FIX: was a no-op comment; "settings" route already existed below
                    onEditProfileClick = { navController.navigate("edit_profile") },
                    onChangePasswordClick = { navController.navigate("change_password") }
                )
            }
            // ===== EDIT PROFILE =====
            composable("edit_profile") {
                EditProfileScreen(
                    authViewModel = authViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            // ===== CHANGE PASSWORD =====
            composable("change_password") {
                ChangePasswordScreen(
                    authViewModel = authViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            // ===== ADDRESSES =====
            if (isCustomerApp) composable("addresses") {
                AddressesScreen(
                    addressViewModel = addressViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            // ===== SETTINGS =====
            composable("settings") {
                SettingsScreen(
                    authViewModel = authViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            // ========== Role-specific routes ==========
            //
            // Supplied by the flavor: src/{customer,rider,vendor}/.../ui/nav/
            // FlavorRoutes.kt. The rider dashboard and vendor screens are not
            // compiled into every app, so this file cannot name them directly.
            flavorRoutes(
                FlavorRouteDeps(
                    navController = navController,
                    riderViewModel = riderViewModel,
                    vendorViewModel = vendorViewModel,
                    cartViewModel = cartViewModel,
                    deliveryResultViewModel = deliveryResultViewModel,
                    deliveryRepository = deliveryRepository,
                    notificationViewModel = notificationViewModel,
                    productViewModel = productViewModel,
                    trackingViewModel = trackingViewModel,
                )
            )

            // ========== Checkout & Maps ==========
            if (isCustomerApp) composable("checkout") {
                val cartItems by cartViewModel.cartItems.collectAsState()
                Log.d("MainScreen", "Checkout screen items: ${cartItems.size}")
                CheckoutScreen(
                    cartItems = cartItems,
                    onBack = { navController.navigate("cart") },
                    checkoutViewModel = checkoutViewModel,
                    paymentViewModel = paymentViewModel,
                    onPaymentRedirect = { paymentUrl, transactionId ->
                        // Uri.encode is essential, not cosmetic: the Pesapal URL
                        // is an absolute https:// address with a query string, and
                        // dropping it into a path segment raw makes Navigation
                        // split it on its own '/' and '?' — the WebView then
                        // receives a truncated URL and loads a blank page.
                        navController.navigate(
                            "payment_webview/${Uri.encode(paymentUrl)}/${Uri.encode(transactionId)}"
                        )
                    },
                    onOrderComplete = {
                        cartViewModel.clearCart()
                        navController.navigate("home") {
                            popUpTo("checkout") { inclusive = true }
                        }
                    },
                    onSelectLocation = { navController.navigate("delivery_map") },
                    deliveryResultViewModel = deliveryResultViewModel,
                    addressViewModel = addressViewModel,
                    userEmail = user?.email,
                    userPhone = user?.mobile
                )
            }

            // delivery_map moved to src/customer/.../ui/nav/FlavorRoutes.kt.
            // It is the only screen using the Maps SDK, which is now scoped to
            // the customer flavor.

            // ========== Payment WebView ==========
            //
            // The Pesapal checkout URL is passed URL-ENCODED — see the navigate()
            // call in the checkout route. A raw https:// URL cannot travel in a
            // path segment: its own slashes and query string split the route and
            // the argument arrives truncated, so the WebView would load nothing.
            if (isCustomerApp) composable("payment_webview/{paymentUrl}/{transactionId}") { backStackEntry ->
                val paymentUrl = Uri.decode(
                    backStackEntry.arguments?.getString("paymentUrl") ?: ""
                )
                val transactionId = backStackEntry.arguments?.getString("transactionId") ?: ""

                PaymentWebViewScreen(
                    paymentUrl = paymentUrl,
                    transactionId = transactionId,
                    onBack = {
                        navController.navigate("checkout") {
                            popUpTo("checkout") { inclusive = true }
                        }
                    },
                    // The WebView reports only that checkout ENDED. Whether money
                    // moved is decided by asking Pesapal, because mobile-money
                    // approval happens on the customer's handset seconds later and
                    // a URL parameter is not evidence of payment.
                    onCheckoutFinished = { trackingId ->
                        navController.navigate("payment_confirming/$trackingId") {
                            popUpTo("checkout") { inclusive = true }
                        }
                    }
                )
            }

            // ========== Payment confirmation (polls the server) ==========
            if (isCustomerApp) composable("payment_confirming/{trackingId}") { backStackEntry ->
                val trackingId = backStackEntry.arguments?.getString("trackingId") ?: ""
                PaymentConfirmingScreen(
                    trackingId = trackingId,
                    paymentViewModel = paymentViewModel,
                    onPaid = {
                        cartViewModel.clearCart()
                        navController.navigate("orders") {
                            popUpTo("home") { inclusive = false }
                        }
                    },
                    // Reuses this same order rather than sending the customer
                    // back to checkout, where "Place order" would call
                    // api/orders.php?action=create again and leave this order
                    // behind, dead at payment_status = 'failed' forever — shop
                    // orders have no cleanup job to ever remove it.
                    onFailed = { orderId, amount ->
                        navController.navigate(
                            "payment_retry/${ApiService.ORDER_TYPE_SHOP}/${orderId ?: ""}/${amount ?: 0.0}"
                        ) {
                            popUpTo("checkout") { inclusive = true }
                        }
                    },
                    // Unknown outcome: send them to their orders rather than back
                    // to checkout, so a customer who HAS paid is never nudged into
                    // paying a second time.
                    onUnconfirmed = {
                        cartViewModel.clearCart()
                        navController.navigate("orders") {
                            popUpTo("home") { inclusive = false }
                        }
                    }
                )
            }

            // ========== Product Details ==========
            if (isCustomerApp) composable("product_detail/{productId}") { backStackEntry ->
                // items.id is int(100). Parsing this as a Double also meant the
                // route was built from an id rendered as "4.0".
                val productId = backStackEntry.arguments?.getString("productId")?.toIntOrNull()
                val products by productViewModel.products.collectAsState()
                val product = productId?.let { id -> products.find { it.id == id } }
                ProductDetailScreen(
                    product = product,
                    onBack = { navController.navigate("home") },
                    onAddToCart = { productToAdd, quantity ->
                        cartViewModel.addToCart(productToAdd, quantity)
                        Log.d("MainScreen", "Added to cart: ${productToAdd.name}, quantity: $quantity")
                    }
                )
            }
        }
    }
}
