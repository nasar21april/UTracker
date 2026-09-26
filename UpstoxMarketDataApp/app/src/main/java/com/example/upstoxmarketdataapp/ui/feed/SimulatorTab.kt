package com.example.upstoxmarketdataapp.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.upstoxmarketdataapp.data.UpstoxService
import com.example.upstoxmarketdataapp.simulator.DataDownloader
import com.example.upstoxmarketdataapp.simulator.SimulatorPlaybackEngine
import com.example.upstoxmarketdataapp.simulator.VirtualTradeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorTab(
    dataDownloader: DataDownloader,
    upstoxService: UpstoxService,
    optionsChain: Map<String, com.example.upstoxmarketdataapp.data.StrikeRowState>,
    indexCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    index15sCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    indexFutures: Map<String, List<com.example.upstoxmarketdataapp.data.FutureData>>,
    pivotsState: Map<String, Map<String, com.example.upstoxmarketdataapp.data.PivotCalculator.Pivots>>,
    onDateSelected: (File) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var availableDates by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }

    var isAdminAuthenticated by remember { mutableStateOf(false) }
    var adminPassword by remember { mutableStateOf("") }
    
    var showAdminDialog by remember { mutableStateOf(false) }

    if (!isAdminAuthenticated) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("COMING SOON", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = { showAdminDialog = true }) {
                Text("Login as Admin")
            }
        }
        
        if (showAdminDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAdminDialog = false },
                title = { Text("Admin Login") },
                text = {
                    OutlinedTextField(
                        value = adminPassword,
                        onValueChange = { adminPassword = it },
                        label = { Text("Admin Password") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (adminPassword == "Delt@F0rce@786") {
                                isAdminAuthenticated = true
                                showAdminDialog = false
                            }
                        }
                    ) {
                        Text("Login")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAdminDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        return
    }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var simulatorEngine by remember { mutableStateOf<SimulatorPlaybackEngine?>(null) }
    var speedIndex by remember { mutableStateOf(0) }
    val speeds = listOf(1f, 5f, 10f, 60f, 120f, 300f)
    val speedLabels = listOf("1x", "5x", "10x", "60x", "120x", "300x")

    val isPlaying by (simulatorEngine?.isPlaying ?: MutableStateFlow(false)).collectAsState()
    val isPaused by (simulatorEngine?.isPaused ?: MutableStateFlow(false)).collectAsState()
    val isSeeking by (simulatorEngine?.isSeeking ?: MutableStateFlow(false)).collectAsState()
    val tickCount by (simulatorEngine?.tickCount ?: MutableStateFlow(0)).collectAsState()
    val currentTime by (simulatorEngine?.currentTime ?: MutableStateFlow("--:--:--")).collectAsState()

    var sliderValue by remember { mutableStateOf(0f) }
    var isUserSliding by remember { mutableStateOf(false) }
    var showOptionsChain by remember { mutableStateOf(false) }

    // Redesign properties aligned with PivotsAndChartTab
    var selectedQuickIndex by remember { mutableStateOf("NIFTY") }
    val trades by upstoxService.trades.collectAsState()

    fun timeStrToFraction(timeStr: String): Float {
        try {
            val parts = timeStr.split(":")
            if (parts.size >= 2) {
                val hour = parts[0].toIntOrNull() ?: 9
                val min = parts[1].toIntOrNull() ?: 15
                val sec = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
                val currentSecs = (hour * 60 + min) * 60 + sec
                val startSecs = (9 * 60 + 15) * 60
                val endSecs = (15 * 60 + 30) * 60
                val fraction = (currentSecs - startSecs).toFloat() / (endSecs - startSecs).toFloat()
                return fraction.coerceIn(0f, 1f)
            }
        } catch (e: Exception) {}
        return 0f
    }

    fun fractionToTimeStr(fraction: Float): String {
        val startSecs = (9 * 60 + 15) * 60
        val endSecs = (15 * 60 + 30) * 60
        val targetSecs = startSecs + (fraction * (endSecs - startSecs)).toInt()
        val hour = targetSecs / 3600
        val min = (targetSecs % 3600) / 60
        val sec = targetSecs % 60
        return String.format("%02d:%02d:%02d", hour, min, sec)
    }

    fun getSeekTimestamp(dateStr: String, fraction: Float): Long {
        try {
            val tz = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sdf.timeZone = tz
            val parsedDate = sdf.parse(dateStr) ?: return 0L
            val cal = java.util.Calendar.getInstance(tz)
            cal.time = parsedDate
            
            val startSecs = (9 * 60 + 15) * 60
            val endSecs = (15 * 60 + 30) * 60
            val targetSecs = startSecs + (fraction * (endSecs - startSecs)).toInt()
            
            cal.set(java.util.Calendar.HOUR_OF_DAY, targetSecs / 3600)
            cal.set(java.util.Calendar.MINUTE, (targetSecs % 3600) / 60)
            cal.set(java.util.Calendar.SECOND, targetSecs % 60)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        } catch (e: Exception) {}
        return 0L
    }

    LaunchedEffect(currentTime, isUserSliding, isSeeking) {
        if (!isUserSliding && !isSeeking && currentTime.isNotEmpty() && currentTime != "--:--:--") {
            sliderValue = timeStrToFraction(currentTime)
        }
    }

    LaunchedEffect(Unit) {
        availableDates = dataDownloader.fetchAvailableDates()
    }

    DisposableEffect(Unit) {
        onDispose {
            simulatorEngine?.stopPlayback()
            simulatorEngine = null
            upstoxService.isReplaying = false
            upstoxService.currentReplayTimestamp = 0L
        }
    }

    // ─── DATE PICKER DIALOG ─────────────────────────────────────────────
    if (showDatePicker) {
        DatePickerDialog(
            availableDates = availableDates,
            onDismiss = { showDatePicker = false },
            onDatePicked = { date ->
                showDatePicker = false
                selectedDate = date
                isDownloading = true
                downloadError = null
                simulatorEngine?.stopPlayback()
                simulatorEngine = null
                coroutineScope.launch {
                    val file = dataDownloader.downloadAndExtractFile(date)
                    isDownloading = false
                    if (file != null && file.length() > 0) {
                        val engine = SimulatorPlaybackEngine(upstoxService, file)
                        engine.playbackSpeed = speeds[speedIndex]
                        simulatorEngine = engine
                        engine.startPlayback()
                        onDateSelected(file)
                    } else {
                        downloadError = "No data found for $date. Make sure the file is uploaded to GitHub."
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        // ─── SCROLLABLE CONTENT ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ─── ALL 3 INDEX CHARTS (Selectable like Feed tab) ───────────────────
            listOf("NIFTY", "SENSEX", "BANKNIFTY").forEach { sym ->
                IndexDashboardCard(
                    sym = sym,
                    upstoxService = upstoxService,
                    optionsChain = optionsChain,
                    pivotsState = pivotsState,
                    candles = indexCandles[sym] ?: emptyList(),
                    futures = indexFutures[sym],
                    isSelected = selectedQuickIndex == sym,
                    onClick = { selectedQuickIndex = sym }
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Global Trade Buttons & Ledger (Forces Virtual/Paper trades)
            GlobalTradeButtonsRow(
                optionsChain = optionsChain,
                indexFutures = indexFutures,
                index15sCandles = index15sCandles,
                upstoxService = upstoxService,
                trades = trades,
                forcedTradingMode = "virtual"
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Options Trading Table for selected index
            QuickOptionsTradingTable(
                selectedIndex = selectedQuickIndex,
                optionsChain = optionsChain,
                upstoxService = upstoxService,
                tradingMode = "virtual", // Enforce paper-trade only
                productType = "delivery",
                pivotsState = pivotsState
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Virtual Ledger Header & Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Virtual Ledger (Backtest)",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (trades.any { !it.isClosed }) {
                        TextButton(
                            onClick = { upstoxService.squareOffAllTrades() },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Square Off All", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(
                        onClick = { upstoxService.clearTrades() },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Reset Ledger", color = Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            // Ledger display
            VirtualLedgerTable(
                trades = trades,
                tradingMode = "virtual",
                upstoxService = upstoxService
            )

            // Optional raw options chain list for ATM strikes with Show/Hide toggle
            if (selectedDate != null && !isDownloading && downloadError == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { showOptionsChain = !showOptionsChain }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                ) {
                    Checkbox(
                        checked = showOptionsChain,
                        onCheckedChange = { showOptionsChain = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "Show Detailed Option Chain",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (showOptionsChain) {
                    BacktestOptionsChain(
                        upstoxService = upstoxService,
                        optionsChain = optionsChain
                    )
                }
            }

            // Bottom padding so content isn't hidden behind control bar
            Spacer(modifier = Modifier.height(100.dp))
        }

        // ─── COMPACT TRADINGVIEW-STYLE BOTTOM CONTROL BAR ───────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), thickness = 1.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Slider & seek labels
                    if (selectedDate != null && simulatorEngine != null && !isDownloading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "09:15",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isUserSliding) "Jump to: ${fractionToTimeStr(sliderValue)}" else "Time: $currentTime",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isUserSliding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "15:30",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                isUserSliding = true
                                sliderValue = it
                            },
                            onValueChangeFinished = {
                                val targetTs = getSeekTimestamp(selectedDate!!, sliderValue)
                                simulatorEngine?.let { engine ->
                                    val wasPaused = engine.isPaused.value || !engine.isPlaying.value
                                    coroutineScope.launch {
                                        engine.stopPlaybackAndJoin() // Cleanly wait for the old job to finish
                        engine.seekTimestamp = targetTs
                                        engine.pauseOnSeekComplete = wasPaused
                                        upstoxService.clearCandlesForReplay()
                                        engine.startPlayback()
                                        isUserSliding = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp)) // Spacing to separate play controls from progress line

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speed selector buttons: 1x 2x 5x 10x
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            speedLabels.forEachIndexed { idx, label ->
                                val isSelected = speedIndex == idx
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            speedIndex = idx
                                            simulatorEngine?.playbackSpeed = speeds[idx]
                                        }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Compact Date selector shortcut
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable { showDatePicker = true }
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Default.DateRange, contentDescription = "Pick Date", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                Text(
                                    text = selectedDate?.takeLast(5) ?: "Pick Date",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Status details (Ticks & Time details)
                        if (selectedDate != null && !isDownloading && downloadError == null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isSeeking) "🔍 Seek" else if (isPlaying) "▶ Live" else if (isPaused) "⏸ Pause" else "⏹ Stop",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSeeking) Color(0xFFFFD600) else if (isPlaying) Color(0xFF00E676) else if (isPaused) Color(0xFFFFD600) else Color.Gray
                                )
                                Text(
                                    text = "${tickCount}t",
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.2.dp, color = Color(0xFFFFD600))
                        } else if (downloadError != null) {
                            Text("Err", fontSize = 9.sp, color = Color(0xFFFF5252))
                        }

                        // Play/Pause and Stop buttons row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Play / Pause Button
                            IconButton(
                                onClick = {
                                    if (isPlaying) {
                                        simulatorEngine?.pausePlayback()
                                    } else {
                                        simulatorEngine?.startPlayback()
                                    }
                                },
                                enabled = simulatorEngine != null && !isDownloading,
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(
                                        Color(0xFF00E676).copy(alpha = 0.15f),
                                        RoundedCornerShape(6.dp)
                                    )
                            ) {
                                if (isPlaying) {
                                    // Custom Pause Icon (2 vertical bars)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.height(9.dp)
                                    ) {
                                        Box(modifier = Modifier.width(2.5.dp).fillMaxHeight().background(Color(0xFF00E676)))
                                        Box(modifier = Modifier.width(2.5.dp).fillMaxHeight().background(Color(0xFF00E676)))
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color(0xFF00E676),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Stop Button
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        simulatorEngine?.stopPlaybackAndJoin()
                                        sliderValue = 0f
                                    }
                                },
                                enabled = simulatorEngine != null && !isDownloading,
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(
                                        Color(0xFFFF5252).copy(alpha = 0.15f),
                                        RoundedCornerShape(6.dp)
                                    )
                            ) {
                                // Custom Stop Icon (solid square)
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFFFF5252))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── DATE PICKER DIALOG ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    availableDates: List<String>,
    onDismiss: () -> Unit,
    onDatePicked: (String) -> Unit
) {
    val datePickerState = rememberDatePickerState()

    // Highlight available dates
    val availableMillis = remember(availableDates) {
        availableDates.mapNotNull { dateStr ->
            try {
                val parts = dateStr.split("-")
                val cal = Calendar.getInstance()
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 12, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            } catch (e: Exception) { null }
        }.toSet()
    }

    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = millis
                        val dateStr = String.format(
                            "%04d-%02d-%02d",
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH) + 1,
                            cal.get(Calendar.DAY_OF_MONTH)
                        )
                        onDatePicked(dateStr)
                    }
                }
            ) { Text("Load") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(
            state = datePickerState,
            title = {
                Text(
                    "  Select Replay Date",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, start = 16.dp)
                )
            },
            headline = {
                val millis = datePickerState.selectedDateMillis
                if (millis != null) {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = millis
                    val dateStr = String.format(
                        "%04d-%02d-%02d",
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH) + 1,
                        cal.get(Calendar.DAY_OF_MONTH)
                    )
                    val hasData = availableDates.contains(dateStr)
                    Text(
                        if (hasData) "✅ Data available for $dateStr" else "❌ No data for $dateStr",
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                        fontSize = 12.sp,
                        color = if (hasData) Color(0xFF00E676) else Color(0xFFFF5252)
                    )
                }
            },
            showModeToggle = false
        )
    }
}

// ─── OPTIONS CHAIN COMPACT ───────────────────────────────────────────────────
@Composable
private fun BacktestOptionsChain(
    upstoxService: UpstoxService,
    optionsChain: Map<String, com.example.upstoxmarketdataapp.data.StrikeRowState>
) {
    val indices = listOf("NIFTY", "SENSEX", "BANKNIFTY")
    val labels = upstoxService.strikeLabels
    if (labels.isEmpty()) return

    indices.forEach { sym ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "$sym Options",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp)
                ) {
                    Text("CE LTP", modifier = Modifier.weight(1f), color = Color(0xFF00E676), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("CE Δ", modifier = Modifier.weight(0.8f), color = Color(0xFF00E676), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("STRIKE", modifier = Modifier.weight(1f), color = Color(0xFFFFD600), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("PE Δ", modifier = Modifier.weight(0.8f), color = Color(0xFFFF5252), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("PE LTP", modifier = Modifier.weight(1f), color = Color(0xFFFF5252), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                labels.forEach { lbl ->
                    val row = optionsChain["${sym}_$lbl"]
                    val isAtm = lbl == "ATM"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isAtm) Color(0xFFFFD600).copy(0.06f) else Color.Transparent)
                            .padding(vertical = 2.dp)
                    ) {
                        val ceLtp = row?.ceData?.ltp ?: 0.0
                        val peLtp = row?.peData?.ltp ?: 0.0
                        val ceDiff = row?.ceDiff ?: 0.0
                        val peDiff = row?.peDiff ?: 0.0
                        val strike = row?.strikePrice?.toInt()?.toString() ?: "--"
                        Text(if (ceLtp > 0) "%.1f".format(ceLtp) else "--", modifier = Modifier.weight(1f), color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = if (isAtm) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                        Text(if (ceDiff != 0.0) "%.1f".format(ceDiff) else "--", modifier = Modifier.weight(0.8f), color = if (ceDiff > 0) Color(0xFF00E676) else Color(0xFFFF5252), fontSize = 9.sp, textAlign = TextAlign.Center)
                        Box(modifier = Modifier.weight(1f).background(if (isAtm) Color(0xFFFFD600) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                            Text(strike, color = if (isAtm) Color.Black else MaterialTheme.colorScheme.onSurface, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(if (peDiff != 0.0) "%.1f".format(peDiff) else "--", modifier = Modifier.weight(0.8f), color = if (peDiff > 0) Color(0xFF00E676) else Color(0xFFFF5252), fontSize = 9.sp, textAlign = TextAlign.Center)
                        Text(if (peLtp > 0) "%.1f".format(peLtp) else "--", modifier = Modifier.weight(1f), color = Color(0xFFFF5252), fontSize = 10.sp, fontWeight = if (isAtm) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
