package io.github.kaerbr.pizzaorder

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

data class AppState(
    val orders: List<Order> = emptyList(),
    val menu: MenuConfig = MenuConfig.Standard.menu,
    val isLoaded: Boolean = false,
    /** The order just placed, shown as the full-screen confirmation until dismissed. */
    val confirmation: Confirmation? = null,
    /** Print state per order id ([TEST_PRINT_ID] for the test print); shown where the print was started. */
    val prints: Map<Long, PrintStatus> = emptyMap(),
    val printStyle: PrintStyle = PrintStyle.Receipt,
) {
    // ponytail: restarts at #001 after "Alle löschen"; persist a counter if numbers must stay unique all night
    val nextNumber get() = (orders.maxOfOrNull { it.number } ?: 0) + 1
}

/** [printout] is the exact markup sent to the printer, so the on-screen slip matches the paper one. */
data class Confirmation(val order: Order, val printout: String)

sealed interface PrintStatus {
    data object Printing : PrintStatus
    data object Printed : PrintStatus
    data class Failed(val reason: String) : PrintStatus
}

const val TEST_PRINT_ID = -1L

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val orderRepo = JsonStore(application, "orders.json", ListSerializer(Order.serializer()), emptyList())
    private val menuRepo  = JsonStore(application, "menu.json", MenuConfig.serializer(), MenuConfig.Standard.menu, pretty = true)
    private val printer   = Printer(application)

    private val prefs     = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AppState(
        printStyle = PrintStyle.entries.find { it.name == prefs.getString("printStyle", null) } ?: PrintStyle.Receipt,
    ))
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val orders = orderRepo.load()
            val menu = menuRepo.load()
            _state.update { it.copy(orders = orders, menu = menu, isLoaded = true) }
        }
    }

    private fun updateMenu(transform: (MenuConfig) -> MenuConfig) {
        val next = transform(_state.value.menu)
        if (next != _state.value.menu) {
            _state.update { it.copy(menu = next) }
            viewModelScope.launch { menuRepo.save(next) }
        }
    }

    private fun setPrint(id: Long, status: PrintStatus?) =
        _state.update { it.copy(prints = if (status == null) it.prints - id else it.prints + (id to status)) }

    // Fresh random QR link on every print, reprint and test print.
    private fun printout(order: Order) = _state.value.printStyle.format(order, _state.value.menu.funLinks.randomOrNull())

    private fun runPrint(markup: String, id: Long) = viewModelScope.launch {
        setPrint(id, PrintStatus.Printing)
        printer.print(markup)
            .onSuccess {
                setPrint(id, PrintStatus.Printed)
                // "Gedruckt" is a brief tick on the button, not a lasting state; failures stay until retried.
                delay(3_000)
                if (_state.value.prints[id] == PrintStatus.Printed) setPrint(id, null)
            }
            .onFailure { e -> setPrint(id, PrintStatus.Failed(e.message ?: "Unbekannter Fehler")) }
    }

    // ─── Admin lock ─────────────────────────────────────────────────────────

    // Lives in the ViewModel so the wrong-try counter survives rotation.
    val adminLock = AdminLock()

    // ─── Ausdruck ───────────────────────────────────────────────────────────

    fun setPrintStyle(style: PrintStyle) {
        _state.update { it.copy(printStyle = style) }
        prefs.edit { putString("printStyle", style.name) }
    }

    fun testPrint() = runPrint(
        printout((_state.value.menu.presets.firstOrNull() ?: Pizza("Margherita", MenuConfig.Standard.sauce, linkedSetOf())).let { pizza ->
            Order(number = 42, customer = "Testgast", pizza = pizza, note = "Nur ein Testdruck", diet = _state.value.menu.dietOf(pizza))
        }),
        TEST_PRINT_ID,
    )

    // ─── Orders ─────────────────────────────────────────────────────────────

    fun submitOrder(customer: String, pizza: Pizza, note: String) {
        val cleanCustomer = customer.trim()
        // The order button only enables with a name and dough left; this guards a stale screen, it has nothing to say.
        if (cleanCustomer.isEmpty()) return
        val menuAfter = _state.value.menu.takeDough() ?: return
        updateMenu { menuAfter }

        val number = _state.value.nextNumber
        val order = Order(number = number, customer = cleanCustomer, pizza = pizza, note = note.trim(), diet = _state.value.menu.dietOf(pizza))

        val markup = printout(order)
        _state.update { it.copy(
            orders = listOf(order) + it.orders,
            confirmation = Confirmation(order, markup),
        ) }

        viewModelScope.launch { orderRepo.save(_state.value.orders) }
        runPrint(markup, order.id)
    }

    fun dismissConfirmation() = _state.update { it.copy(confirmation = null) }

    fun reprint(order: Order) = runPrint(printout(order), order.id)

    fun toggleDone(order: Order) {
        _state.update { s ->
            s.copy(orders = s.orders.map {
                if (it.id == order.id) it.copy(completedAt = if (it.isDone()) null else System.currentTimeMillis())
                else it
            })
        }
        viewModelScope.launch { orderRepo.save(_state.value.orders) }
    }

    fun clearAll() {
        _state.update { it.copy(orders = emptyList(), prints = emptyMap()) }
        viewModelScope.launch { orderRepo.save(emptyList()) }
    }

    // ─── Menu ───────────────────────────────────────────────────────────────

    fun addSauce(name: String) = name.trim().takeIf { it.isNotEmpty() }?.let { clean ->
        updateMenu { it.withSauce(Sauce(clean)) }
    }

    fun removeSauce(sauce: Sauce) = updateMenu { m ->
        // At least one sauce must stay; Settings hides the remove button on the last one.
        if (m.sauces.size <= 1) m else m.withoutSauce(sauce)
    }

    fun addTopping(name: String) = name.trim().takeIf { it.isNotEmpty() }?.let { clean ->
        updateMenu { it.withTopping(Topping(clean)) }
    }

    fun removeTopping(topping: Topping) = updateMenu { it.withoutTopping(topping) }

    fun setDiet(name: String, diet: Diet) = updateMenu { it.withDiet(name, diet) }

    fun addPreset(pizza: Pizza) {
        val clean = pizza.name.trim()
        if (clean.isEmpty()) return
        updateMenu { it.withPreset(pizza.copy(name = clean)) }
    }

    fun removePreset(name: String) = updateMenu { it.withoutPreset(name) }

    fun addFunLink(teaser: String, url: String) {
        val cleanUrl = url.trim()
        if (!isWebLink(cleanUrl)) return
        updateMenu { it.withFunLink(FunLink(teaser.trim().ifEmpty { "Überraschung!" }, cleanUrl)) }
    }

    fun removeFunLink(link: FunLink) = updateMenu { it.withoutFunLink(link) }

    fun setDoughToday(count: Int?) = updateMenu { it.withDoughToday(count) }

    fun resetMenu() {
        // The dough count isn't part of the menu: keep it, mid-party it's still what's in the fridge.
        val newMenu = MenuConfig.Standard.menu.copy(dough = _state.value.menu.dough)
        _state.update { it.copy(menu = newMenu) }
        viewModelScope.launch { menuRepo.save(newMenu) }
    }
}

/** Settings only enables "add" for these, so the check lives in one place. */
fun isWebLink(url: String) = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
