package io.github.kaerbr.pizzaorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One JSON file, as the service needs it: [JsonStore] in the app, a plain temp file in the tests. */
interface JsonFile<T> {
    suspend fun load(): T
    suspend fun save(data: T)
}

typealias OrderStore = JsonFile<List<Order>>
typealias MenuStore = JsonFile<MenuConfig>

/** The thermal printer without Android, so the order sequence runs in plain JVM tests. */
interface PrinterPort {
    suspend fun print(markup: String): Result<Unit>
}

/** What a successful submit produced: the order, its printout, and the queue position at that moment. */
data class Placed(val order: Order, val printout: String, val ordersAhead: Int) : SubmitResult

/** The other outcome: nothing was taken, and the guest gets a reason instead of a silent no. */
data class Refused(val reason: Rejection) : SubmitResult

sealed interface SubmitResult

/**
 * Why an order was refused. The [message] is what a guest reads, so it names the ingredient or the limit
 * instead of repeating the rule.
 */
sealed interface Rejection {
    val message: String

    data object BlankName : Rejection {
        override val message get() = "Bitte einen Namen eingeben."
    }

    data object NoSauce : Rejection {
        override val message get() = "Bitte eine Sauce wählen."
    }

    data object NoDough : Rejection {
        override val message get() = "Der Teig ist alle, für heute gibt es keine Pizza mehr."
    }

    data class SoldOut(val ingredients: List<String>) : Rejection {
        override val message get() = "Heute aus: ${ingredients.joinToString()}. Bitte anders belegen."
    }

    data class UnknownIngredient(val names: List<String>) : Rejection {
        override val message get() = "Nicht auf dem Menü: ${names.joinToString()}."
    }

    data class TooManyOpen(val limit: Int) : Rejection {
        override val message get() = "Gerade sind schon $limit Bestellungen offen. Bitte in ein paar Minuten nochmal."
    }
}

/** Guest fields are cut here rather than refused, the same as the form does; a long name still goes through. */
// NAME_MAX fits next to "Gast" on the 32-character printout line.
const val NAME_MAX = 24
const val NOTE_MAX = 160

/**
 * The order desk: order numbers, dough, print bookkeeping and the state every screen renders.
 *
 * No Context on purpose, so the whole sequence runs in plain JVM tests; the two files and the printer
 * come in as interfaces.
 *
 * Every write runs under [mutex] and touches state and disk together: number, dough decrement and save
 * are one step, so two requests arriving at the same moment can neither share a number nor take one
 * dough ball too few. Saves stay inside the lock for the same reason — an older list must never land on
 * disk after a newer one.
 */
