package com.techaus.afamfresh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.InkMuted

/**
 * A row of five tappable stars. No reusable rating widget existed anywhere in
 * the app before the customer confirm-and-rate screen needed one.
 */
@Composable
fun StarRating(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: androidx.compose.ui.unit.Dp = 28.dp
) {
    Row(modifier = modifier) {
        for (star in 1..5) {
            Icon(
                imageVector = if (star <= value) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Rate $star star${if (star == 1) "" else "s"}",
                tint = if (star <= value) Forest else InkMuted,
                modifier = Modifier
                    .size(starSize)
                    .clickable { onValueChange(star) }
            )
        }
    }
}
