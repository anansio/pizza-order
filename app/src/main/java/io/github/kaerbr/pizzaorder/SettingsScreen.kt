package io.github.kaerbr.pizzaorder.ui

import android.app.ActivityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kaerbr.pizzaorder.Allergen
import io.github.kaerbr.pizzaorder.Diet
import io.github.kaerbr.pizzaorder.DoughStock
import io.github.kaerbr.pizzaorder.FunLink
import io.github.kaerbr.pizzaorder.MenuConfig
import io.github.kaerbr.pizzaorder.Order
import io.github.kaerbr.pizzaorder.Pizza
import io.github.kaerbr.pizzaorder.Sauce
import io.github.kaerbr.pizzaorder.PrintStatus
import io.github.kaerbr.pizzaorder.PrintStyle
import io.github.kaerbr.pizzaorder.isWebLink
import io.github.kaerbr.pizzaorder.Topping
import io.github.kaerbr.pizzaorder.topPizzas
import kotlinx.coroutines.delay

private val ADMIN_TABS = listOf("Übersicht", "Menü", "Ausdruck", "QR-Links")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    menu: MenuConfig,
    orders: List<Order>,
    onAddSauce: (String) -> Unit,
    onRemoveSauce: (Sauce) -> Unit,
    onAddTopping: (String) -> Unit,
    onRemoveTopping: (Topping) -> Unit,
    onSetSoldOut: (name: String, out: Boolean) -> Unit,
    onSetDiet: (name: String, Diet) -> Unit,
    onSetAllergen: (name: String, Allergen, contained: Boolean) -> Unit,
    onSavePreset: (Pizza, replacing: String?) -> Unit,
    onRemovePreset: (String) -> Unit,
    onResetMenu: () -> Unit,
    onExportMenu: () -> Unit,
    onImportMenu: () -> Unit,
    menuFileMessage: String?,
    onSetDoughToday: (Int?) -> Unit,
    onAdjustDough: (Int) -> Unit,
    onClearAllOrders: () -> Unit,
    printStyle: PrintStyle,
    onPrintStyle: (PrintStyle) -> Unit,
    onTestPrint: () -> Unit,
    testPrintStatus: PrintStatus?,
    onAddFunLink: (teaser: String, url: String) -> Unit,
    onRemoveFunLink: (FunLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAddPreset by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<Pizza?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var removing by remember { mutableStateOf<Removal?>(null) }

    removing?.let { r ->
        val affected = menu.presets.filter { p -> p.sauce.name == r.name || p.toppings.any { it.name == r.name } }
        DangerDialog(
            title = "${r.name} löschen?",
            text = (if (affected.isEmpty()) "" else "Das ändert auch: ${affected.joinToString { it.name }}. ") +
                "Geht es nur um heute, nimm stattdessen „Heute aus“.",
            action = "Löschen",
            onConfirm = r.remove,
            onDismiss = { removing = null },
        )
    }

    if (showAddPreset || editingPreset != null) {
        val close = { showAddPreset = false; editingPreset = null }
        PresetDialog(
            menu = menu,
            editing = editingPreset,
            onDismiss = close,
            onSave = { p ->
                onSavePreset(p, editingPreset?.name)
                close()
            },
        )
    }

    confirm?.let { c ->
        DangerDialog(
            title = c.title,
            text = c.text,
            action = c.action,
            onConfirm = {
                when (c) {
                    Confirm.ClearOrders -> onClearAllOrders()
                    Confirm.ResetMenu -> onResetMenu()
                    Confirm.ImportMenu -> onImportMenu()
                }
            },
            onDismiss = { confirm = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            ADMIN_TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, fontWeight = FontWeight.SemiBold) })
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                when (tab) {
                    0 -> {
                        Section(title = "Heute") {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val open = orders.count { !it.isDone() }
                                StatTile("Bestellungen", orders.size, Modifier.weight(1f))
                                StatTile("Offen", open, Modifier.weight(1f))
                                StatTile("Fertig", orders.size - open, Modifier.weight(1f))
                                StatTile("QR-Links", menu.funLinks.size, Modifier.weight(1f))
                            }
                        }
                        orders.topPizzas().takeIf { it.isNotEmpty() }?.let { top ->
                            Section(title = "Beliebteste Pizzen") {
                                top.forEach { (name, count) ->
                                    Row {
                                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                        Text("${count}×", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        Section(title = "Teig") {
                            DoughSetting(menu.dough, onSetDoughToday, onAdjustDough)
                        }
                        Section(title = "Drucker") {
                            Text("Ausdruck-Design: ${printStyle.label}", style = MaterialTheme.typography.bodyLarge)
                            TestPrintButton(onTestPrint, testPrintStatus)
                        }
                        Section(title = "Tablet") {
                            ScreenPinButton()
                        }
                        Section(title = "Gefahrenzone") {
                            DangerButton("Alle Bestellungen löschen", Icons.Default.Delete) { confirm = Confirm.ClearOrders }
                            DangerButton("Standardmenü wiederherstellen", Icons.Default.Refresh) { confirm = Confirm.ResetMenu }
                        }
                    }

                    1 -> {
                        val cycleDiet = { name: String -> onSetDiet(name, menu.dietOf(name).next()) }
                        Section(title = "Heute aus") {
                            Text(
                                "Antippen, wenn etwas ausgeht: Gäste können es dann nicht mehr wählen, auch keine Pizza damit. Bleibt im Menü und ist beim Löschen aller Bestellungen wieder da.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                (menu.sauces.map { it.name } + menu.toppings.map { it.name }).distinct().forEach { name ->
                                    val out = name in menu.soldOut
                                    FilterChip(
                                        selected = out,
                                        onClick = { onSetSoldOut(name, !out) },
                                        label = { Text(name) },
                                        leadingIcon = if (out) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null,
                                    )
                                }
                            }
                        }
                        Text(
                            "Tippen auf eine Zutat wechselt: Vegan → Vegetarisch → Nichts davon. ✕ löscht sie.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Section(title = "Saucen") {
                            val removeSauce = { s: Sauce -> removing = Removal(s.name) { onRemoveSauce(s) } }
                            ChipList(items = menu.sauces.toList(), onRemove = removeSauce.takeIf { menu.sauces.size > 1 }, labelProvider = { it.name }, dietOf = { menu.dietOf(it.name) }, onCycleDiet = { cycleDiet(it.name) })
                            AddRow(placeholder = "Neue Sauce", onAdd = onAddSauce)
                        }

                        Section(title = "Beläge") {
                            ChipList(items = menu.toppings.toList(), onRemove = { t -> removing = Removal(t.name) { onRemoveTopping(t) } }, labelProvider = { it.name }, dietOf = { menu.dietOf(it.name) }, onCycleDiet = { cycleDiet(it.name) })
                            AddRow(placeholder = "Neuer Belag", onAdd = onAddTopping)
                        }

                        Section(title = "Allergene") {
                            Text(
                                "Antippen markiert, was die Zutat enthält. Gäste sehen es an Zutaten, Pizzen und auf dem Ausdruck.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Sauces first, then toppings, as on the order screen; a name in both shows once.
                            (menu.sauces.map { it.name } + menu.toppings.map { it.name }).distinct().forEach { name ->
                                AllergenRow(name, menu.allergensOf(name)) { allergen, contained -> onSetAllergen(name, allergen, contained) }
                            }
                        }

                        Section(title = "Vordefinierte Pizzen") {
                            if (menu.presets.isEmpty()) {
                                Text(
                                    "(noch keine)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                menu.presets.forEach { preset ->
                                    PresetRow(
                                        preset = preset,
                                        onEdit = { editingPreset = preset },
                                        onRemove = { onRemovePreset(preset.name) },
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { showAddPreset = true },
                                shape = RoundedCornerShape(10.dp),
                                enabled = menu.sauces.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Pizza hinzufügen")
                            }
                        }

                        Section(title = "Menü-Datei") {
                            Text(
                                "Sichert Zutaten, Pizzen und QR-Links in eine Datei, z. B. für den nächsten Abend oder ein neues Tablet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onExportMenu, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Speichern")
                                }
                                OutlinedButton(onClick = { confirm = Confirm.ImportMenu }, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Laden")
                                }
                            }
                            menuFileMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }

                    2 -> Section(title = "Ausdruck-Design") {
                        PrintStyle.entries.forEach { style ->
                            val selected = style == printStyle
                            Surface(
                                onClick = { onPrintStyle(style) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = selectionBorder(selected),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(style.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(style.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        TestPrintButton(onTestPrint, testPrintStatus)
                    }

                    else -> Section(title = "QR-Links") {
                        if (menu.funLinks.isEmpty()) {
                            Text(
                                "(keine – Ausdruck ohne QR-Code)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        menu.funLinks.forEach { link ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(link.teaser, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(link.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onRemoveFunLink(link) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        AddLinkRow(onAdd = onAddFunLink)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllergenRow(name: String, contained: Set<Allergen>, onToggle: (Allergen, Boolean) -> Unit) {
    // Name above its chips: next to a long one like "Pesto al basilico" they no longer fit on one line.
    Column {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Allergen.entries.forEach { allergen ->
                val selected = allergen in contained
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(allergen, !selected) },
                    label = { Text(allergen.label) },
                    leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null,
                    border = selectionChipBorder(selected),
                )
            }
        }
    }
}

private fun Diet.next() = Diet.entries[(ordinal + 1) % Diet.entries.size]

private enum class Confirm(val title: String, val text: String, val action: String) {
    ClearOrders("Alle Bestellungen löschen?", "Auch Teig-Zähler und „Heute aus“ starten von vorn. Das kann nicht rückgängig gemacht werden.", "Löschen"),
    ResetMenu("Standardmenü wiederherstellen?", "Saucen, Beläge, Pizzen und QR-Links werden ersetzt. Bestellungen bleiben erhalten.", "Wiederherstellen"),
    ImportMenu("Menü laden?", "Saucen, Beläge, Pizzen und QR-Links werden durch die Datei ersetzt. Bestellungen bleiben erhalten.", "Datei wählen"),
}

/** An ingredient waiting for its delete confirmation. */
private class Removal(val name: String, val remove: () -> Unit)

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("$value", style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TestPrintButton(onTestPrint: () -> Unit, status: PrintStatus?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = onTestPrint,
            enabled = status != PrintStatus.Printing,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(status.icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(status.label("Testdruck"))
        }
        // Here, of all places, the reason matters: this is where the printer gets set up.
        if (status is PrintStatus.Failed) {
            Text(status.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DangerButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/** Android's screen pinning: Home and Back stop working, so guests stay in the app. Android asks once before it pins. */
@Composable
private fun ScreenPinButton() {
    val activity = LocalActivity.current ?: return
    val manager = remember { activity.getSystemService(ActivityManager::class.java) }
    // ponytail: polled, because the system's prompt fires no callback or lifecycle event when it's accepted.
    val pinned by produceState(false) {
        while (true) {
            value = manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            delay(1_000)
        }
    }
    Text(
        if (pinned) "Fixiert: Home und Zurück sind gesperrt." else "Sperrt Home und Zurück, damit Gäste in der App bleiben.",
        style = MaterialTheme.typography.bodyLarge,
    )
    // Android itself tells guests how to unpin, so without this it only stops accidents.
    Text(
        "Wischen und Halten löst die Fixierung. Dagegen hilft die PIN-Abfrage beim Lösen in den Android-Einstellungen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = { if (pinned) activity.stopLockTask() else activity.startLockTask() },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(if (pinned) Icons.Default.LockOpen else Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (pinned) "Fixierung lösen" else "Bildschirm fixieren")
    }
}

@Composable
private fun DangerDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun DoughSetting(dough: DoughStock?, onSet: (Int?) -> Unit, onAdjust: (Int) -> Unit) {
    // Re-keyed so the field shows the new count once it's set.
    var count by remember(dough?.today) { mutableStateOf(dough?.today?.toString().orEmpty()) }
    val quantity = count.toIntOrNull()
    var confirmRefill by remember { mutableStateOf(false) }

    if (confirmRefill) {
        DangerDialog(
            title = "Bestand neu setzen?",
            text = "Es sind noch ${dough?.available} übrig. Danach sind es $quantity, was schon verbraucht ist, zählt dann nicht mehr. Zum Korrigieren gibt es −1 und +1.",
            action = "Neu setzen",
            onConfirm = { onSet(quantity) },
            onDismiss = { confirmRefill = false },
        )
    }

    Text(
        when {
            dough == null -> "Kein Limit: Bestellungen werden nicht gezählt."
            dough.isSoldOut -> "Ausverkauft (heute ${dough.today})"
            else -> "Noch ${dough.available} von ${dough.today} Pizzen übrig"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = if (dough != null && (dough.isSoldOut || dough.isLow)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(
            value = count,
            onValueChange = { new -> count = new.filter(Char::isDigit).take(4) },
            label = { Text("Teiglinge heute") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f),
        )
        // Fills the stock to this number, so once dough is used up that deserves a second look.
        Button(
            onClick = { if (dough != null && dough.available != dough.today) confirmRefill = true else onSet(quantity) },
            enabled = quantity != null,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(56.dp),
        ) { Text("Setzen") }
        if (dough != null) {
            TextButton(onClick = { onSet(null) }) { Text("Kein Limit") }
        }
    }
    if (dough != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bestand korrigieren", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onAdjust(-1) }, enabled = !dough.isSoldOut, shape = RoundedCornerShape(10.dp)) { Text("−1") }
            OutlinedButton(onClick = { onAdjust(1) }, shape = RoundedCornerShape(10.dp)) { Text("+1") }
        }
    }
}

// ─── Section building blocks ───────────────────────────────────────────────

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipList(items: List<T>, onRemove: ((T) -> Unit)?, labelProvider: (T) -> String, dietOf: (T) -> Diet, onCycleDiet: (T) -> Unit) {
    if (items.isEmpty()) {
        Text(
            "(noch keine)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val diet = dietOf(item)
            AssistChip(
                onClick = { onCycleDiet(item) },
                label = { Text(labelProvider(item)) },
                leadingIcon = diet.icon?.let { { Icon(it, diet.label, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) } },
                // No ✕ when removing isn't allowed (the last sauce).
                trailingIcon = onRemove?.let { remove -> {
                    // Own click target: the chip body cycles the diet, only the ✕ deletes.
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Löschen",
                        modifier = Modifier.clip(CircleShape).clickable { remove(item) }.padding(4.dp).size(18.dp),
                    )
                } },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun AddRow(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(placeholder) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                if (text.isNotBlank()) {
                    onAdd(text)
                    text = ""
                }
            },
            enabled = text.isNotBlank(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(56.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Hinzufügen")
        }
    }
}

@Composable
private fun AddLinkRow(onAdd: (teaser: String, url: String) -> Unit) {
    var teaser by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val urlInvalid = url.isNotBlank() && !isWebLink(url)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(
            value = teaser,
            onValueChange = { teaser = it },
            placeholder = { Text("Bezeichnung (wird nicht gedruckt), z.B. Pizza-Katzen") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        // Top-aligned: the error line under the field would otherwise push the button off its row.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("https://…") },
                isError = urlInvalid,
                supportingText = if (urlInvalid) { { Text("Muss mit http:// oder https:// beginnen") } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onAdd(teaser, url); teaser = ""; url = "" },
                enabled = url.isNotBlank() && !urlInvalid,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(56.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Hinzufügen")
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Pizza, onEdit: () -> Unit, onRemove: () -> Unit) {
     Card(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Bearbeiten")
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Löschen",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ─── Preset dialog ─────────────────────────────────────────────────────────

/** Adds a preset, or changes [editing] when there is one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetDialog(
    menu: MenuConfig,
    editing: Pizza?,
    onDismiss: () -> Unit,
    onSave: (Pizza) -> Unit,
) {
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var sauce by remember { mutableStateOf(editing?.sauce ?: menu.sauces.firstOrNull() ?: Sauce("Tomate")) }
    var toppings by remember { mutableStateOf(editing?.toppings?.toSet().orEmpty()) }
    // Picking and deleting a preset go by name, so a second one of the same name could never be picked.
    val taken = (menu.presets.map { it.name } + CUSTOM_NAME).filter { it != editing?.name }.map { it.lowercase() }
    val nameTaken = name.trim().lowercase() in taken

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "Pizza hinzufügen" else "Pizza bearbeiten") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("z.B. Mafiosa") },
                    isError = nameTaken,
                    supportingText = if (nameTaken) { { Text("Diesen Namen gibt es schon") } } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "SAUCE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    menu.sauces.forEach { s ->
                        FilterChip(
                            selected = s == sauce,
                            onClick = { sauce = s },
                            label = { Text(s.name) },
                            border = selectionChipBorder(s == sauce),
                        )
                    }
                }

                Text(
                    "BELAG",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (menu.toppings.isEmpty()) {
                    Text(
                        "Erst Beläge im Hauptmenü anlegen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        menu.toppings.forEach { t ->
                            FilterChip(
                                selected = t in toppings,
                                onClick = { toppings = if (t in toppings) toppings - t else toppings + t },
                                label = { Text(t.name) },
                                border = selectionChipBorder(t in toppings),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(Pizza(name.trim(), sauce, Pizza.toppingsOf(toppings.map { it.name }))) },
                enabled = name.isNotBlank() && !nameTaken,
            ) { Text(if (editing == null) "Hinzufügen" else "Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

