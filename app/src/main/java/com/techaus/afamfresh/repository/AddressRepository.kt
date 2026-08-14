package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.Address
import com.techaus.afamfresh.models.AddressesResponse
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.SaveAddressResponse
import com.techaus.afamfresh.utils.enqueueApi
import java.util.UUID

/**
 * Storage for the customer's saved delivery addresses.
 *
 * Callback-shaped so that [ServerAddressRepository] (network-backed, talking
 * to `api/addresses.php`) is the only implementation that needs to exist —
 * AddressViewModel and every screen talk to this interface only.
 */
interface AddressRepository {
    fun getAddresses(callback: (List<Address>) -> Unit)

    /** Inserts when [address] has an id not already stored, otherwise updates. */
    fun saveAddress(address: Address, callback: (Boolean) -> Unit)

    fun deleteAddress(id: String, callback: (Boolean) -> Unit)

    /** Marks one address default and clears the flag on all others. */
    fun setDefaultAddress(id: String, callback: (Boolean) -> Unit)

    /** Generates an id for a newly created address. */
    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Network-backed implementation, talking to `api/addresses.php`
 * (list/create/update/delete/set_default, session-scoped to the signed-in
 * customer — see that file for the full contract).
 *
 * [newId] returns an empty string rather than a UUID: the ViewModel always
 * calls it before [saveAddress] when creating (see
 * `AddressViewModel.save()`), so a blank id is how [saveAddress] tells a
 * create from an update. The server assigns the real id; [saveAddress]'s
 * caller never needs it directly, since [AddressViewModel] always calls
 * [getAddresses] again right after a save completes.
 */
class ServerAddressRepository(
    private val apiService: ApiService
) : AddressRepository {

    private companion object {
        const val TAG = "AddressRepo"
    }

    override fun newId(): String = ""

    override fun getAddresses(callback: (List<Address>) -> Unit) {
        apiService.getAddresses().enqueueApi<AddressesResponse>(TAG, "getAddresses") { body, error ->
            if (error != null || body?.success != true) {
                Log.e(TAG, "getAddresses failed: ${error?.userMessage ?: body?.error}")
                callback(emptyList())
            } else {
                callback(body.addresses ?: emptyList())
            }
        }
    }

    override fun saveAddress(address: Address, callback: (Boolean) -> Unit) {
        val action = if (address.id.isBlank()) "create" else "update"
        apiService.saveAddress(action, address).enqueueApi<SaveAddressResponse>(TAG, "saveAddress") { body, error ->
            if (error != null || body?.success != true) {
                Log.e(TAG, "saveAddress ($action) failed: ${error?.userMessage ?: body?.error}")
            }
            callback(error == null && body?.success == true)
        }
    }

    override fun deleteAddress(id: String, callback: (Boolean) -> Unit) {
        apiService.deleteAddress(id = id).enqueueApi<BaseResponse>(TAG, "deleteAddress") { body, error ->
            if (error != null || body?.success != true) {
                Log.e(TAG, "deleteAddress failed: ${error?.userMessage}")
            }
            callback(error == null && body?.success == true)
        }
    }

    override fun setDefaultAddress(id: String, callback: (Boolean) -> Unit) {
        apiService.setDefaultAddress(id = id).enqueueApi<BaseResponse>(TAG, "setDefaultAddress") { body, error ->
            if (error != null || body?.success != true) {
                Log.e(TAG, "setDefaultAddress failed: ${error?.userMessage}")
            }
            callback(error == null && body?.success == true)
        }
    }
}
