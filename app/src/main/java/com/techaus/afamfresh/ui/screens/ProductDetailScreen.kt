package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx

@Composable
fun ProductDetailScreen(
    product: Product?,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onBack: () -> Unit,
    onAddToCart: (Product, Int) -> Unit
) {
    var quantity by remember { mutableStateOf(1) }
    var isExpanded by remember { mutableStateOf(false) }

    if (product == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Product not found", color = NeutralMuted)
        }
        return
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Total Price", fontSize = 12.sp, color = NeutralMuted)
                        Text(
                            text = formatUgx(product.effectivePrice * quantity),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeutralText
                        )
                    }

                    Button(
                        onClick = { onAddToCart(product, quantity) },
                        modifier = Modifier
                            .height(50.dp)
                            .widthIn(min = 180.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EcoGreen),
                        enabled = product.inStock
                    ) {
                        Text(
                            if (product.inStock) "Add to Cart" else "Out of Stock",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ===== HERO IMAGE + ROUNDED HEADER =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Color(0xFFEFF5F0)),
                contentAlignment = Alignment.Center
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = NeutralText, modifier = Modifier.size(18.dp))
                    }

                    Text("Details", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = NeutralText)

                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) Tomato else NeutralText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Image
                NetworkImage(
                    model = product.imageUrl,
                    contentDescription = product.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(190.dp)
                        .padding(top = 28.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== TITLE, RATING & STEPPER =====
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(product.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NeutralText)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            repeat(4) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = StarYellow, modifier = Modifier.size(14.dp))
                            }
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFD8DCD8), modifier = Modifier.size(14.dp))
                        }
                    }

                    // Stepper (- 1 KG +)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFF2F4F2)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (quantity > 1) quantity-- },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = NeutralText, modifier = Modifier.size(14.dp))
                            }

                            Text(
                                text = "$quantity ${product.packLabel ?: "KG"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeutralText,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(EcoGreen)
                                    .clickable { quantity++ },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "${formatUgx(product.effectivePrice)}/${product.packLabel ?: "KG"}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = EcoGreen
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ===== PRODUCT DETAILS DESCRIPTION =====
                Text("Product Details", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NeutralText)
                Spacer(modifier = Modifier.height(6.dp))
                val description = product.description ?: "Fresh farm produce delivered directly to your doorstep with guaranteed freshness and organic certification."
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = NeutralMuted,
                    lineHeight = 20.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 3
                )
                if (description.length > 100) {
                    Text(
                        text = if (isExpanded) "Show Less" else "Read More",
                        color = EcoGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { isExpanded = !isExpanded }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}