package io.github.kaerbr.pizzaorder

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class Sauce(val name: String)

@Serializable
data class Topping(val name: String)

/** Ordered by strictness: a pizza gets the highest ordinal among its ingredients. */
@Serializable
enum class Diet(val label: String) { VEGAN("Vegan"), VEGETARIAN("Vegetarisch"), NONE("Nichts davon") }

/** Allergens an ingredient can contain; [short] is the letter on chips and cards, explained in the legend. */
@Serializable
enum class Allergen(val label: String, val short: String) { LACTOSE("Laktose", "L"), NUTS("Nüsse", "N"), GLUTEN("Gluten", "G"), SOY("Soja", "S") }

/** Today's prepared dough balls, one per pizza; [today] is the count set in Admin, shown as "Noch X von Y". */
@Serializable
data class DoughStock(val available: Int, val today: Int = available) {
    val isSoldOut get() = available <= 0
    val isLow get() = available in 1..LOW

    companion object {
        /** At or below this many left, the count turns red. */
        const val LOW = 3
    }
}

/** QR code on the printout: [url] is what it opens; [teaser] is only a label in Settings, not printed. */
@Serializable
data class FunLink(val teaser: String, val url: String)

@Serializable
data class Pizza(
    val name: String,
    val sauce: Sauce,
    val toppings: LinkedHashSet<Topping>,
) {
    val description: String get() = "${sauce.name} · ${toppings.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: "—"}"

    companion object {
        // Always a real LinkedHashSet: toSet() hands back emptySet()/singleton for 0–1 items,
        // which made the old `as LinkedHashSet` cast crash on a Margherita.
        fun toppingsOf(names: List<String>): LinkedHashSet<Topping> = names.mapTo(LinkedHashSet()) { Topping(it) }
    }
}

/** How far the kitchen has come with an order; derived from [Order.bakingAt] and [Order.completedAt], never stored. */
enum class OrderStatus(val label: String) { QUEUED("Wartet"), BAKING("Im Ofen"), READY("Fertig") }

/** The most ordered pizzas with their counts, most popular first; Eigenkreationen count as one. */
fun List<Order>.topPizzas(limit: Int = 3) =
    groupingBy { it.pizza.name }.eachCount().toList().sortedByDescending { it.second }.take(limit)

/** Name of the pizza the guest built by hand, when no preset matches. */
const val CUSTOM_NAME = "Eigenkreation"

@Serializable
data class Order(
    val id: Long = System.currentTimeMillis(),
    val number: Int = 0,
    val customer: String,
    val pizza: Pizza,
    val note: String = "",
    // One timestamp per step instead of a status field: the status can't disagree with the kitchen's taps,
    // and orders.json from before this field loads as QUEUED, which is what those orders already were.
    val bakingAt: Long? = null,
    val completedAt: Long? = null,
    // Snapshot at order time, so later menu edits don't relabel what the kitchen already got.
    val diet: Diet = Diet.NONE,
    // Same snapshot idea; sorted by enum order so it always reads "Laktose, Nüsse".
    val allergens: Set<Allergen> = emptySet(),
    // False until the printer took it, on disk so a lost printout survives an app restart. The default is
    // for orders.json files from before this field: those orders are long past.
    val printed: Boolean = true,
) {
    val status: OrderStatus get() = when {
        completedAt != null -> OrderStatus.READY
        bakingAt != null -> OrderStatus.BAKING
        else -> OrderStatus.QUEUED
    }

    fun isDone() = completedAt != null
    val formattedTime: String get() = SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date(id))
    val formattedNumber: String get() = "#%03d".format(number)
}

/**
 * How many open orders were placed before this one: the queue position minus one, so the guest sees "noch 2 vor dir".
 *
 * Computed, never stored: a counter would be wrong as soon as the kitchen finishes something out of order.
 */
fun Order.ordersAheadIn(orders: List<Order>): Int = orders.count { !it.isDone() && it.id < id }
