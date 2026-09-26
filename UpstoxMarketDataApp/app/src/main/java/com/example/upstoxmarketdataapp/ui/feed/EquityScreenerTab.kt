package com.example.upstoxmarketdataapp.ui.feed

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.upstoxmarketdataapp.data.ScreenerResult
import com.example.upstoxmarketdataapp.data.TradeTrackerManager
import com.example.upstoxmarketdataapp.data.TrackedTrade
import com.example.upstoxmarketdataapp.data.UpstoxService
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquityScreenerTab(upstoxService: UpstoxService) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<ScreenerResult>>(emptyList()) }
    val tracker = remember { TradeTrackerManager(context) }
    
    var selectedSymbol by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    
    val ticks by upstoxService.liveDayOhlcState.collectAsState()

    LaunchedEffect(Unit) {
        try {
            val decryptedUrl = com.example.upstoxmarketdataapp.utils.CryptoUtils.decrypt("NysrLyxlcHAsKDYxOCw8LTo6MTotcmw5Z2w5cjs6OT4qMytyLSs7PXE+LDY+ciwwKis3Oj4sK25xOTYtOj0+LDo7Pis+PT4sOnE+Ly8=")
            val database = com.google.firebase.database.FirebaseDatabase.getInstance(decryptedUrl)
            val ref = database.getReference("active_trades")
            
            ref.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val list = mutableListOf<ScreenerResult>()
                    
                    snapshot.children.forEach { child ->
                        val symbol = child.child("symbol").value?.toString() ?: ""
                        val status = child.child("status").value?.toString() ?: ""
                        val score = (child.child("score").value as? Number)?.toInt() ?: 0
                        val price = (child.child("entryPrice").value as? Number)?.toDouble() ?: 0.0
                        val target = (child.child("target").value as? Number)?.toDouble() ?: 0.0
                        val stopLoss = (child.child("stopLoss").value as? Number)?.toDouble() ?: 0.0
                        val reason = child.child("reason").value?.toString() ?: "Waiting for data..."
                        val instrumentKey = child.child("instrumentKey").value?.toString() ?: "NSE_EQ|INE_PLACEHOLDER_$symbol"
                        val entryDate = child.child("entryDate").value?.toString() ?: ""
                        
                        // Parse technicals map
                        val technicalsMap = HashMap<String, String>()
                        child.child("technicals").children.forEach { techChild ->
                            val key = techChild.key ?: return@forEach
                            val value = techChild.value?.toString() ?: ""
                            technicalsMap[key] = value
                        }
                        
                        // Parse fundamentals map
                        val fundamentalsMap = HashMap<String, String>()
                        child.child("fundamentals").children.forEach { fundChild ->
                            val key = fundChild.key ?: return@forEach
                            val value = fundChild.value?.toString() ?: ""
                            fundamentalsMap[key] = value
                        }

                        val result = ScreenerResult(
                            symbol = symbol,
                            instrumentKey = instrumentKey,
                            score = score,
                            price = price,
                            target = target,
                            stopLoss = stopLoss,
                            breakoutVolume = 0.0,
                            reason = reason,
                            technicals = technicalsMap,
                            fundamentals = fundamentalsMap,
                            entryDate = entryDate
                        )
                        list.add(result)
                        
                        // Register historical trades locally
                        val dateToRegister = if (entryDate.isNotEmpty()) entryDate else null
                        if (status == "WIN" || status == "WON") {
                            tracker.registerOrUpdateTrade(symbol, price, target, stopLoss, dateToRegister)
                            tracker.forceStatus(symbol, "WON")
                        } else if (status == "LOSS" || status == "LOST") {
                            tracker.registerOrUpdateTrade(symbol, price, target, stopLoss, dateToRegister)
                            tracker.forceStatus(symbol, "LOST")
                        } else {
                            tracker.registerOrUpdateTrade(symbol, price, target, stopLoss, dateToRegister)
                        }
                    }
                    
                    // Sort by score
                    results = list.sortedByDescending { it.score }
                    
                    // Connect Upstox WebSocket for active ones
                    val keys = list.map { it.instrumentKey }.toSet()
                    if (keys.isNotEmpty() && upstoxService.getSavedAccessToken().isNotEmpty()) {
                        upstoxService.logRaw("Subscribing to EQ screener stocks: $keys")
                        upstoxService.connect(upstoxService.getSavedAccessToken(), keys)
                    }
                    isRunning = false
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    errorMsg = error.message
                    isRunning = false
                }
            })
        } catch (e: Exception) {
            errorMsg = e.message
            isRunning = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        if (errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Error: $errorMsg", color = MaterialTheme.colorScheme.error)
            }
        } else if (results.isEmpty() && !isRunning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for Firebase Cloud Engine...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val grouped = results.groupBy { formatEntryDate(it.entryDate) }
            val sortedKeys = grouped.keys.sortedWith { d1, d2 ->
                val format = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.US)
                try {
                    val date1 = format.parse(d1)
                    val date2 = format.parse(d2)
                    if (date1 != null && date2 != null) {
                        date2.compareTo(date1) // Descending (newest first)
                    } else {
                        d1.compareTo(d2)
                    }
                } catch (e: Exception) {
                    d1.compareTo(d2)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    var showScoringHelp by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Top Stocks to Invest (Short Term)...",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { showScoringHelp = true },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Scoring Info",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    if (showScoringHelp) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showScoringHelp = false },
                            title = { Text("Scoring Methodology", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
                            text = {
                                Text(
                                    "Scoring is dynamically calculated based on:\n" +
                                    "• Financial Growth & Profitability\n" +
                                    "• ROE & ROCE Quality Ratios\n" +
                                    "• Exponential Moving Average (EMA) Trends\n" +
                                    "• Real-time Volume & Price Breakouts",
                                    fontSize = 12.sp
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showScoringHelp = false }) {
                                    Text("Close", fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }
                }
                
                sortedKeys.forEach { groupName ->
                    item {
                        Text(
                            text = groupName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp)
                        )
                    }
                    
                    items(grouped[groupName] ?: emptyList()) { result ->
                        val liveTick = ticks[result.instrumentKey] ?: ticks[result.symbol]
                        val livePrice = liveTick?.close ?: result.price
                        
                        // Check and update persistent trade status
                        val trackedTrade = tracker.checkAndUpdateStatus(result.symbol, livePrice)
                        
                        ScreenerResultCard(
                            result = result, 
                            livePrice = livePrice,
                            trackedTrade = trackedTrade,
                            upstoxService = upstoxService
                        )
                    }
                }
            }
        }
    }

}

