package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
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
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.components.ProductGridSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.CartViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel

@Composable
fun HomeScreen(
    userName: String,
    currentRole: String,
    availableRoles: List<String>,
    onRoleSwitch: (String) -> Unit,
    onLogout: () -> Unit,
    onProductClick: (Product) -> Unit,
    onOrdersClick: () -> Unit,
    onProfileClick: () -> Unit,
    onBulkClick: () -> Unit,
    onCartClick: () -> Unit,
    productViewModel: ProductViewModel,
    cartViewModel: CartViewModel,
    unreadNotifications: Int,
    onNotificationsClick: () -> Unit
) {
    val products by productViewModel.products.collectAsState()
    val isLoadingProducts by productViewModel.isLoading.collectAsState()
    val productsError by productViewModel.error.collectAsState()
    val canRetryProducts by productViewModel.canRetry.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }

    val filters = listOf("All", "Hot sale", "Popularity")
    val visibleProducts = remember(products, searchQuery) {
        if (searchQuery.isBlank()) products
        else products.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(containerColor = Cream) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // ===== Header matching the mockup styling =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onProfileClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(PillGray)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = Ink,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Hello, $userName",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Ink
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNotificationsClick) {
                        BadgedBox(
                            badge = {
                                if (unreadNotifications > 0) {
                                    Badge(containerColor = Tomato) {
                                        Text(
                                            if (unreadNotifications > 99) "99+" else "$unreadNotifications",
                                            color = Color.White,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = "Notifications",
                                tint = Ink
                            )
                        }
                    }
                    IconButton(onClick = onCartClick) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = "Cart", tint = Ink)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Search Pill Bar (Rounded pill shape from mockup) =====
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Search here...",
                        color = InkMuted,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = InkMuted,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = CircleShape,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = CardWhite,
                    focusedContainerColor = CardWhite,
                    unfocusedBorderColor = ForestSurface,
                    focusedBorderColor = Forest
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Section Title & Category Filter Pills =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Products",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { filter ->
                        val selected = filter == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) Forest else PillGray)
                                .clickable { selectedFilter = filter }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter,
                                color = if (selected) Color.White else Ink,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Product Grid Area =====
            if (isLoadingProducts && products.isEmpty()) {
                ProductGridSkeleton(modifier = Modifier.weight(1f))
            } else if (productsError != null && products.isEmpty()) {
                ErrorState(
                    message = productsError ?: "",
                    modifier = Modifier.weight(1f),
                    onRetry = if (canRetryProducts) ({ productViewModel.loadProducts() }) else null
                )
            } else if (visibleProducts.isEmpty()) {
                if (searchQuery.isNotBlank()) {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No matches for \"$searchQuery\"",
                        detail = "Try a different spelling or a broader word.",
                        modifier = Modifier.weight(1f),
                        actionLabel = "CLEAR SEARCH",
                        onAction = { searchQuery = "" }
                    )
                } else {
                    EmptyState(
                        icon = Icons.Default.ShoppingCart,
                        title = "Nothing available yet",
                        detail = "There are no products to show right now.",
                        modifier = Modifier.weight(1f),
                        actionLabel = "REFRESH",
                        onAction = { productViewModel.loadProducts() }
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(visibleProducts, key = { it.id }) { product ->
                        MockupStyleProductCard(
                            product = product,
                            onClick = { onProductClick(product) },
                            onQuickAdd = { cartViewModel.addToCart(product, 1) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MockupStyleProductCard(
    product: Product,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Image Container with Soft Card Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ForestSurface),
            contentAlignment = Alignment.Center
        ) {
            NetworkImage(
                model = product.imageUrl,
                contentDescription = product.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )

            // Favorite Icon Overlay
            IconButton(
                onClick = { /* toggle favorite */ },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(CardWhite)
            ) {
                Icon(
                    Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = InkMuted,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Product Name
        Text(
            text = product.name,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Pack Size / Weight & Discount
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = product.packLabel ?: "1 unit",
                fontSize = 12.sp,
                color = InkMuted
            )
            if (product.hasDiscount) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "-${product.discountPercent.toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Tomato
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Price
        Text(
            text = formatUgx(product.price),
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp,
            color = Ink
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Mockup-style "Add to Cart" Pill Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .border(1.dp, Forest, CircleShape)
                .clickable { onQuickAdd() }
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Add to Cart",
                color = Forest,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
