package io.github.kaerbr.pizzaorder

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kaerbr.pizzaorder.ui.FormSidePadding
import io.github.kaerbr.pizzaorder.ui.FormTop
import io.github.kaerbr.pizzaorder.ui.OrderConfirmation
import io.github.kaerbr.pizzaorder.ui.OrderScreen
import io.github.kaerbr.pizzaorder.ui.QuickMenuSize
import io.github.kaerbr.pizzaorder.ui.OrdersListScreen
import io.github.kaerbr.pizzaorder.ui.PatternLock
import io.github.kaerbr.pizzaorder.ui.SettingsScreen
import io.github.kaerbr.pizzaorder.ui.theme.Palette
import io.github.kaerbr.pizzaorder.ui.theme.PizzaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Party tablet sits on the table all evening: never dim or lock while the app is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            App(vm)
        }
    }

    // Kiosk-style: status and navigation bars stay hidden so the order form gets the whole screen.
    // A swipe from the edge shows them briefly. Re-applied on focus because dialogs and the
    // keyboard can bring them back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    val systemDark = isSystemInDarkTheme()
    // Appearance survives restarts; until the user picks, brightness follows the system.
    val prefs = LocalContext.current.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var darkTheme by remember(systemDark) { mutableStateOf(if (prefs.contains("dark")) prefs.getBoolean("dark", false) else systemDark) }
    var palette by remember { mutableStateOf(Palette.entries.find { it.name == prefs.getString("palette", null) } ?: Palette.Forno) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }

    val btLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* handled at print time */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
            )
        }
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLock by remember { mutableStateOf(false) }

    // Leaving the app (home button, screen off) always locks the admin panel.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { showSettings = false; showLock = false }

    PizzaTheme(darkTheme = darkTheme, palette = palette) {
        if (showLock) {
            PatternDialog(
                adminLock = vm.adminLock,
                onUnlocked = { showLock = false; showSettings = true },
                onDismiss = { showLock = false },
            )
        }
        if (showAppearance) {
            AppearanceDialog(
                dark = darkTheme,
                palette = palette,
                onDark = { darkTheme = it; prefs.edit { putBoolean("dark", it) } },
                onPalette = { palette = it; prefs.edit { putString("palette", it.name) } },
                onDismiss = { showAppearance = false },
            )
        }
        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScaffold(
                vm = vm,
                state = state,
                onClose = { showSettings = false },
            )
        } else {
            MainScaffold(
                state = state,
                pagerState = pagerState,
                onOpenSettings = { showLock = true },
                onOpenAppearance = { showAppearance = true },
                vm = vm,
            )
        }
    }
}

@Composable
private fun MainScaffold(
    state: AppState,
    pagerState: PagerState,
    onOpenSettings: () -> Unit,
    onOpenAppearance: () -> Unit,
    vm: AppViewModel,
) {
    val scope = rememberCoroutineScope()

    // Tab counter shows pending orders only — that's the actionable
    // queue. Done orders are still in the list but already handled.
    val pendingCount = state.orders.count { !it.isDone() }

    // No app bars at all: on the 8.7" tablet every dp goes to the order form. Page switch and settings
    // hide behind a small round button top-right, above the content, which both pages push down to
    // make room. Outside the pager so it stays put while the pages slide.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> OrderScreen(
                        menu = state.menu,
                        nextNumber = state.nextNumber,
                        onSubmit = vm::submitOrder,
                    )
                    else -> OrdersListScreen(
                        orders = state.orders,
                        onToggleDone = vm::toggleDone,
                        onReprint = vm::reprint,
                        prints = state.prints,
                    )
                }
            }
            QuickMenu(
                currentPage = pagerState.currentPage,
                pendingCount = pendingCount,
                onSelectPage = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                onOpenAppearance = onOpenAppearance,
                onOpenSettings = onOpenSettings,
            )
            // Last child, so the slip and its scrim cover the quick menu too.
            OrderConfirmation(
                confirmation = state.confirmation,
                printStatus = state.confirmation?.let { state.prints[it.order.id] },
                onDismiss = vm::dismissConfirmation,
            )
        }
    }
}

private val pageIcons = listOf(Icons.Default.LocalPizza, Icons.AutoMirrored.Filled.ReceiptLong)

