package io.github.kaerbr.pizzaorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.kaerbr.pizzaorder.GuestQr
import kotlinx.coroutines.delay
import io.github.kaerbr.pizzaorder.LocalAddress

/**
 * The tablet's address, kept current while the panel or the QR screen is open.
 *
 * A DHCP lease, a dropped WLAN or a hotspot switched on changes the address behind a running server's back:
 * the socket keeps listening, the QR code on screen starts pointing at nobody. The callback is what turns a
 * new default network into a recomposition, instead of a value read once when the view opened.
 *
 * A hotspot is the exception the callback cannot cover: it is no `Network` at all (see [LocalAddress]), so
 * nothing is announced when it appears and the fallback list changes silently. Hence the slow poll — only
 * while this is composed, and cheap: a `NetworkInterface` walk, no socket and no radio.
 */
@Composable
internal fun rememberLocalIpv4(): String? {
    val context = LocalContext.current
    var address by remember { mutableStateOf(LocalAddress.ipv4(context)) }

    LaunchedEffect(context) {
        while (true) {
            delay(2_000)
            address = LocalAddress.ipv4(context)
        }
    }

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
 * The guest URL as text with a copy button.
 *
 * On a tablet the URL is the fallback for a phone whose camera won't scan; typing `192.168.178.23:8080/?k=x7k2m9`
 * by hand is the part that fails. Copying it into a messenger is how the link actually reaches a guest, so the
 * button has to hand over the exact string the QR code encodes.
 *
 * The system clipboard, not Compose's: `LocalClipboardManager` is deprecated and `LocalClipboard` wants a
 * `ClipEntry`. Three lines through [android.content.ClipboardManager] do the same on every supported version.
 */
@Composable
internal fun GuestUrl(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember(url) { mutableStateOf(false) }
    // Android 13+ shows its own confirmation overlay; a second one on screen would just flicker.
    val showLabel = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(2_000)
        copied = false
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            url,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Gäste-Link", url))
            copied = true
        }) {
            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (copied && showLabel) "Kopiert" else "Kopieren")
        }
    }
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
