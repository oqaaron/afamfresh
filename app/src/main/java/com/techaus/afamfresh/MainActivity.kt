package com.techaus.afamfresh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.User
import com.techaus.afamfresh.services.AfamFreshMessagingService
import com.techaus.afamfresh.ui.nav.flavorAuthRoutes
import com.techaus.afamfresh.ui.screens.CompletePhoneSignupScreen
import com.techaus.afamfresh.ui.screens.ForgotPasswordScreen
import com.techaus.afamfresh.ui.screens.LoginScreen
import com.techaus.afamfresh.ui.screens.MainScreen
import com.techaus.afamfresh.ui.screens.MaintenanceScreen
import com.techaus.afamfresh.ui.screens.OnboardingScreen
import com.techaus.afamfresh.ui.screens.OtpEntryScreen
import com.techaus.afamfresh.ui.screens.PhoneEntryScreen
import com.techaus.afamfresh.ui.screens.RegisterScreen
import com.techaus.afamfresh.ui.screens.ResetPasswordScreen
import com.techaus.afamfresh.ui.screens.SplashScreen
import com.techaus.afamfresh.ui.theme.AfamfreshTheme
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.utils.FirebaseTokenManager
import com.techaus.afamfresh.utils.OnboardingPrefs
import com.techaus.afamfresh.utils.SessionTracker
import com.techaus.afamfresh.viewmodel.*

class MainActivity : ComponentActivity() {

    private val viewModelFactory by lazy { AppViewModelFactory(applicationContext) }

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

    private val pendingOrderId = mutableStateOf<String?>(null)
    private val pendingOrderSource = mutableStateOf<String?>(null)
    private val pendingResetToken = mutableStateOf<String?>(null)

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

            if (BuildConfig.APP_ROLE == "user") {
                requestLocationPermission()
            }

            handleIntent(intent)

            setContent {
                val context = LocalContext.current
                var isLoggedIn by remember { mutableStateOf(authRepository.isLoggedIn()) }
                var currentUser by remember { mutableStateOf<User?>(authRepository.getRestorableUser()) }

                var showSplash by rememberSaveable { mutableStateOf(true) }
                var shouldProceed by rememberSaveable { mutableStateOf(true) }
                var maintenanceMessage by rememberSaveable { mutableStateOf<String?>(null) }
                var forceUpdateRequired by rememberSaveable { mutableStateOf(false) }
                var showOnboarding by rememberSaveable {
                    mutableStateOf(
                        BuildConfig.APP_ROLE == "user" && !OnboardingPrefs.hasSeenOnboarding(context)
                    )
                }

                LaunchedEffect(Unit) {
                    authViewModel.user.collect { user ->
                        if (user != null) {
                            isLoggedIn = true
                            currentUser = user
                            Log.d("MainActivity", "User logged in: ${user.name}")
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

                            !shouldProceed || forceUpdateRequired -> {
                                MaintenanceScreen(
                                    message = maintenanceMessage,
                                    isForceUpdate = forceUpdateRequired,
                                    onRetry = {
                                        maintenanceMessage = null
                                        forceUpdateRequired = false
                                        shouldProceed = true
                                        showSplash = true
                                    }
                                )
                            }

                            showOnboarding -> {
                                OnboardingScreen(
                                    productViewModel = productViewModel,
                                    onDone = {
                                        OnboardingPrefs.markOnboardingSeen(context)
                                        showOnboarding = false
                                    }
                                )
                            }

                            else -> {
                                val resetToken by pendingResetToken

                                LaunchedEffect(resetToken) {
                                    resetToken?.let { navController.navigate("reset_password/$it") }
                                }

                                fun completeLogin(name: String) {
                                    isLoggedIn = true
                                    currentUser = authRepository.getUser()
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Welcome $name!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    navController.navigate("home") {
                                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                                    }
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
                                                navController.navigate(
                                                    if (BuildConfig.APP_ROLE == "rider") "rider_register" else "register"
                                                )
                                            },
                                            onPhoneSignIn = {
                                                navController.navigate("phone_entry")
                                            }
                                        )
                                    }

                                    flavorAuthRoutes(navController, authRepository)

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
                                            },
                                            onPhoneSignUp = {
                                                navController.navigate("phone_entry")
                                            }
                                        )
                                    }

