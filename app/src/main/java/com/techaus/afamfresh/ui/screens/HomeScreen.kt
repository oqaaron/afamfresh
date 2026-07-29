package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.CartViewModel
import com.techaus.afamfresh.viewmodel.ProductViewModel

// ⚠️ INFERRED screen. Signature matches the exact call in MainScreen.kt's
// composable("home") block. Visual design follows the reference mockup
// (location header, search bar, category pills, 2-col product grid).
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
    onSurplusClick: () -> Unit,
    onCartClick: () -> Unit,
    productViewModel: ProductViewModel,
    cartViewModel: CartViewModel,
    unreadNotifications: Int,
    onNotificationsClick: () -> Unit
) {
    val products by productViewModel.products.collectAsState()
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
            Spacer(modifier = Modifier.height(16.dp))

            // ===== Top bar: location + menu (menu -> profile, mirrors mockup's hamburger) =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Forest)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Hi, $userName", fontWeight = FontWeight.SemiBold, color = Ink)
                }
                Row {
                    IconButton(onClick = onNotificationsClick) {
                        BadgedBox(
                            badge = {
                                if (unreadNotifications > 0) {
                                    Badge(containerColor = Tomato) {
                                        // Cap the label so a large count cannot
                                        // stretch the badge across the bar.
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
                                contentDescription = if (unreadNotifications > 0) {
                                    "Notifications, $unreadNotifications unread"
                                } else {
                                    "Notifications"
                                },
                                tint = Ink
                            )
                        }
                    }
                    IconButton(onClick = onCartClick) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = "Cart", tint = Ink)
                    }
                    IconButton(
                        onClick = onProfileClick,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(PillGray)
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Ink)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                buildString { append("Find The Best") },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            Text(
                "Food Around You",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Search bar =====
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search your favourite food") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { Icon(Icons.Default.Tune, contentDescription = "Filter") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = CardWhite,
                    focusedContainerColor = CardWhite
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ===== Filter pills =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                filters.forEach { filter ->
                    val selected = filter == selectedFilter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Forest else PillGray)
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = filter,
                            color = if (selected) Color.White else Ink,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ===== Product grid =====
            if (visibleProducts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No dishes found", color = InkMuted)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Without a stable key, Compose identifies items by index,
                    // so any change to the list (search, category filter, a
                    // refresh) recomposes every visible cell and drops their
                    // loaded images. Keying by product id means only genuinely
                    // changed items recompose.
                    items(visibleProducts, key = { it.id }) { product ->
                        ProductGridCard(
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
private fun ProductGridCard(
    product: Product,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            NetworkImage(
                model = product.image,
                contentDescription = product.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ForestSurface)
            )
            IconButton(
                onClick = { /* toggle favorite - not wired to backend yet */ },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(CardWhite)
            ) {
                Icon(
                    Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = Ink,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(product.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink)

        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            product.prepTimeMinutes?.let {
                Text("${it}min", fontSize = 12.sp, color = InkMuted)
                Spacer(modifier = Modifier.width(8.dp))
            }
            product.rating?.let {
                Icon(Icons.Default.Star, contentDescription = null, tint = StarYellow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("$it", fontSize = 12.sp, color = InkMuted)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formatUgx(product.price), fontWeight = FontWeight.Bold, color = Ink)
            IconButton(
                onClick = onQuickAdd,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Forest)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Quick add", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}
