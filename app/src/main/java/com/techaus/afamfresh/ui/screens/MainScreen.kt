package com.techaus.afamfresh.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.techaus.afamfresh.models.DeliveryResult
import com.techaus.afamfresh.repository.DeliveryRepository
import com.techaus.afamfresh.utils.OrderCalc
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.ui.screens.vendor.*
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
    addressViewModel: AddressViewModel,
    notificationViewModel: NotificationViewModel,
    deliveryRepository: DeliveryRepository,
    /** Order id from a tapped push notification, if the app was opened by one. */
    pendingOrderId: String? = null,
    onPendingOrderHandled: () -> Unit = {},
    onLogout: () -> Unit,
    onProductClick: (Product) -> Unit,
    onBack: () -> Unit
) {
    val navController = rememberNavController()
    val currentRoute by navController.currentBackStackEntryAsState()
    val currentDestination = currentRoute?.destination?.route ?: "home"

    val user by authViewModel.user.collectAsState()
    val vendorListings by vendorViewModel.listings.collectAsState()
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
    LaunchedEffect(pendingOrderId) {
        pendingOrderId?.let {
            navController.navigate("edit_order/$it")
            onPendingOrderHandled()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    "home" to Icons.Default.Home,
                    "orders" to Icons.Default.List,
                    "cart" to Icons.Default.ShoppingCart,
                    "surplus" to Icons.Default.ShoppingCart,
                    "profile" to Icons.Default.Person
                )
                items.forEach { (route, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = route) },
                        label = { Text(route.replaceFirstChar { it.uppercase() }) },
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
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            // ===== HOME =====
            composable("home") {
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
            composable("orders") {
                OrdersScreen(
                    orderViewModel = orderViewModel,
                    onBack = { navController.navigate("home") },
                    onEditOrder = { orderId ->
                        navController.navigate("edit_order/$orderId")
                    }
                )
            }

            // ===== EDIT ORDER =====
            composable("edit_order/{orderId}") { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                EditOrderScreen(
                    orderId = orderId,
                    orderViewModel = orderViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // ===== CART =====
            composable("cart") {
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
            composable("surplus") {
                SurplusScreen(
                    surplusViewModel = surplusViewModel,
                    onBack = { navController.navigate("home") },
                    onListingClick = { listing ->
                        // Navigate to product detail
                    }
                )
            }

            // ===== PROFILE =====
            composable("profile") {
                ProfileScreen(
                    authViewModel = authViewModel,
                    onLogout = onLogout,
                    onBack = { navController.navigate("home") },
                    onOrdersClick = { navController.navigate("orders") },
                    onAddressesClick = { navController.navigate("addresses") },
                    onSettingsClick = { navController.navigate("settings") } // ✅ FIX: was a no-op comment; "settings" route already existed below
                )
            }
            // ===== ADDRESSES =====
            composable("addresses") {
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
            // ========== Vendor Screens ==========
            composable("vendor_dashboard") {
                VendorDashboardScreen(
                    vendorViewModel = vendorViewModel,
                    onAddListing = { navController.navigate("add_surplus") },
                    onEditListing = { listing ->
                        navController.navigate("edit_surplus/${listing.id}")
                    },
                    onViewOrders = { navController.navigate("vendor_orders") },
                    onViewProducts = { navController.navigate("vendor_products") },
                    onBack = { navController.navigate("home") }
                )
            }
            composable("add_surplus") {
                AddSurplusScreen(
                    vendorViewModel = vendorViewModel,
                    onSave = { navController.navigate("vendor_dashboard") },
                    onCancel = { navController.navigate("vendor_dashboard") }
                )
            }
            composable("edit_surplus/{listingId}") { backStackEntry ->
                // surplus_listings.id is int(11), so the route argument has to
                // be parsed rather than string-compared against it.
                val listingId = backStackEntry.arguments?.getString("listingId")?.toIntOrNull()
                val existingListing = listingId?.let { id -> vendorListings.find { it.id == id } }
                AddSurplusScreen(
                    vendorViewModel = vendorViewModel,
                    existingListing = existingListing,
                    onSave = { navController.navigate("vendor_dashboard") },
                    onCancel = { navController.navigate("vendor_dashboard") }
                )
            }
            composable("vendor_orders") {
                VendorOrdersScreen(
                    vendorViewModel = vendorViewModel,
                    onBack = { navController.navigate("vendor_dashboard") }
                )
            }
            composable("vendor_products") {
                VendorProductsScreen(
                    vendorViewModel = vendorViewModel,
                    onBack = { navController.navigate("vendor_dashboard") }
                )
            }

            // ========== Checkout & Maps ==========
            composable("checkout") {
                val cartItems by cartViewModel.cartItems.collectAsState()
                Log.d("MainScreen", "Checkout screen items: ${cartItems.size}")
                CheckoutScreen(
                    cartItems = cartItems,
                    onBack = { navController.navigate("cart") },
                    checkoutViewModel = checkoutViewModel,
                    paymentViewModel = paymentViewModel,
                    onPaymentRedirect = { paymentUrl, transactionId ->
                        navController.navigate("payment_webview/${paymentUrl}/${transactionId}")
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

            composable("delivery_map") {
                val cartItems by cartViewModel.cartItems.collectAsState()
                DeliveryMapScreen(
                    onBack = { navController.navigate("checkout") },
                    // The fee is tiered by order value, so the quote cannot be
                    // requested without the cart subtotal.
                    cartSubtotal = OrderCalc.subtotal(cartItems),
                    deliveryRepository = deliveryRepository,
                    onLocationSelected = { pickupAddress, dropoffAddress, pickupLat, pickupLng,
                                          dropoffLat, dropoffLng, distanceKm, totalCost ->
                        deliveryResultViewModel.setDeliveryResult(
                            DeliveryResult(
                                pickupAddress = pickupAddress,
                                dropoffAddress = dropoffAddress,
                                pickupLat = pickupLat,
                                pickupLng = pickupLng,
                                dropoffLat = dropoffLat,
                                dropoffLng = dropoffLng,
                                distanceKm = distanceKm,
                                cost = totalCost
                            )
                        )
                        navController.navigate("checkout") {
                            popUpTo("delivery_map") { inclusive = true }
                        }
                    }
                )
            }

            // ========== Payment WebView ==========
            composable("payment_webview/{paymentUrl}/{transactionId}") { backStackEntry ->
                val paymentUrl = backStackEntry.arguments?.getString("paymentUrl") ?: ""
                val transactionId = backStackEntry.arguments?.getString("transactionId") ?: ""
                PaymentWebViewScreen(
                    paymentUrl = paymentUrl,
                    transactionId = transactionId,
                    onBack = {
                        navController.navigate("checkout") {
                            popUpTo("checkout") { inclusive = true }
                        }
                    },
                    onPaymentComplete = { success, txnId ->
                        if (success) {
                            cartViewModel.clearCart()
                            navController.navigate("home") {
                                popUpTo("checkout") { inclusive = true }
                            }
                        } else {
                            navController.navigate("checkout") {
                                popUpTo("checkout") { inclusive = true }
                            }
                        }
                    }
                )
            }

            // ========== Product Details ==========
            composable("product_detail/{productId}") { backStackEntry ->
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
