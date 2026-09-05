package com.techaus.afamfresh.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.repository.AddressRepository
import com.techaus.afamfresh.repository.AppRepository
import com.techaus.afamfresh.repository.AuthRepository
import com.techaus.afamfresh.repository.DeliveryRepository
import com.techaus.afamfresh.repository.FavoritesRepository
import com.techaus.afamfresh.repository.ServerAddressRepository
import com.techaus.afamfresh.repository.NotificationRepository
import com.techaus.afamfresh.repository.OrderRepository
import com.techaus.afamfresh.repository.PaymentRepository
import com.techaus.afamfresh.repository.ProductRepository
import com.techaus.afamfresh.repository.RiderRepository
import com.techaus.afamfresh.repository.BulkRepository
import com.techaus.afamfresh.repository.TrackingRepository
import com.techaus.afamfresh.repository.VendorRepository

class AppViewModelFactory(context: Context) : ViewModelProvider.Factory {

    private val application: Application = context.applicationContext as Application
    private val appContext: Context = context.applicationContext
    private val apiService = ApiClient.apiService

    val authRepository = AuthRepository(apiService, appContext)
    val appRepository = AppRepository(apiService)

    private val productRepository = ProductRepository(apiService)
    private val orderRepository = OrderRepository(apiService)
    private val bulkRepository = BulkRepository(apiService)
    private val trackingRepository = TrackingRepository(apiService)
    private val paymentRepository = PaymentRepository(apiService)
    private val vendorRepository = VendorRepository(apiService)

    private val addressRepository: AddressRepository = ServerAddressRepository(apiService)
    private val notificationRepository = NotificationRepository(apiService)
    private val favoritesRepository = FavoritesRepository(apiService)
    private val riderRepository = RiderRepository(apiService, appContext)

    val deliveryRepository = DeliveryRepository(apiService)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AuthViewModel::class.java) ->
                AuthViewModel(application, authRepository)

            modelClass.isAssignableFrom(ProductViewModel::class.java) ->
                ProductViewModel(productRepository)

            modelClass.isAssignableFrom(OrderViewModel::class.java) ->
                OrderViewModel(orderRepository)

            modelClass.isAssignableFrom(BulkViewModel::class.java) ->
                BulkViewModel(bulkRepository)

            modelClass.isAssignableFrom(TrackingViewModel::class.java) ->
                TrackingViewModel(trackingRepository)

            modelClass.isAssignableFrom(CartViewModel::class.java) ->
                CartViewModel()

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

            modelClass.isAssignableFrom(FavoritesViewModel::class.java) ->
                FavoritesViewModel(application)

            modelClass.isAssignableFrom(RiderViewModel::class.java) ->
                RiderViewModel(riderRepository)

            modelClass.isAssignableFrom(RoleGateViewModel::class.java) ->
                RoleGateViewModel(apiService)

            modelClass.isAssignableFrom(LocationViewModel::class.java) ->
                LocationViewModel(appContext)

            else -> throw IllegalArgumentException(
                "AppViewModelFactory cannot build ${modelClass.name}. " +
                    "Add a branch for it here."
            )
        } as T
    }
}