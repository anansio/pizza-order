package io.github.kaerbr.pizzaorder

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What a guest's browser may see of the menu. Deliberately not [MenuConfig]: the admin half (QR links,
 * dough bookkeeping, what ran out) either doesn't belong on a stranger's phone or would leak the kitchen's
 * evening. Everything a guest needs to choose a pizza is here, diets and allergens included.
 */
@Serializable
data class GuestMenu(
    val sauces: List<GuestIngredient>,
    val toppings: List<GuestIngredient>,
    val presets: List<GuestPizza>,
    val soldOut: List<String>,
    /** Dough left tonight; null when the kitchen doesn't count. */
    val available: Int?,
    /** Open orders the kitchen takes at once; null when unlimited. */
    val maxOpenOrders: Int?,
    val openOrders: Int,
)

@Serializable
data class GuestIngredient(val name: String, val diet: Diet, val allergens: List<Allergen>)

@Serializable
data class GuestPizza(
    val name: String,
    val sauce: String,
    val toppings: List<String>,
    val diet: Diet,
    val allergens: List<Allergen>,
    /** Ingredients of this pizza that ran out tonight; non-empty = not orderable. */
    val soldOut: List<String>,
)

/**
 * One entry of the guest status page: an order **the requesting phone placed itself**. No name, no note —
 * and no other guest's pizza either, because [GuestApi] never sends a row to a phone that doesn't own it.
 *
 * [status] is the enum name (`QUEUED`) for the page to switch on, [statusLabel] the German word for people;
 * [OrderStatus] itself stays unserialized, it's derived and part of no file.
 */
@Serializable
data class GuestOrder(
    val number: Int,
    val pizza: String,
    val status: String,
    val statusLabel: String,
    /** Open orders placed before this one; 0 once it's done. */
    val ahead: Int,
    val placedAt: Long,
)

/** The `201` body right after ordering, so the phone can show the same slip the tablet shows. */
@Serializable
data class GuestReceipt(
    val order: GuestOrder,
    val printout: String,
    /** The phone's key for its own orders; a retry without one gets the same key back. */
    val token: String,
)

/** Why an order was refused, in a shape the page can switch on and a person can read. */
@Serializable
data class GuestRefusal(val reason: String, val message: String)

/** What a guest's browser sends to `POST /api/orders`. Fields are checked by [OrderService], not trusted here. */
@Serializable
data class GuestOrderRequest(
    val customer: String = "",
    val note: String = "",
    val pizza: GuestPizzaRequest = GuestPizzaRequest(),
)

@Serializable
data class GuestPizzaRequest(
    val sauce: String = "",
    val toppings: List<String> = emptyList(),
)

/** One HTTP request, already decoded: no NanoHTTPD types, so the routes run in plain JVM tests. */
data class GuestRequest(
    val method: String,
    val path: String,
    val remoteIp: String,
    val query: Map<String, List<String>> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    /** The phone's own key, when it sent one. Decides what [GuestApi] sends back, never what it accepts. */
    val guestToken: String? get() = headers[GuestApi.GUEST_TOKEN_HEADER]
}

data class GuestResponse(
    val status: Int,
    val contentType: String = JSON_TYPE,
    val body: String = "",
) {
    companion object {
        const val JSON_TYPE = "application/json; charset=utf-8"
        const val HTML_TYPE = "text/html; charset=utf-8"
        const val PLAIN_TYPE = "text/plain; charset=utf-8"
    }
}

/**
 * The HTTP face of the order desk: four routes, no more.
 *
 *     GET  /            → the guest page from assets
 *     GET  /api/menu    → [GuestMenu]
 *     POST /api/orders  → [GuestReceipt] or [GuestRefusal]
 *     GET  /api/orders  → [GuestOrdersEnvelope]: this phone's own orders, the queue as a count
 *
 * Nothing here writes the menu, the dough, what ran out or "clear all": the admin panel stays on the tablet,
 * behind the pattern lock. Everything an order changes goes through [OrderService.submit], which is where the
 * rules live — this class only guards the door.
 *
 * The door has three locks: the join code (constant time), a body size cap, and a per-IP rate limit. [admit]
 * runs the first two before [GuestServer] reads a byte, so a request without the code costs no body parse.
 * Guest strings are filtered here, before they reach the service, because `printableOnly()` shares a
 * non-thread-safe encoder with the Compose form on the main thread.
 *
 * What a phone sees is filtered as well: [GUEST_TOKEN_HEADER] names the phone, and [guestView] hands back
 * only the orders that phone placed. A guest never reads another guest's pizza — a page that merely hides
 * the rows would still ship them over the WiFi.
 */
