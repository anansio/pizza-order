package io.github.kaerbr.pizzaorder

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.security.SecureRandom

/** Where the guest server stands, as the admin panel shows it. */
sealed interface GuestStatus {
    data object Off : GuestStatus
    data object Starting : GuestStatus
    data class Running(val port: Int) : GuestStatus
    /** [message] is what the admin reads: port taken, no page, WiFi down. */
    data class Failed(val message: String) : GuestStatus
}

data class GuestRuntime(
    val enabled: Boolean = false,
    val port: Int = GuestSettings.DEFAULT_PORT,
    val status: GuestStatus = GuestStatus.Off,
    /** In the URL the QR code carries; the only thing a neighbour can't guess. */
    val code: String = "",
)

/**
 * The admin's guest preferences as [GuestHost] needs them. [GuestSettings] is the Android one; a test can
 * hand in a plain map, so the switch's state machine runs without a device.
 */
interface GuestPreferences {
    var enabled: Boolean
    var port: Int
    val code: String
}

/**
 * The admin's switch and the numbers behind it, in their own preferences file: the guest feature is not part
 * of the menu and must not travel with an exported one.
 */
class GuestSettings(context: Context) : GuestPreferences {
    private val prefs = context.getSharedPreferences("guest", Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    override var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit { putInt(KEY_PORT, value) }

    /** Created on first read and then stable, so yesterday's QR code keeps working after a restart. */
    override val code: String
        get() = prefs.getString(KEY_CODE, null) ?: newCode().also { prefs.edit { putString(KEY_CODE, it) } }

    fun newCode(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789" // no l/i/o/0/1: read out loud and typed by hand
        val random = SecureRandom()
        return (1..CODE_LENGTH).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** A new code invalidates every QR code already handed out. */
    fun rotateCode(): String = newCode().also { prefs.edit { putString(KEY_CODE, it) } }

    companion object {
        /** 8080 is what a phone user recognises and what a browser tries when the port is missing. */
        const val DEFAULT_PORT = 8080
        const val CODE_LENGTH = 6
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_CODE = "code"
    }
}

/**
 * Application-wide home of the order desk.
 *
 * One [OrderService] for the whole process: the Compose screens and the HTTP server must take numbers,
 * dough and the lock from the same instance, or two guests get the same number again. The scope is the
 * application's, not a ViewModel's, so a printout started by a phone keeps going when the tablet's screen
 * is recreated.
 */
class OrderDesk private constructor(private val application: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val orderStore = JsonStore(application, "orders.json", ListSerializer(Order.serializer()), emptyList())
    private val menuStore = JsonStore(application, "menu.json", MenuConfig.serializer(), MenuConfig.Standard.menu, pretty = true)

    val service = OrderService(
        orderStore,
        menuStore,
        Printer(application),
        scope,
        // Chosen style survives a restart, so the first printout after one is not suddenly the other design.
        printStyle = PrintStyle.entries.find { it.name == prefs.getString("printStyle", null) } ?: PrintStyle.Receipt,
    )

    val guestSettings = GuestSettings(application)
    val guest = GuestHost(service, guestSettings, { GuestPage.load(application) }) {
        LocalNetworkPermission.isMissing(application)
    }

    private val loadLock = Mutex()
    @Volatile private var loaded = false

    /** Reads both files once. The HTTP server waits on this, so no request is answered from a default menu. */
    suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return@withLock
            service.load()
            loaded = true
        }
    }

    /**
     * Loads the files, then starts the server. Both callers (the foreground service and the admin panel) go
     * through here, so no request is ever answered from a default menu.
     *
     * The permission is checked inside [GuestHost.start], not in [GuestService]: without it the socket binds
     * fine and the kernel drops every packet, so a server would claim to run while each phone times out. One
     * check, both callers.
     *
     * @return true when something listens.
     */
    suspend fun startGuest(port: Int = guestSettings.port): Boolean {
        ensureLoaded()
        return guest.start(port)
    }

    fun setPrintStyle(style: PrintStyle) {
        service.setPrintStyle(style)
        prefs.edit { putString("printStyle", style.name) }
    }

    companion object {
        @Volatile private var instance: OrderDesk? = null

        fun get(context: Context): OrderDesk = instance ?: synchronized(this) {
            instance ?: OrderDesk(context.applicationContext as Application).also { instance = it }
        }
    }
}

