package com.techaus.afamfresh.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.techaus.afamfresh.ui.theme.CardWhite
import com.techaus.afamfresh.ui.theme.DividerGray

/**
 * Skeleton placeholders for list screens.
 *
 * A bare CircularProgressIndicator tells the user only that *something* is
 * happening. A skeleton shows the shape of what is coming, so the layout does
 * not jump when data lands and the wait feels shorter.
 *
 * The pulse is one shared [rememberInfiniteTransition] per skeleton block
 * rather than per row, so a screenful of placeholders animates in step and
 * costs one animation instead of a dozen.
 */
@Composable
private fun rememberPulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    return alpha
}

/** A single grey block standing in for a line of text or an image. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 8,
    alpha: Float
) {
    Spacer(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(DividerGray)
            .alpha(alpha)
    )
}

/**
 * Placeholder for a vertical list of cards — orders, Bulk deals, vendor
 * listings, notifications.
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 5
) {
    val alpha = rememberPulseAlpha()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardWhite)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBlock(modifier = Modifier.size(56.dp), cornerRadius = 12, alpha = alpha)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBlock(
                        modifier = Modifier.fillMaxWidth(0.6f).height(14.dp),
                        alpha = alpha
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SkeletonBlock(
                        modifier = Modifier.fillMaxWidth(0.35f).height(12.dp),
                        alpha = alpha
                    )
                }
            }
        }
    }
}

/** Placeholder matching the two-column product grid on the home screen. */
@Composable
fun ProductGridSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 3
) {
    val alpha = rememberPulseAlpha()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(2) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(CardWhite)
                            .padding(10.dp)
                    ) {
                        SkeletonBlock(
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            cornerRadius = 16,
                            alpha = alpha
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        SkeletonBlock(
                            modifier = Modifier.fillMaxWidth(0.7f).height(14.dp),
                            alpha = alpha
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SkeletonBlock(
                            modifier = Modifier.fillMaxWidth(0.4f).height(12.dp),
                            alpha = alpha
                        )
                    }
                }
            }
        }
    }
}
