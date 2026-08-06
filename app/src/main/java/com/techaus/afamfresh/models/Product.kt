package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * ✅ VERIFIED against the real backend, not inferred.
 *
 * `api/products.php` does `SELECT * FROM items` and hands the rows straight to
 * `json_encode` with no normalisation, so the wire format IS the `items` table.
 * Every @SerializedName below is a column that exists in `kitchen.items`.
 *
 * Removed from the previous version, because no such column exists and the
 * server can therefore never send them — they were always null, so the badges
 * that read them never rendered:
 *
 *   rating, calories, prep_time_minutes, ingredient_icons
 *
 * They have been replaced with the fields the catalogue actually carries:
 * unit (`quantitytype`), discount, stock and the deal/offer flags.
 *
 * `price` is VARCHAR(50) in the database. Gson coerces a JSON string to Double,
 * and all 72 current rows are clean numeric strings, so this parses today — but
 * one row entered as "12,500" or "12500/=" would throw at parse time and take
 * out the whole product list. The column wants to be DECIMAL(12,2).
 */
data class Product(
    // int(100) auto_increment. Was modelled as Double, which rendered ids as
    // "4.0" in navigation routes and cart keys.
    @SerializedName("id") val id: Int,

    @SerializedName("name") val name: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("price") val price: Double = 0.0,
    /**
     * The raw `items.image` column: a bare filename like "apple.png".
     *
     * Not loadable — Coil cannot resolve a relative name — which is why product
     * images never rendered. Kept because it is still what the column holds;
     * use [imageUrl] to display one.
     */
    @SerializedName("image") val image: String? = null,

    /**
     * Absolute, loadable URL built server-side by `includes/product_image.php`.
     *
     * Null when the file is missing from disk, which is the case for most of
     * the catalogue: the rows reference filenames that were never uploaded to
     * this deployment. Null is the signal to draw a placeholder rather than
     * retry a 404 on every scroll.
     *
     * Nullable with a null default, like every other field here — Gson skips
     * the Kotlin constructor, so a non-null declaration would not survive an
     * absent JSON key (e.g. against an older backend that predates this field).
     */
    @SerializedName("image_url") val imageUrl: String? = null,

    /** Numeric part of the pack size, e.g. "500" — VARCHAR(11) upstream. */
    @SerializedName("quantity") val quantity: String? = null,

    /** Unit of sale: "Kg", "Grams", "Unit". Column is `quantitytype`. */
    @SerializedName("quantitytype") val unit: String? = null,

    /** decimal(5,2) percentage off. 0.00 on every current row. */
    @SerializedName("discount") val discountPercent: Double = 0.0,

    /**
     * NULLABLE ON PURPOSE — null means "the server did not tell us".
     *
     * A non-null default would not survive Gson: when a JSON field is absent,
     * Gson allocates the object with Unsafe rather than calling the Kotlin
     * constructor, so declared default values are skipped and an Int lands on 0.
     * That turned a missing `stock_qty` into "out of stock" and disabled Add to
     * Cart on every product. Nullable makes absence distinguishable from a real
     * zero, and [inStock] treats only a real 0 as a stockout.
     */
    @SerializedName("stock_qty") val stockQty: Int? = null,

    @SerializedName("freshness_date") val freshnessDate: String? = null,

    // enum('YES','NO') / varchar flags. Kept as strings because that is what
    // the column holds; use the booleans below rather than comparing by hand.
    @SerializedName("weekly_deal") val weeklyDeal: String? = null,
    @SerializedName("offer") val offer: String? = null,
    @SerializedName("homepage") val homepage: String? = null
) {
    /**
     * Stock is not tracked yet — every catalogue row sits at the column default
     * of 999 — so an unreported quantity must not block the sale. Only an
     * explicit 0 or negative counts as a stockout.
     */
    val inStock: Boolean get() = (stockQty ?: Int.MAX_VALUE) > 0

    val isWeeklyDeal: Boolean get() = weeklyDeal.isYes()
    val isOffer: Boolean get() = offer.isYes()
    val isOnHomepage: Boolean get() = homepage.isYes()

    /** True only when there is a discount worth showing. */
    val hasDiscount: Boolean get() = discountPercent > 0.0

    /** What the customer actually pays. Falls back to [price] when undiscounted. */
    val effectivePrice: Double
        get() = if (hasDiscount) price * (1.0 - discountPercent / 100.0) else price

    /** e.g. "500 Grams", or just "Kg" when no quantity is recorded. */
    val packLabel: String?
        get() {
            val q = quantity?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
            val u = unit?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                q != null && u != null -> "$q $u"
                else -> u ?: q
            }
        }

    private fun String?.isYes() = this?.trim().equals("YES", ignoreCase = true)
}

data class ProductsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("products") val products: List<Product>? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * `action=detail` replies `{"success":true,"product":{...}}` — the product is
 * WRAPPED. ApiService previously declared this call as `Call<Product>`, so Gson
 * parsed the envelope into a Product and every field came back null.
 */
data class ProductDetailResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("product") val product: Product? = null,
    @SerializedName("error") val error: String? = null
)

data class CartItem(
    val product: Product,
    val quantity: Int
) {
    /** Uses the discounted price so the cart total matches what is displayed. */
    val lineTotal: Double get() = product.effectivePrice * quantity
}
