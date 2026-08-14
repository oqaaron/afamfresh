package com.techaus.afamfresh.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
 * There is no addresses endpoint in ApiService.kt, so [LocalAddressRepository]
 * keeps them on the device. The interface is callback-shaped — even though the
 * local implementation answers synchronously — precisely so that swapping in a
 * network-backed implementation later requires no change to AddressViewModel
 * or any screen.
 *
 * See the KDoc on [LocalAddressRepository] for the endpoint contract this
 * would map onto.
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
 * SharedPreferences-backed implementation. Addresses are stored as a single
 * JSON array under one key.
 *
 * ---------------------------------------------------------------------------
 * WHAT THE REAL ENDPOINT WOULD NEED TO LOOK LIKE
 *
 * A drop-in replacement would be `api/addresses.php`, matching the same
 * action-query style as the rest of ApiService:
 *
 *   GET  addresses.php?action=list
 *        -> { "success": true, "addresses": [ Address, ... ] }
 *
 *   POST addresses.php?action=create   (form-encoded or JSON body)
 *        fields: label, recipient_name, phone, area, address_line,
 *                is_default, lat?, lng?
 *        -> { "success": true, "address": Address }   // server assigns "id"
 *
 *   POST addresses.php?action=update
 *        fields: id + the same fields as create
 *        -> { "success": true }
 *
 *   POST addresses.php?action=delete
 *        fields: id
 *        -> { "success": true }
 *
 *   POST addresses.php?action=set_default
 *        fields: id
 *        -> { "success": true }
 *
 * All five are authenticated (session cookie, same as the other endpoints) and
 * scoped to the logged-in user. Two server-side rules matter:
 *
 *   1. `set_default` must clear is_default on the user's other addresses in the
 *      same transaction, or the client can end up with two defaults.
 *   2. Deleting the default should promote another address to default, so the
 *      customer is never left with addresses but no default.
 *
 * The [Address] model already carries the matching @SerializedName values, so
 * only this class would need replacing.
 * ---------------------------------------------------------------------------
 */
class LocalAddressRepository(context: Context) : AddressRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences("address_prefs", Context.MODE_PRIVATE)

    private val gson = Gson()

    private companion object {
        const val KEY_ADDRESSES = "saved_addresses"
        const val TAG = "AddressRepo"
    }

    private fun readAll(): List<Address> {
        val json = prefs.getString(KEY_ADDRESSES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Address>>() {}.type
            gson.fromJson<List<Address>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            // Corrupt or schema-changed JSON must not crash the app on launch.
            // Losing saved addresses is bad; an unrecoverable crash is worse.
            Log.e(TAG, "Could not parse saved addresses, discarding: ${e.message}", e)
            emptyList()
        }
    }

    private fun writeAll(addresses: List<Address>) {
        prefs.edit().putString(KEY_ADDRESSES, gson.toJson(addresses)).apply()
    }

    override fun getAddresses(callback: (List<Address>) -> Unit) {
        // Default first, then alphabetical by label, so the picker is stable.
        callback(readAll().sortedWith(compareByDescending<Address> { it.isDefault }.thenBy { it.label }))
    }

    override fun saveAddress(address: Address, callback: (Boolean) -> Unit) {
        val current = readAll().toMutableList()
        val index = current.indexOfFirst { it.id == address.id }

        if (index >= 0) current[index] = address else current.add(address)

        // The first address saved becomes the default automatically — otherwise
        // a customer with exactly one address would have no default at all.
        val normalised = when {
            address.isDefault -> current.map { it.copy(isDefault = it.id == address.id) }
            current.none { it.isDefault } -> current.mapIndexed { i, a -> a.copy(isDefault = i == 0) }
            else -> current
        }

        writeAll(normalised)
        callback(true)
    }

    override fun deleteAddress(id: String, callback: (Boolean) -> Unit) {
        val remaining = readAll().filterNot { it.id == id }

        // Deleting the default promotes another address, so the customer is
        // never left with saved addresses but nothing marked default.
        val normalised = if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
            remaining.mapIndexed { i, a -> a.copy(isDefault = i == 0) }
        } else {
            remaining
        }

        writeAll(normalised)
        callback(true)
    }

    override fun setDefaultAddress(id: String, callback: (Boolean) -> Unit) {
        val updated = readAll().map { it.copy(isDefault = it.id == id) }
        writeAll(updated)
        callback(true)
    }
}

/**
 * Network-backed implementation, talking to `api/addresses.php` — the
 * endpoint contract [LocalAddressRepository]'s KDoc above specifies.
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
