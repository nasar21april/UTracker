import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

new_global_row = """@Composable
fun GlobalTradeButtonsRow(
    optionsChain: Map<String, com.example.upstoxmarketdataapp.data.StrikeRowState>,
    indexFutures: Map<String, List<com.example.upstoxmarketdataapp.data.FutureData>>,
    index15sCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    upstoxService: com.example.upstoxmarketdataapp.data.UpstoxService,
    virtualTradeManager: com.example.upstoxmarketdataapp.simulator.VirtualTradeManager
) {
    var globalMaxFuturesSum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.0) }
    var globalMinFuturesSum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.0) }
    var isAutoAlgoEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val supertrendCalculator = androidx.compose.runtime.remember { com.example.upstoxmarketdataapp.simulator.SupertrendCalculator(10, 3.0) }

    val indices = listOf("NIFTY", "BANKNIFTY", "SENSEX")

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

        val candles15s = index15sCandles[sym] ?: emptyList()
        val supertrendResult = supertrendCalculator.calculate(candles15s)
        val isSupertrendBullish = supertrendResult?.state == com.example.upstoxmarketdataapp.simulator.SupertrendState.BULLISH
        val isSupertrendBearish = supertrendResult?.state == com.example.upstoxmarketdataapp.simulator.SupertrendState.BEARISH

        val isBuyCe = allFuturesGreen && buyCallDots >= 3 && buyPutDots == 0 && ceDiffPositiveCount >= threshold80Percent && isSupertrendBullish
        val isBuyPe = allFuturesRed && buyPutDots >= 3 && buyCallDots == 0 && peDiffPositiveCount >= threshold80Percent && isSupertrendBearish

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

    val lotSizes = mapOf("NIFTY" to 25, "BANKNIFTY" to 15, "SENSEX" to 10)
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val manualBuyCE = {
        indices.forEach { sym ->
            val base = upstoxService.fixedBaseStrike[sym]
            if (base != null) {
                val atm = base.toInt().toString()
                val row = optionsChain["${sym}_ATM"]
                val ltp = row?.ceData?.ltp ?: 0.0
                val token = row?.ceData?.instrumentKey ?: ""
                if (ltp > 0) {
                    if (upstoxService.tradingMode.value == "real") {
                        coroutineScope.launch { upstoxService.placeRealOrder(token, "BUY", lotSizes[sym] ?: 1, upstoxService.productType.value) }
                    } else virtualTradeManager.executeBuy(sym, "CE", atm, ltp, lotSizes[sym] ?: 1)
                }
            }
        }
    }
    
    val manualExitCE = {
        indices.forEach { sym ->
            val base = upstoxService.fixedBaseStrike[sym]
            if (base != null) {
                val atm = base.toInt().toString()
                val row = optionsChain["${sym}_ATM"]
                val ltp = row?.ceData?.ltp ?: 0.0
                val token = row?.ceData?.instrumentKey ?: ""
                if (ltp > 0) {
                    if (upstoxService.tradingMode.value == "real") {
                        coroutineScope.launch { upstoxService.placeRealOrder(token, "SELL", lotSizes[sym] ?: 1, upstoxService.productType.value) }
                    } else virtualTradeManager.executeSell(sym, "CE", ltp)
                }
            }
        }
    }

    val manualBuyPE = {
        indices.forEach { sym ->
            val base = upstoxService.fixedBaseStrike[sym]
            if (base != null) {
                val atm = base.toInt().toString()
                val row = optionsChain["${sym}_ATM"]
                val ltp = row?.peData?.ltp ?: 0.0
                val token = row?.peData?.instrumentKey ?: ""
                if (ltp > 0) {
                    if (upstoxService.tradingMode.value == "real") {
                        coroutineScope.launch { upstoxService.placeRealOrder(token, "BUY", lotSizes[sym] ?: 1, upstoxService.productType.value) }
                    } else virtualTradeManager.executeBuy(sym, "PE", atm, ltp, lotSizes[sym] ?: 1)
                }
            }
        }
    }
    
    val manualExitPE = {
        indices.forEach { sym ->
            val base = upstoxService.fixedBaseStrike[sym]
            if (base != null) {
                val atm = base.toInt().toString()
                val row = optionsChain["${sym}_ATM"]
                val ltp = row?.peData?.ltp ?: 0.0
                val token = row?.peData?.instrumentKey ?: ""
                if (ltp > 0) {
                    if (upstoxService.tradingMode.value == "real") {
                        coroutineScope.launch { upstoxService.placeRealOrder(token, "SELL", lotSizes[sym] ?: 1, upstoxService.productType.value) }
                    } else virtualTradeManager.executeSell(sym, "PE", ltp)
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(allBuyCe) { if (allBuyCe && isAutoAlgoEnabled) manualBuyCE() }
    androidx.compose.runtime.LaunchedEffect(exitCe) { if (exitCe && isAutoAlgoEnabled) manualExitCE() }
    androidx.compose.runtime.LaunchedEffect(allBuyPe) { if (allBuyPe && isAutoAlgoEnabled) manualBuyPE() }
    androidx.compose.runtime.LaunchedEffect(exitPe) { if (exitPe && isAutoAlgoEnabled) manualExitPE() }

    val tradingMode by upstoxService.tradingMode.collectAsState()
    val dailyPnl = virtualTradeManager.getDailyPnl()
    val pnlColor = if (dailyPnl >= 0) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFF5252)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        
        // Row 1: Toggles and PNL
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(4.dp))
                        .border(1.dp, androidx.compose.ui.graphics.Color.DarkGray, RoundedCornerShape(4.dp))
                        .clickable { upstoxService.saveTradingMode(if (tradingMode == "real") "virtual" else "real") }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (tradingMode == "real") "REAL" else "PaperTrade",
                        color = androidx.compose.ui.graphics.Color.LightGray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    modifier = Modifier.clickable { isAutoAlgoEnabled = !isAutoAlgoEnabled }.padding(4.dp)
                ) {
                    Text("Auto:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isAutoAlgoEnabled) "ON" else "OFF", 
                        color = if (isAutoAlgoEnabled) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color.Gray, 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("P&L: \u20B9${String.format(java.util.Locale.US, "%.2f", dailyPnl)}", color = pnlColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { virtualTradeManager.clearHistory() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Ledger", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Row 2: Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { manualBuyCE() },
                colors = ButtonDefaults.buttonColors(containerColor = if (allBuyCe) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFF2E2E2E)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("Algo BUY CE", color = if (allBuyCe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1) }

            Button(
                onClick = { manualExitCE() },
                colors = ButtonDefaults.buttonColors(containerColor = if (exitCe) androidx.compose.ui.graphics.Color(0xFFFFB300) else androidx.compose.ui.graphics.Color(0xFF2E2E2E)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("Algo EXIT CE", color = if (exitCe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1) }

            Button(
                onClick = { manualBuyPE() },
                colors = ButtonDefaults.buttonColors(containerColor = if (allBuyPe) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFF2E2E2E)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("Algo BUY PE", color = if (allBuyPe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1) }

            Button(
                onClick = { manualExitPE() },
                colors = ButtonDefaults.buttonColors(containerColor = if (exitPe) androidx.compose.ui.graphics.Color(0xFFFFB300) else androidx.compose.ui.graphics.Color(0xFF2E2E2E)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("Algo EXIT PE", color = if (exitPe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1) }
        }
    }
}
"""

index = content.find("@Composable\nfun GlobalTradeButtonsRow")
if index != -1:
    content = content[:index] + new_global_row
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Replaced FeedScreen.kt with refined GlobalTradeButtonsRow!")
else:
    print("Could not find GlobalTradeButtonsRow to replace.")
