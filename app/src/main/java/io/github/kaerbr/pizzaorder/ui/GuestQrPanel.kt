package io.github.kaerbr.pizzaorder.ui

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.kaerbr.pizzaorder.GuestQr
import io.github.kaerbr.pizzaorder.LocalAddress

/**
 * The tablet's address, kept current while the panel is open.
 *
 * A DHCP lease, a hotspot switched on or a dropped WLAN changes the address behind a running server's back:
 * the socket keeps listening, the QR code on screen starts pointing at nobody. The callback is what turns
 * that into a recomposition, instead of a value read once when the panel opened.
 */
@Composable
internal fun rememberLocalIpv4(): String? {
    val context = LocalContext.current
    var address by remember { mutableStateOf(LocalAddress.ipv4(context)) }

    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            // A new lease usually arrives here, not as a new network.
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
            private fun refresh() { address = LocalAddress.ipv4(context) }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

    return address
}

/**
 * The QR code a guest scans, drawn module by module in one path.
 *
 * Always black on white and never themed: a scanner works on contrast, and a dark-mode variant in the
 * palette's brown on near-black is exactly the "it doesn't scan" that costs an evening.
 */
@Composable
internal fun GuestQrCode(url: String, modifier: Modifier = Modifier) {
    val matrix = remember(url) { GuestQr.matrix(url) }
    Canvas(modifier.semantics { contentDescription = "QR-Code für $url" }) {
        drawRect(Color.White)
        val module = minOf(size.width, size.height) / matrix.size
        val path = Path()
        matrix.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) path.addRect(Rect(x * module, y * module, (x + 1) * module, (y + 1) * module))
            }
        }
        drawPath(path, Color.Black)
    }
}
