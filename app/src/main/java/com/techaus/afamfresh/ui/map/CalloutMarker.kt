package com.techaus.afamfresh.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.techaus.afamfresh.ui.theme.CardWhite
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted
import com.techaus.afamfresh.ui.theme.Tomato

/**
 * A labelled pill marker, the way ride-hailing apps mark the ends of a trip.
 *
 * A bare Google pin says "something is here". A pill saying "Nakawa Market" with
 * a shop icon says what and why, and that is the difference between a map you
 * read and a map you decode.
 *
 * ⚠️ MarkerComposable at maps-compose 4.3.0 rasterises its content to a bitmap
 * ONCE and is documented as not reliably re-rendering when that content
 * changes. So the content passed here must be stable for the marker's lifetime.
 * Anything that moves — the rider — animates its POSITION via MarkerState
 * instead, which does update correctly.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun CalloutMarker(
    state: MarkerState,
    label: String,
    sub: String? = null,
    accent: Color = Forest,
    icon: ImageVector = Icons.Default.Store,
    zIndex: Float = 1f
) {
    MarkerComposable(
        // The key is what tells maps-compose this is a different marker. Without
        // a label in it, two markers with identical content can be collapsed.
        keys = arrayOf(label, sub ?: "", accent.value.toString()),
        state = state,
        // Bottom-centre: the tail should touch the coordinate, not the middle of
        // the pill.
        anchor = androidx.compose.ui.geometry.Offset(0.5f, 1f),
        zIndex = zIndex
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardWhite)
                    .border(2.dp, accent, RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .widthIn(max = 190.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White,
                         modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(7.dp))
                Column {
                    Text(
                        label,
                        color = Ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!sub.isNullOrBlank()) {
                        Text(
                            sub,
                            color = InkMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // The tail. A 6dp square rotated 45° and clipped by the pill above
            // reads as a pointer without needing a custom shape.
            Box(
                modifier = Modifier
                    .padding(top = 0.dp)
                    .size(10.dp, 6.dp)
                    .background(accent, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
            )
        }
    }
}

/** Where the goods are collected. */
@Composable
fun PickupMarker(state: MarkerState, label: String, sub: String? = null) =
    CalloutMarker(state, label, sub, Forest, Icons.Default.Store, zIndex = 1f)

/** Where they are going. */
@Composable
fun DropoffMarker(state: MarkerState, label: String, sub: String? = null) =
    CalloutMarker(state, label, sub, Tomato, Icons.Default.Home, zIndex = 2f)