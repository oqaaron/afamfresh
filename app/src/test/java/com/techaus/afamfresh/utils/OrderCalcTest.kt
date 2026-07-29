package com.techaus.afamfresh.utils

import com.google.gson.Gson
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.DeliveryResult
import com.techaus.afamfresh.models.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the customer is charged and what the backend is told they bought.
 * A wrong total here is the worst bug this app can have.
 */
class OrderCalcTest {

    private fun product(id: Double, name: String, price: Double) = Product(
        id = id, name = name, price = price
    )

    private val tomatoes = product(1.0, "Tomatoes", 2500.0)
    private val onions = product(2.0, "Onions", 1800.0)

    private val cart = listOf(
        CartItem(tomatoes, 2),   // 5000
        CartItem(onions, 3)      // 5400
    )

    private fun delivery(cost: Double) = DeliveryResult(
        pickupAddress = "Shop",
        dropoffAddress = "Somewhere",
        pickupLat = 0.0,
        pickupLng = 0.0,
        dropoffLat = 0.0,
        dropoffLng = 0.0,
        distanceKm = 4.0,
        cost = cost
    )

    // ===== subtotal =====

    @Test
    fun `subtotal sums price times quantity across lines`() {
        assertEquals(10400.0, OrderCalc.subtotal(cart), 0.001)
    }

    @Test
    fun `empty cart has a zero subtotal`() {
        assertEquals(0.0, OrderCalc.subtotal(emptyList()), 0.001)
    }

    // ===== delivery =====

    @Test
    fun `delivery cost comes from the delivery result`() {
        assertEquals(6000.0, OrderCalc.deliveryCost(delivery(6000.0)), 0.001)
    }

    /**
     * No location selected means no delivery charge. This mirrors what the
     * cart and checkout screens display, and the three must agree — a total
     * that includes a fee the customer was never shown is the failure mode
     * worth guarding.
     */
    @Test
    fun `no delivery result means no delivery charge`() {
        assertEquals(0.0, OrderCalc.deliveryCost(null), 0.001)
    }

    // ===== total =====

    @Test
    fun `total is subtotal plus delivery`() {
        assertEquals(16400.0, OrderCalc.total(cart, delivery(6000.0)), 0.001)
    }

    @Test
    fun `total equals subtotal when no location has been chosen`() {
        assertEquals(10400.0, OrderCalc.total(cart, null), 0.001)
    }

    @Test
    fun `total of an empty cart with delivery is just the delivery`() {
        assertEquals(6000.0, OrderCalc.total(emptyList(), delivery(6000.0)), 0.001)
    }

    // ===== order items =====

    @Test
    fun `order items carry id name price and quantity from the cart`() {
        val items = OrderCalc.toOrderItems(cart)

        assertEquals(2, items.size)
        assertEquals(1.0, items[0].productId, 0.001)
        assertEquals("Tomatoes", items[0].name)
        assertEquals(2500.0, items[0].price, 0.001)
        assertEquals(2, items[0].quantity)
    }

    /**
     * Unit price, not line total. Sending 5000 here for 2 x 2500 would make
     * the server compute a total of 10000 for that line.
     */
    @Test
    fun `order item price is the unit price, not the line total`() {
        val items = OrderCalc.toOrderItems(listOf(CartItem(tomatoes, 4)))
        assertEquals(2500.0, items[0].price, 0.001)
    }

    @Test
    fun `empty cart produces no order items`() {
        assertTrue(OrderCalc.toOrderItems(emptyList()).isEmpty())
    }

    // ===== serialisation =====

    @Test
    fun `items serialise with the wire field names the backend expects`() {
        val json = OrderCalc.serialiseItems(OrderCalc.toOrderItems(cart), Gson())

        // product_id, not productId — the @SerializedName must survive.
        assertTrue("expected product_id in $json", json.contains("\"product_id\""))
        assertTrue("expected name in $json", json.contains("\"name\""))
        assertTrue("expected price in $json", json.contains("\"price\""))
        assertTrue("expected quantity in $json", json.contains("\"quantity\""))
    }

    @Test
    fun `serialised payload is a JSON array of every line`() {
        val json = OrderCalc.serialiseItems(OrderCalc.toOrderItems(cart), Gson())

        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("Tomatoes"))
        assertTrue(json.contains("Onions"))
    }

    @Test
    fun `empty cart serialises to an empty array, not null`() {
        assertEquals("[]", OrderCalc.serialiseItems(emptyList(), Gson()))
    }

    /**
     * The order total must equal what the line items add up to, plus delivery.
     * If these ever diverge, the customer is charged one figure while the
     * backend is told another.
     */
    @Test
    fun `total agrees with the serialised line items`() {
        val items = OrderCalc.toOrderItems(cart)
        val fromItems = items.sumOf { it.price * it.quantity }

        assertEquals(OrderCalc.subtotal(cart), fromItems, 0.001)
        assertEquals(OrderCalc.total(cart, delivery(6000.0)), fromItems + 6000.0, 0.001)
    }
}
