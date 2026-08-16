package com.techaus.afamfresh.viewmodel

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _pickedArea = MutableStateFlow<String?>(null)
    val pickedArea: StateFlow<String?> = _pickedArea.asStateFlow()

    private val _pickedAddress = MutableStateFlow<String?>(null)
    val pickedAddress: StateFlow<String?> = _pickedAddress.asStateFlow()

    // The coordinates the customer actually pinned. These are the whole point
    // of the map picker — a delivery quote and a rider's navigation need the
    // point, not a street name — and they were being dropped entirely:
    // AddressViewModel.save() built its Address without lat/lng, so every
    // pinned address reached the server with both null.
    private val _pickedLat = MutableStateFlow<Double?>(null)
    val pickedLat: StateFlow<Double?> = _pickedLat.asStateFlow()

    private val _pickedLng = MutableStateFlow<Double?>(null)
    val pickedLng: StateFlow<Double?> = _pickedLng.asStateFlow()

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
     *
     * Note: Geocoding callback may run on a background thread on TIRAMISU+,
     * so we switch to Main dispatcher before calling callbacks to ensure
     * Compose state updates are visible.
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
                        // No "Kampala" fallback. Defaulting the area to the capital labelled
                        // every pin whose adminArea came back null as Kampala — so a
                        // delivery pinned in Gulu or Mbarara was saved with the wrong
                        // area and nothing told the customer. Blank is honest, and the
                        // form requires the field, so they name it themselves.
                        val area = address.adminArea
                            ?: address.subAdminArea
                            ?: address.locality
                            ?: ""
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
                        // Switch to main thread before calling Compose-affecting callbacks
                        viewModelScope.launch(Dispatchers.Main) {
                            onSuccess(area, fullAddress.ifEmpty { "Location ($latitude, $longitude)" })
                        }
                    } else {
                        viewModelScope.launch(Dispatchers.Main) {
                            onError("Address not found for this location")
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    // Same as the TIRAMISU branch above: no "Kampala" fallback.
                    val area = address.adminArea
                        ?: address.subAdminArea
                        ?: address.locality
                        ?: ""
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

    fun setPickedLocation(area: String, address: String, lat: Double? = null, lng: Double? = null) {
        _pickedArea.value = area
        _pickedAddress.value = address
        _pickedLat.value = lat
        _pickedLng.value = lng
    }

    /**
     * The pin succeeded but naming it did not — no geocoder result, no network,
     * or simply nowhere with a registered address, which is common outside
     * Kampala.
     *
     * The coordinates are still the valuable part, so they are kept and the
     * address line is filled with them. Area is deliberately left blank: it is
     * a required field, so the customer is asked to name the place themselves
     * rather than having a wrong guess saved on their behalf.
     */
    fun setPickedCoordinatesOnly(lat: Double, lng: Double) {
        _pickedArea.value = null
        _pickedAddress.value = "Pinned location (%.5f, %.5f)".format(lat, lng)
        _pickedLat.value = lat
        _pickedLng.value = lng
    }

    fun clearPickedLocation() {
        _pickedArea.value = null
        _pickedAddress.value = null
        _pickedLat.value = null
        _pickedLng.value = null
    }

    fun clearError() {
        _locationError.value = null
    }
}
