package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Ink

/**
 * Pieces shared by the two listing forms — [AddBulkScreen] for surplus and
 * [AddWholesaleListingScreen] for wholesale.
 *
 * Extracted rather than duplicated: the unit list in particular is not
 * cosmetic. `weight_per_unit_kg` is what the delivery fee is calculated from,
 * so a second copy that drifted would price a sack as if it weighed what the
 * other form said it did. There is one list, and both forms read it.
 */

/** One unit of sale, and what it means for weight-based delivery pricing. */
internal data class BulkUnit(
    val label: String,
    val plural: String,
    /** True only for kilograms, where the unit IS the weight. */
    val isWeight: Boolean,
    val defaultKg: Double
)

// Ordinary Ugandan market units. Kilogram first because it is both the most
// common and the only one that needs no weight estimate.
internal val Bulk_UNITS = listOf(
    BulkUnit("Kilogram", "kilograms", true, 1.0),
    BulkUnit("Piece", "pieces", false, 0.2),
    BulkUnit("Bunch", "bunches", false, 1.0),
    BulkUnit("Tray", "trays", false, 2.0),
    BulkUnit("Basket", "baskets", false, 10.0),
    BulkUnit("Crate", "crates", false, 12.0),
    BulkUnit("Sack", "sacks", false, 50.0)
)

/** Numbered section heading, so a long form reads as a sequence of steps. */
@Composable
internal fun StepHeader(number: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Forest),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
    }
}
