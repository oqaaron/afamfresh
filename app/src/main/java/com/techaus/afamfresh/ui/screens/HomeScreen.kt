package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.AppConfigResponse
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.ui.components.AutoSlidingPromoBanner
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.components.ProductGridSkeleton
import com.techaus.afamfresh.ui.components.PromoSlide
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.AddressViewModel
import com.techaus.afamfresh.viewmodel.CartViewModel
import com.techaus.afamfresh.viewmodel.FavoritesViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel

private const val GROCERIES_CATEGORY = "Groceries"
private val FRESH_FOOD_CATEGORIES = setOf(
    "vegetables", "vegetable",
    "fruits", "fruit",
    "meats", "meat",
    "fish products", "fish",
    "chicken products", "chicken", "poultry"
)

private sealed class HomeFilter {
    object All : HomeFilter()
    object HotSale : HomeFilter()
    object Promos : HomeFilter()
    object FlashSales : HomeFilter()
    object FreshFood : HomeFilter()
    data class Category(val name: String) : HomeFilter()
}

private fun Product.matches(filter: HomeFilter): Boolean = when (filter) {
    HomeFilter.All -> true
    HomeFilter.HotSale -> hasDiscount
    HomeFilter.Promos -> isOffer
    HomeFilter.FlashSales -> isWeeklyDeal
    HomeFilter.FreshFood -> category?.trim()?.lowercase() in FRESH_FOOD_CATEGORIES
    is HomeFilter.Category -> category?.trim().equals(filter.name, ignoreCase = true)
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
    onLocationClick: () -> Unit,
    onProfileClick: () -> Unit,
    onBulkClick: () -> Unit,
    onCartClick: () -> Unit,
    onBrowseClick: () -> Unit,
    onPromoClick: () -> Unit,
    productViewModel: ProductViewModel,
    cartViewModel: CartViewModel,
    favoritesViewModel: FavoritesViewModel,
    addressViewModel: AddressViewModel,
    appConfig: AppConfigResponse? = null,
    unreadNotifications: Int,
    onNotificationsClick: () -> Unit
) {
    val products by productViewModel.products.collectAsState()
    val isLoadingProducts by productViewModel.isLoading.collectAsState()
    val productsError by productViewModel.error.collectAsState()
    val addresses by addressViewModel.addresses.collectAsState()
    val favoriteIds by favoritesViewModel.favoriteIds.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<HomeFilter>(HomeFilter.All) }

    val visibleProducts = remember(products, searchQuery, selectedFilter) {
        products
            .filter { it.matches(selectedFilter) }
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    }

    val promoSlides = remember(appConfig, products) {
        val slides = mutableListOf<PromoSlide>()
        val baseBannerTitle = appConfig?.promoBannerTitle ?: "Get up to 40% off\non your first order\nfrom app."
        val baseBtn = appConfig?.promoBannerButtonText ?: "Shop Now"
        val baseImg = appConfig?.promoBannerImageUrl

        slides.add(PromoSlide("1", baseBannerTitle, baseBtn, baseImg))

        products.filter { it.hasDiscount || it.isOffer || it.isWeeklyDeal }.take(3).forEach { deal ->
            slides.add(
                PromoSlide(
                    id = deal.id.toString(),
                    title = "Deal: ${deal.name}\nNow ${formatUgx(deal.effectivePrice)}",
                    buttonText = if (deal.hasDiscount) "Save ${deal.discountPercent.toInt()}%" else "Shop Deal",
                    imageUrl = deal.imageUrl
                )
            )
        }
        slides
    }

    Scaffold(
        containerColor = Color(0xFFF9FAF9)
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                        .background(Forest)
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onProfileClick,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f))
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Profile",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { onLocationClick() }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Location",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                                val currentArea = addresses.firstOrNull { it.isDefault }?.area ?: "Kampala, Uganda"
                                Text(
                                    currentArea,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            IconButton(
                                onClick = onNotificationsClick,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f))
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (unreadNotifications > 0) {
                                            Badge(containerColor = Tomato) {
                                                Text(
                                                    "$unreadNotifications",
                                                    color = Color.White,
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        contentDescription = "Notifications",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text("Search Your Groceries", color = InkMuted, fontSize = 14.sp)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
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
                                unfocusedContainerColor = Color.White,
                                focusedContainerColor = Color.White,
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent
                            )
                        )
                    }
                }
            }

            if (appConfig?.promoBannerActive != false) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AutoSlidingPromoBanner(
                        slides = promoSlides,
                        slideDurationMs = 3500L,
                        onSlideClick = { onPromoClick() }
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Categories",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        Text(
                            "See all",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Forest,
                            modifier = Modifier.clickable { onBrowseClick() }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            CategoryBubbleItem(
                                title = "Veggies",
                                icon = Icons.Default.Spa,
                                isSelected = selectedFilter == HomeFilter.FreshFood
                            ) {
                                selectedFilter =
                                    if (selectedFilter == HomeFilter.FreshFood) HomeFilter.All else HomeFilter.FreshFood
                            }
                        }
                        item {
                            CategoryBubbleItem(
                                title = "Fruits",
                                icon = Icons.Default.Spa,
                                isSelected = selectedFilter == HomeFilter.Category("Fruits")
                            ) {
                                selectedFilter =
                                    if (selectedFilter == HomeFilter.Category("Fruits")) HomeFilter.All else HomeFilter.Category("Fruits")
                            }
                        }
                        item {
                            CategoryBubbleItem(
                                title = "Groceries",
                                icon = Icons.Default.ShoppingCart,
                                isSelected = selectedFilter == HomeFilter.Category(GROCERIES_CATEGORY)
                            ) {
                                selectedFilter =
                                    if (selectedFilter == HomeFilter.Category(GROCERIES_CATEGORY)) HomeFilter.All else HomeFilter.Category(GROCERIES_CATEGORY)
                            }
                        }
                        item {
                            CategoryBubbleItem(
                                title = "Merchant",
                                icon = Icons.Default.ViewAgenda,
                                isSelected = false,
                                onClick = onBulkClick
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "Search Results" else "Popular",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        Text(
                            "See all",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Forest,
                            modifier = Modifier.clickable {
                                searchQuery = ""
                                selectedFilter = HomeFilter.All
                            }
                        )
                    }
                }
            }

            if (isLoadingProducts && products.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProductGridSkeleton(modifier = Modifier.fillMaxWidth().height(300.dp))
                }
            } else if (productsError != null && products.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ErrorState(
                        message = productsError ?: "",
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                    )
                }
            } else if (visibleProducts.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No products found",
                        detail = "Try adjusting your search or filters.",
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                    )
                }
            } else {
                items(visibleProducts, key = { it.id }) { product ->
                    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                        ModernProductCard(
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
private fun CategoryBubbleItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(if (isSelected) Forest else ForestSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = if (isSelected) Color.White else Forest,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
fun ModernProductCard(
    product: Product,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF7FAF7)),
            contentAlignment = Alignment.Center
        ) {
            NetworkImage(
                model = product.imageUrl,
                contentDescription = product.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            )

            if (product.discountPercent > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Tomato)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "-${product.discountPercent.toInt()}%",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) Tomato else Color(0xFFB0B8B2),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = product.name,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(4) {
                Icon(Icons.Default.Star, contentDescription = null, tint = StarYellow, modifier = Modifier.size(12.dp))
            }
            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFD8DCD8), modifier = Modifier.size(12.dp))
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = formatUgx(product.effectivePrice),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Forest
                )
                if (product.hasDiscount) {
                    Text(
                        text = formatUgx(product.price),
                        fontSize = 11.sp,
                        color = InkMuted,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Forest)
                    .clickable { onQuickAdd() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}