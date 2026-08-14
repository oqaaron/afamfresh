package com.techaus.afamfresh.viewmodel

import com.techaus.afamfresh.models.CreateBulkListingRequest
import com.techaus.afamfresh.models.BulkListing
import com.techaus.afamfresh.models.BulkOrder
import com.techaus.afamfresh.models.UpdateVendorProfileRequest
import com.techaus.afamfresh.models.VendorCatalogueProduct
import com.techaus.afamfresh.models.VendorEarning
import com.techaus.afamfresh.models.VendorEarningsSummary
import com.techaus.afamfresh.models.VendorPayout
import com.techaus.afamfresh.models.VendorProduct
import com.techaus.afamfresh.models.VendorProfile
import com.techaus.afamfresh.repository.VendorRepository
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vendor-side state.
 *
 * IMPORTANT — why this needs an explicit identity step:
 *
 * The vendor endpoints take the vendor's id as a parameter, and they disagree
 * about WHICH id:
 *
 *   vendor-products.php     -> user_id
 *   Bulk-listings.php    -> vendor_id (GET), user_id (POST)
 *   Bulk-orders.php      -> vendor_id
 *
 * Nothing in the auth response carries the vendor id, so [start] must be called
 * with the signed-in user's id first; it resolves the vendor record and only
 * then can anything else load.
 *
 * They do also read the PHP session now, and refuse an id that disagrees with
 * it — so a stale or expired session shows up here as a 401 on calls that used
 * to succeed, not as somebody else's data.
 *
 * This also replaces the old `init { loadListings() }`. That fired at
 * construction — before any user was known — so it always requested another
 * vendor's data or none at all.
 */
