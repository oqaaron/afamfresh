package com.techaus.afamfresh.ui.nav

import androidx.navigation.NavHostController
import com.techaus.afamfresh.repository.AuthRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.techaus.afamfresh.ui.screens.vendor.NewProductScreen
import com.techaus.afamfresh.ui.screens.vendor.AddBulkScreen
import com.techaus.afamfresh.ui.screens.vendor.AddWholesaleListingScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorBusinessDetailsScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorDashboardScreen
import com.techaus.afamfresh.ui.screens.vendor.WholesalerDashboardScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorEarningsScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorLocationPickerScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorOrdersScreen
import com.techaus.afamfresh.ui.screens.vendor.VendorProductsScreen

fun androidx.navigation.NavGraphBuilder.flavorAuthRoutes(
    nav: NavHostController,
    authRepository: AuthRepository
) = Unit

/**
 * Routes that exist only in the Vendor app.
 *
 * Declared per flavor with a matching signature, so `MainScreen` in `src/main`
 * can call it without naming screens that are not compiled into every build.
 */
fun NavGraphBuilder.flavorRoutes(deps: FlavorRouteDeps) {
    val nav = deps.navController
    val vm = deps.vendorViewModel

    // A wholesaler is a vendors row with business_type = 'wholesaler', so this
    // is the same app with two front ends rather than a fourth flavor.
    //
    // The branch is HERE, inside the vendor flavor, and not in MainScreen.kt:
    // that file lives in src/main and compiles into the customer and rider
    // builds too, so a business_type check there would be shared code changed
    // for two apps that have no vendors at all. Keeping the route NAMES the
    // same ("vendor_dashboard", "add_Bulk") also means the bottom bar and the
    // start destination in MainScreen need no change whatsoever.
    //
    // profile is null until VendorViewModel.start() resolves the record, and
    // isWholesaler is false while it is — so the surplus screens are what an
    // unresolved profile shows. That is the safe default: it is the existing
    // behaviour, and it corrects itself on the next recomposition.
    composable("vendor_dashboard") {
        val unread by deps.notificationViewModel.unreadCount.collectAsState()
        val profile by vm.profile.collectAsState()

        val onAddListing = { nav.navigate("add_Bulk") }
        val onEditListing = { listing: com.techaus.afamfresh.models.BulkListing ->
            nav.navigate("edit_Bulk/${listing.id}")
        }
        val onViewOrders = { nav.navigate("vendor_orders") }
        val onViewProducts = { nav.navigate("vendor_products") }
        val onEditBusinessDetails = { nav.navigate("vendor_business_details") }
        // popBackStack, not navigate("home"): "home" is the customer
        // catalogue, still in the shared graph but not somewhere a vendor
        // should land. This is the top of the vendor app.
        val onBack = { nav.popBackStack(); Unit }

        if (profile?.isWholesaler == true) {
            WholesalerDashboardScreen(
                vendorViewModel = vm,
                onNotificationsClick = { nav.navigate("notifications") },
                unreadNotifications = unread,
                onAddListing = onAddListing,
                onEditListing = onEditListing,
                onViewOrders = onViewOrders,
                onViewProducts = onViewProducts,
                onEditBusinessDetails = onEditBusinessDetails,
                onBack = onBack
            )
        } else {
            VendorDashboardScreen(
                vendorViewModel = vm,
                onNotificationsClick = { nav.navigate("notifications") },
                unreadNotifications = unread,
                onAddListing = onAddListing,
                onEditListing = onEditListing,
                onViewOrders = onViewOrders,
                onViewProducts = onViewProducts,
                onEditBusinessDetails = onEditBusinessDetails,
                onBack = onBack
            )
        }
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

    // Same branch as the dashboard: a wholesaler quotes a price and a minimum
    // order, a vendor a discount off a retail price and an expiry date. The
    // server decides which is acceptable from business_type regardless, so
    // showing the wrong form would only produce a rejected request.
    composable("add_Bulk") {
        val profile by vm.profile.collectAsState()
        if (profile?.isWholesaler == true) {
            AddWholesaleListingScreen(
                vendorViewModel = vm,
                onSave = { nav.navigate("vendor_dashboard") },
                onAddInventory = { nav.navigate("vendor_new_product") },
                onCancel = { nav.navigate("vendor_dashboard") }
            )
        } else {
            AddBulkScreen(
                vendorViewModel = vm,
                onSave = { nav.navigate("vendor_dashboard") },
                onAddInventory = { nav.navigate("vendor_new_product") },
                onCancel = { nav.navigate("vendor_dashboard") }
            )
        }
    }

    composable("edit_Bulk/{listingId}") { backStackEntry ->
        // Collected here rather than passed in, so the listing list is only
        // observed by the app that actually has vendor screens.
        val listings by vm.listings.collectAsState()
        // Bulk_listings.id is int(11), so the route argument has to be
        // parsed rather than string-compared against it.
        val listingId = backStackEntry.arguments?.getString("listingId")?.toIntOrNull()
        val existingListing = listingId?.let { id -> listings.find { it.id == id } }

        // Branched on the LISTING, not the seller. Both forms reduce to the
        // remaining quantity in edit mode, but the headings and the "to change
        // the price, cancel and re-create" wording differ — and a seller whose
        // type was changed by an admin can still hold listings of the other
        // kind, which the listing itself is the honest record of.
        if (existingListing?.isWholesale == true) {
            AddWholesaleListingScreen(
                vendorViewModel = vm,
                existingListing = existingListing,
                onSave = { nav.navigate("vendor_dashboard") },
                onAddInventory = { nav.navigate("vendor_new_product") },
                onCancel = { nav.navigate("vendor_dashboard") }
            )
        } else {
            AddBulkScreen(
                vendorViewModel = vm,
                existingListing = existingListing,
                onSave = { nav.navigate("vendor_dashboard") },
                onAddInventory = { nav.navigate("vendor_new_product") },
                onCancel = { nav.navigate("vendor_dashboard") }
            )
        }
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