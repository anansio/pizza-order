package io.github.kaerbr.pizzaorder

import org.junit.Assert.assertEquals
import org.junit.Test

class PizzaTest {

    // Regression: 0 or 1 topping used to crash ordering (ClassCastException on the set type).
    @Test
    fun toppingsOfKeepsOrderForAnyCount() {
        for (names in listOf(emptyList(), listOf("Käse"), listOf("Salami", "Käse", "Rucola"))) {
            val set: LinkedHashSet<Topping> = Pizza.toppingsOf(names)
            assertEquals(LinkedHashSet::class.java, set.javaClass)
            assertEquals(names, set.map { it.name })
        }
    }

    @Test
    fun pizzaDietIsItsLeastStrictIngredient() {
        // Own tags, so the test doesn't depend on what Standard.diets currently says.
        val menu = MenuConfig.Standard.menu.copy(diets = mapOf("Tomate" to Diet.VEGAN, "Rucola" to Diet.VEGAN, "Käse" to Diet.VEGETARIAN))
        fun pizza(sauce: String, vararg toppings: String) = Pizza("x", Sauce(sauce), Pizza.toppingsOf(toppings.toList()))
        assertEquals(Diet.VEGAN, menu.dietOf(pizza("Tomate", "Rucola")))
        assertEquals(Diet.VEGETARIAN, menu.dietOf(pizza("Tomate", "Rucola", "Käse")))
        assertEquals(Diet.NONE, menu.dietOf(pizza("Tomate", "Käse", "Salami")))
        assertEquals(Diet.NONE, menu.dietOf(pizza("Tomate", "Unbekannt")))  // untagged never claims vegan
        assertEquals(Diet.VEGAN, menu.dietOf(pizza("")))                    // nothing picked yet
    }

    @Test
    fun pizzaHasEveryAllergenOfItsIngredients() {
        val menu = MenuConfig.Standard.menu.copy(allergens = emptyMap())
            .withAllergen("Käse", Allergen.LACTOSE, true)
            .withAllergen("Pinienkerne", Allergen.NUTS, true)
        fun pizza(sauce: String, vararg toppings: String) = Pizza("x", Sauce(sauce), Pizza.toppingsOf(toppings.toList()))
        assertEquals(emptySet<Allergen>(), menu.allergensOf(pizza("Tomate", "Rucola")))
        // Enum order, whatever order the toppings were picked in.
        assertEquals(listOf(Allergen.LACTOSE, Allergen.NUTS), menu.allergensOf(pizza("Tomate", "Pinienkerne", "Käse")).toList())
        // Switching the last one off drops the entry instead of keeping an empty set in menu.json.
        assertEquals(mapOf("Pinienkerne" to setOf(Allergen.NUTS)), menu.withAllergen("Käse", Allergen.LACTOSE, false).allergens)
    }
}
