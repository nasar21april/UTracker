import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\data\UpstoxService.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Inject _dailyOhlcState near _index15sCandlesState
state_declaration = """    private val _index15sCandlesState = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    val index15sCandlesState: StateFlow<Map<String, List<Candle>>> = _index15sCandlesState

    private val _dailyOhlcState = MutableStateFlow<Map<String, IndexOhlc>>(emptyMap())
    val dailyOhlcState: StateFlow<Map<String, IndexOhlc>> = _dailyOhlcState"""

content = content.replace(
    '    private val _index15sCandlesState = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())\n    val index15sCandlesState: StateFlow<Map<String, List<Candle>>> = _index15sCandlesState',
    state_declaration
)

# Inject tracking into updateIndexCandles
logic = """    private fun updateIndexCandles(sym: String, ltp: Double) {
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

        val sdf"""

content = content.replace('    private fun updateIndexCandles(sym: String, ltp: Double) {\n        val sdf', logic)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Properly patched UpstoxService.kt")
