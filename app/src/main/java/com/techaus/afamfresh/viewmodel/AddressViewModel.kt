package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.Address
import com.techaus.afamfresh.repository.AddressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saved delivery addresses.
 *
 * Talks only to the [AddressRepository] interface, so moving from local
 * storage to a real endpoint does not touch this class.
 */
class AddressViewModel(
    private val addressRepository: AddressRepository
) : ViewModel() {

    private val _addresses = MutableStateFlow<List<Address>>(emptyList())
    val addresses: StateFlow<List<Address>> = _addresses.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _isLoading.value = true
        addressRepository.getAddresses { list ->
            _addresses.value = list
            _isLoading.value = false
        }
    }

    /** The address to prefill checkout with, if the customer has one. */
    fun defaultAddress(): Address? =
        _addresses.value.firstOrNull { it.isDefault } ?: _addresses.value.firstOrNull()

    /**
     * Creates or updates. Pass [existingId] when editing; leave it null to add.
     */
    fun save(
        existingId: String?,
        label: String,
        recipientName: String,
        phone: String,
        area: String,
        addressLine: String,
        isDefault: Boolean,
        /**
         * From the map picker, or carried over from the address being edited.
         * These were previously not parameters at all, so [Address]'s lat/lng
         * defaulted to null on every save: api/addresses.php has columns for
         * them and Address.kt has the fields, but a pinned point never once
         * reached the database. Null stays meaningful — a typed-in address
         * genuinely has no coordinates, and inventing them would produce a
         * wrong delivery quote.
         */
        lat: Double? = null,
        lng: Double? = null,
        onDone: (Boolean) -> Unit = {}
    ) {
        val address = Address(
            id = existingId ?: addressRepository.newId(),
            label = label.trim(),
            recipientName = recipientName.trim(),
            phone = phone.trim(),
            area = area.trim(),
            addressLine = addressLine.trim(),
            isDefault = isDefault,
            lat = lat,
            lng = lng
        )
        addressRepository.saveAddress(address) { ok ->
            refresh()
            onDone(ok)
        }
    }

    fun delete(id: String, onDone: (Boolean) -> Unit = {}) {
        addressRepository.deleteAddress(id) { ok ->
            refresh()
            onDone(ok)
        }
    }

    fun setDefault(id: String, onDone: (Boolean) -> Unit = {}) {
        addressRepository.setDefaultAddress(id) { ok ->
            refresh()
            onDone(ok)
        }
    }
}
