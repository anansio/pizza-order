package io.github.kaerbr.pizzaorder

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * The Android half of the order desk: files, printer, preferences and the admin lock.
 * Everything that changes orders lives in [OrderService]; this delegates and keeps the Compose flow.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val orderStore = JsonStore(application, "orders.json", ListSerializer(Order.serializer()), emptyList())
    private val menuStore = JsonStore(application, "menu.json", MenuConfig.serializer(), MenuConfig.Standard.menu, pretty = true)

    private val printer = Printer(application)

    private val service = OrderService(
        orderStore,
        menuStore,
        printer,
        viewModelScope,
        printStyle = PrintStyle.entries.find { it.name == prefs.getString("printStyle", null) } ?: PrintStyle.Receipt,
    )

    val state: StateFlow<AppState> = service.state

    // The confirmation belongs to the caller, not to the shared state: with guest orders arriving over HTTP,
    // a single slot would let one guest's slip replace another's.
    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation: StateFlow<Confirmation?> = _confirmation.asStateFlow()

    init {
        viewModelScope.launch { service.load() }
    }

    // ─── Admin lock ─────────────────────────────────────────────────────────

    // Lives in the ViewModel so the wrong-try counter survives rotation.
    val adminLock = AdminLock()

    // ─── Ausdruck ───────────────────────────────────────────────────────────

    fun setPrintStyle(style: PrintStyle) {
        service.setPrintStyle(style)
        prefs.edit { putString("printStyle", style.name) }
    }

    fun testPrint() = service.testPrint()

    // ─── Orders ─────────────────────────────────────────────────────────────

    /** @param onPlaced runs only when the order was taken, so a refusal does not wipe what the guest typed. */
    fun submitOrder(customer: String, pizza: Pizza, note: String, onPlaced: () -> Unit) {
        viewModelScope.launch {
            service.submit(customer, pizza, note)?.let {
                _confirmation.value = Confirmation(it.order, it.printout)
                onPlaced()
            }
        }
    }

    fun dismissConfirmation() {
        _confirmation.value = null
    }

    fun reprint(order: Order) = service.reprint(order)

    /** One tap on the list: the next step (Wartet → Im Ofen → Fertig), and one step back. */
    fun advanceOrder(order: Order) {
        viewModelScope.launch { service.advance(order) }
    }

    fun clearAll() {
        viewModelScope.launch { service.clearAll() }
    }

    // ─── Menu ───────────────────────────────────────────────────────────────

    private fun updateMenu(transform: (MenuConfig) -> MenuConfig) {
        viewModelScope.launch { service.updateMenu(transform) }
    }

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

    fun setSoldOut(name: String, out: Boolean) = updateMenu { it.withSoldOut(name, out) }

    fun setDiet(name: String, diet: Diet) = updateMenu { it.withDiet(name, diet) }

    fun setAllergen(name: String, allergen: Allergen, contained: Boolean) = updateMenu { it.withAllergen(name, allergen, contained) }

    /** @param replacing the name of the preset being edited; null adds a new one. */
    fun savePreset(pizza: Pizza, replacing: String?) {
        val clean = pizza.name.trim()
        if (clean.isEmpty()) return
        updateMenu { it.withPreset(pizza.copy(name = clean), replacing ?: clean) }
    }

    fun removePreset(name: String) = updateMenu { it.withoutPreset(name) }

    fun addFunLink(teaser: String, url: String) {
        val cleanUrl = url.trim()
        if (!isWebLink(cleanUrl)) return
        updateMenu { it.withFunLink(FunLink(teaser.trim().ifEmpty { "Überraschung!" }, cleanUrl)) }
    }

    fun removeFunLink(link: FunLink) = updateMenu { it.withoutFunLink(link) }

    fun setDoughToday(count: Int?) = updateMenu { it.withDoughToday(count) }

    fun adjustDough(delta: Int) = updateMenu { it.adjustDough(delta) }

    fun exportMenu(uri: Uri) = viewModelScope.launch {
        val menu = state.value.menu
        val written = withContext(Dispatchers.IO) {
            runCatching { resolver.openOutputStream(uri, "wt")!!.use { it.write(MenuFile.encode(menu).toByteArray()) } }
        }
        service.setMenuFileMessage(if (written.isSuccess) "Menü gespeichert." else "Speichern fehlgeschlagen.")
    }

    fun importMenu(uri: Uri) = viewModelScope.launch {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { MenuFile.decode(resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
        }
        // The dough count isn't in the file: keep it, mid-party it's still what's in the fridge.
        loaded.onSuccess { imported -> service.updateMenu { imported.copy(dough = it.dough) } }
        service.setMenuFileMessage(if (loaded.isSuccess) "Menü geladen." else "Keine gültige Menü-Datei, das Menü bleibt unverändert.")
    }

    private val resolver get() = getApplication<Application>().contentResolver

    /** Back to a fresh install: no orders, the standard menu without a dough count, the default print style. */
    fun resetAll() {
        prefs.edit { putString("printStyle", PrintStyle.Receipt.name) }
        viewModelScope.launch { service.resetAll() }
    }
}

/** Settings only enables "add" for these, so the check lives in one place. */
fun isWebLink(url: String) = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
