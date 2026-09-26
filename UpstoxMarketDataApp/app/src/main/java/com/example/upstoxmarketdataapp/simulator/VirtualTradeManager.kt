package com.example.upstoxmarketdataapp.simulator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VirtualTrade(
    val id: String = java.util.UUID.randomUUID().toString(),
    val indexName: String,
    val type: String, // "CE" or "PE"
    val strike: String,
    val entryPrice: Double,
    val entryTime: Long,
    var exitPrice: Double? = null,
    var exitTime: Long? = null,
    val lotSize: Int
) {
    val profitPerUnit: Double
        get() = if (exitPrice != null) exitPrice!! - entryPrice else 0.0

    val totalProfit: Double
        get() = profitPerUnit * lotSize
        
    val formattedEntryTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entryTime))
        
    val formattedExitTime: String
        get() = exitTime?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "--:--:--"
}

class VirtualTradeManager {
    private val _trades = MutableStateFlow<List<VirtualTrade>>(emptyList())
    val trades: StateFlow<List<VirtualTrade>> = _trades.asStateFlow()

    private val activeTrades = mutableMapOf<String, VirtualTrade>()

    fun executeBuy(indexName: String, type: String, strike: String, price: Double, lotSize: Int) {
        // Prevent buying if we already hold an active trade for this type
        val tradeKey = "${indexName}_$type"
        if (activeTrades.containsKey(tradeKey)) return

        val newTrade = VirtualTrade(
            indexName = indexName,
            type = type,
            strike = strike,
            entryPrice = price,
            entryTime = System.currentTimeMillis(),
            lotSize = lotSize
        )
        activeTrades[tradeKey] = newTrade
        updateFlow()
    }

    fun executeSell(indexName: String, type: String, price: Double) {
        val tradeKey = "${indexName}_$type"
        val trade = activeTrades[tradeKey]
        if (trade != null) {
            trade.exitPrice = price
            trade.exitTime = System.currentTimeMillis()
            activeTrades.remove(tradeKey)
            updateFlow()
        }
    }

    private fun updateFlow() {
        // Combine active and closed trades
        val allTrades = _trades.value.filter { it.exitPrice != null } + activeTrades.values
        _trades.value = allTrades.sortedByDescending { it.entryTime }
    }
    
    fun getDailyPnl(): Double {
        return _trades.value.filter { it.exitPrice != null }.sumOf { it.totalProfit }
    }
    
    fun clearHistory() {
        _trades.value = emptyList()
        activeTrades.clear()
    }
}
