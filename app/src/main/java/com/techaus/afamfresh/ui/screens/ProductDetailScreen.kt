package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
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

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's
// composable("product_detail/{productId}") call:
//   ProductDetailScreen(product, onBack, onAddToCart)
@Composable
fun ProductDetailScreen(
    product: Product?,
    onBack: () -> Unit,
    onAddToCart: (Product, Int) -> Unit
) {
    var quantity by remember { mutableStateOf(1) }

    if (product == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Item not found", color = InkMuted)
        }
        return
    }

    Scaffold(
        containerColor = Cream,
        bottomBar = {
            Surface(color = Cream, shadowElevation = 8.dp) {
                Button(
                    onClick = { onAddToCart(product, quantity) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest),
                    enabled = product.inStock
                ) {
                    Text(
                        if (product.inStock) "Add To Cart" else "Out of stock",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ===== Hero image with green rounded-bottom header, back + favorite icons =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(ForestSurface, shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            ) {
                NetworkImage(
                    model = product.imageUrl,
                    contentDescription = product.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.clip(CircleShape).background(CardWhite.copy(alpha = 0.9f))
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                    }
                    IconButton(
                        onClick = { /* favorite - not wired to backend yet */ },
                        modifier = Modifier.clip(CircleShape).background(CardWhite.copy(alpha = 0.9f))
                    ) {
                        Icon(Icons.Default.FavoriteBorder, contentDescription = "Favorite", tint = Ink)
                    }
                }

                // Quantity stepper overlapping the bottom edge of the image, like the mockup
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 28.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Forest)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (quantity > 1) quantity-- }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = "$quantity",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                    IconButton(onClick = { quantity++ }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(product.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)

                Spacer(modifier = Modifier.height(8.dp))
                product.description?.let {
                    Text(it, fontSize = 14.sp, color = InkMuted, lineHeight = 20.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ===== Product facts =====
                //
                // This used to show a star rating, calorie count, prep time and
                // an ingredient-icon strip. None of those columns exist on
                // `items`, so the server could never populate them and this
                // whole block rendered blank. Replaced with the catalogue's real
                // attributes.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    product.packLabel?.let {
                        Text("📦 $it", fontSize = 13.sp, color = Ink)
                    }
                    product.category?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 13.sp, color = InkMuted)
                    }
                    if (product.isWeeklyDeal) {
                        Text("⭐ Weekly deal", fontSize = 13.sp, color = Ink)
                    }
                }

                if (product.hasDiscount) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatUgx(product.effectivePrice),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Ink
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "-${product.discountPercent.toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Tomato
                        )
                    }
                }

                product.freshnessDate?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Fresh until $it", fontSize = 13.sp, color = InkMuted)
                }
            }
        }
    }
}

@Composable
private fun BadgeItem(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, color = Ink)
    }
}
