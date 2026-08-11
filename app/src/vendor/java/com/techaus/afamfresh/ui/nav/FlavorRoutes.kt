package com.techaus.afamfresh.ui.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.techaus.afamfresh.ui.screens.vendor.AddInventoryScreen
import com.techaus.afamfresh.ui.screens.vendor.AddSurplusScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorBusinessDetailsScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorDashboardScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorOrdersScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorProductsScreen

/**
 * Routes that exist only in the Vendor app.
 *
 * Declared per flavor with a matching signature, so `MainScreen` in `src/main`
 * can call it without naming screens that are not compiled into every build.
 */
fun NavGraphBuilder.flavorRoutes(deps: FlavorRouteDeps) {
    val nav = deps.navController
    val vm = deps.vendorViewModel

    composable("vendor_dashboard") {
        val unread by deps.notificationViewModel.unreadCount.collectAsState()
        VendorDashboardScreen(
            vendorViewModel = vm,
            onNotificationsClick = { nav.navigate("notifications") },
            unreadNotifications = unread,
            onAddListing = { nav.navigate("add_surplus") },
            onEditListing = { listing -> nav.navigate("edit_surplus/${listing.id}") },
            onViewOrders = { nav.navigate("vendor_orders") },
            onViewProducts = { nav.navigate("vendor_products") },
            onEditBusinessDetails = { nav.navigate("vendor_business_details") },
            // popBackStack, not navigate("home"): "home" is the customer
            // catalogue, still in the shared graph but not somewhere a vendor
            // should land. This is the top of the vendor app.
            onBack = { nav.popBackStack() }
        )
    }

    composable("vendor_business_details") {
        VendorBusinessDetailsScreen(
            vendorViewModel = vm,
            onDone = { nav.popBackStack() },
            onBack = { nav.popBackStack() }
        )
    }

    composable("add_surplus") {
        AddSurplusScreen(
            vendorViewModel = vm,
            onSave = { nav.navigate("vendor_dashboard") },
            onCancel = { nav.navigate("vendor_dashboard") }
        )
    }

    composable("edit_surplus/{listingId}") { backStackEntry ->
        // Collected here rather than passed in, so the listing list is only
        // observed by the app that actually has vendor screens.
        val listings by vm.listings.collectAsState()
        // surplus_listings.id is int(11), so the route argument has to be
        // parsed rather than string-compared against it.
        val listingId = backStackEntry.arguments?.getString("listingId")?.toIntOrNull()
        val existingListing = listingId?.let { id -> listings.find { it.id == id } }
        AddSurplusScreen(
            vendorViewModel = vm,
            existingListing = existingListing,
            onSave = { nav.navigate("vendor_dashboard") },
            onCancel = { nav.navigate("vendor_dashboard") }
        )
    }

    composable("vendor_orders") {
        VendorOrdersScreen(vendorViewModel = vm, onBack = { nav.navigate("vendor_dashboard") })
    }

    composable("vendor_products") {
        VendorProductsScreen(
            vendorViewModel = vm,
            onAddProduct = { nav.navigate("vendor_add_inventory") },
            onBack = { nav.navigate("vendor_dashboard") }
        )
    }

    composable("vendor_add_inventory") {
        AddInventoryScreen(
            vendorViewModel = vm,
            productViewModel = deps.productViewModel,
            onDone = { nav.popBackStack() },
            onBack = { nav.popBackStack() }
        )
    }
}