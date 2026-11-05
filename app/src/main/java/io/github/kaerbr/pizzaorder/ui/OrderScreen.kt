package io.github.kaerbr.pizzaorder.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.kaerbr.pizzaorder.Allergen
import io.github.kaerbr.pizzaorder.Diet
import io.github.kaerbr.pizzaorder.MenuConfig
import io.github.kaerbr.pizzaorder.Pizza
import io.github.kaerbr.pizzaorder.Sauce
import io.github.kaerbr.pizzaorder.Topping

internal const val CUSTOM_NAME = "Eigenkreation"
// Fits next to "Gast" on the 32-character printout line.
private const val NAME_MAX = 24
private const val NOTE_MAX = 160

// The floating quick menu (MainActivity) sits in the "01 Dein Name" row: same top edge, and that
// heading row is exactly as tall as the button so both centre on one line.
internal val FormTop = 25.dp
internal val FormSidePadding = 20.dp

// Two control sizes for the whole screen: inputs and the main action at 48dp, choices and navigation at 40dp.
internal val ControlHeight = 48.dp
internal val ChipHeight = 40.dp
internal val QuickMenuSize = ChipHeight

// Every selected or focused control uses this, so cards, chips and text fields can't drift apart.
internal val SelectedBorderWidth = 1.5.dp

@Composable
internal fun selectionBorder(selected: Boolean) = MaterialTheme.colorScheme.let { colors ->
    BorderStroke(if (selected) SelectedBorderWidth else 1.dp, if (selected) colors.primary else colors.outline)
}

