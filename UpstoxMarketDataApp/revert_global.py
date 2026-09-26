import os
import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

original_global_row = """@Composable
fun GlobalTradeButtonsRow(
    optionsChain: Map<String, com.example.upstoxmarketdataapp.data.StrikeRowState>,
    indexFutures: Map<String, List<com.example.upstoxmarketdataapp.data.FutureData>>,
    index15sCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    upstoxService: com.example.upstoxmarketdataapp.data.UpstoxService,
    virtualTradeManager: com.example.upstoxmarketdataapp.simulator.VirtualTradeManager
) {
    var globalMaxFuturesSum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.0) }
    var globalMinFuturesSum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.0) }

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

    androidx.compose.runtime.LaunchedEffect(allBuyCe) {
        if (allBuyCe) {
            indices.forEach { sym ->
                val base = upstoxService.fixedBaseStrike[sym]
                if (base != null) {
                    val itm1 = (base - 100.0).toInt().toString() // Simple ITM+1 approx
                    val row = optionsChain["${sym}_$itm1"]
                    if (row != null && (row.ceData?.ltp ?: 0.0) > 0) {
                        virtualTradeManager.executeBuy(sym, "CE", itm1, row.ceData?.ltp ?: 0.0, lotSizes[sym] ?: 1)
                    }
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(exitCe) {
        if (exitCe) {
            indices.forEach { sym ->
                val base = upstoxService.fixedBaseStrike[sym]
                if (base != null) {
                    val itm1 = (base - 100.0).toInt().toString()
                    val row = optionsChain["${sym}_$itm1"]
                    if (row != null && (row.ceData?.ltp ?: 0.0) > 0) {
                        virtualTradeManager.executeSell(sym, "CE", row.ceData?.ltp ?: 0.0)
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = if (allBuyCe) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color.DarkGray),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 1.dp).height(20.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("BUY CE", color = if (allBuyCe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp) }

            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = if (exitCe) androidx.compose.ui.graphics.Color(0xFFFFB300) else androidx.compose.ui.graphics.Color.DarkGray),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 1.dp).height(20.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("EXIT CE", color = if (exitCe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp) }

            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = if (allBuyPe) androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color.DarkGray),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 1.dp).height(20.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("BUY PE", color = if (allBuyPe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp) }

            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = if (exitPe) androidx.compose.ui.graphics.Color(0xFFFFB300) else androidx.compose.ui.graphics.Color.DarkGray),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 1.dp).height(20.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("EXIT PE", color = if (exitPe) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 9.sp) }
        }

        // Ledger Row
        val tradingMode by upstoxService.tradingMode.collectAsState()
        val dailyPnl = virtualTradeManager.getDailyPnl()
        val pnlColor = if (dailyPnl >= 0) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFF5252)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Trading Mode Badge
            Box(
                modifier = Modifier
                    .background(
                        color = if (tradingMode == "real") androidx.compose.ui.graphics.Color(0xFFFF5252) else androidx.compose.ui.graphics.Color(0xFF00E676),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (tradingMode == "real") "REAL" else "VIRTUAL",
                    color = androidx.compose.ui.graphics.Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Text("Daily Virtual P&L: ₹${String.format(java.util.Locale.US, "%.2f", dailyPnl)}", color = pnlColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)

            IconButton(
                onClick = { virtualTradeManager.clearHistory() },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset Ledger", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
"""

index = content.find("@Composable\nfun GlobalTradeButtonsRow")
if index != -1:
    content = content[:index] + original_global_row
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Reverted FeedScreen.kt to original GlobalTradeButtonsRow!")
else:
    print("Could not find GlobalTradeButtonsRow to replace.")
