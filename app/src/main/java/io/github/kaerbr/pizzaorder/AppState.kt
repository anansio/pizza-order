package io.github.kaerbr.pizzaorder

data class AppState(
    val orders: List<Order> = emptyList(),
    val menu: MenuConfig = MenuConfig.Standard.menu,
    /** The order just placed, shown as the full-screen confirmation until dismissed. */
    val confirmation: Confirmation? = null,
    /** Print state per order id ([TEST_PRINT_ID] for the test print); shown where the print was started. */
    val prints: Map<Long, PrintStatus> = emptyMap(),
    val printStyle: PrintStyle = PrintStyle.Receipt,
    /** Outcome of the last menu export or import, shown next to its buttons. */
    val menuFileMessage: String? = null,
) {
    /** Open orders the kitchen has no paper for; the printout is its only signal. One still printing doesn't count yet. */
    val unprinted get() = orders.count { !it.isDone() && !it.printed && prints[it.id] != PrintStatus.Printing }
}

/** [printout] is the exact markup sent to the printer, so the on-screen slip matches the paper one. */
data class Confirmation(val order: Order, val printout: String)

sealed interface PrintStatus {
    data object Printing : PrintStatus
    data object Printed : PrintStatus
    data class Failed(val reason: String) : PrintStatus
}

const val TEST_PRINT_ID = -1L
