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

    // Returns {"success":true,"product":{...}} — a wrapper, not a bare Product.
    // This was Call<Product>, which parsed the envelope and produced an
    // all-null product.
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

    // Returns {"success":true,"order":{...}} — wrapped, not a bare Order.
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

    // ✅ Update Order.
    //
    // `delivery_notes` was removed: there is no such column on `orders`, and the
    // endpoint ignores unknown fields, so sending it did nothing.
    //
    // The server accepts only these five fields and refuses the request once the
    // order has left the editable states — see Order.isEditable, which mirrors
    // isOrderEditable() in orders.php.
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
    
    // ✅ VERIFIED against api/surplus-listings.php.
    //
    // There is no `action` parameter — this endpoint dispatches on HTTP method.
    // Note the server also hard-filters `remaining_quantity > 0` and
    // `expiry_date > NOW()`, so sold-out and expired listings are never
    // returned regardless of the status asked for.
    @GET("surplus-listings.php")
    fun getSurplusListings(
        @Query("status") status: String = "approved",
        @Query("vendor_id") vendorId: Int? = null,
        @Query("listing_type") listingType: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Call<SurplusListingsResponse>

    // ============================================================
    // VENDOR SURPLUS ENDPOINTS
    // ============================================================
    //
    // ⚠️ These previously pointed at `vendor/surplus/listings.php`, which does
    // not exist — every call 404'd. There is no vendor-specific surplus file;
    // vendors use the same surplus-listings.php, scoped by vendor_id/user_id.
    //
    // `vendor-listings.php` is NOT this endpoint — it is a public directory of
    // verified vendors.

    /** A vendor's own listings are the public list scoped by vendor_id. */
    @GET("surplus-listings.php")
    fun getVendorSurplusListings(
        @Query("vendor_id") vendorId: Int,
        @Query("status") status: String = "approved",
        @Query("limit") limit: Int = 50
    ): Call<SurplusListingsResponse>

    /** JSON body, keyed on user_id; server derives vendor_id and sets status=pending. */
    @POST("surplus-listings.php")
    @Headers("Content-Type: application/json")
    fun createVendorSurplusListing(
        @Body listing: CreateSurplusListingRequest
    ): Call<SurplusListingResponse>

    /** Only status / remaining_quantity / admin_notes are updatable server-side. */
    @PUT("surplus-listings.php")
    @Headers("Content-Type: application/json")
    fun updateVendorSurplusListing(
        @Body update: UpdateSurplusListingRequest
    ): Call<BaseResponse>

    /** Soft delete — sets status='cancelled'. listing_id is a QUERY param. */
    @DELETE("surplus-listings.php")
    fun deleteVendorSurplusListing(
        @Query("listing_id") listingId: Int
    ): Call<BaseResponse>

    // ============================================================
    // VENDOR ORDERS ENDPOINTS
    // ============================================================
    //
    // ⚠️ `vendor/orders.php` does not exist and never did. The nearest real
    // endpoint is surplus-orders.php scoped by vendor_id, which covers surplus
    // orders only. Ordinary catalogue orders are not exposed per-vendor at all.

    @GET("surplus-orders.php")
    fun getVendorOrders(
        @Query("vendor_id") vendorId: Int,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Call<VendorOrdersResponse>

    // ============================================================
    // VENDOR PRODUCTS ENDPOINTS
    // ============================================================
    //
    // ⚠️ Was `vendor/products.php` (404). The real file takes `user_id` as a
    // query parameter and looks the vendor up from it — it does not read the
    // session, so the caller must supply the logged-in user's id.

    @GET("vendor-products.php")
    fun getVendorProducts(
        @Query("user_id") userId: Int
    ): Call<VendorProductsResponse>

    // ============================================================
    // VENDOR PROFILE
    // ============================================================
    //
    // Resolves user_id -> vendor_id. Required because the vendor endpoints are
    // inconsistent about which id they take, and the auth response carries
    // neither the vendor id nor the user's roles.

    @GET("vendor-profile.php")
    fun getVendorProfile(
        @Query("user_id") userId: Int
    ): Call<VendorProfileResponse>

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
    // DELIVERY ENDPOINTS — REMOVED
    // ============================================================
    //
    // getDeliveryAreas, saveDeliveryLocation and getSavedDeliveryLocation used
    // to be declared here against `delivery.php`. That file does not exist in
    // api/, and nothing in the app ever called them, so they have been deleted
    // rather than left as three guaranteed 404s.
    //
    // `location.php` is not a replacement — it is rider GPS tracking
    // (?action=update writes the caller's position, ?action=rider&id=N reads a
    // rider's). Delivery quoting is calculateDeliveryFee above.

    // ============================================================
    // NOTIFICATIONS ENDPOINTS
    // ============================================================
    //
    // ✅ VERIFIED against api/notifications.php. Actions are HYPHENATED:
    // list | unread-count | mark-read | mark-all-read. The endpoint requires a
    // session and returns {"success":false,"error":"Not logged in"} otherwise.

    @GET("notifications.php")
    fun getNotifications(
        @Query("action") action: String = "list"
    ): Call<NotificationsResponse>

    @GET("notifications.php")
    fun getUnreadNotificationCount(
        @Query("action") action: String = "unread-count"
    ): Call<UnreadCountResponse>

    // Was action="mark_read" with field "notification_id" — both wrong. The PHP
    // matches "mark-read" and reads $_POST['id'].
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

    // ------------------------------------------------------------
    // FCM TOKEN REGISTRATION
    // ------------------------------------------------------------
    //
    // ⚠️ NOT IMPLEMENTED SERVER-SIDE. There is no endpoint anywhere in api/
    // that writes `users.fcm_token`. The push pipeline reads that column
    // (includes/classes/NotificationManager.php) but nothing ever fills it, so
    // push notifications cannot reach this app until the endpoint below exists.
    //
    // Required contract — add to notifications.php alongside the other actions:
    //
    //   POST notifications.php?action=register-token
    //     fields:  fcm_token (required), device_id (optional)
    //     session: required; the token is stored against $_SESSION user id
    //     returns  { "success": true }
    //
    //     UPDATE users SET fcm_token = :token WHERE id = :user_id
    //
    //     Must overwrite, not append: a token is per-install and Firebase
    //     rotates it, so the previous value is stale the moment this is called.
    //     Should also clear the column on logout, otherwise pushes keep going
    //     to a device that has signed out.
    @POST("notifications.php")
    @FormUrlEncoded
    fun registerFCMToken(
        @Query("action") action: String = "register-token",
        @Field("fcm_token") fcmToken: String,
        @Field("device_id") deviceId: String? = null
    ): Call<BaseResponse>

    // ============================================================
    // APP CONFIG ENDPOINT
    // ============================================================

    @GET("config.php")
    fun getAppConfig(): Call<AppConfigResponse>
}