/**
 * FAB-menu look, but top-right and opening downwards (Material's FloatingActionButtonMenu only opens
 * upwards, which would put it next to the order button). Collapsed it's a small neutral button with the
 * current page's icon and the pending count; any choice, a tap elsewhere or a few idle seconds close it.
 */
@Composable
private fun BoxScope.QuickMenu(
    currentPage: Int,
    pendingCount: Int,
    onSelectPage: (Int) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) { delay(6_000); open = false } }
    val colors = MaterialTheme.colorScheme

    // Tap anywhere else closes the menu; it swallows that tap so nothing behind gets hit by accident.
    if (open) Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) { open = false })

    Column(
        Modifier.align(Alignment.TopEnd).padding(top = FormTop, end = FormSidePadding),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Badge outside the Surface: the circle clips its content and would cut it off.
        BadgedBox(badge = { if (!open && pendingCount > 0) Badge { Text("$pendingCount") } }) {
            Surface(
                onClick = { open = !open },
                shape = CircleShape,
                color = if (open) colors.primaryContainer else colors.surface,
                contentColor = if (open) colors.primary else colors.onSurfaceVariant,
                border = BorderStroke(1.dp, colors.outline),
                shadowElevation = 2.dp,
                modifier = Modifier.size(QuickMenuSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Crossfade(open, label = "quickMenu") { isOpen ->
                        if (isOpen) Icon(Icons.Default.Close, contentDescription = "Menü schließen", Modifier.size(20.dp))
                        else Icon(pageIcons[currentPage], contentDescription = "Menü", Modifier.size(20.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            open,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Bestellen", "Bestellungen ($pendingCount)").forEachIndexed { page, label ->
                    QuickMenuItem(pageIcons[page], label, selected = currentPage == page) { open = false; onSelectPage(page) }
                }
                QuickMenuItem(Icons.Outlined.LightMode, "Aussehen") { open = false; onOpenAppearance() }
                QuickMenuItem(Icons.Outlined.AdminPanelSettings, "Admin-Panel") { open = false; onOpenSettings() }
            }
        }
    }
}

@Composable
private fun QuickMenuItem(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.primary else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outline),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.height(44.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AppearanceDialog(
    dark: Boolean,
    palette: Palette,
    onDark: (Boolean) -> Unit,
    onPalette: (Palette) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aussehen", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Hell für den Küchentisch, dunkel für den gedimmten Raum.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "Hell", true to "Dunkel").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = dark == value,
                            onClick = { onDark(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Palette.entries.forEach { p ->
                    val selected = p == palette
                    Surface(
                        onClick = { onPalette(p) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label, style = MaterialTheme.typography.headlineSmall)
                                Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            p.light.run { listOf(background, primary, onSurface) }.forEach { c ->
                                Spacer(Modifier.width(5.dp))
                                Box(Modifier.size(16.dp).clip(CircleShape).background(c).border(1.dp, Color.Black.copy(alpha = 0.14f), CircleShape))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Fertig") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** Draw the compiled-in pattern to unlock; checked as soon as the finger lifts. */
@Composable
private fun PatternDialog(adminLock: AdminLock, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text("Admin-Panel entsperren") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    error ?: "Muster zeichnen",
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PatternLock(
                    onPattern = { p -> adminLock.unlock(p).let { if (it == null) onUnlocked() else error = it } },
                    error = error != null,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    vm: AppViewModel,
    state: AppState,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AdminPanelSettings, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Admin-Panel", style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    TextButton(onClick = onClose) {
                        Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sperren & schließen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        SettingsScreen(
            menu = state.menu,
            orders = state.orders,
            onClearAllOrders = vm::clearAll,
            onAddSauce       = vm::addSauce,
            onRemoveSauce    = vm::removeSauce,
            onAddTopping     = vm::addTopping,
            onRemoveTopping  = vm::removeTopping,
            onSetDiet        = vm::setDiet,
            onAddPreset      = vm::addPreset,
            onRemovePreset   = vm::removePreset,
            onResetMenu      = vm::resetMenu,
            onSetDoughToday  = vm::setDoughToday,
            printStyle      = state.printStyle,
            onPrintStyle    = vm::setPrintStyle,
            onTestPrint      = { vm.testPrint() },
            testPrintStatus  = state.prints[TEST_PRINT_ID],
            onAddFunLink     = vm::addFunLink,
            onRemoveFunLink  = vm::removeFunLink,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}
