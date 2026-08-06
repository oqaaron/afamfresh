package com.techaus.afamfresh.api

import com.techaus.afamfresh.models.LoginRequest
import com.techaus.afamfresh.models.LoginResponse
import com.techaus.afamfresh.models.User
import retrofit2.Call
import retrofit2.http.*

interface ApiService {

    // ===== AUTH =====
    // Your PHP: auth.php?action=login
    @POST("auth.php")
    @FormUrlEncoded
    fun login(
        @Query("action") action: String = "login",
        @Field("email") email: String,
        @Field("password") password: String
    ): Call<LoginResponse>

    // Your PHP: auth.php?action=me
    @GET("auth.php")
    fun getCurrentUser(
        @Query("action") action: String = "me"
    ): Call<UserResponse>

    // Your PHP: auth.php?action=logout
    @POST("auth.php")
    @FormUrlEncoded
    fun logout(
        @Query("action") action: String = "logout"
    ): Call<BaseResponse>

    // ===== PRODUCTS (For testing after login) =====
    @GET("products.php")
    fun getProducts(
        @Query("action") action: String = "list"
    ): Call<ProductsResponse>
}

// Response wrapper for current user
data class UserResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("user") val user: User?,
    @SerializedName("error") val error: String? = null
)

data class BaseResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("error") val error: String? = null
)

data class ProductsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("products") val products: List<Product>?,
    @SerializedName("error") val error: String? = null
)

data class Product(
    @SerializedName("id") val id: Int,
    @SerializedName("itemname") val itemname: String,
    @SerializedName("description") val description: String?,
    @SerializedName("category") val category: String?,
    @SerializedName("price") val price: Double,
    @SerializedName("image") val image: String?
)