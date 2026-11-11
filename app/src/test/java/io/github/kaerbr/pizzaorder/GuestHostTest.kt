package io.github.kaerbr.pizzaorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket

/**
 * The switch's state machine without Android: what the panel reads after a start that failed, and what
 * survives the foreground service being torn down. [GuestPreferences] is a plain map here.
 */
class GuestHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class Prefs(
        override var enabled: Boolean = false,
        override var port: Int = GuestSettings.DEFAULT_PORT,
    ) : GuestPreferences {
        override var code: String = "test12"
    }

    private fun host(
        prefs: Prefs = Prefs(),
        permissionMissing: Boolean = false,
    ) = GuestHost(
        OrderService(
            FileStore(File(tmp.root, "orders.json"), ListSerializer(Order.serializer()), emptyList()),
            FileStore(File(tmp.root, "menu.json"), MenuConfig.serializer(), MenuConfig.Standard.menu),
            RecordingPrinter(),
            scope,
        ),
        prefs,
        { "<!doctype html>" },
        { permissionMissing },
    )

    @Test
    fun aFailedStartLeavesTheSwitchOffAndSaysWhy() {
        // A port somebody else holds: the bind fails the same way "Port belegt" does on the tablet.
        val taken = ServerSocket(0)
        try {
            val prefs = Prefs()
            val host = host(prefs)

            assertFalse(host.start(taken.localPort))

            assertFalse("Ein Start, der nichts hört, darf nicht als 'an' gelten", prefs.enabled)
            assertFalse(host.runtime.value.enabled)
            val status = host.runtime.value.status
            assertTrue(status is GuestStatus.Failed)
            assertTrue((status as GuestStatus.Failed).message.contains("belegt"))
        } finally {
            taken.close()
        }
    }

    @Test
    fun theReasonSurvivesTheServiceBeingTornDown() {
        val taken = ServerSocket(0)
        try {
            val host = host()
            host.start(taken.localPort)
            val reason = host.runtime.value.status

            // What GuestService.onDestroy() does after stopSelf(); "Aus: …" would hide the reason.
            host.shutdown()

            assertEquals(reason, host.runtime.value.status)
        } finally {
            taken.close()
        }
    }

    @Test
    fun aStopByAndroidKeepsTheSwitchOnSoTheServerComesBack() {
        val prefs = Prefs()
        val host = host(prefs)
        val port = ServerSocket(0).use { it.localPort }

        assertTrue(host.start(port))
        assertTrue("Der Admin hat eingeschaltet, das gehört gespeichert", prefs.enabled)

        host.shutdown()

        assertTrue(prefs.enabled)
        assertEquals(GuestStatus.Off, host.runtime.value.status)
    }

    @Test
    fun withoutTheLocalNetworkPermissionNothingStarts() {
        val prefs = Prefs()
        val host = host(prefs, permissionMissing = true)

        assertFalse(host.start(ServerSocket(0).use { it.localPort }))

        assertFalse(prefs.enabled)
        val status = host.runtime.value.status
        assertTrue(status is GuestStatus.Failed)
        assertTrue((status as GuestStatus.Failed).message.contains("lokale Netz"))
    }

    @Test
    fun aChangedPortRestartsInPlaceAndStartingTwiceKeepsRunning() {
        val prefs = Prefs()
        val host = host(prefs)
        val first = ServerSocket(0).use { it.localPort }
        val second = ServerSocket(0).use { it.localPort }

        assertTrue(host.start(first))
        assertTrue(host.start(second))
        assertTrue(prefs.enabled)
        assertEquals(GuestStatus.Running(second), host.runtime.value.status)
        // Starting again on the port that already listens keeps it up, it does not come back as Failed.
        assertTrue(host.start(second))
        assertEquals(GuestStatus.Running(second), host.runtime.value.status)
        host.stop()
    }

    @Test
    fun stopClearsTheAdminChoice() {
        val prefs = Prefs()
        val host = host(prefs)
        host.start(ServerSocket(0).use { it.localPort })

        host.stop()

        assertFalse(prefs.enabled)
        assertEquals(GuestRuntime(enabled = false, port = prefs.port, status = GuestStatus.Off, code = "test12"), host.runtime.value)
    }
}
