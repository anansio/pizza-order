package io.github.kaerbr.pizzaorder

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android half of the order desk: files, printer, preferences and the admin lock.
 * Everything that changes orders lives in [OrderService]; this delegates and keeps the Compose flow.
 *
 * The service comes from [OrderDesk], not from here: the HTTP server takes numbers and dough from the same
 * instance, or two guests would get the same number again.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val desk = OrderDesk.get(application)

    private val service = desk.service

    val state: StateFlow<AppState> = service.state

    /** Switch, port and join code of the guest server, plus where it stands right now. */
    val guest: StateFlow<GuestRuntime> = desk.guest.runtime

    // The confirmation belongs to the caller, not to the shared state: with guest orders arriving over HTTP,
    // a single slot would let one guest's slip replace another's.
    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation: StateFlow<Confirmation?> = _confirmation.asStateFlow()

    init {
        viewModelScope.launch {
            desk.ensureLoaded()
            // On by the admin's choice before the app was closed: without this the switch would read "an"
            // while nothing listens. The service is asked for from the foreground, which is where Android
            // allows a dataSync start.
            if (desk.guestSettings.enabled) GuestService.setEnabled(getApplication(), true)
        }
    }

    // ─── Admin lock ─────────────────────────────────────────────────────────

    // Lives in the ViewModel so the wrong-try counter survives rotation.
    val adminLock = AdminLock()

    // ─── Ausdruck ───────────────────────────────────────────────────────────

    fun setPrintStyle(style: PrintStyle) = desk.setPrintStyle(style)

    fun testPrint() = service.testPrint()

    /** The table card a guest scans; no order behind it, so it reports its own print state. */
    fun printGuestCard(url: String) = service.printGuestCard(url)

    // ─── Orders ─────────────────────────────────────────────────────────────

    /**
     * @param onResult runs on the main thread with what the desk decided: a refusal leaves the guest's input
     *   in place, so the form is only cleared once [Placed] came back.
     */
    fun submitOrder(customer: String, pizza: Pizza, note: String, onResult: (SubmitResult) -> Unit) {
        viewModelScope.launch {
            val result = service.submit(customer, pizza, note)
            if (result is Placed) _confirmation.value = Confirmation(result.order, result.printout)
            onResult(result)
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

    // ─── Gäste-Bestellung ───────────────────────────────────────────────────

    /** Switches the server on or off; the foreground service is what keeps it alive in the background. */
    fun setGuestEnabled(enabled: Boolean) = GuestService.setEnabled(getApplication(), enabled)

    /**
     * A different port restarts the server in place; the page in a guest's browser just polls on.
     *
     * A port that can't be bound leaves nothing behind: the foreground service goes too, or it would keep a
     * notification for a server that isn't there. The reason stays on the panel.
     */
    fun setGuestPort(port: Int) = viewModelScope.launch {
        if (!desk.startGuest(port)) GuestService.stop(getApplication())
    }

    /** Invalidates every QR code handed out so far. */
    fun newGuestCode() {
        desk.guestSettings.rotateCode()
        desk.guest.refreshCode()
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

    fun setMaxOpenOrders(count: Int?) = updateMenu { it.withMaxOpenOrders(count) }

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
        desk.setPrintStyle(PrintStyle.Receipt)
        viewModelScope.launch { service.resetAll() }
    }
}

/** Settings only enables "add" for these, so the check lives in one place. */
fun isWebLink(url: String) = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
