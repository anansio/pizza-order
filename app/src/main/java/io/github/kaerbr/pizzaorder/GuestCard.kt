package io.github.kaerbr.pizzaorder

/**
 * The card that lies on the table: a QR code a guest scans to open the order page, and the address and join
 * code in type, for a phone whose camera refuses.
 *
 * Printed through the same [PrintStyle] pipeline — DantSu markup, 32 characters per line — so it goes out of
 * the same printer as an order slip and [Printout] can draw it on screen unchanged. Nothing here comes from a
 * guest: the address, the port and the code are the admin's.
 */
object GuestCard {

    /**
     * @param url what the QR carries, i.e. [LocalAddress.url]. Printed as QR only; the parts go on paper
     *   separately, because the full URL wraps and nobody types a `?k=` by hand.
     */
    fun format(url: String): String {
        val withoutScheme = url.removePrefix("http://")
        val address = withoutScheme.substringBefore('/')
        val code = withoutScheme.substringAfter("?k=", "")
        return """
            |[C]<b>P I Z Z A   P A R T Y</b>
            |[C]Hier bestellen
            |[C]================================
            |[C]
            |[C]<qrcode size='40'>$url</qrcode>
            |[C]
            |[C]<b>Handy draufhalten</b>
            |[C]und im WLAN die Pizza wählen.
            |[C]================================
            |[C]Kein Scanner? Selbst eintippen:
            |[L]Adresse[R]$address
            |[L]Code[R]<b>$code</b>
            |[C]
            |[C]Gilt bis zum neuen Code.
            |[C]
            |[L]
            |[L]
            |[L]
        """.trimMargin()
    }
}
