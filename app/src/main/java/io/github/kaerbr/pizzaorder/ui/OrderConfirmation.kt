package io.github.kaerbr.pizzaorder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.kaerbr.pizzaorder.Confirmation
import io.github.kaerbr.pizzaorder.PrintStatus
import io.github.kaerbr.pizzaorder.Printout
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Thermal paper stays paper in every palette and in dark mode: it's the printout, not app chrome.
private val Paper = Color(0xFFFFFDF7)
private val Ink = Color(0xFF1C1B1A)

/** Paper speed of the party printer (MUNBYN IMP001): the slip feeds in as fast as the real one comes out. */
private const val PRINT_SPEED_MM_PER_S = 90f
/** Full paper width; the printer prints on [Printout.PAPER_MM] of it. */
private const val PAPER_WIDTH_MM = 58f

/** How long the slip stays after a successful print before it rolls back up. */
private const val SHOW_AFTER_PRINT_MS = 2_500L

/** Button text for a print in flight, so every print button reports its own outcome in place. */
internal fun PrintStatus?.label(idle: String) = when (this) {
    PrintStatus.Printing -> "Druckt…"
    PrintStatus.Printed -> "Gedruckt"
    is PrintStatus.Failed -> "Nochmal drucken"
    null -> idle
}

internal val PrintStatus?.icon: ImageVector
    get() = when (this) {
        PrintStatus.Printed -> Icons.Default.Check
        is PrintStatus.Failed -> Icons.Default.Warning
        else -> Icons.Default.Print
    }

/**
 * The one confirmation in the app: after "Bestellen & drucken" the slip feeds down from the top edge
 * like out of the printer, drawn from the very markup the printer gets, so it looks like the paper one
 * whatever print style is set. It rolls back up on its own once the printer is done; a failed print
 * keeps it up until tapped.
 */
@Composable
fun OrderConfirmation(confirmation: Confirmation?, printStatus: PrintStatus?, onDismiss: () -> Unit) {
    // Keep the last one around so the slip still has content while it slides away.
    var shown by remember { mutableStateOf(confirmation) }
    if (confirmation != null) shown = confirmation
    val id = confirmation?.order?.id

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(id) {
        if (id != null) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    LaunchedEffect(id, printStatus) {
        if (id != null && printStatus == PrintStatus.Printed) {
            delay(SHOW_AFTER_PRINT_MS)
            onDismiss()
        }
    }

    val dismiss = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    AnimatedVisibility(confirmation != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(350))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).then(dismiss))
    }
    AnimatedVisibility(
        confirmation != null,
        // Feeding in is the slip's own job (paper speed needs its height); rolling back up is quick.
        enter = EnterTransition.None,
        exit = slideOutVertically(tween(350)) { -it } + fadeOut(tween(350)),
    ) {
        // Long styles (QR code, ASCII art) can outgrow the screen: the whole slip scrolls then.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(dismiss).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            shown?.let { Slip(it.printout) }
            PrintLine(printStatus)
        }
    }
}

/** 58 mm paper around the 48 mm the printer actually prints on; the margins are part of the look. */
@Composable
private fun Slip(markup: String) {
    val slipWidth = 400.dp
    val margin = slipWidth * ((PAPER_WIDTH_MM - Printout.PAPER_MM) / 2 / PAPER_WIDTH_MM)

    // Paper feed: slides down from above at the printer's speed, so a long slip takes longer, like on paper.
    // Own frame loop, not a tween: Android's animation-speed setting scales every Compose animation (0.5x
    // doubles the speed, off skips it), and this has to match a real printer, whatever that's set to.
    var feed by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(0) }
    val slipWidthPx = with(LocalDensity.current) { slipWidth.toPx() }
    LaunchedEffect(heightPx > 0) {
        if (heightPx == 0) return@LaunchedEffect
        val paperMm = heightPx / slipWidthPx * PAPER_WIDTH_MM
        val durationMs = paperMm / PRINT_SPEED_MM_PER_S * 1000
        val start = withFrameMillis { it }
        while (feed < 1f) feed = withFrameMillis { (it - start) / durationMs }.coerceAtMost(1f)
    }

    BoxWithConstraints(
        Modifier
            .onSizeChanged { heightPx = it.height }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, ((feed - 1f) * placeable.height).roundToInt())
                }
            }
            .width(slipWidth)
            .shadow(16.dp, TornEdge)
            .background(Paper, TornEdge)
            .padding(start = margin, end = margin, top = 28.dp, bottom = 36.dp),
    ) {
        // Read once here: inside Column the BoxWithConstraints scope isn't an implicit receiver.
        val paperWidth = maxWidth
        // Size the monospace font so exactly LINE_CHARS fit across, like the printer's 32 columns.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Pixels one monospace char takes per sp of font size (density and font scale included).
        // getLineRight, not size.width: the latter rounds up to whole pixels.
        val pxPerSp = remember(measurer, density) {
            measurer.measure("0".repeat(10), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 100.sp)).getLineRight(0) / 1000f
        }
        // 2 % slack: a full 32-char rule that fits to the pixel still wrapped its last "=" onto a new line.
        val fontSize = (with(density) { paperWidth.toPx() } / Printout.LINE_CHARS / pxPerSp * 0.98f).sp
        val lines = remember(markup) { Printout.parse(markup) }
        Column {
            lines.forEach { PaperLine(it, fontSize, paperWidth) }
        }
    }
}

