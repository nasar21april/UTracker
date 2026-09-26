import re

file_path = 'app/src/main/java/com/example/upstoxmarketdataapp/ui/feed/FeedScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update PivotsAndChartTab signature
content = content.replace(
'''fun PivotsAndChartTab(
    upstoxService: UpstoxService,
    optionsChain: Map<String, StrikeRowState>,
    pivotsState: Map<String, Map<String, PivotCalculator.Pivots>>,
    indexCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    yoiProgress: String
) {''',
'''fun PivotsAndChartTab(
    upstoxService: UpstoxService,
    optionsChain: Map<String, StrikeRowState>,
    pivotsState: Map<String, Map<String, PivotCalculator.Pivots>>,
    indexCandles: Map<String, List<com.example.upstoxmarketdataapp.data.Candle>>,
    yoiProgress: String,
    trades: List<com.example.upstoxmarketdataapp.data.Trade>
) {'''
)

# 2. Update PivotsAndChartTab invocation
content = content.replace(
'''                0 -> PivotsAndChartTab(
                    upstoxService = upstoxService,
                    optionsChain = optionsChain,
                    pivotsState = pivotsState,
                    indexCandles = indexCandles,
                    yoiProgress = yoiProgress
                )''',
'''                0 -> PivotsAndChartTab(
                    upstoxService = upstoxService,
                    optionsChain = optionsChain,
                    pivotsState = pivotsState,
                    indexCandles = indexCandles,
                    yoiProgress = yoiProgress,
                    trades = trades
                )'''
)

# 3. Add VirtualLedgerTable inside PivotsAndChartTab below QuickOptionsTradingTable
old_qot = '''        // Options Trading Table for the selected chart
        QuickOptionsTradingTable(
            selectedIndex = selectedQuickIndex,
            optionsChain = optionsChain,
            upstoxService = upstoxService,
            tradingMode = tradingMode,
            productType = productType
        )

        Spacer(modifier = Modifier.height(4.dp))'''

new_qot = '''        // Options Trading Table for the selected chart
        QuickOptionsTradingTable(
            selectedIndex = selectedQuickIndex,
            optionsChain = optionsChain,
            upstoxService = upstoxService,
            tradingMode = tradingMode,
            productType = productType,
            pivotsState = pivotsState
        )

        Spacer(modifier = Modifier.height(8.dp))
        
        Text("Virtual Ledger", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp)
        VirtualLedgerTable(trades = trades, onClear = { upstoxService.clearVirtualTrades() })

        Spacer(modifier = Modifier.height(8.dp))'''
content = content.replace(old_qot, new_qot)

# 4. Modify QuickOptionsTradingTable signature
old_qotsig = '''fun QuickOptionsTradingTable(
    selectedIndex: String,
    optionsChain: Map<String, StrikeRowState>,
    upstoxService: UpstoxService,
    tradingMode: String,
    productType: String,
    modifier: Modifier = Modifier
) {'''
new_qotsig = '''fun QuickOptionsTradingTable(
    selectedIndex: String,
    optionsChain: Map<String, StrikeRowState>,
    upstoxService: UpstoxService,
    tradingMode: String,
    productType: String,
    pivotsState: Map<String, Map<String, com.example.upstoxmarketdataapp.data.PivotCalculator.Pivots>> = emptyMap(),
    modifier: Modifier = Modifier
) {'''
content = content.replace(old_qotsig, new_qotsig)

# 5. Modify QuickOptionsTradingTable labels loop
content = content.replace(
'''            // Strikes List (11 rows)
            val labels = upstoxService.strikeLabels
            for (lbl in labels) {''',
'''            // Strikes List (ATM Only)
            val labels = listOf("Current")
            for (lbl in labels) {'''
)

# 6. Add Support/Resistance values below the QuickOptionsTradingTable's row
sr_addition = '''                }
                
                // Show S/R values just below the Buy/Sell options
                val pivots = pivotsState[selectedIndex]?.get("1D")
                if (pivots != null) {
                    val ltp = (ceLtp + peLtp) / 2.0 // Approximation for context if needed, or just display raw values
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text("Support 1: ${pivots.s1.toInt()}", color = androidx.compose.ui.graphics.Color(0xFFFF1744), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("Resistance 1: ${pivots.r1.toInt()}", color = androidx.compose.ui.graphics.Color(0xFF00E676), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }'''
content = content.replace(
'''                }
            }
        }
    }
}

// ==========================================''',
sr_addition + '''
        }
    }
}

@Composable
fun VirtualLedgerTable(trades: List<com.example.upstoxmarketdataapp.data.Trade>, onClear: () -> Unit) {
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        if (trades.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier
                    .androidx.compose.foundation.layout.fillMaxWidth()
                    .androidx.compose.foundation.layout.height(80.dp)
                    .androidx.compose.foundation.border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text("No trades recorded yet.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        } else {
            for (trade in trades) {
                val color = if (trade.pnl >= 0.0) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFF5252)
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier
                        .androidx.compose.foundation.layout.fillMaxWidth()
                        .androidx.compose.foundation.background(androidx.compose.material3.MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .androidx.compose.foundation.border(0.5.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .androidx.compose.foundation.layout.padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(
                                modifier = androidx.compose.ui.Modifier
                                    .androidx.compose.ui.draw.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                    .androidx.compose.foundation.background(if (trade.action == "BUY") androidx.compose.ui.graphics.Color(0x3300E676) else androidx.compose.ui.graphics.Color(0x33FF5252))
                                    .androidx.compose.foundation.layout.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                androidx.compose.material3.Text(trade.action, color = if (trade.action == "BUY") androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFF5252), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.androidx.compose.foundation.layout.width(6.dp))
                            androidx.compose.material3.Text("${trade.indexSymbol} ${trade.strikePrice.toInt()} ${trade.optionType}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.androidx.compose.foundation.layout.height(4.dp))
                        androidx.compose.material3.Text("Entry: ${trade.entryPrice} | LTP: ${trade.currentPrice} | Qty: ${trade.quantity}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }

                    androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        androidx.compose.material3.Text(
                            text = String.format(java.util.Locale.US, "%s%.2f", if (trade.pnl >= 0.0) "+" else "", trade.pnl),
                            color = color,
                            fontSize = 14.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                        )
                        androidx.compose.material3.Text("P&L", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ==========================================''')

# 7. Modify VirtualTradingTab to use VirtualLedgerTable
content = content.replace(
'''        // Ledger cards
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (trade in trades) {
                    val color = if (trade.pnl >= 0.0) Color(0xFF00E676) else Color(0xFFFF5252)
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
                                        .background(if (trade.action == "BUY") Color(0x3300E676) else Color(0x33FF5252))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(trade.action, color = if (trade.action == "BUY") Color(0xFF00E676) else Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("${trade.indexSymbol} ${trade.strikePrice.toInt()} ${trade.optionType}", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Entry: ${trade.entryPrice} | LTP: ${trade.currentPrice} | Qty: ${trade.quantity}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format(Locale.US, "%s%.2f", if (trade.pnl >= 0.0) "+" else "", trade.pnl),
                                color = color,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text("P&L", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                }
            }
        }''',
'''        // Ledger cards
        VirtualLedgerTable(trades = trades, onClear = { upstoxService.clearVirtualTrades() })'''
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Done")
