package com.techaus.afamfresh.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.techaus.afamfresh.R
import com.techaus.afamfresh.utils.GkmaBounds

/**
 * One map look, everywhere.
 *
 * Every map in the three apps — the customer's tracking screen and drop-off
 * picker, the vendor's pickup pin, the rider's navigation — shares this so they
 * read as one product rather than four screens that happen to contain maps.
 *
 * THE STYLE
 *
 * Google's default map is built for exploring: every restaurant, bus stop and
 * petrol station competes for attention. None of that matters when the question
 * is "where is my matooke". `map_style_afamfresh.json` strips POIs, transit and
 * icon labels and mutes the roads to pale grey, so the only saturated thing on
 * screen is the route and the markers on it.
 */

/** Greater Kampala, as the Maps SDK wants it. Built from the shared constants. */
val GKMA_CAMERA_BOUNDS: LatLngBounds = LatLngBounds(
    LatLng(GkmaBounds.SOUTH, GkmaBounds.WEST),
    LatLng(GkmaBounds.NORTH, GkmaBounds.EAST)
)

/** Kampala centre, for a map with nothing to show yet. */
val GKMA_CENTER: LatLng = LatLng(GkmaBounds.CENTER_LAT, GkmaBounds.CENTER_LNG)

/**
 * @param clampToServiceArea true for pickers, where a point outside Greater
 *        Kampala is refused anyway and panning there only wastes the user's
 *        time. FALSE for tracking: a rider who strays outside the box is a real
 *        situation, and a camera that refuses to follow them would look broken.
 */
@Composable
fun rememberAfamFreshMapProperties(
    showMyLocation: Boolean = false,
    clampToServiceArea: Boolean = true
): MapProperties {
    val context = LocalContext.current
    return remember(showMyLocation, clampToServiceArea) {
        MapProperties(
            mapStyleOptions = MapStyleOptions.loadRawResourceStyle(
                context, R.raw.map_style_afamfresh
            ),
            isMyLocationEnabled = showMyLocation,
            latLngBoundsForCameraTarget = if (clampToServiceArea) GKMA_CAMERA_BOUNDS else null,
            minZoomPreference = 9f
        )
    }
}

/**
 * Controls, deliberately sparse.
 *
 * `mapToolbarEnabled = false` is the important one: that toolbar is the little
 * pair of buttons Google overlays on a marker, and one of them LAUNCHES GOOGLE
 * MAPS. Leaving it on would put an "escape to another app" button on the rider's
 * navigation screen, which is the exact thing being removed.
 */
@Composable
fun rememberAfamFreshMapUiSettings(
    showMyLocationButton: Boolean = false,
    allowScroll: Boolean = true
): MapUiSettings = remember(showMyLocationButton, allowScroll) {
    MapUiSettings(
        mapToolbarEnabled = false,
        zoomControlsEnabled = false,
        compassEnabled = false,
        myLocationButtonEnabled = showMyLocationButton,
        scrollGesturesEnabled = allowScroll,
        rotationGesturesEnabled = false,
        tiltGesturesEnabled = false
    )
}