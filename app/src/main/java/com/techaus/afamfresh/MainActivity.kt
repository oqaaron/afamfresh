package com.techaus.afamfresh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.services.AfamFreshMessagingService
import com.techaus.afamfresh.models.User
import com.techaus.afamfresh.ui.screens.ForgotPasswordScreen
import com.techaus.afamfresh.ui.screens.LoginScreen
import com.techaus.afamfresh.ui.screens.MainScreen
import com.techaus.afamfresh.ui.screens.MaintenanceScreen
import com.techaus.afamfresh.ui.screens.RegisterScreen
import com.techaus.afamfresh.ui.screens.ResetPasswordScreen
import com.techaus.afamfresh.ui.screens.SplashScreen
import com.techaus.afamfresh.ui.theme.AfamfreshTheme
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.utils.FirebaseTokenManager
import com.techaus.afamfresh.utils.SessionTracker
import com.techaus.afamfresh.viewmodel.*

class MainActivity : ComponentActivity() {

    /**
     * Built lazily so that [ApiClient.initialize] has already run by the time
     * the factory reads `ApiClient.apiService` (a lateinit property). onCreate
     * initialises ApiClient before anything below is touched.
     */
    private val viewModelFactory by lazy { AppViewModelFactory(applicationContext) }

    // `by viewModels` scopes these to the Activity's ViewModelStore, which
    // Android preserves across configuration changes. Rotating the device no
    // longer rebuilds them, so ProductViewModel / BulkViewModel /
    // VendorViewModel no longer re-run their init-block network calls and
    // discard already-loaded data.
    private val authViewModel: AuthViewModel by viewModels { viewModelFactory }
    private val productViewModel: ProductViewModel by viewModels { viewModelFactory }
    private val orderViewModel: OrderViewModel by viewModels { viewModelFactory }
    private val BulkViewModel: BulkViewModel by viewModels { viewModelFactory }
    private val cartViewModel: CartViewModel by viewModels { viewModelFactory }
    private val checkoutViewModel: CheckoutViewModel by viewModels { viewModelFactory }
    private val paymentViewModel: PaymentViewModel by viewModels { viewModelFactory }
    private val deliveryResultViewModel: DeliveryResultViewModel by viewModels { viewModelFactory }
    private val vendorViewModel: VendorViewModel by viewModels { viewModelFactory }
    private val riderViewModel: RiderViewModel by viewModels { viewModelFactory }
    private val roleGateViewModel: RoleGateViewModel by viewModels { viewModelFactory }
    private val addressViewModel: AddressViewModel by viewModels { viewModelFactory }
    private val locationViewModel: LocationViewModel by viewModels { viewModelFactory }
    private val notificationViewModel: NotificationViewModel by viewModels { viewModelFactory }
    private val favoritesViewModel: FavoritesViewModel by viewModels { viewModelFactory }
    private val trackingViewModel: TrackingViewModel by viewModels { viewModelFactory }

    /** Order id carried by a tapped push notification. */
    private val pendingOrderId = mutableStateOf<String?>(null)
    private val pendingOrderSource = mutableStateOf<String?>(null)

    /** Reset token from an `afamfresh://reset-password?token=...` deep link. */
    private val pendingResetToken = mutableStateOf<String?>(null)

    // Used directly (not through a ViewModel) by the splash and session checks.
    private val authRepository get() = viewModelFactory.authRepository
    private val appRepository get() = viewModelFactory.appRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        try {
            ApiClient.initialize(applicationContext)
            FirebaseTokenManager.initialize(applicationContext)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission()
            }

            // Request location permissions for customer app (used for GPS-based address selection)
            if (BuildConfig.APP_ROLE == "user") {
                requestLocationPermission()
            }

            handleIntent(intent)

            setContent {
                var isLoggedIn by remember { mutableStateOf(authRepository.isLoggedIn()) }
                // Must agree with isLoggedIn above. getUser() ignores session
                // validity, so pairing it with isLoggedIn() gave a signed-out
                // user a non-null profile — enough for the UI to treat them as
                // signed in on a cold start.
                var currentUser by remember { mutableStateOf<User?>(authRepository.getRestorableUser()) }

                // rememberSaveable so a rotation does not replay the splash or
                // re-run the config check.
                var showSplash by rememberSaveable { mutableStateOf(true) }
                var shouldProceed by rememberSaveable { mutableStateOf(true) }
                var maintenanceMessage by rememberSaveable { mutableStateOf<String?>(null) }
                var forceUpdateRequired by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    authViewModel.user.collect { user ->
                        if (user != null) {
                            isLoggedIn = true
                            currentUser = user
                            Log.d("MainActivity", "User logged in: ${user.name}")

                            // register_token is an authenticated endpoint, so
                            // this has to happen after login — not at app
                            // start, where it would just 401. This is the call
                            // site that was previously missing entirely.
                            FirebaseTokenManager.registerTokenWithBackend()
                        } else {
                            isLoggedIn = false
                            currentUser = null
                            Log.d("MainActivity", "User logged out")
                        }
                    }
                }

