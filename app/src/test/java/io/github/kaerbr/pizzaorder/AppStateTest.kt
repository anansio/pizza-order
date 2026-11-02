package io.github.kaerbr.pizzaorder

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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

    // orders.json from before the flag: without the default every old order would turn up as a failed print.
    @Test
    fun oldOrdersCountAsPrinted() {
        val json = """{"customer":"Anna","pizza":{"name":"Margherita","sauce":{"name":"Tomate"},"toppings":[]}}"""
        assertTrue(Json.decodeFromString(Order.serializer(), json).printed)
    }
}
