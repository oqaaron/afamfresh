package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.DeliveryResultViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("cart") call.
@Composable
fun CartScreen(
    cartItems: List<CartItem>,
    onBack: () -> Unit,
    onRemoveItem: (CartItem) -> Unit,
    onUpdateQuantity: (CartItem, Int) -> Unit,
    onCheckout: () -> Unit,
    deliveryResultViewModel: DeliveryResultViewModel
) {
    var promoCode by remember { mutableStateOf("") }

    val deliveryResult by deliveryResultViewModel.deliveryResult.collectAsState()

    val subtotal = cartItems.sumOf { it.lineTotal }

    // Null until the customer has picked a drop-off point on the map. There is
    // deliberately no fallback rate: the fee is distance-based, so any constant
    // we substituted here would be wrong for every customer except by accident,
    // and it would disagree with the figure they are charged at checkout.
    val deliveryFee: Double? = deliveryResult?.cost

    // Only include delivery once it is actually known, so the total shown here
    // never claims to be final while the fee is still outstanding.
    val total = subtotal + (deliveryFee ?: 0.0)

    Scaffold(containerColor = Cream) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ===== Green header =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Forest, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("Cart", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            if (cartItems.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.ShoppingCart,
                    title = "Your cart is empty",
                    detail = "Add a few things and they'll show up here ready to check out.",
                    // onBack returns to the product list, so this is a genuine
                    // route onward rather than a dead end.
                    actionLabel = "BROWSE PRODUCTS",
                    onAction = onBack
                )
                return@Scaffold
            }

            Column(modifier = Modifier.weight(1f).padding(20.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(cartItems, key = { it.product.id }) { item ->
                        CartItemRow(
                            item = item,
                            onIncrease = { onUpdateQuantity(item, item.quantity + 1) },
                            onDecrease = { onUpdateQuantity(item, item.quantity - 1) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ===== Promo code =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = promoCode,
                        onValueChange = { promoCode = it },
                        placeholder = { Text("Promo Code") },
                        leadingIcon = { Icon(Icons.Default.LocalOffer, contentDescription = null) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = CardWhite,
                            focusedContainerColor = CardWhite
                        )
                    )
                    Button(
                        onClick = { /* ⚠️ not wired — no promo/discount endpoint was shared with me */ },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Forest),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                SummaryRow(label = "Subtotal", display = formatUgx(subtotal))
                Spacer(modifier = Modifier.height(8.dp))
                SummaryRow(
                    label = "Delivery",
                    display = deliveryFee?.let { formatUgx(it) } ?: "Calculated at checkout"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = DividerGray)
                SummaryRow(
                    label = if (deliveryFee == null) "Subtotal so far" else "Total",
                    display = formatUgx(total),
                    bold = true,
                    accent = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onCheckout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        // Mockup uses a green-tinted glow (shadow colored to match
                        // the button, not plain black) via Modifier.shadow's
                        // ambientColor/spotColor params — only available on
                        // Compose UI 1.6+. Uncomment if your BOM supports it;
                        // ButtonDefaults.buttonElevation below is the safe
                        // fallback that works on any version.
                        // .shadow(
                        //     elevation = 12.dp,
                        //     shape = RoundedCornerShape(16.dp),
                        //     ambientColor = Forest.copy(alpha = 0.30f),
                        //     spotColor = Forest.copy(alpha = 0.30f)
                        // )
                        ,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Text(
                        "CHECKOUT",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CartItemRow(item: CartItem, onIncrease: () -> Unit, onDecrease: () -> Unit) {
    // Two rows, not one: cramming the image, a possibly-long product name, the
    // quantity stepper, and the line total into a single Row squeezed the name's
    // weighted Column down to a sliver on some devices, and its text ran into
    // the stepper/total next to it. Splitting into name-row + totals-row gives
    // each its own full-width line, so nothing has to fight for space.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(
                model = item.product.imageUrl,
                contentDescription = item.product.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ForestSurface)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.product.name,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(formatUgx(item.product.price), fontSize = 13.sp, color = InkMuted, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // One continuous pill, not two separate circular buttons — matches
            // the mockup's compact stepper (bg-[#F0F7F1] rounded-full,
            // overflow-hidden) more closely than the previous black-circle pair.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ForestSurface),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDecrease, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Forest, modifier = Modifier.size(16.dp))
                }
                Text(
                    "%02d".format(item.quantity),
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                IconButton(onClick = onIncrease, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Increase", tint = Forest, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                formatUgx(item.lineTotal),
                fontWeight = FontWeight.Bold,
                color = Ink,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Takes an already-formatted string rather than a Double so the delivery row can
 * say "Calculated at checkout" when no location has been chosen yet.
 */
@Composable
private fun SummaryRow(label: String, display: String, bold: Boolean = false, accent: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (bold) 16.sp else 14.sp,
            color = Ink
        )
        Text(
            display,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (bold) 16.sp else 14.sp,
            color = if (accent) Forest else Ink,
            fontFamily = FontFamily.Monospace
        )
    }
}
