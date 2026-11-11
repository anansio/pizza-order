package io.github.kaerbr.pizzaorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * The guest server over real sockets: a [GuestServer] on a free port, a real [OrderService] with files
 * in a temp dir.
 *
 * Only the pitch (which is one order per key, one number per order) is checked here; the routes themselves
 * are cheap enough to test through the server as well, so nothing is exercised twice in two ways.
 */
class GuestServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val printer = RecordingPrinter()
    private val json = Json { ignoreUnknownKeys = true }

    private val code = "test12"
    private var server: GuestServer? = null

    private fun service(menu: MenuConfig = MenuConfig.Standard.menu) = OrderService(
        FileStore(File(tmp.root, "orders.json"), ListSerializer(Order.serializer()), emptyList()),
        FileStore(File(tmp.root, "menu.json"), MenuConfig.serializer(), menu),
        printer,
        scope,
    )

    private suspend fun start(service: OrderService): Int {
        val api = GuestApi(service, { code }, PAGE, now = { 1_000L })
        val s = GuestServer(0, api)
        s.start()
        server = s
        return s.listeningPort
    }

    @After
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    private fun pizza() = MenuConfig.Standard.menu.presets.first { it.name == "Margherita" }

    private fun body(
        customer: String = "Anna",
        sauce: String = "Tomate",
        toppings: List<String> = listOf("Käse"),
        note: String = "",
    ) = """{"customer":"$customer","note":"$note","pizza":{"sauce":"$sauce","toppings":[${toppings.joinToString(",") { "\"$it\"" }}]}}"""

    private data class Answer(val status: Int, val body: String)

    private fun request(
        port: Int,
        path: String,
        method: String = "GET",
        body: String? = null,
        key: String? = null,
        token: String? = null,
    ): Answer {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        key?.let { connection.setRequestProperty("Idempotency-Key", it) }
        token?.let { connection.setRequestProperty("X-Guest-Token", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
        connection.disconnect()
        return Answer(status, text)
    }

    private fun guestOrder(text: String): GuestOrder =
        json.decodeFromString(GuestReceipt.serializer(), text).order

    private fun receipt(answer: Answer): GuestReceipt =
        json.decodeFromString(GuestReceipt.serializer(), answer.body)

    private fun guestOrders(text: String): GuestOrdersEnvelope =
        json.decodeFromString(GuestOrdersEnvelope.serializer(), text)

    private fun SubmitResult.placed(): Placed = assertTrue(this is Placed).let { this as Placed }

    /** The status page as that phone sees it, with the token it got when it ordered. */
    private fun statusOf(port: Int, token: String): GuestOrdersEnvelope =
        guestOrders(request(port, "/api/orders?k=$code", token = token).body)

    // ─── Routes ─────────────────────────────────────────────────────────────

    @Test
    fun thePageIsOneFileWithNoExternalAssets() {
        // A guest's phone has no route to the internet at a pizza party: a stylesheet from a CDN would simply
        // not load, and the page must not need a build step to be readable.
        val page = File("src/main/assets/guest/index.html")
            .takeIf { it.exists() } ?: File("app/src/main/assets/guest/index.html")
        val html = page.readText()

        assertTrue(html.contains("<form id=\"orderForm\""))
        assertTrue(html.contains("/api/orders"))
        // Die Seite schickt ihr Geräte-Token mit; sonst bekäme sie keine eigene Bestellung zurück.
        assertTrue("Token-Header", html.contains("X-Guest-Token"))
        // Die Warteschlange kommt als Zahl vom Server, nicht aus einer Liste, die die Seite selbst zählt.
        assertTrue("Warteschlange aus der Antwort", html.contains("data.open"))
        assertTrue("Name ist auf 24 Zeichen begrenzt", html.contains("maxlength=\"24\""))
        assertTrue("Notiz ist auf 160 Zeichen begrenzt", html.contains("maxlength=\"160\""))
        assertFalse("Kein externes Skript", html.contains("<script src"))
        assertFalse("Kein externes Stylesheet", html.contains("<link rel=\"stylesheet\""))
        // Keine Liste fremder Bestellungen mehr: die Seite hat für andere Gäste gar keine Ansicht.
        assertFalse("Keine Fremdliste", html.contains("Alle Bestellungen"))
        // Guest strings (name, note) reach the page through textContent. An HTML sink would execute them,
        // which is stored XSS on the tablet the kitchen is looking at.
        assertFalse("Kein innerHTML-Sink", html.contains("innerHTML ="))
        assertFalse("Kein insertAdjacentHTML", html.contains("insertAdjacentHTML"))
        assertFalse("Kein document.write", html.contains("document.write"))
    }

    @Test
    fun thePageRequiresASauceLikeTheKitchenDoes() {
        // Der Server lehnt jede Pizza ohne Sauce ab (Rejection.NoSauce), das Tablet sagt "Erst Sauce wählen".
        // Die Seite darf keine Eigenkreation anbieten, die die Küche danach ablehnt.
        val html = pageHtml()

        // Der Knopf hängt nur noch an der Sauce, nicht mehr an "Sauce leer *und* Belag leer".
        val buy = html.substringAfter("function updateBuy()").substringBefore("}")
        assertTrue("Sauce ist Pflicht für den Knopf", buy.contains("sauce === \"\";"))
        assertFalse("Belag ersetzt keine Sauce", buy.contains("toppings.length === 0"))
        assertTrue("Absenden nennt den Grund", html.contains("Bitte eine Sauce wählen."))
        assertTrue("Dock nennt den Grund", html.contains("Erst Sauce wählen"))
    }

    @Test
    fun thePageGivesUpOnAWiFiThatHangs() {
        // Ohne Abort bleibt der Knopf gesperrt, bis der TCP-Stack aufgibt — Minuten, in denen der Gast nicht
        // weiss, ob die Pizza unterwegs ist. Der Abbruch ist eine eigene Lage ("Tablet antwortet nicht"),
        // nicht "kein WLAN": sonst sucht der Gast beim Router nach einem Fehler, der dort nicht ist.
        val html = pageHtml()

        assertTrue("Bestellung bricht ab", html.contains("SUBMIT_TIMEOUT_MS"))
        assertTrue("Polling bricht ab", html.contains("POLL_TIMEOUT_MS"))
        assertTrue("Abbruch erzeugt einen eigenen Fehler", html.contains("timeout.timeout = true"))
        assertTrue("Meldung nennt das Tablet", html.contains("Das Tablet antwortet nicht."))
        // Der Knopf muss in jedem Fall wieder frei werden: der Abschluss haengt am then(), nicht am catch().
        assertTrue(html.contains(".then(function () {\n        busy = false;"))
    }

    @Test
    fun allergenLettersAreExplainedOnThePhoneToo() {
        // "L" im Chip war auf dem Handy eine Auskunft ohne Inhalt: `title` gibt es dort nicht, ein
        // Fingertipp zeigt keinen Tooltip. Die Legende steht sichtbar unter dem Belag, und sie nennt nur,
        // was in diesem Menü wirklich vorkommt (wie DietLegend auf dem Tablet).
        val html = pageHtml()
        assertTrue("Legende im Markup", html.contains("id=\"legend\""))
        assertTrue("Legende wird gefuellt", html.contains("function renderLegend()"))
        assertTrue("Legende nennt das Allergen", html.contains("\"Enthält \" + ALLERGEN_LABELS[a]"))
        assertTrue("Marken an der Sauce", html.contains("allergenTags(s.allergens)"))
        assertTrue("Marken am Belag", html.contains("allergenTags(t.allergens)"))
        // Die Legende haengt unter beiden Listen, nicht zwischen ihnen: sonst wirkt sie wie eine dritte Zutat.
        assertTrue(html.indexOf("id=\"legend\"") > html.indexOf("id=\"toppings\""))
    }

    /**
     * `localStorage` wirft in Safari (privat, "Alle Cookies blockieren"), in Chrome bei gesperrten
     * Website-Daten und in manchen WebViews. Ungesichert stirbt beim Lesen das Skript (leere Seite) und
     * beim Schreiben der Erfolgszweig — die Kueche haette die Bestellung, das Handy zeigte "keine
     * Verbindung", und der Gast bestellte ein zweites Mal.
     */
    @Test
    fun thePageSurvivesAStorageThatThrows() {
        val html = pageHtml()

        // Genau ein Zugriff je Richtung, beide in einem try.
        assertEquals("Ein Lesezugriff, gekapselt", 1, Regex("""localStorage\.getItem""").findAll(html).count())
        assertEquals("Ein Schreibzugriff, gekapselt", 1, Regex("""localStorage\.setItem""").findAll(html).count())
        assertTrue("Token faellt auf den Speicher der Sitzung zurueck", html.contains("var memoryToken"))
        assertTrue("Lesen ist gesichert", html.contains("return memoryToken || \"\";"))

        val read = html.substringAfter("function readToken()").substringBefore("function writeToken")
        assertTrue("Lesen in try", read.contains("try {") && read.contains("catch (e)"))
        val write = html.substringAfter("function writeToken").substringBefore("// Das eigene Geräte-Token")
        assertTrue("Schreiben in try", write.contains("try {") && write.contains("catch (e)"))
        // Der Erfolgszweig darf nicht am Speicher scheitern: die Bestellung ist dann schon in der Kueche.
        assertTrue("Erfolgszweig schreibt ueber writeToken", !html.contains("localStorage.setItem(\"pizzaGuestToken\", token)"))
    }

    private fun pageHtml(): String {
        val page = File("src/main/assets/guest/index.html")
            .takeIf { it.exists() } ?: File("app/src/main/assets/guest/index.html")
        return page.readText()
    }

    @Test
    fun thePageNeedsTheCodeAndThenArrives() = runBlocking {
        val port = start(service().also { it.load() })

        assertEquals(403, request(port, "/").status)
        assertEquals(403, request(port, "/?k=falsch").status)

        val page = request(port, "/?k=$code")
        assertEquals(200, page.status)
        assertEquals(PAGE, page.body)
    }

    @Test
    fun theMenuCarriesWhatAGuestNeedsAndNothingFromTheAdminSide() = runBlocking {
        val service = service(
            MenuConfig.Standard.menu.withDoughToday(7).withSoldOut("Käse", true).withMaxOpenOrders(4),
        )
        service.load()
        val port = start(service)

        val answer = request(port, "/api/menu?k=$code")
        assertEquals(200, answer.status)
        val menu = json.decodeFromString(GuestMenu.serializer(), answer.body)

        assertEquals(7, menu.available)
        assertEquals(4, menu.maxOpenOrders)
        assertEquals(listOf("Käse"), menu.soldOut)
        assertTrue(menu.sauces.isNotEmpty() && menu.toppings.isNotEmpty() && menu.presets.isNotEmpty())
        // The margherita can't be ordered tonight, and the guest page can see why.
        assertEquals(listOf("Käse"), menu.presets.first { it.name == "Margherita" }.soldOut)
        // Admin-only data stays off the phone: no QR links, no dough's "today", no open-order list.
        assertTrue(!answer.body.contains("funLinks") && !answer.body.contains("today"))
    }

    @Test
    fun anOrderComesBackWithItsNumberSlipAndPosition() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val answer = request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna"))
        assertEquals(201, answer.status)

        val receipt = json.decodeFromString(GuestReceipt.serializer(), answer.body)
        assertEquals(1, receipt.order.number)
        assertEquals("Margherita", receipt.order.pizza)
        assertEquals("QUEUED", receipt.order.status)
        assertEquals("Wartet", receipt.order.statusLabel)
        assertEquals(0, receipt.order.ahead)
        // The phone shows the same slip the tablet shows.
        assertTrue(receipt.printout.contains("Anna"))
    }

    @Test
    fun twoGuestsGetTwoNumbersAndTheSecondSeesOneAhead() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val anna = receipt(request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna")))
        val bene = receipt(request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Bene")))

        assertEquals(listOf(1, 2), listOf(anna.order.number, bene.order.number))
        // Bene bestellt nach Anna, also steht genau eine Pizza vor ihm.
        assertEquals(1, statusOf(port, bene.token).mine.single().ahead)
        assertEquals(0, statusOf(port, anna.token).mine.single().ahead)
        assertEquals(2, statusOf(port, bene.token).open)
    }

    // ─── A guest sees their own pizza and no one else's ─────────────────────

    @Test
    fun aGuestNeverReadsAnotherGuestsPizza() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val anna = receipt(request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna")))
        request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Bene", toppings = listOf("Salami", "Käse")))

        val answer = request(port, "/api/orders?k=$code", token = anna.token)

        val view = guestOrders(answer.body)
        assertEquals(1, view.mine.size)
        assertEquals("Margherita", view.mine.single().pizza)
        // Die Zahl der offenen Bestellungen sieht Anna, die Pizza von Bene nicht — auch nicht im rohen Body.
        assertEquals(2, view.open)
        assertFalse("Fremde Pizza im Body", answer.body.contains("Salami"))
        assertFalse("Fremder Name im Body", answer.body.contains("Bene"))
    }

    @Test
    fun aPhoneWithoutATokenGetsNoOrdersButStillTheQueueLength() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })
        request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna"))

        val view = guestOrders(request(port, "/api/orders?k=$code").body)

        assertTrue(view.mine.isEmpty())
        assertEquals(1, view.open)
        assertNull("Ohne eigene offene Bestellung gibt es keine Position", view.ahead)
    }

    @Test
    fun theTokenComesBackAndBothOrdersLandInTheSameList() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val token = receipt(request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna"))).token
        assertEquals(32, token.length)

        // Dieselbe Karte, zweite Bestellung: das Handy schickt sein Token mit.
        request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna", toppings = listOf("Oliven")), token = token)

        val view = statusOf(port, token)
        assertEquals(listOf(2, 1), view.mine.map { it.number })
        assertEquals(2, view.open)
    }

    @Test
    fun aTokenWeDidNotHandOutIsIgnoredRatherThanTrusted() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })
        val anna = receipt(request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna")))

        // Ein geratenes Token hat nicht die Form, die wir vergeben — das Handy bekommt ein eigenes statt Annas Liste.
        val forged = request(
            port, "/api/orders?k=$code", method = "POST",
            body = body(customer = "Bene"), token = "anna",
        )
        val benesToken = receipt(forged).token

        assertNotEquals(anna.token, benesToken)
        assertEquals(1, statusOf(port, benesToken).mine.size)
    }

    @Test
    fun aTabletOrderCountsInTheQueueButBelongsToNobody() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(9))
        service.load()
        val port = start(service)

        // Am Tablet aufgenommen, ohne Token: sie steht in der Schlange, aber auf keinem Handy als "meine".
        service.submit("Küche", pizza(), "", guestToken = null).placed()
        val token = receipt(request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna"))).token

        val view = statusOf(port, token)
        assertEquals(listOf(2), view.mine.map { it.number })
        assertEquals(1, view.mine.single().ahead)
        assertEquals(2, view.open)
    }

    @Test
    fun aBuiltByHandPizzaIsNamedEigenkreationAndNeverCarriesAGuestString() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val answer = request(
            port, "/api/orders?k=$code", method = "POST",
            body = body(sauce = "Tomate", toppings = listOf("Oliven", "Rucola")),
        )
        assertEquals(201, answer.status)
        assertEquals(CUSTOM_NAME, guestOrder(answer.body).pizza)
    }

    // ─── Retry, refusal, trust boundary ─────────────────────────────────────

    @Test
    fun theSameRetryIsOneOrderNotTwo() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val first = request(port, "/api/orders?k=$code", method = "POST", body = body(), key = "abc")
        val retry = request(port, "/api/orders?k=$code", method = "POST", body = body(), key = "abc")

        assertEquals(201, first.status)
        // 200, not 201: the order exists, the phone only repeated itself.
        assertEquals(200, retry.status)
        // Byte for byte the same answer, the token included: a retry must not mint a second identity.
        assertEquals(first.body, retry.body)
        // One dough ball, one printout, one entry on the tablet.
        assertEquals(1, request(port, "/api/menu?k=$code").body
            .let { json.decodeFromString(GuestMenu.serializer(), it) }.openOrders)
        // Printing runs beside the answer, so wait for the first slip and then give a second one time to
        // appear; a plain size check right away is a race in CI, not a guarantee.
        withTimeout(5_000) { while (printer.markups.size < 1) delay(10) }
        delay(200)
        assertEquals(1, printer.markups.size)
    }

    @Test
    fun aDifferentKeyIsADifferentOrder() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        request(port, "/api/orders?k=$code", method = "POST", body = body(), key = "abc")
        val second = request(port, "/api/orders?k=$code", method = "POST", body = body(), key = "def")

        assertEquals(201, second.status)
        assertEquals(2, guestOrder(second.body).number)
    }

    @Test
    fun anInventedIngredientIsRefusedWithAReason() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val answer = request(
            port, "/api/orders?k=$code", method = "POST",
            body = body(sauce = "Ketchup", toppings = listOf("Ananas")),
        )

        assertEquals(400, answer.status)
        val refusal = json.decodeFromString(GuestRefusal.serializer(), answer.body)
        assertEquals("unknown_ingredient", refusal.reason)
        assertTrue(refusal.message.contains("Ketchup"))
        assertTrue(printer.markups.isEmpty())
    }

    @Test
    fun anEmptyNameIsRefused() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val answer = request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "   "))

        assertEquals(400, answer.status)
        assertEquals("name", json.decodeFromString(GuestRefusal.serializer(), answer.body).reason)
    }

    @Test
    fun noDoughIsAConflictNotABadRequest() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(0)).also { it.load() })

        val answer = request(port, "/api/orders?k=$code", method = "POST", body = body())

        assertEquals(409, answer.status)
        assertEquals("no_dough", json.decodeFromString(GuestRefusal.serializer(), answer.body).reason)
    }

    @Test
    fun theFullQueueIsAConflict() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9).withMaxOpenOrders(1)).also { it.load() })

        assertEquals(201, request(port, "/api/orders?k=$code", method = "POST", body = body()).status)
        val refused = request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Bene"))

        assertEquals(429, refused.status)
        assertEquals("too_many_open", json.decodeFromString(GuestRefusal.serializer(), refused.body).reason)
    }

    @Test
    fun aWrongCodeIsForbiddenBeforeTheBodyIsLookedAt() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        // Garbage body: if the code were checked after parsing, this would be a 400 instead of a 403.
        val answer = request(port, "/api/orders?k=falsch", method = "POST", body = "kein json")

        assertEquals(403, answer.status)
    }

    @Test
    fun aWrongCodeIsForbiddenBeforeTheBodyLengthIsChecked() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        // Over the cap and refused for the code: 413 here would mean the transport measured the body before
        // the code, which is exactly the order a stranger could exploit.
        val answer = request(
            port, "/api/orders?k=falsch", method = "POST",
            body = body(note = "x".repeat(GuestApi.MAX_BODY_BYTES + 100)),
        )

        assertEquals(403, answer.status)
        assertTrue(printer.markups.isEmpty())
    }

    @Test
    fun aBodyWithoutSauceIsRefusedWithAReason() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val answer = request(port, "/api/orders?k=$code", method = "POST", body = body(sauce = "", toppings = emptyList()))

        assertEquals(400, answer.status)
        val refusal = json.decodeFromString(GuestRefusal.serializer(), answer.body)
        assertEquals("no_sauce", refusal.reason)
        assertTrue(refusal.message.contains("Sauce"))
        assertTrue(printer.markups.isEmpty())
    }

    @Test
    fun toppingsWithoutSauceAreRefusedToo() = runBlocking {
        // The guest page used to let this through (sauce empty, toppings picked = "Eigenkreation" with cheese)
        // and the kitchen answered NoSauce. The page now blocks it; the server stays the one that decides.
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        val answer = request(port, "/api/orders?k=$code", method = "POST", body = body(sauce = "", toppings = listOf("Käse")))

        assertEquals(400, answer.status)
        assertEquals("no_sauce", json.decodeFromString(GuestRefusal.serializer(), answer.body).reason)
        assertTrue(printer.markups.isEmpty())
    }

    @Test
    fun aBodyOverTheLimitIsRefusedWithoutTakingAnOrder() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        // Printed, not refused: the form cuts the same way. Well past NOTE_MAX, still under the cap.
        val long = request(port, "/api/orders?k=$code", method = "POST", body = body(note = "x".repeat(500)))
        assertEquals(201, long.status)

        val huge = request(port, "/api/orders?k=$code", method = "POST", body = body(note = "x".repeat(GuestApi.MAX_BODY_BYTES + 100)))
        assertEquals(413, huge.status)

        // The too-large one never reached the printer, so exactly one slip exists.
        withTimeout(5_000) { while (printer.markups.size < 1) delay(10) }
        delay(200)
        assertEquals(1, printer.markups.size)
    }

    @Test
    fun theBodyIsReadAsUtf8EvenWithoutACharsetHeader() = runBlocking {
        val port = start(service(MenuConfig.Standard.menu.withDoughToday(9)).also { it.load() })

        // NanoHTTPD's own parseBody would fall back to US-ASCII here and turn "Jörg" into "J?rg".
        val answer = request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Jörg"))

        assertEquals(201, answer.status)
        assertTrue(answer.body.contains("Jörg"))
    }

    // ─── The desk is the same one the tablet uses ───────────────────────────

    @Test
    fun aGuestOrderShowsUpInTheSameListTheTabletRenders() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(9))
        service.load()
        val port = start(service)

        val token = receipt(request(port, "/api/orders?k=$code", method = "POST", body = body(customer = "Anna"))).token

        // One service: the kitchen screen and the phone see the same order, number and dough.
        val order = service.state.value.orders.single()
        assertEquals(1, order.number)
        assertEquals("Anna", order.customer)
        assertEquals(8, service.state.value.menu.dough!!.available)
        // The same print path as a tablet order: answered at once, printed beside the response.
        withTimeout(5_000) { while (printer.markups.isEmpty()) delay(10) }
        assertEquals(1, printer.markups.size)

        service.advance(order)
        val view = statusOf(port, token)
        assertEquals("BAKING", view.mine.single().status)
        assertEquals(0, view.mine.single().ahead)
    }

    @Test
    fun onlyTheFourRoutesAnswer() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(9))
        service.load()
        val port = start(service)

        // The admin half must not be reachable from a phone, not even with the code.
        listOf("/api/admin", "/api/menu/delete", "/api/orders/clear", "/api/dough").forEach { path ->
            assertEquals(path, 404, request(port, "$path?k=$code").status)
        }
        assertEquals(405, request(port, "/api/orders?k=$code", method = "PUT").status)
        assertEquals(405, request(port, "/?k=$code", method = "POST", body = "x").status)
    }

    private companion object {
        const val PAGE = "<!doctype html><title>Testseite</title>"
    }
}
