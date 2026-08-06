package com.techaus.afamfresh.ui.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.techaus.afamfresh.models.DeliveryResult
import com.techaus.afamfresh.ui.screens.DeliveryMapScreen
import com.techaus.afamfresh.utils.OrderCalc

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

    composable("delivery_map") {
        val cartItems by deps.cartViewModel.cartItems.collectAsState()
        DeliveryMapScreen(
            onBack = { nav.navigate("checkout") },
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
                nav.navigate("checkout") {
                    popUpTo("delivery_map") { inclusive = true }
                }
            }
        )
    }
}