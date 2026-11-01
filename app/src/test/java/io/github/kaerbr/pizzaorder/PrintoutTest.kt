package io.github.kaerbr.pizzaorder

import io.github.kaerbr.pizzaorder.Printout.Align
import io.github.kaerbr.pizzaorder.Printout.Line
import io.github.kaerbr.pizzaorder.Printout.QrSegment
import io.github.kaerbr.pizzaorder.Printout.Run
import io.github.kaerbr.pizzaorder.Printout.Size
import io.github.kaerbr.pizzaorder.Printout.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintoutTest {

    @Test
    fun labelLeftValueRightWithSizeAndBold() {
        val line = Printout.parse("[L]BESTELLUNG[R]<font size='big'>#007</font>").single()
        assertEquals(
            Line(listOf(
                TextSegment(Align.Left, listOf(Run("BESTELLUNG"))),
                TextSegment(Align.Right, listOf(Run("#007", size = Size.Big))),
            )),
            line,
        )
        assertEquals(
            listOf(Run("Gast "), Run("Anna", bold = true)),
            (Printout.parse("[C]Gast <b>Anna</b>").single().segments.single() as TextSegment).runs,
        )
    }

    @Test
    fun qrCodeAndEmptyLines() {
        val lines = Printout.parse("[C]<qrcode size='25'>https://example.org</qrcode>\n\n[L]\n[L]x\n[L]\n[L]")
        assertEquals(QrSegment(Align.Center, "https://example.org", 25), lines[0].segments.single())
        // Blank lines in the middle stay paper feed; the trailing feed for the cutter is dropped.
        assertEquals(4, lines.size)
        assertTrue(lines[1].segments.isEmpty() && lines[2].segments.isEmpty())
    }

    @Test
    fun everyStyleParsesWithoutStrayMarkup() {
        val order = Order(number = 7, customer = "Anna", pizza = Pizza("Funghi", Sauce("Tomate"), Pizza.toppingsOf(listOf("Pilze"))), note = "knusprig", diet = Diet.VEGETARIAN, allergens = setOf(Allergen.LACTOSE, Allergen.NUTS))
        for (style in PrintStyle.entries) {
            val text = Printout.parse(style.format(order, FunLink("x", "https://example.org")))
                .flatMap { it.segments }.filterIsInstance<TextSegment>().flatMap { it.runs }.joinToString("") { it.text }
            assertTrue(style.name, "Anna" in text && "Funghi" in text && "LAKTOSE, NÜSSE" in text)
            assertTrue(style.name, '<' !in text && "[L]" !in text && "[C]" !in text && "[R]" !in text)
        }
    }
}