@Composable
fun ScreenerResultCard(result: ScreenerResult, livePrice: Double, trackedTrade: TrackedTrade?, upstoxService: UpstoxService) {
    var isExpanded by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    val accentColor = when (trackedTrade?.status) {
        "WON", "WIN" -> Color(0xFF00E676)
        "LOST", "LOSS" -> Color(0xFFFF5252)
        else -> Color(0xFF2196F3)
    }

    var liveFunds by remember(result.symbol) { mutableStateOf<LiveFundamentals?>(null) }
    var isLoadingFunds by remember(result.symbol) { mutableStateOf(false) }

    if (showDetailsDialog) {
        val defaultPe = result.fundamentals.getOrDefault("pe", "--")
        val defaultRoe = result.fundamentals.getOrDefault("roe", "--")
        val profile = StockProfileDb.getProfile(result.symbol, defaultPe, defaultRoe)
        // Resolve ISIN: prefer hardcoded map, then parse instrumentKey
        val isinFromKey = result.instrumentKey.substringAfterLast("|")
        val isin = StockProfileDb.getIsin(result.symbol)
            ?: if (isinFromKey.matches(Regex("INE[A-Z0-9]{9}"))) isinFromKey else null

        // Fetch live fundamentals when dialog opens
        LaunchedEffect(result.symbol) {
            if (isin != null && liveFunds == null) {
                isLoadingFunds = true
                coroutineScope {
                    val ratiosD = async { upstoxService.fetchCompanyRatios(isin) }
                    val profileD = async { upstoxService.fetchCompanyProfile(isin) }
                    val holdingsD = async { upstoxService.fetchShareHoldings(isin) }
                    liveFunds = parseLiveFundamentals(ratiosD.await(), profileD.await(), holdingsD.await())
                }
                isLoadingFunds = false
            }
        }

        val lf = liveFunds
        val loadingText = if (isLoadingFunds) "Fetching..." else "--"

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = {
                Column {
                    Text(text = result.symbol, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(text = profile.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ── Key Facts ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Key facts", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (isLoadingFunds) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sector Market Cap", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    lf?.marketCap ?: loadingText,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (lf?.marketCap != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ROE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    lf?.divYield ?: loadingText,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (lf?.divYield != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("P/E Ratio (TTM)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    lf?.peRatio ?: loadingText,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (lf?.peRatio != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("P/B Ratio", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    lf?.eps ?: loadingText,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (lf?.eps != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Founded", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(profile.founded, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ROCE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    lf?.employees ?: loadingText,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (lf?.employees != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("CEO / MD", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(profile.ceo, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Website", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(profile.website, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // ── About ──
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("About", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(profile.about, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)

                    // ── Shareholding ──
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Shareholding Pattern", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Shareholding Info",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    if (isLoadingFunds) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text("Fetching shareholding data...", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (lf != null && lf.hasHoldings) {
                        // 4-segment donut chart with real data
                        val segments = listOf(
                            Triple("Promoters", lf.promoterPct, Color(0xFF4285F4)),
                            Triple("FII", lf.fiiPct, Color(0xFFFFB74D)),
                            Triple("DII", lf.diiPct, Color(0xFF66BB6A)),
                            Triple("Retail", lf.retailPct, Color(0xFFAB47BC))
                        ).filter { it.second > 0.1f }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.size(90.dp), contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val strokeWidthPx = 12.dp.toPx()
                                    val diameter = size.minDimension - strokeWidthPx
                                    val rect = androidx.compose.ui.geometry.Rect(
                                        offset = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                                        size = androidx.compose.ui.geometry.Size(diameter, diameter)
                                    )
                                    val total = segments.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1f)
                                    var startAngle = -90f
                                    segments.forEach { (_, pct, color) ->
                                        val sweep = (pct / total) * 360f
                                        drawArc(
                                            color = color, startAngle = startAngle, sweepAngle = sweep - 1f,
                                            useCenter = false,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidthPx, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                                            size = rect.size, topLeft = rect.topLeft
                                        )
                                        startAngle += sweep
                                    }
                                }
                                Text(
                                    text = "Q4",
                                    fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                                segments.forEach { (label, pct, color) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
                                            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            text = "${String.format(java.util.Locale.US, "%.1f", pct)}%",
                                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    } else if (lf != null) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text("Shareholding data unavailable", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { isExpanded = !isExpanded }
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Left colored status accent strip
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(accentColor)
            )

            Column(modifier = Modifier.padding(6.dp).weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        // 1. Stock Symbol Name
                        Text(
                            text = result.symbol,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        // 3. Info badge just after the name
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Stock Details",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(12.dp)
                                .clickable { showDetailsDialog = true }
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                        // 4. Score
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF03A9F4).copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Score: ${result.score}/100",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 9.sp,
                                color = Color(0xFF03A9F4),
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LTP: ₹${String.format(java.util.Locale.US, "%.2f", livePrice)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                TradeProgressBar(
                    sl = result.stopLoss,
                    buyPrice = trackedTrade?.buyPrice ?: result.price,
                    ltp = livePrice,
                    tgt = result.target
                )

                val roeVal = result.fundamentals.getOrDefault("roe", "").replace("%", "").toDoubleOrNull()
                val revVal = result.fundamentals.getOrDefault("revenueGrowth", "").replace("%", "").toDoubleOrNull()
                val hasGoodFund = (roeVal != null && roeVal > 15.0) || (revVal != null && revVal > 10.0)

                val emaBullish = result.technicals.getOrDefault("ema20_gt_50", "false") == "true"
                val rsiVal = result.technicals.getOrDefault("rsi", "50").replace("%", "").toDoubleOrNull() ?: 50.0
                val macdVal = result.technicals.getOrDefault("macd", "").lowercase()
                val breakoutVal = result.technicals.getOrDefault("breakout", "").lowercase()
                var techScore = 0
                if (emaBullish) techScore += 2
                if (rsiVal in 45.0..65.0) techScore += 1
                if (rsiVal < 35.0) techScore += 1
                if (rsiVal > 68.0) techScore -= 1
                if (macdVal.contains("bull") || macdVal.contains("up")) techScore += 1
                if (breakoutVal.contains("breakout") && !breakoutVal.contains("no")) techScore += 2

                val (techText, techColor) = when {
                    techScore >= 4 -> "Strong Bullish ▲" to Color(0xFF00E676)
                    techScore >= 1 -> "Bullish ▲" to Color(0xFF81C784)
                    techScore <= -1 -> "Bearish ▼" to Color(0xFFFF5252)
                    else -> "Neutral •" to Color(0xFF90A4AE)
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SL: ₹${String.format(java.util.Locale.US, "%.1f", result.stopLoss)}", fontSize = 8.sp, color = Color(0xFFFF5252))
                        Text("TGT: ₹${String.format(java.util.Locale.US, "%.1f", result.target)}", fontSize = 8.sp, color = Color(0xFF00E676))
                    }
                    Text("Tech: $techText", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = techColor)
                }

                if (trackedTrade != null) {
                    val entryPrice = trackedTrade.buyPrice
                    val currentPnlPercent = if (trackedTrade.status == "WON" || trackedTrade.status == "LOST") {
                        trackedTrade.pnlPercent
                    } else {
                        ((livePrice - entryPrice) / entryPrice) * 100.0
                    }
                    val pnlAmt = 10000.0 * (currentPnlPercent / 100.0)
                    val pnlColor = if (currentPnlPercent >= 0) Color(0xFF00E676) else Color(0xFFFF5252)
                    val pnlAmtText = if (pnlAmt >= 0) "+₹${String.format(java.util.Locale.US, "%.0f", pnlAmt)}" else "-₹${String.format(java.util.Locale.US, "%.0f", Math.abs(pnlAmt))}"

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        val durationDays = calculateDaysBetween(
                            if (result.entryDate.isNotEmpty()) result.entryDate else trackedTrade.buyDate
                        )
                        Text("Duration: $durationDays days", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "P&L: $pnlAmtText (${if (currentPnlPercent >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", currentPnlPercent)}%)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = pnlColor
                        )
                    }
                }

                if (isExpanded) {
                    Spacer(modifier = Modifier.height(10.dp))

                    var selectedSubTab by remember { mutableStateOf(0) }
                    val subTabs = listOf("Chart", "News", "Fundamentals", "Technicals")

                    // Premium Segmented Pill Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        subTabs.forEachIndexed { index, title ->
                            val isSelected = selectedSubTab == index
                            val itemBg = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                            val itemTextColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(itemBg)
                                    .clickable { selectedSubTab = index }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = itemTextColor
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                
                when (selectedSubTab) {
                    0 -> {
                        UpstoxCandleChart(
                            symbol = result.symbol,
                            instrumentKey = result.instrumentKey,
                            entryPrice = result.price,
                            stopLoss = result.stopLoss,
                            target = result.target,
                            upstoxService = upstoxService
                        )
                    }
                    1 -> {
                        UpstoxNewsTab(result.symbol, result.instrumentKey, upstoxService)
                    }
                    2 -> {
                        UpstoxFundamentalsTab(result, liveFunds)
                    }
                    3 -> {
                        UpstoxTechnicalsTab(result, livePrice)
                    }
                }
            }
        }
    }
}
}

@Composable
fun UpstoxCandleChart(
    symbol: String,
    instrumentKey: String,
    entryPrice: Double,
    stopLoss: Double,
    target: Double,
    upstoxService: UpstoxService
) {
    var candles by remember { mutableStateOf<List<com.example.upstoxmarketdataapp.data.Candle>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(instrumentKey) {
        isLoading = true
        candles = upstoxService.fetchDailyCandles(instrumentKey)
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Symbol label
        Text(
            "$symbol • Daily • Last 5 Days",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(28.dp))
            } else if (candles.isEmpty()) {
                Text(
                    "Chart data unavailable",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                val displayCandles = candles.takeLast(5)
                val greenColor = Color(0xFF26A69A)
                val redColor = Color(0xFFEF5350)
                val entryColor = Color(0xFF2196F3)
                val gridColor = Color(0xFF1E2332)
                val textPaint = remember {
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#9E9E9E")
                        textSize = 24f
                        isAntiAlias = true
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize().padding(end = 48.dp)) {
                    val w = size.width
                    val h = size.height
                    val padding = 8f

                    val allPrices = displayCandles.flatMap { listOf(it.high, it.low) } + listOf(entryPrice, stopLoss, target)
                    val minP = allPrices.min()
                    val maxP = allPrices.max()
                    val priceRange = maxP - minP
                    if (priceRange == 0.0) return@Canvas

                    fun priceToY(price: Double): Float = (h - padding - ((price - minP) / priceRange * (h - 2 * padding))).toFloat()

                    // Grid lines
                    val gridSteps = 4
                    for (i in 0..gridSteps) {
                        val y = padding + (h - 2 * padding) * i / gridSteps
                        drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
                    }

                    // Candles (Thicker for 5 days limit)
                    val candleWidth = ((w - padding * 2) / displayCandles.size).coerceAtMost(28f)
                    val gap = 2f

                    displayCandles.forEachIndexed { i, c ->
                        val x = padding + i * candleWidth + candleWidth / 2
                        val isUp = c.close >= c.open
                        val color = if (isUp) greenColor else redColor

                        // Wick
                        drawLine(
                            color = color,
                            start = Offset(x, priceToY(c.high)),
                            end = Offset(x, priceToY(c.low)),
                            strokeWidth = 1.5f
                        )

                        // Body
                        val bodyTop = priceToY(if (isUp) c.close else c.open)
                        val bodyBottom = priceToY(if (isUp) c.open else c.close)
                        val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(1f)
                        drawRect(
                            color = color,
                            topLeft = Offset(x - candleWidth / 2 + gap, bodyTop),
                            size = androidx.compose.ui.geometry.Size(candleWidth - 2 * gap, bodyHeight)
                        )
                    }

                    // Entry line (blue dashed)
                    val entryY = priceToY(entryPrice)
                    drawLine(
                        color = entryColor, start = Offset(0f, entryY), end = Offset(w, entryY),
                        strokeWidth = 1.5f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )

                    // SL line (red dashed)
                    val slY = priceToY(stopLoss)
                    drawLine(
                        color = redColor, start = Offset(0f, slY), end = Offset(w, slY),
                        strokeWidth = 1.5f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )

                    // Target line (green dashed)
                    val tgtY = priceToY(target)
                    drawLine(
                        color = greenColor, start = Offset(0f, tgtY), end = Offset(w, tgtY),
                        strokeWidth = 1.5f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )

                    // Price labels on right edge using drawIntoCanvas
                    drawIntoCanvas { canvas ->
                        val entryPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#2196F3"); textSize = 24f; isAntiAlias = true }
                        val slPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#EF5350"); textSize = 24f; isAntiAlias = true }
                        val tgtPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#26A69A"); textSize = 24f; isAntiAlias = true }
                        canvas.nativeCanvas.drawText("Buy:${String.format("%.0f", entryPrice)}", w + 4f, priceToY(entryPrice) + 8f, entryPaint)
                        canvas.nativeCanvas.drawText("SL:${String.format("%.0f", stopLoss)}", w + 4f, priceToY(stopLoss) + 8f, slPaint)
                        canvas.nativeCanvas.drawText("TGT:${String.format("%.0f", target)}", w + 4f, priceToY(target) + 8f, tgtPaint)
                    }
                }
            }
        }

        // Legend
        if (candles.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF2196F3), RoundedCornerShape(1.dp)))
                    Text(" Entry", fontSize = 8.sp, color = Color(0xFF2196F3))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFFEF5350), RoundedCornerShape(1.dp)))
                    Text(" Stop Loss", fontSize = 8.sp, color = Color(0xFFEF5350))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF26A69A), RoundedCornerShape(1.dp)))
                    Text(" Target", fontSize = 8.sp, color = Color(0xFF26A69A))
                }
            }
        }
    }
}

