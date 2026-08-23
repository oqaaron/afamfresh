package com.techaus.afamfresh.ui.screens

import com.techaus.afamfresh.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.components.ProductGridSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.AddressViewModel
import com.techaus.afamfresh.viewmodel.CartViewModel
import com.techaus.afamfresh.viewmodel.FavoritesViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Row 18 of the `category` table — the dry-goods aisle (sugar, tea, bottled
 * water), not the catalogue as a whole. Verified against api/schema.sql rather
 * than assumed.
 */
private const val GROCERIES_CATEGORY = "Groceries"

/**
 * Categories the "Fresh Food" bubble collapses into one view.
 *
 * The canonical names come from the `category` table in api/schema.sql, which
 * is the list the catalogue is actually filed under:
 *
 *     Fruits, Juice, Vegetables, Oils, Meats, Dairy Products,
 *     Chicken Products, Dry Ratio, Groceries, Grains, Fish Products,
 *     Meal Plans, Condiments, Beverages
 *
 * Note "Chicken Products" and "Fish Products" — NOT "chicken" and "fish". An
 * earlier guess at those two names matched nothing, so poultry and fish were
 * silently missing from this bubble.
 *
 * The singular/bare variants are kept as a safety net because nothing enforces
 * that list: api/admin/add-product.php:89 is a plain
 * `<input type="text" name="category" required>`, so an admin can file a
 * product under "Fruit" or "Chicken" and it will still appear here. Matching is
 * trimmed and lowercased for the same reason.
 */
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
    /** Fresh Picks banner's "Grab Offer" — the only filter that's an OR of
     *  two others rather than its own single condition. */
    object PromosAndFlashSales : HomeFilter()
    data class Category(val name: String) : HomeFilter()
}

private fun Product.matches(filter: HomeFilter): Boolean = when (filter) {
    HomeFilter.All -> true
    HomeFilter.HotSale -> hasDiscount
    HomeFilter.Promos -> isOffer
    HomeFilter.FlashSales -> isWeeklyDeal
    HomeFilter.PromosAndFlashSales -> isOffer || isWeeklyDeal
    HomeFilter.FreshFood -> category?.trim()?.lowercase() in FRESH_FOOD_CATEGORIES
    // Case-insensitive for the same free-text reason as FRESH_FOOD_CATEGORIES:
    // an exact == meant a product filed under "groceries" never matched the
    // Groceries bubble.
    is HomeFilter.Category -> category?.trim().equals(filter.name, ignoreCase = true)
}

/** "Good morning"/"afternoon"/"evening" — the mockup only showed one example
 *  state; a real greeting should track time of day rather than always say
 *  "morning" at 8pm. */
private fun timeBasedGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}

private enum class PromoBannerId { FRESH_PICKS, WEEKEND_DEAL }

private data class PromoBanner(val id: PromoBannerId, val label: String, val headline: String, val ctaLabel: String)

