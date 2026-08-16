package com.techaus.afamfresh.viewmodel

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages GPS location detection and reverse geocoding for delivery addresses.
 *
 * Handles:
 * - Detecting current user GPS location
 * - Reverse geocoding coordinates to area names
 * - Caching location for diaspora users to quickly set delivery points
 */
class LocationViewModel(
    private val context: Context
) : ViewModel() {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isGettingLocation = MutableStateFlow(false)
    val isGettingLocation: StateFlow<Boolean> = _isGettingLocation.asStateFlow()

    private val _locationError = MutableStateFlow<String?>(null)
    val locationError: StateFlow<String?> = _locationError.asStateFlow()

    /**
     * Attempts to get the user's current GPS location.
     * Called on app start to detect location automatically.
     */
    fun requestCurrentLocation() {
        _isGettingLocation.value = true
        _locationError.value = null

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                _currentLocation.value = location
                _isGettingLocation.value = false
                if (location == null) {
                    _locationError.value = "Location not available. Please enable GPS."
                }
            }.addOnFailureListener { exception ->
                Log.e("LocationViewModel", "Location request failed: ${exception.message}")
                _locationError.value = exception.message ?: "Failed to get location"
                _isGettingLocation.value = false
            }
        } catch (e: SecurityException) {
            Log.e("LocationViewModel", "Missing location permission: ${e.message}")
            _locationError.value = "Location permission not granted"
            _isGettingLocation.value = false
        }
    }

    /**
     * Reverse geocode coordinates to get area/address name.
     * Used to fill in address field when user pins location on map.
     */
    fun reverseGeocode(
        latitude: Double,
        longitude: Double,
        onSuccess: (area: String, fullAddress: String) -> Unit = { _, _ -> },
        onError: (error: String) -> Unit = {}
    ) {
        try {
            val geocoder = Geocoder(context)
            // Handle both old (deprecated) and new APIs
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val area = address.adminArea ?: address.subAdminArea ?: "Kampala"
                        val fullAddress = buildString {
                            if (!address.thoroughfare.isNullOrEmpty()) append(address.thoroughfare)
                            if (!address.subThoroughfare.isNullOrEmpty()) {
                                if (isNotEmpty()) append(", ")
                                append(address.subThoroughfare)
                            }
                            if (!address.locality.isNullOrEmpty()) {
                                if (isNotEmpty()) append(", ")
                                append(address.locality)
                            }
                            if (!area.isEmpty()) {
                                if (isNotEmpty()) append(", ")
                                append(area)
                            }
                        }
                        onSuccess(area, fullAddress.ifEmpty { "Location ($latitude, $longitude)" })
                    } else {
                        onError("Address not found for this location")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val area = address.adminArea ?: address.subAdminArea ?: "Kampala"
                    val fullAddress = buildString {
                        if (!address.thoroughfare.isNullOrEmpty()) append(address.thoroughfare)
                        if (!address.subThoroughfare.isNullOrEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append(address.subThoroughfare)
                        }
                        if (!address.locality.isNullOrEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append(address.locality)
                        }
                        if (!area.isEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append(area)
                        }
                    }
                    onSuccess(area, fullAddress.ifEmpty { "Location ($latitude, $longitude)" })
                } else {
                    onError("Address not found for this location")
                }
            }
        } catch (e: Exception) {
            Log.e("LocationViewModel", "Reverse geocoding failed: ${e.message}")
            onError(e.message ?: "Geocoding service unavailable")
        }
    }

    fun clearError() {
        _locationError.value = null
    }
}
