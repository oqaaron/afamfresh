package com.techaus.afamfresh.ui.nav

import androidx.navigation.NavHostController
import com.techaus.afamfresh.repository.DeliveryRepository
import com.techaus.afamfresh.viewmodel.CartViewModel
import com.techaus.afamfresh.viewmodel.DeliveryResultViewModel
import com.techaus.afamfresh.viewmodel.NotificationViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel
import com.techaus.afamfresh.viewmodel.RiderViewModel
import com.techaus.afamfresh.viewmodel.TrackingViewModel
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * What a flavor's own routes need in order to register themselves.
 *
 * Customer, Rider and Vendor are separate apps built from one codebase, and
 * each keeps its role-specific screens in its own source set — so a rider build
 * physically does not contain the delivery map, and a customer build does not
 * contain the rider dashboard.
 *
 * `MainScreen` lives in `src/main` and therefore cannot name those screens. It
 * calls `NavGraphBuilder.flavorRoutes(deps)` instead, which each flavor
 * declares with this same signature in its own `ui/nav/FlavorRoutes.kt`. This
 * class is the only shared part: the seam between the common shell and the
 * per-app routes.
 *
 * Everything here is already constructed by AppViewModelFactory; nothing is
 * built specially for this. Fields are supplied to every flavor even when only
 * one uses them, because the signature has to match across all three.
 */
data class FlavorRouteDeps(
    val navController: NavHostController,
    val riderViewModel: RiderViewModel,
    val vendorViewModel: VendorViewModel,
    val cartViewModel: CartViewModel,
    val deliveryResultViewModel: DeliveryResultViewModel,
    val deliveryRepository: DeliveryRepository,
    /**
     * Carried so the Rider and Vendor dashboards can show an unread badge.
     *
     * The `notifications` route is registered in `MainScreen` for every flavor,
     * but the only way in was the bell on `HomeScreen` — the customer
     * catalogue, which those two apps never open. Both were notification-blind:
     * messages were written for them and there was no screen to read them on.
     */
    val notificationViewModel: NotificationViewModel,
    /**
     * The shared `items` catalogue. A vendor stocks catalogue products rather
     * than inventing them, so the Add-to-inventory screen picks from here.
     */
    val productViewModel: ProductViewModel,
    /**
     * Live delivery tracking. Customer-only in practice — the tracking screen
     * is registered in the customer flavor — but supplied to all three because
     * the signature has to match across them.
     */
    val trackingViewModel: TrackingViewModel,
    val authRepository: com.techaus.afamfresh.repository.AuthRepository,
)