                if (isLoggedIn && currentUser == null) {
                    currentUser = authRepository.getUser()
                }

                val navController = rememberNavController()

                // A 401 from any request anywhere in the app lands here. This
                // is the case that previously had no handling at all: once the
                // session lapsed mid-browse, every call just failed silently
                // and the user was left tapping a dead app.
                LaunchedEffect(Unit) {
                    SessionTracker.expired.collect {
                        if (!isLoggedIn) return@collect

                        authRepository.clearSession()
                        authRepository.clearUser()
                        isLoggedIn = false
                        currentUser = null

                        Toast.makeText(
                            this@MainActivity,
                            "Your session expired. Please sign in again.",
                            Toast.LENGTH_LONG
                        ).show()

                        // popUpTo(0) clears the whole back stack, so back from
                        // login cannot return to a screen the user is no
                        // longer authorised to see.
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }

                AfamfreshTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
                        when {
                            showSplash -> {
                                SplashScreen(
                                    authRepository = authRepository,
                                    appRepository = appRepository,
                                    onConfigChecked = { proceed, message, forceUpdate ->
                                        // The real value is honoured now. The old
                                        // `shouldProceed = true` override existed
                                        // because the config check used to fail
                                        // closed, so any backend outage locked
                                        // everyone out. SplashScreen/AppRepository
                                        // now fail OPEN — errors, timeouts and a
                                        // missing config.php all yield proceed=true
                                        // — so only an explicit maintenance_mode
                                        // (or force_update) blocks anyone.
                                        shouldProceed = proceed
                                        maintenanceMessage = message
                                        forceUpdateRequired = forceUpdate
                                        showSplash = false
                                        Log.d(
                                            "MainActivity",
                                            "Splash finished. proceed=$proceed forceUpdate=$forceUpdate"
                                        )
                                    }
                                )
                            }

                            // The message is actually shown to the user now,
                            // rather than being received and discarded.
                            !shouldProceed || forceUpdateRequired -> {
                                MaintenanceScreen(
                                    message = maintenanceMessage,
                                    isForceUpdate = forceUpdateRequired,
                                    onRetry = {
                                        // Re-run the whole config check.
                                        maintenanceMessage = null
                                        forceUpdateRequired = false
                                        shouldProceed = true
                                        showSplash = true
                                    }
                                )
                            }

                            else -> {
                                val resetToken by pendingResetToken

                                // A reset link must win over the normal start
                                // destination, otherwise tapping it while
                                // already signed in would silently ignore it.
                                LaunchedEffect(resetToken) {
                                    resetToken?.let { navController.navigate("reset_password/$it") }
                                }

                                NavHost(
                                    navController = navController,
                                    startDestination = if (isLoggedIn) "home" else "login"
                                ) {
                                    composable("login") {
                                        LoginScreen(
                                            authViewModel = authViewModel,
                                            onLoginSuccess = { name: String ->
                                                isLoggedIn = true
                                                currentUser = authRepository.getUser()
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Welcome $name!",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                navController.navigate("home") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                            },
                                            onForgotPassword = {
                                                navController.navigate("forgot_password")
                                            },
                                            onCreateAccount = {
                                                navController.navigate("register")
                                            }
                                        )
                                    }

                                    composable("forgot_password") {
                                        ForgotPasswordScreen(
                                            authRepository = authRepository,
                                            onBack = { navController.popBackStack() }
                                        )
                                    }

                                    composable("reset_password/{token}") { entry ->
                                        ResetPasswordScreen(
                                            token = entry.arguments?.getString("token").orEmpty(),
                                            authRepository = authRepository,
                                            onDone = {
                                                pendingResetToken.value = null
                                                navController.navigate("login") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                            }
                                        )
                                    }

                                    composable("register") {
                                        RegisterScreen(
                                            authViewModel = authViewModel,
                                            onRegister = { fname, lname, email, password, role, phone ->
                                                authViewModel.register(fname, lname, email, password, role, phone)
                                            },
                                            onGoogleSignUpSuccess = {
                                                isLoggedIn = true
                                                currentUser = authRepository.getUser()
                                                navController.navigate("home") {
                                                    popUpTo("register") { inclusive = true }
                                                }
                                            },
                                            onBackToLogin = {
                                                navController.navigate("login") {
                                                    popUpTo("register") { inclusive = true }
                                                }
                                            }
                                        )
                                    }

                                    composable("home") {
                                        MainScreen(
                                            authViewModel = authViewModel,
                                            productViewModel = productViewModel,
                                            orderViewModel = orderViewModel,
                                            BulkViewModel = BulkViewModel,
                                            cartViewModel = cartViewModel,
                                            checkoutViewModel = checkoutViewModel,
                                            paymentViewModel = paymentViewModel,
                                            deliveryResultViewModel = deliveryResultViewModel,
                                            vendorViewModel = vendorViewModel,
                                            riderViewModel = riderViewModel,
                                            roleGateViewModel = roleGateViewModel,
                                            addressViewModel = addressViewModel,
                                            locationViewModel = locationViewModel,
                                            notificationViewModel = notificationViewModel,
                                            favoritesViewModel = favoritesViewModel,
                                            trackingViewModel = trackingViewModel,
                                            deliveryRepository = viewModelFactory.deliveryRepository,
                                            pendingOrderId = pendingOrderId.value,
                                            pendingOrderSource = pendingOrderSource.value,
                                            onPendingOrderHandled = {
                                                pendingOrderId.value = null
                                                pendingOrderSource.value = null
                                            },
                                            onLogout = {
                                                authViewModel.logout()
                                                isLoggedIn = false
                                                currentUser = null
                                                navController.navigate("login") {
                                                    popUpTo("home") { inclusive = true }
                                                }
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Logged out",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onProductClick = { product: Product ->
                                                Log.d("MainActivity", "Product clicked: ${product.name}")
                                            },
                                            onBack = { /* Handle back press */ }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MAIN_ACTIVITY", "Error: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The activity is `launchMode="singleTop"`, so when it is already running
     * a new intent arrives here instead of through onCreate. Without this,
     * tapping a notification while the app was open would do nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Extracts the two things that can launch the app from outside:
     * a tapped push notification, and a password-reset deep link.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        intent.getStringExtra(AfamFreshMessagingService.EXTRA_ORDER_ID)?.let {
            Log.d("MainActivity", "Opened from notification for order $it")
            pendingOrderId.value = it
            pendingOrderSource.value = intent.getStringExtra(AfamFreshMessagingService.EXTRA_SOURCE)
        }

        // Two shapes reach here for the same reset flow:
        //
        //   1. afamfresh://reset-password?token=XYZ (and the per-flavor
        //      variants afamfresh-rider://, afamfresh-vendor://) — the
        //      custom-scheme fallback, still used when Android hasn't
        //      verified the https App Link (or can't, e.g. a debug build
        //      against a local backend with no assetlinks.json).
        //   2. https://<appLinkHost>/go/<DEEP_LINK_SCHEME>/reset-password?token=XYZ
        //      — the verified Android App Link. Preferred: it can't be
        //      claimed by another app the way the custom scheme can, since
        //      Digital Asset Links ties it to this app's signing cert.
        //
        // Both are compared against BuildConfig.DEEP_LINK_SCHEME, not a
        // literal "afamfresh" — the manifest registers whichever scheme/path
        // the flavor declares, so a hardcoded literal here would drop the
        // token silently in two of the three apps, as it once did.
        val data = intent.data
        val isCustomSchemeReset = data != null &&
            data.scheme == BuildConfig.DEEP_LINK_SCHEME &&
            data.host == "reset-password"
        val isAppLinkReset = data != null &&
            data.scheme == "https" &&
            (data.path ?: "").trimStart('/').startsWith("go/${BuildConfig.DEEP_LINK_SCHEME}/reset-password")

        if (isCustomSchemeReset || isAppLinkReset) {
            val token = data?.getQueryParameter("token")
            if (token.isNullOrBlank()) {
                Log.w("MainActivity", "Reset-password link had no token")
            } else {
                pendingResetToken.value = token
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1002
                )
            }
        }
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1003
            )
        }
    }
}
