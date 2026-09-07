package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.techaus.afamfresh.viewmodel.WishlistViewModel

@Composable
fun MainScreen() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                // Add your HomeScreen composable here
            }
            
            composable("favorites") {
                val wishlistViewModel: WishlistViewModel = viewModel()
                FavoriteScreen(
                    viewModel = wishlistViewModel,
                    onBackClick = { navController.popBackStack() },
                    onProductClick = { product ->
                        navController.navigate("product_detail/${product.id}")
                    },
                    onAddToCart = { product ->
                        // Handle quick add to cart action here
                    },
                    onToggleFavorite = { product ->
                        // TODO: Replace with the actual method name from your WishlistViewModel 
                        // (e.g., wishlistViewModel.delete(product), wishlistViewModel.removeFromWishlist(product))
                    }
                )
            }
            
            composable(
                route = "product_detail/{productId}",
                arguments = listOf(navArgument("productId") { type = NavType.StringType })
            ) { backStackEntry ->
                val productId = backStackEntry.arguments?.getString("productId")
                // Handle your Product Detail Screen implementation here
            }
        }
    }
}