@Composable
private fun PaperLine(line: Printout.Line, fontSize: TextUnit, paperWidth: Dp) {
    // An empty line is still a line of paper feed.
    val base = TextStyle(color = Ink, fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = 1.3.em)
    if (line.segments.isEmpty()) {
        BasicText(" ", style = base)
        return
    }
    Box(Modifier.fillMaxWidth()) {
        line.segments.forEach { seg ->
            val align = when (seg.align) {
                Printout.Align.Left -> Alignment.CenterStart
                Printout.Align.Center -> Alignment.Center
                Printout.Align.Right -> Alignment.CenterEnd
            }
            when (seg) {
                is Printout.QrSegment -> FakeQr(seg.url, paperWidth * (seg.sizeMm / Printout.PAPER_MM), Modifier.align(align).padding(vertical = 6.dp))
                is Printout.TextSegment -> {
                    // Line height follows the largest run, so "big" numbers don't overlap their neighbours.
                    val tallest = if (seg.runs.any { it.size != Printout.Size.Normal }) fontSize * 2 else fontSize
                    BasicText(
                        buildAnnotatedString {
                            seg.runs.forEach { run ->
                                withStyle(run.style(fontSize)) { append(run.text) }
                            }
                        },
                        style = base.copy(
                            fontSize = tallest,
                            textAlign = when (seg.align) {
                                Printout.Align.Left -> TextAlign.Start
                                Printout.Align.Center -> TextAlign.Center
                                Printout.Align.Right -> TextAlign.End
                            },
                        ),
                        // A lone segment spans the paper so long text wraps like on the printer.
                        modifier = Modifier.align(align).then(if (line.segments.size == 1) Modifier.fillMaxWidth() else Modifier),
                    )
                }
            }
        }
    }
}

// ESC/POS sizes: big = double width and height, tall = double height only.
private fun Printout.Run.style(fontSize: TextUnit) = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontSize = if (size == Printout.Size.Normal) fontSize else fontSize * 2,
    textGeometricTransform = if (size == Printout.Size.Tall) TextGeometricTransform(scaleX = 0.5f) else null,
)

/**
 * Stand-in for the printed QR code: same size and look, but not scannable (no QR encoder in the app).
 * The pattern is seeded from the link, so the slip stays the same while it's on screen.
 */
@Composable
private fun FakeQr(url: String, size: Dp, modifier: Modifier = Modifier) {
    val modules = 25
    val bits = remember(url) { java.util.Random(url.hashCode().toLong()).let { r -> List(modules * modules) { r.nextBoolean() } } }
    Canvas(modifier.size(size)) {
        val cell = this.size.width / modules
        fun inFinder(x: Int, y: Int) = (x < 8 && y < 8) || (x >= modules - 8 && y < 8) || (x < 8 && y >= modules - 8)
        for (y in 0 until modules) for (x in 0 until modules) {
            if (!inFinder(x, y) && bits[y * modules + x]) drawRect(Ink, Offset(x * cell, y * cell), Size(cell, cell))
        }
        listOf(Offset(0f, 0f), Offset((modules - 7) * cell, 0f), Offset(0f, (modules - 7) * cell)).forEach { o ->
            drawRect(Ink, o, Size(7 * cell, 7 * cell))
            drawRect(Paper, o + Offset(cell, cell), Size(5 * cell, 5 * cell))
            drawRect(Ink, o + Offset(2 * cell, 2 * cell), Size(3 * cell, 3 * cell))
        }
    }
}

/** Print state under the slip, on the scrim: the slip itself stays exactly the printout. */
@Composable
private fun PrintLine(status: PrintStatus?) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 400.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (status) {
                    PrintStatus.Printed -> Icon(Icons.Default.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    is PrintStatus.Failed -> Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    when (status) {
                        PrintStatus.Printed -> "Bon ist raus – guten Appetit!"
                        is PrintStatus.Failed -> "Druck fehlgeschlagen"
                        else -> "Bon wird gedruckt…"
                    },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (status is PrintStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (status is PrintStatus.Failed) {
                Text(
                    "${status.reason}\nUnter „Bestellungen“ nachdrucken. Tippen zum Schließen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Straight top (it comes out of the screen edge), sawtooth bottom like a slip torn off the printer. */
private val TornEdge = GenericShape { size, _ ->
    val tooth = size.width / 27
    val depth = tooth / 2
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height - depth)
    var x = size.width
    var down = true
    while (x > 0f) {
        x = (x - tooth / 2).coerceAtLeast(0f)
        lineTo(x, if (down) size.height else size.height - depth)
        down = !down
    }
    close()
}