@Composable
internal fun selectionChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true, selected = selected,
    borderColor = MaterialTheme.colorScheme.outline,
    selectedBorderColor = MaterialTheme.colorScheme.primary,
    // Material's default is 0dp, which draws a 1px hairline.
    selectedBorderWidth = SelectedBorderWidth,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderScreen(
    menu: MenuConfig,
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
    val allergens = menu.allergensOf(current)
    // The form stays readable but greyed out and locked; the dock says why.
    val soldOut = menu.dough?.isSoldOut == true

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .alpha(if (soldOut) 0.45f else 1f)
                .verticalScroll(rememberScrollState())
                .padding(start = FormSidePadding, end = FormSidePadding, top = FormTop, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Spacing tuned so the whole form fits one portrait screen of the 8.7" party tablet (~600×1000dp).
            Column(Modifier.widthIn(max = 920.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Section("01", "Dein Name", headerHeight = QuickMenuSize) {
                    CompactTextField(
                        value = customer,
                        onValueChange = { customer = it.take(NAME_MAX) },
                        placeholder = "z.B. Anna",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        enabled = !soldOut,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Dough sits where guests decide.
                Section("02", "Pizza wählen", trailing = {
                    menu.dough?.takeUnless { it.isSoldOut }?.let { d ->
                        Eyebrow(
                            "Noch ${d.available} ${if (d.available == 1) "Pizza" else "Pizzen"}",
                            maxLines = 1,
                            color = if (d.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }) {
                    // Eigenkreation only shows a diet while it's the one being built.
                    val building = pizzaName == CUSTOM_NAME
                    val tiles = menu.presets.map { p ->
                        val missing = menu.soldOutOf(p)
                        Tile(p.name, if (missing.isEmpty()) p.description else "Heute aus: ${missing.joinToString()}", menu.dietOf(p), menu.allergensOf(p), missing.isEmpty())
                    } + Tile(CUSTOM_NAME, "Sauce und Belag frei wählen", if (building) diet else Diet.NONE, if (building) allergens else emptySet(), true)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(3).forEach { row ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (name, desc, tileDiet, tileAllergens, available) ->
                                    PizzaCard(name, desc, tileDiet, tileAllergens, pizzaName == name, !soldOut && available, Modifier.weight(1f).fillMaxHeight().alpha(if (available) 1f else 0.45f)) {
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
                            Pill(s.name, s.name == sauceName, !soldOut, diet = menu.dietOf(s.name), allergens = menu.allergensOf(s.name), out = s.name in menu.soldOut) { sauceName = s.name }
                        }
                    }
                }

                Section("04", "Belag") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        menu.toppings.forEach { t ->
                            val selected = t.name in toppingNames
                            Pill(t.name, selected, !soldOut, if (selected) Icons.Default.Check else Icons.Default.Add, menu.dietOf(t.name), menu.allergensOf(t.name), out = t.name in menu.soldOut) {
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
                        // Keyboards that still send Enter as "\n" (or a pasted break) get no line break either.
                        onValueChange = { note = it.replace('\n', ' ').take(NOTE_MAX) },
                        placeholder = "z.B. extra knusprig, ohne Oregano…",
                        maxLines = 3,
                        // OK closes the keyboard instead of a line break: the note still wraps over up to 3 lines.
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        enabled = !soldOut,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Menu-style footnote: the leaves mark pizzas, sauces and toppings alike, so the key
                // closes the form instead of sitting in one section.
                DietLegend(menu, Modifier.align(Alignment.CenterHorizontally))
            }
        }

        // Dock: order summary + slide-to-order peel, pinned to the bottom. Page switch and settings
        // float top-right (MainActivity) and stay out of here.
        PeelDock(
            DockOrder(customer, pizzaName, sauceName, toppingNames, soldOut),
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

/** The letter in a ring that marks an allergen, e.g. "N" for Nüsse; [AllergenMarks] lines them up. */
@Composable
internal fun AllergenMark(allergen: Allergen, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    // Drawn by hand and centred on the glyph's ink bounds: a Text centres its line box, which leaves room
    // for descenders and line spacing, so a capital "N" sat visibly off-centre in the ring.
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT } }
    val bounds = remember { Rect() }
    // contentDescription: as a Canvas the mark has no text of its own for TalkBack.
    Canvas(modifier.size(16.dp).border(1.dp, color, CircleShape), contentDescription = allergen.label) {
        paint.color = color.toArgb()
        paint.textSize = 10.sp.toPx()
        paint.getTextBounds(allergen.short, 0, allergen.short.length, bounds)
        drawContext.canvas.nativeCanvas.drawText(
            allergen.short,
            center.x - bounds.exactCenterX(),
            center.y - bounds.exactCenterY(),
            paint,
        )
    }
}

/** Nothing for an empty set, so untagged ingredients stay as clean as before. */
@Composable
internal fun AllergenMarks(allergens: Set<Allergen>, modifier: Modifier = Modifier) {
    if (allergens.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        allergens.forEach { AllergenMark(it) }
    }
}

// Allergen keys only for those somewhere on the menu: a "N = Nüsse" without any nuts is noise.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DietLegend(menu: MenuConfig, modifier: Modifier = Modifier) {
    val used = menu.allergensInUse
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Diet.VEGAN, Diet.VEGETARIAN).forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(d.icon!!, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Allergen.entries.filter { it in used }.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AllergenMark(a)
                Text("Enthält ${a.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class Tile(val name: String, val description: String, val diet: Diet, val allergens: Set<Allergen>, val available: Boolean)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    errorContainerColor = MaterialTheme.colorScheme.surface,
)

/**
 * Material's outlined field at [ControlHeight]: OutlinedTextField forces a 56dp minimum and 16dp inner
 * padding, which made the inputs look oversized next to the 48dp order button and 40dp chips.
 */
@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) = AppTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = { Text(placeholder) },
    keyboardOptions = keyboardOptions,
    enabled = enabled,
    modifier = modifier,
    singleLine = singleLine,
    maxLines = maxLines,
    shape = RoundedCornerShape(14.dp),
    colors = fieldColors(),
    minHeight = ControlHeight,
    contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 12.dp, bottom = 12.dp),
)

/**
 * OutlinedTextField rebuilt from its own decoration box: it has no parameter for the focused border,
 * so [SelectedBorderWidth] can only reach the field through this container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    minHeight: Dp = OutlinedTextFieldDefaults.MinHeight,
    contentPadding: PaddingValues = OutlinedTextFieldDefaults.contentPadding(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    // The raised label sits half above the outline, so that half needs reserving, as OutlinedTextField does.
    val labelOverhang = if (label == null) 0.dp else with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier
            .semantics(mergeDescendants = label != null) { if (isError) error("Ungültige Eingabe") }
            .padding(top = labelOverhang)
            .heightIn(min = minHeight),
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        maxLines = maxLines,
        interactionSource = interactionSource,
    ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = innerTextField,
            enabled = enabled,
            singleLine = singleLine,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            isError = isError,
            label = label,
            placeholder = placeholder,
            supportingText = supportingText,
            colors = colors,
            contentPadding = contentPadding,
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = enabled,
                    isError = isError,
                    interactionSource = interactionSource,
                    colors = colors,
                    shape = shape,
                    focusedBorderThickness = SelectedBorderWidth,
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
private fun PizzaCard(name: String, description: String, diet: Diet, allergens: Set<Allergen>, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primaryContainer else colors.surface,
        border = selectionBorder(selected),
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
                AllergenMarks(allergens)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, enabled: Boolean, icon: ImageVector? = null, diet: Diet = Diet.NONE, allergens: Set<Allergen> = emptySet(), out: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled && !out,
        label = { Text(if (out) "$label · aus" else label, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) } },
        trailingIcon = if (diet.icon == null && allergens.isEmpty()) null else { {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                diet.icon?.let { Icon(it, diet.label, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
                AllergenMarks(allergens)
            }
        } },
        shape = CircleShape,
        // Disabled keeps the normal colours: the form's alpha does the greying, both together left the chips unreadable.
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = selectionChipBorder(selected),
        modifier = Modifier.heightIn(min = ChipHeight).alpha(if (out) 0.45f else 1f),
    )
}