/**
 * Owns the running [GuestServer] and the status the admin panel shows.
 *
 * Start and stop are idempotent: the admin toggles a switch, the foreground service asks again after a
 * restart, and a changed port restarts the same server in place.
 */
class GuestHost(
    private val service: OrderService,
    private val settings: GuestPreferences,
    private val page: () -> String,
    /** The permission Android 17 needs before LAN traffic flows; injected so the guard is testable. */
    private val permissionMissing: () -> Boolean = { false },
) {
    private val _runtime = MutableStateFlow(
        GuestRuntime(enabled = settings.enabled, port = settings.port, code = settings.code),
    )
    val runtime: StateFlow<GuestRuntime> = _runtime.asStateFlow()

    private val lock = Any()
    private var server: GuestServer? = null

    /**
     * Starts on [port], or keeps the running one if it already listens there.
     *
     * The switch is persisted only once something listens: a failed start must not leave "on" behind, or the
     * panel offers "Server ausschalten" for a server that isn't there and every app start retries the same
     * failure from [AppViewModel].
     *
     * @return true when [port] is being served.
     */
    fun start(port: Int = settings.port): Boolean = synchronized(lock) {
        when {
            permissionMissing() -> {
                blockedLocked()
                false
            }
            server?.listeningPort == port -> {
                settings.enabled = true
                settings.port = port
                _runtime.value = _runtime.value.copy(enabled = true, port = port, code = settings.code, status = GuestStatus.Running(port))
                true
            }
            else -> serveLocked(port)
        }
    }

    private fun serveLocked(port: Int): Boolean {
        stopLocked()
        _runtime.value = _runtime.value.copy(enabled = true, port = port, code = settings.code, status = GuestStatus.Starting)
        return try {
            server = GuestServer(port, GuestApi(service, { settings.code }, page())).apply { start() }
            settings.enabled = true
            settings.port = port
            _runtime.value = _runtime.value.copy(status = GuestStatus.Running(port))
            true
        } catch (e: IOException) {
            // Port taken, no network: the message is more use to the admin than a stack trace.
            server = null
            settings.enabled = false
            _runtime.value = _runtime.value.copy(enabled = false, status = GuestStatus.Failed(explain(e, port)))
            false
        }
    }

    fun stop() = synchronized(lock) {
        stopLocked()
        settings.enabled = false
        _runtime.value = _runtime.value.copy(enabled = false, status = GuestStatus.Off)
    }

    /**
     * Closes the socket but keeps the admin's choice: [GuestService.onDestroy] calls this when Android stops
     * the service, and after a restart the switch must still read "on" so the server comes back.
     *
     * A [GuestStatus.Failed] stays: it is the reason the service is going away, and "Aus: …" under a
     * "Server ausschalten" button would hide it.
     */
    fun shutdown() = synchronized(lock) {
        stopLocked()
        if (_runtime.value.status !is GuestStatus.Failed) {
            _runtime.value = _runtime.value.copy(status = GuestStatus.Off)
        }
    }

    /** Android 17 hasn't been given the permission: the switch stays off and says why, rather than reporting
     *  a server every phone would time out on. */
    private fun blockedLocked() {
        stopLocked()
        settings.enabled = false
        _runtime.value = _runtime.value.copy(
            enabled = false,
            status = GuestStatus.Failed("Zugriff auf das lokale Netz fehlt. Bitte oben erlauben."),
        )
    }

    /** Picks up a new join code; [GuestApi] reads it per request, so the running server follows along. */
    fun refreshCode() = synchronized(lock) {
        _runtime.value = _runtime.value.copy(code = settings.code)
    }

    private fun stopLocked() {
        runCatching { server?.stop() }
        server = null
    }

    // "EADDRINUSE" on Android, "Address already in use" on the JVM where the tests run.
    private fun explain(e: IOException, port: Int) = when {
        e.message?.let { it.contains("EADDRINUSE", true) || it.contains("already in use", true) } == true ->
            "Port $port ist belegt."
        else -> "Server startet nicht: ${e.message ?: e.javaClass.simpleName}"
    }
}

/** The guest page, shipped as an asset so the server needs no file system access of its own. */
object GuestPage {
    /** A build without the page still answers, with a sentence instead of a blank screen. */
    fun load(context: Context): String = runCatching {
        context.assets.open(GuestApi.PAGE_ASSET).use { it.readBytes().decodeToString() }
    }.getOrElse {
        "<!doctype html><meta charset='utf-8'><title>Pizza Order</title>" +
            "<p>Die Gastseite fehlt in diesem Build."
    }
}
