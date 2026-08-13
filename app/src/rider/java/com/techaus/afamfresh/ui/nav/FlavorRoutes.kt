package com.techaus.afamfresh.ui.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.techaus.afamfresh.ui.screens.rider.RiderDashboardScreen
import com.techaus.afamfresh.ui.screens.rider.RiderDeliveriesScreen
import com.techaus.afamfresh.ui.screens.rider.RiderDeliveryDetailScreen
import com.techaus.afamfresh.ui.screens.rider.RiderEarningsScreen
import com.techaus.afamfresh.ui.screens.rider.RiderNavigationScreen

/**
 * Routes that exist only in the Rider app.
 *
 * Declared per flavor with a matching signature, so `MainScreen` in `src/main`
 * can call it without naming screens that are not compiled into every build.
 */
fun NavGraphBuilder.flavorRoutes(deps: FlavorRouteDeps) {
    val nav = deps.navController

    composable("rider_dashboard") {
        val unread by deps.notificationViewModel.unreadCount.collectAsState()
        RiderDashboardScreen(
            riderViewModel = deps.riderViewModel,
            onNotificationsClick = { nav.navigate("notifications") },
            unreadNotifications = unread,
            onDeliveryClick = { orderId, source -> nav.navigate("rider_delivery/$orderId/$source") },
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
            onDeliveryClick = { orderId, source -> nav.navigate("rider_delivery/$orderId/$source") },
            onBack = { nav.navigate("rider_dashboard") }
        )
    }

    composable("rider_earnings") {
        RiderEarningsScreen(
            riderViewModel = deps.riderViewModel,
            onBack = { nav.navigate("rider_dashboard") }
        )
    }

    // The source is part of the route, not an assumption made at the far end.
    // rider_assignments now covers both shop and surplus orders and their ids
    // overlap, so "rider_delivery/41" alone is ambiguous — it could be either
    // customer's job.
    composable("rider_delivery/{orderId}/{source}") { backStackEntry ->
        // orders.orderid is int(11), so the route argument is parsed rather
        // than compared as a string.
        val orderId = backStackEntry.arguments?.getString("orderId")?.toIntOrNull()
        val source = backStackEntry.arguments?.getString("source") ?: "order"
        if (orderId == null) {
            nav.navigate("rider_dashboard")
        } else {
            RiderDeliveryDetailScreen(
                orderId = orderId,
                source = source,
                riderViewModel = deps.riderViewModel,
                onBack = { nav.popBackStack() },
                onNavigate = { id, src -> nav.navigate("rider_navigate/$id/$src") }
            )
        }
    }

    composable("rider_navigate/{orderId}/{source}") { backStackEntry ->
        val orderId = backStackEntry.arguments?.getString("orderId")?.toIntOrNull()
        val source = backStackEntry.arguments?.getString("source") ?: "order"
        if (orderId == null) {
            nav.popBackStack()
        } else {
            RiderNavigationScreen(
                orderId = orderId,
                source = source,
                riderViewModel = deps.riderViewModel,
                trackingViewModel = deps.trackingViewModel,
                onBack = { nav.popBackStack() }
            )
        }
    }
}