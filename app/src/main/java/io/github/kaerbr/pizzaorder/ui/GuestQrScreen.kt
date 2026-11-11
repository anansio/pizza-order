package io.github.kaerbr.pizzaorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kaerbr.pizzaorder.GuestRuntime
import io.github.kaerbr.pizzaorder.GuestStatus
import io.github.kaerbr.pizzaorder.LocalAddress
import io.github.kaerbr.pizzaorder.PrintStatus

/**
 * The guest QR code as its own screen: one tap in the quick menu, no pattern, no admin panel.
 *
 * The admin panel is the wrong home for this. The code is what a guest is supposed to find, and it was
 * three taps and a scroll away behind the lock. Nothing on this screen is secret — the code keeps phones
 * outside the party out, not the person standing at the tablet.
 *
 * @param printStatus the state of the card print, so the button reports its own outcome like every other
 *   print button in the app.
 */
@Composable
fun GuestQrScreen(
    guest: GuestRuntime,
    printStatus: PrintStatus?,
    onPrintCard: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ip = rememberLocalIpv4()
    // The same rules as the panel: a code without a listening server or without an address would point at
    // nobody, and that is worse than a sentence saying why.
    val url = ip?.takeIf { guest.status is GuestStatus.Running }?.let { LocalAddress.url(it, guest.port, guest.code) }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Gäste-Bestellung",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )

            when {
                guest.status is GuestStatus.Failed -> Message(
                    "Der Server läuft nicht: ${(guest.status as GuestStatus.Failed).message}",
                    error = true,
                )
                guest.status !is GuestStatus.Running -> Message(
                    "Der Server ist aus. Im Admin-Panel unter „Gäste-Bestellung“ einschalten, " +
                        "dann erscheint hier der QR-Code.",
                    error = false,
                )
                ip == null -> Message(
                    "Kein WLAN und kein Hotspot. Das Tablet hängt an keinem Netz, also kann kein Handy bestellen.",
                    error = true,
                )
                url != null -> {
                    // Black on white whatever the theme: a dark-mode code in the palette's brown doesn't scan.
                    GuestQrCode(
                        url,
                        Modifier
                            .size(360.dp)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    )
                    GuestUrl(url)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Handy draufhalten. Bleibt das Tablet in der Küche: den Bon unten drucken und auf " +
                            "den Tisch legen — dieselbe Adresse, nur auf Papier.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onPrintCard(url) },
                        enabled = printStatus != PrintStatus.Printing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Icon(printStatus.icon, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(printStatus.label("QR-Bon drucken"))
                    }
                    printStatus?.let { status ->
                        if (status is PrintStatus.Failed) {
                            Text(
                                status.reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        } else if (status == PrintStatus.Printed) {
                            Text(
                                "Der Bon liegt im Drucker. Auf den Tisch legen, dann findet ihn jedes Handy.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(10.dp), modifier = Modifier.height(48.dp)) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Schließen")
            }
        }
    }
}

@Composable
private fun Message(text: String, error: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}
