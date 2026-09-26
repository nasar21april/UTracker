import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the block that calculates the candle dimensions and draws it
# We want to replace the `getPriceY` logic specific to the daily candle with our custom math.
# We also want to change the text labels for S and R.

# The existing block starts at: `// Draw Daily Candlestick on the right`
# and ends right before `// Draw last ticker time on top right`

start_str = "// Draw Daily Candlestick on the right"
end_str = "// Draw last ticker time on top right"

if start_str in content and end_str in content:
    pre_part = content.split(start_str)[0]
    post_part = content.split(end_str)[1]
    
    new_daily_candle_logic = """// Draw Daily Candlestick on the right
            if (dailyOhlc != null) {
                val currentLtp = candles.lastOrNull()?.close ?: dailyOhlc.close
                val isBullish = currentLtp >= dailyOhlc.open
                
                // Adjust High/Low to encompass the entire day properly on chart
                val maxHigh = maxOf(dailyOhlc.high, currentLtp)
                val minLow = minOf(dailyOhlc.low, currentLtp)
                
                val candleX = width - 60f
                val candleW = 16f
                
                // Calculate zeroY based on the options graph scale defined outside Canvas
                val currentZeroY = (height - paddingY - ((0.0 - minY) / (maxY - minY)) * plotHeight).toFloat()
                
                // We want to scale the daily candle so its MIDPOINT is at currentZeroY,
                // and it occupies the maximum vertical space available
                val maxVisualRadius = minOf(currentZeroY - paddingY, (height - paddingY) - currentZeroY) - 10f
                val midPrice = (maxHigh + minLow) / 2.0
                val candleRange = maxHigh - minLow
                
                fun getCenteredCandleY(p: Double): Float {
                    if (candleRange == 0.0) return currentZeroY
                    val distFrac = (p - midPrice) / (candleRange / 2.0)
                    return currentZeroY - (distFrac * maxVisualRadius).toFloat()
                }
                
                val yHigh = getCenteredCandleY(maxHigh)
                val yLow = getCenteredCandleY(minLow)
                val yOpen = getCenteredCandleY(dailyOhlc.open)
                val yClose = getCenteredCandleY(currentLtp)

                val bodyTop = minOf(yOpen, yClose)
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
                )

                // 3. Draw S/R and LTP Overlays directly cutting across this candlestick
                val overlayLineW = 30f // Width of the horizontal tick
                val labelX = candleX + 16f

                var labelSupY: Float? = null
                var labelResY: Float? = null
                var labelLtpY = getCenteredCandleY(currentLtp)

                // Draw LTP line indicator across the daily candle
                drawLine(
                    color = candleColor,
                    start = Offset(candleX - overlayLineW, labelLtpY),
                    end = Offset(candleX + overlayLineW, labelLtpY),
                    strokeWidth = 3f
                )
                ltpLinePaint.color = if (isBullish) android.graphics.Color.parseColor("#00E676") else android.graphics.Color.parseColor("#FF5252")

                // Determine Support Y
                if (sup != null) {
                    val supY = getCenteredCandleY(sup)
                    // Only draw if it's within visual bounds
                    if (supY in paddingY..(height - paddingY)) {
                        labelSupY = supY
                        drawLine(
                            color = androidx.compose.ui.graphics.Color(0xFFFF1744),
                            start = Offset(candleX - overlayLineW, supY),
                            end = Offset(candleX + overlayLineW, supY),
                            strokeWidth = 3f
                        )
                    }
                }

                // Determine Resistance Y
                if (res != null) {
                    val resY = getCenteredCandleY(res)
                    if (resY in paddingY..(height - paddingY)) {
                        labelResY = resY
                        drawLine(
                            color = androidx.compose.ui.graphics.Color(0xFF00E676), // Green for Resistance
                            start = Offset(candleX - overlayLineW, resY),
                            end = Offset(candleX + overlayLineW, resY),
                            strokeWidth = 3f
                        )
                    }
                }

                // Avoid text collision for labels next to the candle
                if (labelSupY != null && Math.abs(labelLtpY - labelSupY) < 20f) {
                    labelSupY = if (labelSupY > labelLtpY) labelLtpY + 20f else labelLtpY - 20f
                }
                if (labelResY != null && Math.abs(labelLtpY - labelResY) < 20f) {
                    labelResY = if (labelResY > labelLtpY) labelLtpY + 20f else labelLtpY - 20f
                }
                if (labelSupY != null && labelResY != null && Math.abs(labelSupY - labelResY) < 20f) {
                    if (labelSupY > labelResY) labelSupY += 10f else labelSupY -= 10f
                }

                // Draw text labels with direct numeric values (no "S" or "R")
                if (labelSupY != null) {
                    srLabelPaint.color = android.graphics.Color.parseColor("#FF1744")
                    drawContext.canvas.nativeCanvas.drawText(sup?.toInt().toString(), labelX, labelSupY + 6f, srLabelPaint)
                }
                if (labelResY != null) {
                    srLabelPaint.color = android.graphics.Color.parseColor("#00E676")
                    drawContext.canvas.nativeCanvas.drawText(res?.toInt().toString(), labelX, labelResY + 6f, srLabelPaint)
                }
                
                drawContext.canvas.nativeCanvas.drawText(currentLtp.toInt().toString(), labelX, labelLtpY + 6f, ltpLinePaint)
            }
        }

        // Draw last ticker time on top right"""
    
    content = pre_part + new_daily_candle_logic + post_part

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patched FeedScreen.kt to perfectly center Daily Candle on zeroY and use full space.")
