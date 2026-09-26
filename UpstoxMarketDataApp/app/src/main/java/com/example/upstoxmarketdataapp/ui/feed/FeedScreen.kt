package com.example.upstoxmarketdataapp.ui.feed


import android.graphics.Paint
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import com.example.upstoxmarketdataapp.R
import com.example.upstoxmarketdataapp.data.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    upstoxService: UpstoxService,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToScanner: () -> Unit = {}
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 800
    val isDark = MaterialTheme.colorScheme.onBackground == Color.White

    val coroutineScope = rememberCoroutineScope()
    val connectionStatus by upstoxService.connectionStatus.collectAsState(initial = "Disconnected")
    val optionsChain by upstoxService.optionsChainState.collectAsState()
    val pivotsState by upstoxService.pivotsState.collectAsState()
    val trades by upstoxService.trades.collectAsState()
    val userName by upstoxService.userName.collectAsState()
    val yoiProgress by upstoxService.yoiProgress.collectAsState()
    val repoState by upstoxService.instrumentRepository.repoState.collectAsState()
    val indexCandles by upstoxService.indexCandlesState.collectAsState()
    val index15sCandles by upstoxService.index15sCandlesState.collectAsState()
    val indexFutures by upstoxService.indexFuturesState.collectAsState()
    val isGuestUser by upstoxService.isGuestUser.collectAsState()

    

    var activeTab by remember { mutableStateOf(0) }
    var selectedIndexForChart by remember { mutableStateOf("NIFTY") }
    var selectedIndexForChain by remember { mutableStateOf("NIFTY") }
    var consoleLogs by remember { mutableStateOf(emptyList<String>()) }
    var consoleExpanded by remember { mutableStateOf(false) }
    
    var isChartMaximized by remember { mutableStateOf(false) }

    LaunchedEffect(isWideScreen) {
        if (isWideScreen) {
            kotlinx.coroutines.delay(1000)
            isChartMaximized = true
        } else {
            isChartMaximized = false
        }
    }

    // Collect debug logs
    LaunchedEffect(Unit) {
        upstoxService.rawLogs
            .onEach { log ->
                consoleLogs = (listOf(log) + consoleLogs).take(150)
            }
            .collect()
    }

    // Auto-init Instrument Repo if idle
    LaunchedEffect(Unit) {
        if (upstoxService.instrumentRepository.repoState.value is RepoState.Idle) {
            upstoxService.instrumentRepository.initialize()
        }
    }

    // Auto-connect to WebSocket & index data immediately on launch (zero delay, does not wait for repo)
    LaunchedEffect(Unit) {
        val token = upstoxService.getSavedAccessToken()
        if (token.isNotEmpty()) {
            upstoxService.logRaw("FeedScreen init: Connecting WebSocket and fetching index data immediately...")
            upstoxService.connect(token, emptySet())
        }
    }

    // Auto-Click Refresh Strikes until they populate (wait until repo is loaded)
    LaunchedEffect(connectionStatus, repoState) {
        if (repoState is RepoState.Success && connectionStatus == "Connected") {
            while (true) {
                val hasMissingStrikes = listOf("NIFTY", "SENSEX", "BANKNIFTY").any { 
                    upstoxService.fixedBaseStrike[it] == null || upstoxService.fixedBaseStrike[it] == 0.0 
                }
                if (hasMissingStrikes) {
                    upstoxService.logRaw("Auto-refresher: Missing strike labels, initiating strike population...")
                    upstoxService.manualRefreshStrikes()
                    kotlinx.coroutines.delay(8000) // Wait 8 seconds before retrying
                } else {
                    kotlinx.coroutines.delay(15000) // Check periodically without spamming
                }
            }
        }
    }

    // When repoState transitions to Success, refresh strikes or connect if not yet connected
    LaunchedEffect(repoState) {
        if (repoState is RepoState.Success) {
            val token = upstoxService.getSavedAccessToken()
            if (token.isNotEmpty()) {
                if (connectionStatus != "Connected") {
                    upstoxService.connect(token, emptySet())
                } else {
                    upstoxService.manualRefreshStrikes()
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val token = upstoxService.getSavedAccessToken()
                val isStale = (System.currentTimeMillis() - upstoxService.lastReceivedTickTime) > 6000L
                if (token.isNotEmpty() && (connectionStatus != "Connected" || isStale)) {
                    upstoxService.logRaw("App resumed (stale or disconnected): Auto-reconnecting WebSocket...")
                    upstoxService.connect(token, emptySet())
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            if (!isChartMaximized) {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "UTracker",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    userName.split(" ").first(),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            Text(
                                "WebSocket feed: $connectionStatus",
                                fontSize = 11.sp,
                                color = when {
                                    connectionStatus.startsWith("Connected") -> if (isDark) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF089981)
                                    connectionStatus.startsWith("Connecting") -> if (isDark) androidx.compose.ui.graphics.Color(0xFFFFD600) else androidx.compose.ui.graphics.Color(0xFFD97706)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    },
                    actions = {
                        val token = upstoxService.getSavedAccessToken()
                        if (token.isNotEmpty()) {
                            if (connectionStatus == "Connected") {
                                IconButton(
                                    onClick = {
                                        upstoxService.logRaw("Manual feed disconnect requested from top bar...")
                                        upstoxService.disconnect()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Disconnect Feed",
                                        tint = if (isDark) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFDC2626)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        upstoxService.logRaw("Manual feed connect requested from top bar...")
                                        upstoxService.connect(token, emptySet())
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Connect Feed",
                                        tint = if (isDark) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF089981)
                                    )
                                }
                            }
                        }

                        if (isWideScreen) {
                            IconButton(onClick = { isChartMaximized = !isChartMaximized }) {
                                Icon(Icons.Default.Search, contentDescription = "Toggle Fullscreen")
                            }
                        }

                        IconButton(onClick = { upstoxService.manualRefreshStrikes() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Feed")
                        }
                        IconButton(onClick = onNavigateToScanner) {
                            Icon(Icons.Default.Build, contentDescription = "TV Scanner")
                        }
                        IconButton(onClick = { /* upstoxService.clearAllData(); */ onLogout() }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        },
        bottomBar = {
            if (!isWideScreen) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    val tabs = listOf(
                        Triple(0, "F&O", Icons.Default.Home),
                        Triple(1, "EQ", Icons.Default.List),
                        Triple(2, "Option Chain", Icons.Default.Menu),
                        Triple(3, "Trading", Icons.Default.ShoppingCart),
                        Triple(4, "EQ-History", Icons.Default.PlayArrow),
                        Triple(5, "Settings", Icons.Default.Settings)
                    )
                    tabs.forEach { (index, label, icon) ->
                        NavigationBarItem(
                            selected = activeTab == index,
                            onClick = { activeTab = index },
                            label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                            icon = { Icon(icon, contentDescription = label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = if (isDark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                indicatorColor = if (isDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeTab) {
                0 -> PivotsAndChartTab(
                    upstoxService = upstoxService,
                    optionsChain = optionsChain,
                    pivotsState = pivotsState,
                    indexCandles = indexCandles,
                    index15sCandles = index15sCandles,
                    indexFutures = indexFutures,
                    yoiProgress = yoiProgress,
                    trades = trades,
                    isChartMaximized = isChartMaximized
                )
                1 -> EquityScreenerTab(
                    upstoxService = upstoxService
                )
                2 -> OptionChainTab(
                    upstoxService = upstoxService,
                    optionsChain = optionsChain,
                    index15sCandles = index15sCandles,
                    selectedIndex = selectedIndexForChain,
                    trades = trades,
                    onIndexChange = { selectedIndexForChain = it }
                )
                3 -> VirtualTradingTab(
                    upstoxService = upstoxService,
                    optionsChain = optionsChain,
                    trades = trades)
                4 -> EquityBacktestTab()
                5 -> SettingsAndLogsTab(
                    upstoxService = upstoxService,
                    consoleLogs = consoleLogs,
                    yoiProgress = yoiProgress,
                    repoState = repoState,
                    isWideScreen = isWideScreen,
                    onNavigateToScanner = onNavigateToScanner
                )
            }
            } // close Box
        }
    }
}


// ==========================================
// TAB 1: Pivots & Canvas Chart
// ==========================================
@Composable
fun PivotsAndChartTab(
    upstoxService: UpstoxService,
    optionsChain: Map<String, StrikeRowState>,
    pivotsState: Map<String, Map<String, PivotCalculator.Pivots>>,
    indexCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    index15sCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    indexFutures: Map<String, List<com.example.upstoxmarketdataapp.data.FutureData>>,
    yoiProgress: String,
    trades: List<com.example.upstoxmarketdataapp.data.Trade>,
    isChartMaximized: Boolean = false
) {
    var selectedQuickIndex by remember { mutableStateOf("NIFTY") }

    val productType by upstoxService.productType.collectAsState()
    val tradingMode by upstoxService.tradingMode.collectAsState()

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 800
    
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(isChartMaximized) {
        if (isChartMaximized) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    val LeftCharts: @Composable ColumnScope.(Boolean) -> Unit = { isFlexible ->
        val indices = if (isChartMaximized) listOf(selectedQuickIndex) else listOf("NIFTY", "SENSEX", "BANKNIFTY")
        indices.forEach { sym ->
            IndexDashboardCard(
                sym = sym,
                upstoxService = upstoxService,
                optionsChain = optionsChain,
                pivotsState = pivotsState,
                candles = indexCandles[sym] ?: emptyList(),
                futures = indexFutures[sym],
                isSelected = selectedQuickIndex == sym,
                modifier = if (isFlexible) Modifier.weight(1f) else Modifier,
                isFlexibleHeight = isFlexible,
                onClick = { selectedQuickIndex = sym }
            )
        }
    }

    val RightControls: @Composable ColumnScope.(Modifier, Modifier) -> Unit = { tableModifier, ledgerModifier ->
        // Global Trade Buttons & Ledger
        GlobalTradeButtonsRow(optionsChain, indexFutures, index15sCandles, upstoxService, trades)

        Spacer(modifier = Modifier.height(2.dp))

        // Options Trading Table for the selected chart
        QuickOptionsTradingTable(
            selectedIndex = selectedQuickIndex,
            optionsChain = optionsChain,
            upstoxService = upstoxService,
            tradingMode = tradingMode,
            productType = productType,
            pivotsState = pivotsState,
            modifier = tableModifier
        )

        Spacer(modifier = Modifier.height(8.dp))
        
        val title = if (tradingMode == "real") "Real Ledger" else "Virtual Ledger"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tradingMode == "virtual" && trades.any { !it.isClosed }) {
                    TextButton(
                        onClick = { upstoxService.squareOffAllTrades() },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Square Off All", color = androidx.compose.ui.graphics.Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                TextButton(
                    onClick = {
                        upstoxService.clearTrades()
                        android.widget.Toast.makeText(context, "Ledger reset: All trade data wiped", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Reset Ledger", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(modifier = ledgerModifier) {
            VirtualLedgerTable(trades = trades, tradingMode = tradingMode, upstoxService = upstoxService)
        }
    }

    if (isWideScreen) {
        val containerModifier = if (isChartMaximized) {
            Modifier.fillMaxSize()
                .padding(8.dp)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                selectedQuickIndex = when (selectedQuickIndex) {
                                    "NIFTY" -> "BANKNIFTY"
                                    "BANKNIFTY" -> "SENSEX"
                                    else -> "NIFTY"
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                selectedQuickIndex = when (selectedQuickIndex) {
                                    "NIFTY" -> "SENSEX"
                                    "SENSEX" -> "BANKNIFTY"
                                    else -> "NIFTY"
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        } else {
            Modifier.fillMaxSize().padding(8.dp)
        }

        Row(modifier = containerModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val leftWeight = if (isChartMaximized) 1f else 0.6f
            Column(
                modifier = Modifier.weight(leftWeight).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LeftCharts(true)
            }
            if (!isChartMaximized) {
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RightControls(Modifier.weight(0.6f).verticalScroll(rememberScrollState()), Modifier.weight(0.4f).verticalScroll(rememberScrollState()))
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LeftCharts(false)
            Spacer(modifier = Modifier.height(2.dp))
            RightControls(Modifier, Modifier)
        }
    }
}

@Composable
fun QuickOptionsTradingTable(
    selectedIndex: String,
    optionsChain: Map<String, StrikeRowState>,
    upstoxService: UpstoxService,
    tradingMode: String,
    productType: String,
    pivotsState: Map<String, Map<String, com.example.upstoxmarketdataapp.data.PivotCalculator.Pivots>> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val lotSizes = mapOf("NIFTY" to 65, "BANKNIFTY" to 30, "SENSEX" to 20)
    val lotSize = lotSizes[selectedIndex] ?: 50
    var quantityInput by remember(selectedIndex) { mutableStateOf(lotSize.toString()) }
    
    var placingOrderKey by remember { mutableStateOf<String?>(null) }
    val isOrderPlacing = placingOrderKey != null

    androidx.compose.runtime.LaunchedEffect(selectedIndex) {
        upstoxService.ensureStrikesForSymbol(selectedIndex)
    }

    val strikeOptions = remember(selectedIndex, optionsChain, upstoxService.fixedBaseStrike[selectedIndex]) {
        val baseStrike = upstoxService.fixedBaseStrike[selectedIndex]
            ?: upstoxService.getSavedBaseStrike(selectedIndex).takeIf { it > 0.0 }
            ?: (upstoxService.indexLtpMap[selectedIndex]?.let { upstoxService.instrumentRepository.getValidStrike(selectedIndex, it) })
            ?: 0.0
        val step = if (selectedIndex == "NIFTY") 50.0 else 100.0

        val offsets = mapOf(
            "5 Below" to -5, "4 Below" to -4, "3 Below" to -3, "ATM + 2" to -2, "ATM + 1" to -1,
            "ATM" to 0,
            "ATM - 1" to 1, "ATM - 2" to 2, "3 Above" to 3, "4 Above" to 4, "5 Above" to 5
        )

        upstoxService.strikeLabels.map { lbl ->
            val rowKey = "${selectedIndex}_$lbl"
            val rowStrike = optionsChain[rowKey]?.strikePrice ?: 0.0
            val strikeVal = if (rowStrike > 0.0) {
                rowStrike.toInt()
            } else if (baseStrike > 0.0) {
                val off = offsets[lbl] ?: 0
                (baseStrike + off * step).toInt()
            } else {
                0
            }

            val label = when {
                strikeVal > 0 && lbl == "ATM" -> "$strikeVal ATM"
                strikeVal > 0 -> "$strikeVal"
                else -> lbl
            }
            label to lbl
        }
    }

    var selectedLabel by remember(selectedIndex) { mutableStateOf("ATM") }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val isDark = MaterialTheme.colorScheme.onBackground == Color.White
    val callColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF089981)
    val putColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFDC2626)
    val expiryColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFB09540) else androidx.compose.ui.graphics.Color(0xFFD97706)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val rowKey = "${selectedIndex}_${selectedLabel}"
            val row = optionsChain[rowKey]
            val strikeText = if (row != null && row.strikePrice > 0.0) {
                row.strikePrice.toInt().toString()
            } else {
                strikeOptions.firstOrNull { it.second == selectedLabel }?.first?.replace(" ATM", "") ?: "--"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    val currentStrikeName = strikeOptions.firstOrNull { it.second == selectedLabel }?.first ?: "ATM"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$selectedIndex : $currentStrikeName",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Strike", modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        strikeOptions.forEach { (name, lbl) ->
                            DropdownMenuItem(
                                text = { Text(name, fontSize = 11.sp) },
                                onClick = {
                                    selectedLabel = lbl
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Strike text and executed amount to the left
                val quickTrades by upstoxService.trades.collectAsState()
                val indexExecAmount = quickTrades.filter { !it.isClosed && it.indexSymbol == selectedIndex }.sumOf { it.entryPrice * it.quantity }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (indexExecAmount > 0.0) {
                        Text(
                            text = "Exec: ₹${String.format(java.util.Locale.US, "%,.0f", indexExecAmount)} | ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (isDark) androidx.compose.ui.graphics.Color(0xFFFFD600) else MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Strike: $strikeText",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .height(24.dp)
                ) {
                    IconButton(
                        onClick = {
                            val currentVal = quantityInput.toIntOrNull() ?: lotSize
                            if (currentVal > lotSize) quantityInput = (currentVal - lotSize).toString()
                        },
                        modifier = Modifier.size(24.dp),
                        enabled = !isOrderPlacing
                    ) {
                        Text("-", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text(
                        text = quantityInput,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            val currentVal = quantityInput.toIntOrNull() ?: lotSize
                            quantityInput = (currentVal + lotSize).toString()
                        },
                        modifier = Modifier.size(24.dp),
                        enabled = !isOrderPlacing
                    ) {
                        Text("+", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CALLS", modifier = Modifier.weight(1f), color = callColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                val expiryText = row?.expiryStr ?: ""
                if (expiryText.isNotEmpty()) {
                    Text(expiryText.uppercase(), modifier = Modifier.weight(0.5f), color = expiryColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Text("PUTS", modifier = Modifier.weight(1f), color = putColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }

            if (row != null) {
                val ceData = row.ceData
                val peData = row.peData
                val ceLtp = ceData?.ltp ?: 0.0
                val peLtp = peData?.ltp ?: 0.0
                val ceToken = ceData?.instrumentKey ?: ""
                val peToken = peData?.instrumentKey ?: ""

                val ceLtpText = if (ceLtp > 0.0) String.format(java.util.Locale.US, "%.1f", ceLtp) else "--"
                val peLtpText = if (peLtp > 0.0) String.format(java.util.Locale.US, "%.1f", peLtp) else "--"
                val strikeText = if (row.strikePrice > 0.0) row.strikePrice.toInt().toString() else "--"
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // CALLS Column (Buy | LTP | Sell)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val buyCeKey = "BUY_CE_${selectedLabel}"
                        val isBuyCeLoading = placingOrderKey == buyCeKey
                        Button(
                            onClick = {
                                if (ceToken.isEmpty() || ceLtp <= 0.0) return@Button
                                val qty = quantityInput.toIntOrNull() ?: lotSize
                                if (tradingMode == "real") {
                                    placingOrderKey = buyCeKey
                                    coroutineScope.launch {
                                        try {
                                            val res = upstoxService.placeRealOrder(ceToken, "BUY", qty, productType)
                                            upstoxService.executeTrade(selectedIndex, "CE", row.strikePrice, ceToken, "BUY", qty, ceLtp)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        } finally { placingOrderKey = null }
                                    }
                                } else {
                                    upstoxService.executeTrade(selectedIndex, "CE", row.strikePrice, ceToken, "BUY", qty, ceLtp)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = callColor, contentColor = if (isDark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(24.dp),
                            enabled = ceToken.isNotEmpty() && ceLtp > 0.0 && (!isOrderPlacing || isBuyCeLoading)
                        ) {
                            if (isBuyCeLoading) CircularProgressIndicator(modifier = Modifier.size(10.dp), color = if (isDark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White, strokeWidth = 1.2.dp) else Text("B", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Text(ceLtpText, modifier = Modifier.weight(1.2f), color = if (ceLtp > 0.0) callColor else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

                        val sellCeKey = "SELL_CE_${selectedLabel}"
                        val isSellCeLoading = placingOrderKey == sellCeKey
                        Button(
                            onClick = {
                                if (ceToken.isEmpty() || ceLtp <= 0.0) return@Button
                                val qty = quantityInput.toIntOrNull() ?: lotSize
                                if (tradingMode == "real") {
                                    placingOrderKey = sellCeKey
                                    coroutineScope.launch {
                                        try {
                                            val res = upstoxService.placeRealOrder(ceToken, "SELL", qty, productType)
                                            upstoxService.executeTrade(selectedIndex, "CE", row.strikePrice, ceToken, "SELL", qty, ceLtp)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        } finally { placingOrderKey = null }
                                    }
                                } else {
                                    upstoxService.executeTrade(selectedIndex, "CE", row.strikePrice, ceToken, "SELL", qty, ceLtp)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = putColor, contentColor = androidx.compose.ui.graphics.Color.White),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(24.dp),
                            enabled = ceToken.isNotEmpty() && ceLtp > 0.0 && (!isOrderPlacing || isSellCeLoading)
                        ) {
                            if (isSellCeLoading) CircularProgressIndicator(modifier = Modifier.size(10.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 1.2.dp) else Text("S", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // PUTS Column (Buy | LTP | Sell)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val buyPeKey = "BUY_PE_${selectedLabel}"
                        val isBuyPeLoading = placingOrderKey == buyPeKey
                        Button(
                            onClick = {
                                if (peToken.isEmpty() || peLtp <= 0.0) return@Button
                                val qty = quantityInput.toIntOrNull() ?: lotSize
                                if (tradingMode == "real") {
                                    placingOrderKey = buyPeKey
                                    coroutineScope.launch {
                                        try {
                                            val res = upstoxService.placeRealOrder(peToken, "BUY", qty, productType)
                                            upstoxService.executeTrade(selectedIndex, "PE", row.strikePrice, peToken, "BUY", qty, peLtp)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        } finally { placingOrderKey = null }
                                    }
                                } else {
                                    upstoxService.executeTrade(selectedIndex, "PE", row.strikePrice, peToken, "BUY", qty, peLtp)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = callColor, contentColor = if (isDark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(24.dp),
                            enabled = peToken.isNotEmpty() && peLtp > 0.0 && (!isOrderPlacing || isBuyPeLoading)
                        ) {
                            if (isBuyPeLoading) CircularProgressIndicator(modifier = Modifier.size(10.dp), color = if (isDark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White, strokeWidth = 1.2.dp) else Text("B", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Text(peLtpText, modifier = Modifier.weight(1.2f), color = if (peLtp > 0.0) putColor else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

                        val sellPeKey = "SELL_PE_${selectedLabel}"
                        val isSellPeLoading = placingOrderKey == sellPeKey
                        Button(
                            onClick = {
                                if (peToken.isEmpty() || peLtp <= 0.0) return@Button
                                val qty = quantityInput.toIntOrNull() ?: lotSize
                                if (tradingMode == "real") {
                                    placingOrderKey = sellPeKey
                                    coroutineScope.launch {
                                        try {
                                            val res = upstoxService.placeRealOrder(peToken, "SELL", qty, productType)
                                            upstoxService.executeTrade(selectedIndex, "PE", row.strikePrice, peToken, "SELL", qty, peLtp)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        } finally { placingOrderKey = null }
                                    }
                                } else {
                                    upstoxService.executeTrade(selectedIndex, "PE", row.strikePrice, peToken, "SELL", qty, peLtp)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = putColor, contentColor = androidx.compose.ui.graphics.Color.White),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(24.dp),
                            enabled = peToken.isNotEmpty() && peLtp > 0.0 && (!isOrderPlacing || isSellPeLoading)
                        ) {
                            if (isSellPeLoading) CircularProgressIndicator(modifier = Modifier.size(10.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 1.2.dp) else Text("S", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text("No Data for $selectedLabel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            
            val pivots = pivotsState[selectedIndex]?.get("1D")
            if (pivots != null) {
                val s1 = pivots.supports.getOrNull(0)?.toInt()?.toString() ?: "--"
                val r1 = pivots.resistances.getOrNull(0)?.toInt()?.toString() ?: "--"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("S1: $s1", color = putColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("R1: $r1", color = callColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@Composable
fun IndexDashboardCard(
    sym: String,
    upstoxService: UpstoxService,
    optionsChain: Map<String, StrikeRowState>,
    pivotsState: Map<String, Map<String, PivotCalculator.Pivots>>,
    candles: List<com.example.upstoxmarketdataapp.data.Candle>,
    futures: List<com.example.upstoxmarketdataapp.data.FutureData>?,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    isFlexibleHeight: Boolean = false,
    onClick: () -> Unit
) {
    val trades by upstoxService.trades.collectAsState()
    val ltp = upstoxService.indexLtpMap[sym] ?: 0.0
    val pivots = pivotsState[sym]
    val isGuestUser by upstoxService.isGuestUser.collectAsState()

    val isDark = MaterialTheme.colorScheme.onBackground == Color.White

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(
                width = if (isSelected) 1.5.dp else 0.8.dp,
                color = if (isSelected) (if (isDark) Color(0xFF00E676) else Color(0xFF089981)) else MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.5f else 0.8f),
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val boxModifier = if (isFlexibleHeight) {
                Modifier.fillMaxWidth().weight(1f)
            } else {
                Modifier.fillMaxWidth().height(150.dp)
            }
            Box(
                modifier = boxModifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                    .padding(4.dp)
            ) {
                // Full-width Dual-layer Canvas Chart with inline S/R + LTP overlays
                DualLayerIndexChart(
                    sym = sym,
                    optionsChain = optionsChain,
                    candles = candles,
                    futures = futures,
                    pivots = pivots,
                    upstoxService = upstoxService
                )
            }
        }
    }
}

@Composable
fun DualLayerIndexChart(
    sym: String,
    optionsChain: Map<String, StrikeRowState>,
    candles: List<com.example.upstoxmarketdataapp.data.Candle>,
    futures: List<com.example.upstoxmarketdataapp.data.FutureData>?,
    pivots: Map<String, PivotCalculator.Pivots>?,
    upstoxService: UpstoxService
) {
    val trades by upstoxService.trades.collectAsState()
    val currentReplayTimestamp by upstoxService.currentReplayTimestampFlow.collectAsState()
    val liveSocketTickTime by upstoxService.liveSocketTickTime.collectAsState()
    val labels = upstoxService.strikeLabels
    val isDark = MaterialTheme.colorScheme.onBackground == Color.White
    val bullishCompose = if (isDark) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF089981)
    val bearishCompose = if (isDark) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFDC2626)
    val bullishAndroid = if (isDark) android.graphics.Color.parseColor("#00E676") else android.graphics.Color.parseColor("#089981")
    val bearishAndroid = if (isDark) android.graphics.Color.parseColor("#FF5252") else android.graphics.Color.parseColor("#DC2626")
    val dailyOhlcMap by upstoxService.dailyOhlcState.collectAsState()
    val liveDayOhlcMap by upstoxService.liveDayOhlcState.collectAsState()
    val dailyOhlc = dailyOhlcMap[sym]
    
    // Smooth autoscale state
    var maxAbs by remember { mutableStateOf(10.0) }
    var lastExpandTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(optionsChain, sym) {
        var currentMax = 1.0
        for (lbl in labels) {
            val key = "${sym}_$lbl"
            val row = optionsChain[key] ?: continue
            val ceD = Math.abs(row.ceDiff)
            val peD = Math.abs(row.peDiff)
            if (ceD > currentMax) currentMax = ceD
            if (peD > currentMax) currentMax = peD
        }
        val targetMax = currentMax + 1.5
        val now = System.currentTimeMillis()

        if (targetMax > maxAbs) {
            maxAbs = targetMax
            lastExpandTime = now
        } else if (now - lastExpandTime > 8000) {
            maxAbs = (1.0 - 0.15) * maxAbs + 0.15 * targetMax
        }
    }

    val padBottom = maxAbs * 0.1
    val padTop = maxAbs * 0.25
    val minY = -(maxAbs + padBottom)
    val maxY = maxAbs + padTop

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val paddingX = 40f
        val paddingY = 40f
        val paddingLeft = 140f
        val paddingRight = 240f

        val plotWidth = width - paddingLeft - paddingRight
        val plotHeight = height - 2 * paddingY

        // ============================================================
        // LAYER 1: Background 1-Minute Index Price Candles (Low Opacity)
        // ============================================================
        if (candles.isNotEmpty()) {
            val dOhlcs = dailyOhlcMap[sym] ?: emptyList()
            val minPrice = if (dOhlcs.isNotEmpty()) minOf(candles.minOf { it.low }, dOhlcs.minOf { it.low }) else candles.minOf { it.low }
            val maxPrice = if (dOhlcs.isNotEmpty()) maxOf(candles.maxOf { it.high }, dOhlcs.maxOf { it.high }) else candles.maxOf { it.high }
            val priceRange = if (maxPrice > minPrice) maxPrice - minPrice else 1.0
            
            // Add padding to price scale
            val chartMinPrice = minPrice - priceRange * 0.05
            val chartMaxPrice = maxPrice + priceRange * 0.05
            val priceSpan = chartMaxPrice - chartMinPrice

            fun getPriceY(price: Double): Float {
                val frac = (price - chartMinPrice) / priceSpan
                return (height - paddingY - frac * plotHeight).toFloat()
            }

            val candleWidth = plotWidth / candles.size
            
            // Background candles have been removed as per user request to clean the chart.
        }

        // S/R Calculation & Daily Candlestick Sidebar
        val ladder = upstoxService.pivotLadderMap[sym]
            val sup = ladder?.support
            val res = ladder?.resistance

            val srLabelPaint = android.graphics.Paint().apply {
                textSize = 17f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.RIGHT
                isAntiAlias = true
            }

            val ltpLinePaint = android.graphics.Paint().apply {
                textSize = 17f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.LEFT
                isAntiAlias = true
            }

            // Draw Gauge Line and Daily Candlestick on the right
            val historicalOhlcs = dailyOhlcMap[sym] ?: emptyList()
            val liveCandleFromSession = liveDayOhlcMap[sym]
            val currentLtpForMerge = candles.lastOrNull()?.close ?: historicalOhlcs.lastOrNull()?.close ?: 0.0
            
            val dailyOhlcs = if (historicalOhlcs.isNotEmpty()) {
                val baseLiveCandle = historicalOhlcs.last()
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Calendar.getInstance().time)
                val isBaseToday = baseLiveCandle.timestamp.startsWith(todayStr)
                
                if (isBaseToday) {
                    val pastOhlcs = historicalOhlcs.dropLast(1)
                    val mergedLiveCandle = if (liveCandleFromSession != null) {
                        baseLiveCandle.copy(
                            high = maxOf(baseLiveCandle.high, liveCandleFromSession.high),
                            low = minOf(baseLiveCandle.low, liveCandleFromSession.low),
                            close = currentLtpForMerge
                        )
                    } else {
                        baseLiveCandle.copy(close = currentLtpForMerge)
                    }
                    (pastOhlcs + mergedLiveCandle).takeLast(5)
                } else {
                    val pastOhlcs = historicalOhlcs
                    val todayCandle = if (liveCandleFromSession != null) {
                        liveCandleFromSession.copy(close = currentLtpForMerge)
                    } else {
                        com.example.upstoxmarketdataapp.data.IndexOhlc(timestamp = todayStr, open = currentLtpForMerge, high = currentLtpForMerge, low = currentLtpForMerge, close = currentLtpForMerge)
                    }
                    (pastOhlcs + todayCandle).takeLast(5)
                }
            } else {
                emptyList()
            }
            
            if (dailyOhlcs.isNotEmpty()) {
                val latestOhlc = dailyOhlcs.last()
                val currentLtp = candles.lastOrNull()?.close ?: latestOhlc.close
                val isBullish = currentLtp >= latestOhlc.open
                
                // Get two levels of Support and Resistance from the dynamic ladder
                val sorted = ladder?.sortedPivots
                var s2: Double? = null
                var r2: Double? = null
                if (sorted != null && sup != null && res != null) {
                    val sIdx = sorted.indexOf(sup)
                    if (sIdx > 0) s2 = sorted[sIdx - 1]
                    
                    val rIdx = sorted.indexOf(res)
                    if (rIdx != -1 && rIdx < sorted.size - 1) r2 = sorted[rIdx + 1]
                }
                
                val rawGaugeRes = r2 ?: res ?: latestOhlc.high
                val rawGaugeSup = s2 ?: sup ?: latestOhlc.low
                val gaugeRes = maxOf(rawGaugeRes, currentLtp + 1.0)
                val gaugeSup = minOf(rawGaugeSup, currentLtp - 1.0)
                
                // Adjust High/Low to encompass the entire day properly on chart
                val maxHigh = maxOf(latestOhlc.high, currentLtp, gaugeRes)
                val minLow = minOf(latestOhlc.low, currentLtp, gaugeSup)
                
                val currentZeroY = (height - paddingY - ((0.0 - minY) / (maxY - minY)) * plotHeight).toFloat()
                val maxVisualRadius = minOf(currentZeroY - paddingY, (height - paddingY) - currentZeroY) - 10f
                
                // Define the physical bounds of the gauge line
                val gaugeTopVisualY = currentZeroY - maxVisualRadius
                val gaugeBottomVisualY = currentZeroY + maxVisualRadius
                val gaugeRange = maxOf(gaugeRes - gaugeSup, 0.001)
                
                // Proportional scaler: p=gaugeSup -> gaugeBottomVisualY, p=gaugeRes -> gaugeTopVisualY
                fun getCenteredCandleY(p: Double): Float {
                    val frac = (p - gaugeSup) / gaugeRange
                    return gaugeBottomVisualY - (frac * (gaugeBottomVisualY - gaugeTopVisualY)).toFloat()
                }
                
                // Set the X coordinates: Chart on left (of the right padding), Gauge on far right
                val startCandleX = width - 210f
                val gaugeX = width - 30f
                
                // --- 1. Draw the Gauge Line (Left) ---
                val yGaugeTop = getCenteredCandleY(gaugeRes)
                val yGaugeBottom = getCenteredCandleY(gaugeSup)
                val yLtp = getCenteredCandleY(currentLtp)
                
                // Set text alignment to right so it draws to the left of gaugeX
                srLabelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                ltpLinePaint.textAlign = android.graphics.Paint.Align.RIGHT
                
                val themeColorGraph = if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF334155)
                val themeColorAndroid = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#334155")
                val gaugeLineColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF475569) else androidx.compose.ui.graphics.Color(0xFFCBD5E1)
                
                // The main vertical gauge line (Fixed Top=R2, Bottom=S2)
                drawLine(
                    color = gaugeLineColor,
                    start = Offset(gaugeX, gaugeTopVisualY),
                    end = Offset(gaugeX, gaugeBottomVisualY),
                    strokeWidth = 3f
                )

                fun drawTick(value: Double?, isSupport: Boolean) {
                    if (value == null) return
                    val y = getCenteredCandleY(value)
                    // Highlight ONLY when current LTP touches or crosses the support/resistance level
                    val isTouched = if (isSupport) {
                        currentLtp <= value || Math.abs(currentLtp - value) <= 1.5
                    } else {
                        currentLtp >= value || Math.abs(currentLtp - value) <= 1.5
                    }

                    if (isTouched) {
                        // Yellow highlight ONLY when touched by LTP
                        val yellowGlow = androidx.compose.ui.graphics.Color(0xFFFFD600)
                        val yellowAndroid = android.graphics.Color.parseColor("#FFD600")

                        // Soft yellow halo glow behind the horizontal line
                        drawLine(
                            color = yellowGlow.copy(alpha = 0.5f),
                            start = Offset(gaugeX - 18f, y),
                            end = Offset(gaugeX + 18f, y),
                            strokeWidth = 9f
                        )
                        // Sharp bright yellow glowing horizontal line
                        drawLine(
                            color = yellowGlow,
                            start = Offset(gaugeX - 14f, y),
                            end = Offset(gaugeX + 14f, y),
                            strokeWidth = 4.5f
                        )
                        srLabelPaint.color = yellowAndroid
                        srLabelPaint.isFakeBoldText = true
                    } else {
                        drawLine(
                            color = themeColorGraph,
                            start = Offset(gaugeX - 8f, y),
                            end = Offset(gaugeX + 8f, y),
                            strokeWidth = 3f
                        )
                        srLabelPaint.color = themeColorAndroid
                        srLabelPaint.isFakeBoldText = false
                    }
                    drawContext.canvas.nativeCanvas.drawText(value.toInt().toString(), gaugeX - 18f, y + 6f, srLabelPaint)
                }

                // Resistance and Support Markers (with glowing horizontal lines)
                drawTick(r2, isSupport = false)
                drawTick(res, isSupport = false)
                drawTick(sup, isSupport = true)
                drawTick(s2, isSupport = true)
                
                // LTP Marker on the Gauge (Dotted Line)
                val ltpColorCompose = if (isBullish) bullishCompose else bearishCompose
                val ltpColorAndroid = if (isBullish) bullishAndroid else bearishAndroid
                
                drawLine(
                    color = ltpColorCompose,
                    start = Offset(gaugeX - 25f, yLtp),
                    end = Offset(gaugeX + 25f, yLtp),
                    strokeWidth = 5f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
                ltpLinePaint.color = ltpColorAndroid
                drawContext.canvas.nativeCanvas.drawText(currentLtp.toInt().toString(), gaugeX - 32f, yLtp + 6f, ltpLinePaint)


                // --- 2. Draw the smaller standard Candlesticks (Right) ---
                val candleW = 10f
                val candleMaxHalfPx = plotHeight * 0.30f
                
                // We calculate an absolute min/max across all visible dailyOhlcs to keep them scaled proportionally
                val overallMin = dailyOhlcs.minOf { it.low }
                val overallMax = dailyOhlcs.maxOf { it.high }
                val overallRange = maxOf(overallMax - overallMin, 1.0)
                
                fun getMultiCandlePixelY(price: Double): Float {
                    val frac = (price - overallMin) / overallRange
                    return (currentZeroY + candleMaxHalfPx - frac * 2f * candleMaxHalfPx).toFloat()
                }

                dailyOhlcs.forEachIndexed { i, ohlc ->
                    val cX = startCandleX + (i * 20f)
                    
                    val isDayBullish = ohlc.close >= ohlc.open
                    val candleColor = if (isDayBullish) bullishCompose else bearishCompose
                    
                    val yHigh = getMultiCandlePixelY(ohlc.high)
                    val yLow = getMultiCandlePixelY(ohlc.low)
                    val yOpen = getMultiCandlePixelY(ohlc.open)
                    
                    // The last candle uses currentLtp for its close, others use their historical close
                    val endClose = if (i == dailyOhlcs.size - 1) currentLtp.coerceIn(ohlc.low, ohlc.high) else ohlc.close
                    val yClose = getMultiCandlePixelY(endClose)
                    
                    val clampTop = paddingY
                    val clampBot = height - paddingY
                    val yHighC  = yHigh.coerceIn(clampTop, clampBot)
                    val yLowC   = yLow.coerceIn(clampTop, clampBot)
                    val yOpenC  = yOpen.coerceIn(clampTop, clampBot)
                    val yCloseC = yClose.coerceIn(clampTop, clampBot)
                    
                    val bodyTop = minOf(yOpenC, yCloseC)
                    val bodyBottom = maxOf(yOpenC, yCloseC)
                    val bodyHeight = maxOf(Math.abs(yCloseC - yOpenC), 6f)
                    
                    // Draw Wick
                    drawLine(
                        color = candleColor,
                        start = Offset(cX, yHighC),
                        end = Offset(cX, yLowC),
                        strokeWidth = 2f
                    )
                    
                    // Draw Body
                    drawRect(
                        color = candleColor,
                        topLeft = Offset(cX - (candleW / 2f), bodyTop),
                        size = androidx.compose.ui.geometry.Size(candleW, bodyHeight)
                    )
                }
            }

        // Draw last ticker time on top right (True WebSocket arrival time)
        val displayTimeMillis = when {
            upstoxService.isReplaying && currentReplayTimestamp > 0L -> currentReplayTimestamp
            liveSocketTickTime > 0L -> liveSocketTickTime
            else -> System.currentTimeMillis()
        }
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val timeText = sdf.format(java.util.Date(displayTimeMillis))

        val timePaint = android.graphics.Paint().apply {
            color = if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.parseColor("#64748B")
            textSize = 20f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        drawContext.canvas.nativeCanvas.drawText(timeText, width - 10f, 30f, timePaint)

        // ============================================================
        // LAYER 2: Foreground Options Calculation Lines, Zero Line, Dots
        // ============================================================
        fun getCoordY(value: Double): Float {
            val yFraction = (value - minY) / (maxY - minY)
            return (height - paddingY - yFraction * plotHeight).toFloat()
        }

        val zeroY = getCoordY(0.0)

        // Calculate Watermark Logic conditions for Futures
        var greenFuturesCount = 0
        var redFuturesCount = 0
        if (futures != null && futures.isNotEmpty()) {
            for (f in futures) {
                if (f.diff >= 0) greenFuturesCount++ else redFuturesCount++
            }
        }
        val allFuturesGreen = (futures != null && futures.isNotEmpty() && greenFuturesCount == futures.size)
        val allFuturesRed = (futures != null && futures.isNotEmpty() && redFuturesCount == futures.size)

        // ============================================================
        // LAYER 1.5: Futures (Vertical bars on the left edge based on diff)
        // ============================================================
        if (futures != null && futures.isNotEmpty()) {
            val futurePaintBase = android.graphics.Paint().apply {
                textSize = 18f
                isAntiAlias = true
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                textAlign = android.graphics.Paint.Align.LEFT
            }
            
            // X positions for the 3 futures
            val futureStartX = 25f
            val futureSpacing = 30f
            
            val sortedFutures = futures.reversed()
            val maxFutureAbs = sortedFutures.maxOfOrNull { Math.abs(it.diff) } ?: 1.0
            val scaleFactor = if (maxFutureAbs > maxY) (maxY * 0.9) / maxFutureAbs else 1.0
            
            // Draw "Futures" title (Highlighted)
            val futuresTitlePaint = android.graphics.Paint().apply {
                color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#0284C7")
                textSize = 15f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.LEFT
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            drawContext.canvas.nativeCanvas.drawText("Futures", futureStartX, 30f, futuresTitlePaint)
            
            for (i in 0 until minOf(3, sortedFutures.size)) {
                val fut = sortedFutures[i]
                val xPos = futureStartX + (i * futureSpacing)
                
                val startY = zeroY
                val scaledDiff = fut.diff * scaleFactor
                var endY = getCoordY(scaledDiff)
                
                // Cap futures top margin to avoid hitting "Futures" text
                val minFuturesY = 50f
                if (endY < minFuturesY) endY = minFuturesY
                
                val isPositive = fut.diff >= 0
                val col = if (isPositive) bullishCompose else bearishCompose
                
                // Draw vertical thick line (bar)
                drawLine(
                    color = col,
                    start = Offset(xPos, startY),
                    end = Offset(xPos, endY),
                    strokeWidth = 20f,
                    alpha = 0.9f
                )
                
                val futPaint = android.graphics.Paint(futurePaintBase).apply {
                    color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#0F172A")
                }
                
                val roundedDiff = Math.abs(kotlin.math.round(fut.diff).toInt())
                val diffStr = "$roundedDiff"
                
                // Rotate canvas 90 degrees to draw text vertically
                drawContext.canvas.nativeCanvas.save()
                
                val textX = xPos
                // Anchor text above/below the bar appropriately
                val textY = if (isPositive) endY - 15f else endY + 25f
                
                drawContext.canvas.nativeCanvas.rotate(-90f, textX, textY)
                val textOffsetY = textY + (futPaint.textSize / 3f)
                drawContext.canvas.nativeCanvas.drawText(diffStr, textX, textOffsetY, futPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }

        // Zero baseline
        drawLine(
            color = if (isDark) androidx.compose.ui.graphics.Color(0xFF334155) else androidx.compose.ui.graphics.Color(0xFF94A3B8),
            start = Offset(paddingLeft, zeroY),
            end = Offset(width - paddingRight, zeroY),
            strokeWidth = 3f
        )

        val cePoints = mutableListOf<Offset>()
        val pePoints = mutableListOf<Offset>()
        val strikeXCoords = mutableListOf<Float>()
        val strikeValuesText = mutableListOf<String>()

        val strikeCount = labels.size
        val xStep = plotWidth / (strikeCount - 1)
        val baseStrike = upstoxService.fixedBaseStrike[sym]

        var buyCallDots = 0
        var buyPutDots = 0
        var ceDiffPositiveCount = 0
        var peDiffPositiveCount = 0

        // Find max Net OI Change magnitude to scale baseline dots beautifully
        val maxNetOiChange = labels.maxOfOrNull { lbl ->
            val row = optionsChain["${sym}_$lbl"]
            Math.abs(row?.netOiChange ?: 0.0)
        } ?: 1.0

        for (i in labels.indices) {
            val lbl = labels[i]
            val key = "${sym}_$lbl"
            val row = optionsChain[key]
            val ceDiff = row?.ceDiff ?: 0.0
            val peDiff = row?.peDiff ?: 0.0
            
            if (ceDiff > 0) ceDiffPositiveCount++
            if (peDiff > 0) peDiffPositiveCount++

            val x = paddingLeft + i * xStep
            strikeXCoords.add(x)
            cePoints.add(Offset(x, getCoordY(ceDiff)))
            pePoints.add(Offset(x, getCoordY(peDiff)))

            // Strike labels (Fall back to calculating using baseStrike if options chain is empty)
            val strikeText = if (row != null && row.strikePrice > 0.0) {
                row.strikePrice.toInt().toString()
            } else if (baseStrike != null && baseStrike > 0.0) {
                val step = if (sym == "NIFTY") 50.0 else 100.0
                val offset = i - 5 // index of "ATM" is 5
                val calculatedStrike = baseStrike + offset * step
                calculatedStrike.toInt().toString()
            } else {
                ""
            }
            // Hide alternate strikes, but ensure center is visible
            val centerIndex = strikeCount / 2
            val isCenter = (i == centerIndex)
            val shouldShow = isCenter || (Math.abs(i - centerIndex) % 2 == 0)
            if (shouldShow && strikeText.isNotEmpty()) {
                strikeValuesText.add(strikeText)
            } else {
                strikeValuesText.add("")
            }

            // Draw Net OI Change dot ONLY if there is an active signal matching the logic
            val signal = row?.activeSignal
            if (signal != null && signal.type != "NONE") {
                if (signal.type == "BUY CALL") buyCallDots++
                if (signal.type == "BUY PUT") buyPutDots++

                val netOi = row.netOiChange
                val dotColor = if (signal.type == "BUY CALL") bullishCompose else bearishCompose
                
                val radius = if (maxNetOiChange > 0.0) {
                    (Math.abs(netOi) / maxNetOiChange * 11f + 5f).toFloat()
                } else {
                    8f
                }
                
                drawCircle(
                    color = dotColor,
                    radius = radius,
                    center = Offset(x, zeroY)
                )
            }
        }

        // Draw Watermark Top Center
        var watermarkText = ""
        var watermarkColor = android.graphics.Color.TRANSPARENT

        val threshold80Percent = (strikeCount * 0.8).toInt()

        if (allFuturesGreen && buyCallDots >= 3 && buyPutDots == 0 && ceDiffPositiveCount >= threshold80Percent) {
            watermarkText = "BUY CE"
            watermarkColor = if (isDark) android.graphics.Color.argb(90, 150, 150, 150) else android.graphics.Color.argb(45, 15, 23, 42)
        } else if (allFuturesRed && buyPutDots >= 3 && buyCallDots == 0 && peDiffPositiveCount >= threshold80Percent) {
            watermarkText = "BUY PE"
            watermarkColor = if (isDark) android.graphics.Color.argb(90, 150, 150, 150) else android.graphics.Color.argb(45, 15, 23, 42)
        }

        if (watermarkText.isNotEmpty()) {
            val wmPaint = android.graphics.Paint().apply {
                color = watermarkColor
                textSize = 28f
                isAntiAlias = true
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            drawContext.canvas.nativeCanvas.drawText(watermarkText, width - 120f, 30f, wmPaint)
        }

        // Draw CE lines
        for (i in 0 until cePoints.size - 1) {
            drawLine(
                color = bullishCompose,
                start = cePoints[i],
                end = cePoints[i + 1],
                strokeWidth = 3f
            )
        }

        // Draw PE lines
        for (i in 0 until pePoints.size - 1) {
            drawLine(
                color = bearishCompose,
                start = pePoints[i],
                end = pePoints[i + 1],
                strokeWidth = 3f
            )
        }

        val textPaint = Paint().apply {
            color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#0F172A")
            textSize = 18f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val strikePaint = Paint().apply {
            color = if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.parseColor("#64748B")
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }

        val vertIndices = listOf(0, 3, 5, 7, 10)
        for (i in labels.indices) {
            val key = "${sym}_${labels[i]}"
            val row = optionsChain[key]
            
            if (i in vertIndices) {
                val ceDiffVal = row?.ceDiff ?: 0.0
                val peDiffVal = row?.peDiff ?: 0.0
                
                drawContext.canvas.nativeCanvas.drawText(
                    Math.round(ceDiffVal).toString(),
                    strikeXCoords[i],
                    getCoordY(ceDiffVal) - 8f,
                    textPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    Math.round(peDiffVal).toString(),
                    strikeXCoords[i],
                    getCoordY(peDiffVal) + 16f,
                    textPaint
                )
            }

            val strikeText = strikeValuesText[i]
            if (strikeText.isNotEmpty()) {
                drawContext.canvas.nativeCanvas.drawText(
                    strikeText,
                    strikeXCoords[i],
                    height - 8f,
                    strikePaint
                )
            }
        }

        val headerPaint = Paint().apply {
            color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#0F172A")
            textSize = 20f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(sym, width / 2f, paddingY - 15f, headerPaint)
        
        val activeTrades = trades.filter { !it.isClosed && it.indexSymbol == sym }
        val latestOhlcForText = dailyOhlcMap[sym]?.lastOrNull()
        val currentLtpForText = candles.lastOrNull()?.close ?: latestOhlcForText?.close ?: 0.0
          
        if (activeTrades.isNotEmpty()) {
            val totalPnl = activeTrades.sumOf { it.pnl }
            val totalQty = activeTrades.sumOf { it.quantity }
            val totalExecutedAmount = activeTrades.sumOf { it.entryPrice * it.quantity }
            val avgEntry = totalExecutedAmount / totalQty
            val avgLtp = activeTrades.sumOf { it.currentPrice * it.quantity } / totalQty
            
            val pnlStr = String.format(java.util.Locale.US, "%s%.2f", if(totalPnl >= 0.0) "+" else "", totalPnl)
            val pnlColor = if (totalPnl >= 0.0) bullishAndroid else bearishAndroid
            
            val infoText = "Exec: ₹${String.format(java.util.Locale.US, "%,.0f", totalExecutedAmount)} | Entry: ${String.format(java.util.Locale.US, "%.2f", avgEntry)} | Qty: $totalQty | LTP: ${String.format(java.util.Locale.US, "%.2f", avgLtp)}"
            val infoPaint = Paint().apply {
                color = if (isDark) android.graphics.Color.parseColor("#90A4AE") else android.graphics.Color.parseColor("#607D8B")
                textSize = 14f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(infoText, width / 2f, paddingY + 6f, infoPaint)
            
            val pnlPaint = Paint().apply {
                color = pnlColor
                textSize = 18f
                textAlign = Paint.Align.LEFT
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                isAntiAlias = true
            }
            val symWidth = headerPaint.measureText(sym)
            drawContext.canvas.nativeCanvas.drawText("  $pnlStr", (width / 2f) + (symWidth / 2f), paddingY - 15f, pnlPaint)
        }
    }
}

// ==========================================
// TAB 2: Option Chain Grid
// ==========================================
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OptionChainTab(
    upstoxService: UpstoxService,
    optionsChain: Map<String, StrikeRowState>,
    index15sCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    selectedIndex: String,
    trades: List<com.example.upstoxmarketdataapp.data.Trade> = emptyList(),
    onIndexChange: (String) -> Unit
) {
    val indices = listOf("NIFTY", "SENSEX", "BANKNIFTY")
    val initialPage = indices.indexOf(selectedIndex).coerceAtLeast(0)
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = initialPage,
        pageCount = { indices.size }
    )
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val productType by upstoxService.productType.collectAsState()
    val tradingMode by upstoxService.tradingMode.collectAsState()
    val supertrendCalculator = androidx.compose.runtime.remember { com.example.upstoxmarketdataapp.simulator.SupertrendCalculator(10, 3.0) }

    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        if (indices[pagerState.currentPage] != selectedIndex) {
            onIndexChange(indices[pagerState.currentPage])
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tab header selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            indices.forEachIndexed { index, sym ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { 
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        sym,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Horizontal Pager for the content
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            val pageSym = indices[page]
            
            // Calculate Supertrend for the current index
            val candles15s = index15sCandles[pageSym] ?: emptyList()
            val supertrendResult = supertrendCalculator.calculate(candles15s)
            val trendText = when (supertrendResult?.state) {
                com.example.upstoxmarketdataapp.simulator.SupertrendState.BULLISH -> "UP"
                com.example.upstoxmarketdataapp.simulator.SupertrendState.BEARISH -> "DOWN"
                else -> "--"
            }
            val trendColor = when (supertrendResult?.state) {
                com.example.upstoxmarketdataapp.simulator.SupertrendState.BULLISH -> androidx.compose.ui.graphics.Color(0xFF00E676)
                com.example.upstoxmarketdataapp.simulator.SupertrendState.BEARISH -> androidx.compose.ui.graphics.Color(0xFFFF5252)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            val pageTrades = trades.filter { !it.isClosed && it.indexSymbol == pageSym }
            val pageExecAmount = pageTrades.sumOf { it.entryPrice * it.quantity }
            val stValue = supertrendResult?.value ?: 0.0

            Column(modifier = Modifier.fillMaxSize()) {
                // Top ST Calculation & Executed Amount Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Executed Amount (if active trade exists) or Index Name
                    if (pageExecAmount > 0.0) {
                        Text(
                            text = "Exec: ₹${String.format(java.util.Locale.US, "%,.0f", pageExecAmount)}",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = if (MaterialTheme.colorScheme.onBackground == Color.White) androidx.compose.ui.graphics.Color(0xFFFFD600) else MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "$pageSym Chain",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Right: ST (Supertrend) Calculation
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ST: ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (stValue > 0.0) "$trendText (${String.format(java.util.Locale.US, "%.1f", stValue)})" else trendText,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = trendColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                // Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("STRIKE", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("STrend", modifier = Modifier.weight(0.5f), color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("CE LTP (Dlt/Bld)", modifier = Modifier.weight(1.3f), color = androidx.compose.ui.graphics.Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("PE LTP (Dlt/Bld)", modifier = Modifier.weight(1.3f), color = androidx.compose.ui.graphics.Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Up/Dn", modifier = Modifier.weight(0.7f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }

                // Table List
                val labels = upstoxService.strikeLabels
                val baseStrike = upstoxService.fixedBaseStrike[pageSym]
                val step = if (pageSym == "NIFTY") 50.0 else 100.0
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(labels.size) { i ->
                        val lbl = labels[i]
                        val key = "${pageSym}_$lbl"
                        val row = optionsChain[key]
                        
                        val computedStrike = if (baseStrike != null && baseStrike > 0.0) {
                            val offset = i - 5 // index of "ATM" is 5
                            (baseStrike + offset * step).toInt().toString()
                        } else if (row != null && row.strikePrice > 0.0) {
                            row.strikePrice.toInt().toString()
                        } else {
                            "--"
                        }
                        
                        OptionChainRow(row, trendText, trendColor, computedStrike)
                    }
                }
            }
        }
    }
}

@Composable
fun OptionChainRow(row: StrikeRowState?, trendText: String, trendColor: androidx.compose.ui.graphics.Color, computedStrike: String) {
    val isDark = MaterialTheme.colorScheme.onBackground == Color.White
    val ceThemeColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF089981)
    val peThemeColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFDC2626)
    val strikeThemeColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFFFD600) else MaterialTheme.colorScheme.primary

    val ce = row?.ceData
    val pe = row?.peData

    val ceDiff = row?.ceDiff ?: 0.0
    val peDiff = row?.peDiff ?: 0.0
    val ceBuild = row?.ceBuild ?: 0.0
    val peBuild = row?.peBuild ?: 0.0
    val computedUpDn = row?.computedUpDn ?: "--"

    val ceBgColor = if (ceDiff > 0.0) ceThemeColor.copy(alpha = if (isDark) 0.2f else 0.12f) else androidx.compose.ui.graphics.Color.Transparent
    val peBgColor = if (peDiff > 0.0) peThemeColor.copy(alpha = if (isDark) 0.2f else 0.12f) else androidx.compose.ui.graphics.Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)).padding(vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = computedStrike,
                color = strikeThemeColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Text(trendText, modifier = Modifier.weight(0.5f), color = trendColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

        Row(
            modifier = Modifier.weight(1.3f).background(ceBgColor).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val deltaText = ce?.let { String.format(java.util.Locale.US, "%.2f", it.delta) } ?: "--"
            val buildText = ce?.let { String.format(java.util.Locale.US, "%.1fM", ceBuild / 1e6) } ?: "--"
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "I:$deltaText", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
                Text(text = "B:$buildText", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
            }
            Text(
                text = ce?.let { String.format(java.util.Locale.US, "%.1f", it.ltp) } ?: "--",
                modifier = Modifier.weight(1f),
                color = if (ceDiff > 0.0) ceThemeColor else MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        Row(
            modifier = Modifier.weight(1.3f).background(peBgColor).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = pe?.let { String.format(java.util.Locale.US, "%.1f", it.ltp) } ?: "--",
                modifier = Modifier.weight(1f),
                color = if (peDiff > 0.0) peThemeColor else MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            val deltaText = pe?.let { String.format(java.util.Locale.US, "%.2f", it.delta) } ?: "--"
            val buildText = pe?.let { String.format(java.util.Locale.US, "%.1fM", peBuild / 1e6) } ?: "--"
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "I:$deltaText", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
                Text(text = "B:$buildText", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
            }
        }

        val sig = computedUpDn
        Box(
            modifier = Modifier.weight(0.7f),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sig != "--") {
                    Box(modifier = Modifier.size(6.dp).background(color = if (sig == "Up") ceThemeColor else peThemeColor, shape = androidx.compose.foundation.shape.CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(sig, color = if (sig == "Up") ceThemeColor else if (sig == "Dn") peThemeColor else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==========================================
// TAB 3: Virtual Trading Tickets
// ==========================================
@Composable
fun VirtualTradingTab(
    upstoxService: UpstoxService,
    optionsChain: Map<String, StrikeRowState>,
    trades: List<Trade>,
    forcedTradingMode: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedIndex by remember { mutableStateOf("NIFTY") }
    var selectedStrikeKey by remember { mutableStateOf("") }
    
    val productType by upstoxService.productType.collectAsState()
    val tradingMode by upstoxService.tradingMode.collectAsState()
    val isGuestUser by upstoxService.isGuestUser.collectAsState()
    val userName by upstoxService.userName.collectAsState()
    val availableFunds by upstoxService.availableFunds.collectAsState()
    var placingOrderType by remember { mutableStateOf<String?>(null) }
    val isOrderPlacing = placingOrderType != null

    val lotSizes = mapOf("NIFTY" to 65, "BANKNIFTY" to 30, "SENSEX" to 20)
    val lotSize = lotSizes[selectedIndex] ?: 25
    var quantityInput by remember(selectedIndex) { mutableStateOf(lotSize.toString()) }

    var statusMessage by remember { mutableStateOf("Ready") }

    LaunchedEffect(selectedIndex) {
        upstoxService.ensureStrikesForSymbol(selectedIndex)
    }

    val strikeOptions = remember(selectedIndex, optionsChain, upstoxService.fixedBaseStrike[selectedIndex]) {
        val baseStrike = upstoxService.fixedBaseStrike[selectedIndex]
            ?: upstoxService.getSavedBaseStrike(selectedIndex).takeIf { it > 0.0 }
            ?: (upstoxService.indexLtpMap[selectedIndex]?.let { upstoxService.instrumentRepository.getValidStrike(selectedIndex, it) })
            ?: 0.0
        val step = if (selectedIndex == "NIFTY") 50.0 else 100.0

        val offsets = mapOf(
            "5 Below" to -5, "4 Below" to -4, "3 Below" to -3, "ATM + 2" to -2, "ATM + 1" to -1,
            "ATM" to 0,
            "ATM - 1" to 1, "ATM - 2" to 2, "3 Above" to 3, "4 Above" to 4, "5 Above" to 5
        )

        upstoxService.strikeLabels.map { lbl ->
            val rowKey = "${selectedIndex}_$lbl"
            val rowStrike = optionsChain[rowKey]?.strikePrice ?: 0.0
            val strikeVal = if (rowStrike > 0.0) {
                rowStrike.toInt()
            } else if (baseStrike > 0.0) {
                val off = offsets[lbl] ?: 0
                (baseStrike + off * step).toInt()
            } else {
                0
            }

            val label = when {
                strikeVal > 0 && lbl == "ATM" -> "$strikeVal ATM"
                strikeVal > 0 -> "$strikeVal"
                else -> lbl
            }
            label to rowKey
        }
    }

    LaunchedEffect(strikeOptions) {
        if (strikeOptions.isNotEmpty()) {
            val atm = strikeOptions.firstOrNull { it.second.endsWith("_ATM") }
            selectedStrikeKey = atm?.second ?: strikeOptions.first().second
        } else {
            selectedStrikeKey = "${selectedIndex}_ATM"
        }
    }

    val totalPnl = trades.sumOf { it.pnl }
    val pnlColor = if (totalPnl >= 0.0) Color(0xFF00E676) else Color(0xFFFF5252)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Profile & Funds Header
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Hi, $userName", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Available to Trade", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹${String.format(java.util.Locale.US, "%.2f", availableFunds)}", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text("Note: To add money to your trade funds, please open the official Upstox app.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            }
        }
        
        // Compact Header with Total P&L
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Virtual Orders", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                text = "Total P&L: ₹${String.format(Locale.US, "%.2f", totalPnl)}",
                color = pnlColor,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp
            )
        }



        // Compact Toggle Panel (Toggles combined horizontally to save massive space)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Trading Mode Toggle Box
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (tradingMode == "real") androidx.compose.ui.graphics.Color(0xFFE8F5E9) else androidx.compose.ui.graphics.Color(0xFFFFFDE7))
                        .clickable(enabled = forcedTradingMode == null && !isGuestUser) { upstoxService.saveTradingMode(if (tradingMode == "real") "virtual" else "real") }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (tradingMode == "real") "Live: Real" else "PaperTrade",
                        color = if (tradingMode == "real") androidx.compose.ui.graphics.Color(0xFF2E7D32) else androidx.compose.ui.graphics.Color(0xFFF57F17),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }

                // Product Type Toggle Box
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { upstoxService.saveProductType(if (productType == "I") "D" else "I") }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (productType == "I") "MIS (Intraday)" else "CNC (Delivery)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Ticket Placement Card (Very Compact)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
        ) {
            Column(
                modifier = Modifier.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Index Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("NIFTY", "SENSEX", "BANKNIFTY").forEach { sym ->
                        val isSelected = selectedIndex == sym
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { selectedIndex = sym }
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                sym,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Strike selector & Qty inputs (Side by Side & Compact)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Strike Dropdown
                    Column(modifier = Modifier.weight(1.2f)) {
                        if (strikeOptions.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            val currentStrikeName = strikeOptions.firstOrNull { it.second == selectedStrikeKey }?.first ?: "Choose..."
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .clickable { expanded = true }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("$selectedIndex : $currentStrikeName", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                ) {
                                    strikeOptions.forEach { (name, key) ->
                                        DropdownMenuItem(
                                            text = { Text(name, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp) },
                                            onClick = {
                                                selectedStrikeKey = key
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Quantity Stepper
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                .height(28.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val currentVal = quantityInput.toIntOrNull() ?: lotSize
                                    if (currentVal > lotSize) {
                                        quantityInput = (currentVal - lotSize).toString()
                                    }
                                },
                                modifier = Modifier.size(24.dp),
                                enabled = !isOrderPlacing
                            ) {
                                Text("-", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text(
                                text = quantityInput,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    val currentVal = quantityInput.toIntOrNull() ?: lotSize
                                    quantityInput = (currentVal + lotSize).toString()
                                },
                                modifier = Modifier.size(24.dp),
                                enabled = !isOrderPlacing
                            ) {
                                Text("+", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                // Dynamic Buy / Sell Placement (B | LTP | S) horizontally for CE and PE
                val row = optionsChain[selectedStrikeKey]
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CALLS", modifier = Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFF00E676), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    val expiryText = row?.expiryStr ?: ""
                    if (expiryText.isNotEmpty()) {
                        Text(expiryText.uppercase(), modifier = Modifier.weight(0.5f), color = androidx.compose.ui.graphics.Color(0xFFB09540), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                    Text("PUTS", modifier = Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFFFF5252), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }

                if (row != null) {
                    val ceLtp = row.ceData?.ltp ?: 0.0
                    val peLtp = row.peData?.ltp ?: 0.0

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // CE Side (B | LTP | S)
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // BUY CE
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (!isOrderPlacing) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF00E676).copy(alpha = 0.5f))
                                    .clickable(enabled = !isOrderPlacing) {
                                        val token = row.ceData?.instrumentKey ?: ""
                                        if (token.isEmpty() || ceLtp <= 0.0) {
                                            statusMessage = "Error: Invalid CE price"
                                            return@clickable
                                        }
                                        val qty = quantityInput.toIntOrNull() ?: lotSize
                                        if (tradingMode == "real") {
                                            placingOrderType = "BUY_CE"
                                            statusMessage = "Placing real BUY CE..."
                                            coroutineScope.launch {
                                                try {
                                                    val res = upstoxService.placeRealOrder(token, "BUY", qty, productType)
                                                    statusMessage = res
                                                } catch (e: Exception) {
                                                    statusMessage = "Error: ${e.message}"
                                                } finally {
                                                    placingOrderType = null
                                                }
                                            }
                                        } else {
                                            upstoxService.executeTrade(selectedIndex, "CE", row.strikePrice, token, "BUY", qty, ceLtp)
                                            statusMessage = "BUY CE @ $ceLtp x $qty"
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (placingOrderType == "BUY_CE" && tradingMode == "real") {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.Black, strokeWidth = 1.5.dp)
                                } else {
                                    Text("B", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                }
                            }
                            
                            Text(
                                text = if (ceLtp > 0.0) String.format(Locale.US, "%.1f", ceLtp) else "--",
                                modifier = Modifier.weight(1.2f),
                                color = if (ceLtp > 0.0) androidx.compose.ui.graphics.Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            // SELL CE
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (!isOrderPlacing) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFFF5252).copy(alpha = 0.5f))
                                    .clickable(enabled = !isOrderPlacing) {
                                        val token = row.ceData?.instrumentKey ?: ""
                                        if (token.isEmpty() || ceLtp <= 0.0) {
                                            statusMessage = "Error: Invalid CE price"
                                            return@clickable
                                        }
                                        val qty = quantityInput.toIntOrNull() ?: lotSize
                                        if (tradingMode == "real") {
                                            placingOrderType = "SELL_CE"
                                            statusMessage = "Placing real SELL CE..."
                                            coroutineScope.launch {
                                                try {
                                                    val res = upstoxService.placeRealOrder(token, "SELL", qty, productType)
                                                    statusMessage = res
                                                } catch (e: Exception) {
                                                    statusMessage = "Error: ${e.message}"
                                                } finally {
                                                    placingOrderType = null
                                                }
                                            }
                                        } else {
                                            upstoxService.executeTrade(selectedIndex, "CE", row.strikePrice, token, "SELL", qty, ceLtp)
                                            statusMessage = "SELL CE @ $ceLtp x $qty"
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (placingOrderType == "SELL_CE" && tradingMode == "real") {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
                                } else {
                                    Text("S", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                }
                            }
                        }

                        // PE Side (B | LTP | S)
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // BUY PE
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (!isOrderPlacing) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF00E676).copy(alpha = 0.5f))
                                    .clickable(enabled = !isOrderPlacing) {
                                        val token = row.peData?.instrumentKey ?: ""
                                        if (token.isEmpty() || peLtp <= 0.0) {
                                            statusMessage = "Error: Invalid PE price"
                                            return@clickable
                                        }
                                        val qty = quantityInput.toIntOrNull() ?: lotSize
                                        if (tradingMode == "real") {
                                            placingOrderType = "BUY_PE"
                                            statusMessage = "Placing real BUY PE..."
                                            coroutineScope.launch {
                                                try {
                                                    val res = upstoxService.placeRealOrder(token, "BUY", qty, productType)
                                                    statusMessage = res
                                                } catch (e: Exception) {
                                                    statusMessage = "Error: ${e.message}"
                                                } finally {
                                                    placingOrderType = null
                                                }
                                            }
                                        } else {
                                            upstoxService.executeTrade(selectedIndex, "PE", row.strikePrice, token, "BUY", qty, peLtp)
                                            statusMessage = "BUY PE @ $peLtp x $qty"
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (placingOrderType == "BUY_PE" && tradingMode == "real") {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.Black, strokeWidth = 1.5.dp)
                                } else {
                                    Text("B", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                }
                            }
                            
                            Text(
                                text = if (peLtp > 0.0) String.format(Locale.US, "%.1f", peLtp) else "--",
                                modifier = Modifier.weight(1.2f),
                                color = if (peLtp > 0.0) androidx.compose.ui.graphics.Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            // SELL PE
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (!isOrderPlacing) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFFF5252).copy(alpha = 0.5f))
                                    .clickable(enabled = !isOrderPlacing) {
                                        val token = row.peData?.instrumentKey ?: ""
                                        if (token.isEmpty() || peLtp <= 0.0) {
                                            statusMessage = "Error: Invalid PE price"
                                            return@clickable
                                        }
                                        val qty = quantityInput.toIntOrNull() ?: lotSize
                                        if (tradingMode == "real") {
                                            placingOrderType = "SELL_PE"
                                            statusMessage = "Placing real SELL PE..."
                                            coroutineScope.launch {
                                                try {
                                                    val res = upstoxService.placeRealOrder(token, "SELL", qty, productType)
                                                    statusMessage = res
                                                } catch (e: Exception) {
                                                    statusMessage = "Error: ${e.message}"
                                                } finally {
                                                    placingOrderType = null
                                                }
                                            }
                                        } else {
                                            upstoxService.executeTrade(selectedIndex, "PE", row.strikePrice, token, "SELL", qty, peLtp)
                                            statusMessage = "SELL PE @ $peLtp x $qty"
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (placingOrderType == "SELL_PE" && tradingMode == "real") {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
                                } else {
                                    Text("S", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
// Compact Status banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        statusMessage,
                        color = if (statusMessage.startsWith("Error")) androidx.compose.ui.graphics.Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Ledger board header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val title = if (tradingMode == "real") "Real Ledger" else "Virtual Ledger"
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tradingMode == "virtual" && trades.any { !it.isClosed }) {
                    TextButton(
                        onClick = { upstoxService.squareOffAllTrades() },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("Square Off All", color = androidx.compose.ui.graphics.Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                TextButton(
                    onClick = {
                        upstoxService.clearTrades()
                        statusMessage = "Trades cleared"
                        android.widget.Toast.makeText(context, "Ledger reset: All trade data wiped", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isOrderPlacing,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Reset Ledger", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Ledger cards
        VirtualLedgerTable(trades = trades, tradingMode = tradingMode, upstoxService = upstoxService)
    }
}

@Composable
fun VirtualLedgerTable(
    trades: List<com.example.upstoxmarketdataapp.data.Trade>,
    tradingMode: String,
    upstoxService: UpstoxService
) {
    val lotSizes = mapOf("NIFTY" to 65, "BANKNIFTY" to 30, "SENSEX" to 20)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trades.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("No trades recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        } else {
            for (trade in trades) {
                val color = if (trade.pnl >= 0.0) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFF5252)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (trade.action == "BUY") androidx.compose.ui.graphics.Color(0x3300E676) else androidx.compose.ui.graphics.Color(0x33FF5252))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(trade.action, color = if (trade.action == "BUY") androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${trade.indexSymbol} ${trade.strikePrice.toInt()} ${trade.optionType}", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (trade.isClosed) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("CLOSED", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val priceText = if (trade.isClosed) "Entry: ${trade.entryPrice} | Exit: ${trade.exitPrice}" else "Entry: ${trade.entryPrice} | LTP: ${trade.currentPrice}"
                        Text("$priceText | Qty: ${trade.quantity}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }

                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = String.format(java.util.Locale.US, "%s%.2f", if (trade.pnl >= 0.0) "+" else "", trade.pnl),
                            color = color,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (!trade.isClosed && tradingMode == "virtual") {
                            val lotSize = lotSizes[trade.indexSymbol] ?: 1
                            val totalLots = trade.quantity / lotSize
                            if (totalLots > 1) {
                                var exitLots by androidx.compose.runtime.remember(trade.id) { androidx.compose.runtime.mutableStateOf(totalLots) }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                    ) {
                                        Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if(exitLots > 1) exitLots-- }.padding(horizontal = 8.dp, vertical = 2.dp))
                                        Text("${exitLots * lotSize}", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                                        Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if(exitLots < totalLots) exitLots++ }.padding(horizontal = 8.dp, vertical = 2.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(androidx.compose.ui.graphics.Color(0xFFFF5252))
                                            .clickable { upstoxService.squareOffPartialTrade(trade.id, exitLots * lotSize) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Exit", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(androidx.compose.ui.graphics.Color(0xFFFF5252))
                                        .clickable { upstoxService.squareOffTrade(trade.id) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Exit", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else if (!trade.isClosed && tradingMode == "real") {
                            Text("Exit via Upstox", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 8.sp)
                        }
                    }
                }
            }
        }

        // Trading Mode Notification Banners (Bottom) - Moved outside LazyColumn so it appears exactly once at the bottom
        val isDark = MaterialTheme.colorScheme.onBackground == Color.White
        if (tradingMode == "real") {
            val bannerBg = if (isDark) androidx.compose.ui.graphics.Color(0xFF0F2E1E) else androidx.compose.ui.graphics.Color(0xFFDCFCE7)
            val bannerBorder = if (isDark) androidx.compose.ui.graphics.Color(0xFF059669) else androidx.compose.ui.graphics.Color(0xFF10B981)
            val bannerText = if (isDark) androidx.compose.ui.graphics.Color(0xFF34D399) else androidx.compose.ui.graphics.Color(0xFF065F46)
            Card(
                colors = CardDefaults.cardColors(containerColor = bannerBg),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, bannerBorder, RoundedCornerShape(6.dp))
            ) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = bannerText, modifier = Modifier.size(14.dp))
                    Text(
                        text = "REAL MODE active! Live money order placement enabled.",
                        color = bannerText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            val bannerBg = if (isDark) androidx.compose.ui.graphics.Color(0xFF2C2411) else androidx.compose.ui.graphics.Color(0xFFFEF3C7)
            val bannerBorder = if (isDark) androidx.compose.ui.graphics.Color(0xFFD97706) else androidx.compose.ui.graphics.Color(0xFFF59E0B)
            val bannerText = if (isDark) androidx.compose.ui.graphics.Color(0xFFFBBF24) else androidx.compose.ui.graphics.Color(0xFF92400E)
            Card(
                colors = CardDefaults.cardColors(containerColor = bannerBg),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, bannerBorder, RoundedCornerShape(6.dp))
            ) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = bannerText, modifier = Modifier.size(14.dp))
                    Text(
                        text = "PAPER MODE active! Virtual trading only.",
                        color = bannerText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ==========================================
// TAB 4: Settings & Debug Logs
// ==========================================
@Composable
fun SettingsAndLogsTab(
    upstoxService: UpstoxService,
    consoleLogs: List<String>,
    yoiProgress: String,
    repoState: RepoState,
    isWideScreen: Boolean,
    onNavigateToScanner: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val savedCreds = remember { upstoxService.getSavedCredentials() }
    var apiKey by remember { mutableStateOf(savedCreds.first) }
    var apiSecret by remember { mutableStateOf(savedCreds.second) }
    var redirectUri by remember { mutableStateOf(savedCreds.third.ifEmpty { "http://localhost:8080" }) }

    val themeMode by upstoxService.themeMode.collectAsState()
    val credsSaved = savedCreds.first.isNotEmpty() && savedCreds.second.isNotEmpty() && savedCreds.third.isNotEmpty()
    var credentialsExpanded by remember { mutableStateOf(!credsSaved) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App settings header
        Text("App Configuration & Logs", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)

        if (!isWideScreen) {
            Button(
                onClick = onNavigateToScanner,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = "Link TV", modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Connect to TV", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Theme Selection Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Select Theme Mode", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val themes = listOf("light" to "Light", "dark" to "Dark", "system" to "System")
                    themes.forEach { (mode, label) ->
                        val isSelected = themeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { upstoxService.saveTheme(mode) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // API Configuration Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Upstox Credentials Settings", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                if (!credentialsExpanded) {
                    // Collapsed - show saved chip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color(0xFF00875A).copy(alpha = 0.12f))
                            .clickable { credentialsExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color(0xFF00875A),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Credentials saved", color = androidx.compose.ui.graphics.Color(0xFF00875A), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Text("Edit", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // Expanded - show all fields
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key / Client ID", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = apiSecret,
                        onValueChange = { apiSecret = it },
                        label = { Text("API Secret / Client Secret", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = redirectUri,
                        onValueChange = { redirectUri = it },
                        label = { Text("Redirect URI", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                upstoxService.saveCredentials(apiKey.trim(), apiSecret.trim(), redirectUri.trim())
                                upstoxService.logRaw("Credentials saved successfully")
                                credentialsExpanded = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { credentialsExpanded = false },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Instrument Cache Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Derivatives Option Master Cache", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                
                val statusText = when (repoState) {
                    is RepoState.Idle -> "Idle"
                    is RepoState.Loading -> "Loading: ${(repoState as RepoState.Loading).message} (${String.format(Locale.US, "%.0f%%", (repoState as RepoState.Loading).progress * 100)})"
                    is RepoState.Success -> "Success: loaded ${(repoState as RepoState.Success).count} derivatives contracts"
                    is RepoState.Error -> "Error: ${(repoState as RepoState.Error).message}"
                }
                
                Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

                // Progress Bar
                if (repoState is RepoState.Loading) {
                    val progress = (repoState as RepoState.Loading).progress
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            upstoxService.instrumentRepository.initialize()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF00E676)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = repoState !is RepoState.Loading
                ) {
                    Text("Update Option Master Cache", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Logs Display Terminal
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connection Console logs", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                val scrollState = rememberScrollState()
                Text(
                    text = if (consoleLogs.isEmpty()) "Connection is idle. Logs will appear here..." else consoleLogs.joinToString("\n"),
                    color = androidx.compose.ui.graphics.Color(0xFF00FF00),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "DISCLAIMER: This application is strictly for educational purposes and paper trading. It is not financial advice. Real trading involves significant risk of loss. The developers are not responsible for any financial losses incurred.",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 9.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
            lineHeight = 11.sp
        )
    }
}

@Composable
fun GlobalTradeButtonsRow(
    optionsChain: Map<String, com.example.upstoxmarketdataapp.data.StrikeRowState>,
    indexFutures: Map<String, List<com.example.upstoxmarketdataapp.data.FutureData>>,
    index15sCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    upstoxService: com.example.upstoxmarketdataapp.data.UpstoxService,
    trades: List<com.example.upstoxmarketdataapp.data.Trade>,
    forcedTradingMode: String? = null
) {
    val rawTradingMode by upstoxService.tradingMode.collectAsState()
    val tradingMode = forcedTradingMode ?: rawTradingMode
    var globalMaxFuturesSum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.0) }
    var globalMinFuturesSum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.0) }
    var isAutoAlgoEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val supertrendCalculator = androidx.compose.runtime.remember { com.example.upstoxmarketdataapp.simulator.SupertrendCalculator(10, 3.0) }

    val indices = listOf("NIFTY", "SENSEX", "BANKNIFTY")

    var allBuyCe = true
    var allBuyPe = true
    var currentFuturesSum = 0.0
    var totalCallDots = 0
    var totalPutDots = 0

    indices.forEach { sym ->
        val futures = indexFutures[sym] ?: emptyList()
        val allFuturesGreen = futures.isNotEmpty() && futures.all { it.diff >= 0 }
        val allFuturesRed = futures.isNotEmpty() && futures.all { it.diff < 0 }

        val labels = upstoxService.strikeLabels
        val strikeCount = labels.size
        val threshold80Percent = (strikeCount * 0.8).toInt()

        var buyCallDots = 0
        var buyPutDots = 0
        var ceDiffPositiveCount = 0
        var peDiffPositiveCount = 0

        labels.forEach { lbl ->
            val row = optionsChain["${sym}_$lbl"]
            if (row != null) {
                if (row.ceDiff > 0) ceDiffPositiveCount++
                if (row.peDiff > 0) peDiffPositiveCount++

                val signal = row.activeSignal
                if (signal != null) {
                    if (signal.type == "BUY CALL") buyCallDots++
                    if (signal.type == "BUY PUT") buyPutDots++
                }
            }
        }

        // Simplified Signal Criteria (Omitted 80% momentum & Supertrend)
        // 1. All Futures direction aligned
        // 2. Options Breadth >= 3 signal dots
        // 3. Zero counter-signal dots
        val isBuyCe = allFuturesGreen && buyCallDots >= 3 && buyPutDots == 0
        val isBuyPe = allFuturesRed && buyPutDots >= 3 && buyCallDots == 0

        if (!isBuyCe) allBuyCe = false
        if (!isBuyPe) allBuyPe = false

        currentFuturesSum += futures.sumOf { it.diff }
        totalCallDots += buyCallDots
        totalPutDots += buyPutDots
    }

    androidx.compose.runtime.LaunchedEffect(currentFuturesSum) {
        if (currentFuturesSum > globalMaxFuturesSum) globalMaxFuturesSum = currentFuturesSum
        if (currentFuturesSum < globalMinFuturesSum) globalMinFuturesSum = currentFuturesSum
    }

    val exitCe = (globalMaxFuturesSum > 100 && currentFuturesSum < globalMaxFuturesSum * 0.8 && totalCallDots < 6)
    val exitPe = (globalMinFuturesSum < -100 && currentFuturesSum > globalMinFuturesSum * 0.8 && totalPutDots < 6)

    val lotSizes = mapOf("NIFTY" to 65, "BANKNIFTY" to 30, "SENSEX" to 20)
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    var showAutoConfigDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var autoSelectedIndices by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf("NIFTY", "SENSEX", "BANKNIFTY")) }
    var autoLots by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1) }

    val manualBuyCE = {
        indices.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val strike = row?.strikePrice ?: (upstoxService.fixedBaseStrike[sym] ?: 0.0)
            val ltp = row?.ceData?.ltp ?: 0.0
            val token = row?.ceData?.instrumentKey ?: ""
            if (strike > 0.0 && ltp > 0.0 && token.isNotEmpty()) {
                val qty = lotSizes[sym] ?: 1
                if (tradingMode == "real") {
                    val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                    coroutineScope.launch { try { upstoxService.placeRealOrder(token, "BUY", qty, productType) } catch(e: Exception) { } }
                } else {
                    upstoxService.executeTrade(sym, "CE", strike, token, "BUY", qty, ltp)
                }
            }
        }
    }
    
    val manualExitCE = {
        indices.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val ltp = row?.ceData?.ltp ?: 0.0
            if (ltp > 0.0) {
                if (tradingMode == "real") {
                    val token = row?.ceData?.instrumentKey ?: ""
                    if (token.isNotEmpty()) {
                        val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                        coroutineScope.launch { try { upstoxService.placeRealOrder(token, "SELL", lotSizes[sym] ?: 1, productType) } catch(e: Exception) { } }
                    }
                } else {
                    upstoxService.squareOffVirtualType(sym, "CE")
                }
            }
        }
    }

    val manualBuyPE = {
        indices.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val strike = row?.strikePrice ?: (upstoxService.fixedBaseStrike[sym] ?: 0.0)
            val ltp = row?.peData?.ltp ?: 0.0
            val token = row?.peData?.instrumentKey ?: ""
            if (strike > 0.0 && ltp > 0.0 && token.isNotEmpty()) {
                val qty = lotSizes[sym] ?: 1
                if (tradingMode == "real") {
                    val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                    coroutineScope.launch { try { upstoxService.placeRealOrder(token, "BUY", qty, productType) } catch(e: Exception) { } }
                } else {
                    upstoxService.executeTrade(sym, "PE", strike, token, "BUY", qty, ltp)
                }
            }
        }
    }
    
    val manualExitPE = {
        indices.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val ltp = row?.peData?.ltp ?: 0.0
            if (ltp > 0.0) {
                if (tradingMode == "real") {
                    val token = row?.peData?.instrumentKey ?: ""
                    if (token.isNotEmpty()) {
                        val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                        coroutineScope.launch { try { upstoxService.placeRealOrder(token, "SELL", lotSizes[sym] ?: 1, productType) } catch(e: Exception) { } }
                    }
                } else {
                    upstoxService.squareOffVirtualType(sym, "PE")
                }
            }
        }
    }

    // Auto Execution Logic (using user selected indices and lot sizes)
    val autoBuyCE = {
        val selectedNow = autoSelectedIndices // capture state at time of invocation
        selectedNow.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val strike = row?.strikePrice ?: (upstoxService.fixedBaseStrike[sym] ?: 0.0)
            val ltp = row?.ceData?.ltp ?: 0.0
            val token = row?.ceData?.instrumentKey ?: ""
            if (strike > 0.0 && ltp > 0.0 && token.isNotEmpty()) {
                val qty = autoLots * (lotSizes[sym] ?: 1)
                if (tradingMode == "real") {
                    val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                    coroutineScope.launch { try { upstoxService.placeRealOrder(token, "BUY", qty, productType) } catch(e: Exception) { } }
                } else {
                    upstoxService.executeTrade(sym, "CE", strike, token, "BUY", qty, ltp)
                }
            }
        }
    }

    val autoExitCE = {
        val selectedNow = autoSelectedIndices
        selectedNow.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val ltp = row?.ceData?.ltp ?: 0.0
            if (ltp > 0.0) {
                val token = row?.ceData?.instrumentKey ?: ""
                val qty = autoLots * (lotSizes[sym] ?: 1)
                if (tradingMode == "real") {
                    if (token.isNotEmpty()) {
                        val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                        coroutineScope.launch { try { upstoxService.placeRealOrder(token, "SELL", qty, productType) } catch(e: Exception) { } }
                    }
                } else {
                    upstoxService.squareOffVirtualType(sym, "CE")
                }
            }
        }
    }

    val autoBuyPE = {
        val selectedNow = autoSelectedIndices
        selectedNow.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val strike = row?.strikePrice ?: (upstoxService.fixedBaseStrike[sym] ?: 0.0)
            val ltp = row?.peData?.ltp ?: 0.0
            val token = row?.peData?.instrumentKey ?: ""
            if (strike > 0.0 && ltp > 0.0 && token.isNotEmpty()) {
                val qty = autoLots * (lotSizes[sym] ?: 1)
                if (tradingMode == "real") {
                    val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                    coroutineScope.launch { try { upstoxService.placeRealOrder(token, "BUY", qty, productType) } catch(e: Exception) { } }
                } else {
                    upstoxService.executeTrade(sym, "PE", strike, token, "BUY", qty, ltp)
                }
            }
        }
    }

    val autoExitPE = {
        val selectedNow = autoSelectedIndices
        selectedNow.forEach { sym ->
            val row = optionsChain["${sym}_ATM"]
            val ltp = row?.peData?.ltp ?: 0.0
            if (ltp > 0.0) {
                val token = row?.peData?.instrumentKey ?: ""
                val qty = autoLots * (lotSizes[sym] ?: 1)
                if (tradingMode == "real") {
                    if (token.isNotEmpty()) {
                        val productType = try { upstoxService.productType.value } catch(e: Exception) { "I" }
                        coroutineScope.launch { try { upstoxService.placeRealOrder(token, "SELL", qty, productType) } catch(e: Exception) { } }
                    }
                } else {
                    upstoxService.squareOffVirtualType(sym, "PE")
                }
            }
        }
    }

    // Temporary S/R Touch Detection for Exit lights (glows amber for few seconds when touching S/R)
    var isTouchingResistance by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var isTouchingSupport by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    indices.forEach { sym ->
        val ltp = upstoxService.indexLtpMap[sym] ?: upstoxService.indexCandlesState.value[sym]?.lastOrNull()?.close ?: 0.0
        val ladder = upstoxService.pivotLadderMap[sym]
        val res = ladder?.resistance
        val sup = ladder?.support
        if (ltp > 0.0) {
            if (res != null && res > 0.0 && kotlin.math.abs(ltp - res) <= 15.0) {
                isTouchingResistance = true
            }
            if (sup != null && sup > 0.0 && kotlin.math.abs(ltp - sup) <= 15.0) {
                isTouchingSupport = true
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(isTouchingResistance) {
        if (isTouchingResistance) {
            kotlinx.coroutines.delay(4000)
            isTouchingResistance = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(isTouchingSupport) {
        if (isTouchingSupport) {
            kotlinx.coroutines.delay(4000)
            isTouchingSupport = false
        }
    }

    val isDark = MaterialTheme.colorScheme.onBackground == Color.White
    val dailyPnl = trades.sumOf { it.pnl }
    val pnlColor = if (dailyPnl >= 0) (if (isDark) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF089981)) else (if (isDark) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFDC2626))

    // ─── AUTO CONFIG DIALOG ──────────────────────────────────────────────────
    if (showAutoConfigDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAutoConfigDialog = false },
            title = { Text("Auto Algo Settings", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select Indices to Trade:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    indices.forEach { sym ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                autoSelectedIndices = if (autoSelectedIndices.contains(sym)) {
                                    autoSelectedIndices - sym
                                } else {
                                    autoSelectedIndices + sym
                                }
                            }
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = autoSelectedIndices.contains(sym),
                                onCheckedChange = { checked ->
                                    autoSelectedIndices = if (checked) {
                                        autoSelectedIndices + sym
                                    } else {
                                        autoSelectedIndices - sym
                                    }
                                }
                            )
                            Text(sym, fontSize = 14.sp)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text("Choose Lots Count:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { if (autoLots > 1) autoLots-- },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("$autoLots", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        androidx.compose.material3.OutlinedButton(
                            onClick = { autoLots++ },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (tradingMode == "real") {
                        // Live calculation of Required Margin
                        var totalMargin = 0.0
                        autoSelectedIndices.forEach { sym ->
                            val row = optionsChain["${sym}_ATM"]
                            val ceLtp = row?.ceData?.ltp ?: 0.0
                            val lotSize = lotSizes[sym] ?: 1
                            totalMargin += ceLtp * lotSize * autoLots
                        }
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Estimated Required Margin:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("₹${String.format(java.util.Locale.US, "%.2f", totalMargin)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Do not trade if account balance is below this amount.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        if (autoSelectedIndices.isNotEmpty()) {
                            isAutoAlgoEnabled = true
                            showAutoConfigDialog = false
                        }
                    },
                    enabled = autoSelectedIndices.isNotEmpty()
                ) {
                    Text("Activate Auto")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoConfigDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Calculate live estimated margin required for 1 lot of ATM
    var totalEstMargin = 0.0
    indices.forEach { sym ->
        val row = optionsChain["${sym}_ATM"]
        val ceLtp = row?.ceData?.ltp ?: 0.0
        val lotSize = lotSizes[sym] ?: 1
        totalEstMargin += ceLtp * lotSize
    }

    val activeCeColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF089981)
    val activePeColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFDC2626)
    val activeAmberColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFFFB300) else androidx.compose.ui.graphics.Color(0xFFD97706)
    val inactiveDotColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF333333) else androidx.compose.ui.graphics.Color(0xFFCBD5E1)
    
    val buttonContainerColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF262626) else androidx.compose.ui.graphics.Color(0xFFDFE3E7)
    val buttonBorderColor = if (isDark) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.ui.graphics.Color(0xFFCBD5E1)
    val neutralButtonColor = ButtonDefaults.buttonColors(containerColor = buttonContainerColor)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        
        // Trading Action Buttons with Individual Indicator Lights Above (No Text Written)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. BUY CE
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (allBuyCe) activeCeColor else inactiveDotColor,
                            shape = CircleShape
                        )
                        .then(
                            if (allBuyCe) Modifier.border(1.5.dp, activeCeColor.copy(alpha = 0.6f), CircleShape)
                            else if (!isDark) Modifier.border(0.5.dp, androidx.compose.ui.graphics.Color(0xFF94A3B8), CircleShape)
                            else Modifier
                        )
                )
                Spacer(modifier = Modifier.height(3.dp))
                Button(
                    onClick = { manualBuyCE() },
                    colors = neutralButtonColor,
                    shape = RoundedCornerShape(6.dp),
                    border = if (!isDark) androidx.compose.foundation.BorderStroke(1.dp, buttonBorderColor) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("BUY CE", color = if (isDark) androidx.compose.ui.graphics.Color.White else activeCeColor, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
                }
            }

            // 2. EXIT CE
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isExitCeLit = exitCe || isTouchingResistance
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (isExitCeLit) activeAmberColor else inactiveDotColor,
                            shape = CircleShape
                        )
                        .then(
                            if (isExitCeLit) Modifier.border(1.5.dp, activeAmberColor.copy(alpha = 0.6f), CircleShape)
                            else if (!isDark) Modifier.border(0.5.dp, androidx.compose.ui.graphics.Color(0xFF94A3B8), CircleShape)
                            else Modifier
                        )
                )
                Spacer(modifier = Modifier.height(3.dp))
                Button(
                    onClick = { manualExitCE() },
                    colors = neutralButtonColor,
                    shape = RoundedCornerShape(6.dp),
                    border = if (!isDark) androidx.compose.foundation.BorderStroke(1.dp, buttonBorderColor) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("EXIT CE", color = if (isDark) androidx.compose.ui.graphics.Color.LightGray else activeAmberColor, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
                }
            }

            // 3. BUY PE
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (allBuyPe) activePeColor else inactiveDotColor,
                            shape = CircleShape
                        )
                        .then(
                            if (allBuyPe) Modifier.border(1.5.dp, activePeColor.copy(alpha = 0.6f), CircleShape)
                            else if (!isDark) Modifier.border(0.5.dp, androidx.compose.ui.graphics.Color(0xFF94A3B8), CircleShape)
                            else Modifier
                        )
                )
                Spacer(modifier = Modifier.height(3.dp))
                Button(
                    onClick = { manualBuyPE() },
                    colors = neutralButtonColor,
                    shape = RoundedCornerShape(6.dp),
                    border = if (!isDark) androidx.compose.foundation.BorderStroke(1.dp, buttonBorderColor) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("BUY PE", color = if (isDark) androidx.compose.ui.graphics.Color.White else activePeColor, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
                }
            }

            // 4. EXIT PE
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isExitPeLit = exitPe || isTouchingSupport
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (isExitPeLit) activeAmberColor else inactiveDotColor,
                            shape = CircleShape
                        )
                        .then(
                            if (isExitPeLit) Modifier.border(1.5.dp, activeAmberColor.copy(alpha = 0.6f), CircleShape)
                            else if (!isDark) Modifier.border(0.5.dp, androidx.compose.ui.graphics.Color(0xFF94A3B8), CircleShape)
                            else Modifier
                        )
                )
                Spacer(modifier = Modifier.height(3.dp))
                Button(
                    onClick = { manualExitPE() },
                    colors = neutralButtonColor,
                    shape = RoundedCornerShape(6.dp),
                    border = if (!isDark) androidx.compose.foundation.BorderStroke(1.dp, buttonBorderColor) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("EXIT PE", color = if (isDark) androidx.compose.ui.graphics.Color.LightGray else activeAmberColor, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
                }
            }
        }

        // Row 2: Mode Toggle and PNL with Estimated Margin
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val modeBg = if (tradingMode == "real") {
                    if (isDark) androidx.compose.ui.graphics.Color(0xFF0F2E1E) else androidx.compose.ui.graphics.Color(0xFFD1E7DD)
                } else {
                    if (isDark) androidx.compose.ui.graphics.Color(0xFF2C2411) else androidx.compose.ui.graphics.Color(0xFFE2DDD3)
                }
                val modeBorder = if (tradingMode == "real") {
                    if (isDark) androidx.compose.ui.graphics.Color(0xFF059669) else androidx.compose.ui.graphics.Color(0xFF198754)
                } else {
                    if (isDark) androidx.compose.ui.graphics.Color(0xFFD97706) else androidx.compose.ui.graphics.Color(0xFFD97706)
                }
                val modeText = if (tradingMode == "real") {
                    if (isDark) androidx.compose.ui.graphics.Color(0xFF34D399) else androidx.compose.ui.graphics.Color(0xFF0F5132)
                } else {
                    if (isDark) androidx.compose.ui.graphics.Color(0xFFFBBF24) else androidx.compose.ui.graphics.Color(0xFF78350F)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(100.dp)
                        .background(modeBg, RoundedCornerShape(4.dp))
                        .border(1.dp, modeBorder, RoundedCornerShape(4.dp))
                        .clickable(enabled = forcedTradingMode == null) { upstoxService.saveTradingMode(if (tradingMode == "real") "virtual" else "real") }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (tradingMode == "real") "Live: Real" else "PaperTrade",
                        color = modeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                if (tradingMode == "virtual" && trades.any { !it.isClosed }) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isDark) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFFDC2626))
                            .clickable { upstoxService.squareOffAllTrades() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Square Off All", color = androidx.compose.ui.graphics.Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Total P&L: ₹${String.format(java.util.Locale.US, "%.2f", dailyPnl)}", color = if (dailyPnl >= 0) activeCeColor else activePeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            upstoxService.clearTrades()
                            android.widget.Toast.makeText(context, "Ledger reset: All trade data wiped", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Ledger", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    }
                }
                val activeTotalExecuted = trades.filter { !it.isClosed }.sumOf { it.entryPrice * it.quantity }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (activeTotalExecuted > 0.0) {
                        Text(
                            text = "Exec: ₹${String.format(java.util.Locale.US, "%,.0f", activeTotalExecuted)}  |  ",
                            fontSize = 9.sp,
                            color = if (isDark) androidx.compose.ui.graphics.Color(0xFFFFD600) else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Est. Order: ₹${String.format(java.util.Locale.US, "%,.0f", totalEstMargin)}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
