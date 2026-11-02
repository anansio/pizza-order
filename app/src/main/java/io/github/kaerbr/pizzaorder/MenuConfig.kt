package io.github.kaerbr.pizzaorder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The file the admin panel saves the menu to and loads it from. */
object MenuFile {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // Dough and what ran out belong to tonight, not to the menu.
    fun encode(menu: MenuConfig): String = json.encodeToString(MenuConfig.serializer(), menu.copy(dough = null, soldOut = emptySet()))

    /** @throws IllegalArgumentException on anything that isn't a usable menu. */
    fun decode(text: String): MenuConfig = json.decodeFromString(MenuConfig.serializer(), text).also {
        // Any JSON object decodes to an empty menu, thanks to the defaults; without a sauce nobody could order.
        require(it.sauces.isNotEmpty()) { "Menü ohne Sauce" }
    }
}

@Serializable
data class MenuConfig(
    val sauces: LinkedHashSet<Sauce> = linkedSetOf(),
    val toppings: LinkedHashSet<Topping> = linkedSetOf(),
    val presets: LinkedHashSet<Pizza> = linkedSetOf(),
    // Default here (not just in Standard.menu) so menu.json files saved before this field existed get the links too.
    val funLinks: List<FunLink> = Standard.funLinks,
    // Keyed by ingredient name instead of a field on Sauce/Topping: those get rebuilt from names
    // (OrderScreen) and compared by equality, a diet field would break every `in` check.
    // Unlisted = Diet.NONE, so nothing is ever labelled vegan by accident.
    val diets: Map<String, Diet> = Standard.diets,
    // Same keying as [diets]. Unlisted = no allergens known: guests with allergies still ask, the marks only help.
    val allergens: Map<String, Set<Allergen>> = Standard.allergens,
    // In menu.json, so every order's decrement is on disk right away. null = no limit (nothing counted or shown).
    val dough: DoughStock? = null,
    // Run out tonight, by name like [diets]. Not the same as deleting: that strips an ingredient from every preset for good.
    val soldOut: Set<String> = emptySet(),
) {
    // Lists every problem at once, so one failure shows all typos. MenuConfigTest runs this on Standard.menu
    // before each debug build; a broken menu.json never gets here as a crash (JsonStore falls back to Standard.menu).
    init {
        val problems = presets.flatMap { pizza ->
            listOfNotNull(pizza.sauce.takeIf { it !in sauces }?.let { "Pizza \"${pizza.name}\": Sauce \"${it.name}\" fehlt in sauces" }) +
                pizza.toppings.filter { it !in toppings }.map { "Pizza \"${pizza.name}\": Belag \"${it.name}\" fehlt in toppings" }
        }
        require(problems.isEmpty()) { "Ungültiges Menü:\n" + problems.joinToString("\n") }
    }

    // 1. Creates ONE new LinkedHashSet with the current items
    // 2. Adds the new item to it
    // 3. Passes the new set to the copy constructor
    fun withSauce(sauce: Sauce) = copy(
        sauces = LinkedHashSet(sauces).apply { add(sauce) }
    )

    fun withoutSauce(sauce: Sauce): MenuConfig {
        val nextSauces = LinkedHashSet(sauces).apply { remove(sauce) }
        val fallback = nextSauces.firstOrNull() ?: Standard.sauce

        return copy(
            sauces = nextSauces,
            presets = presets.mapTo(LinkedHashSet()) {
                if (it.sauce == sauce) it.copy(sauce = fallback) else it
            },
            soldOut = soldOut - sauce.name,
        )
    }

    fun withTopping(topping: Topping) = copy(
        toppings = LinkedHashSet(toppings).apply { add(topping) }
    )

    fun withoutTopping(topping: Topping) = copy(
        toppings = LinkedHashSet(toppings).apply { remove(topping) },
        presets = presets.mapTo(LinkedHashSet()) {
            it.copy(toppings = LinkedHashSet(it.toppings).apply { remove(topping) })
        },
        soldOut = soldOut - topping.name,
    )

    /** Puts [pizza] in the place of the preset called [replacing], or at the end if there's none. */
    fun withPreset(pizza: Pizza, replacing: String = pizza.name) = copy(
        presets = if (presets.any { it.name == replacing }) {
            presets.mapTo(LinkedHashSet()) { if (it.name == replacing) pizza else it }
        } else {
            LinkedHashSet(presets).apply { add(pizza) }
        }
    )

    fun withoutPreset(name: String) = copy(
        presets = presets.filterTo(LinkedHashSet()) { it.name != name }
    )

    fun withFunLink(link: FunLink) = copy(funLinks = funLinks + link)

    fun withoutFunLink(link: FunLink) = copy(funLinks = funLinks - link)

    fun withDiet(name: String, diet: Diet) = copy(diets = diets + (name to diet))

    fun dietOf(name: String) = diets[name] ?: Diet.NONE

    /** Switches [allergen] on or off for one ingredient. */
    fun withAllergen(name: String, allergen: Allergen, contained: Boolean): MenuConfig {
        val next = if (contained) allergensOf(name) + allergen else allergensOf(name) - allergen
        return copy(allergens = if (next.isEmpty()) allergens - name else allergens + (name to next))
    }

    fun allergensOf(name: String): Set<Allergen> = allergens[name].orEmpty()

    /** Every allergen of any ingredient, in enum order. */
    fun allergensOf(pizza: Pizza): Set<Allergen> =
        ingredients(pizza).let { names -> Allergen.entries.filterTo(LinkedHashSet()) { a -> names.any { a in allergensOf(it) } } }

    /** What's on the menu right now; [allergens] also keeps the tags of deleted ingredients, for when they come back. */
    val allergensInUse get() = (sauces.map { it.name } + toppings.map { it.name }).flatMapTo(HashSet()) { allergensOf(it) }

    /** Sets how many dough balls there are today and fills the stock to it; null lifts the limit. */
    fun withDoughToday(count: Int?) = copy(dough = count?.let { DoughStock(it.coerceAtLeast(0)) })

    /** One dough ball used up; null when sold out, so no order goes through without one. Unchanged without a limit. */
    fun takeDough(): MenuConfig? = when {
        dough == null -> this
        dough.isSoldOut -> null
        else -> copy(dough = dough.copy(available = dough.available - 1))
    }

    /** Corrects the stock: a cancelled order, a dropped dough ball, one more found. Today's count grows along. Unchanged without a limit. */
    fun adjustDough(delta: Int) = copy(dough = dough?.let { d ->
        val next = (d.available + delta).coerceAtLeast(0)
        d.copy(available = next, today = maxOf(d.today, next))
    })

    fun withSoldOut(name: String, out: Boolean) = copy(soldOut = if (out) soldOut + name else soldOut - name)

    /** The ingredients of [pizza] that have run out; a pizza with any of them can't be ordered tonight. */
    fun soldOutOf(pizza: Pizza) = ingredients(pizza).filter { it in soldOut }

    /** As strict as its least strict ingredient; a bare crust counts as vegan. */
    fun dietOf(pizza: Pizza) = ingredients(pizza).maxOfOrNull { dietOf(it) } ?: Diet.VEGAN

    // Blank sauce = Eigenkreation before a sauce is picked.
    private fun ingredients(pizza: Pizza) = (listOf(pizza.sauce.name) + pizza.toppings.map { it.name }).filter(String::isNotBlank)

    /** The built-in menu: what a fresh install and "Standardmenü wiederherstellen" start with. */
    object Standard {
        // Declared before [menu]: building it reads these through the constructor defaults.
        // Keys via .sauce/.topping, the exact objects on the menu: an enum's own `name` is "KAESE", not "Käse".
        val diets = Sauce.entries.associate { it.sauce.name to it.diet } + Topping.entries.associate { it.topping.name to it.diet }
        val allergens = (Sauce.entries.associate { it.sauce.name to it.allergens } + Topping.entries.associate { it.topping.name to it.allergens })
            .filterValues { it.isNotEmpty() }

        val funLinks = listOf(
            FunLink("Mathe beweist: fair teilen!",       "https://de.wikipedia.org/wiki/Pizza-Theorem"),
            FunLink("Der kleine Tisch im Karton",        "https://en.wikipedia.org/wiki/Pizza_saver"),
            FunLink("Ananas: Krieg der Welten",          "https://en.wikipedia.org/wiki/Hawaiian_pizza"),
            FunLink("Warum Pizza erst in den USA berühmt wurde", "https://en.wikipedia.org/wiki/Pizza_effect"),
            FunLink("Pizza",                             "https://de.wikipedia.org/wiki/Pizza"),
            FunLink("PizzaGate",                         "https://de.wikipedia.org/wiki/Pizzagate"),
            FunLink("Pizza Karton",                      "https://de.wikipedia.org/wiki/Pizzakarton"),
            FunLink("Neapolitanische Pizza",             "https://de.wikipedia.org/wiki/Neapolitanische_Pizza"),
            // Short youtu.be URLs keep the QR code coarse for thermal printers.
            FunLink("Die beste NAPOLI PIZZA die DU zuhause backen kannst!", "https://youtu.be/mVJkdqTeSQ4"),
            FunLink("SO machst DU eine perfekte Napoli Pizza mit dem 0,59€ Mehl!", "https://youtu.be/1QR5NQj52Po"),
            FunLink("Wie man Pizza macht | SWR Handwerkskunst", "https://youtu.be/vf0god0LtP8"),
            FunLink("A day running my pizza truck at St Albans market", "https://youtu.be/PQJ6xljo0-U"),
            FunLink("The Peddling Pizza Neapolitan dough recipe.", "https://youtu.be/ObYx4ofdVCc"),
            FunLink("3 Fehler beim Pizza backen, die jeder macht!", "https://youtu.be/wGY3oaerRgI"),
            FunLink("Gelingsicher! 24 Stunden Pizzateig ganz einfach für zu Hause.\uD83C\uDF55", "https://youtu.be/JQ3nO5t0GNI"),
            FunLink("Das geheime Teigrezept",            "https://youtu.be/dQw4w9WgXcQ"),
        )

        /** Fallback when a preset's sauce gets deleted. */
        val sauce = Sauce.TOMATE.sauce

        val menu = MenuConfig(
            // Every entry is on the menu, so a preset can't use one that's missing here.
            sauces = Sauce.entries.mapTo(LinkedHashSet()) { it.sauce },
            toppings = Topping.entries.mapTo(LinkedHashSet()) { it.topping },
            presets = linkedSetOf(
                preset("Speciale",    Sauce.TOMATE, Topping.ZWIEBELN, Topping.KNOBLAUCH, Topping.RUCOLA),
                preset("Margherita",  Sauce.TOMATE, Topping.KAESE),
                preset("Salami",      Sauce.TOMATE, Topping.SALAMI, Topping.KAESE),
                preset("Pizza Verde", Sauce.PESTO, Topping.KIRSCHTOMATEN, Topping.PINIENKERNE),
            ),
        )

        private fun preset(name: String, sauce: Sauce, vararg toppings: Topping) =
            Pizza(name, sauce.sauce, toppings.mapTo(LinkedHashSet()) { it.topping })

        // Typed on purpose: presets take these, not strings, so an ingredient that isn't declared
        // here is a compile error. Only the built-ins; Settings still adds plain Sauce/Topping by name.
        // Everything vegan for now (vegan cheese, pesto and salami); retag in Settings when that changes.
        // Allergens as best known for the party's ingredients (vegan cheese = no lactose); retag in Settings.
        private enum class Sauce(val label: String, val diet: Diet, vararg allergens: Allergen) {
            TOMATE("Tomate", Diet.VEGAN),
            PESTO("Pesto al basilico", Diet.VEGAN, Allergen.NUTS);

            val allergens = allergens.toSet()

            val sauce get() = Sauce(label)
        }

        private enum class Topping(val label: String, val diet: Diet, vararg allergens: Allergen) {
            ZWIEBELN("Zwiebeln", Diet.VEGAN),
            KNOBLAUCH("Knoblauch", Diet.VEGAN),
            SALAMI("Salami", Diet.VEGAN),
            OLIVEN("Oliven", Diet.VEGAN),
            KAESE("Käse", Diet.VEGAN),
            RUCOLA("Rucola", Diet.VEGAN),
            BASILIKUM("Basilikum", Diet.VEGAN),
            KIRSCHTOMATEN("Kirschtomaten", Diet.VEGAN),
            PINIENKERNE("Pinienkerne", Diet.VEGAN, Allergen.NUTS);

            val allergens = allergens.toSet()
            val topping get() = Topping(label)
        }
    }
}
