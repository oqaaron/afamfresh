package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.techaus.afamfresh.viewmodel.FavoritesViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel
import kotlinx.coroutines.delay

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
    onBrowseClick: () -> Unit,
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

    val visibleProducts = remember(products, searchQuery, selectedFilter) {
        products
            .filter { it.matches(selectedFilter) }
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(containerColor = Cream) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ===== TOP GLOVO BUBBLE HEADER (COLLAPSES WITH SCROLL) =====
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForestSurface)
                        .padding(bottom = 18.dp)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Location Pill and Action Icons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = CardWhite,
                            shadowElevation = 1.dp,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable { onProfileClick() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = "Location",
                                    tint = Forest,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Deliver to Kampala • $userName",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = InkMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
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
                                Icon(
                                    Icons.Default.ShoppingCart,
                                    contentDescription = "Cart",
                                    tint = Ink
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Glovo-style 3-Row Bubble Grid
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            GlovoBubble(
                                icon = Icons.Default.Spa,
                                title = "Groceries",
                                isSelected = selectedFilter == HomeFilter.All,
                                onClick = { selectedFilter = HomeFilter.All }
                            )
                            GlovoBubble(
                                icon = Icons.Default.Inventory2,
                                title = "Bulk Deals",
                                isSelected = false,
                                onClick = onBulkClick
                            )
                            GlovoBubble(
                                icon = Icons.Default.LocalFireDepartment,
                                title = "Hot Sale",
                                isSelected = selectedFilter == HomeFilter.HotSale,
                                onClick = {
                                    selectedFilter = if (selectedFilter == HomeFilter.HotSale) HomeFilter.All else HomeFilter.HotSale
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            GlovoBubble(
                                icon = Icons.Default.Sell,
                                title = "Promos",
                                isSelected = selectedFilter == HomeFilter.Promos,
                                onClick = {
                                    selectedFilter = if (selectedFilter == HomeFilter.Promos) HomeFilter.All else HomeFilter.Promos
                                }
                            )
                            GlovoBubble(
                                icon = Icons.Default.Bolt,
                                title = "Flash Sales",
                                isSelected = selectedFilter == HomeFilter.FlashSales,
                                onClick = {
                                    selectedFilter = if (selectedFilter == HomeFilter.FlashSales) HomeFilter.All else HomeFilter.FlashSales
                                }
                            )
                            GlovoBubble(
                                icon = Icons.Default.ShoppingCart,
                                title = "Orders",
                                isSelected = false,
                                onClick = onOrdersClick
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            GlovoBubble(
                                icon = Icons.Default.ViewAgenda,
                                title = "Browse",
                                isSelected = false,
                                onClick = onBrowseClick
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // ===== SEARCH & TITLE HEADER =====
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text("Search fresh produce, fruits...", color = InkMuted, fontSize = 13.sp)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = InkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CircleShape,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = CardWhite,
                            focusedContainerColor = CardWhite,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Forest
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "Search Results" else "$userName, these are for you",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = InkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ===== PRODUCT CATALOGUE / STATES =====
            if (isLoadingProducts && products.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProductGridSkeleton(modifier = Modifier.fillMaxWidth().height(350.dp))
                }
            } else if (productsError != null && products.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ErrorState(
                        message = productsError ?: "",
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        onRetry = if (canRetryProducts) ({ productViewModel.loadProducts() }) else null
                    )
                }
            } else if (visibleProducts.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No products found",
                        detail = "Try a different search term or remove filters.",
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        actionLabel = "CLEAR FILTER",
                        onAction = {
                            searchQuery = ""
                            selectedFilter = HomeFilter.All
                        }
                    )
                }
            } else {
                items(visibleProducts, key = { it.id }) { product ->
                    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                        ProductCard(
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
private fun GlovoBubble(
    icon: ImageVector,
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isSelected) Forest else CardWhite)
                .then(
                    if (!isSelected) Modifier.border(1.5.dp, ForestSurface, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) Color.White else Forest,
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Surface(
            shape = CircleShape,
            color = CardWhite,
            shadowElevation = 1.dp
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ProductCard(
    product: Product,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit,
    onToggleFavorite: () -> Unit
) {
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
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(105.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ForestSurface),
            contentAlignment = Alignment.Center
        ) {
            NetworkImage(
                model = product.imageUrl,
                contentDescription = product.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            )

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
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = product.name,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = product.packLabel ?: "1 unit",
                fontSize = 11.sp,
                color = InkMuted
            )
            if (product.hasDiscount) {
                Text(
                    text = "-${product.discountPercent.toInt()}%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Tomato
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatUgx(product.price),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                color = Ink
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (justAdded) Forest else ForestSurface)
                    .clickable {
                        onQuickAdd()
                        justAdded = true
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                if (justAdded) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                } else {
                    Text(
                        text = "+ Add",
                        color = Forest,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