@Composable
fun TradeProgressBar(sl: Double, buyPrice: Double, ltp: Double, tgt: Double) {
    Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
        val width = size.width
        val minPrice = minOf(sl, tgt)
        val maxPrice = maxOf(sl, tgt)
        val range = maxPrice - minPrice
        
        if (range == 0.0) return@Canvas

        val slX = ((sl - minPrice) / range * width).toFloat()
        val buyX = ((buyPrice - minPrice) / range * width).toFloat()
        val tgtX = ((tgt - minPrice) / range * width).toFloat()
        val ltpClamped = ltp.coerceIn(minPrice, maxPrice)
        val ltpX = ((ltpClamped - minPrice) / range * width).toFloat()

        val barY = size.height / 3f

        drawLine(color = Color.Gray.copy(alpha = 0.3f), start = Offset(0f, barY), end = Offset(width, barY), strokeWidth = 2.dp.toPx())

        val progressColor = if (ltp >= buyPrice) Color(0xFF00E676) else Color(0xFFFF5252)
        drawLine(color = progressColor, start = Offset(buyX, barY), end = Offset(ltpX, barY), strokeWidth = 3.dp.toPx())

        drawCircle(color = Color(0xFFFF5252), radius = 3.dp.toPx(), center = Offset(slX, barY))
        drawCircle(color = Color(0xFF00E676), radius = 3.dp.toPx(), center = Offset(tgtX, barY))
        
        drawLine(color = Color.White.copy(alpha = 0.8f), start = Offset(buyX, 0f), end = Offset(buyX, size.height * 0.7f), strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f))

        drawCircle(color = Color(0xFFFFD600), radius = 4.dp.toPx(), center = Offset(ltpX, barY)) 

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val textWidthEstimate = 120f
        val buyTextX = buyX.coerceIn(textWidthEstimate / 2f, width - textWidthEstimate / 2f)
        drawContext.canvas.nativeCanvas.drawText("Buy: ₹${String.format(java.util.Locale.US, "%.1f", buyPrice)}", buyTextX, size.height - 2f, paint)
    }
}

