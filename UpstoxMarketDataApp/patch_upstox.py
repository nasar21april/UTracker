import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\data\UpstoxService.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Add state declaration
state_declaration = """    // Index 15-second candles state
    private val _index15sCandlesState = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    val index15sCandlesState: StateFlow<Map<String, List<Candle>>> = _index15sCandlesState

    private val _dailyOhlcState = MutableStateFlow<Map<String, IndexOhlc>>(emptyMap())
    val dailyOhlcState: StateFlow<Map<String, IndexOhlc>> = _dailyOhlcState
"""
content = re.sub(
    r'    // Index 15-second candles state\s+private val _index15sCandlesState = MutableStateFlow<Map<String, List<Candle>>>\(emptyMap\(\)\)\s+val index15sCandlesState: StateFlow<Map<String, List<Candle>>> = _index15sCandlesState',
    state_declaration,
    content
)

# Update logic inside updateIndexCandles
ohlc_update_logic = """    private fun updateIndexCandles(sym: String, ltp: Double) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sdf15s = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        
        // Track daily OHLC
        val currentDailyMap = _dailyOhlcState.value.toMutableMap()
        val currentDailyOhlc = currentDailyMap[sym]
        if (currentDailyOhlc == null) {
            currentDailyMap[sym] = IndexOhlc(open = ltp, high = ltp, low = ltp, close = ltp)
        } else {
            currentDailyMap[sym] = currentDailyOhlc.copy(
                high = maxOf(currentDailyOhlc.high, ltp),
                low = minOf(currentDailyOhlc.low, ltp),
                close = ltp
            )
        }
        _dailyOhlcState.value = currentDailyMap
"""

content = re.sub(
    r'    private fun updateIndexCandles\(sym: String, ltp: Double\) \{\s+val sdf = SimpleDateFormat\("yyyy-MM-dd HH:mm", Locale\.US\)\s+val sdf15s = SimpleDateFormat\("yyyy-MM-dd HH:mm:ss", Locale\.US\)',
    ohlc_update_logic,
    content
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patched UpstoxService.kt")
