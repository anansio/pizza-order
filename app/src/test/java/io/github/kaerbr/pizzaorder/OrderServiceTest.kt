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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [JsonStore] without Android: same JSON, same "keep the last good file" idea, plain java.io.File. */
private class FileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
) : JsonFile<T> {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): T =
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { defaultValue }
        else defaultValue

    override suspend fun save(data: T) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, data))
    }
}

/** Counts printouts instead of printing them; the print path itself is not what S1 changes. */
private class RecordingPrinter : PrinterPort {
    val markups = mutableListOf<String>()
    override suspend fun print(markup: String): Result<Unit> {
        markups += markup
        return Result.success(Unit)
    }
}

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
            async(Dispatchers.Default) { service.submit(name, pizza(), "") }
        }.awaitAll()

        awaitPrints(2)

        assertEquals(listOf(1, 2), placed.map { it!!.order.number }.sorted())
        assertEquals(2, placed.map { it!!.order.id }.distinct().size)
        assertEquals(8, service.state.value.menu.dough!!.available)
        // On disk as well, and the file agrees with the state.
        assertEquals(8, menu().dough!!.available)
        assertEquals(placed.map { it!!.order.id }.sorted(), orders().map { it.id }.sorted())
    }

    // Without a dough limit takeDough() returns the menu unchanged: writing menu.json for every order was pure I/O.
    @Test
    fun anOrderWithoutADoughLimitDoesNotRewriteTheMenu() = runBlocking {
        val service = service()
        service.load()

        service.submit("Anna", pizza(), "")!!
        awaitPrints(1)

        assertTrue(!menuFile.exists())
    }

    @Test
    fun noDoughMeansNoOrder() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(1))
        service.load()

        assertTrue(service.submit("Anna", pizza(), "") != null)
        assertNull(service.submit("Bene", pizza(), ""))

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

        assertNull(service.submit("   ", pizza(), ""))

        awaitPrints(0)
        delay(200)

        assertTrue(service.state.value.orders.isEmpty())
        assertEquals(5, service.state.value.menu.dough!!.available)
        assertTrue(printer.markups.isEmpty())
    }

    @Test
    fun aPlacedOrderIsOpenNotYetDoneAndOnDisk() = runBlocking {
        val service = service(MenuConfig.Standard.menu.withDoughToday(5))
        service.load()

        val placed = service.submit("  Anna  ", pizza(), "  ohne Oregano ")!!
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
        val order = service.submit("Anna", pizza(), "")!!.order
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
        service.submit("Anna", pizza(), "")!!
        awaitPrints(1)
        assertEquals(4, service.state.value.menu.dough!!.available)

        service.clearAll()

        assertTrue(service.state.value.orders.isEmpty())
        assertTrue(orders().isEmpty())
        // Dough back to tonight's count, what ran out available again.
        assertEquals(5, menu().dough!!.available)
        assertTrue(menu().soldOut.isEmpty())
    }
}
