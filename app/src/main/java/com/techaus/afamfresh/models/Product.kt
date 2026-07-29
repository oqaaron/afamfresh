package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

// ⚠️ INFERRED. MainScreen.kt compares `product.id == productId` where productId
// comes from `.toDoubleOrNull()`, so `id` is modeled as Double here (NOT Int, which
// is what the stale duplicate api/ApiService.kt used — that file should be deleted,
// see earlier patch notes). Rating/kcal/prepTime/ingredients are added to support
// a product-detail screen matching the reference mockup; make these nullable-safe
// if your products.php doesn't return them yet.

data class Product(
    @SerializedName("id") val id: Double,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("price") val price: Double,
    @SerializedName("image") val image: String? = null,
    @SerializedName("rating") val rating: Double? = null,
    @SerializedName("prep_time_minutes") val prepTimeMinutes: Int? = null,
    @SerializedName("calories") val calories: Int? = null,
    @SerializedName("ingredient_icons") val ingredientIcons: List<String> = emptyList(),
    @SerializedName("in_stock") val inStock: Boolean = true
)

data class ProductsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("products") val products: List<Product>? = null,
    @SerializedName("error") val error: String? = null
)

data class CartItem(
    val product: Product,
    val quantity: Int
) {
    val lineTotal: Double get() = product.price * quantity
}