// ⚠️ PLACEHOLDER CONTENT. No promotions/banners endpoint exists anywhere in
// what's been shared with me — these are static examples so the carousel has
// something to show, not real campaign data. Every install currently shows
// the same two banners; wire this to a real feed once one exists. Copy also
// adapted to UGX/this app's own locale rather than the mockup's literal
// "Ramadan Offers... 25%... $" example.
private val PROMO_BANNERS = listOf(
    PromoBanner(PromoBannerId.FRESH_PICKS, "Fresh picks", "Get 25% off your first order", "Grab Offer"),
    PromoBanner(PromoBannerId.WEEKEND_DEAL, "Weekend deal", "Free delivery over UGX 50,000", "Shop Now")
)

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
    productViewModel: ProductViewModel,
    cartViewModel: CartViewModel,
    favoritesViewModel: FavoritesViewModel,
    addressViewModel: AddressViewModel,
    unreadNotifications: Int,
    onNotificationsClick: () -> Unit
) {
    val products by productViewModel.products.collectAsState()
    val isLoadingProducts by productViewModel.isLoading.collectAsState()
    val productsError by productViewModel.error.collectAsState()
    val addresses by addressViewModel.addresses.collectAsState()
    val canRetryProducts by productViewModel.canRetry.collectAsState()
    val favoriteIds by favoritesViewModel.favoriteIds.collectAsState()
    // ⚠️ ASSUMED property name — CartViewModel.kt hasn't been shared with me.
    // Every other ViewModel here exposes its list as a StateFlow named after
    // the plural noun (products, orders, favoriteIds), so this follows that
    // convention. If CartViewModel actually calls it something else, this is
    // the one line that needs the real name.
    val cartItems by cartViewModel.cartItems.collectAsState()
    val cartItemCount = cartItems.sumOf { it.quantity }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<HomeFilter>(HomeFilter.All) }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    val visibleProducts = remember(products, searchQuery, selectedFilter) {
        products
            .filter { it.matches(selectedFilter) }
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ===== GREETING + LOCATION HEADER =====
            // No separate colored band behind this (the previous ForestSurface
            // header block) — the mockup runs everything on one flat
            // background, so this now just sits on Scaffold's own background.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Initial-letter avatar — no avatar image/URL is
                            // passed into this screen (only userName), so
                            // there's no real photo to show. Swap for a real
                            // Image(...) once a profile picture is available.
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = userName.trim().firstOrNull()?.uppercase() ?: "?",
                                    color = Forest,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = timeBasedGreeting(),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = userName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(onClick = onCartClick) {
                                BadgedBox(
                                    badge = {
                                        if (cartItemCount > 0) {
                                            Badge(containerColor = Tomato) {
                                                Text(
                                                    if (cartItemCount > 99) "99+" else "$cartItemCount",
                                                    color = Color.White,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.ShoppingCart,
                                        contentDescription = "Cart",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Location — split out from the previous combined
                            // "Deliver to X • Name" pill now that the name has
                            // its own place in the greeting above.
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 1.dp,
                                modifier = Modifier.clickable { onLocationClick() }
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
                                    val deliveryArea = addresses.firstOrNull { it.isDefault }?.area ?: "Kampala"
                                    Text(
                                        text = deliveryArea,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ===== SEARCH =====
                    // Moved up to right after the header, matching the
                    // mockup's position — it used to sit below the whole
                    // category bubble grid.
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text("Search category", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = CircleShape,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Forest
                        )
                    )
                }
            }

            // ===== PROMO BANNER CAROUSEL =====
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(18.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    lazyRowItems(PROMO_BANNERS) { banner ->
                        PromoBannerCard(
                            banner = banner,
                            onClick = {
                                when (banner.id) {
                                    PromoBannerId.FRESH_PICKS -> {
                                        // Both conditions filter this same
                                        // screen's grid, so this can set the
                                        // filter AND bring the results into
                                        // view — no navigation conflict here,
                                        // unlike Weekend Deal below.
                                        selectedFilter = HomeFilter.PromosAndFlashSales
                                        coroutineScope.launch {
                                            gridState.animateScrollToItem(3)
                                        }
                                    }
                                    PromoBannerId.WEEKEND_DEAL -> {
                                        // "Bulk Deals and Hot Sale" can't both
                                        // happen from one tap — Bulk Deals
                                        // navigates to an entirely different
                                        // screen/data source (Bulk_listings,
                                        // not this screen's product catalogue),
                                        // while Hot Sale filters THIS grid.
                                        // Bulk Deals wins, per instruction —
                                        // Hot Sale filtering is dropped here.
                                        onBulkClick()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // ===== CATEGORIES =====
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(22.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Categories",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "See all",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Forest,
                            modifier = Modifier.clickable { onBrowseClick() }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Same 8 entries as before — Bulk Deals/Promos/Flash Sales
                    // are real, separate features (not folded into a generic
                    // "Categories" grab-bag), so nothing here was dropped to
                    // match the mockup's simpler 4-icon example. Only the
                    // GlovoBubble visual style changed (flat circle, no
                    // shadow — the new reference shows no elevation, unlike
                    // the previous lifted-shadow pass).
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            GlovoBubble(
                                drawableRes = R.drawable.groceries,
                                title = "Groceries",
                                isSelected = selectedFilter == HomeFilter.Category(GROCERIES_CATEGORY),
                                onClick = {
                                    selectedFilter = if (selectedFilter == HomeFilter.Category(GROCERIES_CATEGORY))
                                        HomeFilter.All
                                    else
                                        HomeFilter.Category(GROCERIES_CATEGORY)
                                }
                            )
                            GlovoBubble(
                                drawableRes = R.drawable.bulkdeals,
                                title = "Bulk Deals",
                                isSelected = false,
                                onClick = onBulkClick
                            )
                            GlovoBubble(
                                drawableRes = R.drawable.hotsales,
                                title = "Hot Sale",
                                isSelected = selectedFilter == HomeFilter.HotSale,
                                onClick = {
                                    selectedFilter = if (selectedFilter == HomeFilter.HotSale) HomeFilter.All else HomeFilter.HotSale
                                }
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            GlovoBubble(
                                drawableRes = R.drawable.promos,
                                title = "Promos",
                                isSelected = selectedFilter == HomeFilter.Promos,
                                onClick = {
                                    selectedFilter = if (selectedFilter == HomeFilter.Promos) HomeFilter.All else HomeFilter.Promos
                                }
                            )
                            GlovoBubble(
                                drawableRes = R.drawable.flashsales,
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
                        // Two bubbles, so SpaceEvenly rather than SpaceAround:
                        // it lands them at 1/3 and 2/3 of the width, sitting
                        // in the gaps of the three-bubble rows above instead
                        // of hanging off the edges.
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlovoBubble(
                                icon = Icons.Default.Spa,
                                title = "Fresh Food",
                                isSelected = selectedFilter == HomeFilter.FreshFood,
                                onClick = {
                                    selectedFilter = if (selectedFilter == HomeFilter.FreshFood) HomeFilter.All else HomeFilter.FreshFood
                                }
                            )
                            GlovoBubble(
                                icon = Icons.Default.ViewAgenda,
                                title = "Browse",
                                isSelected = false,
                                onClick = onBrowseClick
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "Search Results" else "Best selling",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
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
private fun PromoBannerCard(banner: PromoBanner, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Forest)
            .clickable { onClick() }
            .padding(20.dp)
    ) {
        Text(banner.label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            banner.headline,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 26.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Surface(shape = CircleShape, color = Color.White) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(banner.ctaLabel, color = Forest, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Forest,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun GlovoBubble(
    icon: ImageVector? = null,
    drawableRes: Int? = null,
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
                // Flat, no shadow — the newer mockup (Home.pdf) shows solid
                // flat circles with no visible elevation, unlike the earlier
                // Toppito-inspired pass this replaces.
                .background(if (isSelected) Forest else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (drawableRes != null) {
                Image(
                    painter = painterResource(id = drawableRes),
                    contentDescription = title,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isSelected) Color.White else Forest,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 76.dp)
        )
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
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(105.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            NetworkImage(
                model = product.imageUrl,
                contentDescription = product.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(6.dp)
            )

            if (product.hasDiscount) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Tomato,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                ) {
                    Text(
                        text = "-${product.discountPercent.toInt()}%",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) Tomato else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }

            // Add button lives HERE now, overlapping the image's bottom-right
            // corner — not the whole card's. Screenshots showed it sitting
            // directly on top of the price text below when it overlapped the
            // card instead, covering the last few digits ("UGX 9...", "UGX
            // 20..."). Mirrors the favorite heart's overlap above: both float
            // on the photo, text content below is never touched by either.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(30.dp)
                    .shadow(elevation = 3.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Forest)
                    .clickable {
                        onQuickAdd()
                        justAdded = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (justAdded) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (justAdded) "Added" else "Add to cart",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = product.name,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Pack label and price as separate Text elements — price has no
        // weight, so it's measured at full size first and can never be the
        // one that gets cut off; packLabel takes whatever's left and
        // truncates if it has to. No floating button anywhere near this row
        // now either, so nothing can visually cover it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = product.packLabel ?: "1 unit",
                fontSize = 11.sp,
                color = Tomato,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formatUgx(product.price),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Tomato,
                maxLines = 1
            )
        }
    }
}
