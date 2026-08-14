package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.techaus.afamfresh.viewmodel.FavoritesViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel
import kotlinx.coroutines.delay

/**
 * Replaces the discontinued web storefront's homepage curation
 * (`items.homepage`/`offer`/`weekly_deal`) with real, in-app browsing. Only
 * one filter is active at a time, so "what is the customer looking at right
 * now" always has a single answer.
 */
private sealed class HomeFilter {
    object All : HomeFilter()
    object HotSale : HomeFilter()
    object Promos : HomeFilter()
    object FlashSales : HomeFilter()
    data class Category(val name: String) : HomeFilter()
}

private fun Product.matches(filter: HomeFilter): Boolean = when (filter) {
    HomeFilter.All -> true
    HomeFilter.HotSale -> hasDiscount
    HomeFilter.Promos -> isOffer
    HomeFilter.FlashSales -> isWeeklyDeal
    is HomeFilter.Category -> category == filter.name
}

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
    favoritesViewModel: FavoritesViewModel,
    unreadNotifications: Int,
    onNotificationsClick: () -> Unit
) {
    val products by productViewModel.products.collectAsState()
    val isLoadingProducts by productViewModel.isLoading.collectAsState()
    val productsError by productViewModel.error.collectAsState()
    val canRetryProducts by productViewModel.canRetry.collectAsState()
    val favoriteIds by favoritesViewModel.favoriteIds.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<HomeFilter>(HomeFilter.All) }

    // Derived from whatever is already loaded — no separate categories call
    // needed, the full catalogue is already in `products`.
    val categories = remember(products) {
        products.mapNotNull { it.category?.trim()?.takeIf { c -> c.isNotEmpty() } }
            .distinct()
            .sorted()
    }

    val visibleProducts = remember(products, searchQuery, selectedFilter) {
        products
            .filter { it.matches(selectedFilter) }
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
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

            Spacer(modifier = Modifier.height(10.dp))

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

            Spacer(modifier = Modifier.height(10.dp))

            // ===== Status quick-access row: Hot Sale / Promos / Flash Sales =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusTile(
                    icon = Icons.Default.LocalFireDepartment,
                    label = "Hot Sale",
                    selected = selectedFilter == HomeFilter.HotSale,
                    onClick = {
                        selectedFilter =
                            if (selectedFilter == HomeFilter.HotSale) HomeFilter.All else HomeFilter.HotSale
                    }
                )
                StatusTile(
                    icon = Icons.Default.Sell,
                    label = "Promos",
                    selected = selectedFilter == HomeFilter.Promos,
                    onClick = {
                        selectedFilter =
                            if (selectedFilter == HomeFilter.Promos) HomeFilter.All else HomeFilter.Promos
                    }
                )
                StatusTile(
                    icon = Icons.Default.Bolt,
                    label = "Flash Sales",
                    selected = selectedFilter == HomeFilter.FlashSales,
                    onClick = {
                        selectedFilter =
                            if (selectedFilter == HomeFilter.FlashSales) HomeFilter.All else HomeFilter.FlashSales
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryPill(
                    label = "All",
                    selected = selectedFilter == HomeFilter.All,
                    onClick = { selectedFilter = HomeFilter.All }
                )
                categories.forEach { category ->
                    CategoryPill(
                        label = category,
                        selected = selectedFilter == HomeFilter.Category(category),
                        onClick = { selectedFilter = HomeFilter.Category(category) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                } else if (selectedFilter != HomeFilter.All) {
                    val filterLabel = when (val filter = selectedFilter) {
                        HomeFilter.HotSale -> "Hot Sale"
                        HomeFilter.Promos -> "Promos"
                        HomeFilter.FlashSales -> "Flash Sales"
                        is HomeFilter.Category -> filter.name
                        HomeFilter.All -> ""
                    }
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "Nothing in $filterLabel right now",
                        detail = "Check back later, or browse everything instead.",
                        modifier = Modifier.weight(1f),
                        actionLabel = "CLEAR FILTER",
                        onAction = { selectedFilter = HomeFilter.All }
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
                            isFavorite = product.id in favoriteIds,
                            onClick = { onProductClick(product) },
                            onQuickAdd = { cartViewModel.addToCart(product, 1) },
                            onToggleFavorite = { favoritesViewModel.toggleFavorite(product.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) Forest else ForestSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) Color.White else Forest,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Forest else Ink
        )
    }
}

@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Forest else PillGray)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Ink,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun MockupStyleProductCard(
    product: Product,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    // Brief "Added" confirmation so a tap has visible effect beyond the cart
    // badge changing off-screen — reverts on its own, no user action needed.
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
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(CardWhite)
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) Tomato else InkMuted,
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

        // Mockup-style "Add to Cart" Pill Button — fills solid green with a
        // check for a moment on tap, then reverts to the outlined resting state.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .then(
                    if (justAdded) Modifier.background(Forest)
                    else Modifier.border(1.dp, Forest, CircleShape)
                )
                .clickable {
                    onQuickAdd()
                    justAdded = true
                }
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            if (justAdded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Added",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Text(
                    text = "Add to Cart",
                    color = Forest,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
