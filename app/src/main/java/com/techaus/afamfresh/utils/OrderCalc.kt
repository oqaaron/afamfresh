package com.techaus.afamfresh.utils

import com.google.gson.Gson
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.DeliveryResult
import com.techaus.afamfresh.models.OrderItem

/**
 * The arithmetic and serialisation behind placing an order.
 *
 * Extracted out of CheckoutViewModel so it can be unit-tested directly. This
 * decides what the customer is charged and what the backend is told they
 * bought, so it is the single most consequential logic in the app — and it
 * was previously inline in a ViewModel, unreachable from a test.
 *
 * Deliberately free of Android and Compose types.
 */
object OrderCalc {

    /** Sum of every line before delivery. */
    fun subtotal(cartItems: List<CartItem>): Double =
        cartItems.sumOf { it.lineTotal }

    /**
     * Delivery is 0 when no location has been chosen.
     *
     * NOTE: this mirrors the existing behaviour, where an order can be placed
     * without a delivery location and is simply not charged for delivery. If
     * that is wrong for the business — i.e. a location should be mandatory —
     * the fix belongs in the checkout validation, not here.
     */
    fun deliveryCost(deliveryResult: DeliveryResult?): Double =
        deliveryResult?.cost ?: 0.0

    /** What the customer pays. */
    fun total(cartItems: List<CartItem>, deliveryResult: DeliveryResult?): Double =
        subtotal(cartItems) + deliveryCost(deliveryResult)

    /** Converts the cart into the line items sent to the backend. */
    fun toOrderItems(cartItems: List<CartItem>): List<OrderItem> =
        cartItems.map {
            OrderItem(
                productId = it.product.id,
                name = it.product.name,
                price = it.product.price,
                quantity = it.quantity
            )
        }

    /**
     * The JSON body the `items` field carries.
     *
     * Gson is passed in rather than constructed here so tests can assert on
     * the exact output with a known configuration.
     */
    fun serialiseItems(items: List<OrderItem>, gson: Gson = Gson()): String =
        gson.toJson(items)
}
