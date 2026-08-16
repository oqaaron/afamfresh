package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.CartViewModel
import com.techaus.afamfresh.viewmodel.FavoritesViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel
import kotlinx.coroutines.delay

@Composable
fun BrowseCategoryScreen(
    categoryName: String,
    onBack: () -> Unit,
    onProductClick: (Product) -> Unit,
    productViewModel: ProductViewModel,
    cartViewModel: CartViewModel,
    favoritesViewModel: FavoritesViewModel
) {
    val products by productViewModel.products.collectAsState()
    val favoriteIds by favoritesViewModel.favoriteIds.collectAsState()

    val categoryProducts = remember(products, categoryName) {
        products.filter { it.category == categoryName }
    }

    Scaffold(containerColor = Cream) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Ink
                        )
                    }
                    Text(
                        text = categoryName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${categoryProducts.size} items",
                        fontSize = 13.sp,
                        color = InkMuted
                    )
                }
            }

            if (categoryProducts.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No products in this category",
                            fontSize = 14.sp,
                            color = InkMuted
                        )
                    }
                }
            } else {
                items(categoryProducts) { product ->
                    var justAdded by remember { mutableStateOf(false) }
                    LaunchedEffect(justAdded) {
                        if (justAdded) {
                            delay(900)
                            justAdded = false
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(CardWhite)
                            .clickable { onProductClick(product) }
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            NetworkImage(
                                model = product.imageUrl,
                                contentDescription = product.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            IconButton(
                                onClick = { favoritesViewModel.toggleFavorite(product.id) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    if (product.id in favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (product.id in favoriteIds) Tomato else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = product.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${formatUgx(product.price.toLong())} • ${product.unit}",
                            fontSize = 11.sp,
                            color = InkMuted
                        )

                        if (justAdded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "✓ Added to cart",
                                fontSize = 11.sp,
                                color = Forest,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    cartViewModel.addToCart(product, 1)
                                    justAdded = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Add to cart", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
