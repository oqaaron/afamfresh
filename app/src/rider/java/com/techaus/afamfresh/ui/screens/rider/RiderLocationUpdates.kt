package com.techaus.afamfresh.ui.screens.rider

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.techaus.afamfresh.viewmodel.RiderViewModel

/**
 * Streams the rider's position to the server while they are on duty.
 *
 * Deliberately FOREGROUND ONLY. `ACCESS_BACKGROUND_LOCATION` is declared in the
 * manifest, but requesting it needs a separate two-step permission flow on
 * Android 10+ and a Play Store justification; tracking while the app is open
 * covers the delivery itself, which is what the customer-facing map needs.
 *
 * The updates stop automatically when the rider goes off duty or the composable
 * leaves the tree — a location stream that outlives the screen is the classic
 * way to flatten a delivery phone's battery.
 */
@Composable
fun RiderLocationUpdates(
    enabled: Boolean,
    riderViewModel: RiderViewModel
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Ask only when the rider actually goes on duty, so a customer using the
    // app is never prompted for location by a screen they cannot reach.
    LaunchedEffect(enabled) {
        if (enabled && !granted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(enabled, granted) {
        if (!enabled || !granted) {
            return@DisposableEffect onDispose { }
        }

        val client = LocationServices.getFusedLocationProviderClient(context)

        // 30s is a delivery-scale cadence: frequent enough for a map to look
        // live, infrequent enough not to drain the phone over a long shift.
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(25f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { riderViewModel.postLocation(it.latitude, it.longitude) }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }

        onDispose { client.removeLocationUpdates(callback) }
    }
}
