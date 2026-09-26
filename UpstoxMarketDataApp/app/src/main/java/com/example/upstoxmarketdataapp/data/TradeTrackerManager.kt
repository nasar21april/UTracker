package com.example.upstoxmarketdataapp.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class TrackedTrade(
    val symbol: String,
    var buyDate: String,
    var buyPrice: Double,
    var target: Double,
    var stopLoss: Double,
    var status: String, // "PENDING", "WON", "LOST"
    var sellDate: String? = null,
    var daysTaken: Int = 0,
    var pnlPercent: Double = 0.0
) {
    fun toJSONObject(): JSONObject {
        val obj = JSONObject()
        obj.put("symbol", symbol)
        obj.put("buyDate", buyDate)
        obj.put("buyPrice", buyPrice)
        obj.put("target", target)
        obj.put("stopLoss", stopLoss)
        obj.put("status", status)
        obj.put("sellDate", sellDate ?: JSONObject.NULL)
        obj.put("daysTaken", daysTaken)
        obj.put("pnlPercent", pnlPercent)
        return obj
    }

    companion object {
        fun fromJSONObject(obj: JSONObject): TrackedTrade {
            return TrackedTrade(
                symbol = obj.getString("symbol"),
                buyDate = obj.getString("buyDate"),
                buyPrice = obj.getDouble("buyPrice"),
                target = obj.getDouble("target"),
                stopLoss = obj.getDouble("stopLoss"),
                status = obj.getString("status"),
                sellDate = if (obj.isNull("sellDate")) null else obj.getString("sellDate"),
                daysTaken = obj.optInt("daysTaken", 0),
                pnlPercent = obj.optDouble("pnlPercent", 0.0)
            )
        }
    }
}

class TradeTrackerManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("trade_tracker_prefs", Context.MODE_PRIVATE)
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun getAllTrades(): MutableMap<String, TrackedTrade> {
        val jsonStr = prefs.getString("tracked_trades", "[]") ?: "[]"
        val arr = JSONArray(jsonStr)
        val map = mutableMapOf<String, TrackedTrade>()
        for (i in 0 until arr.length()) {
            val trade = TrackedTrade.fromJSONObject(arr.getJSONObject(i))
            map[trade.symbol] = trade
        }
        return map
    }

    private fun saveAllTrades(map: Map<String, TrackedTrade>) {
        val arr = JSONArray()
        map.values.forEach { arr.put(it.toJSONObject()) }
        prefs.edit().putString("tracked_trades", arr.toString()).apply()
    }

    fun registerOrUpdateTrade(symbol: String, buyPrice: Double, target: Double, stopLoss: Double, buyDate: String? = null) {
        val trades = getAllTrades()
        val existing = trades[symbol]
        if (existing == null) {
            val dateStr = buyDate ?: sdf.format(Date())
            trades[symbol] = TrackedTrade(symbol, dateStr, buyPrice, target, stopLoss, "PENDING")
            saveAllTrades(trades)
        } else {
            if (buyDate != null && buyDate.isNotEmpty() && existing.buyDate != buyDate) {
                existing.buyDate = buyDate
                saveAllTrades(trades)
            }
        }
    }

    fun checkAndUpdateStatus(symbol: String, ltp: Double): TrackedTrade? {
        val trades = getAllTrades()
        val trade = trades[symbol] ?: return null
        
        var changed = false
        if (trade.status == "PENDING") {
            if (ltp >= trade.target) {
                trade.status = "WON"
                changed = true
            } else if (ltp <= trade.stopLoss) {
                trade.status = "LOST"
                changed = true
            }
            
            if (changed) {
                val today = Date()
                trade.sellDate = sdf.format(today)
                
                try {
                    val buyD = sdf.parse(trade.buyDate)
                    if (buyD != null) {
                        val diffInMillis = today.time - buyD.time
                        trade.daysTaken = TimeUnit.MILLISECONDS.toDays(diffInMillis).toInt()
                    }
                } catch (e: Exception) {
                    trade.daysTaken = 0
                }
                
                trade.pnlPercent = ((ltp - trade.buyPrice) / trade.buyPrice) * 100.0
            }
        }
        
        if (changed) {
            saveAllTrades(trades)
        }
        return trade
    }
    fun forceStatus(symbol: String, status: String) {
        val trades = getAllTrades()
        val trade = trades[symbol] ?: return
        trade.status = status
        saveAllTrades(trades)
    }
    
    fun clearAllTrades() {
        prefs.edit().remove("tracked_trades").apply()
    }

    fun exportToJson(): String {
        val jsonStr = prefs.getString("tracked_trades", "[]") ?: "[]"
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "trade_history_export.json")
            file.writeText(jsonStr)
            return file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}
