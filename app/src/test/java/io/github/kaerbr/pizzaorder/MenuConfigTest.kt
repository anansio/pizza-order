package io.github.kaerbr.pizzaorder

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MenuConfigTest {

    // Runs before every debug build (app/build.gradle.kts): a preset with an unknown sauce or
    // topping fails the build here instead of crashing the app on start.
    @Test
    fun standardMenuIsValid() {
        MenuConfig.Standard.menu
    }

    // Regression: diets were keyed by enum constant ("KAESE") instead of the name on the menu ("Käse"),
    // so every lookup missed and all diet badges vanished.
    @Test
    fun standardDietsCoverExactlyTheStandardIngredients() {
        val menu = MenuConfig.Standard.menu
        assertEquals(menu.sauces.map { it.name }.toSet() + menu.toppings.map { it.name }, menu.diets.keys)
    }

    // Same trap as the diets: allergen keys must be names on the menu, or no mark ever shows.
    @Test
    fun standardAllergensAreKeyedByMenuNames() {
        val menu = MenuConfig.Standard.menu
        val names = menu.sauces.map { it.name }.toSet() + menu.toppings.map { it.name }
        assertEquals(setOf("Pesto al basilico", "Pinienkerne"), menu.allergens.keys)
        assertEquals(true, names.containsAll(menu.allergens.keys))
    }

    @Test
    fun invalidMenuListsEveryProblem() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            MenuConfig(
                sauces = linkedSetOf(Sauce("Tomate")),
                toppings = linkedSetOf(Topping("Käse")),
                presets = linkedSetOf(Pizza("error", Sauce("not vorhanden"), Pizza.toppingsOf(listOf("Käse", "nicht vorhanden")))),
            )
        }
        assertEquals(
            "Ungültiges Menü:\n" +
                "Pizza \"error\": Sauce \"not vorhanden\" fehlt in sauces\n" +
                "Pizza \"error\": Belag \"nicht vorhanden\" fehlt in toppings",
            e.message,
        )
    }

    private val menu = MenuConfig.Standard.menu

    @Test
    fun takeDoughCountsDownToSoldOut() {
        val oneLeft = menu.withDoughToday(2).takeDough()!!
        assertEquals(DoughStock(available = 1, today = 2), oneLeft.dough)
        assertNull(oneLeft.takeDough()!!.takeDough())
    }

    @Test
    fun noLimitNeverBlocks() {
        assertSame(menu, menu.takeDough())
    }

    @Test
    fun settingTodaysCountRefillsAndCanBeLifted() {
        val soldOut = menu.withDoughToday(1).takeDough()!!
        assertEquals(DoughStock(5), soldOut.withDoughToday(5).dough)
        assertEquals(DoughStock(0), menu.withDoughToday(-3).dough)
        assertNull(soldOut.withDoughToday(null).dough)
    }

    @Test
    fun lowAndSoldOutFlags() {
        assertEquals(listOf(false, true, true, false), listOf(4, 3, 1, 0).map { DoughStock(it).isLow })
        assertEquals(true, DoughStock(0).isSoldOut)
    }

    // menu.json from before the dough count existed must still load (JsonStore would otherwise fall back to the default menu).
    @Test
    fun oldMenuWithoutDoughHasNoLimit() {
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString(MenuConfig.serializer(), "{}").dough)
    }
}
