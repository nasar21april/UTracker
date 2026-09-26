import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update the scaling logic
scaling_search = """        // LAYER 1: Background 1-Minute Index Price Candles (Low Opacity)
        // ============================================================
        if (candles.isNotEmpty()) {
            val minPrice = candles.minOf { it.low }
            val maxPrice = candles.maxOf { it.high }"""

scaling_replace = """        // LAYER 1: Background 1-Minute Index Price Candles (Low Opacity)
        // ============================================================
        if (candles.isNotEmpty()) {
            val minPrice = if (dailyOhlc != null) minOf(candles.minOf { it.low }, dailyOhlc.low) else candles.minOf { it.low }
            val maxPrice = if (dailyOhlc != null) maxOf(candles.maxOf { it.high }, dailyOhlc.high) else candles.maxOf { it.high }"""

content = content.replace(scaling_search, scaling_replace)

# 2. Remove the background candle drawing loop
loop_search = """            for (idx in candles.indices) {
                val candle = candles[idx]
                val cx = paddingX + idx * candleWidth + (candleWidth / 2f)
                val yHigh = getPriceY(candle.high)
                val yLow = getPriceY(candle.low)
                val yOpen = getPriceY(candle.open)
                val yClose = getPriceY(candle.close)

                val color = if (candle.close >= candle.open) {
                    androidx.compose.ui.graphics.Color(0xFF00E676).copy(alpha = 0.10f) // Bullish candle - 30% opacity
                } else {
                    androidx.compose.ui.graphics.Color(0xFFFF5252).copy(alpha = 0.10f) // Bearish candle - 30% opacity
                }

                // Draw wick
                drawLine(
                    color = if (isDark) Color.LightGray.copy(alpha = 0.10f) else Color.DarkGray.copy(alpha = 0.10f),
                    start = Offset(cx, yHigh),
                    end = Offset(cx, yLow),
                    strokeWidth = 1f
                )

                // Draw body
                val bodyTop = minOf(yOpen, yClose)
                val bodyBottom = maxOf(yOpen, yClose)
                val bodyHeight = maxOf(Math.abs(yClose - yOpen), 1f)
                
                drawRect(
                    color = color,
                    topLeft = Offset(cx - (candleWidth * 0.4f), bodyTop),
                    size = androidx.compose.ui.geometry.Size(candleWidth * 0.8f, bodyHeight)
                )
            }"""

loop_replace = """            // Background candles have been removed as per user request to clean the chart."""
content = content.replace(loop_search, loop_replace)

# 3. Brighten the Daily Candlestick and adjust bounds
# Finding the block:
candle_logic_search = """                val bodyTop = minOf(yOpen, yClose)
                val bodyBottom = maxOf(yOpen, yClose)
                val bodyHeight = maxOf(Math.abs(yClose - yOpen), 2f)

                val candleColor = if (isBullish) androidx.compose.ui.graphics.Color(0xFF00E676) else androidx.compose.ui.graphics.Color(0xFFFF5252)

                // 1. Draw Wick
                drawLine(
                    color = if (isDark) androidx.compose.ui.graphics.Color.LightGray else androidx.compose.ui.graphics.Color.DarkGray,
                    start = Offset(candleX, yHigh),
                    end = Offset(candleX, yLow),
                    strokeWidth = 2f
                )

                // 2. Draw Body
                drawRect(
                    color = candleColor,
                    topLeft = Offset(candleX - (candleW / 2f), bodyTop),
                    size = androidx.compose.ui.geometry.Size(candleW, bodyHeight)
                )"""

candle_logic_replace = """                val bodyTop = minOf(yOpen, yClose)
                val bodyBottom = maxOf(yOpen, yClose)
                val bodyHeight = maxOf(Math.abs(yClose - yOpen), 4f) // Thicker body minimum

                // Extremely bright and opaque colors
                val candleColor = if (isBullish) androidx.compose.ui.graphics.Color(0xFF00FF00) else androidx.compose.ui.graphics.Color(0xFFFF0000)

                // 1. Draw Wick (thick and bright white/black depending on theme)
                drawLine(
                    color = if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
                    start = Offset(candleX, yHigh),
                    end = Offset(candleX, yLow),
                    strokeWidth = 4f
                )

                // 2. Draw Body (bright opaque)
                drawRect(
                    color = candleColor,
                    topLeft = Offset(candleX - (candleW / 2f), bodyTop),
                    size = androidx.compose.ui.geometry.Size(candleW, bodyHeight)
                )"""

content = content.replace(candle_logic_search, candle_logic_replace)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patched FeedScreen.kt with brighter daily candle and removed background candles")
