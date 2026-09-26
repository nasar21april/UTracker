package com.example.upstoxmarketdataapp.data

object PivotCalculator {
    data class Pivots(
        val close: Double,
        val supports: List<Double>,
        val pivotPoint: Double,
        val resistances: List<Double>
    ) {
        fun allLevels(): List<Double> {
            val list = mutableListOf<Double>()
            list.addAll(supports)
            list.add(pivotPoint)
            list.addAll(resistances)
            return list
        }
    }

    fun calculate(high: Double, low: Double, close: Double): Map<String, Pivots> {
        val pp = (high + low + close) / 3.0
        val rng = high - low

        // Classic / Standard Pivot Calculations
        val classicSupports = listOf(
            2 * pp - high,         // S1
            pp - rng,              // S2
            pp - 2 * rng,          // S3
            pp - 3 * rng           // S4
        )
        val classicResistances = listOf(
            2 * pp - low,          // R1
            pp + rng,              // R2
            pp + 2 * rng,          // R3
            pp + 3 * rng           // R4
        )
        val classic = Pivots(close, classicSupports, pp, classicResistances)

        // Fibonacci Pivot Calculations
        val fibSupports = listOf(
            pp - 0.382 * rng,      // S1
            pp - 0.618 * rng,      // S2
            pp - 1.0 * rng,        // S3
            pp - 1.382 * rng,      // S4
            pp - 1.618 * rng       // S5
        )
        val fibResistances = listOf(
            pp + 0.382 * rng,      // R1
            pp + 0.618 * rng,      // R2
            pp + 1.0 * rng,        // R3
            pp + 1.382 * rng,      // R4
            pp + 1.618 * rng       // R5
        )
        val fib = Pivots(close, fibSupports, pp, fibResistances)

        // Camarilla Pivot Calculations
        val camSupports = listOf(
            close - rng * (1.1 / 12.0), // S1
            close - rng * (1.1 / 6.0),  // S2
            close - rng * (1.1 / 4.0),  // S3
            close - rng * (1.1 / 2.0)   // S4
        )
        val camResistances = listOf(
            close + rng * (1.1 / 12.0), // R1
            close + rng * (1.1 / 6.0),  // R2
            close + rng * (1.1 / 4.0),  // R3
            close + rng * (1.1 / 2.0)   // R4
        )
        val cam = Pivots(close, camSupports, pp, camResistances)

        // Woodie Pivot Calculations
        val woodiePp = (high + low + 2.0 * close) / 4.0
        val woodieSupports = listOf(
            2.0 * woodiePp - high,       // S1
            woodiePp - rng,              // S2
            2.0 * woodiePp - high - rng  // S3
        )
        val woodieResistances = listOf(
            2.0 * woodiePp - low,        // R1
            woodiePp + rng,              // R2
            2.0 * woodiePp - low + rng   // R3
        )
        val woodie = Pivots(close, woodieSupports, woodiePp, woodieResistances)

        return mapOf(
            "Classic" to classic,
            "Fibonacci" to fib,
            "Camarilla" to cam,
            "Woodie" to woodie
        )
    }

    fun getNearResSup(pivotsMap: Map<String, Pivots>?, ltp: Double): Pair<Double?, Double?> {
        if (pivotsMap == null) return Pair(null, null)
        // Use Classic pivots specifically as requested by user logic
        val pivots = pivotsMap["Classic"] ?: pivotsMap.values.firstOrNull() ?: return Pair(null, null)
        val allLevels = pivots.allLevels().distinct().sorted()
        if (allLevels.isEmpty()) return Pair(null, null)
        
        val res = allLevels.firstOrNull { it > ltp }
        val sup = allLevels.lastOrNull { it < ltp }
        return Pair(res, sup)
    }

    fun getAllUniquePivotLevels(pivotsMap: Map<String, Pivots>): List<Double> {
        return pivotsMap.values.flatMap { it.allLevels() }.distinct().sorted()
    }
}

class PivotLadderState(val sortedPivots: List<Double>) {
    var support: Double? = null
        private set
    var resistance: Double? = null
        private set

    fun updateLtp(ltp: Double) {
        if (sortedPivots.isEmpty()) return

        val currentSup = support
        val currentRes = resistance

        if (currentSup == null || currentRes == null) {
            // First time initialization based on current LTP
            val rIdx = sortedPivots.indexOfFirst { it > ltp }
            if (rIdx != -1) {
                resistance = sortedPivots[rIdx]
                support = if (rIdx > 0) sortedPivots[rIdx - 1] else sortedPivots[rIdx]
            } else {
                support = sortedPivots.lastOrNull()
                resistance = sortedPivots.lastOrNull()
            }
            return
        }

        // Loop to handle crossing events statefully
        var changed = true
        var safetyCounter = 0
        while (changed && safetyCounter < 100) {
            changed = false
            val sVal = support ?: break
            val rVal = resistance ?: break

            if (ltp > rVal) {
                // Crossed above resistance: old resistance becomes new support, next higher becomes resistance
                val rIdx = sortedPivots.indexOf(rVal)
                if (rIdx != -1 && rIdx < sortedPivots.size - 1) {
                    support = rVal
                    resistance = sortedPivots[rIdx + 1]
                    changed = true
                }
            } else if (ltp < sVal) {
                // Crossed below support: old support becomes new resistance, next lower becomes support
                val sIdx = sortedPivots.indexOf(sVal)
                if (sIdx != -1 && sIdx > 0) {
                    resistance = sVal
                    support = sortedPivots[sIdx - 1]
                    changed = true
                }
            }
            safetyCounter++
        }
    }
}
