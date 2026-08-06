package com.techaus.afamfresh.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.repository.AddressRepository
import com.techaus.afamfresh.repository.AppRepository
import com.techaus.afamfresh.repository.AuthRepository
import com.techaus.afamfresh.repository.DeliveryRepository
import com.techaus.afamfresh.repository.LocalAddressRepository
import com.techaus.afamfresh.repository.NotificationRepository
import com.techaus.afamfresh.repository.OrderRepository
import com.techaus.afamfresh.repository.PaymentRepository
import com.techaus.afamfresh.repository.ProductRepository
import com.techaus.afamfresh.repository.RiderRepository
import com.techaus.afamfresh.repository.SurplusRepository
import com.techaus.afamfresh.repository.VendorRepository

/**
 * Builds every ViewModel in the app, so they can be obtained through
 * `by viewModels { ... }` and therefore survive configuration changes.
 *
 * Previously MainActivity constructed each ViewModel by hand in onCreate() and
 * held them as Activity fields. Android destroys and recreates the Activity on
 * every rotation, so those fields were rebuilt from scratch each time — and
 * because ProductViewModel, SurplusViewModel and VendorViewModel each call a
 * load function from their init block, every rotation re-fired those network
 * requests and threw away whatever had already loaded.
 *
 * This deliberately keeps the existing manual wiring rather than introducing
 * Hilt: repositories are constructed here from [ApiClient.apiService], exactly
 * as they were in onCreate.
 *
 * IMPORTANT: [ApiClient.initialize] must have been called before this factory
 * is first used, because `ApiClient.apiService` is a lateinit property.
 * MainActivity calls it at the top of onCreate, and the factory is only touched
 * later during composition, so that ordering holds.
 */
class AppViewModelFactory(context: Context) : ViewModelProvider.Factory {

    // applicationContext, never the Activity — this object outlives rotation.
    private val appContext: Context = context.applicationContext

    private val apiService = ApiClient.apiService

    /**
     * Exposed because MainActivity passes it straight to SplashScreen and uses
     * it for isLoggedIn()/getUser() outside of any ViewModel.
     */
    val authRepository = AuthRepository(apiService, appContext)

    /** Exposed for the same reason — SplashScreen takes it directly. */
    val appRepository = AppRepository(apiService)

    private val productRepository = ProductRepository(apiService)
    private val orderRepository = OrderRepository(apiService)
    private val surplusRepository = SurplusRepository(apiService)
    private val paymentRepository = PaymentRepository(apiService)
    private val vendorRepository = VendorRepository(apiService)

    /**
     * Declared as the interface, not the concrete class, so replacing this one
     * line with a network-backed implementation is the whole migration.
     */
    private val addressRepository: AddressRepository = LocalAddressRepository(appContext)

    private val notificationRepository = NotificationRepository(apiService)

    // Takes a Context as well: proof-of-delivery photos are read and compressed
    // through the ContentResolver before upload, like avatars.
    private val riderRepository = RiderRepository(apiService, appContext)

    /** Exposed because DeliveryMapScreen takes it directly, not via a ViewModel. */
    val deliveryRepository = DeliveryRepository(apiService)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AuthViewModel::class.java) ->
                AuthViewModel(authRepository)

            modelClass.isAssignableFrom(ProductViewModel::class.java) ->
                ProductViewModel(productRepository)

            modelClass.isAssignableFrom(OrderViewModel::class.java) ->
                OrderViewModel(orderRepository)

            modelClass.isAssignableFrom(SurplusViewModel::class.java) ->
                SurplusViewModel(surplusRepository)

            modelClass.isAssignableFrom(CartViewModel::class.java) ->
                CartViewModel()

            // Shares one PaymentRepository with PaymentViewModel, as before.
            modelClass.isAssignableFrom(CheckoutViewModel::class.java) ->
                CheckoutViewModel(orderRepository, paymentRepository)

            modelClass.isAssignableFrom(PaymentViewModel::class.java) ->
                PaymentViewModel(paymentRepository)

            modelClass.isAssignableFrom(DeliveryResultViewModel::class.java) ->
                DeliveryResultViewModel()

            modelClass.isAssignableFrom(VendorViewModel::class.java) ->
                VendorViewModel(vendorRepository)

            modelClass.isAssignableFrom(AddressViewModel::class.java) ->
                AddressViewModel(addressRepository)

            modelClass.isAssignableFrom(NotificationViewModel::class.java) ->
                NotificationViewModel(notificationRepository)

            modelClass.isAssignableFrom(RiderViewModel::class.java) ->
                RiderViewModel(riderRepository)

            // Talks to api/roles.php directly — there is no repository, because
            // there is nothing to cache or transform.
            modelClass.isAssignableFrom(RoleGateViewModel::class.java) ->
                RoleGateViewModel(apiService)

            else -> throw IllegalArgumentException(
                "AppViewModelFactory cannot build ${modelClass.name}. " +
                    "Add a branch for it here."
            )
        } as T
    }
}