class VendorViewModel(
    private val vendorRepository: VendorRepository
) : ViewModel() {

    private val _listings = MutableStateFlow<List<BulkListing>>(emptyList())
    val listings: StateFlow<List<BulkListing>> = _listings.asStateFlow()

    private val _vendorOrders = MutableStateFlow<List<BulkOrder>>(emptyList())
    val vendorOrders: StateFlow<List<BulkOrder>> = _vendorOrders.asStateFlow()

    private val _vendorProducts = MutableStateFlow<List<VendorProduct>>(emptyList())
    val vendorProducts: StateFlow<List<VendorProduct>> = _vendorProducts.asStateFlow()

    /** Products this vendor created, any status. See [loadMyProducts]. */
    private val _myProducts = MutableStateFlow<List<VendorCatalogueProduct>>(emptyList())
    val myProducts: StateFlow<List<VendorCatalogueProduct>> = _myProducts.asStateFlow()

    private val _profile = MutableStateFlow<VendorProfile?>(null)
    val profile: StateFlow<VendorProfile?> = _profile.asStateFlow()

    /**
     * Save state for the business-details form, kept separate from [isLoading]
     * so saving does not blank the screen the form is sitting on.
     */
    private val _detailsSaveState = MutableStateFlow<VendorDetailsSaveState>(VendorDetailsSaveState.Idle)
    val detailsSaveState: StateFlow<VendorDetailsSaveState> = _detailsSaveState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _canRetry = MutableStateFlow(true)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    /** The signed-in user's id, needed by the user_id-keyed endpoints. */
    private var userId: Int? = null

    /** Resolved from [userId] via vendor-profile.php. */
    private val vendorId: Int? get() = _profile.value?.id

    /**
     * Identifies the vendor and loads their data. Safe to call repeatedly — it
     * no-ops when already resolved for the same user, so recomposition does not
     * re-fire the requests.
     */
    fun start(userId: Int) {
        if (this.userId == userId && _profile.value != null) return
        this.userId = userId

        _isLoading.value = true
        _error.value = null
        vendorRepository.getVendorProfile(userId) { profile, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (profile != null) {
                _profile.value = profile
                loadListings()
                loadVendorProducts()
                loadMyProducts()
            } else {
                _profile.value = null
                _error.value = error?.userMessage ?: "This account is not registered as a vendor."
                _canRetry.value = error?.isRetryable ?: false
            }
        }
    }

    /**
     * Saves the business details an approved vendor supplies before an admin
     * verifies them.
     *
     * Refreshes the profile on success so `is_verified` and the new details are
     * what the dashboard reads — without it the screen would keep showing the
     * placeholder name approval generated.
     */
    fun saveBusinessDetails(
        businessName: String,
        phone: String,
        businessType: String,
        location: String?,
        marketStall: String?,
        // Null means "unchanged". The endpoint COALESCEs, so a vendor who edits
        // their phone number without reopening the map keeps their existing pin
        // instead of clearing it.
        lat: Double? = null,
        lng: Double? = null
    ) {
        _detailsSaveState.value = VendorDetailsSaveState.Saving
        vendorRepository.updateVendorProfile(
            UpdateVendorProfileRequest(
                businessName = businessName.trim(),
                phone = phone.trim(),
                businessType = businessType,
                location = location?.trim()?.ifEmpty { null },
                marketStall = marketStall?.trim()?.ifEmpty { null },
                lat = lat,
                lng = lng
            )
        ) { isVerified, message, error ->
            if (error != null) {
                _detailsSaveState.value = VendorDetailsSaveState.Error(error.userMessage)
                return@updateVendorProfile
            }
            _detailsSaveState.value = VendorDetailsSaveState.Saved(isVerified, message)

            // Re-read the record rather than patching it locally, so the
            // dashboard cannot disagree with the server about is_verified.
            val id = userId
            if (id != null) {
                vendorRepository.getVendorProfile(id) { profile, _ ->
                    if (profile != null) _profile.value = profile
                }
            }
        }
    }

    /** Lets the form clear a message after it has been shown. */
    fun clearDetailsSaveState() {
        _detailsSaveState.value = VendorDetailsSaveState.Idle
    }

    /**
     * The vendor's own products, in every status.
     *
     * Distinct from [vendorProducts], which is catalogue items the vendor
     * stocks. These are products the vendor created, and only the approved ones
     * can carry a Bulk listing.
     */
    fun loadMyProducts() {
        _isLoading.value = true
        vendorRepository.getMyVendorProducts { products, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (products != null) {
                _myProducts.value = products
            } else {
                _error.value = error?.userMessage ?: "Unable to load your products"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    /**
     * Submits a new product for approval.
     *
     * Reloads afterwards so the pending row appears immediately — otherwise a
     * vendor submits, sees nothing change, and submits again.
     */
    fun createProduct(
        name: String,
        category: String,
        price: String,
        description: String,
        quantityType: String,
        image: okhttp3.MultipartBody.Part?,
        onResult: (Boolean, String?) -> Unit
    ) {
        _isLoading.value = true
        vendorRepository.createVendorProduct(
            name, category, price, description, quantityType, image
        ) { message, error ->
            _isLoading.value = false
            if (error == null) {
                loadMyProducts()
                onResult(true, message)
            } else {
                onResult(false, error.userMessage)
            }
        }
    }

    /**
     * Adds a catalogue item to the vendor's inventory, or updates its price and
     * stock — the endpoint upserts.
     *
     * A vendor stocks catalogue items; they cannot create new ones. `items` is
     * shared and admin-owned, which is what stops five vendors inventing five
     * spellings of the same tomato.
     *
     * Reloads the inventory afterwards so the Bulk form's product picker
     * sees the addition without the screen being reopened.
     */
    fun addProductToInventory(
        productId: Int,
        stockQuantity: Int,
        price: Double?,
        onResult: (Boolean, String?) -> Unit
    ) {
        _isLoading.value = true
        vendorRepository.addVendorProduct(productId, stockQuantity, price) { ok, error ->
            _isLoading.value = false
            if (ok) {
                loadVendorProducts()
                onResult(true, null)
            } else {
                onResult(false, error?.userMessage ?: "Could not add that product.")
            }
        }
    }

    /** Removes a product from the vendor's inventory. */
    fun removeProductFromInventory(productId: Int, onResult: (Boolean, String?) -> Unit) {
        _isLoading.value = true
        vendorRepository.removeVendorProduct(productId) { ok, error ->
            _isLoading.value = false
            if (ok) {
                loadVendorProducts()
                onResult(true, null)
            } else {
                onResult(false, error?.userMessage ?: "Could not remove that product.")
            }
        }
    }

    fun loadListings(status: String = "approved") {
        val id = vendorId ?: return reportNotAVendor()
        _isLoading.value = true
        _error.value = null
        vendorRepository.getListings(vendorId = id, status = status) { listings, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (listings != null) {
                _listings.value = listings
            } else {
                _error.value = error?.userMessage ?: "Unable to load your listings"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    /**
     * Submits a Bulk listing.
     *
     * A listing must reference an existing catalogue product — the server joins
     * `Bulk_listings.product_id` onto `items` — so callers pass a productId
     * rather than a free-text title.
     */
    fun createListing(
        productId: Int,
        originalPrice: Double,
        discountPercent: Double,
        BulkQuantity: Int,
        expiryDate: String,
        listingType: String = "goodie_bag",
        description: String = "",
        conditionRating: String = "good",
        pickupOnly: Boolean = false,
        /**
         * How much one unit weighs. The delivery fee is calculated by weight,
         * so a listing sold by the tray or sack has to say what a tray weighs
         * or the customer is quoted for 1 kg regardless.
         */
        weightPerUnitKg: Double = 1.0,
        /** True when the unit IS a weight (kilograms), false for pieces, trays, sacks. */
        isWeightBased: Boolean = true,
        onResult: (Boolean, String?) -> Unit
    ) {
        val uid = userId
        if (uid == null) {
            onResult(false, "You need to be signed in to create a listing.")
            return
        }

        _isLoading.value = true
        vendorRepository.createListing(
            CreateBulkListingRequest(
                userId = uid,
                productId = productId,
                originalPrice = originalPrice,
                discountPercent = discountPercent,
                BulkQuantity = BulkQuantity,
                expiryDate = expiryDate,
                listingType = listingType,
                description = description,
                conditionRating = conditionRating,
                pickupOnly = pickupOnly,
                weightPerUnitKg = weightPerUnitKg,
                isWeightBased = isWeightBased
            )
        ) { _, error ->
            _isLoading.value = false
            if (error == null) {
                // New listings are created as 'pending', so reload that bucket
                // rather than 'approved' — otherwise the vendor submits a
                // listing and sees nothing appear.
                loadListings(status = "pending")
                onResult(true, null)
            } else {
                _error.value = error.userMessage
                onResult(false, error.userMessage)
            }
        }
    }

    /**
     * The only listing field the backend can change is the remaining quantity
     * (plus status and admin notes, which are not the vendor's to set). Price,
     * expiry and quantity are fixed once submitted.
     */
    fun updateListingQuantity(
        listingId: Int,
        remainingQuantity: Int,
        onResult: (Boolean, String?) -> Unit
    ) {
        _isLoading.value = true
        vendorRepository.updateListingQuantity(listingId, remainingQuantity) { success, error ->
            _isLoading.value = false
            if (success) {
                loadListings()
                onResult(true, null)
            } else {
                _error.value = error?.userMessage ?: "Unable to save changes"
                onResult(false, _error.value)
            }
        }
    }

    fun deleteListing(listingId: Int, onResult: (Boolean, String?) -> Unit) {
        _isLoading.value = true
        vendorRepository.deleteListing(listingId) { success, error ->
            _isLoading.value = false
            if (success) {
                loadListings()
                onResult(true, null)
            } else {
                _error.value = error?.userMessage ?: "Unable to delete listing"
                onResult(false, _error.value)
            }
        }
    }

    fun loadVendorOrders() {
        val id = vendorId ?: return reportNotAVendor()
        _isLoading.value = true
        _error.value = null
        vendorRepository.getVendorOrders(vendorId = id) { orders, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (orders != null) {
                _vendorOrders.value = orders
            } else {
                _error.value = error?.userMessage ?: "Unable to load orders"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    /**
     * Orders whose status is being changed right now, by order id.
     *
     * Per-order rather than one screen-wide flag: a vendor working through a
     * morning's orders should not have every button on the list go dead because
     * one of them is in flight.
     */
    private val _updatingOrders = MutableStateFlow<Set<Int>>(emptySet())
    val updatingOrders: StateFlow<Set<Int>> = _updatingOrders.asStateFlow()

    /**
     * Moves an order to a new status.
     *
     * The list is reloaded from the server afterwards rather than patched in
     * place. The server does more than store the status — it stamps
     * delivered_at, credits the earnings ledger, and can refuse the change
     * outright on an unpaid order — so the only honest view of what happened is
     * the one the server returns. Guessing locally would show a vendor
     * "Delivered" on an order the server rejected.
     */
    fun updateOrderStatus(orderId: Int, status: String, onDone: (Boolean) -> Unit = {}) {
        if (orderId in _updatingOrders.value) return   // this credits money; not twice

        _updatingOrders.value = _updatingOrders.value + orderId
        _error.value = null

        vendorRepository.updateBulkOrderStatus(orderId, status) { ok, error ->
            _updatingOrders.value = _updatingOrders.value - orderId
            if (ok) {
                loadVendorOrders()
            } else {
                _error.value = error?.userMessage ?: "Could not update that order."
            }
            onDone(ok)
        }
    }

    // ---------------------------------------------------------------
    // Earnings and withdrawals
    // ---------------------------------------------------------------

    private val _earnings = MutableStateFlow<List<VendorEarning>>(emptyList())
    val earnings: StateFlow<List<VendorEarning>> = _earnings.asStateFlow()

    private val _earningsSummary = MutableStateFlow<VendorEarningsSummary?>(null)
    val earningsSummary: StateFlow<VendorEarningsSummary?> = _earningsSummary.asStateFlow()

    private val _payouts = MutableStateFlow<List<VendorPayout>>(emptyList())
    val payouts: StateFlow<List<VendorPayout>> = _payouts.asStateFlow()

    /** Decided server-side, so the app never offers a button the API refuses. */
    private val _hasOpenPayoutRequest = MutableStateFlow(false)
    val hasOpenPayoutRequest: StateFlow<Boolean> = _hasOpenPayoutRequest.asStateFlow()

    private val _earningsLoading = MutableStateFlow(false)
    val earningsLoading: StateFlow<Boolean> = _earningsLoading.asStateFlow()

    private val _earningsError = MutableStateFlow<String?>(null)
    val earningsError: StateFlow<String?> = _earningsError.asStateFlow()

    private val _payoutMessage = MutableStateFlow<String?>(null)
    val payoutMessage: StateFlow<String?> = _payoutMessage.asStateFlow()

    /**
     * Kept out of [isLoading] on purpose: the earnings screen has its own
     * loading state, and sharing the vendor-wide one would blank the products
     * list every time this ran.
     */
    fun loadEarnings() {
        val uid = userId ?: return reportNotAVendor()
        _earningsLoading.value = true
        _earningsError.value = null

        vendorRepository.getEarnings(uid) { response, error ->
            _earningsLoading.value = false
            if (response != null) {
                _earnings.value = response.earnings.orEmpty()
                _earningsSummary.value = response.summary
                _payouts.value = response.payouts.orEmpty()
                _hasOpenPayoutRequest.value = response.hasOpenRequest
            } else {
                _earningsError.value = error?.userMessage ?: "Could not load your earnings."
            }
        }
    }

    private val _requestingPayout = MutableStateFlow(false)
    val requestingPayout: StateFlow<Boolean> = _requestingPayout.asStateFlow()

    /**
     * Asks to withdraw the whole available balance.
     *
     * No amount is passed anywhere: the server sums the unpaid ledger itself.
     * Accepting one here would only suggest the caller has a say.
     *
     * Reloads afterwards rather than patching state, so what the vendor sees is
     * the ledger the server actually holds.
     */
    fun requestPayout() {
        val uid = userId ?: return reportNotAVendor()
        if (_requestingPayout.value) return   // this is money; not twice

        _requestingPayout.value = true
        _earningsError.value = null
        _payoutMessage.value = null

        vendorRepository.requestPayout(uid) { amount, error ->
            _requestingPayout.value = false
            if (amount != null) {
                _payoutMessage.value =
                    "Withdrawal requested. An administrator will review it."
                loadEarnings()
            } else {
                _earningsError.value = error?.userMessage ?: "Could not request a withdrawal."
            }
        }
    }

    fun clearPayoutMessage() {
        _payoutMessage.value = null
    }

    fun loadVendorProducts() {
        val uid = userId ?: return reportNotAVendor()
        _isLoading.value = true
        _error.value = null
        vendorRepository.getVendorProducts(userId = uid) { products, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (products != null) {
                _vendorProducts.value = products
            } else {
                _error.value = error?.userMessage ?: "Unable to load products"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    private fun reportNotAVendor() {
        _hasLoaded.value = true
        _error.value = "Vendor account not loaded yet. Pull to retry."
        _canRetry.value = true
    }
}

/**
 * Outcome of saving the business-details form.
 *
 * [Saved] carries is_verified because saving details is not what grants
 * permission to list products — an admin verifying the record is. The form
 * says so rather than implying the vendor is now live.
 */
sealed class VendorDetailsSaveState {
    object Idle : VendorDetailsSaveState()
    object Saving : VendorDetailsSaveState()
    data class Saved(val isVerified: Boolean, val message: String?) : VendorDetailsSaveState()
    data class Error(val message: String) : VendorDetailsSaveState()
}
