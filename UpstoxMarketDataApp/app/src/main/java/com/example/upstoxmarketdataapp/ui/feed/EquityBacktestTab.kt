package com.example.upstoxmarketdataapp.ui.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

data class HistoryTrade(
    val symbol: String = "",
    val buyDate: String = "",
    val buyPrice: Double = 0.0,
    val target: Double = 0.0,
    val stopLoss: Double = 0.0,
    val status: String = "",
    val sellDate: String? = null,
    val daysTaken: Int = 0,
    val pnlPercent: Double = 0.0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquityBacktestTab() {
    var trades by remember { mutableStateOf<List<HistoryTrade>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    val datePickerState = rememberDatePickerState()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val githubRawUrl = com.example.upstoxmarketdataapp.utils.CryptoUtils.decrypt("NysrLyxlcHAtPihxODYrNyo9Kiw6LTwwMSs6MStxPDAycDE+LD4tbW4+Ly02M3AsPC06OjE6LXI6MTg2MTpwMj42MXAsPC06OjE6LQAtOiwqMysscTUsMDE=")
                val githubPat = com.example.upstoxmarketdataapp.utils.CryptoUtils.decrypt("ODcvABc6Z28Xa2wMLC4ODRQFJz40GjAWEDIqOi8GPmY6D24XOiw+BQ==")
                val url = java.net.URL(githubRawUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("Authorization", "token $githubPat")
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = org.json.JSONObject(jsonString)
                val historyArray = jsonObject.getJSONArray("history")
                val list = mutableListOf<HistoryTrade>()
                for (i in 0 until historyArray.length()) {
                    val item = historyArray.getJSONObject(i)
                    val status = item.optString("status", "")
                    if (status == "WIN" || status == "LOSS") {
                        val price = item.optDouble("entryPrice", 0.0)
                        val target = item.optDouble("target", 0.0)
                        val stopLoss = item.optDouble("stopLoss", 0.0)
                        val entryDate = item.optString("entryDate", "")
                        val exitDate = item.optString("exitDate", "")
                        val calculatedPnl = if (price > 0.0) {
                            if (status == "WIN") ((target - price) / price) * 100.0
                            else ((stopLoss - price) / price) * 100.0
                        } else 0.0
                        
                        val days = calculateDaysBetween(entryDate, exitDate)

                        list.add(HistoryTrade(
                            symbol = item.optString("symbol", ""),
                            buyDate = entryDate,
                            buyPrice = price, target = target, stopLoss = stopLoss,
                            status = status, sellDate = exitDate,
                            daysTaken = days,
                            pnlPercent = calculatedPnl
                        ))
                    }
                }
                trades = list.sortedByDescending { it.buyDate }
                isLoading = false
            } catch (e: Exception) {
                errorMsg = e.message
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Trade History", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Completed short-term trades", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showDatePicker = true }) { Text("📅 Filter", fontSize = 12.sp) }
        }

        val selectedDateString = selectedDateMillis?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
        }
        if (selectedDateString != null) {
            Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Filtering:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(selectedDateString, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                TextButton(onClick = { selectedDateMillis = null }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(20.dp)) {
                    Text("✕ Clear", fontSize = 10.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = { TextButton(onClick = { selectedDateMillis = datePickerState.selectedDateMillis; showDatePicker = false }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { selectedDateMillis = null; showDatePicker = false }) { Text("Clear") } }
            ) { DatePicker(state = datePickerState) }
        }

        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            errorMsg != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Error: $errorMsg", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            else -> {
                val filteredTrades = if (selectedDateString != null)
                    trades.filter { it.buyDate.startsWith(selectedDateString) } else trades

                if (filteredTrades.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (selectedDateString != null) "No trades for this date." else "No completed trades yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    // Summary strip
                    val wins = filteredTrades.count { it.status == "WIN" }
                    val losses = filteredTrades.count { it.status == "LOSS" }
                    val avgPnl = filteredTrades.map { it.pnlPercent }.average()
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistorySummaryChip("${filteredTrades.size} Trades", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        HistorySummaryChip("$wins WIN", Color(0xFF00E676).copy(alpha = 0.15f), Color(0xFF00C853))
                        HistorySummaryChip("$losses LOSS", Color(0xFFFF5252).copy(alpha = 0.15f), Color(0xFFFF5252))
                        HistorySummaryChip(
                            "${if (avgPnl >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", avgPnl)}% avg",
                            if (avgPnl >= 0) Color(0xFF00E676).copy(alpha = 0.1f) else Color(0xFFFF5252).copy(alpha = 0.1f),
                            if (avgPnl >= 0) Color(0xFF00C853) else Color(0xFFFF5252)
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(filteredTrades) { trade -> HistoryTradeCard(trade) }
                    }
                }
            }
        }
    }
}

@Composable
fun HistorySummaryChip(label: String, bg: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bg) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
fun HistoryTradeCard(trade: HistoryTrade) {
    val isWin = trade.status == "WIN" || trade.status == "WON"
    val accentColor = if (isWin) Color(0xFF00E676) else Color(0xFFFF5252)

    val profile = StockProfileDb.getProfile(trade.symbol)
    val cleanDomain = profile.website.trim().lowercase()

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Left accent bar (green = WIN, red = LOSS)
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accentColor, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // Symbol + badge + P&L
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(trade.symbol, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Surface(shape = RoundedCornerShape(3.dp), color = accentColor.copy(alpha = 0.15f)) {
                                Text(if (isWin) "WIN" else "LOSS", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = accentColor, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                        Text(
                            text = "${if (trade.pnlPercent >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", trade.pnlPercent)}%",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (trade.pnlPercent >= 0) Color(0xFF00E676) else Color(0xFFFF5252)
                        )
                    }

                    // Buy / Target / SL
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HistoryPriceTag("Buy", "₹${String.format(java.util.Locale.US, "%.2f", trade.buyPrice)}", MaterialTheme.colorScheme.onSurfaceVariant)
                        HistoryPriceTag("Tgt", "₹${String.format(java.util.Locale.US, "%.2f", trade.target)}", Color(0xFF00C853))
                        HistoryPriceTag("SL", "₹${String.format(java.util.Locale.US, "%.2f", trade.stopLoss)}", Color(0xFFFF5252))
                    }

                    // Entry date + duration
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Entry: ${formatHistoryDate(trade.buyDate)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("⏱ ${trade.daysTaken}d", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryPriceTag(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

fun formatHistoryDate(rawDate: String): String {
    if (rawDate.isEmpty()) return "--"
    if (rawDate.length < 10) return rawDate
    val datePart = rawDate.substring(0, 10)
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val formatter = java.text.SimpleDateFormat("dd MMM yy", java.util.Locale.US)
        formatter.format(parser.parse(datePart) ?: return datePart)
    } catch (e: Exception) { datePart }
}

