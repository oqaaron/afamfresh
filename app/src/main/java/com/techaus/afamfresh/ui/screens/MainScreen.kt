package com.techaus.afamfresh.ui.screens

import android.net.Uri
import android.util.Log
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BulkListing
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.RoleGateState
import com.techaus.afamfresh.repository.AuthRepository
import com.techaus.afamfresh.repository.DeliveryRepository
import com.techaus.afamfresh.ui.nav.FlavorRouteDeps
import com.techaus.afamfresh.ui.nav.flavorRoutes
import com.techaus.afamfresh.viewmodel.*

@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    productViewModel: ProductViewModel,
    orderViewModel: OrderViewModel,
    BulkViewModel: BulkViewModel,
    cartViewModel: CartViewModel,
    checkoutViewModel: CheckoutViewModel,
    paymentViewModel: PaymentViewModel,
    deliveryResultViewModel: DeliveryResultViewModel,
    vendorViewModel: VendorViewModel,
    riderViewModel: RiderViewModel,
    roleGateViewModel: RoleGateViewModel,
    addressViewModel: AddressViewModel,
    locationViewModel: LocationViewModel,
    notificationViewModel: NotificationViewModel,
    favoritesViewModel: FavoritesViewModel,
    trackingViewModel: TrackingViewModel,
    deliveryRepository: DeliveryRepository,
    authRepository: AuthRepository,
    pendingOrderId: String? = null,
    pendingOrderSource: String? = null,
    onPendingOrderHandled: () -> Unit = {},
    onLogout: () -> Unit,
    onProductClick: (Product) -> Unit,
    onBack: () -> Unit
) {
    val appRole = BuildConfig.APP_ROLE
    val isCustomerApp = appRole == "user"

    val gateState by roleGateViewModel.state.collectAsState()
    val gateChecked by roleGateViewModel.checked.collectAsState()

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
    val unreadNotifications by notificationViewModel.unreadCount.collectAsState()

    LaunchedEffect(user?.id) {
        if (user != null) notificationViewModel.refresh()
    }

    LaunchedEffect(Unit) {
        if (isCustomerApp) {
            locationViewModel.requestCurrentLocation()
        }
    }

    LaunchedEffect(user?.id) {
        user?.id?.toIntOrNull()?.let { vendorViewModel.start(it) }
    }

    LaunchedEffect(pendingOrderId, pendingOrderSource) {
        pendingOrderId?.let {
            if (isCustomerApp) {
                if (pendingOrderSource == "Bulk") {
                    navController.navigate("Bulk_orders")
                } else {
                    navController.navigate("edit_order/$it")
                }
            }
            onPendingOrderHandled()
        }
    }

    val hideBottomBar = currentDestination in setOf(
        "track/{orderId}/{source}",
        "rider_navigate/{orderId}/{source}"
    )

    Scaffold(
        bottomBar = bottomBar@{
            if (hideBottomBar) return@bottomBar
            NavigationBar {
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
                        Triple("vendor_earnings", Icons.Default.Payments, "Earnings"),
                        Triple("profile", Icons.Default.Person, "Profile")
                    )
                    else -> listOf(
                        Triple("home", Icons.Default.Home, "Home"),
                        Triple("orders", Icons.Default.List, "Orders"),
                        Triple("cart", Icons.Default.ShoppingCart, "Cart"),
                        Triple("Merchant", Icons.Default.ShoppingCart, "Merchant"),
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
            startDestination = startRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ===== HOME =====
            if (isCustomerApp) composable("home") {
                HomeScreen(
                    userName = user?.name ?: "User",
                    currentRole = user?.currentRole ?: "user",
                    availableRoles = user?.roles ?: listOf("user"),
                    onRoleSwitch = { },
                    onLogout = onLogout,
                    onProductClick = { product ->
                        onProductClick(product)
                        navController.navigate("product_detail/${product.id}")
                    },
                    onOrdersClick = { navController.navigate("orders") },
                    onLocationClick = { navController.navigate("addresses") },
                    onProfileClick = { navController.navigate("profile") },
                    onBulkClick = { navController.navigate("Merchant") },
                    onCartClick = { navController.navigate("cart") },
                    onBrowseClick = { navController.navigate("browse") },
                    onPromoClick = { navController.navigate("promos") },
                    productViewModel = productViewModel,
                    cartViewModel = cartViewModel,
                    favoritesViewModel = favoritesViewModel,
                    addressViewModel = addressViewModel,
                    unreadNotifications = unreadNotifications,
                    onNotificationsClick = { navController.navigate("notifications") }
                )
            }

            // ===== PROMOS / OFFERS =====
            if (isCustomerApp) composable("promos") {
                PromosScreen(
                    productViewModel = productViewModel,
                    cartViewModel = cartViewModel,
                    favoritesViewModel = favoritesViewModel,
                    onBack = { navController.popBackStack() },
                    onProductClick = { product ->
                        onProductClick(product)
                        navController.navigate("product_detail/${product.id}")
                    }
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

            // ===== BROWSE =====
            if (isCustomerApp) composable("browse") {
                BrowseScreen(
                    onBack = { navController.popBackStack() },
                    onCategorySelect = { category ->
                        navController.navigate("browse_category/$category")
                    },
                    productViewModel = productViewModel,
                    onProductClick = { product ->
                        onProductClick(product)
                        navController.navigate("product_detail/${product.id}")
                    }
                )
            }

            // ===== BROWSE CATEGORY =====
            if (isCustomerApp) composable("browse_category/{categoryName}") { backStackEntry ->
                val categoryName = backStackEntry.arguments?.getString("categoryName") ?: ""
                BrowseCategoryScreen(
                    categoryName = categoryName,
                    onBack = { navController.popBackStack() },
                    onProductClick = { product ->
                        onProductClick(product)
                        navController.navigate("product_detail/${product.id}")
                    },
                    productViewModel = productViewModel,
                    cartViewModel = cartViewModel,
                    favoritesViewModel = favoritesViewModel
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

            // ===== BULK / MERCHANT =====
            if (isCustomerApp) composable("Merchant") {
                BulkScreen(
                    BulkViewModel = BulkViewModel,
                    onBack = { navController.navigate("home") },
                    onListingClick = { listing ->
                        navController.navigate("Merchant_checkout/${listing.id}")
                    },
                    onMyOrdersClick = { navController.navigate("Bulk_orders") }
                )
            }

            // ===== MERCHANT CHECKOUT =====
            if (isCustomerApp) composable("Merchant_checkout/{listingId}") { backStackEntry ->
                val listingId = backStackEntry.arguments?.getString("listingId")?.toIntOrNull()
                val listings by BulkViewModel.listings.collectAsState()
                val addresses by addressViewModel.addresses.collectAsState()

                val listing = remember(listingId) { mutableStateOf<BulkListing?>(null) }
                if (listing.value == null && listingId != null) {
                    listing.value = listings.find { it.id == listingId }
                }

                val pinned by deliveryResultViewModel.deliveryResult.collectAsState()

                BulkCheckoutScreen(
                    listing = listing.value,
                    userId = user?.id?.toIntOrNull(),
                    userEmail = user?.email,
                    userPhone = user?.mobile,
                    defaultAddress = addresses.firstOrNull { it.isDefault } ?: addresses.firstOrNull(),
                    pinnedLat = pinned?.dropoffLat,
                    pinnedLng = pinned?.dropoffLng,
                    pinnedAddress = pinned?.dropoffAddress,
                    BulkViewModel = BulkViewModel,
                    paymentViewModel = paymentViewModel,
                    onBack = { navController.popBackStack() },
                    onPickLocation = {
                        navController.navigate("Merchant_delivery_map/${listingId ?: 0}")
                    },
                    onPaymentRedirect = { paymentUrl, transactionId ->
                        navController.navigate(
                            "Bulk_payment_webview/${Uri.encode(paymentUrl)}/${Uri.encode(transactionId)}"
                        )
                    },
                    onOrderPlacedUnpaid = {
                        navController.navigate("Bulk_orders") {
                            popUpTo("Merchant") { inclusive = false }
                        }
                    },
                    availableLoyaltyPoints = user?.loyaltyPoints ?: 0
                )
            }

            // ===== BULK PAYMENT WEBVIEW =====
            if (isCustomerApp) composable("Bulk_payment_webview/{paymentUrl}/{transactionId}") { backStackEntry ->
                val paymentUrl = Uri.decode(backStackEntry.arguments?.getString("paymentUrl") ?: "")
                val transactionId = backStackEntry.arguments?.getString("transactionId") ?: ""

                PaymentWebViewScreen(
                    paymentUrl = paymentUrl,
                    transactionId = transactionId,
                    onBack = {
                        navController.navigate("Merchant") {
                            popUpTo("Merchant") { inclusive = true }
                        }
                    },
                    onCheckoutFinished = { trackingId ->
                        navController.navigate("Merchant_payment_confirming/$trackingId") {
                            popUpTo("Merchant") { inclusive = false }
                        }
                    }
                )
            }

            if (isCustomerApp) composable("Merchant_payment_confirming/{trackingId}") { backStackEntry ->
                val trackingId = backStackEntry.arguments?.getString("trackingId") ?: ""
                PaymentConfirmingScreen(
                    trackingId = trackingId,
                    paymentViewModel = paymentViewModel,
                    orderType = ApiService.ORDER_TYPE_Bulk,
                    onPaid = {
                        navController.navigate("Bulk_orders") {
                            popUpTo("Merchant") { inclusive = false }
                        }
                    },
                    onFailed = { orderId, amount ->
                        navController.navigate(
                            "payment_retry/Bulk/${orderId ?: ""}/${amount ?: 0.0}"
                        ) {
                            popUpTo("Merchant") { inclusive = false }
                        }
                    },
                    onUnconfirmed = {
                        navController.navigate("Bulk_orders") {
                            popUpTo("Merchant") { inclusive = false }
                        }
                    }
                )
            }

            // ===== PAYMENT RETRY =====
            if (isCustomerApp) composable("payment_retry/{orderType}/{orderId}/{amount}") { backStackEntry ->
                val orderType = backStackEntry.arguments?.getString("orderType") ?: ApiService.ORDER_TYPE_SHOP
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                val amount = backStackEntry.arguments?.getString("amount")?.toDoubleOrNull()
                val isBulk = orderType == ApiService.ORDER_TYPE_Bulk

                PaymentRetryScreen(
                    orderId = orderId,
                    orderType = orderType,
                    amount = amount,
                    paymentViewModel = paymentViewModel,
                    onBack = {
                        if (isBulk) {
                            navController.navigate("Bulk_orders") { popUpTo("Merchant") { inclusive = false } }
                        } else {
                            navController.navigate("orders") { popUpTo("home") { inclusive = false } }
                        }
                    },
                    onCashAccepted = {
                        if (isBulk) {
                            navController.navigate("Bulk_orders") { popUpTo("Merchant") { inclusive = false } }
                        } else {
                            cartViewModel.clearCart()
                            navController.navigate("orders") { popUpTo("home") { inclusive = false } }
                        }
                    },
                    onRedirect = { paymentUrl, transactionId ->
                        val webviewRoute = if (isBulk) "Bulk_payment_webview" else "payment_webview"
                        navController.navigate(
                            "$webviewRoute/${Uri.encode(paymentUrl)}/${Uri.encode(transactionId)}"
                        )
                    }
                )
            }

            // ===== BULK / MERCHANT ORDERS =====
            if (isCustomerApp) {
                composable("Bulk_orders") {
                    BulkOrdersScreen(
                        BulkViewModel = BulkViewModel,
                        userId = user?.id?.toIntOrNull(),
                        onBack = { navController.navigate("Merchant") },
                        onTrackOrder = { orderId -> navController.navigate("track/$orderId/Bulk") },
                        onConfirmReceipt = { orderId ->
                            navController.navigate("confirm_receipt/Bulk/$orderId")
                        },
                        onPayNow = { order ->
                            navController.navigate("payment_retry/Bulk/${order.id}/${order.grandTotal}")
                        }
                    )
                }

                composable("Merchant_orders") {
                    BulkOrdersScreen(
                        BulkViewModel = BulkViewModel,
                        userId = user?.id?.toIntOrNull(),
                        onBack = { navController.navigate("Merchant") },
                        onTrackOrder = { orderId -> navController.navigate("track/$orderId/Bulk") },
                        onConfirmReceipt = { orderId ->
                            navController.navigate("confirm_receipt/Bulk/$orderId")
                        },
                        onPayNow = { order ->
                            navController.navigate("payment_retry/Bulk/${order.id}/${order.grandTotal}")
                        }
                    )
                }
            }

            // ===== CONFIRM RECEIPT =====
            if (isCustomerApp) composable("confirm_receipt/{orderType}/{orderId}") { backStackEntry ->
                val orderType = backStackEntry.arguments?.getString("orderType") ?: "order"
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                ConfirmReceiptScreen(
                    orderId = orderId,
                    orderType = orderType,
                    userId = user?.id?.toIntOrNull(),
                    orderViewModel = orderViewModel,
                    BulkViewModel = BulkViewModel,
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() }
                )
            }

            // ===== PROFILE =====
            composable("profile") {
                ProfileScreen(
                    authViewModel = authViewModel,
                    onLogout = onLogout,
                    onBack = { navController.navigate(startRoute) },
                    showCustomerActions = isCustomerApp,
                    onOrdersClick = { navController.navigate("orders") },
                    onAddressesClick = { navController.navigate("addresses") },
                    onSettingsClick = { navController.navigate("settings") },
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
                    locationViewModel = locationViewModel,
                    onBack = { navController.popBackStack() },
                    onSelectLocation = { navController.navigate("location_picker") }
                )
            }

            // ===== LOCATION PICKER =====
            if (isCustomerApp) composable("location_picker") {
                val currentLocation by locationViewModel.currentLocation.collectAsState()
                LocationPickerScreen(
                    initialLat = null,
                    initialLng = null,
                    currentGpsLat = currentLocation?.latitude,
                    currentGpsLng = currentLocation?.longitude,
                    onBack = { navController.popBackStack() },
                    onLocationPicked = { lat: Double, lng: Double ->
                        locationViewModel.reverseGeocode(
                            latitude = lat,
                            longitude = lng,
                            onSuccess = { area: String, fullAddress: String ->
                                locationViewModel.setPickedLocation(area, fullAddress, lat, lng)
                                navController.popBackStack()
                            },
                            onError = {
                                locationViewModel.setPickedCoordinatesOnly(lat, lng)
                                navController.popBackStack()
                            }
                        )
                    }
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
                    authRepository = authRepository
                )
            )

            // ========== Checkout & Maps ==========
            if (isCustomerApp) composable("checkout") {
                val cartItems by cartViewModel.cartItems.collectAsState()
                CheckoutScreen(
                    cartItems = cartItems,
                    onBack = { navController.navigate("cart") },
                    checkoutViewModel = checkoutViewModel,
                    paymentViewModel = paymentViewModel,
                    onPaymentRedirect = { paymentUrl, transactionId ->
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
                    userPhone = user?.mobile,
                    availableLoyaltyPoints = user?.loyaltyPoints ?: 0
                )
            }

            // ========== Payment WebView ==========
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
                    onCheckoutFinished = { trackingId ->
                        navController.navigate("payment_confirming/$trackingId") {
                            popUpTo("checkout") { inclusive = true }
                        }
                    }
                )
            }

            // ========== Payment confirmation ==========
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
                    onFailed = { orderId, amount ->
                        navController.navigate(
                            "payment_retry/${ApiService.ORDER_TYPE_SHOP}/${orderId ?: ""}/${amount ?: 0.0}"
                        ) {
                            popUpTo("checkout") { inclusive = true }
                        }
                    },
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
                val productId = backStackEntry.arguments?.getString("productId")?.toIntOrNull()
                val products by productViewModel.products.collectAsState()
                val product = productId?.let { id -> products.find { it.id == id } }
                val favoriteIds by favoritesViewModel.favoriteIds.collectAsState()
                ProductDetailScreen(
                    product = product,
                    isFavorite = productId != null && productId in favoriteIds,
                    onToggleFavorite = { productId?.let { favoritesViewModel.toggleFavorite(it) } },
                    onBack = { navController.navigate("home") },
                    onAddToCart = { productToAdd, quantity ->
                        cartViewModel.addToCart(productToAdd, quantity)
                    }
                )
            }
        }
    }
}