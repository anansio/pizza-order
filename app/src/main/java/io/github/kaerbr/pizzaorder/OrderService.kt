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
     * @return the order, or null when refused: no name, or no dough left.
     */
    suspend fun submit(customer: String, pizza: Pizza, note: String): Order? {
        val placed = mutex.withLock {
            val current = _state.value
            val cleanCustomer = customer.trim()
            if (cleanCustomer.isEmpty()) return@withLock null
            val menuAfter = current.menu.takeDough() ?: return@withLock null

            val order = Order(
                id = nextId(current.orders),
                number = nextNumber(current.orders),
                customer = cleanCustomer,
                pizza = pizza,
                note = note.trim(),
                diet = current.menu.dietOf(pizza),
                allergens = current.menu.allergensOf(pizza),
                printed = false,
            )
            val markup = current.printStyle.format(order, current.menu.funLinks.randomOrNull())
            val orders = listOf(order) + current.orders

            _state.value = current.copy(orders = orders, menu = menuAfter, confirmation = Confirmation(order, markup))
            orderStore.save(orders)
            menuStore.save(menuAfter)
            order to markup
        } ?: return null

        runPrint(placed.second, placed.first.id)
        return placed.first
    }

    fun dismissConfirmation() = _state.update { it.copy(confirmation = null) }

    /** One tap on an order in the list: open ↔ fertig. */
    suspend fun advance(order: Order) = mutex.withLock {
        val current = _state.value
        val next = current.orders.map {
            if (it.id == order.id) it.copy(completedAt = if (it.isDone()) null else System.currentTimeMillis())
            else it
        }
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