                                    composable("phone_entry") {
                                        val phoneAuthState by authViewModel.phoneAuthState.collectAsState()
                                        var pendingMobile by remember { mutableStateOf("") }

                                        LaunchedEffect(phoneAuthState) {
                                            if (phoneAuthState is PhoneAuthState.CodeSent) {
                                                navController.navigate("otp_entry/${Uri.encode(pendingMobile)}")
                                            }
                                        }

                                        PhoneEntryScreen(
                                            onBack = {
                                                authViewModel.resetPhoneAuthState()
                                                navController.popBackStack()
                                            },
                                            onContinue = { fullNumber ->
                                                pendingMobile = fullNumber
                                                authViewModel.sendPhoneOtp(fullNumber)
                                            },
                                            onTermsClick = { },
                                            isLoading = phoneAuthState is PhoneAuthState.SendingCode,
                                            errorMessage = (phoneAuthState as? PhoneAuthState.Error)?.message
                                        )
                                    }

                                    composable("otp_entry/{mobile}") { backStackEntry ->
                                        val mobile = backStackEntry.arguments?.getString("mobile").orEmpty()
                                        val phoneAuthState by authViewModel.phoneAuthState.collectAsState()
                                        val loginState by authViewModel.loginState.collectAsState()

                                        LaunchedEffect(loginState) {
                                            val state = loginState
                                            if (state is LoginUiState.Success) {
                                                completeLogin(state.user.name)
                                                authViewModel.resetLoginState()
                                            }
                                        }

                                        LaunchedEffect(phoneAuthState) {
                                            val state = phoneAuthState
                                            if (state is PhoneAuthState.NeedsSignup) {
                                                navController.navigate(
                                                    "complete_phone_signup/${Uri.encode(state.mobile)}/${Uri.encode(state.proofToken)}"
                                                )
                                            }
                                        }

                                        OtpEntryScreen(
                                            mobileDisplay = mobile,
                                            onBack = {
                                                authViewModel.resetPhoneAuthState()
                                                navController.popBackStack()
                                            },
                                            onVerify = { code -> authViewModel.verifyPhoneOtp(mobile, code) },
                                            onResend = { authViewModel.sendPhoneOtp(mobile) },
                                            isLoading = phoneAuthState is PhoneAuthState.Verifying,
                                            errorMessage = (phoneAuthState as? PhoneAuthState.Error)?.message
                                        )
                                    }

                                    composable("complete_phone_signup/{mobile}/{proofToken}") { backStackEntry ->
                                        val mobile = backStackEntry.arguments?.getString("mobile").orEmpty()
                                        val proofToken = backStackEntry.arguments?.getString("proofToken").orEmpty()
                                        val phoneAuthState by authViewModel.phoneAuthState.collectAsState()
                                        val loginState by authViewModel.loginState.collectAsState()

                                        LaunchedEffect(loginState) {
                                            val state = loginState
                                            if (state is LoginUiState.Success) {
                                                completeLogin(state.user.name)
                                                authViewModel.resetLoginState()
                                            }
                                        }

                                        CompletePhoneSignupScreen(
                                            onBack = {
                                                authViewModel.resetPhoneAuthState()
                                                navController.popBackStack()
                                            },
                                            onComplete = { fname, lname ->
                                                authViewModel.completePhoneSignup(mobile, proofToken, fname, lname)
                                            },
                                            isLoading = phoneAuthState is PhoneAuthState.Completing,
                                            errorMessage = (phoneAuthState as? PhoneAuthState.Error)?.message
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
                                            authRepository = authRepository,
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
                                            onBack = { }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        intent.getStringExtra(AfamFreshMessagingService.EXTRA_ORDER_ID)?.let {
            Log.d("MainActivity", "Opened from notification for order $it")
            pendingOrderId.value = it
            pendingOrderSource.value = intent.getStringExtra(AfamFreshMessagingService.EXTRA_SOURCE)
        }

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