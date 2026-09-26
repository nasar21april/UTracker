import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\data\UpstoxService.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the initial fetch logic
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
                    if (niftyFull.isNotEmpty()) candlesMap["NIFTY"] = niftyFull.takeLast(30)
                    if (bankNiftyFull.isNotEmpty()) candlesMap["BANKNIFTY"] = bankNiftyFull.takeLast(30)
                    if (sensexFull.isNotEmpty()) candlesMap["SENSEX"] = sensexFull.takeLast(30)
                    
                    _indexCandlesState.value = candlesMap
                }"""

if search1 in content:
    content = content.replace(search1, replace1)
else:
    print("WARNING: search1 not found in UpstoxService.kt")

# Replace the refresh fetch logic
search2 = """                            // Refresh candles
                            val nc = fetchIntradayCandles("NIFTY", "NSE_INDEX|Nifty 50").takeLast(30)
                            val bnc = fetchIntradayCandles("BANKNIFTY", "NSE_INDEX|Nifty Bank").takeLast(30)
                            val sc = fetchIntradayCandles("SENSEX", "BSE_INDEX|SENSEX").takeLast(30)
                            val updatedMap = _indexCandlesState.value.toMutableMap()
                            if (nc.isNotEmpty()) updatedMap["NIFTY"] = nc
                            if (bnc.isNotEmpty()) updatedMap["BANKNIFTY"] = bnc
                            if (sc.isNotEmpty()) updatedMap["SENSEX"] = sc
                            _indexCandlesState.value = updatedMap"""

replace2 = """                            // Refresh candles
                            val ncFull = fetchIntradayCandles("NIFTY", "NSE_INDEX|Nifty 50")
                            val bncFull = fetchIntradayCandles("BANKNIFTY", "NSE_INDEX|Nifty Bank")
                            val scFull = fetchIntradayCandles("SENSEX", "BSE_INDEX|SENSEX")
                            
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
                            updateDailyOhlcFromFull("NIFTY", ncFull)
                            updateDailyOhlcFromFull("BANKNIFTY", bncFull)
                            updateDailyOhlcFromFull("SENSEX", scFull)
                            _dailyOhlcState.value = dailyMap
                            
                            val updatedMap = _indexCandlesState.value.toMutableMap()
                            if (ncFull.isNotEmpty()) updatedMap["NIFTY"] = ncFull.takeLast(30)
                            if (bncFull.isNotEmpty()) updatedMap["BANKNIFTY"] = bncFull.takeLast(30)
                            if (scFull.isNotEmpty()) updatedMap["SENSEX"] = scFull.takeLast(30)
                            _indexCandlesState.value = updatedMap"""

if search2 in content:
    content = content.replace(search2, replace2)
else:
    print("WARNING: search2 not found in UpstoxService.kt")


with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patched UpstoxService.kt to compute Daily OHLC from full intraday candles.")
