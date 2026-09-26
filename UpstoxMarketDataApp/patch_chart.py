import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update the DualLayerIndexChart function definition and add dailyOhlc
pattern_def = r'fun DualLayerIndexChart\(\s*sym: String,\s*optionsChain: Map<String, StrikeRowState>,\s*candles: List<com\.example\.upstoxmarketdataapp\.data\.Candle>,\s*futures: List<com\.example\.upstoxmarketdataapp\.data\.FutureData>\?,\s*pivots: Map<String, PivotCalculator\.Pivots>\?,\s*upstoxService: UpstoxService\s*\) \{\s*val labels = upstoxService\.strikeLabels\s*val isDark = MaterialTheme\.colorScheme\.onBackground == Color\.White'

replacement_def = """fun DualLayerIndexChart(
    sym: String,
    optionsChain: Map<String, StrikeRowState>,
    candles: List<com.example.upstoxmarketdataapp.data.Candle>,
    futures: List<com.example.upstoxmarketdataapp.data.FutureData>?,
    pivots: Map<String, PivotCalculator.Pivots>?,
    upstoxService: UpstoxService
) {
    val labels = upstoxService.strikeLabels
    val isDark = MaterialTheme.colorScheme.onBackground == Color.White
    val dailyOhlcMap by upstoxService.dailyOhlcState.collectAsState()
    val dailyOhlc = dailyOhlcMap[sym]"""

content = re.sub(pattern_def, replacement_def, content)

# 2. Update paddingRight
content = content.replace('val paddingRight = 60f // Provide right padding for S/R text', 'val paddingRight = 100f // Provide right padding for Daily Candlestick')

# 3. Replace the S/R drawing logic
# From: "// Draw Dashed Support & Resistance Pivot lines on the background price scale!" up to "val timeText ="
# Wait, the best way is to use regex or string split.
import re

start_str = "// Draw Dashed Support & Resistance Pivot lines on the background price scale!"
end_str = "// Draw last ticker time on top right"

if start_str in content and end_str in content:
    pre_part = content.split(start_str)[0]
    post_part = content.split(end_str)[1]
    
    new_daily_candle_logic = """// S/R Calculation & Daily Candlestick Sidebar
            val ladder = upstoxService.pivotLadderMap[sym]
            val sup = ladder?.support
            val res = ladder?.resistance

            val srLabelPaint = android.graphics.Paint().apply {
                textSize = 17f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.LEFT
                isAntiAlias = true
            }

            val ltpLinePaint = android.graphics.Paint().apply {
                textSize = 17f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.LEFT
                isAntiAlias = true
            }

            // Draw Daily Candlestick on the right
            if (dailyOhlc != null) {
                val currentLtp = candles.lastOrNull()?.close ?: dailyOhlc.close
                val isBullish = currentLtp >= dailyOhlc.open
                
                // Adjust High/Low to encompass the entire day properly on chart
                val maxHigh = maxOf(dailyOhlc.high, currentLtp)
                val minLow = minOf(dailyOhlc.low, currentLtp)
                
                // We draw the candle at x = width - 50f
                val candleX = width - 60f
                val candleW = 16f
                
                // Ensure High/Low fit in the chart bounds, if not they clip
                val yHigh = getPriceY(maxHigh)
                val yLow = getPriceY(minLow)
                val yOpen = getPriceY(dailyOhlc.open)
                val yClose = getPriceY(currentLtp)

                val bodyTop = minOf(yOpen, yClose)
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
                )

                // 3. Draw S/R and LTP Overlays directly cutting across this candlestick
                val overlayLineW = 30f // Width of the horizontal tick
                val labelX = candleX + 16f

                var labelSupY: Float? = null
                var labelResY: Float? = null
                var labelLtpY = getPriceY(currentLtp)

                // Draw LTP line indicator across the daily candle
                drawLine(
                    color = candleColor,
                    start = Offset(candleX - overlayLineW, labelLtpY),
                    end = Offset(candleX + overlayLineW, labelLtpY),
                    strokeWidth = 3f
                )
                ltpLinePaint.color = if (isBullish) android.graphics.Color.parseColor("#00E676") else android.graphics.Color.parseColor("#FF5252")

                // Determine Support Y
                if (sup != null && sup in chartMinPrice..chartMaxPrice) {
                    val supY = getPriceY(sup)
                    labelSupY = supY
                    drawLine(
                        color = androidx.compose.ui.graphics.Color(0xFFFF1744),
                        start = Offset(candleX - overlayLineW, supY),
                        end = Offset(candleX + overlayLineW, supY),
                        strokeWidth = 3f
                    )
                }

                // Determine Resistance Y
                if (res != null && res in chartMinPrice..chartMaxPrice) {
                    val resY = getPriceY(res)
                    labelResY = resY
                    drawLine(
                        color = androidx.compose.ui.graphics.Color(0xFF00E676), // Green for Resistance
                        start = Offset(candleX - overlayLineW, resY),
                        end = Offset(candleX + overlayLineW, resY),
                        strokeWidth = 3f
                    )
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

                // Draw text labels
                if (labelSupY != null) {
                    srLabelPaint.color = android.graphics.Color.parseColor("#FF1744")
                    drawContext.canvas.nativeCanvas.drawText("S", labelX, labelSupY + 6f, srLabelPaint)
                }
                if (labelResY != null) {
                    srLabelPaint.color = android.graphics.Color.parseColor("#00E676")
                    drawContext.canvas.nativeCanvas.drawText("R", labelX, labelResY + 6f, srLabelPaint)
                }
                
                drawContext.canvas.nativeCanvas.drawText(currentLtp.toInt().toString(), labelX, labelLtpY + 6f, ltpLinePaint)
            }
        }

        // Draw last ticker time on top right"""
    
    content = pre_part + new_daily_candle_logic + post_part

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patched FeedScreen.kt with Daily Candlestick logic")
