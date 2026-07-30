package com.techaus.afamfresh.viewmodel

import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CartViewModelTest {

    private lateinit var cart: CartViewModel

    private fun product(id: Int, name: String, price: Double) = Product(
        id = id,
        name = name,
        description = null,
        category = null,
        price = price,
        image = null
    )

    private val tomatoes = product(1, "Tomatoes", 2500.0)
    private val onions = product(2, "Onions", 1800.0)

    @Before
    fun setUp() {
        cart = CartViewModel()
    }

    @Test
    fun `starts empty`() {
        assertTrue(cart.cartItems.value.isEmpty())
        assertEquals(0.0, cart.subtotal, 0.001)
    }

    @Test
    fun `adding a product puts it in the cart`() {
        cart.addToCart(tomatoes, 2)

        assertEquals(1, cart.cartItems.value.size)
        assertEquals(2, cart.cartItems.value.first().quantity)
        assertEquals(5000.0, cart.subtotal, 0.001)
    }

    /**
     * Adding the same product again must increase the quantity, not create a
     * second line. A duplicated line would double-count in the subtotal and
     * confuse the order sent to the backend.
     */
    @Test
    fun `adding the same product again increments quantity instead of duplicating`() {
        cart.addToCart(tomatoes, 2)
        cart.addToCart(tomatoes, 3)

        assertEquals(1, cart.cartItems.value.size)
        assertEquals(5, cart.cartItems.value.first().quantity)
        assertEquals(12500.0, cart.subtotal, 0.001)
    }

    @Test
    fun `different products are separate lines`() {
        cart.addToCart(tomatoes, 1)
        cart.addToCart(onions, 1)

        assertEquals(2, cart.cartItems.value.size)
        assertEquals(4300.0, cart.subtotal, 0.001)
    }

    @Test
    fun `removing an item takes it out and updates the subtotal`() {
        cart.addToCart(tomatoes, 2)
        cart.addToCart(onions, 1)

        cart.removeFromCart(CartItem(tomatoes, 2))

        assertEquals(1, cart.cartItems.value.size)
        // Product.id is an Int now, so no delta is needed (and JUnit's
        // assertEquals(double, double) overload no longer applies).
        assertEquals(onions.id, cart.cartItems.value.first().product.id)
        assertEquals(1800.0, cart.subtotal, 0.001)
    }

    @Test
    fun `removing matches on product id, not on quantity`() {
        cart.addToCart(tomatoes, 5)

        // A stale CartItem with a different quantity should still remove the line.
        cart.removeFromCart(CartItem(tomatoes, 1))

        assertTrue(cart.cartItems.value.isEmpty())
    }

    @Test
    fun `updating quantity replaces rather than adds`() {
        cart.addToCart(tomatoes, 2)
        cart.updateQuantity(CartItem(tomatoes, 2), 7)

        assertEquals(1, cart.cartItems.value.size)
        assertEquals(7, cart.cartItems.value.first().quantity)
        assertEquals(17500.0, cart.subtotal, 0.001)
    }

    /**
     * Decrementing to zero is how the UI removes the last one, so it must
     * delete the line rather than leave a zero-quantity item that would be
     * sent to the backend as an order for nothing.
     */
    @Test
    fun `updating quantity to zero removes the item`() {
        cart.addToCart(tomatoes, 1)
        cart.updateQuantity(CartItem(tomatoes, 1), 0)

        assertTrue(cart.cartItems.value.isEmpty())
        assertEquals(0.0, cart.subtotal, 0.001)
    }

    @Test
    fun `negative quantity also removes the item`() {
        cart.addToCart(tomatoes, 1)
        cart.updateQuantity(CartItem(tomatoes, 1), -3)

        assertTrue(cart.cartItems.value.isEmpty())
    }

    @Test
    fun `clearing empties the cart`() {
        cart.addToCart(tomatoes, 2)
        cart.addToCart(onions, 3)

        cart.clearCart()

        assertTrue(cart.cartItems.value.isEmpty())
        assertEquals(0.0, cart.subtotal, 0.001)
    }

    @Test
    fun `subtotal multiplies price by quantity across lines`() {
        cart.addToCart(tomatoes, 3)   // 7500
        cart.addToCart(onions, 2)     // 3600
        assertEquals(11100.0, cart.subtotal, 0.001)
    }
}

