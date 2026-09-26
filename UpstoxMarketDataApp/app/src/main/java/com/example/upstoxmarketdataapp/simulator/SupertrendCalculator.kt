package com.example.upstoxmarketdataapp.simulator

import com.example.upstoxmarketdataapp.data.Candle
import kotlin.math.abs

enum class SupertrendState {
    BULLISH,
    BEARISH,
    NEUTRAL
}

data class SupertrendResult(
    val value: Double,
    val state: SupertrendState
)

class SupertrendCalculator(
    private val period: Int = 10,
    private val multiplier: Double = 3.0
) {
    private var prevFinalUpperband: Double = 0.0
    private var prevFinalLowerband: Double = 0.0
    private var prevSupertrend: Double = 0.0
    private var prevTrend: SupertrendState = SupertrendState.NEUTRAL
    private var atrCalculator = ATRCalculator(period)

    fun calculate(candles: List<Candle>): SupertrendResult? {
        if (candles.size < period) return null

        atrCalculator = ATRCalculator(period) // Reset ATR for simplicity and recalculate over the window
        var currentAtr = 0.0
        
        // Calculate ATR up to the current candle
        for (i in 1 until candles.size) {
            currentAtr = atrCalculator.next(candles[i], candles[i-1])
        }

        if (currentAtr == 0.0) return null

        val currentCandle = candles.last()
        val prevCandle = candles[candles.size - 2]
        
        val hl2 = (currentCandle.high + currentCandle.low) / 2.0
        val basicUpperband = hl2 + (multiplier * currentAtr)
        val basicLowerband = hl2 - (multiplier * currentAtr)

        val finalUpperband = if (basicUpperband < prevFinalUpperband || prevCandle.close > prevFinalUpperband) {
            basicUpperband
        } else {
            prevFinalUpperband
        }

        val finalLowerband = if (basicLowerband > prevFinalLowerband || prevCandle.close < prevFinalLowerband) {
            basicLowerband
        } else {
            prevFinalLowerband
        }

        var currentSupertrend = 0.0
        var currentTrend = prevTrend

        if (prevSupertrend == prevFinalUpperband && currentCandle.close <= finalUpperband) {
            currentSupertrend = finalUpperband
            currentTrend = SupertrendState.BEARISH
        } else if (prevSupertrend == prevFinalUpperband && currentCandle.close > finalUpperband) {
            currentSupertrend = finalLowerband
            currentTrend = SupertrendState.BULLISH
        } else if (prevSupertrend == prevFinalLowerband && currentCandle.close >= finalLowerband) {
            currentSupertrend = finalLowerband
            currentTrend = SupertrendState.BULLISH
        } else if (prevSupertrend == prevFinalLowerband && currentCandle.close < finalLowerband) {
            currentSupertrend = finalUpperband
            currentTrend = SupertrendState.BEARISH
        } else {
            // Initial state
            currentSupertrend = finalUpperband
            currentTrend = SupertrendState.BEARISH
        }

        // Update state for next calculation
        prevFinalUpperband = finalUpperband
        prevFinalLowerband = finalLowerband
        prevSupertrend = currentSupertrend
        prevTrend = currentTrend

        return SupertrendResult(currentSupertrend, currentTrend)
    }
}

class ATRCalculator(private val period: Int) {
    private var trList = mutableListOf<Double>()
    
    fun next(current: Candle, previous: Candle): Double {
        val highLow = current.high - current.low
        val highPrevClose = abs(current.high - previous.close)
        val lowPrevClose = abs(current.low - previous.close)
        
        val trueRange = maxOf(highLow, highPrevClose, lowPrevClose)
        trList.add(trueRange)
        
        if (trList.size > period) {
            trList.removeAt(0)
        }
        
        // Simple Moving Average of True Range for ATR
        return if (trList.size == period) {
            trList.average()
        } else {
            0.0
        }
    }
}
