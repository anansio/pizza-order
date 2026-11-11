package io.github.kaerbr.pizzaorder

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The tablet's own address in the local network — the half of the QR code that changes when the router
 * hands out a new lease.
 *
 * Only IPv4: a guest types nothing (the QR carries the URL), but the printed fallback address and every
 * "das stimmt nicht" report in the wild is IPv4. An IPv6 link-local address can't even be typed.
 */
object LocalAddress {

    /** One interface with its addresses, as the system reports it; a test hands in its own list. */
    data class Interface(val name: String, val addresses: List<InetAddress>)

    /**
     * The first usable IPv4 among [addresses], or null.
     *
     * Loopback is the tablet talking to itself and a link-local (169.254.x.x) address means DHCP failed;
     * neither is reachable from a phone, so both are worse than showing "kein WLAN". A carrier-grade NAT
     * address (100.64.0.0/10) is the same story one step further out: it sits inside the mobile network.
     */
    fun ipv4Of(addresses: List<InetAddress>): String? = addresses
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isReachableFromAPhone() }
        ?.hostAddress

    /**
     * The default network's address, else the hotspot's; null when neither has one a phone could use.
     *
     * @param defaultAddresses what [ConnectivityManager] reports for the active network — empty when there is
     *   none and empty for mobile data, whose address is behind the carrier's NAT.
     * @param interfaces every interface of the device, which is where a hotspot shows up.
     */
    fun addressOf(defaultAddresses: List<InetAddress>, interfaces: List<Interface>): String? =
        ipv4Of(defaultAddresses) ?: hotspotIpv4(interfaces)

    /**
     * The hotspot's address, or null.
     *
     * A hotspot is not a network in the [ConnectivityManager] sense: it has no default route, so it appears as
     * no `Network` at all, only as an interface under a name that differs per vendor and chip. Hence the
     * fallback and the name ranking. Mobile-data interfaces are skipped: a tablet with mobile data on is
     * exactly the case where their address looks right and leads nowhere.
     */
    fun hotspotIpv4(interfaces: List<Interface>): String? = interfaces
        .filterNot { CELLULAR_NAME.matches(it.name) }
        .mapNotNull { iface ->
            ipv4Of(iface.addresses)?.takeIf(::isPrivate)?.let { address -> Candidate(address, iface.name) }
        }
        .minWithOrNull(compareBy({ rank(it.address, it.interfaceName) }, { it.interfaceName }))
        ?.address

    /** All addresses of the active network, else of the hotspot; null when there is nothing a phone can reach. */
    fun ipv4(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return addressOf(defaultAddresses(manager), interfaces())
    }

    /** What the QR code points at; the join code is what keeps a neighbour out. */
    fun url(ip: String, port: Int, code: String): String = "http://$ip:$port/?k=$code"

    private fun defaultAddresses(manager: ConnectivityManager): List<InetAddress> = runCatching {
        val network = manager.activeNetwork ?: return@runCatching emptyList()
        // Mobile data is the default network on a tablet that is not on WiFi. Its address is inside the
        // carrier's network, so the panel would draw a clean, scannable code that leads nowhere.
        val cellular = manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        if (cellular) emptyList()
        else manager.getLinkProperties(network)?.linkAddresses?.map { it.address }.orEmpty()
    }.getOrNull().orEmpty()

    private fun interfaces(): List<Interface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { iface ->
            Interface(iface.name.orEmpty(), iface.inetAddresses.toList())
        }
    }.getOrNull().orEmpty()

    private data class Candidate(val address: String, val interfaceName: String)

    // Access-point name first, then a gateway-like .1. Only private addresses are candidates at all: an AP
    // always sits on RFC 1918, and Android's own `dummy0` (192.0.0.4 and the like) must never win the race.
    private fun rank(address: String, interfaceName: String): Int =
        if (AP_NAME.matches(interfaceName)) 0 else if (address.endsWith(".1")) 1 else 2

    private fun Inet4Address.isReachableFromAPhone(): Boolean =
        !isLoopbackAddress && !isLinkLocalAddress && !isAnyLocalAddress && !isCarrierNat(hostAddress)

    /** 100.64.0.0/10, the range a carrier uses for the address a phone sees on mobile data. */
    private fun isCarrierNat(address: String?): Boolean {
        val parts = address?.split('.')?.mapNotNull(String::toIntOrNull) ?: return false
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }

    /** RFC 1918. Not a guarantee of reachability, only a hint at which address is worth showing. */
    private fun isPrivate(address: String): Boolean {
        val parts = address.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    /** Names an access point gets: `ap0`, `swlan0`, `softap0`, `tether0`, and `wlan1`+ for a second radio. */
    private val AP_NAME = Regex("""(ap\d*|swlan\d*|softap\d*|tether\d*|wlan[1-9]\d*)""")

    /**
     * `rmnet`/`ccmni`/`pdp`/`wwan` are the mobile-data interfaces Android hands out, with a vendor suffix
     * (`rmnet_data0`, `rmnet0`, `pdp_ip0`); `clat` is the IPv4 shim on an IPv6-only carrier.
     */
    private val CELLULAR_NAME = Regex("""(rmnet|ccmni|pdp|wwan|clat)\w*""")
}
