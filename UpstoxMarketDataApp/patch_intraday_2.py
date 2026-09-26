import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\data\UpstoxService.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

search1 = """                launch {
                    logRaw("Fetching 1-minute historical candles for indices...")
                    val niftyCandles = fetchIntradayCandles("NIFTY", "NSE_INDEX|Nifty 50").takeLast(30)
                    val bankNiftyCandles = fetchIntradayCandles("BANKNIFTY", "NSE_INDEX|Nifty Bank").takeLast(30)
                    val sensexCandles = fetchIntradayCandles("SENSEX", "BSE_INDEX|SENSEX").takeLast(30)
                    
                    val candlesMap = mutableMapOf<String, List<Candle>>()
                    if (niftyCandles.isNotEmpty()) candlesMap["NIFTY"] = niftyCandles
                    if (bankNiftyCandles.isNotEmpty()) candlesMap["BANKNIFTY"] = bankNiftyCandles
                    if (sensexCandles.isNotEmpty()) candlesMap["SENSEX"] = sensexCandles
                    
                    _indexCandlesState.value = candlesMap
                    logRaw("Fetched 1-minute historical candles: NIFTY(${niftyCandles.size}), BANKNIFTY(${bankNiftyCandles.size}), SENSEX(${sensexCandles.size})")
                }"""

replace1 = """                launch {
                    logRaw("Fetching 1-minute historical candles for indices...")
                    val niftyFull = fetchIntradayCandles("NIFTY", "NSE_INDEX|Nifty 50")
                    val bankNiftyFull = fetchIntradayCandles("BANKNIFTY", "NSE_INDEX|Nifty Bank")
                    val sensexFull = fetchIntradayCandles("SENSEX", "BSE_INDEX|SENSEX")
                    
                    // Initialize Daily OHLC properly from full day data!
                    val dailyMap = _dailyOhlcState.value.toMutableMap()
                    
                    fun updateDailyOhlcFromFull(sym: String, fullList: List<Candle>) {
                        if (fullList.isNotEmpty()) {
                            val open = fullList.first().open
                            val close = fullList.last().close
                            val high = fullList.maxOf { it.high }
                            val low = fullList.minOf { it.low }
                            dailyMap[sym] = IndexOhlc(open, high, low, close)
                        }
                    }
                    
                    updateDailyOhlcFromFull("NIFTY", niftyFull)
                    updateDailyOhlcFromFull("BANKNIFTY", bankNiftyFull)
                    updateDailyOhlcFromFull("SENSEX", sensexFull)
                    
                    _dailyOhlcState.value = dailyMap

                    val candlesMap = mutableMapOf<String, List<Candle>>()
                    val niftyCandles = niftyFull.takeLast(30)
                    val bankNiftyCandles = bankNiftyFull.takeLast(30)
                    val sensexCandles = sensexFull.takeLast(30)
                    
                    if (niftyCandles.isNotEmpty()) candlesMap["NIFTY"] = niftyCandles
                    if (bankNiftyCandles.isNotEmpty()) candlesMap["BANKNIFTY"] = bankNiftyCandles
                    if (sensexCandles.isNotEmpty()) candlesMap["SENSEX"] = sensexCandles
                    
                    _indexCandlesState.value = candlesMap
                    logRaw("Fetched 1-minute historical candles: NIFTY(${niftyCandles.size}), BANKNIFTY(${bankNiftyCandles.size}), SENSEX(${sensexCandles.size})")
                }"""

if search1 in content:
    content = content.replace(search1, replace1)
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Patched successfully.")
else:
    print("WARNING: search1 not found.")
