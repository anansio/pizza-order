package io.github.kaerbr.pizzaorder

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateTest {

    private val pizza = Pizza("Margherita", Sauce("Tomate"), Pizza.toppingsOf(emptyList()))
    private fun order(id: Long, printed: Boolean, completedAt: Long? = null) =
        Order(id = id, customer = "Anna", pizza = pizza, printed = printed, completedAt = completedAt)

    @Test
    fun unprintedCountsOpenOrdersTheKitchenNeverGot() {
        val state = AppState(
            orders = listOf(
                order(1, printed = false),                   // counts
                order(2, printed = true),
                order(3, printed = false, completedAt = 9),  // already handled
                order(4, printed = false),                   // still on its way to the printer
                order(5, printed = false),                   // failed, counts
            ),
            prints = mapOf(4L to PrintStatus.Printing, 5L to PrintStatus.Failed("Papier alle")),
        )
        assertEquals(2, state.unprinted)
    }

    @Test
    fun topPizzasAreSortedByCountAndLimited() {
        fun ordered(name: String) = order(0, printed = true).copy(pizza = pizza.copy(name = name))
        val orders = listOf("Salami", "Margherita", "Salami", "Verde", "Margherita", "Salami", "Speciale").map(::ordered)
        assertEquals(listOf("Salami" to 3, "Margherita" to 2, "Verde" to 1), orders.topPizzas())
        assertEquals(emptyList<Pair<String, Int>>(), emptyList<Order>().topPizzas())
    }

    @Test
    fun statusFollowsTheKitchenTimestamps() {
        val queued = order(1, printed = true)
        assertEquals(OrderStatus.QUEUED, queued.status)
        assertEquals(OrderStatus.BAKING, queued.copy(bakingAt = 5).status)
        // Done wins: an order that came out of the oven is ready, whatever the oven timestamp says.
        assertEquals(OrderStatus.READY, queued.copy(bakingAt = 5, completedAt = 9).status)
        assertEquals(OrderStatus.READY, queued.copy(completedAt = 9).status)
    }

    // orders.json from before `bakingAt`: those orders were open or done, never "im Ofen".
    @Test
    fun oldOrdersLoadAsQueued() {
        val json = """{"customer":"Anna","pizza":{"name":"Margherita","sauce":{"name":"Tomate"},"toppings":[]}}"""
        val loaded = Json.decodeFromString(Order.serializer(), json)
        assertNull(loaded.bakingAt)
        assertEquals(OrderStatus.QUEUED, loaded.status)
    }

    @Test
    fun ordersAheadCountsOpenPizzasPlacedEarlier() {
        val orders = listOf(
            order(10, printed = true),                             // open, ahead of everyone below
            order(20, printed = true, completedAt = 99),           // done, so not ahead of anyone
            order(30, printed = true),
            order(40, printed = true),                             // placed after 30
            order(50, printed = true).copy(bakingAt = 5),           // in the oven, still ahead of 60
            order(60, printed = true),
        )
        assertEquals(1, orders[2].ordersAheadIn(orders))
        assertEquals(0, orders.first().ordersAheadIn(orders))
        assertEquals(4, orders.last().ordersAheadIn(orders))

        // Finishing the one ahead counts down without anything being stored.
        val afterOven = orders.map { if (it.id == 10L) it.copy(completedAt = 100) else it }
        assertEquals(0, afterOven[2].ordersAheadIn(afterOven))
    }

    // orders.json from before the flag: without the default every old order would turn up as a failed print.
    @Test
    fun oldOrdersCountAsPrinted() {
        val json = """{"customer":"Anna","pizza":{"name":"Margherita","sauce":{"name":"Tomate"},"toppings":[]}}"""
        assertTrue(Json.decodeFromString(Order.serializer(), json).printed)
    }
}
