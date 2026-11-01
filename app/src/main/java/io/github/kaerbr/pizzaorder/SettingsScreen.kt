package io.github.kaerbr.pizzaorder.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
    onSetDiet: (name: String, Diet) -> Unit,
    onSetAllergen: (name: String, Allergen, contained: Boolean) -> Unit,
    onAddPreset: (Pizza) -> Unit,
    onRemovePreset: (String) -> Unit,
    onResetMenu: () -> Unit,
    onSetDoughToday: (Int?) -> Unit,
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
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    if (showAddPreset) {
        AddPresetDialog(
            menu = menu,
            onDismiss = { showAddPreset = false },
            onAdd = { p ->
                onAddPreset(p)
                showAddPreset = false
            },
        )
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = {
                TextButton(
                    onClick = { confirm = null; if (c == Confirm.ClearOrders) onClearAllOrders() else onResetMenu() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(c.action) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Abbrechen") }
            },
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
                        Section(title = "Teig") {
                            DoughSetting(menu.dough, onSetDoughToday)
                        }
                        Section(title = "Drucker") {
                            Text("Ausdruck-Design: ${printStyle.label}", style = MaterialTheme.typography.bodyLarge)
                            TestPrintButton(onTestPrint, testPrintStatus)
                        }
                        Section(title = "Gefahrenzone") {
                            DangerButton("Alle Bestellungen löschen", Icons.Default.Delete) { confirm = Confirm.ClearOrders }
                            DangerButton("Standardmenü wiederherstellen", Icons.Default.Refresh) { confirm = Confirm.ResetMenu }
                        }
                    }

                    1 -> {
                        val cycleDiet = { name: String -> onSetDiet(name, menu.dietOf(name).next()) }
                        Text(
                            "Tippen auf eine Zutat wechselt: Vegan → Vegetarisch → Nichts davon. ✕ löscht sie.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Section(title = "Saucen") {
                            ChipList(items = menu.sauces.toList(), onRemove = onRemoveSauce.takeIf { menu.sauces.size > 1 }, labelProvider = { it.name }, dietOf = { menu.dietOf(it.name) }, onCycleDiet = { cycleDiet(it.name) })
                            AddRow(placeholder = "Neue Sauce", onAdd = onAddSauce)
                        }

                        Section(title = "Beläge") {
                            ChipList(items = menu.toppings.toList(), onRemove = onRemoveTopping, labelProvider = { it.name }, dietOf = { menu.dietOf(it.name) }, onCycleDiet = { cycleDiet(it.name) })
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
                    }

                    2 -> Section(title = "Ausdruck-Design") {
                        PrintStyle.entries.forEach { style ->
                            val selected = style == printStyle
                            Surface(
                                onClick = { onPrintStyle(style) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
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

                    else -> Section(title = "QR-Links (Beleg & QR-Lacher)") {
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

@Composable
private fun AllergenRow(name: String, contained: Set<Allergen>, onToggle: (Allergen, Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Allergen.entries.forEach { allergen ->
            val selected = allergen in contained
            FilterChip(
                selected = selected,
                onClick = { onToggle(allergen, !selected) },
                label = { Text(allergen.label) },
                leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null,
            )
        }
    }
}

private fun Diet.next() = Diet.entries[(ordinal + 1) % Diet.entries.size]

private enum class Confirm(val title: String, val text: String, val action: String) {
    ClearOrders("Alle Bestellungen löschen?", "Auch der Teig-Zähler startet von vorn. Das kann nicht rückgängig gemacht werden.", "Löschen"),
    ResetMenu("Standardmenü wiederherstellen?", "Saucen, Beläge, Pizzen und QR-Links werden ersetzt. Bestellungen bleiben erhalten.", "Wiederherstellen"),
}

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

@Composable
private fun DoughSetting(dough: DoughStock?, onSet: (Int?) -> Unit) {
    // Re-keyed so the field shows the new count once it's set.
    var count by remember(dough?.today) { mutableStateOf(dough?.today?.toString().orEmpty()) }
    val quantity = count.toIntOrNull()
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
        OutlinedTextField(
            value = count,
            onValueChange = { new -> count = new.filter(Char::isDigit).take(4) },
            label = { Text("Teiglinge heute") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f),
        )
        // Same number again starts the count over, e.g. for the next party.
        Button(
            onClick = { onSet(quantity) },
            enabled = quantity != null,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(56.dp),
        ) { Text("Setzen") }
        if (dough != null) {
            TextButton(onClick = { onSet(null) }) { Text("Kein Limit") }
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
                        modifier = Modifier.clip(CircleShape).clickable { remove(item) }.padding(2.dp).size(16.dp),
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
        OutlinedTextField(
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
        OutlinedTextField(
            value = teaser,
            onValueChange = { teaser = it },
            placeholder = { Text("Bezeichnung (wird nicht gedruckt), z.B. Pizza-Katzen") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        // Top-aligned: the error line under the field would otherwise push the button off its row.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
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
private fun PresetRow(preset: Pizza, onRemove: () -> Unit) {
     Card(
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

// ─── Add-preset dialog ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPresetDialog(
    menu: MenuConfig,
    onDismiss: () -> Unit,
    onAdd: (Pizza) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var sauce by remember { mutableStateOf(menu.sauces.firstOrNull() ?: Sauce("Tomate")) }
    var toppings by remember { mutableStateOf<LinkedHashSet<Topping>>(linkedSetOf()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pizza hinzufügen") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("z.B. Mafiosa") },
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
                                onClick = {
                                    toppings = LinkedHashSet(toppings).apply { if (!add(t)) remove(t) }
                                },
                                label = { Text(t.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(Pizza(name.trim(), sauce, toppings)) },
                enabled = name.isNotBlank(),
            ) { Text("Hinzufügen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

