package io.github.kaerbr.pizzaorder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.kaerbr.pizzaorder.Diet
import io.github.kaerbr.pizzaorder.MenuConfig
import io.github.kaerbr.pizzaorder.Pizza
import io.github.kaerbr.pizzaorder.Sauce
import io.github.kaerbr.pizzaorder.Topping

private const val CUSTOM_NAME = "Eigenkreation"
private const val NOTE_MAX = 160

// The floating quick menu (MainActivity) sits in the "01 Dein Name" row: same top edge, and that
// heading row is exactly as tall as the button so both centre on one line.
internal val FormTop = 25.dp
internal val FormSidePadding = 20.dp

// Two control sizes for the whole screen: inputs and the main action at 48dp, choices and navigation at 40dp.
internal val ControlHeight = 48.dp
internal val ChipHeight = 40.dp
internal val QuickMenuSize = ChipHeight

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderScreen(
    menu: MenuConfig,
    /** Shown on the ticket in the dock; the order gets it once submitted. */
    nextNumber: Int,
    onSubmit: (customer: String, pizza: Pizza, note: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var customer by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    // CRITICAL: We store simple names to satisfy rememberSaveable (must be Parcelable/Primitive)
    // Starts empty: guests pick a pizza themselves instead of finding the first preset ticked.
    var sauceName by rememberSaveable { mutableStateOf("") }
    var toppingNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Tapping Eigenkreation yields the same empty pizza as "nothing chosen yet"; this tells them apart.
    var customPicked by rememberSaveable { mutableStateOf(false) }

    // Reconstruct objects for logic
    val sauce = remember(sauceName) { Sauce(sauceName) }
    val toppings = remember(toppingNames) { Pizza.toppingsOf(toppingNames) }

    val nothingChosen = sauceName.isBlank() && toppingNames.isEmpty() && !customPicked
    // null = no card selected yet.
    val pizzaName = remember(sauce, toppings, nothingChosen) {
        menu.presets.find { it.sauce == sauce && it.toppings.toSet() == toppings.toSet() }?.name
            ?: CUSTOM_NAME.takeUnless { nothingChosen }
    }
    val current = Pizza(pizzaName ?: CUSTOM_NAME, sauce, toppings)
    // No diet for an empty pizza: a bare crust would read as "Vegan" before anything is picked.
    val diet = if (nothingChosen) Diet.NONE else menu.dietOf(current)

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = FormSidePadding, end = FormSidePadding, top = FormTop, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Spacing tuned so the whole form fits one portrait screen of the 8.7" party tablet (~600×1000dp).
            Column(Modifier.widthIn(max = 920.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Section("01", "Dein Name", headerHeight = QuickMenuSize) {
                    CompactTextField(
                        value = customer,
                        onValueChange = { customer = it },
                        placeholder = "z.B. Anna",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Section("02", "Pizza wählen") {
                    // Eigenkreation only shows a diet while it's the one being built.
                    val tiles = menu.presets.map { Triple(it.name, it.description, menu.dietOf(it)) } +
                        Triple(CUSTOM_NAME, "Sauce und Belag frei wählen", if (pizzaName == CUSTOM_NAME) diet else Diet.NONE)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(3).forEach { row ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (name, desc, tileDiet) ->
                                    PizzaCard(name, desc, tileDiet, pizzaName == name, Modifier.weight(1f).fillMaxHeight()) {
                                        menu.presets.find { it.name == name }?.let { p ->
                                            sauceName = p.sauce.name; toppingNames = p.toppings.map { it.name }
                                        } ?: run { sauceName = ""; toppingNames = emptyList(); customPicked = true } // Eigenkreation: start empty
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                Section("03", "Sauce") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        menu.sauces.forEach { s ->
                            Pill(s.name, s.name == sauceName, diet = menu.dietOf(s.name)) { sauceName = s.name }
                        }
                    }
                }

                Section("04", "Belag") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        menu.toppings.forEach { t ->
                            val selected = t.name in toppingNames
                            Pill(t.name, selected, if (selected) Icons.Default.Check else Icons.Default.Add, menu.dietOf(t.name)) {
                                toppingNames = if (selected) toppingNames - t.name else toppingNames + t.name
                            }
                        }
                    }
                }

                // Counter sits in the header row: as supportingText it cost a line under the field.
                Section("05", "Notiz für die Küche", trailing = {
                    Text("${note.length} / $NOTE_MAX", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }) {
                    CompactTextField(
                        value = note,
                        onValueChange = { note = it.take(NOTE_MAX) },
                        placeholder = "z.B. extra knusprig, ohne Oregano…",
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Menu-style footnote: the leaves mark pizzas, sauces and toppings alike, so the key
                // closes the form instead of sitting in one section.
                DietLegend(Modifier.align(Alignment.CenterHorizontally))
            }
        }

        // Dock: order summary + slide-to-order peel, pinned to the bottom. Page switch and settings
        // float top-right (MainActivity) and stay out of here.
        PeelDock(
            DockOrder(customer, pizzaName, sauceName, toppingNames, diet, menu.dietCulprits(current), menu.dough),
            number = nextNumber,
            onSubmit = {
                onSubmit(customer, current, note)
                customer = ""; note = ""
                sauceName = ""; toppingNames = emptyList(); customPicked = false
            },
        )
    }
}

@Composable
internal fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) = Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = color,
    fontWeight = FontWeight.SemiBold,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
)

// Filled leaf = vegan, outlined = vegetarian, nothing for the rest: the missing leaf is the signal.
internal val Diet.icon: ImageVector?
    get() = when (this) {
        Diet.VEGAN -> Icons.Filled.Eco
        Diet.VEGETARIAN -> Icons.Outlined.Eco
        Diet.NONE -> null
    }

/** The `.diet` pill from design/index.html; renders nothing for [Diet.NONE]. */
@Composable
internal fun DietBadge(diet: Diet, modifier: Modifier = Modifier) {
    val icon = diet.icon ?: return
    Row(
        modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(diet.label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, letterSpacing = 0.09.em)
    }
}

@Composable
private fun DietLegend(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(Diet.VEGAN, Diet.VEGETARIAN).forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(d.icon!!, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    errorContainerColor = MaterialTheme.colorScheme.surface,
)

/**
 * Material's outlined field at [ControlHeight]: OutlinedTextField forces a 56dp minimum and 16dp inner
 * padding, which made the inputs look oversized next to the 48dp order button and 40dp chips.
 * Same look and focus colours, built from its own decoration box with 12dp vertical padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = fieldColors()
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = ControlHeight),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        maxLines = maxLines,
        interactionSource = interactionSource,
    ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = innerTextField,
            enabled = true,
            singleLine = singleLine,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            placeholder = { Text(placeholder) },
            colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 12.dp, bottom = 12.dp),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = colors,
                    shape = shape,
                )
            },
        )
    }
}

@Composable
private fun Section(
    step: String,
    title: String,
    trailing: @Composable () -> Unit = {},
    headerHeight: Dp = Dp.Unspecified,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.heightIn(min = headerHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(step, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@Composable
private fun PizzaCard(name: String, description: String, diet: Diet, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primaryContainer else colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline),
    ) {
        // Name with diet leaf on top, ingredients below (wrapping as needed). The separate badge row made
        // every card row ~50dp taller on the 8.7" tablet. Selection shows as border and fill, like the chips.
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Shrinks instead of wrapping or "…": at three columns "Eigenkreation" used to break mid-word.
                val nameStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.15f)
                BasicText(
                    name,
                    style = nameStyle.copy(color = colors.onSurface),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = nameStyle.fontSize),
                    modifier = Modifier.weight(1f, fill = false),
                )
                diet.icon?.let { Icon(it, diet.label, Modifier.size(16.dp), tint = colors.onTertiaryContainer) }
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, icon: ImageVector? = null, diet: Diet = Diet.NONE, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) } },
        trailingIcon = diet.icon?.let { { Icon(it, diet.label, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) } },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.heightIn(min = ChipHeight),
    )
}
