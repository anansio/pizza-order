package io.github.kaerbr.pizzaorder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kaerbr.pizzaorder.Order
import io.github.kaerbr.pizzaorder.OrderStatus
import io.github.kaerbr.pizzaorder.PrintStatus
import kotlinx.coroutines.delay

@Composable
fun OrdersListScreen(
    orders: List<Order>,
    onAdvance: (Order) -> Unit,
    onReprint: (Order) -> Unit,
    modifier: Modifier = Modifier,
    prints: Map<Long, PrintStatus> = emptyMap(),
) {
    // "Alle löschen" lives in the locked admin panel, not here where guests can reach it.
    val (waiting, baking, done) = Triple(
        orders.filter { it.status == OrderStatus.QUEUED },
        orders.filter { it.status == OrderStatus.BAKING },
        orders.filter { it.status == OrderStatus.READY },
    )

    // One clock for the whole board; minutes are all the waiting label shows.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    // The two open groups run in the order they came in, the queue the guests see; done newest first.
    val sortedWaiting = waiting.sortedBy { it.id }
    val sortedBaking = baking.sortedBy { it.id }
    val sortedDone = done.sortedByDescending { it.completedAt }
    // After a restart only the flag is left: the reason lived in memory.
    val statusOf = { order: Order -> prints[order.id] ?: PrintStatus.Failed("Nicht gedruckt.").takeUnless { order.printed } }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 920.dp),
            // List starts below the floating quick menu (MainActivity), which has no heading row to sit in here.
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = FormTop + QuickMenuSize + 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (orders.isEmpty()) item { EmptyState() }

            if (sortedWaiting.isNotEmpty()) item { SectionRibbon("${OrderStatus.QUEUED.label} (${sortedWaiting.size})", Modifier.animateItem()) }
            items(sortedWaiting, key = { it.id }) { order ->
                OrderCard(order, now, { onAdvance(order) }, { onReprint(order) }, statusOf(order), Modifier.animateItem())
            }

            if (sortedBaking.isNotEmpty()) item { SectionRibbon("${OrderStatus.BAKING.label} (${sortedBaking.size})", Modifier.animateItem()) }
            items(sortedBaking, key = { it.id }) { order ->
                OrderCard(order, now, { onAdvance(order) }, { onReprint(order) }, statusOf(order), Modifier.animateItem())
            }

            if (sortedDone.isNotEmpty()) {
                item { SectionRibbon("${OrderStatus.READY.label} (${sortedDone.size})", Modifier.animateItem()) }
                items(sortedDone, key = { it.id }) { order ->
                    OrderCard(order, now, { onAdvance(order) }, { onReprint(order) }, statusOf(order), Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: Order,
    now: Long,
    onAdvance: () -> Unit,
    onReprint: () -> Unit,
    printStatus: PrintStatus?,
    modifier: Modifier = Modifier,
) {
    val done = order.isDone()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (order.status == OrderStatus.BAKING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shadowElevation = if (done) 0.dp else 1.dp,
    ) {
        Row(
            Modifier.height(IntrinsicSize.Min).padding(horizontal = 18.dp, vertical = 16.dp).alpha(if (done) 0.55f else 1f),
            verticalAlignment = Alignment.Top,
        ) {
            // The only way to move an order along: guests reach this list, a stray tap on the card moved orders away.
            StatusStep(order.status, onAdvance)
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(order.formattedNumber, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    Text(order.customer, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(order.pizza.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    DietBadge(order.diet)
                }
                Text(order.pizza.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (order.note.isNotBlank()) {
                    Text("„${order.note}“", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The reason is what says whether to fetch paper or switch the printer on.
                if (printStatus is PrintStatus.Failed) {
                    Text(printStatus.reason, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                }
            }
            // Time pinned top-right, reprint bottom-right; IntrinsicSize.Min on the Row lets this column span the card.
            Column(Modifier.fillMaxHeight().padding(start = 16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                // End padding = the button's inner end padding, so the time ends flush with the word "Nachdrucken".
                Text(
                    waitingLabel(order, now),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = ButtonDefaults.ContentPadding.calculateEndPadding(LocalLayoutDirection.current)),
                )
                // The button reports its own print: "Druckt…", a brief "Gedruckt", or red until retried.
                val failed = printStatus is PrintStatus.Failed
                OutlinedButton(
                    onClick = onReprint,
                    enabled = printStatus != PrintStatus.Printing,
                    modifier = Modifier.padding(top = 8.dp),
                    border = BorderStroke(1.dp, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(printStatus.icon, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(printStatus.label("Nachdrucken"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// "19:42 · 12 min" while open; just the time once done or in the first minute.
private fun waitingLabel(order: Order, now: Long): String {
    val minutes = (now - order.id) / 60_000
    return if (order.isDone() || minutes < 1) order.formattedTime else "${order.formattedTime} · $minutes min"
}

/**
 * Three states, one control: the circle says where the order stands, the label what the next tap does.
 * The label is not decoration: with three states a bare tick would leave the kitchen guessing which one comes next.
 * Screen readers get the current state instead of the next step, since the section ribbon is not part of the control.
 */
@Composable
private fun StatusStep(status: OrderStatus, onAdvance: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val action = when (status) {
        OrderStatus.QUEUED -> "In den Ofen"
        OrderStatus.BAKING -> "Fertig"
        OrderStatus.READY -> "Zurück"
    }
    val circle = when (status) {
        OrderStatus.QUEUED -> Modifier.border(1.5.dp, colors.outline, CircleShape)
        OrderStatus.BAKING -> Modifier.background(colors.primary, CircleShape)
        OrderStatus.READY -> Modifier.background(colors.onTertiaryContainer, CircleShape)
    }
    val tint = when (status) {
        OrderStatus.QUEUED -> colors.onSurfaceVariant
        OrderStatus.BAKING -> colors.onPrimary
        OrderStatus.READY -> colors.tertiaryContainer
    }

    Column(
        Modifier
            .width(92.dp)
            .offset(x = (-7).dp, y = (-7).dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onAdvance)
            .semantics { contentDescription = status.label }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).then(circle), contentAlignment = Alignment.Center) {
            val icon = when (status) {
                OrderStatus.QUEUED -> Icons.Outlined.LocalFireDepartment
                OrderStatus.BAKING -> Icons.Default.Check
                OrderStatus.READY -> Icons.Default.Undo
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            action,
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (status == OrderStatus.BAKING) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionRibbon(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Eyebrow(text, Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Noch keine Bestellungen", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
