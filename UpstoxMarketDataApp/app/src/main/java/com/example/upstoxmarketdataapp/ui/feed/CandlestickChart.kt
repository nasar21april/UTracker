package com.example.upstoxmarketdataapp.ui.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChartCandle(val open: Double, val high: Double, val low: Double, val close: Double)

@Composable
fun CandleChartWithLevels(symbol: String, tgt: Double, sl: Double, buyPrice: Double, ltp: Double) {
    var candles by remember { mutableStateOf<List<ChartCandle>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(symbol) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://query1.finance.yahoo.com/v8/finance/chart/$symbol.NS?interval=1d&range=2mo")
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                
                val result = JSONObject(response)
                    .getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0)
                    .getJSONObject("indicators")
                    .getJSONArray("quote")
                    .getJSONObject(0)

                val opens = result.getJSONArray("open")
                val highs = result.getJSONArray("high")
                val lows = result.getJSONArray("low")
                val closes = result.getJSONArray("close")

                val parsedCandles = mutableListOf<ChartCandle>()
                for (i in 0 until opens.length()) {
                    if (!opens.isNull(i) && !closes.isNull(i)) {
                        parsedCandles.add(
                            ChartCandle(
                                opens.getDouble(i),
                                highs.getDouble(i),
                                lows.getDouble(i),
                                closes.getDouble(i)
                            )
                        )
                    }
                }
                candles = parsedCandles
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    if (error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Failed to load chart: $error", color = Color.Red)
        }
    } else if (candles == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        val candleList = candles!!
        if (candleList.isEmpty()) return
        
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            
            val minPrice = minOf(candleList.minOf { it.low }, sl * 0.98, ltp * 0.98)
            val maxPrice = maxOf(candleList.maxOf { it.high }, tgt * 1.02, ltp * 1.02)
            val priceRange = maxPrice - minPrice
            
            val candleWidth = canvasWidth / candleList.size
            val bodyWidth = candleWidth * 0.6f

            // Draw horizontal levels
            val slY = canvasHeight - ((sl - minPrice) / priceRange * canvasHeight).toFloat()
            val tgtY = canvasHeight - ((tgt - minPrice) / priceRange * canvasHeight).toFloat()
            val ltpY = canvasHeight - ((ltp - minPrice) / priceRange * canvasHeight).toFloat()
            val buyY = canvasHeight - ((buyPrice - minPrice) / priceRange * canvasHeight).toFloat()

            // Target Line (Green)
            drawLine(color = Color(0xFF00E676), start = Offset(0f, tgtY), end = Offset(canvasWidth, tgtY), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
            
            // SL Line (Red)
            drawLine(color = Color(0xFFFF5252), start = Offset(0f, slY), end = Offset(canvasWidth, slY), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
            
            // Buy Line (White)
            drawLine(color = Color.White.copy(alpha = 0.5f), start = Offset(0f, buyY), end = Offset(canvasWidth, buyY), strokeWidth = 1.dp.toPx())
            
            // LTP Line (Yellow)
            drawLine(color = Color(0xFFFFD600), start = Offset(0f, ltpY), end = Offset(canvasWidth, ltpY), strokeWidth = 2.dp.toPx())

            // Draw Candles
            candleList.forEachIndexed { index, candle ->
                val x = index * candleWidth + (candleWidth / 2)
                
                val openY = canvasHeight - ((candle.open - minPrice) / priceRange * canvasHeight).toFloat()
                val closeY = canvasHeight - ((candle.close - minPrice) / priceRange * canvasHeight).toFloat()
                val highY = canvasHeight - ((candle.high - minPrice) / priceRange * canvasHeight).toFloat()
                val lowY = canvasHeight - ((candle.low - minPrice) / priceRange * canvasHeight).toFloat()
                
                val candleColor = if (candle.close >= candle.open) Color(0xFF00E676) else Color(0xFFFF5252)
                
                // Wick
                drawLine(color = candleColor, start = Offset(x, highY), end = Offset(x, lowY), strokeWidth = 2f)
                
                // Body
                val topY = minOf(openY, closeY)
                val bottomY = maxOf(openY, closeY)
                val bodyHeight = maxOf(1f, bottomY - topY)
                
                drawRect(
                    color = candleColor,
                    topLeft = Offset(x - (bodyWidth / 2), topY),
                    size = Size(bodyWidth, bodyHeight)
                )
            }
        }
    }
}
