// api/ApiService.kt
package com.techaus.afamfresh.api

import com.techaus.afamfresh.models.*
import retrofit2.Call
import retrofit2.http.*
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Query

interface ApiService {

    // ============================================================
    // AUTH ENDPOINTS
    // ============================================================
    
    @POST("auth.php")
    fun login(
        @Query("action") action: String = "login",
        @Body body: LoginRequest
    ): Call<LoginResponse>

    @POST("auth.php")
    fun register(
        @Query("action") action: String = "register",
        @Body body: RegisterRequest
    ): Call<RegisterResponse>

    @GET("auth.php")
    fun getCurrentUser(
        @Query("action") action: String = "me"
    ): Call<UserResponse>

    @POST("auth.php")
    fun logout(
        @Query("action") action: String = "logout"
    ): Call<BaseResponse>

    @POST("auth.php")
    @FormUrlEncoded
    fun switchRole(
        @Query("action") action: String = "switch_role",
        @Field("role") role: String
    ): Call<RoleSwitchResponse>

    // ============================================================
    // GOOGLE LOGIN
    // ============================================================

    @POST("auth.php")
    @FormUrlEncoded
    fun googleLogin(
        @Query("action") action: String = "google_login",
        @Field("id_token") idToken: String
    ): Call<LoginResponse>

    // ============================================================
    // PASSWORD RESET
    // ============================================================
    //
    // ⚠️ NOT YET IMPLEMENTED SERVER-SIDE. Both actions need writing in
    // auth.php. Contract:
    //
    //   POST auth.php?action=forgot_password
    //     field:  email
    //     returns { "success": true }
    //
    //     MUST return success even when the email is unknown. Returning an
    //     error for unregistered addresses turns this endpoint into a way to
    //     test which emails have accounts.
    //
    //     On a match, email a link of the form:
    //         afamfresh://reset-password?token=<token>
    //     where <token> is single-use, random (32+ bytes), stored hashed, and
    //     expires in ~30 minutes.
    //
    //   POST auth.php?action=reset_password
    //     fields: token, password
    //     returns { "success": true }
    //          or { "success": false, "error": "Link expired or already used" }
    //
    //     MUST invalidate the token on use and end existing sessions for that
    //     user, so a stolen session cannot outlive the password change.

    @POST("auth.php")
    @FormUrlEncoded
    fun requestPasswordReset(
        @Query("action") action: String = "forgot_password",
        @Field("email") email: String
    ): Call<BaseResponse>

    @POST("auth.php")
    @FormUrlEncoded
    fun resetPassword(
        @Query("action") action: String = "reset_password",
        @Field("token") token: String,
        @Field("password") newPassword: String
    ): Call<BaseResponse>

    // ============================================================
    // PRODUCTS ENDPOINTS
    // ============================================================
    
    @GET("products.php")
    fun getProducts(
        @Query("action") action: String = "list"
    ): Call<ProductsResponse>

    @GET("products.php")
    fun getProduct(
        @Query("action") action: String = "detail",
        @Query("id") id: String
    ): Call<Product>

    // ============================================================
    // ORDERS ENDPOINTS
    // ============================================================
    
    @GET("orders.php")
    fun getOrders(
        @Query("action") action: String = "list"
    ): Call<OrdersResponse>

    @GET("orders.php")
    fun getOrder(
        @Query("action") action: String = "detail",
        @Query("id") id: String
    ): Call<Order>

    @POST("orders.php")
    @FormUrlEncoded
    fun createOrder(
        @Query("action") action: String = "create",
        @Field("fname") fname: String,
        @Field("lname") lname: String,
        @Field("mobile") mobile: String,
        @Field("area") area: String,
        @Field("address") address: String,
        @Field("items") items: String,
        @Field("total") total: Double,
        @Field("payment_method") paymentMethod: String = "mobile_money",
        @Field("email") email: String = "",
        @Field("pickup_address") pickupAddress: String = "",
        @Field("dropoff_address") dropoffAddress: String = "",
        @Field("pickup_lat") pickupLat: Double = 0.0,
        @Field("pickup_lng") pickupLng: Double = 0.0,
        @Field("dropoff_lat") dropoffLat: Double = 0.0,
        @Field("dropoff_lng") dropoffLng: Double = 0.0,
        @Field("distance_km") distanceKm: Double = 0.0,
        @Field("delivery_cost") deliveryCost: Double = 0.0
    ): Call<OrderCreateResponse>

    // ✅ Update Order
    @POST("orders.php")
    @FormUrlEncoded
    fun updateOrder(
        @Query("action") action: String = "update",
        @Field("order_id") orderId: String,
        @Field("address") address: String,
        @Field("area") area: String,
        @Field("mobile") mobile: String,
        @Field("scheduled_delivery_date") scheduledDeliveryDate: String? = null,
        @Field("scheduled_delivery_slot") scheduledDeliverySlot: String? = null,
        @Field("delivery_notes") deliveryNotes: String? = null
    ): Call<BaseResponse>

    // ✅ Cancel Order
    @POST("orders.php")
    @FormUrlEncoded
    fun cancelOrder(
        @Query("action") action: String = "cancel",
        @Field("order_id") orderId: String
    ): Call<BaseResponse>

