package io.github.kaerbr.pizzaorder

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun adjustingDoughCorrectsTheStockAndNeverGoesNegative() {
        val oneUsed = menu.withDoughToday(5).takeDough()!!
        assertEquals(DoughStock(available = 5, today = 5), oneUsed.adjustDough(1).dough)
        assertEquals(DoughStock(available = 3, today = 5), oneUsed.adjustDough(-1).dough)
        assertEquals(DoughStock(available = 6, today = 6), menu.withDoughToday(5).adjustDough(1).dough)
        assertEquals(DoughStock(available = 0, today = 1), menu.withDoughToday(1).adjustDough(-2).dough)
        assertNull(menu.adjustDough(1).dough)
    }

    @Test
    fun soldOutIngredientBlocksItsPizzasButStaysOnTheMenu() {
        val out = menu.withSoldOut("Rucola", true)
        fun pizza(name: String) = out.presets.first { it.name == name }
        assertEquals(listOf("Rucola"), out.soldOutOf(pizza("Speciale")))
        assertEquals(emptyList<String>(), out.soldOutOf(pizza("Margherita")))
        assertEquals(menu.presets, out.presets)
        assertEquals(emptySet<String>(), out.withSoldOut("Rucola", false).soldOut)
        // Gone from the menu, nothing is left to be out of: a later "Rucola" starts available.
        assertEquals(emptySet<String>(), out.withoutTopping(Topping("Rucola")).soldOut)
    }

    @Test
    fun lowAndSoldOutFlags() {
        assertEquals(listOf(false, true, true, false), listOf(4, 3, 1, 0).map { DoughStock(it).isLow })
        assertEquals(true, DoughStock(0).isSoldOut)
    }

    @Test
    fun maxOpenOrdersIsSetAndLifted() {
        assertEquals(6, menu.withMaxOpenOrders(6).maxOpenOrders)
        assertNull(menu.withMaxOpenOrders(null).maxOpenOrders)
        // Zero or negative would refuse every order; that is what the dough stock and "Heute aus" are for.
        assertNull(menu.withMaxOpenOrders(0).maxOpenOrders)
        assertNull(menu.withMaxOpenOrders(-2).maxOpenOrders)
        // Old menu.json files have no such key and must stay unlimited.
        assertNull(Json.decodeFromString(MenuConfig.serializer(), MenuFile.encode(menu)).maxOpenOrders)
    }

    // A hand-written or imported menu.json can carry 0; taking it literally would refuse every order.
    @Test
    fun aZeroLimitFromAFileMeansNoLimit() {
        val zero = MenuFile.decode("""{"sauces":[{"name":"Tomate"}],"maxOpenOrders":0}""")
        assertEquals(0, zero.maxOpenOrders)
        assertNull(zero.openOrderLimit)
        assertEquals(5, menu.withMaxOpenOrders(5).openOrderLimit)
    }

    // The guest page and an HTTP body can name anything; only what's on the menu tonight gets through.
    @Test
    fun unknownIngredientsAreNamedAndMenuOnesAreNot() {
        assertEquals(emptyList<String>(), menu.unknownIngredientsOf(menu.presets.first()))

        val invented = Pizza("Fantasie", Sauce("Ketchup"), Pizza.toppingsOf(listOf("Käse", "Ananas")))
        assertEquals(listOf("Ketchup", "Ananas"), menu.unknownIngredientsOf(invented))
        // A sauce used as a topping is unknown in that position, and the other way round.
        assertEquals(listOf("Tomate"), menu.unknownIngredientsOf(Pizza("X", Sauce("Tomate"), Pizza.toppingsOf(listOf("Tomate")))))
    }

    // Regression: the legend kept "Enthält Nüsse" after the last ingredient with nuts was deleted.
    @Test
    fun deletedIngredientsNoLongerCountAsAllergensInUse() {
        assertEquals(setOf(Allergen.NUTS), menu.allergensInUse)
        val withoutNuts = menu.withoutSauce(Sauce("Pesto al basilico")).withoutTopping(Topping("Pinienkerne"))
        assertEquals(emptySet<Allergen>(), withoutNuts.allergensInUse)
    }

    @Test
    fun editingAPresetKeepsItsPlaceAndANewOneGoesLast() {
        val napoli = Pizza("Napoli", Sauce("Tomate"), Pizza.toppingsOf(listOf("Oliven")))
        assertEquals(listOf("Speciale", "Napoli", "Salami", "Pizza Verde"), menu.withPreset(napoli, replacing = "Margherita").presets.map { it.name })
        assertEquals(listOf("Speciale", "Margherita", "Salami", "Pizza Verde", "Napoli"), menu.withPreset(napoli).presets.map { it.name })
    }

    // Regression: a second "Margherita" could be added; it was never pickable, and deleting took both.
    @Test
    fun aPresetNameExistsOnlyOnce() {
        val other = Pizza("Margherita", Sauce("Tomate"), Pizza.toppingsOf(listOf("Oliven")))
        val menuAfter = menu.withPreset(other)
        assertEquals(menu.presets.map { it.name }, menuAfter.presets.map { it.name })
        assertEquals(other, menuAfter.presets.first { it.name == "Margherita" })
    }

    @Test
    fun menuFileRoundTripsTheMenuButNotTonight() {
        val tonight = menu.withDoughToday(5).withSoldOut("Rucola", true).withMaxOpenOrders(4)
            .withAllergen("Käse", Allergen.SOY, true)
        val loaded = MenuFile.decode(MenuFile.encode(tonight))
        assertEquals(tonight.copy(dough = null, soldOut = emptySet()), loaded)
    }

    // Regression, found on the emulator: values equal to the defaults were left out of the file.
    @Test
    fun menuFileHoldsEverythingEvenWhenItEqualsTheDefaults() {
        val file = MenuFile.encode(menu)
        for (key in listOf("diets", "allergens", "funLinks")) assertTrue(key, "\"$key\"" in file)
    }

    // A wrong file must not wipe the menu: valid JSON alone would decode to an empty one.
    @Test
    fun menuFileRejectsWhatIsNoMenu() {
        for (text in listOf("{}", """{"name":"Einkaufsliste"}""", "keine Datei", """{"sauces":[],"presets":[]}""")) {
            assertThrows(text, IllegalArgumentException::class.java) { MenuFile.decode(text) }
        }
    }

    // menu.json from before the dough count existed must still load (JsonStore would otherwise fall back to the default menu).
    @Test
    fun oldMenuWithoutDoughHasNoLimit() {
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString(MenuConfig.serializer(), "{}").dough)
    }
}
