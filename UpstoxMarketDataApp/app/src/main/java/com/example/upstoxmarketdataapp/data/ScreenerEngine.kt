package com.example.upstoxmarketdataapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

data class ScreenerResult(
    val symbol: String,
    val instrumentKey: String,
    val score: Int,
    val price: Double,
    val target: Double,
    val stopLoss: Double,
    val breakoutVolume: Double,
    val reason: String = "",
    val technicals: Map<String, String> = emptyMap(),
    val fundamentals: Map<String, String> = emptyMap(),
    val entryDate: String = ""
)

class ScreenerEngine(private val context: Context) {

    suspend fun runNativeScreener(upstoxService: UpstoxService): List<ScreenerResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScreenerResult>()
        
        for ((symbol, instrumentKey) in Universe.nifty50) {
            val candles = upstoxService.fetchScreenerCandles(instrumentKey)
            if (candles.size < 50) {
                delay(100)
                continue // Not enough data
            }

            // Calculations
            val closes = candles.map { it.close }
            val volumes = candles.map { it.volume.toDouble() }
            val currentPrice = closes.last()
            val currentVolume = volumes.last()
            
            val sma50 = closes.takeLast(50).average()
            val sma200 = if (closes.size >= 200) closes.takeLast(200).average() else sma50
            
            // RSI 14
            var rsi14 = 50.0
            if (closes.size >= 15) {
                val gains = mutableListOf<Double>()
                val losses = mutableListOf<Double>()
                for (i in closes.size - 14 until closes.size) {
                    val diff = closes[i] - closes[i-1]
                    if (diff > 0) gains.add(diff) else losses.add(abs(diff))
                }
                val avgGain = if (gains.isNotEmpty()) gains.average() else 0.0
                val avgLoss = if (losses.isNotEmpty()) losses.average() else 0.0
                
                if (avgLoss == 0.0) {
                    rsi14 = 100.0
                } else {
                    val rs = avgGain / avgLoss
                    rsi14 = 100 - (100 / (1 + rs))
                }
            }
            
            // Volume Average 20
            val vol20 = if (volumes.size >= 20) volumes.takeLast(20).average() else currentVolume
            
            // Scoring
            var score = 0
            if (currentPrice > sma50 && currentPrice > sma200) score += 40
            if (rsi14 in 50.0..70.0) score += 30
            if (currentVolume > vol20 * 2) score += 30
            
            // Risk Reward
            val recentLows = candles.takeLast(5).map { it.low }
            val swingLow = recentLows.minOrNull() ?: (currentPrice * 0.97)
            
            var sl = swingLow
            if (currentPrice - sl > currentPrice * 0.05) sl = currentPrice * 0.95 // Max 5% SL
            if (currentPrice - sl < currentPrice * 0.01) sl = currentPrice * 0.99 // Min 1% SL
            
            val risk = currentPrice - sl
            val tgt = currentPrice + (risk * 2) // 1:2 R:R
            
            if (score > 0) {
                results.add(ScreenerResult(
                    symbol = symbol,
                    instrumentKey = instrumentKey,
                    score = score,
                    price = currentPrice,
                    target = tgt,
                    stopLoss = sl,
                    breakoutVolume = currentVolume
                ))
            }
            
            delay(100) // Rate limit protection
        }
        
        // Sort by score and take top 10
        val top10 = results.sortedByDescending { it.score }.take(10)
        
        // Save to top_10_watchlist.json
        val json = JSONObject()
        val arr = JSONArray()
        for (r in top10) {
            val obj = JSONObject()
            obj.put("symbol", r.symbol)
            obj.put("instrumentKey", r.instrumentKey)
            obj.put("score", r.score)
            obj.put("price", r.price)
            obj.put("target", r.target)
            obj.put("stopLoss", r.stopLoss)
            obj.put("breakoutVolume", r.breakoutVolume)
            arr.put(obj)
        }
        json.put("stocks", arr)
        
        val file = File(context.filesDir, "top_10_watchlist.json")
        file.writeText(json.toString())
        
        return@withContext top10
    }

    suspend fun getWatchlist(): List<ScreenerResult> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ScreenerResult>()
        try {
            val file = File(context.filesDir, "top_10_watchlist.json")
            if (file.exists()) {
                val content = file.readText()
                val json = JSONObject(content)
                val arr = json.getJSONArray("stocks")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        ScreenerResult(
                            symbol = obj.getString("symbol"),
                            instrumentKey = obj.getString("instrumentKey"),
                            score = obj.getInt("score"),
                            price = obj.getDouble("price"),
                            target = obj.optDouble("target", 0.0),
                            stopLoss = obj.optDouble("stopLoss", 0.0),
                            breakoutVolume = obj.optDouble("breakoutVolume", 0.0)
                        )
                    )
                }
            } else {
                // Dummy data
                list.add(ScreenerResult("AMBUJACEM", "NSE_EQ|INE079A01024", 92, 600.0, 650.0, 580.0, 1000000.0))
                list.add(ScreenerResult("RELIANCE", "NSE_EQ|INE002A01018", 90, 3000.0, 3200.0, 2900.0, 5000000.0))
                list.add(ScreenerResult("LT", "NSE_EQ|INE018A01030", 88, 3600.0, 3800.0, 3500.0, 2000000.0))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }
}
