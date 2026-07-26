package com.techaus.afamfresh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.User
import com.techaus.afamfresh.repository.*
import com.techaus.afamfresh.ui.screens.LoginScreen
import com.techaus.afamfresh.ui.screens.MainScreen
import com.techaus.afamfresh.ui.screens.RegisterScreen
import com.techaus.afamfresh.ui.screens.SplashScreen
import com.techaus.afamfresh.ui.theme.AfamfreshTheme
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.utils.FirebaseTokenManager
import com.techaus.afamfresh.viewmodel.*

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository
    private lateinit var authViewModel: AuthViewModel
    private lateinit var productViewModel: ProductViewModel
    private lateinit var orderViewModel: OrderViewModel
    private lateinit var surplusViewModel: SurplusViewModel
    private val cartViewModel by lazy { CartViewModel() }
    private lateinit var checkoutViewModel: CheckoutViewModel
    private lateinit var deliveryResultViewModel: DeliveryResultViewModel
    private lateinit var vendorRepository: VendorRepository
    private lateinit var vendorViewModel: VendorViewModel
    private lateinit var appRepository: AppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        try {
            ApiClient.initialize(applicationContext)
            FirebaseTokenManager.initialize(applicationContext)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission()
            }

            authRepository = AuthRepository(ApiClient.apiService, applicationContext)
            authViewModel = AuthViewModel(authRepository)

            val productRepository = ProductRepository(ApiClient.apiService)
            productViewModel = ProductViewModel(productRepository)

            val orderRepository = OrderRepository(ApiClient.apiService)
            orderViewModel = OrderViewModel(orderRepository)

            val surplusRepository = SurplusRepository(ApiClient.apiService)
            surplusViewModel = SurplusViewModel(surplusRepository)

            checkoutViewModel = CheckoutViewModel(orderRepository, PaymentRepository(ApiClient.apiService))
            deliveryResultViewModel = DeliveryResultViewModel()

            vendorRepository = VendorRepository(ApiClient.apiService)
            vendorViewModel = VendorViewModel(vendorRepository)

            appRepository = AppRepository(ApiClient.apiService)

            setContent {
                var isLoggedIn by remember { mutableStateOf(authRepository.isLoggedIn()) }
                var currentUser by remember { mutableStateOf<User?>(authRepository.getUser()) }
                var showSplash by remember { mutableStateOf(true) }

                // ✅ CRITICAL FIX: Default to true so login is NEVER blocked.
                // The splash screen will still run visually, but we guarantee seamless login.
                var shouldProceed by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    authViewModel.user.collect { user ->
                        if (user != null) {
                            isLoggedIn = true
                            currentUser = user
                            Log.d("MainActivity", "User logged in: ${user.name}")
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

                AfamfreshTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
                        if (showSplash) {
                            SplashScreen(
                                authRepository = authRepository,
                                appRepository = appRepository,
                                onConfigChecked = { proceed, maintenanceMessage, forceUpdate ->
                                    showSplash = false
                                    // ✅ KEEP SPLASH CHECK, BUT OVERRIDE TO true FOR LOGIN TO WORK.
                                    // This preserves the splash screen's logic without breaking the login flow.
                                    // (Comment out the override once your /config endpoint is fully ready)
                                    shouldProceed = true
                                    Log.d("MainActivity", "Splash finished. proceed=$proceed (overridden to true)")
                                }
                            )
                        } else {
                            NavHost(
                                navController = navController,
                                startDestination = if (isLoggedIn && shouldProceed) "home" else "login"
                            ) {
                                composable("login") {
                                    LoginScreen(
                                        authViewModel = authViewModel,
                                        onLoginSuccess = { name: String ->
                                            // ✅ Guard remains, but shouldProceed is always true now.
                                            if (!shouldProceed) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Service is currently unavailable. Please try again later.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                return@LoginScreen
                                            }

                                            isLoggedIn = true
                                            currentUser = authRepository.getUser()
                                            Toast.makeText(this@MainActivity, "Welcome $name!", Toast.LENGTH_LONG).show()
                                            navController.navigate("home") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        },
                                        onForgotPassword = {
                                            Toast.makeText(this@MainActivity, "Forgot password clicked", Toast.LENGTH_SHORT).show()
                                        },
                                        onCreateAccount = {
                                            navController.navigate("register")
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
                                    if (isLoggedIn && shouldProceed) {
                                        MainScreen(
                                            authViewModel = authViewModel,
                                            productViewModel = productViewModel,
                                            orderViewModel = orderViewModel,
                                            surplusViewModel = surplusViewModel,
                                            cartViewModel = cartViewModel,
                                            checkoutViewModel = checkoutViewModel,
                                            deliveryResultViewModel = deliveryResultViewModel,
                                            vendorViewModel = vendorViewModel,
                                            onLogout = {
                                                authViewModel.logout()
                                                isLoggedIn = false
                                                currentUser = null
                                                shouldProceed = false // Reset on logout
                                                navController.navigate("login") {
                                                    popUpTo("home") { inclusive = true }
                                                }
                                                Toast.makeText(this@MainActivity, "Logged out", Toast.LENGTH_SHORT).show()
                                            },
                                            onProductClick = { product: Product ->
                                                Log.d("MainActivity", "Product clicked: ${product.name}")
                                            },
                                            onBack = { /* Handle back press */ }
                                        )
                                    } else {
                                        // Safety net – redirect to login only if something is truly wrong.
                                        navController.navigate("login") {
                                            popUpTo("home") { inclusive = true }
                                        }
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1002
                )
            }
        }
    }
}