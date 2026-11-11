package io.github.kaerbr.pizzaorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrderServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val printer = RecordingPrinter()

    private val ordersFile get() = File(tmp.root, "orders.json")
    private val menuFile get() = File(tmp.root, "menu.json")

    private fun service(menu: MenuConfig = MenuConfig.Standard.menu) = OrderService(
        FileStore(ordersFile, ListSerializer(Order.serializer()), emptyList()),
        FileStore(menuFile, MenuConfig.serializer(), menu),
        printer,
        scope,
    )

    private fun pizza(name: String = "Margherita") =
        MenuConfig.Standard.menu.presets.first { it.name == name }

    private fun orders(): List<Order> = Json { ignoreUnknownKeys = true }
        .decodeFromString(ListSerializer(Order.serializer()), ordersFile.readText())

    private fun menu(): MenuConfig = Json { ignoreUnknownKeys = true }
        .decodeFromString(MenuConfig.serializer(), menuFile.readText())

    /** The success case, for tests that aren't about a refusal. */
    private fun SubmitResult.placed(): Placed = assertTrue(this is Placed).let { this as Placed }

    /** Printing runs beside the call; wait for it so a print's own save can't race the file read. */
    private suspend fun awaitPrints(count: Int) = withTimeout(5_000) {
        while (printer.markups.size < count) delay(10)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun twoOrdersAtTheSameMomentGetTheirOwnNumberAndTheirOwnDoughBall() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(10))
        service.load()

        // Both start before either can finish: without one lock around number, dough and save, they read
        // the same state and one dough ball goes missing.
        val placed = listOf("Anna", "Bene").map { name ->
            async(Dispatchers.Default) { service.submit(name, pizza(), "").placed() }
        }.awaitAll()

        awaitPrints(2)

        assertEquals(listOf(1, 2), placed.map { it.order.number }.sorted())
        assertEquals(2, placed.map { it.order.id }.distinct().size)
        assertEquals(8, service.state.value.menu.dough!!.available)
        // On disk as well, and the file agrees with the state.
        assertEquals(8, menu().dough!!.available)
        assertEquals(placed.map { it.order.id }.sorted(), orders().map { it.id }.sorted())
    }

    // A saved order keeps its id. The field defaults to System.currentTimeMillis(), so a serializer that drops
    // defaults would hand two orders loaded in the same millisecond the same id — one "Weiter" tap moves both,
    // and `prints` can't tell them apart.
    @Test
    fun aReloadedOrderKeepsItsId() = runBlocking {
        val first = service(MenuConfig.Standard.menu.withDoughToday(5))
        first.load()
        val placed = listOf("Anna", "Bene").map { first.submit(it, pizza(), "").placed() }
        awaitPrints(2)

        val second = service()
        second.load()

        assertEquals(placed.map { it.order.id }.sorted(), second.state.value.orders.map { it.id }.sorted())
        assertEquals(2, second.state.value.orders.map { it.id }.distinct().size)
    }

    // Without a dough limit takeDough() returns the menu unchanged: writing menu.json for every order was pure I/O.
    @Test
    fun anOrderWithoutADoughLimitDoesNotRewriteTheMenu() = runBlocking {
        val service = service()
        service.load()

        service.submit("Anna", pizza(), "").placed()
        awaitPrints(1)

        assertTrue(!menuFile.exists())
    }

    @Test
    fun noDoughMeansNoOrder() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(1))
        service.load()

        assertTrue(service.submit("Anna", pizza(), "") is Placed)
        assertEquals(Refused(Rejection.NoDough), service.submit("Bene", pizza(), ""))

        awaitPrints(1)

        assertEquals(1, service.state.value.orders.size)
        assertEquals(0, menu().dough!!.available)
        assertEquals(1, orders().size)
        // The refused order never reached the printer either.
        assertEquals(1, printer.markups.size)
    }

    @Test
    fun noNameMeansNoOrder() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5))
        service.load()

        assertEquals(Refused(Rejection.BlankName), service.submit("   ", pizza(), ""))

        awaitPrints(0)
        delay(200)

        assertTrue(service.state.value.orders.isEmpty())
        assertEquals(5, service.state.value.menu.dough!!.available)
        assertTrue(printer.markups.isEmpty())
    }

    // The form requires a sauce, so the desk must too: a hand-written POST with an empty sauce used to
    // pass both unknownIngredientsOf() and soldOutOf(), which skip blank names.
    @Test
    fun aPizzaWithoutASauceIsRefused() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5))
        service.load()

        val bare = Pizza("", Sauce("  "), Pizza.toppingsOf(listOf("Käse")))
        assertRefused(service, service.submit("Anna", bare, ""), Rejection.NoSauce)

        assertEquals(5, service.state.value.menu.dough!!.available)
    }

    @Test
    fun aPlacedOrderIsOpenNotYetDoneAndOnDisk() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5))
        service.load()

        val placed = service.submit("  Anna  ", pizza(), "  ohne Oregano ").placed()
        awaitPrints(1)
        // The markup is recorded inside print(); the bookkeeping save comes right after it.
        withTimeout(5_000) {
            while (service.state.value.orders.single().printed.not()) delay(10)
        }
        val order = placed.order

        assertEquals("Anna", order.customer)
        assertEquals("ohne Oregano", order.note)
        assertTrue(!order.isDone())
        assertEquals(false, order.printed)
        // The caller gets what to print and where the order stands, not a slot in the shared state.
        assertTrue(placed.printout.isNotBlank())
        assertEquals(0, placed.ordersAhead)
        // The print has meanwhile marked it on disk, which is the whole point of `printed`.
        assertEquals(service.state.value.orders.single(), orders().single())
        assertTrue(orders().single().printed)
    }

    @Test
    fun advanceWalksAnOrderThroughTheKitchenAndBack() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5))
        service.load()
        val order = service.submit("Anna", pizza(), "").placed().order
        awaitPrints(1)
        assertEquals(OrderStatus.QUEUED, service.state.value.orders.single().status)

        // Tap one: into the oven. Only the oven timestamp is set, nothing else.
        service.advance(order)
        val baking = service.state.value.orders.single()
        assertEquals(OrderStatus.BAKING, baking.status)
        assertTrue(baking.bakingAt != null && baking.completedAt == null)
        assertEquals(OrderStatus.BAKING, orders().single().status)

        // Tap two: out of the oven, done.
        service.advance(service.state.value.orders.single())
        val done = service.state.value.orders.single()
        assertEquals(OrderStatus.READY, done.status)
        assertTrue(done.isDone() && done.bakingAt != null)
        assertEquals(OrderStatus.READY, orders().single().status)

        // Third tap takes back the "done", not the whole evening: the order is back in the oven with its
        // baking time, so the guest never sees it fall out of the queue and the bake time stays measurable.
        service.advance(service.state.value.orders.single())
        val back = service.state.value.orders.single()
        assertEquals(OrderStatus.BAKING, back.status)
        assertEquals(baking.bakingAt, back.bakingAt)
        assertTrue(back.bakingAt != null && back.completedAt == null)
        assertEquals(OrderStatus.BAKING, orders().single().status)
    }

    // orders.json from before `bakingAt` existed: such an order is waiting, not in the oven by accident.
    @Test
    fun anOrderFromAnOlderFileStartsAsQueued() = runBlocking {
        ordersFile.writeText(
            """[{"id":1,"number":7,"customer":"Anna","pizza":{"name":"Margherita","sauce":{"name":"Tomate"},"toppings":[]}}]"""
        )
        val service = service()
        service.load()

        assertEquals(OrderStatus.QUEUED, service.state.value.orders.single().status)
    }

    // orders.json from before `printed` existed: those orders are long past and must not come back as
    // failed printouts.
    @Test
    fun anOldOrdersFileLoadsUnchanged() = runBlocking {
        ordersFile.writeText(
            """[{"id":1,"number":7,"customer":"Anna","pizza":{"name":"Margherita","sauce":{"name":"Tomate"},"toppings":[]}}]"""
        )
        val service = service()
        service.load()

        val loaded = service.state.value.orders.single()
        assertEquals(7, loaded.number)
        assertTrue(loaded.printed)
        assertEquals(loaded, orders().single())
    }

    @Test
    fun clearAllStartsANewEvening() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5).withSoldOut("Oliven", true))
        service.load()
        service.submit("Anna", pizza(), "").placed()
        awaitPrints(1)
        assertEquals(4, service.state.value.menu.dough!!.available)

        service.clearAll()

        assertTrue(service.state.value.orders.isEmpty())
        assertTrue(orders().isEmpty())
        // Dough back to tonight's count, what ran out available again.
        assertEquals(5, menu().dough!!.available)
        assertTrue(menu().soldOut.isEmpty())
    }

    // Clearing starts a new evening, but the limit is a rule for the kitchen, not something the evening used up.
    @Test
    fun clearAllKeepsTheOpenOrderLimit() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5).withMaxOpenOrders(2))
        service.load()
        service.submit("Anna", pizza(), "").placed()
        awaitPrints(1)

        service.clearAll()

        assertEquals(2, menu().maxOpenOrders)
        assertEquals(2, service.state.value.menu.maxOpenOrders)
    }

    // A menu file written by hand or imported can carry a 0 limit: it must read as "no limit", not as a
    // kitchen that refuses every order with "Gerade sind schon 0 Bestellungen offen".
    @Test
    fun aZeroLimitFromAFileDoesNotBlockOrders() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5).copy(maxOpenOrders = 0))
        service.load()

        val placed = service.submit("Anna", pizza(), "").placed()

        assertEquals(1, placed.order.number)
        assertEquals(1, service.state.value.orders.size)
    }

    // ─── S3: what the desk refuses, and that nothing is written for it ──────

    /** Every refusal has the same side effect: no order, no dough gone, nothing on disk, no paper. */
    private suspend fun assertRefused(service: OrderService, result: SubmitResult, reason: Rejection) {
        assertEquals(Refused(reason), result)
        assertTrue("Nichts darf geschrieben sein", !ordersFile.exists() || orders().isEmpty())
        assertTrue("Kein Ausdruck", printer.markups.isEmpty())
    }

    @Test
    fun anIngredientThatIsNotOnTheMenuIsRefused() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5))
        service.load()

        // Built by hand, as an HTTP request would: the form can't produce this.
        val invented = Pizza("Fantasie", Sauce("Ketchup"), Pizza.toppingsOf(listOf("Käse", "Ananas")))
        assertRefused(service, service.submit("Anna", invented, ""), Rejection.UnknownIngredient(listOf("Ketchup", "Ananas")))

        assertEquals(5, service.state.value.menu.dough!!.available)
    }

    @Test
    fun aSoldOutIngredientIsRefusedEvenOnAPreset() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5).withSoldOut("Käse", true))
        service.load()

        assertRefused(service, service.submit("Anna", pizza("Margherita"), ""), Rejection.SoldOut(listOf("Käse")))
        assertEquals(5, service.state.value.menu.dough!!.available)
    }

    @Test
    fun completedOrdersDoNotCountAgainstTheLimit() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5).withMaxOpenOrders(1))
        service.load()

        val first = service.submit("Anna", pizza(), "").placed()
        assertEquals(Refused(Rejection.TooManyOpen(1)), service.submit("Bene", pizza(), ""))

        // Working through the queue frees the slot again; nothing was refused for good.
        service.advance(first.order)
        service.advance(service.state.value.orders.single())
        assertTrue(service.submit("Bene", pizza(), "") is Placed)
        awaitPrints(2)
    }

    @Test
    fun theLimitHoldsWhenTwoGuestsOrderAtTheSameMoment() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(10).withMaxOpenOrders(1))
        service.load()

        // Both read the same open count unless the check shares the lock with number and save.
        val results = listOf("Anna", "Bene").map { name ->
            async(Dispatchers.Default) { service.submit(name, pizza(), "") }
        }.awaitAll()

        assertEquals(1, results.count { it is Placed })
        assertEquals(listOf(Refused(Rejection.TooManyOpen(1))), results.filterIsInstance<Refused>())
        awaitPrints(1)
        assertEquals(1, service.state.value.orders.size)
        assertEquals(9, service.state.value.menu.dough!!.available)
    }

    // The form cuts guest input as it is typed; the desk must do the same, because an HTTP body skips the form.
    @Test
    fun longGuestFieldsAreCutNotRefused() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5))
        service.load()

        val placed = service.submit("A".repeat(40), pizza(), "B".repeat(400)).placed()

        assertEquals(NAME_MAX, placed.order.customer.length)
        assertEquals(NOTE_MAX, placed.order.note.length)
        awaitPrints(1)
    }

    // Which reason a guest reads when several apply: the one they can do something about comes first.
    @Test
    fun theRefusalNamesTheMostUsefulReason() = runBlocking {
        // An invented ingredient is refused before the dough is even looked at.
        val invented = Pizza("X", Sauce("Ketchup"), Pizza.toppingsOf(emptyList()))
        val noDough = service(MenuConfig.Standard.menu.withDoughToday(0))
        noDough.load()
        assertEquals(Refused(Rejection.UnknownIngredient(listOf("Ketchup"))), noDough.submit("Anna", invented, ""))

        // Dough gone beats the temporary limit: the evening is over, not merely busy.
        val bothLimits = service(MenuConfig.Standard.menu.withDoughToday(1).withMaxOpenOrders(1))
        bothLimits.load()
        bothLimits.submit("Anna", pizza(), "").placed()
        assertEquals(Refused(Rejection.NoDough), bothLimits.submit("Bene", pizza(), ""))
        awaitPrints(1)
    }
}
