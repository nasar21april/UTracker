package com.example.upstoxmarketdataapp.data

data class StockData(
    val instrumentKey: String,
    val symbolName: String = instrumentKey.substringAfter("|"),
    val ltp: Double = 0.0,
    val close: Double = 0.0,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val lastUpdateTime: Long = System.currentTimeMillis()
) {
    val netChange: Double
        get() = if (close > 0.0 && ltp > 0.0) ltp - close else 0.0

    val percentChange: Double
        get() = if (close > 0.0) (netChange / close) * 100.0 else 0.0
}