class GuestApi(
    private val service: OrderService,
    /** Read per request, not once: a new code invalidates the old QR codes without a restart. */
    private val joinCode: () -> String,
    private val page: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        encodeDefaults = true
        // Nulls stay in the body: a page can read "no limit" from the field instead of from a missing one.
        explicitNulls = true
        // A newer page may send fields this build doesn't know yet; that is not a reason to refuse an order.
        ignoreUnknownKeys = true
    }

    // One retry is one order: a phone that loses the reply on flaky WiFi sends again with the same key.
    private val idempotency = Idempotency(limit = 200)
    private val postLock = Mutex()

    private val limiter = RateLimiter(requests = 60, windowMillis = 10_000, now = now)
    private val orderLimiter = RateLimiter(requests = 12, windowMillis = 60_000, now = now)

    /**
     * Rate limit and join code, in that order, with no body involved: the transport runs this before it reads
     * a single byte, so a stranger on the same WiFi can't cost a body parse or a JSON decode by guessing.
     *
     * @return the refusal to send, or null when the request may proceed.
     */
    fun admit(remoteIp: String, query: Map<String, List<String>>): GuestResponse? {
        // Rate limit first, so a flood costs nothing and never reaches the body.
        if (!limiter.allow(remoteIp)) return error(429, "Zu viele Anfragen. Bitte kurz warten.")

        // The code gates every route, the page included: without it a neighbour on the same WiFi could order.
        if (!codeMatches(query["k"]?.firstOrNull())) {
            return error(403, "Kein Zugang. Bitte den QR-Code auf dem Tablet scannen.")
        }
        return null
    }

    /** Routes a request that already passed [admit]: [GuestServer] calls it after the body has been read. */
    suspend fun handle(request: GuestRequest): GuestResponse =
        when (request.path.trimEnd('/').ifEmpty { "/" }) {
            "/" -> if (request.method == "GET") {
                GuestResponse(200, GuestResponse.HTML_TYPE, page)
            } else methodNotAllowed("GET")

            "/api/menu" -> if (request.method == "GET") {
                ok(json.encodeToString(GuestMenu.serializer(), guestMenu()))
            } else methodNotAllowed("GET")

            "/api/orders" -> when (request.method) {
                "GET" -> ok(json.encodeToString(GuestOrdersEnvelope.serializer(), guestView(request.guestToken)))
                "POST" -> placeOrder(request)
                else -> methodNotAllowed("GET, POST")
            }

            else -> error(404, "Nicht gefunden.")
        }

    // ─── POST /api/orders ───────────────────────────────────────────────────

    private suspend fun placeOrder(request: GuestRequest): GuestResponse {
        if (!orderLimiter.allow(request.remoteIp)) {
            return error(429, "Gerade zu viele Bestellungen von diesem Gerät. Bitte in einer Minute nochmal.")
        }
        if (request.body.toByteArray().size > MAX_BODY_BYTES) return error(413, "Anfrage zu groß.")

        val order = runCatching { json.decodeFromString(GuestOrderRequest.serializer(), request.body) }
            .getOrElse { return error(400, "Die Anfrage war kein gültiges JSON.") }

        val idemKey = request.headers["idempotency-key"]?.trim()?.takeIf { it.isNotEmpty() }

        // A phone sends the key it was handed last time, so a second order lands in the same list as the first.
        // A shape we didn't hand out is ignored rather than trusted: it decides what a phone may see.
        val token = request.guestToken?.takeIf(::isWellFormedGuestToken) ?: newGuestToken()

        // Check-then-store has to be one step, or two retries racing each other both get through.
        val outcome = postLock.withLock {
            if (idemKey != null) idempotency.get(idemKey)?.let { return@withLock Outcome.Repeat(it) }

            val pizza = Pizza(
                name = "",
                sauce = Sauce(order.pizza.sauce.trim()),
                toppings = Pizza.toppingsOf(order.pizza.toppings.map(String::trim).filter(String::isNotEmpty)),
            )
            // Named after the preset it matches, exactly as the tablet does it: a stranger's string never
            // becomes a pizza name on the kitchen's screen or on the paper.
            val named = pizza.copy(name = presetNameFor(pizza))

            // Filter here, before the service: the encoder behind printableOnly is not thread-safe.
            val result = service.submit(
                customer = order.customer.printableOnlyOffMainThread(),
                pizza = named,
                note = order.note.replace('\n', ' ').printableOnlyOffMainThread(),
                guestToken = token,
            )
            when (result) {
                is Placed -> {
                    // Built once, used twice: the phone's answer and the next retry's must be the same slip.
                    val body = receiptJson(result, token)
                    idemKey?.let { idempotency.put(it, body) }
                    Outcome.Created(body)
                }
                is Refused -> Outcome.Refused(result.reason)
            }
        }

        return when (outcome) {
            // Not 201: nothing was created this time, the phone just didn't hear the first answer.
            is Outcome.Repeat -> GuestResponse(200, body = outcome.body)
            is Outcome.Created -> GuestResponse(201, body = outcome.body)
            is Outcome.Refused -> refusal(outcome.reason)
        }
    }

    private fun receiptJson(placed: Placed, token: String): String {
        val orders = service.state.value.orders
        return json.encodeToString(
            GuestReceipt.serializer(),
            GuestReceipt(order = ownOrder(placed.order, orders), printout = placed.printout, token = token),
        )
    }

    private fun refusal(reason: Rejection): GuestResponse {
        val (status, name) = when (reason) {
            Rejection.BlankName -> 400 to "name"
            Rejection.NoSauce -> 400 to "no_sauce"
            is Rejection.UnknownIngredient -> 400 to "unknown_ingredient"
            is Rejection.SoldOut -> 409 to "sold_out"
            Rejection.NoDough -> 409 to "no_dough"
            is Rejection.TooManyOpen -> 429 to "too_many_open"
        }
        val body = json.encodeToString(GuestRefusal.serializer(), GuestRefusal(name, reason.message))
        return GuestResponse(status, GuestResponse.JSON_TYPE, body)
    }

    // ─── GET routes ─────────────────────────────────────────────────────────

    private fun guestMenu(): GuestMenu {
        val state = service.state.value
        val menu = state.menu
        fun ingredient(name: String) = GuestIngredient(name, menu.dietOf(name), menu.allergensOf(name).toList())

        return GuestMenu(
            sauces = menu.sauces.map { ingredient(it.name) },
            toppings = menu.toppings.map { ingredient(it.name) },
            presets = menu.presets.map { p ->
                GuestPizza(
                    name = p.name,
                    sauce = p.sauce.name,
                    toppings = p.toppings.map { it.name },
                    diet = menu.dietOf(p),
                    allergens = menu.allergensOf(p).toList(),
                    soldOut = menu.soldOutOf(p),
                )
            },
            soldOut = menu.soldOut.toList(),
            available = menu.dough?.available,
            maxOpenOrders = menu.openOrderLimit,
            openOrders = state.orders.count { !it.isDone() },
        )
    }

    /**
     * What one phone may see: its own orders, and the queue as a bare count.
     *
     * Filtering happens here and not in the page: a pizza name the server sends is a pizza name anyone on
     * the WiFi can read with `curl`, whether the page draws it or not.
     */
    private fun guestView(token: String?): GuestOrdersEnvelope {
        val orders = service.state.value.orders
        val mine = token?.let { own -> orders.filter { it.guestToken == own } }.orEmpty()
        // The oldest open one is where the phone stands in line; a finished order is no position at all.
        val firstOpen = mine.filter { !it.isDone() }.minByOrNull { it.id }
        return GuestOrdersEnvelope(
            mine = mine.map { ownOrder(it, orders) },
            ahead = firstOpen?.ordersAheadIn(orders),
            open = orders.count { !it.isDone() },
        )
    }

    /** Only ever called for an order the requesting phone placed: [guestView] decides that, not the caller's name. */
    private fun ownOrder(order: Order, orders: List<Order>) = GuestOrder(
        number = order.number,
        pizza = order.pizza.name,
        status = order.status.name,
        statusLabel = order.status.label,
        ahead = if (order.isDone()) 0 else order.ordersAheadIn(orders),
        placedAt = order.id,
    )

    /** A preset match keeps the menu's name; anything built by hand is an Eigenkreation, as on the tablet. */
    private fun presetNameFor(pizza: Pizza): String {
        val menu = service.state.value.menu
        return menu.presets.find { it.sauce == pizza.sauce && it.toppings.toSet() == pizza.toppings.toSet() }?.name
            ?: CUSTOM_NAME
    }

    // ─── Guards ─────────────────────────────────────────────────────────────

    /**
     * Constant time, and it doesn't leak the length either: both sides are hashed first, so a wrong code of
     * another length costs the same as a wrong code of the right one.
     */
    private fun codeMatches(given: String?): Boolean {
        val expected = joinCode()
        // An empty code would make every guess right; refuse everyone instead.
        if (expected.isEmpty()) return false
        return MessageDigest.isEqual(sha256(expected), sha256(given ?: ""))
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    private fun ok(body: String) = GuestResponse(200, body = body)

    private fun error(status: Int, message: String) = GuestResponse(status, GuestResponse.PLAIN_TYPE, message)

    private fun methodNotAllowed(allowed: String) = GuestResponse(405, GuestResponse.PLAIN_TYPE, "Nur $allowed.")

    private sealed interface Outcome {
        data class Created(val body: String) : Outcome
        data class Repeat(val body: String) : Outcome
        data class Refused(val reason: Rejection) : Outcome
    }

    companion object {
        /** A guest's body is a name, a note and a handful of ingredient names; 4 KB is generous. */
        const val MAX_BODY_BYTES = 4 * 1024

        /** The guest page, relative to the assets root. */
        const val PAGE_ASSET = "guest/index.html"

        /**
         * The header that names the phone, so `GET /api/orders` can answer with its own orders only.
         *
         * A header and not a query parameter: the URL is on the QR card, gets copied, and is passed around
         * at the table — the token would travel with it and every phone would read every pizza again.
         */
        const val GUEST_TOKEN_HEADER = "x-guest-token"

        /** 128 bits as hex, the shape [newGuestToken] hands out; anything else is a client's own idea. */
        private val GUEST_TOKEN_SHAPE = Regex("[0-9a-f]{32}")

        fun isWellFormedGuestToken(token: String) = GUEST_TOKEN_SHAPE.matches(token)

        /** Not guessable, and not derived from the IP: two phones behind one NAT still get their own order list. */
        fun newGuestToken(): String = ByteArray(16)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** `GET /api/orders` body: this phone's own orders, and the queue as a bare count. */
@Serializable
data class GuestOrdersEnvelope(
    /** Only orders placed by the requesting phone, newest first; empty for a phone that ordered nothing yet. */
    val mine: List<GuestOrder> = emptyList(),
    /** Open orders placed before this phone's oldest open one; null when this phone has nothing open. */
    val ahead: Int? = null,
    /** Open orders in the kitchen — the number only, never whose they are or what's on them. */
    val open: Int = 0,
)

/**
 * Remembers recent idempotency keys, oldest out first. In memory on purpose: a retry arrives seconds after
 * the first attempt, not after a restart, and losing the map costs a repeated slip, not an order.
 */
private class Idempotency(private val limit: Int) {
    private val entries = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > limit
    }

    @Synchronized fun get(key: String): String? = entries[key]

    @Synchronized fun put(key: String, body: String) {
        entries[key] = body
    }
}

/** A sliding window per IP: enough to stop a runaway page, harmless for a party of a dozen phones. */
private class RateLimiter(
    private val requests: Int,
    private val windowMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    @Synchronized fun allow(ip: String): Boolean {
        val at = now()
        // Only a backstop against a scan: a party has a handful of phones, not thousands of addresses.
        // Cleared before the lookup, or the queue below would be a detached copy and that IP would go
        // uncounted until the process restarts.
        if (hits.size > 512) hits.clear()
        val queue = hits.getOrPut(ip) { ArrayDeque() }
        while (queue.isNotEmpty() && at - queue.first() > windowMillis) queue.removeFirst()
        if (queue.size >= requests) return false
        queue.addLast(at)
        return true
    }
}
