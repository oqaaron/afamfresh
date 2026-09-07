// api/ApiService.kt
package com.techaus.afamfresh.api

import com.techaus.afamfresh.BuildConfig
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

    @POST("auth.php")
    fun registerRider(
        @Query("action") action: String = "register_rider",
        @Body body: RiderRegistrationRequest
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
        @Field("id_token") idToken: String,
        @Field("app_role") appRole: String = BuildConfig.APP_ROLE
    ): Call<LoginResponse>

    // ============================================================
    // PHONE / OTP SIGN-IN
    // ============================================================

    @POST("auth.php")
    @FormUrlEncoded
    fun sendPhoneOtp(
        @Query("action") action: String = "send_phone_otp",
        @Field("mobile") mobile: String
    ): Call<BaseResponse>

    @POST("auth.php")
    @FormUrlEncoded
    fun verifyPhoneOtp(
        @Query("action") action: String = "verify_phone_otp",
        @Field("mobile") mobile: String,
        @Field("code") code: String,
        @Field("app_role") appRole: String = BuildConfig.APP_ROLE
    ): Call<PhoneVerifyResponse>

    @POST("auth.php")
    @FormUrlEncoded
    fun completePhoneSignup(
        @Query("action") action: String = "complete_phone_signup",
        @Field("mobile") mobile: String,
        @Field("proof_token") proofToken: String,
        @Field("fname") fname: String,
        @Field("lname") lname: String,
        @Field("app_role") appRole: String = BuildConfig.APP_ROLE
    ): Call<LoginResponse>

    // ============================================================
    // PASSWORD RESET
    // ============================================================

    @POST("auth.php")
    @FormUrlEncoded
    fun requestPasswordReset(
        @Query("action") action: String = "forgot_password",
        @Field("email") email: String,
        @Field("scheme") scheme: String = BuildConfig.DEEP_LINK_SCHEME
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
        @Query("id") id: Int
    ): Call<ProductDetailResponse>

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
    ): Call<OrderDetailResponse>

    @POST("orders.php")
    @FormUrlEncoded
    fun createOrder(
        @Query("action") action: String = "create",
        @Field("fname") fname: String,
        @Field("lname") lname: String,
        @Field("mobile") mobile: String,
        @Field("area") area: String,
        @Field("address") address: String,
        @Field("landmark_notes") landmarkNotes: String = "",
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
        @Field("delivery_cost") deliveryCost: Double = 0.0,
        @Field("points_redeem") pointsRedeem: Int = 0,
        @Field("scheduled_delivery_date") scheduledDeliveryDate: String? = null,
        @Field("scheduled_delivery_slot") scheduledDeliverySlot: String? = null
    ): Call<OrderCreateResponse>

    @POST("loyalty-quote.php")
    @Headers("Content-Type: application/json")
    fun getLoyaltyQuote(
        @Body request: LoyaltyQuoteRequest
    ): Call<LoyaltyQuoteResponse>

    @POST("orders.php")
    @FormUrlEncoded
    fun updateOrder(
        @Query("action") action: String = "update",
        @Field("order_id") orderId: String,
        @Field("address") address: String,
        @Field("area") area: String,
        @Field("mobile") mobile: String,
        @Field("scheduled_delivery_date") scheduledDeliveryDate: String? = null,
        @Field("scheduled_delivery_slot") scheduledDeliverySlot: String? = null
    ): Call<BaseResponse>

    @POST("orders.php")
    @FormUrlEncoded
    fun cancelOrder(
        @Query("action") action: String = "cancel",
        @Field("order_id") orderId: String
    ): Call<BaseResponse>

    @Multipart
    @POST("orders.php")
    fun confirmOrderReceipt(
        @Query("action") action: String = "confirm_receipt",
        @Part("order_id") orderId: okhttp3.RequestBody,
        @Part("rating") rating: okhttp3.RequestBody? = null,
        @Part("rating_speed") ratingSpeed: okhttp3.RequestBody? = null,
        @Part("rating_professionalism") ratingProfessionalism: okhttp3.RequestBody? = null,
        @Part("rating_packaging") ratingPackaging: okhttp3.RequestBody? = null,
        @Part("feedback") feedback: okhttp3.RequestBody? = null,
        @Part("emoji_reaction") emojiReaction: okhttp3.RequestBody? = null,
        @Part photo: okhttp3.MultipartBody.Part? = null
    ): Call<BaseResponse>

    // ============================================================
    // Bulk ENDPOINTS (Public)
    // ============================================================
    
    @GET("Bulk-listings.php")
    fun getBulkListings(
        @Query("status") status: String = "approved",
        @Query("vendor_id") vendorId: Int? = null,
        @Query("listing_type") listingType: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Call<BulkListingsResponse>

    @POST("Bulk-orders.php")
    @Headers("Content-Type: application/json")
    fun createBulkOrder(
        @Body request: CreateBulkOrderRequest
    ): Call<CreateBulkOrderResponse>

    @POST("Bulk-quote.php")
    @Headers("Content-Type: application/json")
    fun getBulkQuote(
        @Body request: BulkQuoteRequest
    ): Call<BulkQuoteResponse>

    @GET("Bulk-orders.php")
    fun getMyBulkOrders(
        @Query("user_id") userId: Int,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Call<BulkOrdersResponse>

    // ============================================================
    // VENDOR Bulk ENDPOINTS
    // ============================================================

    @GET("Bulk-listings.php")
    fun getVendorBulkListings(
        @Query("vendor_id") vendorId: Int,
        @Query("status") status: String = "approved",
        @Query("limit") limit: Int = 50
    ): Call<BulkListingsResponse>

    @POST("Bulk-listings.php")
    @Headers("Content-Type: application/json")
    fun createVendorBulkListing(
        @Body listing: CreateBulkListingRequest
    ): Call<BulkListingResponse>

    @PUT("Bulk-listings.php")
    @Headers("Content-Type: application/json")
    fun updateVendorBulkListing(
        @Body update: UpdateBulkListingRequest
    ): Call<BaseResponse>

    @DELETE("Bulk-listings.php")
    fun deleteVendorBulkListing(
        @Query("listing_id") listingId: Int
    ): Call<BaseResponse>

    // ============================================================
    // VENDOR ORDERS ENDPOINTS
    // ============================================================

    @GET("Bulk-orders.php")
    fun getVendorOrders(
        @Query("vendor_id") vendorId: Int,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Call<BulkOrdersResponse>

    @PUT("Bulk-orders.php")
    @Headers("Content-Type: application/json")
    fun updateBulkOrderStatus(
        @Body request: UpdateBulkOrderStatusRequest
    ): Call<BaseResponse>

    @Multipart
    @POST("Bulk-orders.php")
    fun confirmBulkReceipt(
        @Query("action") action: String = "confirm_receipt",
        @Part("order_id") orderId: okhttp3.RequestBody,
        @Part("user_id") userId: okhttp3.RequestBody,
        @Part("rating") rating: okhttp3.RequestBody? = null,
        @Part("rating_speed") ratingSpeed: okhttp3.RequestBody? = null,
        @Part("rating_professionalism") ratingProfessionalism: okhttp3.RequestBody? = null,
        @Part("rating_packaging") ratingPackaging: okhttp3.RequestBody? = null,
        @Part("feedback") feedback: okhttp3.RequestBody? = null,
        @Part("emoji_reaction") emojiReaction: okhttp3.RequestBody? = null,
        @Part photo: okhttp3.MultipartBody.Part? = null
    ): Call<BaseResponse>

    // ============================================================
    // VENDOR EARNINGS & WITHDRAWALS
    // ============================================================

    @GET("vendor-earnings.php")
    fun getVendorEarnings(
        @Query("user_id") userId: Int,
        @Query("limit") limit: Int = 50
    ): Call<VendorEarningsResponse>

    @POST("vendor-earnings.php")
    fun requestVendorPayout(
        @Query("action") action: String = "request_payout",
        @Query("user_id") userId: Int
    ): Call<RequestVendorPayoutResponse>

    // ============================================================
    // VENDOR PRODUCTS ENDPOINTS
    // ============================================================

    @GET("vendor-products.php")
    fun getVendorProducts(
        @Query("user_id") userId: Int
    ): Call<VendorProductsResponse>

    @GET("vendor-catalogue.php")
    fun getMyVendorProducts(
        @Query("action") action: String = "mine"
    ): Call<VendorCatalogueResponse>

    @Multipart
    @POST("vendor-catalogue.php")
    fun createVendorProduct(
        @Query("action") action: String = "create",
        @Part("name") name: okhttp3.RequestBody,
        @Part("category") category: okhttp3.RequestBody,
        @Part("price") price: okhttp3.RequestBody,
        @Part("description") description: okhttp3.RequestBody,
        @Part("quantitytype") quantityType: okhttp3.RequestBody,
        @Part image: okhttp3.MultipartBody.Part? = null
    ): Call<CreateVendorProductResponse>

    @POST("vendor-products.php")
    fun addVendorProduct(
        @Body body: AddVendorProductRequest
    ): Call<BaseResponse>

    @DELETE("vendor-products.php")
    fun removeVendorProduct(
        @Query("product_id") productId: Int
    ): Call<BaseResponse>

    // ============================================================
    // VENDOR PROFILE
    // ============================================================

    @GET("vendor-profile.php")
    fun getVendorProfile(
        @Query("user_id") userId: Int
    ): Call<VendorProfileResponse>

    @POST("vendor-profile.php")
    fun updateVendorProfile(
        @Query("action") action: String = "update",
        @Body body: UpdateVendorProfileRequest
    ): Call<UpdateVendorProfileResponse>

    // ============================================================
    // PAYMENT ENDPOINTS
    // ============================================================
    
    @POST("payment.php")
    @Headers("Content-Type: application/json")
    fun initiatePayment(
        @Query("action") action: String = "initiate",
        @Query("order_type") orderType: String = ORDER_TYPE_SHOP,
        @Body request: PaymentRequest
    ): Call<PaymentResponse>

    @POST("payment.php")
    @FormUrlEncoded
    fun verifyPayment(
        @Query("action") action: String = "verify",
        @Query("order_type") orderType: String = ORDER_TYPE_SHOP,
        @Field("transaction_id") transactionId: String? = null,
        @Field("order_id") orderId: String? = null
    ): Call<PaymentResponse>

    // ============================================================
    // DELIVERY FEE QUOTE
    // ============================================================

    @POST("calculate-delivery-fee.php")
    @Headers("Content-Type: application/json")
    fun calculateDeliveryFee(
        @Body request: DeliveryQuoteRequest
    ): Call<DeliveryQuoteResponse>

    // ============================================================
    // NOTIFICATIONS ENDPOINTS
    // ============================================================

    @GET("notifications.php")
    fun getNotifications(
        @Query("action") action: String = "list"
    ): Call<NotificationsResponse>

    @GET("notifications.php")
    fun getUnreadNotificationCount(
        @Query("action") action: String = "unread-count"
    ): Call<UnreadCountResponse>

    @POST("notifications.php")
    @FormUrlEncoded
    fun markNotificationRead(
        @Query("action") action: String = "mark-read",
        @Field("id") notificationId: Int
    ): Call<BaseResponse>

    @POST("notifications.php")
    fun markAllNotificationsRead(
        @Query("action") action: String = "mark-all-read"
    ): Call<BaseResponse>

    // ============================================================
    // SAVED ADDRESSES
    // ============================================================

    @GET("addresses.php")
    fun getAddresses(
        @Query("action") action: String = "list"
    ): Call<AddressesResponse>

    @POST("addresses.php")
    @Headers("Content-Type: application/json")
    fun saveAddress(
        @Query("action") action: String,
        @Body address: Address
    ): Call<SaveAddressResponse>

    @POST("addresses.php")
    @FormUrlEncoded
    fun deleteAddress(
        @Query("action") action: String = "delete",
        @Field("id") id: String
    ): Call<BaseResponse>

    @POST("addresses.php")
    @FormUrlEncoded
    fun setDefaultAddress(
        @Query("action") action: String = "set_default",
        @Field("id") id: String
    ): Call<BaseResponse>

    // ============================================================
    // PRODUCT FAVORITES
    // ============================================================

    @GET("favorites.php")
    fun getFavorites(
        @Query("action") action: String = "list"
    ): Call<FavoritesResponse>

    @POST("favorites.php")
    @FormUrlEncoded
    fun toggleFavorite(
        @Query("action") action: String = "toggle",
        @Field("product_id") productId: Int
    ): Call<ToggleFavoriteResponse>

    // ------------------------------------------------------------
    // FCM TOKEN REGISTRATION
    // ------------------------------------------------------------
    
    @POST("notifications.php")
    @FormUrlEncoded
    fun registerFCMToken(
        @Query("action") action: String = "register-token",
        @Field("fcm_token") fcmToken: String,
        @Field("device_id") deviceId: String? = null
    ): Call<BaseResponse>

    // ============================================================
    // PROFILE ENDPOINTS  (api/profile.php)
    // ============================================================

    @POST("profile.php")
    fun updateProfile(
        @Query("action") action: String = "update",
        @Body body: UpdateProfileRequest
    ): Call<UserResponse>

    @POST("profile.php")
    @FormUrlEncoded
    fun changePassword(
        @Query("action") action: String = "change_password",
        @Field("current_password") currentPassword: String,
        @Field("new_password") newPassword: String
    ): Call<BaseResponse>

    @Multipart
    @POST("profile.php")
    fun uploadAvatar(
        @Query("action") action: String = "upload_avatar",
        @Part avatar: okhttp3.MultipartBody.Part
    ): Call<UserResponse>

    @POST("profile.php")
    fun removeAvatar(
        @Query("action") action: String = "remove_avatar"
    ): Call<UserResponse>

    @POST("profile.php")
    @FormUrlEncoded
    fun updateNotificationPrefs(
        @Query("action") action: String = "notification_prefs",
        @Field("email") email: Boolean,
        @Field("push") push: Boolean
    ): Call<UserResponse>

    // ============================================================
    // RIDER ENDPOINTS
    // ============================================================

    @GET("rider.php")
    fun getRiderProfile(
        @Query("action") action: String = "me"
    ): Call<RiderProfileResponse>

    @GET("rider.php")
    fun getDeliveries(
        @Query("action") action: String = "deliveries"
    ): Call<DeliveriesResponse>

    @GET("rider.php")
    fun getDeliveryDetail(
        @Query("action") action: String = "delivery_detail",
        @Query("order_id") orderId: Int,
        @Query("source") source: String = "order"
    ): Call<DeliveryDetailResponse>

    @POST("rider.php")
    @FormUrlEncoded
    fun updateDeliveryStatus(
        @Query("action") action: String = "update_status",
        @Field("order_id") orderId: Int,
        @Field("status") status: String,
        @Field("source") source: String = "order",
        @Field("cash_collected") cashCollected: String? = null,
        @Field("delivery_otp") deliveryOtp: String? = null,
        @Field("latitude") latitude: Double? = null,
        @Field("longitude") longitude: Double? = null
    ): Call<UpdateDeliveryStatusResponse>

    @POST("rider.php")
    @FormUrlEncoded
    fun setDutyStatus(
        @Query("action") action: String = "duty_status",
        @Field("status") status: String
    ): Call<DutyStatusResponse>

    @POST("rider.php")
    @FormUrlEncoded
    fun postRiderLocation(
        @Query("action") action: String = "location",
        @Field("lat") lat: Double,
        @Field("lng") lng: Double,
        @Field("accuracy") accuracy: Float? = null,
        @Field("speed") speed: Float? = null,
        @Field("heading") heading: Float? = null
    ): Call<BaseResponse>

    @Multipart
    @POST("rider.php")
    fun uploadDeliveryProof(
        @Query("action") action: String = "upload_proof",
        @Part("order_id") orderId: okhttp3.RequestBody,
        @Part("source") source: okhttp3.RequestBody,
        @Part photo: okhttp3.MultipartBody.Part
    ): Call<ProofUploadResponse>

    @GET("rider.php")
    fun getRiderEarnings(
        @Query("action") action: String = "earnings"
    ): Call<EarningsResponse>

    @POST("rider.php")
    fun requestPayout(
        @Query("action") action: String = "request_payout"
    ): Call<RequestPayoutResponse>

    @GET("rider.php")
    fun getMyPayouts(
        @Query("action") action: String = "my_payouts"
    ): Call<MyPayoutsResponse>

    // ============================================================
    // ROLE REQUESTS
    // ============================================================

    @GET("roles.php")
    fun getRoleStatus(
        @Query("action") action: String = "status",
        @Query("role") role: String
    ): Call<RoleStatusResponse>

    @POST("roles.php")
    @FormUrlEncoded
    fun requestRole(
        @Query("action") action: String = "request",
        @Field("role") role: String
    ): Call<RoleRequestResponse>

    // ============================================================
    // APP CONFIG ENDPOINT
    // ============================================================

    @GET("config.php")
    fun getAppConfig(): Call<AppConfigResponse>

    // ============================================================
    // LIVE TRACKING
    // ============================================================

    @GET("tracking.php")
    fun getOrderTracking(
        @Query("order_id") orderId: Int,
        @Query("source") source: String = ORDER_TYPE_SHOP_TRACKING,
        @Query("since") since: String? = null
    ): Call<TrackingResponse>

    companion object {
        const val ORDER_TYPE_SHOP = "shop"
        const val ORDER_TYPE_Bulk = "Bulk"
        const val ORDER_TYPE_SHOP_TRACKING = "order"
    }
}