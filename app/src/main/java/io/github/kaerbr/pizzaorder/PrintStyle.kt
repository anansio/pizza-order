package io.github.kaerbr.pizzaorder

/**
 * Selectable print layouts (Admin → Ausdruck), 32 chars wide.
 * DantSu markup: [L]/[C]/[R] align, [L]…[R] on one line = label left, value right,
 * <font size='big'> halves the chars per line. Decoration is plain ASCII on
 * purpose: ×, ·, box-drawing etc. depend on the printer's codepage.
 */
enum class PrintStyle(val label: String, val description: String) {

    Receipt("Beleg", "Wie ein Kassenbon: Nummer, Gast, Zeit, Positionen") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]<b>P I Z Z A   P A R T Y</b>
            |[C]forno a corrente
            |[C]================================
            |[L]BESTELLUNG[R]<font size='big'>${order.formattedNumber}</font>
            |[L]
            |[L]Gast[R]<b>${order.customer}</b>
            |[L]Zeit[R]${order.formattedTime}${aheadLine(ahead)}
            |[C]- - - - - - - - - - - - - - - -
            |[L]<font size='tall'><b>1x ${order.pizza.name}</b></font>${dietLine(order)}
            |[L]   ${order.pizza.sauce.name}
            |${order.pizza.toppings.joinToString("\n") { "[L]   + ${it.name}" }}
            |${order.note.takeIf(String::isNotBlank)?.let { "[C]- - - - - - - - - - - - - - - -\n[L]<b>NOTIZ</b>\n[L]$it" } ?: ""}
            |[C]================================
            |[C]BUON APPETITO
            |${qrBlock(link)}
            |[L]
            |[L]
            |[L]
        """.trimMargin()
    },

    ReceiptDesign("Beleg Design", "Wie ein Kassenbon: Nummer, Gast, Zeit, Positionen + Design") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]*  .  *  .  *  .  *  .  *  .  *
            |[C]<b>P I Z Z A   P A R T Y</b>
            |[C]*  .  *  .  *  .  *  .  *  .  *
            |[C]================================
            |[L]BESTELLUNG[R]<font size='big'>${order.formattedNumber}</font>
            |[L]
            |[L]Gast[R]<b>${order.customer}</b>
            |[L]Zeit[R]${order.formattedTime}${aheadLine(ahead)}
            |[C]- - - - - - - - - - - - - - - -
            |[L]<font size='tall'><b>1x ${order.pizza.name}</b></font>${dietLine(order)}
            |[L]   ${order.pizza.sauce.name}
            |${order.pizza.toppings.joinToString("\n") { "[L]   + ${it.name}" }}
            |${order.note.takeIf(String::isNotBlank)?.let { "[C]- - - - - - - - - - - - - - - -\n[L]<b>NOTIZ</b>\n[L]$it" } ?: ""}
            |[C]================================
            |[C]BUON APPETITO
            |${qrBlock(link)}
            |[L]
            |[L]
            |[L]
        """.trimMargin()
    };

    /** @param ahead open orders before this one, so the guest knows how long it'll take. */
    fun format(order: Order, link: FunLink?, ahead: Int = 0) =
        layout(order.copy(customer = order.customer.asPlainText(), note = order.note.asPlainText()), link, ahead)

    protected abstract fun layout(order: Order, link: FunLink?, ahead: Int): String
}

// Guests type these; "[R]" or "<qrcode>" would otherwise reach the printer as markup. Everything else on the
// paper comes from the admin panel. Round brackets, because the library has no escape.
internal fun String.asPlainText() = replace(Printout.alignTag) { "(${it.groupValues[1]})" }.replace('<', '(').replace('>', ')')

// "Scan me" footer; empty when Settings has no QR links.
private fun qrBlock(link: FunLink?) = link?.let {
    "[C]- - - - - - - - - - - - - - - -\n[C]Wartezeit? Scan mich!\n[C]<qrcode size='25'>${it.url}</qrcode>"
} ?: ""

// Same trick as dietLine; first in line needs no such line.
private fun aheadLine(ahead: Int) =
    if (ahead <= 0) "" else "\n[L]Vor dir[R]<b>$ahead ${if (ahead == 1) "Pizza" else "Pizzen"}</b>"

// Under the pizza name so the kitchen grabs a clean board; nothing for Diet.NONE.
// Leading "\n" instead of its own template line, so untagged pizzas don't print a blank line.
private fun dietLine(order: Order) =
    if (order.diet == Diet.NONE) "" else "\n[L]   <b>*** ${order.diet.label.uppercase()} ***</b>"