    // ============================================================
    // SURPLUS ENDPOINTS (Public)
    // ============================================================
    
    @GET("surplus-listings.php")
    fun getSurplusListings(
        @Query("status") status: String = "approved"
    ): Call<SurplusListingsResponse>

    // ============================================================
    // VENDOR SURPLUS ENDPOINTS (Authenticated)
    // ============================================================
    
    @GET("vendor/surplus/listings.php")
    fun getVendorSurplusListings(
        @Query("action") action: String = "list"
    ): Call<SurplusListingsResponse>

    @POST("vendor/surplus/listings.php")
    fun createVendorSurplusListing(
        @Query("action") action: String = "create",
        @Body listing: SurplusListing
    ): Call<SurplusListingResponse>

    @PUT("vendor/surplus/listings.php")
    @FormUrlEncoded
    fun updateVendorSurplusListing(
        @Query("action") action: String = "update",
        @Field("id") id: String,
        @Field("title") title: String,
        @Field("description") description: String? = null,
        @Field("original_price") originalPrice: Double,
        @Field("price") price: Double,
        @Field("quantity") quantity: Double,
        @Field("unit") unit: String,
        @Field("expires_at") expiresAt: String
    ): Call<BaseResponse>

    @DELETE("vendor/surplus/listings.php")
    @FormUrlEncoded
    fun deleteVendorSurplusListing(
        @Query("action") action: String = "delete",
        @Field("id") id: String
    ): Call<BaseResponse>

    // ============================================================
    // VENDOR ORDERS ENDPOINTS
    // ============================================================
    
    @GET("vendor/orders.php")
    fun getVendorOrders(
        @Query("action") action: String = "list"
    ): Call<VendorOrdersResponse>

    // ============================================================
    // VENDOR PRODUCTS ENDPOINTS
    // ============================================================
    
    @GET("vendor/products.php")
    fun getVendorProducts(
        @Query("action") action: String = "list"
    ): Call<VendorProductsResponse>

    // ============================================================
    // PAYMENT ENDPOINTS
    // ============================================================
    
    @POST("payment.php")
    @Headers("Content-Type: application/json")
    fun initiatePayment(
        @Body request: PaymentRequest
    ): Call<PaymentResponse>

    @POST("payment.php")
    @FormUrlEncoded
    fun verifyPayment(
        @Query("action") action: String = "verify",
        @Field("transaction_id") transactionId: String,
        @Field("reference") reference: String? = null
    ): Call<PaymentResponse>

    @POST("payment.php")
    @FormUrlEncoded
    fun paymentCallback(
        @Query("action") action: String = "callback",
        @Field("transaction_id") transactionId: String,
        @Field("status") status: String,
        @Field("reference") reference: String? = null
    ): Call<BaseResponse>

    // ============================================================
    // DELIVERY FEE QUOTE
    // ============================================================
    //
    // ✅ VERIFIED against api/calculate-delivery-fee.php — request fields,
    // response fields and the endpoint path are read from the real file, not
    // inferred.
    //
    // The fee is tiered by order value, so `order_value` is mandatory and the
    // endpoint rejects anything <= 0.

    @POST("calculate-delivery-fee.php")
    @Headers("Content-Type: application/json")
    fun calculateDeliveryFee(
        @Body request: DeliveryQuoteRequest
    ): Call<DeliveryQuoteResponse>

    // ============================================================
    // DELIVERY ENDPOINTS
    // ============================================================
    
    @GET("delivery.php")
    fun getDeliveryAreas(
        @Query("action") action: String = "list"
    ): Call<DeliveryAreasResponse>

    @POST("delivery.php")
    @FormUrlEncoded
    fun saveDeliveryLocation(
        @Query("action") action: String = "save",
        @Field("pickup_address") pickupAddress: String,
        @Field("dropoff_address") dropoffAddress: String,
        @Field("pickup_lat") pickupLat: Double,
        @Field("pickup_lng") pickupLng: Double,
        @Field("dropoff_lat") dropoffLat: Double,
        @Field("dropoff_lng") dropoffLng: Double,
        @Field("distance_km") distanceKm: Double,
        @Field("delivery_cost") deliveryCost: Double
    ): Call<BaseResponse>

    @GET("delivery.php")
    fun getSavedDeliveryLocation(
        @Query("action") action: String = "get_saved"
    ): Call<DeliveryLocationResponse>

    // ============================================================
    // NOTIFICATIONS ENDPOINTS
    // ============================================================
    
    @POST("notifications.php")
    @FormUrlEncoded
    fun registerFCMToken(
        @Query("action") action: String = "register_token",
        @Field("fcm_token") fcmToken: String,
        @Field("device_id") deviceId: String? = null
    ): Call<BaseResponse>

    @GET("notifications.php")
    fun getNotifications(
        @Query("action") action: String = "list"
    ): Call<NotificationsResponse>

    @POST("notifications.php")
    @FormUrlEncoded
    fun markNotificationRead(
        @Query("action") action: String = "mark_read",
        @Field("notification_id") notificationId: String
    ): Call<BaseResponse>

    // ============================================================
    // APP CONFIG ENDPOINT
    // ============================================================

    @GET("config.php")
    fun getAppConfig(): Call<AppConfigResponse>
}