class OrderService(
    private val orderStore: OrderStore,
    private val menuStore: MenuStore,
    private val printer: PrinterPort,
    /** Printing outlives the caller: Bluetooth takes seconds and no screen should wait for it. */
    private val scope: CoroutineScope,
    printStyle: PrintStyle = PrintStyle.Receipt,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AppState(printStyle = printStyle))
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Reads both files; on a fresh install they don't exist and the stores hand back their defaults. */
    suspend fun load() = mutex.withLock {
        _state.value = _state.value.copy(orders = orderStore.load(), menu = menuStore.load())
    }

    // ─── Menu ───────────────────────────────────────────────────────────────

    /** Menu edits share the mutex with order-taking, so nobody can submit against a menu that is being replaced. */
    suspend fun updateMenu(transform: (MenuConfig) -> MenuConfig) = mutex.withLock {
        val current = _state.value
        val next = transform(current.menu)
        if (next == current.menu) return@withLock
        _state.value = current.copy(menu = next)
        menuStore.save(next)
    }

    fun setMenuFileMessage(message: String?) = _state.update { it.copy(menuFileMessage = message) }

    fun setPrintStyle(style: PrintStyle) = _state.update { it.copy(printStyle = style) }

    // ─── Ausdruck ───────────────────────────────────────────────────────────

    fun testPrint() {
        val state = _state.value
        val pizza = state.menu.presets.firstOrNull()
            ?: Pizza("Margherita", MenuConfig.Standard.sauce, linkedSetOf())
        val order = Order(
            number = 42, customer = "Testgast", pizza = pizza, note = "Nur ein Testdruck",
            diet = state.menu.dietOf(pizza), allergens = state.menu.allergensOf(pizza),
        )
        runPrint(state.printStyle.format(order, state.menu.funLinks.randomOrNull()), TEST_PRINT_ID)
    }

    fun reprint(order: Order) {
        val state = _state.value
        runPrint(state.printStyle.format(order, state.menu.funLinks.randomOrNull()), order.id)
    }

    private fun setPrint(id: Long, status: PrintStatus?) = _state.update {
        it.copy(prints = if (status == null) it.prints - id else it.prints + (id to status))
    }

    private fun runPrint(markup: String, id: Long) = scope.launch {
        setPrint(id, PrintStatus.Printing)
        printer.print(markup)
            .onSuccess {
                markPrinted(id)
                setPrint(id, PrintStatus.Printed)
                // "Gedruckt" is a brief tick on the button, not a lasting state; failures stay until retried.
                delay(3_000)
                if (_state.value.prints[id] == PrintStatus.Printed) setPrint(id, null)
            }
            .onFailure { e -> setPrint(id, PrintStatus.Failed(e.message ?: "Unbekannter Fehler")) }
    }

    private suspend fun markPrinted(id: Long) = mutex.withLock {
        val current = _state.value
        // Reprints of printed orders and the test print have nothing to record.
        if (current.orders.none { it.id == id && !it.printed }) return@withLock
        val next = current.orders.map { if (it.id == id) it.copy(printed = true) else it }
        _state.value = current.copy(orders = next)
        orderStore.save(next)
    }

    // ─── Orders ─────────────────────────────────────────────────────────────

    /**
     * Takes an order, its number and one dough ball in one step.
     *
     * This is the trust boundary: the Compose form and an HTTP thread both end up here, so every
     * rule that keeps an unusable order off the kitchen screen is checked inside the lock. The menu checks
     * and the write happen under the same mutex as the number and the dough, so a request can't be accepted
     * against a menu that was replaced a moment ago.
     */
    suspend fun submit(customer: String, pizza: Pizza, note: String): SubmitResult {
        val result = mutex.withLock {
            val current = _state.value
            val cleanCustomer = customer.trim().take(NAME_MAX)
            if (cleanCustomer.isEmpty()) return@withLock Refused(Rejection.BlankName)
            // An empty sauce passes unknownIngredientsOf() because a blank name is skipped, and soldOutOf()
            // filters it the same way: without this a hand-written POST prints a slip for a pizza with no sauce.
            if (pizza.sauce.name.isBlank()) return@withLock Refused(Rejection.NoSauce)

            // Both belong to the current menu: a built-by-hand request may invent ingredients or use one
            // that ran out tonight.
            val unknown = current.menu.unknownIngredientsOf(pizza)
            if (unknown.isNotEmpty()) return@withLock Refused(Rejection.UnknownIngredient(unknown))
            val soldOut = current.menu.soldOutOf(pizza)
            if (soldOut.isNotEmpty()) return@withLock Refused(Rejection.SoldOut(soldOut))

            // takeDough() copies, it doesn't spend anything: the check can come before the limit and both
            // refusals stay in one place. Dough gone ends the evening, so it outranks the temporary limit.
            val menuAfter = current.menu.takeDough() ?: return@withLock Refused(Rejection.NoDough)
            val limit = current.menu.openOrderLimit
            if (limit != null && current.orders.count { !it.isDone() } >= limit) {
                return@withLock Refused(Rejection.TooManyOpen(limit))
            }

            val order = Order(
                id = nextId(current.orders),
                number = nextNumber(current.orders),
                customer = cleanCustomer,
                pizza = pizza,
                note = note.trim().take(NOTE_MAX),
                diet = current.menu.dietOf(pizza),
                allergens = current.menu.allergensOf(pizza),
                printed = false,
            )
            val markup = current.printStyle.format(order, current.menu.funLinks.randomOrNull())
            val orders = listOf(order) + current.orders

            _state.value = current.copy(orders = orders, menu = menuAfter)
            orderStore.save(orders)
            // Without a dough limit takeDough() hands back the very same menu: no write for an unchanged file.
            if (menuAfter != current.menu) menuStore.save(menuAfter)
            Placed(order, markup, order.ordersAheadIn(orders))
        }

        val placed = result as? Placed ?: return result
        runPrint(placed.printout, placed.order.id)
        return placed
    }

    /**
     * One tap on an order in the list: Wartet → Im Ofen → Fertig, and one step back.
     *
     * The list has one control, so the kitchen must be able to take a stray tap back: on a finished order the
     * tap undoes the last step instead of advancing. It goes back to "Im Ofen", not to "Wartet", so the guest
     * never sees the order fall out of the queue and the baking time is not lost.
     */
    suspend fun advance(order: Order) = mutex.withLock {
        val current = _state.value
        val now = System.currentTimeMillis()
        val next = current.orders.map { if (it.id != order.id) it else when (it.status) {
            OrderStatus.QUEUED -> it.copy(bakingAt = now)
            OrderStatus.BAKING -> it.copy(completedAt = now)
            OrderStatus.READY -> it.copy(completedAt = null)
        } }
        _state.value = current.copy(orders = next)
        orderStore.save(next)
    }

    suspend fun clearAll() = mutex.withLock {
        // Clearing starts a new evening, so the dough count and what ran out start over too.
        val current = _state.value
        val menuAfter = current.menu.withDoughToday(current.menu.dough?.today).copy(soldOut = emptySet())
        _state.value = current.copy(orders = emptyList(), menu = menuAfter, prints = emptyMap())
        orderStore.save(emptyList())
        if (menuAfter != current.menu) menuStore.save(menuAfter)
    }

    /** Back to a fresh install: no orders, the standard menu without a dough count, the default print style. */
    suspend fun resetAll() = mutex.withLock {
        _state.value = _state.value.copy(
            orders = emptyList(),
            prints = emptyMap(),
            menu = MenuConfig.Standard.menu,
            printStyle = PrintStyle.Receipt,
        )
        orderStore.save(emptyList())
        menuStore.save(MenuConfig.Standard.menu)
    }

    // ponytail: restarts at #001 after "Alle löschen"; persist a counter if numbers must stay unique all night
    private fun nextNumber(orders: List<Order>) = (orders.maxOfOrNull { it.number } ?: 0) + 1

    // Two orders placed in the same millisecond would share an id and collide in `prints`; ids are handed out
    // here, together with the number, so that can't happen.
    private fun nextId(orders: List<Order>) =
        maxOf(System.currentTimeMillis(), (orders.maxOfOrNull { it.id } ?: 0) + 1)
}
