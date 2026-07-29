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
        onDone: (Boolean) -> Unit = {}
    ) {
        val address = Address(
            id = existingId ?: addressRepository.newId(),
            label = label.trim(),
            recipientName = recipientName.trim(),
            phone = phone.trim(),
            area = area.trim(),
            addressLine = addressLine.trim(),
            isDefault = isDefault
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
