package com.techaus.afamfresh.ui.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.techaus.afamfresh.ui.screens.vendor.NewProductScreen
import com.techaus.afamfresh.ui.screens.vendor.AddBulkScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorBusinessDetailsScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorDashboardScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorEarningsScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorLocationPickerScreen
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
            onAddListing = { nav.navigate("add_Bulk") },
            onEditListing = { listing -> nav.navigate("edit_Bulk/${listing.id}") },
            onViewOrders = { nav.navigate("vendor_orders") },
            onViewProducts = { nav.navigate("vendor_products") },
            onEditBusinessDetails = { nav.navigate("vendor_business_details") },
            // popBackStack, not navigate("home"): "home" is the customer
            // catalogue, still in the shared graph but not somewhere a vendor
            // should land. This is the top of the vendor app.
            onBack = { nav.popBackStack() }
        )
    }

    // The pin travels back through the nav back-stack rather than a shared
    // ViewModel: it is one pair of numbers used by exactly one screen, and a
    // ViewModel for it would outlive the form it belongs to.
    composable("vendor_location_picker?lat={lat}&lng={lng}") { backStackEntry ->
        VendorLocationPickerScreen(
            initialLat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull(),
            initialLng = backStackEntry.arguments?.getString("lng")?.toDoubleOrNull(),
            onBack = { nav.popBackStack() },
            onPicked = { lat, lng ->
                nav.navigate("vendor_business_details?lat=$lat&lng=$lng") {
                    popUpTo("vendor_business_details") { inclusive = true }
                }
            }
        )
    }

    composable("vendor_business_details?lat={lat}&lng={lng}") { backStackEntry ->
        val lat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull()
        val lng = backStackEntry.arguments?.getString("lng")?.toDoubleOrNull()
        VendorBusinessDetailsScreen(
            vendorViewModel = vm,
            pickedLat = lat,
            pickedLng = lng,
            onPickLocation = { curLat, curLng ->
                nav.navigate("vendor_location_picker?lat=${curLat ?: ""}&lng=${curLng ?: ""}")
            },
            onDone = { nav.navigate("vendor_dashboard") },
            onBack = { nav.popBackStack() }
        )
    }

    // The no-argument entry point, used everywhere the form is opened fresh.
    // The ?lat=&lng= variant above is only reached on the way back from the
    // picker.
    composable("vendor_business_details") {
        VendorBusinessDetailsScreen(
            vendorViewModel = vm,
            onPickLocation = { curLat, curLng ->
                nav.navigate("vendor_location_picker?lat=${curLat ?: ""}&lng=${curLng ?: ""}")
            },
            onDone = { nav.popBackStack() },
            onBack = { nav.popBackStack() }
        )
    }

    composable("add_Bulk") {
        AddBulkScreen(
            vendorViewModel = vm,
            onSave = { nav.navigate("vendor_dashboard") },
            onAddInventory = { nav.navigate("vendor_new_product") },
            onCancel = { nav.navigate("vendor_dashboard") }
        )
    }

    composable("edit_Bulk/{listingId}") { backStackEntry ->
        // Collected here rather than passed in, so the listing list is only
        // observed by the app that actually has vendor screens.
        val listings by vm.listings.collectAsState()
        // Bulk_listings.id is int(11), so the route argument has to be
        // parsed rather than string-compared against it.
        val listingId = backStackEntry.arguments?.getString("listingId")?.toIntOrNull()
        val existingListing = listingId?.let { id -> listings.find { it.id == id } }
        AddBulkScreen(
            vendorViewModel = vm,
            existingListing = existingListing,
            onSave = { nav.navigate("vendor_dashboard") },
            onAddInventory = { nav.navigate("vendor_new_product") },
            onCancel = { nav.navigate("vendor_dashboard") }
        )
    }

    composable("vendor_orders") {
        VendorOrdersScreen(vendorViewModel = vm, onBack = { nav.navigate("vendor_dashboard") })
    }

    composable("vendor_earnings") {
        VendorEarningsScreen(
            vendorViewModel = deps.vendorViewModel,
            onBack = { nav.navigate("vendor_dashboard") }
        )
    }

    composable("vendor_products") {
        VendorProductsScreen(
            vendorViewModel = vm,
            onAddProduct = { nav.navigate("vendor_new_product") },
            onBack = { nav.navigate("vendor_dashboard") }
        )
    }

    // Replaces the old "add a catalogue item to my inventory" screen. Vendors
    // now create products of their own, which an admin approves.
    composable("vendor_new_product") {
        NewProductScreen(
            vendorViewModel = vm,
            onDone = { nav.popBackStack() },
            onBack = { nav.popBackStack() }
        )
    }
}