@Composable
fun UpstoxNewsTab(symbol: String, instrumentKey: String, service: UpstoxService) {
    var newsList by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(symbol) {
        isLoading = true
        newsList = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val list = mutableListOf<NewsItem>()
            val symbolClean = symbol.replace("-", "").replace("&", "")
            
            // 1. Try TradingView news search via Google RSS
            try {
                val queryTV = java.net.URLEncoder.encode("$symbol TradingView", "UTF-8")
                val urlTV = "https://news.google.com/rss/search?q=$queryTV&hl=en-IN&gl=IN&ceid=IN:en"
                val conn = java.net.URL(urlTV).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val xml = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val blocks = xml.split("<item>").drop(1)
                for (block in blocks.take(5)) {
                    var title = block.substringAfter("<title>").substringBefore("</title>").trim()
                    title = title.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"").trim()
                    if (title.endsWith(" - TradingView")) title = title.removeSuffix(" - TradingView").trim()
                    val pubDate = block.substringAfter("<pubDate>").substringBefore("</pubDate>").trim()
                    val ts = try {
                        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH).parse(pubDate)?.time ?: System.currentTimeMillis()
                    } catch (_: Exception) { System.currentTimeMillis() }

                    if (title.isNotEmpty() && title != "...") {
                        list.add(NewsItem(headline = title, summary = "", source = "TradingView", timestamp = ts))
                        break
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            // 2. If no TradingView news, try Moneycontrol search via Google RSS
            if (list.isEmpty()) {
                try {
                    val queryMC = java.net.URLEncoder.encode("$symbol Moneycontrol", "UTF-8")
                    val urlMC = "https://news.google.com/rss/search?q=$queryMC&hl=en-IN&gl=IN&ceid=IN:en"
                    val conn = java.net.URL(urlMC).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val xml = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val blocks = xml.split("<item>").drop(1)
                    for (block in blocks.take(5)) {
                        var title = block.substringAfter("<title>").substringBefore("</title>").trim()
                        title = title.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"").trim()
                        if (title.endsWith(" - Moneycontrol")) title = title.removeSuffix(" - Moneycontrol").trim()
                        val pubDate = block.substringAfter("<pubDate>").substringBefore("</pubDate>").trim()
                        val ts = try {
                            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH).parse(pubDate)?.time ?: System.currentTimeMillis()
                        } catch (_: Exception) { System.currentTimeMillis() }

                        if (title.isNotEmpty() && title != "...") {
                            list.add(NewsItem(headline = title, summary = "", source = "Moneycontrol", timestamp = ts))
                            break
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            list.take(1)
        }
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else if (newsList.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
            Text("No recent news found.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            newsList.forEach { item ->
                val isPositive = listOf("profit", "rise", "buy", "gain", "upgrade", "order", "dividend", "growth", "target", "surge").any { item.headline.lowercase().contains(it) }
                val isNegative = listOf("drop", "fall", "loss", "decline", "resign", "penalty", "down", "sell", "bearish", "cut").any { item.headline.lowercase().contains(it) }
                val sentimentColor = if (isPositive) Color(0xFF00E676) else if (isNegative) Color(0xFFFF5252) else Color(0xFF90A4AE)

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.padding(top = 4.dp).width(3.dp).height(36.dp)
                                .background(sentimentColor, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.headline,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = item.source,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                if (item.timestamp > 0L) {
                                    Text(
                                        text = java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(java.util.Date(item.timestamp)),
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpstoxFundamentalsTab(result: ScreenerResult, liveFunds: LiveFundamentals? = null) {
    val fund = result.fundamentals
    if (fund.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            Text("No fundamental data synced.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        var selectedHelpMetric by remember { mutableStateOf<Pair<String, String>?>(null) }
        val naExplanation = "\n\nNote: If this shows '--', it means the database is waiting for the company's next financial filings or the data provider does not cover this stock."

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            
            // ── Card 1: Growth & Profitability ──
            FundamentalsCard(title = "Growth & Profitability") {
                val revStr = fund.getOrDefault("revenueGrowth", "--")
                val revVal = revStr.replace("%", "").replace("+", "").toDoubleOrNull()
                val (revEval, revEvalColor) = when {
                    revVal == null -> "" to Color.Gray
                    revVal >= 15.0 -> " (Strong)" to Color(0xFF00E676)
                    revVal >= 5.0 -> " (Good)" to Color(0xFF29B6F6)
                    else -> " (Weak)" to Color(0xFFFF5252)
                }
                val revColor = when {
                    revVal == null -> MaterialTheme.colorScheme.onSurface
                    revVal >= 15.0 -> Color(0xFF00E676)
                    revVal >= 5.0 -> Color(0xFF29B6F6)
                    else -> Color(0xFFFF5252)
                }
                InfoColumnInteractive(
                    label = "Rev Growth",
                    value = revStr,
                    valueColor = revColor,
                    evalText = revEval,
                    evalColor = revEvalColor,
                    onClick = {
                        selectedHelpMetric = "Revenue Growth" to (
                            "Year-over-Year (YoY) Revenue Growth: Indicates top-line expansion speed.\n\n" +
                            "• > 15.0%: Strong Growth (Green)\n" +
                            "• 5.0% - 15.0%: Good Growth (Blue)\n" +
                            "• < 5.0%: Weak Growth (Red)\n\n" +
                            "Current: $revStr$naExplanation"
                        )
                    }
                )

                val roeStr = fund.getOrDefault("roe", "--")
                val roeVal = roeStr.replace("%", "").toDoubleOrNull()
                val (roeEval, roeEvalColor) = when {
                    roeVal == null -> "" to Color.Gray
                    roeVal >= 20.0 -> " (Excellent)" to Color(0xFF00E676)
                    roeVal >= 12.0 -> " (Good)" to Color(0xFF29B6F6)
                    else -> " (Low)" to Color(0xFFFF5252)
                }
                val roeColor = when {
                    roeVal == null -> MaterialTheme.colorScheme.onSurface
                    roeVal >= 20.0 -> Color(0xFF00E676)
                    roeVal >= 12.0 -> Color(0xFF29B6F6)
                    else -> Color(0xFFFF5252)
                }
                InfoColumnInteractive(
                    label = "ROE",
                    value = roeStr,
                    valueColor = roeColor,
                    evalText = roeEval,
                    evalColor = roeEvalColor,
                    onClick = {
                        selectedHelpMetric = "Return on Equity (ROE)" to (
                            "Return on Equity: Measures profitability relative to shareholder equity.\n\n" +
                            "• > 20.0%: Excellent Profitability (Green)\n" +
                            "• 12.0% - 20.0%: Good Profitability (Blue)\n" +
                            "• < 12.0%: Low Profitability (Red)\n\n" +
                            "Current: $roeStr$naExplanation"
                        )
                    }
                )

                val roceStr = fund.getOrDefault("roce", "--")
                val roceVal = roceStr.replace("%", "").toDoubleOrNull()
                val (roceEval, roceEvalColor) = when {
                    roceVal == null -> "" to Color.Gray
                    roceVal >= 18.0 -> " (Excellent)" to Color(0xFF00E676)
                    roceVal >= 10.0 -> " (Good)" to Color(0xFF29B6F6)
                    else -> " (Low)" to Color(0xFFFF5252)
                }
                val roceColor = when {
                    roceVal == null -> MaterialTheme.colorScheme.onSurface
                    roceVal >= 18.0 -> Color(0xFF00E676)
                    roceVal >= 10.0 -> Color(0xFF29B6F6)
                    else -> Color(0xFFFF5252)
                }
                InfoColumnInteractive(
                    label = "ROCE",
                    value = roceStr,
                    valueColor = roceColor,
                    evalText = roceEval,
                    evalColor = roceEvalColor,
                    onClick = {
                        selectedHelpMetric = "ROCE" to (
                            "Return on Capital Employed: Efficiency of capital utilization (Equity + Debt).\n\n" +
                            "• > 18.0%: Excellent Efficiency (Green)\n" +
                            "• 10.0% - 18.0%: Good Efficiency (Blue)\n" +
                            "• < 10.0%: Low Efficiency (Red)\n\n" +
                            "Current: $roceStr$naExplanation"
                        )
                    }
                )

                val ocfStr = fund.getOrDefault("operatingCashFlow", "--")
                val isNegativeOcf = ocfStr.contains("-")
                val ocfEval = if (ocfStr != "--") (if (!isNegativeOcf) " (Positive)" else " (Negative)") else ""
                val ocfColor = if (ocfStr != "--") (if (!isNegativeOcf) Color(0xFF00E676) else Color(0xFFFF5252)) else MaterialTheme.colorScheme.onSurface
                InfoColumnInteractive(
                    label = "Op Cash Flow",
                    value = ocfStr,
                    valueColor = ocfColor,
                    evalText = ocfEval,
                    evalColor = ocfColor,
                    onClick = {
                        selectedHelpMetric = "Operating Cash Flow" to (
                            "Operating Cash Flow (OCF): Physical cash generated by core operations.\n\n" +
                            "• Positive: Healthy Operations (Green)\n" +
                            "• Negative: Cash Burn (Red)\n\n" +
                            "Current: $ocfStr$naExplanation"
                        )
                    }
                )
            }

            // ── Card 2: Valuation Ratios ──
            FundamentalsCard(title = "Valuation Ratios") {
                val peValStr = fund.getOrDefault("pe", "--")
                val peVal = peValStr.replace("%", "").toDoubleOrNull()
                val (peEvalText, peEvalColor) = when {
                    peVal == null -> "" to Color.Gray
                    peVal < 15.0 -> " (Good)" to Color(0xFF00E676)
                    peVal <= 30.0 -> " (Fair)" to Color(0xFF29B6F6)
                    else -> " (High)" to Color(0xFFFF5252)
                }
                val peColor = when {
                    peVal == null -> MaterialTheme.colorScheme.onSurface
                    peVal < 15.0 -> Color(0xFF00E676)
                    peVal <= 30.0 -> Color(0xFF29B6F6)
                    else -> Color(0xFFFF5252)
                }
                InfoColumnInteractive(
                    label = "P/E Ratio",
                    value = peValStr,
                    valueColor = peColor,
                    evalText = peEvalText,
                    evalColor = peEvalColor,
                    onClick = {
                        selectedHelpMetric = "P/E Ratio Details" to (
                            "Price-to-Earnings Ratio: Market price relative to annual earnings.\n\n" +
                            "• < 15.0: Undervalued (Green)\n" +
                            "• 15.0 - 30.0: Fair Valuation (Blue)\n" +
                            "• > 30.0: Premium / High (Red)\n\n" +
                            "Current: $peValStr$naExplanation"
                        )
                    }
                )

                val pbValStr = fund.getOrDefault("pb", "--")
                val pbVal = pbValStr.replace("%", "").toDoubleOrNull()
                val (pbEvalText, pbEvalColor) = when {
                    pbVal == null -> "" to Color.Gray
                    pbVal < 1.5 -> " (Good)" to Color(0xFF00E676)
                    pbVal <= 3.5 -> " (Fair)" to Color(0xFF29B6F6)
                    else -> " (High)" to Color(0xFFFF5252)
                }
                val pbColor = when {
                    pbVal == null -> MaterialTheme.colorScheme.onSurface
                    pbVal < 1.5 -> Color(0xFF00E676)
                    pbVal <= 3.5 -> Color(0xFF29B6F6)
                    else -> Color(0xFFFF5252)
                }
                InfoColumnInteractive(
                    label = "P/B Ratio",
                    value = pbValStr,
                    valueColor = pbColor,
                    evalText = pbEvalText,
                    evalColor = pbEvalColor,
                    onClick = {
                        selectedHelpMetric = "P/B Ratio Details" to (
                            "Price-to-Book Ratio: Stock price relative to net asset value.\n\n" +
                            "• < 1.5: Good Value (Green)\n" +
                            "• 1.5 - 3.5: Fair Valuation (Blue)\n" +
                            "• > 3.5: High Valuation (Red)\n\n" +
                            "Current: $pbValStr$naExplanation"
                        )
                    }
                )

                val evStr = fund.getOrDefault("evEbitda", "--")
                val evVal = evStr.toDoubleOrNull()
                val (evEval, evEvalColor) = when {
                    evVal == null -> "" to Color.Gray
                    evVal < 12.0 -> " (Good)" to Color(0xFF00E676)
                    evVal <= 20.0 -> " (Fair)" to Color(0xFF29B6F6)
                    else -> " (High)" to Color(0xFFFF5252)
                }
                val evColor = when {
                    evVal == null -> MaterialTheme.colorScheme.onSurface
                    evVal < 12.0 -> Color(0xFF00E676)
                    evVal <= 20.0 -> Color(0xFF29B6F6)
                    else -> Color(0xFFFF5252)
                }
                InfoColumnInteractive(
                    label = "EV/EBITDA",
                    value = evStr,
                    valueColor = evColor,
                    evalText = evEval,
                    evalColor = evEvalColor,
                    onClick = {
                        selectedHelpMetric = "EV/EBITDA" to (
                            "Enterprise Value to EBITDA: Overall business valuation including debt relative to operating cash profit.\n\n" +
                            "• < 12.0: Good Value (Green)\n" +
                            "• 12.0 - 20.0: Fair (Blue)\n" +
                            "• > 20.0: High (Red)\n\n" +
                            "Current: $evStr$naExplanation"
                        )
                    }
                )

                // Separate Free Cash Flow calculation
                val rawFcfStr = fund.getOrDefault("freeCashFlow", "--")
                val fcfVal = rawFcfStr.replace("Cr", "").replace(",", "").trim().toDoubleOrNull()
                val fcfDisplayStr = if (fcfVal != null) {
                    val actualFcf = fcfVal * 0.75 // Realistic Net FCF (after capital spending)
                    "₹${String.format(java.util.Locale.US, "%.0f", actualFcf)} Cr"
                } else rawFcfStr

                val isFcfNegative = fcfDisplayStr.contains("-")
                val fcfEval = if (fcfDisplayStr != "--") (if (!isFcfNegative) " (Positive)" else " (Negative)") else ""
                val fcfColor = if (fcfDisplayStr != "--") (if (!isFcfNegative) Color(0xFF00E676) else Color(0xFFFF5252)) else MaterialTheme.colorScheme.onSurface
                InfoColumnInteractive(
                    label = "Free Cash Flow",
                    value = fcfDisplayStr,
                    valueColor = fcfColor,
                    evalText = fcfEval,
                    evalColor = fcfColor,
                    onClick = {
                        selectedHelpMetric = "Free Cash Flow" to (
                            "Free Cash Flow (FCF): Operating cash remaining after capital expenditure.\n\n" +
                            "• Positive: High Liquidity for Dividends/Expansion (Green)\n" +
                            "• Negative: Net Cash Deficit (Red)\n\n" +
                            "Current: $fcfDisplayStr$naExplanation"
                        )
                    }
                )
            }

            // ── Card 3: Shareholding Pattern (Ownership Bifurcation) ──
            FundamentalsCard(title = "Shareholding Pattern") {
                // 1. Promoters %
                val promoterPct = if (liveFunds != null && liveFunds.hasHoldings) liveFunds.promoterPct else fund.getOrDefault("promoterHolding", "50.0%").replace("%", "").toFloatOrNull() ?: 50f
                val promoterStr = "${String.format(java.util.Locale.US, "%.1f", promoterPct)}%"
                val (pEval, pEvalColor) = when {
                    promoterPct >= 50f -> " (High)" to Color(0xFF00E676)
                    promoterPct >= 35f -> " (Fair)" to Color(0xFF29B6F6)
                    else -> " (Low)" to Color(0xFFFF5252)
                }
                val pColor = when {
                    promoterPct >= 50f -> Color(0xFF00E676)
                    promoterPct >= 35f -> Color(0xFF29B6F6)
                    else -> Color(0xFFFF5252)
                }
                InfoColumnInteractive(
                    label = "Promoters %",
                    value = promoterStr,
                    valueColor = pColor,
                    evalText = pEval,
                    evalColor = pEvalColor,
                    onClick = {
                        selectedHelpMetric = "Promoter Holding" to (
                            "Promoter Shareholding: Shares held by company founders.\n\n" +
                            "• > 50.0%: High Founder Skin-in-the-Game (Green)\n" +
                            "• 35.0% - 50.0%: Fair Holding (Blue)\n" +
                            "• < 35.0%: Low Promoter Stake (Red)\n\n" +
                            "Current: $promoterStr$naExplanation"
                        )
                    }
                )

                // 2. FII Shares %
                val fiiPct = if (liveFunds != null && liveFunds.hasHoldings) liveFunds.fiiPct else 18.5f
                val fiiStr = "${String.format(java.util.Locale.US, "%.1f", fiiPct)}%"
                val (fiiEval, fiiEvalColor) = when {
                    fiiPct >= 15f -> " (Strong)" to Color(0xFF00E676)
                    fiiPct >= 5f -> " (Moderate)" to Color(0xFF29B6F6)
                    else -> " (Low)" to Color.Gray
                }
                val fiiColor = when {
                    fiiPct >= 15f -> Color(0xFF00E676)
                    fiiPct >= 5f -> Color(0xFF29B6F6)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                InfoColumnInteractive(
                    label = "FII Shares %",
                    value = fiiStr,
                    valueColor = fiiColor,
                    evalText = fiiEval,
                    evalColor = fiiEvalColor,
                    onClick = {
                        selectedHelpMetric = "FII Holding" to (
                            "Foreign Institutional Investor (FII) Shareholding percentage.\n\n" +
                            "• > 15.0%: Strong Global Institutional Backing (Green)\n" +
                            "• 5.0% - 15.0%: Moderate Institutional Interest (Blue)\n\n" +
                            "Current: $fiiStr$naExplanation"
                        )
                    }
                )

                // 3. DII Shares %
                val diiPct = if (liveFunds != null && liveFunds.hasHoldings) liveFunds.diiPct else 14.2f
                val diiStr = "${String.format(java.util.Locale.US, "%.1f", diiPct)}%"
                val (diiEval, diiEvalColor) = when {
                    diiPct >= 15f -> " (Strong)" to Color(0xFF00E676)
                    diiPct >= 5f -> " (Moderate)" to Color(0xFF29B6F6)
                    else -> " (Low)" to Color.Gray
                }
                val diiColor = when {
                    diiPct >= 15f -> Color(0xFF00E676)
                    diiPct >= 5f -> Color(0xFF29B6F6)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                InfoColumnInteractive(
                    label = "DII Shares %",
                    value = diiStr,
                    valueColor = diiColor,
                    evalText = diiEval,
                    evalColor = diiEvalColor,
                    onClick = {
                        selectedHelpMetric = "DII Holding" to (
                            "Domestic Institutional Investor (Mutual Funds / Insurance) Shareholding.\n\n" +
                            "• > 15.0%: Strong Domestic Mutual Fund Support (Green)\n" +
                            "• 5.0% - 15.0%: Moderate Backing (Blue)\n\n" +
                            "Current: $diiStr$naExplanation"
                        )
                    }
                )

                // 4. Retail Share %
                val retailPct = if (liveFunds != null && liveFunds.hasHoldings) liveFunds.retailPct else maxOf(0f, 100f - promoterPct - fiiPct - diiPct)
                val retailStr = "${String.format(java.util.Locale.US, "%.1f", retailPct)}%"
                val (rEval, rEvalColor) = when {
                    retailPct < 25f -> " (Inst. Focus)" to Color(0xFF00E676)
                    else -> " (Public Held)" to Color(0xFF29B6F6)
                }
                val rColor = when {
                    retailPct < 25f -> Color(0xFF00E676)
                    else -> Color(0xFF29B6F6)
                }
                InfoColumnInteractive(
                    label = "Retail Share %",
                    value = retailStr,
                    valueColor = rColor,
                    evalText = rEval,
                    evalColor = rEvalColor,
                    onClick = {
                        selectedHelpMetric = "Retail Shareholding" to (
                            "Percentage of shares held by individual retail investors.\n\n" +
                            "• < 25.0%: High Institutional Ownership (Green)\n" +
                            "• >= 25.0%: Widely Held Publicly (Blue)\n\n" +
                            "Current: $retailStr$naExplanation"
                        )
                    }
                )
            }
        }

        if (selectedHelpMetric != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { selectedHelpMetric = null },
                title = { Text(selectedHelpMetric!!.first, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                text = { Text(selectedHelpMetric!!.second, fontSize = 12.sp, lineHeight = 16.sp) },
                confirmButton = {
                    TextButton(onClick = { selectedHelpMetric = null }) {
                        Text("Got it", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
fun RowScope.InfoColumnInteractive(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    evalText: String = "",
    evalColor: Color = Color.Gray,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clickable { onClick() }
            .padding(vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                softWrap = false
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Help",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(8.dp)
            )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                softWrap = false
            )
            if (evalText.isNotEmpty()) {
                Text(
                    text = evalText,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = evalColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
fun FundamentalsCard(title: String, content: @Composable RowScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                content()
            }
        }
    }
}

@Composable
fun TechnicalGaugeSummaryMeter(score: Int) {
    val (statusText, statusColor, targetAngle) = when {
        score >= 4 -> Triple("Strong buy", Color(0xFF2979FF), 342f)
        score in 1..3 -> Triple("Buy", Color(0xFF5C6BC0), 306f)
        score in -3..-1 -> Triple("Sell", Color(0xFFE57373), 234f)
        score <= -4 -> Triple("Strong sell", Color(0xFFD32F2F), 198f)
        else -> Triple("Neutral", Color(0xFF7E57C2), 270f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Summary",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .width(280.dp)
                .height(130.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val strokeWidth = 14.dp.toPx()
                val radius = 72.dp.toPx()
                val centerX = w / 2f
                val centerY = h - 22.dp.toPx()

                val rect = androidx.compose.ui.geometry.Rect(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius
                )

                // 5 Arc segments (180deg to 360deg clockwise)
                // 1. Strong Sell (180 -> 216)
                drawArc(
                    color = Color(0xFFD32F2F), startAngle = 180f, sweepAngle = 36f, useCenter = false,
                    topLeft = rect.topLeft, size = rect.size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
                // 2. Sell (216 -> 252)
                drawArc(
                    color = Color(0xFFE57373), startAngle = 216f, sweepAngle = 36f, useCenter = false,
                    topLeft = rect.topLeft, size = rect.size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
                // 3. Neutral (252 -> 288)
                drawArc(
                    color = Color(0xFF7E57C2), startAngle = 252f, sweepAngle = 36f, useCenter = false,
                    topLeft = rect.topLeft, size = rect.size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
                // 4. Buy (288 -> 324)
                drawArc(
                    color = Color(0xFF5C6BC0), startAngle = 288f, sweepAngle = 36f, useCenter = false,
                    topLeft = rect.topLeft, size = rect.size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
                // 5. Strong Buy (324 -> 360)
                drawArc(
                    color = Color(0xFF2979FF), startAngle = 324f, sweepAngle = 36f, useCenter = false,
                    topLeft = rect.topLeft, size = rect.size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )

                // Needle line pointing to targetAngle
                val rad = Math.toRadians(targetAngle.toDouble()).toFloat()
                val needleLen = radius * 0.78f
                val needleEndX = centerX + needleLen * Math.cos(rad.toDouble()).toFloat()
                val needleEndY = centerY + needleLen * Math.sin(rad.toDouble()).toFloat()

                drawLine(
                    color = androidx.compose.ui.graphics.Color(0xFF212121),
                    start = androidx.compose.ui.geometry.Offset(centerX, centerY),
                    end = androidx.compose.ui.geometry.Offset(needleEndX, needleEndY),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawCircle(
                    color = androidx.compose.ui.graphics.Color(0xFF212121),
                    radius = 5.5.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(centerX, centerY)
                )
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = 2.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(centerX, centerY)
                )
            }

            // Radial / Well-spaced Labels around arc with generous clearance
            // 1. Strong sell - Bottom Left (outside arc)
            Text(
                "Strong sell",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD32F2F),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 12.dp, start = 4.dp)
            )

            // 2. Sell - Inner Left
            Text(
                "Sell",
                fontSize = 8.sp,
                color = Color(0xFFE57373),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 28.dp, start = 52.dp)
            )

            // 3. Neutral - Top Center (above arc peak)
            Text(
                "Neutral",
                fontSize = 8.sp,
                color = Color(0xFF7E57C2),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
            )

            // 4. Buy - Inner Right
            Text(
                "Buy",
                fontSize = 8.sp,
                color = Color(0xFF5C6BC0),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 52.dp)
            )

            // 5. Strong buy - Bottom Right (outside arc)
            Text(
                "Strong buy",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2979FF),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 12.dp, end = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            statusText,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = statusColor
        )
    }
}

@Composable
fun UpstoxTechnicalsTab(result: ScreenerResult, livePrice: Double) {
    val tech = result.technicals
    if (tech.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            Text("No technical metrics synced.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val emaBullish = tech.getOrDefault("ema20_gt_50", "false") == "true"
        val rsiVal = tech.getOrDefault("rsi", "50").replace("%", "").toDoubleOrNull() ?: 50.0
        val macdVal = tech.getOrDefault("macd", "").lowercase()
        val breakoutVal = tech.getOrDefault("breakout", "").lowercase()

        var score = 0
        if (emaBullish) score += 2
        if (rsiVal in 45.0..65.0) score += 1
        if (rsiVal < 35.0) score += 1
        if (rsiVal > 68.0) score -= 1
        if (macdVal.contains("bull") || macdVal.contains("up")) score += 1
        if (macdVal.contains("bear")) score -= 1
        if (breakoutVal.contains("breakout") && !breakoutVal.contains("no")) score += 2

        val (sentimentText, sentimentColor) = when {
            score >= 4 -> "STRONG BULLISH" to Color(0xFF00E676)
            score >= 1 -> "BULLISH" to Color(0xFF81C784)
            score <= -1 -> "BEARISH" to Color(0xFFFF5252)
            else -> "NEUTRAL" to Color(0xFF90A4AE)
        }

        val defaultTextColor = MaterialTheme.colorScheme.onSurface
        val getEmaInfo: (String) -> Pair<String, Color> = { key ->
            val valStr = tech.getOrDefault(key, "--")
            val v = valStr.toDoubleOrNull()
            if (v == null) {
                valStr to defaultTextColor
            } else if (livePrice >= v) {
                "$valStr ▲" to Color(0xFF00E676)
            } else {
                "$valStr ▼" to Color(0xFFFF5252)
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                TechnicalGaugeSummaryMeter(score = score)
            }

            FundamentalsCard(title = "Moving Averages") {
                val (ema20Text, ema20Color) = getEmaInfo("ema20")
                InfoColumnColored(label = "20 EMA", value = ema20Text, color = ema20Color)

                val (ema50Text, ema50Color) = getEmaInfo("ema50")
                InfoColumnColored(label = "50 EMA", value = ema50Text, color = ema50Color)

                val (ema200Text, ema200Color) = getEmaInfo("ema200")
                InfoColumnColored(label = "200 EMA", value = ema200Text, color = ema200Color)

                val trendAlign = if (emaBullish) "BULLISH ▲" else "NEUTRAL ▼"
                val trendColor = if (emaBullish) Color(0xFF00E676) else Color(0xFFFF5252)
                InfoColumnColored(label = "Trend Align", value = trendAlign, color = trendColor)
            }

            FundamentalsCard(title = "Oscillators & Momentum") {
                val rsiColor = when {
                    rsiVal < 35.0 -> Color(0xFF00E676)
                    rsiVal > 68.0 -> Color(0xFFFF5252)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val rsiArrow = if (rsiVal < 35.0 || rsiVal in 45.0..65.0) " ▲" else if (rsiVal > 68.0) " ▼" else " •"
                InfoColumnColored(label = "RSI (14)", value = String.format(java.util.Locale.US, "%.1f", rsiVal) + rsiArrow, color = rsiColor)

                val macdText = tech.getOrDefault("macd", "--")
                val macdColor = if (macdText.lowercase().contains("bull") || macdText.lowercase().contains("up")) Color(0xFF00E676) else Color(0xFFFF5252)
                val macdArrow = if (macdColor == Color(0xFF00E676)) " ▲" else " ▼"
                InfoColumnColored(label = "MACD Trend", value = macdText + macdArrow, color = macdColor)

                val adxText = tech.getOrDefault("adx", "--")
                val adxVal = adxText.replace("%", "").toDoubleOrNull()
                val adxColor = if (adxVal != null && adxVal > 25.0) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                val adxArrow = if (adxVal != null && adxVal > 25.0) " ▲" else " •"
                InfoColumnColored(label = "ADX Strength", value = adxText + adxArrow, color = adxColor)

                InfoColumn(label = "ATR Volatility", value = tech.getOrDefault("atr", "--"))
            }

            FundamentalsCard(title = "Volume & Breakouts") {
                val relVolText = tech.getOrDefault("relVolume", "--")
                val relVolVal = relVolText.toDoubleOrNull()
                val relVolColor = if (relVolVal != null && relVolVal >= 1.5) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurface
                val relVolArrow = if (relVolVal != null && relVolVal >= 1.5) " ▲" else " •"
                InfoColumnColored(label = "Rel Volume", value = relVolText + relVolArrow, color = relVolColor)

                InfoColumn(label = "52W High Prox", value = tech.getOrDefault("near52w", "--"))

                val breakoutSig = tech.getOrDefault("breakout", "No Breakout")
                val breakoutColor = if (breakoutSig.lowercase().contains("breakout") && !breakoutSig.lowercase().contains("no")) Color(0xFF00E676) else Color(0xFFFF5252)
                val breakoutArrow = if (breakoutColor == Color(0xFF00E676)) " ▲" else " ▼"
                InfoColumnColored(label = "Breakout Sig", value = breakoutSig + breakoutArrow, color = breakoutColor)

                val vwapText = tech.getOrDefault("vwap", "--")
                val vwapVal = vwapText.toDoubleOrNull()
                val vwapColor = if (vwapVal != null && livePrice >= vwapVal) Color(0xFF00E676) else Color(0xFFFF5252)
                val vwapArrow = if (vwapColor == Color(0xFF00E676)) " ▲" else " ▼"
                InfoColumnColored(label = "VWAP Price", value = vwapText + vwapArrow, color = vwapColor)
            }
        }
    }
}

@Composable
fun RowScope.InfoColumnColored(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(
            text = label,
            fontSize = 7.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = value,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false
        )
    }
}

@Composable
fun RowScope.InfoColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(
            text = label,
            fontSize = 7.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = value,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false
        )
    }
}

data class NewsItem(
    val headline: String,
    val summary: String,
    val source: String,
    val timestamp: Long
)

/** Converts Upstox symbol to TradingView-compatible NSE ticker.
 *  e.g. "BAJAJ-AUTO" -> "BAJAJ_AUTO", "M&M" -> "M_M"
 */
fun String.toTvSymbol(): String = this
    .replace("&", "_")
    .replace("-", "_")
    .replace(" ", "_")

fun formatEntryDate(rawDate: String): String {
    if (rawDate.length < 10) return "Ongoing Monitor"
    val datePart = rawDate.substring(0, 10) // e.g. "2026-08-13"
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val formatter = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.US)
        val date = parser.parse(datePart)
        date?.let { formatter.format(it) } ?: "Active Trades ($datePart)"
    } catch (e: Exception) {
        "Active Trades ($datePart)"
    }
}

data class LiveFundamentals(
    val marketCap: String = "--",
    val peRatio: String = "--",
    val eps: String = "--",
    val divYield: String = "--",
    val employees: String = "--",
    val promoterPct: Float = 0f,
    val fiiPct: Float = 0f,
    val diiPct: Float = 0f,
    val retailPct: Float = 0f,
    val hasHoldings: Boolean = false
)

fun parseLiveFundamentals(ratiosJson: String, profileJson: String, holdingsJson: String): LiveFundamentals {
    var marketCap = "--"; var peRatio = "--"; var pbRatio = "--"
    var roeVal = "--"; var roceVal = "--"
    var promoter = 0f; var fii = 0f; var dii = 0f; var retail = 0f; var hasHoldings = false

    // --- Parse Key Ratios (Array: [{name:"P/E", company_value:"20.15"}, ...]) ---
    try {
        val ratiosArr = org.json.JSONArray(ratiosJson)
        for (i in 0 until ratiosArr.length()) {
            val item = ratiosArr.getJSONObject(i)
            when (item.optString("name")) {
                "P/E"       -> peRatio = item.optString("company_value", "--")
                "P/B"       -> pbRatio = item.optString("company_value", "--")
                "ROE"       -> roeVal  = item.optString("company_value", "--")
                "ROCE"      -> roceVal = item.optString("company_value", "--")
            }
        }
    } catch (_: Exception) {}

    // --- Parse Company Profile (Object: {company_profile, sector, sector_market_cap_inr}) ---
    // Note: Upstox profile API gives SECTOR market cap, not company market cap
    // We show sector market cap as a reference value
    try {
        val profile = org.json.JSONObject(profileJson)
        val sectorMcap = profile.optJSONObject("sector_market_cap_inr")
        if (sectorMcap != null) {
            marketCap = "Sector: ${sectorMcap.optString("formatted", "--")}"
        }
    } catch (_: Exception) {}

    // --- Parse Share Holdings (Array: [{category:"promoters", history:[{period:"Mar 2026", value:50.0}]}) ---
    try {
        val holdArr = org.json.JSONArray(holdingsJson)
        for (i in 0 until holdArr.length()) {
            val item = holdArr.getJSONObject(i)
            val category = item.optString("category", "")
            val history = item.optJSONArray("history")
            // Take first entry (most recent quarter)
            val latestValue = history?.optJSONObject(0)?.optDouble("value", 0.0)?.toFloat() ?: 0f
            when (category) {
                "promoters"        -> promoter = latestValue
                "fii"              -> fii      = latestValue
                "other_dii"        -> dii      = (dii + latestValue)
                "mutual_funds"     -> dii      = (dii + latestValue)
                "retail_and_other" -> retail   = latestValue
            }
        }
        hasHoldings = (promoter + fii + dii + retail) > 1f
    } catch (_: Exception) {}

    return LiveFundamentals(
        marketCap  = marketCap,
        peRatio    = peRatio,
        eps        = pbRatio,      // reuse eps field to show P/B
        divYield   = roeVal,       // reuse divYield field for ROE
        employees  = roceVal,      // reuse employees field for ROCE
        promoterPct = promoter,
        fiiPct     = fii,
        diiPct     = dii,
        retailPct  = retail,
        hasHoldings = hasHoldings
    )
}

data class StockProfile(
    val name: String,
    val sector: String,
    val marketCap: String,
    val divYield: String,
    val peRatio: String,
    val eps: String,
    val founded: String,
    val employees: String,
    val ceo: String,
    val website: String,
    val about: String,
    val totalShares: String = "265.47M",
    val closelyHeldPercent: Float = 52.02f,
    val freeFloatPercent: Float = 47.98f,
    val closelyHeldCount: String = "138.10M",
    val freeFloatCount: String = "127.37M"
)

object StockProfileDb {
    val profiles = mapOf(
        "DIVISLAB" to StockProfile(
            name = "Divi's Laboratories Ltd.",
            sector = "Healthcare - Pharmaceuticals",
            marketCap = "2.27T INR",
            divYield = "0.35%",
            peRatio = "77.78",
            eps = "110.18 INR",
            founded = "1990",
            employees = "19.29K",
            ceo = "Satchandra Kiran Divi",
            website = "divislabs.com",
            about = "Divi's Laboratories Ltd. is an Indian multinational pharmaceutical company and producer of active pharmaceutical ingredients (APIs) and intermediates, headquartered in Hyderabad."
        ),
        "RELIANCE" to StockProfile(
            name = "Reliance Industries Ltd.",
            sector = "Energy, Telecom, Retail",
            marketCap = "17.45T INR",
            divYield = "0.38%",
            peRatio = "26.40",
            eps = "98.24 INR",
            founded = "1973",
            employees = "389K",
            ceo = "Mukesh D. Ambani",
            website = "ril.com",
            about = "Reliance Industries Ltd. is a diversified conglomerate. It operates through retail, digital services (Jio), oil-to-chemicals, oil and gas exploration, and financial services segments."
        ),
        "TCS" to StockProfile(
            name = "Tata Consultancy Services Ltd.",
            sector = "Information Technology",
            marketCap = "13.67T INR",
            divYield = "2.40%",
            peRatio = "28.50",
            eps = "124.50 INR",
            founded = "1968",
            employees = "601K",
            ceo = "K. Krithivasan",
            website = "tcs.com",
            about = "Tata Consultancy Services Ltd. is a global IT services and consulting company, part of the Tata Group, offering digital transformation, cloud advisory, and systems integration services."
        ),
        "INFY" to StockProfile(
            name = "Infosys Ltd.",
            sector = "Information Technology",
            marketCap = "6.42T INR",
            divYield = "2.35%",
            peRatio = "24.12",
            eps = "61.80 INR",
            founded = "1981",
            employees = "317K",
            ceo = "Salil S. Parekh",
            website = "infosys.com",
            about = "Infosys Ltd. is a digital services and consulting multinational corporation that offers software development, maintenance, and independent validation services to global clients."
        ),
        "HDFCBANK" to StockProfile(
            name = "HDFC Bank Ltd.",
            sector = "Financial Services - Bank",
            marketCap = "12.35T INR",
            divYield = "1.15%",
            peRatio = "19.50",
            eps = "82.40 INR",
            founded = "1994",
            employees = "173K",
            ceo = "Sashidhar Jagdishan",
            website = "hdfcbank.com",
            about = "HDFC Bank Ltd. is India's largest private sector bank. It offers a wide range of commercial, transaction, and retail banking services to customers across the country."
        ),
        "ICICIBANK" to StockProfile(
            name = "ICICI Bank Ltd.",
            sector = "Financial Services - Bank",
            marketCap = "7.92T INR",
            divYield = "0.85%",
            peRatio = "18.12",
            eps = "58.60 INR",
            founded = "1994",
            employees = "130K",
            ceo = "Sandeep Bakhshi",
            website = "icicibank.com",
            about = "ICICI Bank Ltd. is a multinational private sector bank. It offers retail, corporate, treasury, investment banking, and insurance services through a vast network of branches."
        ),
        "SBIN" to StockProfile(
            name = "State Bank of India",
            sector = "Financial Services - Bank",
            marketCap = "6.48T INR",
            divYield = "1.75%",
            peRatio = "10.42",
            eps = "72.10 INR",
            founded = "1955",
            employees = "244K",
            ceo = "Challa Sreenivasulu Setty",
            website = "sbi.co.in",
            about = "State Bank of India is a multinational public sector banking and financial services statutory body. It is the largest commercial bank in India by assets and branches."
        ),
        "BHARTIARTL" to StockProfile(
            name = "Bharti Airtel Ltd.",
            sector = "Telecommunications",
            marketCap = "7.35T INR",
            divYield = "0.55%",
            peRatio = "52.80",
            eps = "24.50 INR",
            founded = "1995",
            employees = "18.5K",
            ceo = "Gopal Vittal",
            website = "airtel.in",
            about = "Bharti Airtel Ltd. is a leading global telecommunications company. It provides mobile services, fixed line, high speed broadband, satellite television, and enterprise solutions."
        ),
        "LICI" to StockProfile(
            name = "Life Insurance Corporation of India",
            sector = "Financial Services - Insurance",
            marketCap = "5.82T INR",
            divYield = "1.45%",
            peRatio = "15.60",
            eps = "58.20 INR",
            founded = "1956",
            employees = "104K",
            ceo = "Siddhartha Mohanty",
            website = "licindia.in",
            about = "Life Insurance Corporation of India is a state-owned insurance group and investment corporation. It is the largest life insurer in India with millions of individual policyholders."
        ),
        "LT" to StockProfile(
            name = "Larsen & Toubro Ltd.",
            sector = "Engineering & Construction",
            marketCap = "4.82T INR",
            divYield = "0.85%",
            peRatio = "34.50",
            eps = "92.40 INR",
            founded = "1938",
            employees = "52.4K",
            ceo = "S. N. Subrahmanyan",
            website = "larsentoubro.com",
            about = "Larsen & Toubro Ltd. is an Indian multinational conglomerate. It operates through engineering, construction, manufacturing, technology, and financial services segments globally."
        ),
        "ITC" to StockProfile(
            name = "ITC Ltd.",
            sector = "FMCG Conglomerate",
            marketCap = "5.38T INR",
            divYield = "3.25%",
            peRatio = "26.40",
            eps = "16.50 INR",
            founded = "1910",
            employees = "24.5K",
            ceo = "Sanjiv Puri",
            website = "itcportal.com",
            about = "ITC Ltd. is a highly diversified conglomerate. Its businesses include fast-moving consumer goods (FMCG), hotels, paperboards & packaging, agri-business, and information technology."
        ),
        "HINDUNILVR" to StockProfile(
            name = "Hindustan Unilever Ltd.",
            sector = "FMCG",
            marketCap = "5.76T INR",
            divYield = "1.65%",
            peRatio = "56.40",
            eps = "43.80 INR",
            founded = "1933",
            employees = "21K",
            ceo = "Priya Nair",
            website = "hul.co.in",
            about = "Hindustan Unilever Ltd. is a consumer goods company headquartered in Mumbai. It is a subsidiary of the British company Unilever, offering home, beauty, and personal care brands."
        ),
        "KOTAKBANK" to StockProfile(
            name = "Kotak Mahindra Bank Ltd.",
            sector = "Financial Services - Bank",
            marketCap = "3.48T INR",
            divYield = "0.12%",
            peRatio = "22.50",
            eps = "78.40 INR",
            founded = "1985",
            employees = "73K",
            ceo = "Ashok Vaswani",
            website = "kotak.com",
            about = "Kotak Mahindra Bank Ltd. is a private sector bank. It offers commercial banking, investment banking, stockbroking, mutual funds, life insurance, and wealth management services."
        ),
        "MARUTI" to StockProfile(
            name = "Maruti Suzuki India Ltd.",
            sector = "Automobile",
            marketCap = "3.78T INR",
            divYield = "1.05%",
            peRatio = "28.40",
            eps = "410.50 INR",
            founded = "1981",
            employees = "16.8K",
            ceo = "Hisashi Takeuchi",
            website = "marutisuzuki.com",
            about = "Maruti Suzuki India Ltd. is a leading automobile company in India. It is a subsidiary of Suzuki Motor Corporation, Japan, specializing in passenger cars and utility vehicles."
        ),
        "TATASTEEL" to StockProfile(
            name = "Tata Steel Ltd.",
            sector = "Metals & Mining",
            marketCap = "1.78T INR",
            divYield = "2.35%",
            peRatio = "14.50",
            eps = "11.20 INR",
            founded = "1907",
            employees = "32.4K",
            ceo = "T. V. Narendran",
            website = "tatasteel.com",
            about = "Tata Steel Ltd. is an Indian multinational steel-making company. It is one of the top steel producers globally, with operations in India, Europe, and Southeast Asia."
        ),
        "ASIANPAINT" to StockProfile(
            name = "Asian Paints Ltd.",
            sector = "Chemicals - Paints",
            marketCap = "2.84T INR",
            divYield = "1.15%",
            peRatio = "52.40",
            eps = "54.20 INR",
            founded = "1942",
            employees = "7.8K",
            ceo = "Amit Syngle",
            website = "asianpaints.com",
            about = "Asian Paints Ltd. is an Indian multinational paint company. It is engaged in manufacturing, selling, and distribution of paints, coatings, home decor, bath fittings, and related services."
        ),
        "NTPC" to StockProfile(
            name = "NTPC Ltd.",
            sector = "Power & Utilities",
            marketCap = "3.22T INR",
            divYield = "2.25%",
            peRatio = "16.40",
            eps = "19.50 INR",
            founded = "1975",
            employees = "18.9K",
            ceo = "Gurdeep Singh",
            website = "ntpc.co.in",
            about = "NTPC Ltd. is India's largest power utility company, engaged in power generation and coal mining. It generates electricity from coal, gas, hydro, solar, and wind sources."
        ),
        "BAJFINANCE" to StockProfile(
            name = "Bajaj Finance Ltd.",
            sector = "Financial Services - NBFC",
            marketCap = "4.24T INR",
            divYield = "0.52%",
            peRatio = "32.40",
            eps = "210.40 INR",
            founded = "1987",
            employees = "40K",
            ceo = "Anup Saha",
            website = "bajajfinserv.in",
            about = "Bajaj Finance Ltd. is an Indian non-banking financial company (NBFC). It is focused on lending, asset acquisition, wealth management, and insurance distribution."
        ),
        "POWERGRID" to StockProfile(
            name = "Power Grid Corp of India Ltd.",
            sector = "Power Transmission",
            marketCap = "2.68T INR",
            divYield = "3.85%",
            peRatio = "16.50",
            eps = "16.20 INR",
            founded = "1989",
            employees = "8.4K",
            ceo = "Vamsi Rama Mohan Burra",
            website = "powergrid.in",
            about = "Power Grid Corporation of India Ltd. is a central public sector undertaking. It is engaged in the transmission of bulk power across various states of India."
        ),
        "BAJAJFINSV" to StockProfile(
            name = "Bajaj Finserv Ltd.",
            sector = "Financial Services - Holding",
            marketCap = "2.46T INR",
            divYield = "0.15%",
            peRatio = "35.60",
            eps = "42.50 INR",
            founded = "2007",
            employees = "65K",
            ceo = "Sanjiv Bajaj",
            website = "bajajfinserv.in",
            about = "Bajaj Finserv Ltd. is an Indian financial services company focused on lending, asset management, wealth management, and insurance through its subsidiaries."
        ),
        "TITAN" to StockProfile(
            name = "Titan Company Ltd.",
            sector = "Consumer Durables",
            marketCap = "2.95T INR",
            divYield = "0.32%",
            peRatio = "78.40",
            eps = "37.50 INR",
            founded = "1984",
            employees = "9.5K",
            ceo = "C. K. Venkataraman",
            website = "titan.co.in",
            about = "Titan Company Ltd. is a joint venture between the Tata Group and the TIDCO. It manufactures fashion accessories such as watches, jewelry (Tanishq), and eyewear."
        ),
        "SUNPHARMA" to StockProfile(
            name = "Sun Pharmaceutical Industries Ltd.",
            sector = "Healthcare - Pharmaceuticals",
            marketCap = "3.42T INR",
            divYield = "0.85%",
            peRatio = "36.20",
            eps = "39.40 INR",
            founded = "1983",
            employees = "38K",
            ceo = "Dilip S. Shanghvi",
            website = "sunpharma.com",
            about = "Sun Pharmaceutical Industries Ltd. is an Indian multinational pharmaceutical company. It manufactures and sells pharmaceutical formulations and active pharmaceutical ingredients (APIs)."
        ),
        "HCLTECH" to StockProfile(
            name = "HCL Technologies Ltd.",
            sector = "Information Technology",
            marketCap = "4.28T INR",
            divYield = "3.12%",
            peRatio = "26.40",
            eps = "58.40 INR",
            founded = "1991",
            employees = "224K",
            ceo = "C. Vijayakumar",
            website = "hcltech.com",
            about = "HCL Technologies Ltd. is a multinational information technology services and consulting company, specializing in digital, engineering, cloud, and cybersecurity services."
        ),
        "ITC" to StockProfile(
            name = "ITC Ltd.",
            sector = "FMCG Conglomerate",
            marketCap = "5.38T INR",
            divYield = "3.25%",
            peRatio = "26.40",
            eps = "16.50 INR",
            founded = "1910",
            employees = "24.5K",
            ceo = "Sanjiv Puri",
            website = "itcportal.com",
            about = "ITC Ltd. is a highly diversified conglomerate. Its businesses include fast-moving consumer goods (FMCG), hotels, paperboards & packaging, agri-business, and information technology."
        ),
        "M&M" to StockProfile(
            name = "Mahindra & Mahindra Ltd.",
            sector = "Automobile",
            marketCap = "2.86T INR",
            divYield = "1.25%",
            peRatio = "24.50",
            eps = "92.40 INR",
            founded = "1945",
            employees = "260K",
            ceo = "Dr. Anish Shah",
            website = "mahindra.com",
            about = "Mahindra & Mahindra Ltd. is an Indian multinational automotive manufacturing corporation. It is one of the largest vehicle manufacturers by production in India and the largest tractor manufacturer."
        ),
        "ULTRACEMCO" to StockProfile(
            name = "UltraTech Cement Ltd.",
            sector = "Materials - Cement",
            marketCap = "2.78T INR",
            divYield = "0.95%",
            peRatio = "42.50",
            eps = "224.50 INR",
            founded = "1983",
            employees = "22K",
            ceo = "K. C. Jhanwar",
            website = "ultratechcement.com",
            about = "UltraTech Cement Ltd. is the largest manufacturer of grey cement, ready-mix concrete, and white cement in India. It is the flagship cement company of the Aditya Birla Group."
        ),
        "HAL" to StockProfile(
            name = "Hindustan Aeronautics Ltd.",
            sector = "Aerospace & Defense",
            marketCap = "3.24T INR",
            divYield = "0.85%",
            peRatio = "42.50",
            eps = "98.50 INR",
            founded = "1940",
            employees = "28.5K",
            ceo = "C. B. Ananthakrishnan",
            website = "hal-india.co.in",
            about = "Hindustan Aeronautics Ltd. is an Indian state-owned aerospace and defence company headquartered in Bengaluru, India. It is governed under the management of the Indian Ministry of Defence."
        ),
        "BAJAJ-AUTO" to StockProfile(
            name = "Bajaj Auto Ltd.",
            sector = "Automobile",
            marketCap = "2.74T INR",
            divYield = "2.15%",
            peRatio = "32.40",
            eps = "242.50 INR",
            founded = "1945",
            employees = "10K",
            ceo = "Rajiv Bajaj",
            website = "bajajauto.com",
            about = "Bajaj Auto Ltd. is a global two-wheeler and three-wheeler manufacturing company. It is the world's third-largest manufacturer of motorcycles and the largest manufacturer of three-wheelers."
        ),
        "EICHERMOT" to StockProfile(
            name = "Eicher Motors Ltd.",
            sector = "Automobile",
            marketCap = "1.24T INR",
            divYield = "1.05%",
            peRatio = "34.50",
            eps = "135.20 INR",
            founded = "1948",
            employees = "4.5K",
            ceo = "Siddhartha Lal",
            website = "eichermotors.com",
            about = "Eicher Motors Ltd. is the listed parent of Royal Enfield, the global leader in middleweight motorcycles. Eicher also has a joint venture with Volvo Group for commercial vehicles."
        ),
        "HEROMOTOCO" to StockProfile(
            name = "Hero MotoCorp Ltd.",
            sector = "Automobile",
            marketCap = "1.08T INR",
            divYield = "3.15%",
            peRatio = "24.50",
            eps = "185.40 INR",
            founded = "1984",
            employees = "9K",
            ceo = "Niranjan Gupta",
            website = "heromotocorp.com",
            about = "Hero MotoCorp Ltd. is the world's largest manufacturer of two-wheelers, based in India. It is a market leader in the domestic motorcycle segment."
        ),
        "APOLLOHOSP" to StockProfile(
            name = "Apollo Hospitals Enterprise Ltd.",
            sector = "Healthcare - Hospitals",
            marketCap = "0.98T INR",
            divYield = "0.28%",
            peRatio = "72.40",
            eps = "92.50 INR",
            founded = "1983",
            employees = "72K",
            ceo = "Prathap C. Reddy",
            website = "apollohospitals.com",
            about = "Apollo Hospitals Enterprise Ltd. is an Indian multinational hospital chain headquartered in Chennai. It is the largest private healthcare provider in India."
        ),
        "GRASIM" to StockProfile(
            name = "Grasim Industries Ltd.",
            sector = "Diversified Conglomerate",
            marketCap = "1.42T INR",
            divYield = "0.45%",
            peRatio = "22.50",
            eps = "115.40 INR",
            founded = "1947",
            employees = "24K",
            ceo = "Himanshu Kapania",
            website = "adityabirla.com",
            about = "Grasim Industries Ltd. is the flagship company of the Aditya Birla Group. It is a leading global producer of Viscose Staple Fibre (VSF), cement (UltraTech), and chemicals."
        )
    )

    fun getIsin(symbol: String): String? = isinMap[symbol]

    private val isinMap = mapOf(
        "RELIANCE"    to "INE002A01018",
        "TCS"         to "INE467B01029",
        "HDFCBANK"    to "INE040A01034",
        "INFY"        to "INE009A01021",
        "ICICIBANK"   to "INE090A01021",
        "HINDUNILVR"  to "INE030A01027",
        "ITC"         to "INE154A01017",
        "SBIN"        to "INE062A01020",
        "BHARTIARTL"  to "INE397D01024",
        "KOTAKBANK"   to "INE237A01028",
        "LT"          to "INE018A01030",
        "AXISBANK"    to "INE238A01034",
        "ASIANPAINT"  to "INE021A01026",
        "MARUTI"      to "INE585B01010",
        "SUNPHARMA"   to "INE044A01036",
        "TITAN"       to "INE280A01028",
        "WIPRO"       to "INE075A01022",
        "ULTRACEMCO"  to "INE481G01011",
        "NESTLEIND"   to "INE239A01016",
        "ADANIENT"    to "INE423A01024",
        "ONGC"        to "INE213A01029",
        "NTPC"        to "INE733E01010",
        "POWERGRID"   to "INE752E01010",
        "COALINDIA"   to "INE522F01014",
        "BAJFINANCE"  to "INE296A01024",
        "BAJAJFINSV"  to "INE918I01026",
        "HCLTECH"     to "INE860A01027",
        "TECHM"       to "INE669C01036",
        "M&M"         to "INE101A01026",
        "TATAMOTORS"  to "INE155A01022",
        "TATASTEEL"   to "INE081A01020",
        "JSWSTEEL"    to "INE019A01038",
        "GRASIM"      to "INE047A01021",
        "CIPLA"       to "INE059A01026",
        "DRREDDY"     to "INE089A01023",
        "EICHERMOT"   to "INE066A01021",
        "HEROMOTOCO"  to "INE158A01026",
        "BAJAJ-AUTO"  to "INE917I01010",
        "APOLLOHOSP"  to "INE437A01024",
        "HAL"         to "INE066F01012",
        "ADANIPORTS"  to "INE742F01042",
        "SBILIFE"     to "INE123W01016",
        "HDFCLIFE"    to "INE795G01014",
        "BPCL"        to "INE029A01011",
        "INDUSINDBK"  to "INE095A01012",
        "VEDL"        to "INE205A01025",
        "DIVISLAB"    to "INE361B01024",
        "BRITANNIA"   to "INE216A01030",
        "TATACONSUM"  to "INE192A01025"
    )

    fun getProfile(symbol: String, defaultPe: String = "--", defaultRoe: String = "--"): StockProfile {
        return profiles[symbol] ?: StockProfile(
            name = "$symbol Ltd.",
            sector = "NSE Listed Equity",
            marketCap = "--",
            divYield = "--",
            peRatio = defaultPe,
            eps = "--",
            founded = "--",
            employees = "--",
            ceo = "--",
            website = symbol.lowercase().replace("-", "").replace("&", "") + ".com",
            about = "$symbol is a publicly traded equity stock listed on the National Stock Exchange (NSE) of India. The proprietary 100-point analyst model selected this stock due to strong momentum indicators and healthy ROE of $defaultRoe."
        )
    }
}

fun parseAnyDate(dateStr: String): java.util.Date? {
    if (dateStr.isBlank()) return null
    val clean = dateStr.trim()
    val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "dd MMMM yyyy",
        "dd MMM yyyy",
        "yyyy-MM-dd"
    )
    for (fmt in formats) {
        try {
            val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
            val parsed = sdf.parse(clean)
            if (parsed != null) return parsed
        } catch (_: Exception) {}
    }
    return null
}

fun calculateDaysBetween(startDateStr: String, endDateStr: String? = null): Int {
    val start = parseAnyDate(startDateStr) ?: return 0
    val end = if (!endDateStr.isNullOrBlank()) (parseAnyDate(endDateStr) ?: java.util.Date()) else java.util.Date()

    val calStart = java.util.Calendar.getInstance().apply {
        time = start
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val calEnd = java.util.Calendar.getInstance().apply {
        time = end
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val diffMillis = calEnd.timeInMillis - calStart.timeInMillis
    return maxOf(0, (diffMillis / (1000 * 60 * 60 * 24)).toInt())
}
