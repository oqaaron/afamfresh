package com.techaus.afamfresh.ui.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.techaus.afamfresh.ui.screens.rider.RiderDashboardScreen
import com.techaus.afamfresh.ui.screens.rider.RiderDeliveriesScreen
import com.techaus.afamfresh.ui.screens.rider.RiderDeliveryDetailScreen
import com.techaus.afamfresh.ui.screens.rider.RiderEarningsScreen

/**
 * Routes that exist only in the Rider app.
 *
 * Declared per flavor with a matching signature, so `MainScreen` in `src/main`
 * can call it without naming screens that are not compiled into every build.
 */
fun NavGraphBuilder.flavorRoutes(deps: FlavorRouteDeps) {
    val nav = deps.navController

    composable("rider_dashboard") {
        RiderDashboardScreen(
            riderViewModel = deps.riderViewModel,
            onDeliveryClick = { orderId -> nav.navigate("rider_delivery/$orderId") },
            onViewAll = { nav.navigate("rider_deliveries") },
            // popBackStack, not navigate("home"): "home" is the customer
            // catalogue, which is still registered in the shared graph but is
            // not somewhere a rider should ever land. This is the top of the
            // rider app, so back does nothing when there is nothing to pop.
            onBack = { nav.popBackStack() }
        )
    }

    composable("rider_deliveries") {
        RiderDeliveriesScreen(
            riderViewModel = deps.riderViewModel,
            onDeliveryClick = { orderId -> nav.navigate("rider_delivery/$orderId") },
            onBack = { nav.navigate("rider_dashboard") }
        )
    }

    composable("rider_earnings") {
        RiderEarningsScreen(
            riderViewModel = deps.riderViewModel,
            onBack = { nav.navigate("rider_dashboard") }
        )
    }

    composable("rider_delivery/{orderId}") { backStackEntry ->
        // orders.orderid is int(11), so the route argument is parsed rather
        // than compared as a string.
        val orderId = backStackEntry.arguments?.getString("orderId")?.toIntOrNull()
        if (orderId == null) {
            nav.navigate("rider_dashboard")
        } else {
            RiderDeliveryDetailScreen(
                orderId = orderId,
                riderViewModel = deps.riderViewModel,
                onBack = { nav.popBackStack() }
            )
        }
    }
}