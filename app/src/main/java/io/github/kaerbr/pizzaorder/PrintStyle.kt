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
            |[L]<font size='tall'><b>1x ${order.pizza.name}</b></font>${dietLine(order)}${allergenLine(order)}
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
            |[L]<font size='tall'><b>1x ${order.pizza.name}</b></font>${dietLine(order)}${allergenLine(order)}
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

    /*Kitchen("Küche", "Riesige Nummer und Pizza, lesbar vom Ofen aus") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]<font size='big'>${order.formattedNumber}</font>
            |[C]<font size='wide'><b>${order.customer.uppercase()}</b></font>
            |[C]${order.formattedTime}
            |[C]================================
            |[L]<font size='big'>${order.pizza.name.uppercase()}</font>
            |[L]<font size='tall'><b>${order.pizza.sauce.name}</b></font>
            |${order.pizza.toppings.joinToString("\n") { "[L]<font size='tall'><b>+ ${it.name.uppercase()}</b></font>" }}
            |${order.note.takeIf(String::isNotBlank)?.let { "[C]================================\n[L]<font size='tall'><b>! $it</b></font>" } ?: ""}
            |[C]================================
            |[L]
            |[L]
        """.trimMargin()
    },

    Compact("Kompakt", "Drei Zeilen, spart Papier") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[L]<b>${order.formattedNumber} ${order.customer}</b>[R]${order.formattedTime}
            |[L]<b>${order.pizza.name}</b>: ${(listOf(order.pizza.sauce.name) + order.pizza.toppings.map { it.name }).joinToString(", ")}
            |${order.note.takeIf(String::isNotBlank)?.let { "[L]Notiz: $it" } ?: ""}
            |[C]--------------------------------
            |[L]
        """.trimMargin()
    },

    Party("Fest", "Verspielt mit Rahmen und Pizza-Bild") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]*  .  *  .  *  .  *  .  *  .  *
            |[C]<b>~ PIZZA PARTY ~</b>
            |[C]*  .  *  .  *  .  *  .  *  .  *
            |[L]
            |[C]Bestellung <font size='big'>${order.formattedNumber}</font>
            |[L]
            |[C]Für <b>${order.customer}</b>
            |[C]<font size='tall'><b>${order.pizza.name}</b></font>
            |[C]${(listOf(order.pizza.sauce.name) + order.pizza.toppings.map { it.name }).joinToString(" + ")}
            |${order.note.takeIf(String::isNotBlank)?.let { "[L]\n[C]\"$it\"" } ?: ""}
            |[L]
            |[C]     _.-~~~~-._
            |[C]   .' o   .  o '.
            |[C]  /  .  o   .   \
            |[C] |  o  .  o  . o |
            |[C]  \  .  o  .  o /
            |[C]   '._  .  o _.'
            |[C]      '~~~~~~'
            |[L]
            |[C]${order.formattedTime} - Buon appetito!
            |[L]
            |[L]
            |[L]
        """.trimMargin()
    },

    // design/receipt.html — the "one-pizza slip": logo, who it's for, the pizza
    // big, ingredients one per line, then a loud claim number.
    Nero("Nero", "Pizza-Zettel: Zutaten einzeln, große Abholnummer") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]  .-~~-.
            |[C] / o  o \
            |[C]|        |
            |[C] \  o   /
            |[C]  '-..-'
            |[C]<font size='wide'><b>N E R O</b></font>
            |[C]pizza night
            |[C]--------------------------------
            |[L]<b>für</b>
            |[L]<font size='tall'><b>${order.customer}</b></font>
            |[L]${order.formattedNumber}[R]bestellt ${order.formattedTime}
            |[C]--------------------------------
            |[L]<font size='tall'><b>${order.pizza.name}</b></font>
            |[L]
            |[L]<b>drauf</b>
            |[L] - ${order.pizza.sauce.name.lowercase()}
            |${order.pizza.toppings.joinToString("\n") { "[L] - ${it.name.lowercase()}" }}
            |${order.note.takeIf(String::isNotBlank)?.let { "[L]\n[L]<b>extra</b>\n[L]<b>$it</b>" } ?: ""}
            |[C]--------------------------------
            |[C]<b>deine nummer</b>
            |[C]<font size='big'>${order.number}</font>
            |[C]ruf sie am ofen
            |[C]--------------------------------
            |[C]<font size='wide'><b>grazie!</b></font>
            |[C]iss sie, solange sie heiß ist
            |[L]
            |[L]
            |[L]
        """.trimMargin()
    },

    // Compact receipt + the QR code to something pizza-silly.
    Qr("QR-Lacher", "Kurzer Ausdruck mit QR-Code zu einem Pizza-Witz zum Scannen") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]<b>P I Z Z A   P A R T Y</b>
            |[C]================================
            |[L]<b>${order.formattedNumber} ${order.customer}</b>[R]${order.formattedTime}
            |[L]
            |[L]<font size='tall'><b>${order.pizza.name}</b></font>
            |[L]   ${order.pizza.sauce.name}
            |${order.pizza.toppings.joinToString("\n") { "[L]   + ${it.name}" }}
            |${order.note.takeIf(String::isNotBlank)?.let { "[L]\n[L]<b>NOTIZ:</b> $it" } ?: ""}
            |${qrBlock(link)}
            |[L]
            |[L]
            |[L]
        """.trimMargin()
    },

    // design/bon.html — inverted header bar, dotted label/value rows, check box.
    // ponytail: no inverted bg-black bar, cheap thermal heads print large black areas only grey.
    Bon("Bon", "Kopf mit Doppelrahmen, Punktlinien, Ankreuzfeld und QR-Code") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]================================
            |[C]<font size='wide'><b>(o) PIZZA PARTY</b></font>
            |[C]HAUSGEMACHT - FÜR DICH
            |[C]================================
            |[L]
            |${dotRow("Bestell-Nr.", order.formattedNumber)}
            |${dotRow("Gast", order.customer)}
            |${dotRow("Zeit", order.formattedTime)}
            |[C]________________________________
            |[L]
            |[L]<b>B E S T E L L U N G</b>
            |[L]<font size='tall'><b>${order.pizza.name}</b></font>
            |[L]
            |[L]<b>SAUCE</b>
            |[L]${order.pizza.sauce.name}
            |[L]
            |[L]<b>BELÄGE</b>
            |${order.pizza.toppings.joinToString("\n") { "[L]- ${it.name}" }.ifEmpty { "[L]- keine" }}
            |${order.note.takeIf(String::isNotBlank)?.let { "[L]\n[L]<b>NOTIZ</b>\n[L]$it" } ?: ""}
            |[L]
            |[C]________________________________
            |${link?.let { "[L]\n[C]<qrcode size='25'>${it.url}</qrcode>" } ?: ""}
            |[L]
            |[C]<b>Danke & guten Appetit!</b>
            |[L]
            |[L]
        """.trimMargin()
    },

    Classic("Klassisch", "Der ursprüngliche Ausdruck mit Ofen-Bild") {
        override fun layout(order: Order, link: FunLink?, ahead: Int) = """
            |[C]<font size='big'>${order.formattedNumber}</font>
            |[C]================================
            |[C]ORDINE PER: <b>${order.customer}</b>
            |[C]================================
            |[L]MODELLO: <b>${order.pizza.name}</b>
            |[L]
            |[L]SAUCE: ${order.pizza.sauce.name}
            |[L]INGREDIENTI:
            |${order.pizza.toppings.joinToString("\n") { "[L]  > ${it.name}" }.ifEmpty { "[L]  > (keine)" }}
            |${order.note.takeIf(String::isNotBlank)?.let { "[L]\n[L]<b>NOTA BENE:</b>\n[L]  $it" } ?: ""}
            |[C]--------------------------------
            |[L]            ( )  ( )  ( )
            |[L]             )    )    )
            |[L]         _______________
            |[L]        \_______________/
            |[L]
            |[L]
            |[L]
        """.trimMargin()
    };*/

    /** @param ahead open orders before this one, so the guest knows how long it'll take. */
    fun format(order: Order, link: FunLink?, ahead: Int = 0) =
        layout(order.copy(customer = order.customer.asPlainText(), note = order.note.asPlainText()), link, ahead)

    protected abstract fun layout(order: Order, link: FunLink?, ahead: Int): String
}

// Guests type these; "[R]" or "<qrcode>" would otherwise reach the printer as markup. Everything else on the
// paper comes from the admin panel. Round brackets, because the library has no escape.
internal fun String.asPlainText() = replace(Printout.alignTag) { "(${it.groupValues[1]})" }.replace('<', '(').replace('>', ')')

// "Gast ........... Laura" filled to the 32-char line; overlong values just wrap.
internal fun dotRow(label: String, value: String) =
    "[L]$label ${".".repeat((Printout.LINE_CHARS - label.length - value.length - 2).coerceAtLeast(1))} <b>$value</b>"

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

// Right under the diet line, loud for the same reason: the kitchen must not miss it. Nothing without allergens.
private fun allergenLine(order: Order) =
    if (order.allergens.isEmpty()) "" else "\n[L]   <b>!! ENTHÄLT: ${order.allergens.joinToString { it.label.uppercase() }} !!</b>"
