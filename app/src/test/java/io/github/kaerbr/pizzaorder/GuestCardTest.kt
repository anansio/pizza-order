package io.github.kaerbr.pizzaorder

import io.github.kaerbr.pizzaorder.Printout.Align
import io.github.kaerbr.pizzaorder.Printout.QrSegment
import io.github.kaerbr.pizzaorder.Printout.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card that lies on the table: the QR code plus the two things a guest can type when the camera refuses.
 * Checked through [Printout], i.e. the same parse the confirmation slip draws, so it is what the paper gets.
 */
class GuestCardTest {

    private val url = LocalAddress.url("192.168.43.1", 8080, "abc123")

    private fun lines(markup: String = GuestCard.format(url)) = Printout.parse(markup)

    private fun textOf(markup: String = GuestCard.format(url)) = lines(markup)
        .flatMap { it.segments }.filterIsInstance<TextSegment>()
        .flatMap { it.runs }.joinToString("") { it.text }

    @Test
    fun theQrCodeCarriesTheWholeUrl() {
        val qr = lines().flatMap { it.segments }.filterIsInstance<QrSegment>().single()

        assertEquals(url, qr.url)
        // 40 mm of 48 mm paper: a card is read from 40–60 cm away, not held to the camera like a phone screen.
        assertTrue("QR must be big enough for a table", qr.sizeMm >= 35)
    }

    @Test
    fun addressAndCodeAreOnPaperInTypeBecauseNoCameraIsPerfect() {
        val text = textOf()

        // The URL itself would wrap on 32 characters; the two halves are what someone types.
        assertTrue(text, "192.168.43.1" in text)
        assertTrue(text, "abc123" in text)
        assertTrue("The card should say what to do", "Handy" in text)
    }

    @Test
    fun noLineOverflowsThePrinterWidth() {
        // Everything on the card is admin text, so 32 characters is a budget, not a guess: a line the printer
        // wraps in the middle looks like a bug on the one card guests actually look at.
        val longest = lines().flatMap { it.segments }.filterIsInstance<TextSegment>()
            .maxOf { seg -> seg.runs.joinToString("") { it.text }.length }
        assertTrue("longest line is $longest chars", longest <= Printout.LINE_CHARS)
    }

    @Test
    fun theCardDoesNotPromiseAnExpiryTheCodeDoesNotHave() {
        // The code is kept in preferences across restarts (yesterday's QR still works), so "nur für diesen
        // Abend" was a promise the app never kept. The card now names the lever that really ends it.
        val text = textOf()

        assertTrue(text, "Abend" !in text)
        assertTrue("The card has to say when it stops working", "code" in text.lowercase())
    }

    @Test
    fun aChangedAddressOrCodeChangesTheCard() {
        val other = GuestCard.format(LocalAddress.url("10.0.0.7", 9090, "zzz999"))

        assertTrue(other != GuestCard.format(url))
        assertTrue(textOf(other).let { "10.0.0.7" in it && "zzz999" in it })
        assertEquals("9090", other.substringAfter("10.0.0.7:").substringBefore('/'))
    }

    @Test
    fun theCardHasNoStrayMarkupForThePrinter() {
        val text = textOf()

        assertTrue('<' !in text && '>' !in text)
        assertTrue(listOf("[L]", "[C]", "[R]").none { it in text })
        // Nothing on the paper is a guest string, so nothing to escape — but the code must still be plain.
        assertTrue(text.lines().none { it.startsWith("[") })
    }

    @Test
    fun theQrSitsInTheMiddleOfThePaper() {
        val qrLine = lines().single { line -> line.segments.any { it is QrSegment } }

        assertEquals(Align.Center, qrLine.segments.single().align)
    }
}
