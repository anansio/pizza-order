package io.github.kaerbr.pizzaorder

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Address and QR code without a tablet: which address the code gets, and whether a scanner can read back
 * what the panel draws.
 *
 * The QR side is checked by decoding the matrix again — a code that "looks right" but carries the wrong URL
 * is exactly the failure a guest notices, not the kitchen.
 */
class GuestNetworkTest {

    // ─── Adresse ────────────────────────────────────────────────────────────

    @Test
    fun theFirstReachableIpv4Wins() {
        val addresses = listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("169.254.10.20"),
            InetAddress.getByName("192.168.1.42"),
        )

        assertEquals("192.168.1.42", LocalAddress.ipv4Of(addresses))
    }

    @Test
    fun withoutAReachableIpv4ThereIsNoAddress() {
        // Loopback is the tablet talking to itself, 169.254 means DHCP failed: neither is a phone's way in.
        val addresses = listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("169.254.10.20"))

        assertNull(LocalAddress.ipv4Of(addresses))
        assertNull(LocalAddress.ipv4Of(emptyList()))
    }

    @Test
    fun ipv6IsNotAnAddressAGuestCanUse() {
        // A guest types nothing (the QR carries the URL), but a printed fallback and every "das geht nicht"
        // report in the wild is IPv4; an IPv6 address can't even be typed on a phone keypad comfortably.
        assertNull(LocalAddress.ipv4Of(listOf(InetAddress.getByName("2001:db8::1"))))
        assertNull(LocalAddress.ipv4Of(listOf(InetAddress.getByName("::1"))))
    }

    @Test
    fun carrierNatIsNoAddressAPhoneCanReach() {
        // 100.64.0.0/10: the address a carrier gives the tablet on mobile data. The phone would dial into
        // the carrier's network, not the party.
        assertNull(LocalAddress.ipv4Of(listOf(InetAddress.getByName("100.64.3.7"))))
        assertEquals(
            "192.168.1.42",
            LocalAddress.ipv4Of(listOf(InetAddress.getByName("100.100.1.1"), InetAddress.getByName("192.168.1.42"))),
        )
    }

    // ─── Hotspot ────────────────────────────────────────────────────────────

    private fun iface(name: String, address: String) =
        LocalAddress.Interface(name, listOf(InetAddress.getByName(address)))

    @Test
    fun aHotspotWithoutADefaultNetworkStillHasAnAddress() {
        // The tablet shares its own hotspot and is not on any WiFi: ConnectivityManager knows no network with
        // an IPv4, but the AP interface is there and is exactly what the guests have to dial.
        val address = LocalAddress.addressOf(
            defaultAddresses = emptyList(),
            interfaces = listOf(iface("lo", "127.0.0.1"), iface("ap0", "192.168.43.1")),
        )

        assertEquals("192.168.43.1", address)
    }

    @Test
    fun theAccessPointNameWinsOverAnotherPrivateAddress() {
        // Two private addresses, one of them on the AP interface: the AP's is the one guests reach.
        val address = LocalAddress.addressOf(
            defaultAddresses = emptyList(),
            interfaces = listOf(iface("eth0", "10.1.2.3"), iface("swlan0", "192.168.232.1")),
        )

        assertEquals("192.168.232.1", address)
    }

    @Test
    fun aGatewayLikeAddressWinsWithoutAnAccessPointName() {
        // Vendor names vary (tether0, softap0, wlan1); without a known name, the .1 is the DHCP gateway.
        val address = LocalAddress.addressOf(
            defaultAddresses = emptyList(),
            interfaces = listOf(iface("dummy0", "192.168.9.44"), iface("dummy1", "192.168.9.1")),
        )

        assertEquals("192.168.9.1", address)
    }

    @Test
    fun mobileDataNeverBecomesTheGuestAddress() {
        // Mobile data on and no WiFi is the standard tablet. Its address scans fine and leads nowhere,
        // so the panel must say "kein WLAN" instead. The transport check in ipv4(context) already leaves
        // defaultAddresses empty; the interface fallback must not pick the same address up again.
        assertNull(
            LocalAddress.addressOf(
                defaultAddresses = emptyList(),
                interfaces = listOf(iface("rmnet_data0", "10.12.34.56"), iface("lo", "127.0.0.1")),
            ),
        )
    }

    @Test
    fun everyMobileDataInterfaceNameIsSkipped() {
        // Vendors: Qualcomm rmnet, MediaTek ccmni/pdp, Intel wwan, plus clat as the IPv4 shim over IPv6.
        val names = listOf("rmnet_data1", "ccmni1", "pdp0", "wwan0", "clat4")
        names.forEach { name ->
            assertNull(name, LocalAddress.hotspotIpv4(listOf(iface(name, "10.20.30.40"))))
        }
    }

    @Test
    fun theDefaultNetworkBeatsTheHotspot() {
        // Both present (WiFi plus hotspot): the address every guest got over DHCP is the WiFi one.
        val address = LocalAddress.addressOf(
            defaultAddresses = listOf(InetAddress.getByName("192.168.1.42")),
            interfaces = listOf(iface("ap0", "192.168.43.1")),
        )

        assertEquals("192.168.1.42", address)
    }

    @Test
    fun aPublicInterfaceIsNotAnAddressForGuests() {
        // Android keeps `dummy0` on 192.0.0.4 (a public range); picking it would print a code into the
        // internet. An AP always sits on RFC 1918, so only private addresses count as the hotspot.
        assertNull(
            LocalAddress.addressOf(
                defaultAddresses = emptyList(),
                interfaces = listOf(iface("dummy0", "192.0.0.4"), iface("lo", "127.0.0.1")),
            ),
        )
    }

    @Test
    fun nothingUsableMeansNoAddress() {
        assertNull(
            LocalAddress.addressOf(
                emptyList(),
                listOf(iface("lo", "127.0.0.1"), iface("dummy0", "169.254.3.4")),
            ),
        )
    }

    @Test
    fun theUrlCarriesPortAndCode() {
        assertEquals(
            "http://192.168.1.42:8080/?k=abc123",
            LocalAddress.url("192.168.1.42", 8080, "abc123"),
        )
    }

    // ─── QR-Code ────────────────────────────────────────────────────────────

    @Test
    fun theDrawnMatrixReadsBackAsTheSameUrl() {
        val url = LocalAddress.url("192.168.1.42", 8080, "abc123")

        assertEquals(url, decode(GuestQr.matrix(url)))
    }

    @Test
    fun aLongerCodeStillFits() {
        // A rotated code is the same length, but a hostname or a longer port path must not silently truncate.
        val url = "http://192.168.100.200:65535/?k=abcdef"

        assertEquals(url, decode(GuestQr.matrix(url)))
    }

    @Test
    fun theQuietZoneKeepsTheCodeOffTheEdge() {
        val matrix = GuestQr.matrix(LocalAddress.url("10.0.0.5", 8080, "test12"))

        // Four modules of background on every side; a scanner needs that margin to find the code at all.
        val quiet = GuestQr.QUIET_ZONE
        assertTrue((0 until quiet).none { i -> matrix[i].any { it } })
        assertTrue((0 until quiet).none { i -> matrix[matrix.lastIndex - i].any { it } })
        assertTrue(matrix.all { row -> row.take(quiet).none { it } && row.takeLast(quiet).none { it } })
        assertEquals(matrix.size, matrix[0].size)

        // The code itself is still in there: an all-white matrix would pass every edge check above.
        assertTrue(matrix.any { row -> row.any { it } })
        assertTrue(matrix.any { row -> row.any { !it } })
    }

    /** Real decoding through zxing, not an eyeball check: this is what a phone's camera does. */
    private fun decode(matrix: Array<BooleanArray>): String {
        val pixels = Array(matrix.size) { y -> IntArray(matrix.size) { x -> if (matrix[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() } }
        val source = RGBLuminanceSource(matrix.size, matrix.size, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    /**
     * zxing's [com.google.zxing.RGBLuminanceSource] takes one flat array; this keeps the test readable and
     * stays in the same package, so no dependency is added for it.
     */
    private class RGBLuminanceSource(width: Int, height: Int, pixels: Array<IntArray>) :
        com.google.zxing.LuminanceSource(width, height) {

        private val luminances = ByteArray(width * height).also { out ->
            var i = 0
            for (y in 0 until height) for (x in 0 until width) {
                val argb = pixels[y][x]
                // Standard luminance weights; black is 0, white stays 255, so binarising stays trivial.
                out[i++] = (((argb shr 16 and 0xFF) * 33 + (argb shr 8 and 0xFF) * 34 + (argb and 0xFF) * 33) / 100).toByte()
            }
        }

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            luminances.copyInto(out, 0, y * width, (y + 1) * width)
            return out
        }

        override fun getMatrix(): ByteArray = luminances
    }
}
