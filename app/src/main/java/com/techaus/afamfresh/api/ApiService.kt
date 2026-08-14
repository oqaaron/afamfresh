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
        /**
         * Which app is signing in, from BuildConfig.APP_ROLE — the same field
         * [LoginRequest] carries for email/password.
         *
         * It decides `users.account_type` on first Google sign-in, and the
         * server refuses an existing account whose type does not match. While
         * this was missing, every Google account was created as a customer,
         * so "request vendor access" was rejected before it could reach the
         * admin queue.
         */
        @Field("app_role") appRole: String = BuildConfig.APP_ROLE
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

    // `scheme` tells the server which app's deep link to put in the email.
    // Customer, Rider and Vendor are separate installs; without this they would
    // all claim afamfresh:// and tapping a reset link would raise an
    // app-chooser instead of opening the app that asked for it. The server
    // checks the value against a fixed list and falls back to the customer
    // scheme, so older installs that send nothing still work.
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
        @Field("delivery_cost") deliveryCost: Double = 0.0,
        @Field("points_redeem") pointsRedeem: Int = 0
    ): Call<OrderCreateResponse>

    /**
     * How many of a customer's requested loyalty points can actually be
     * redeemed against a goods value, and the resulting discount — before
     * committing to an order. Shared by shop and surplus checkout; see
     * LoyaltyQuoteRequest's own doc.
     */
    @POST("loyalty-quote.php")
    @Headers("Content-Type: application/json")
    fun getLoyaltyQuote(
        @Body request: LoyaltyQuoteRequest
    ): Call<LoyaltyQuoteResponse>

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

    /**
     * The customer's confirm-and-rate step, once the rider has uploaded proof
     * of delivery. The server refuses this before that (409-shaped error in
     * the JSON body, not an HTTP code — see api/orders.php) and refuses a
     * second call once it has already been confirmed.
     *
     * Multipart because the photo is optional, not because one is expected —
     * every field but order_id may be omitted for a bare confirmation.
     */
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

    /**
     * Places a surplus order.
     *
     * The order is created UNPAID and the listing's stock is decremented
     * immediately, so this call is a reservation as much as an order. Pay for it
     * with [initiatePayment] passing `order_type = "surplus"`; an order left
     * unpaid for 30 minutes is cancelled server-side and its stock returned.
     *
     * The server rejects orders under UGX 250,000, under 20 kg on weight-based
     * listings, and over 1000 kg. Those come back as {"error": "..."} with a
     * message written for the customer, so show it rather than replacing it.
     */
    @POST("surplus-orders.php")
    @Headers("Content-Type: application/json")
    fun createSurplusOrder(
        @Body request: CreateSurplusOrderRequest
    ): Call<CreateSurplusOrderResponse>

    /**
     * Prices a surplus order without placing it.
     *
     * Runs the identical calculation order creation will, so the total shown at
     * checkout and the amount charged cannot drift apart. The delivery fee
     * depends on weight AND distance, neither of which the app can work out, so
     * this is the only honest way to show a total before committing.
     *
     * Answers OUT_OF_SERVICE_AREA when the pin falls outside Greater Kampala.
     */
    @POST("surplus-quote.php")
    @Headers("Content-Type: application/json")
    fun getSurplusQuote(
        @Body request: SurplusQuoteRequest
    ): Call<SurplusQuoteResponse>

    /**
     * A customer's own surplus orders.
     *
     * user_id must be sent, but the server ignores what it says and uses the
     * session — asking for someone else's id returns yours, not theirs. Omitting
     * it entirely used to return every surplus order on the platform.
     */
    @GET("surplus-orders.php")
    fun getMySurplusOrders(
        @Query("user_id") userId: Int,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Call<SurplusOrdersResponse>

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
    ): Call<SurplusOrdersResponse>

    /**
     * Moves a surplus order along. The vendor who owns the listing may send it;
     * so may an admin. Nobody else — before that check existed, anyone could
     * mark any order delivered, which now means anyone could pay a vendor.
     *
     * Reaching "delivered" credits the vendor's earnings ledger, idempotently.
     * The server refuses the transition on an unpaid order and answers 409.
     */
    @PUT("surplus-orders.php")
    @Headers("Content-Type: application/json")
    fun updateSurplusOrderStatus(
        @Body request: UpdateSurplusOrderStatusRequest
    ): Call<BaseResponse>

    /**
     * The customer's confirm-and-rate step for a SURPLUS order. Mirrors
     * [confirmOrderReceipt]; kept as its own call because it hits a different
     * file and the server needs user_id to resolve which of the two id spaces
     * `order_id` (shared with `orders`) actually means.
     *
     * A plain POST gated on ?action=confirm_receipt, not PUT — PHP does not
     * auto-populate $_FILES for PUT/PATCH bodies, and this optionally carries
     * a photo.
     */
    @Multipart
    @POST("surplus-orders.php")
    fun confirmSurplusReceipt(
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
    //
    // ✅ VERIFIED against api/vendor-earnings.php.
    //
    // `user_id` is sent but not trusted — the endpoint runs it through
    // requireOwnUserId() and resolves the vendor from the session, so asking
    // for another vendor's ledger returns your own, not theirs.

    /** Ledger, totals and the vendor's own withdrawal requests, in one call. */
    @GET("vendor-earnings.php")
    fun getVendorEarnings(
        @Query("user_id") userId: Int,
        @Query("limit") limit: Int = 50
    ): Call<VendorEarningsResponse>

    /**
     * Asks to withdraw. No amount is sent: the server sums the unpaid ledger
     * itself, because a client-supplied amount is a client-supplied withdrawal.
     *
     * Refused when nothing is available, or when a request is already open —
     * two open requests would each claim the same balance and approving both
     * would pay it out twice.
     */
    @POST("vendor-earnings.php")
    fun requestVendorPayout(
        @Query("action") action: String = "request_payout",
        @Query("user_id") userId: Int
    ): Call<RequestVendorPayoutResponse>

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
    // VENDOR'S OWN PRODUCTS  (api/vendor-catalogue.php)
    // ============================================================
    //
    // Products the vendor creates themselves, which an admin approves before
    // they can be listed as surplus. Distinct from vendor-products.php, which
    // is "catalogue items I stock" — this is "products I own".
    //
    // No user_id on either call: the server takes the vendor from the session.

    @GET("vendor-catalogue.php")
    fun getMyVendorProducts(
        @Query("action") action: String = "mine"
    ): Call<VendorCatalogueResponse>

    // Multipart because a photo comes with it. The server also accepts the
    // fields without one, so `image` is optional.
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

    // Adds a catalogue item to this vendor's inventory, or updates the price
    // and stock if it is already there — the endpoint upserts.
    //
    // Both of these existed server-side from the start and the app never called
    // either, which is why a vendor's inventory could only be filled by an
    // admin, and why the surplus form had nothing to offer in its picker.
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
    //
    // Resolves user_id -> vendor_id. Required because the vendor endpoints are
    // inconsistent about which id they take, and the auth response carries
    // neither the vendor id nor the user's roles.

    @GET("vendor-profile.php")
    fun getVendorProfile(
        @Query("user_id") userId: Int
    ): Call<VendorProfileResponse>

    // Second stage of vendor onboarding: the details the vendor supplies after
    // an admin approves their role request, before an admin verifies them.
    //
    // Sends no user_id — the server takes the vendor from the session, so this
    // cannot be pointed at someone else's account.
    @POST("vendor-profile.php")
    fun updateVendorProfile(
        @Query("action") action: String = "update",
        @Body body: UpdateVendorProfileRequest
    ): Call<UpdateVendorProfileResponse>

    // ============================================================
    // PAYMENT ENDPOINTS
    // ============================================================
    
    // ✅ VERIFIED against the rewritten api/payment.php.
    //
    // The `action` query parameter was MISSING from initiatePayment, so every
    // call was answered with {"success":false,"error":"Missing action parameter"}
    // before any Pesapal code ran.
    //
    // The request carries no amount: the server takes the payable total from the
    // orders row and ignores anything the client sends. See PaymentRequest.
    //
    // `order_type` says which table order_id refers to: "shop" is the `orders`
    // table, "surplus" is `surplus_orders`. It defaults to "shop" server-side,
    // so it is only ever sent explicitly. The two id spaces overlap — shop order
    // 41 and surplus order 41 both exist — which is why this cannot be inferred.
    @POST("payment.php")
    @Headers("Content-Type: application/json")
    fun initiatePayment(
        @Query("action") action: String = "initiate",
        @Query("order_type") orderType: String = ORDER_TYPE_SHOP,
        @Body request: PaymentRequest
    ): Call<PaymentResponse>

    /**
     * Asks the server to re-check the payment with Pesapal's
     * GetTransactionStatus. Either identifier works: the app usually knows the
     * order id, the WebView callback knows the tracking id.
     *
     * A 502/VERIFY_UNAVAILABLE means the outcome is UNKNOWN, not failed — retry
     * rather than telling the customer the payment failed.
     */
    @POST("payment.php")
    @FormUrlEncoded
    fun verifyPayment(
        @Query("action") action: String = "verify",
        @Query("order_type") orderType: String = ORDER_TYPE_SHOP,
        @Field("transaction_id") transactionId: String? = null,
        @Field("order_id") orderId: String? = null
    ): Call<PaymentResponse>

    // NOTE: paymentCallback was removed. It posted a client-supplied `status` to
    // payment.php?action=callback — an action that no longer exists, and a
    // pattern that would let the app declare its own payment successful.
    // Pesapal notifies the server directly (pesapal-ipn.php); the app confirms
    // by calling verifyPayment, which asks Pesapal.

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
    // PROFILE ENDPOINTS  (api/profile.php)
    // ============================================================
    //
    // ✅ VERIFIED against the real api/profile.php.
    //
    // Every action except changePassword returns the FULL user object, so the
    // caller replaces its cached copy in one step instead of patching single
    // fields and drifting out of sync with the server.
    //
    // All of these require a session — the PHP session cookie is carried by
    // CookieJarImpl, so nothing extra is needed here.

    @POST("profile.php")
    fun updateProfile(
        @Query("action") action: String = "update",
        @Body body: UpdateProfileRequest
    ): Call<UserResponse>

    // Returns only {"success":true} — there is nothing to display afterwards.
    // The session deliberately stays alive, so the user is NOT signed out.
    @POST("profile.php")
    @FormUrlEncoded
    fun changePassword(
        @Query("action") action: String = "change_password",
        @Field("current_password") currentPassword: String,
        @Field("new_password") newPassword: String
    ): Call<BaseResponse>

    // The server sniffs the real MIME type and derives the stored extension
    // from that, so the filename sent here is not security-relevant.
    @Multipart
    @POST("profile.php")
    fun uploadAvatar(
        @Query("action") action: String = "upload_avatar",
        @Part avatar: okhttp3.MultipartBody.Part
    ): Call<UserResponse>

    // Clears the uploaded picture; avatar_url then falls back to the Google
    // photo when there is one, so this is "remove mine", not "no avatar".
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
    //
    // A rider is an ordinary app account with the Rider role, linked to a
    // `riders` row by riders.user_id. Every call below is authenticated by the
    // normal session cookie — there is no separate rider login. Each returns
    // "This account is not set up as a rider yet" when the link is missing,
    // which is a state an admin can leave the account in.
    // ============================================================

    @GET("rider.php")
    fun getRiderProfile(
        @Query("action") action: String = "me"
    ): Call<RiderProfileResponse>

    /** Assigned orders, already split into active and delivered by the server. */
    @GET("rider.php")
    fun getDeliveries(
        @Query("action") action: String = "deliveries"
    ): Call<DeliveriesResponse>

    //
    // `source` says which table order_id refers to — "order" for the shop,
    // "surplus" for a bulk order collected from a vendor. It must come from the
    // Delivery the rider tapped, never be assumed: the id spaces overlap, and
    // guessing would open a different customer's job.
    @GET("rider.php")
    fun getDeliveryDetail(
        @Query("action") action: String = "delivery_detail",
        @Query("order_id") orderId: Int,
        @Query("source") source: String = "order"
    ): Call<DeliveryDetailResponse>

    /**
     * Advances one delivery. The server enforces the forward-only flow
     * (assigned → picked_up → on_way → delivered) and writes the assignment
     * row and both `orders` status columns in one transaction.
     */
    @POST("rider.php")
    @FormUrlEncoded
    fun updateDeliveryStatus(
        @Query("action") action: String = "update_status",
        @Field("order_id") orderId: Int,
        @Field("status") status: String,
        @Field("source") source: String = "order",
        // Only meaningful on the transition to "delivered" for an order that
        // was pending_cash — the server ignores it otherwise. Sent as "1" once
        // the rider has confirmed they physically hold the cash.
        @Field("cash_collected") cashCollected: String? = null
    ): Call<UpdateDeliveryStatusResponse>

    @POST("rider.php")
    @FormUrlEncoded
    fun setDutyStatus(
        @Query("action") action: String = "duty_status",
        @Field("status") status: String
    ): Call<DutyStatusResponse>

    /** Writes the rider's position and breadcrumbs any active delivery. */
    @POST("rider.php")
    @FormUrlEncoded
    fun postRiderLocation(
        @Query("action") action: String = "location",
        @Field("lat") lat: Double,
        @Field("lng") lng: Double,
        // Optional quality metadata. The server drops trail points worse than
        // 100m of accuracy — without it, GPS wobble reads as the rider
        // teleporting on the customer's map.
        @Field("accuracy") accuracy: Float? = null,
        @Field("speed") speed: Float? = null,
        @Field("heading") heading: Float? = null
    ): Call<BaseResponse>

    // Same hardening as the avatar upload: the server sniffs the real MIME
    // type and derives the stored extension from that, so the filename sent
    // here is not security-relevant.
    //
    // Surplus deliveries are refused by the server here — surplus_orders has no
    // delivery_photo column, so there is nowhere to file the proof. It says so
    // rather than accepting the upload and dropping it.
    @Multipart
    @POST("rider.php")
    fun uploadDeliveryProof(
        @Query("action") action: String = "upload_proof",
        @Part("order_id") orderId: okhttp3.RequestBody,
        @Part("source") source: okhttp3.RequestBody,
        @Part photo: okhttp3.MultipartBody.Part
    ): Call<ProofUploadResponse>

    /** Summary (today/week/all-time/available) plus the per-delivery ledger. */
    @GET("rider.php")
    fun getRiderEarnings(
        @Query("action") action: String = "earnings"
    ): Call<EarningsResponse>

    /**
     * Requests a payout of everything currently unpaid. The amount is
     * decided server-side from rider_earnings, never sent by the client.
     */
    // No @FormUrlEncoded: Retrofit requires at least one @Field on a
    // form-encoded request, and this one carries no body — the server reads
    // everything it needs from the session.
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
    //
    // Installing the Rider or Vendor app grants nothing. An account
    // registers, requests the role here, and an admin approves it in
    // admin/role-requests.php — which also creates the riders/vendors
    // record the workspace needs.
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

    /**
     * Where a delivery is right now.
     *
     * Permission is resolved server-side FROM THE ORDER — its customer, its
     * rider, or an admin — so passing someone else's order id answers 403 with
     * the same wording as an order that does not exist.
     *
     * `since` returns only breadcrumbs newer than that timestamp, which keeps a
     * steady-state poll to a few hundred bytes. The response carries
     * `poll_after_seconds`; obey it rather than choosing an interval here.
     */
    @GET("tracking.php")
    fun getOrderTracking(
        @Query("order_id") orderId: Int,
        @Query("source") source: String = ORDER_TYPE_SHOP_TRACKING,
        @Query("since") since: String? = null
    ): Call<TrackingResponse>

    companion object {
        /** payment.php `order_type`: the `orders` table. The server's default. */
        const val ORDER_TYPE_SHOP = "shop"

        /** payment.php `order_type`: the `surplus_orders` table. */
        const val ORDER_TYPE_SURPLUS = "surplus"

        /**
         * tracking.php and rider.php use `source`, whose shop value is "order"
         * — NOT payment.php's "shop". Two different endpoints, two vocabularies;
         * naming them separately is cheaper than one of them being silently
         * wrong.
         */
        const val ORDER_TYPE_SHOP_TRACKING = "order"
    }
}