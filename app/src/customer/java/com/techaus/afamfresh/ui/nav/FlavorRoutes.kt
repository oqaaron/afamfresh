package com.techaus.afamfresh.ui.nav

import androidx.navigation.NavHostController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.techaus.afamfresh.models.DeliveryResult
import com.techaus.afamfresh.ui.screens.DeliveryMapScreen
import com.techaus.afamfresh.ui.screens.OrderTrackingScreen
import com.techaus.afamfresh.utils.OrderCalc
import com.techaus.afamfresh.repository.AuthRepository

fun androidx.navigation.NavGraphBuilder.flavorAuthRoutes(
    nav: NavHostController,
    authRepository: AuthRepository
) = Unit

/**
 * Routes that exist only in the Customer app.
 *
 * Just the delivery map: it is the sole screen pulling the Maps SDK, and
 * keeping it here is what lets `maps-compose` and `play-services-maps` be
 * scoped to this flavor in build.gradle.kts. The rest of the customer graph
 * stays in the shared `MainScreen`, since moving it would buy nothing.
 */
fun NavGraphBuilder.flavorRoutes(deps: FlavorRouteDeps) {
    val nav = deps.navController

    // Live tracking. Customer-only: the rider has their own navigation screen,
    // and the vendor has no reason to watch a delivery move.
    composable("track/{orderId}/{source}") { backStackEntry ->
        val orderId = backStackEntry.arguments?.getString("orderId")?.toIntOrNull()
        val source = backStackEntry.arguments?.getString("source") ?: "order"
        if (orderId == null) {
            nav.popBackStack()
        } else {
            OrderTrackingScreen(
                orderId = orderId,
                source = source,
                trackingViewModel = deps.trackingViewModel,
                onBack = { nav.popBackStack() }
            )
        }
    }

    composable("Bulk_delivery_map/{listingId}") { backStackEntry ->
        val listingId = backStackEntry.arguments?.getString("listingId").orEmpty()
        DeliveryMapScreen(
            onBack = { nav.popBackStack() },
            // The map's own fee quote is for shop orders. Bulk is priced by
            // api/Bulk-quote.php, which the checkout screen calls with the
            // coordinates this returns — requiresFeeQuote = false skips the
            // shop quote entirely rather than calling it with a cartSubtotal
            // of 0, which used to trip its "add something to your cart"
            // guard and block confirming a Bulk delivery point outright.
            cartSubtotal = 0.0,
            requiresFeeQuote = false,
            deliveryRepository = deps.deliveryRepository,
            onLocationSelected = { pickupAddress, dropoffAddress, pickupLat, pickupLng,
                                   dropoffLat, dropoffLng, distanceKm, totalCost ->
                deps.deliveryResultViewModel.setDeliveryResult(
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
                // popBackStack rather than navigate(): see the delivery_map
                // route below. Pushing a fresh Bulk_checkout discarded whatever
                // the customer had already filled in there.
                nav.popBackStack()
            }
        )
    }

    composable("delivery_map") {
        val cartItems by deps.cartViewModel.cartItems.collectAsState()
        DeliveryMapScreen(
            // popBackStack, not navigate("checkout"). navigate() pushed a
            // SECOND checkout on top of the one that opened this map, and a new
            // destination gets a new NavBackStackEntry — so it got a fresh
            // SaveableStateHolder and the customer's half-filled form was gone.
            // Popping returns to the original entry with its state intact.
            onBack = { nav.popBackStack() },
            // The fee is tiered by order value, so the quote cannot be
            // requested without the cart subtotal.
            cartSubtotal = OrderCalc.subtotal(cartItems),
            deliveryRepository = deps.deliveryRepository,
            onLocationSelected = { pickupAddress, dropoffAddress, pickupLat, pickupLng,
                                   dropoffLat, dropoffLng, distanceKm, totalCost ->
                deps.deliveryResultViewModel.setDeliveryResult(
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
                // Same reason as onBack above. The quote is already in
                // DeliveryResultViewModel, so checkout picks it up from there
                // on the way back rather than needing it passed through.
                nav.popBackStack()
            }
        )
    }
}