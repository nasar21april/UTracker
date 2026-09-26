package com.example.upstoxmarketdataapp.data

import android.content.Context
import android.util.JsonReader
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

sealed interface RepoState {
    object Idle : RepoState
    data class Loading(val message: String, val progress: Float) : RepoState
    data class Success(val count: Int) : RepoState
    data class Error(val message: String) : RepoState
}

class InstrumentRepository(private val context: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val cacheDir = File(context.cacheDir, "instruments")
    private val filteredCacheFile = File(cacheDir, "filtered_instruments.json")
    private val dateFile = File(cacheDir, "instruments_date.txt")

    private val _repoState = MutableStateFlow<RepoState>(RepoState.Idle)
    val repoState: StateFlow<RepoState> = _repoState

    private var instruments: List<Instrument> = emptyList()

    init {
        cacheDir.mkdirs()
    }

    fun getInstrumentsCount(): Int = instruments.size

    fun getValidStrike(symbol: String, ltp: Double): Double {
        val step = when (symbol) {
            "NIFTY" -> 50
            "BANKNIFTY" -> 100
            "SENSEX" -> 100
            else -> 100
        }
        val raw = Math.round(ltp / step) * step
        val symbolInstruments = instruments.filter { it.assetSymbol == symbol }
        if (symbolInstruments.isEmpty()) return raw.toDouble()
        return symbolInstruments.minByOrNull { Math.abs(it.strikePrice - raw) }?.strikePrice ?: raw.toDouble()
    }

    fun getOffsetStrikes(symbol: String, baseStrike: Double): Map<String, Double> {
        val step = when (symbol) {
            "NIFTY" -> 50
            "BANKNIFTY" -> 100
            "SENSEX" -> 100
            else -> 100
        }
        val labels = listOf(
            "5 Below", "4 Below", "3 Below", "ATM + 2", "ATM + 1",
            "ATM",
            "ATM - 1", "ATM - 2", "3 Above", "4 Above", "5 Above"
        )
        val offsets = listOf(-5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5)

        val symbolInstruments = instruments.filter { it.assetSymbol == symbol && it.instrumentType == "CE" }
        if (symbolInstruments.isEmpty()) {
            return labels.zip(offsets.map { baseStrike + it * step }).toMap()
        }

        val uniqueStrikes = symbolInstruments.map { it.strikePrice }.distinct()

        return labels.zip(offsets.map { off ->
            val target = baseStrike + off * step
            uniqueStrikes.minByOrNull { Math.abs(it - target) } ?: target
        }).toMap()
    }

    fun getCePeTokens(symbol: String, strike: Double): Pair<String?, String?> {
        val symbolInstruments = instruments.filter {
            it.assetSymbol == symbol && it.strikePrice == strike
        }
        if (symbolInstruments.isEmpty()) return Pair(null, null)

        val nowCal = Calendar.getInstance()
        val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)
        val currentMinute = nowCal.get(Calendar.MINUTE)
        val isPastMarketClose = currentHour > 15 || (currentHour == 15 && currentMinute >= 30)

        val cutoffMillis = if (isPastMarketClose) {
            // Cutoff is tomorrow midnight
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else {
            getTodayMidnightMillis()
        }

        val validExpiries = symbolInstruments.filter { it.expiry >= cutoffMillis }
        if (validExpiries.isEmpty()) return Pair(null, null)

        val nearestExpiry = validExpiries.minOf { it.expiry }

        val ceToken = symbolInstruments.firstOrNull { it.expiry == nearestExpiry && it.instrumentType == "CE" }?.instrumentKey
        val peToken = symbolInstruments.firstOrNull { it.expiry == nearestExpiry && it.instrumentType == "PE" }?.instrumentKey

        return Pair(ceToken, peToken)
    }

    fun getFuturesForSymbol(symbol: String): List<Instrument> {
        val todayMidnight = getTodayMidnightMillis()
        return instruments
            .filter { it.assetSymbol == symbol && it.instrumentType.startsWith("FUT") && it.expiry >= todayMidnight }
            .sortedBy { it.expiry }
            .take(3)
    }

    fun getNearestExpiryForSymbol(symbol: String): Long? {
        val symbolInstruments = instruments.filter { it.assetSymbol == symbol && (it.instrumentType == "CE" || it.instrumentType == "PE") }
        if (symbolInstruments.isEmpty()) return null
        
        val todayMidnight = getTodayMidnightMillis()
        val nowCal = java.util.Calendar.getInstance()
        val currentHour = nowCal.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = nowCal.get(java.util.Calendar.MINUTE)
        val isPastMarketClose = currentHour > 15 || (currentHour == 15 && currentMinute >= 30)

        val validInstruments = if (isPastMarketClose) {
            symbolInstruments.filter { it.expiry > todayMidnight }
        } else {
            symbolInstruments.filter { it.expiry >= todayMidnight }
        }
        return validInstruments.minByOrNull { it.expiry }?.expiry
    }

    private fun getTodayMidnightMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        
        var loadedFromCache = false
        if (filteredCacheFile.exists() && filteredCacheFile.length() > 100) {
            try {
                val type = object : TypeToken<List<Instrument>>() {}.type
                val cached: List<Instrument> = gson.fromJson(filteredCacheFile.readText(), type)
                if (cached.isNotEmpty()) {
                    instruments = cached
                    Log.d("InstrumentRepo", "Instantly loaded ${instruments.size} instruments from cache")
                    _repoState.value = RepoState.Success(instruments.size)
                    loadedFromCache = true

                    val savedDate = if (dateFile.exists()) dateFile.readText().trim() else ""
                    if (savedDate == todayStr) {
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                Log.e("InstrumentRepo", "Error reading cache, will re-download", e)
            }
        }

        // If not loaded from cache, set Loading state. If already loaded, download and update in background
        if (!loadedFromCache) {
            _repoState.value = RepoState.Loading("Downloading Instruments...", 0.1f)
        }
        downloadAndParseInstruments(todayStr)
    }

    private suspend fun downloadAndParseInstruments(todayStr: String) = withContext(Dispatchers.IO) {
        _repoState.value = RepoState.Loading("Downloading Instruments...", 0.1f)
        val completeUrl = "https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz"
        val destFile = File(cacheDir, "complete.json.gz")

        try {
            // Download complete.json.gz
            downloadFile(completeUrl, destFile)
            _repoState.value = RepoState.Loading("Parsing Options...", 0.5f)

            val parsedList = mutableListOf<Instrument>()
            
            // Parse complete.json.gz
            parseGzipJson(destFile, parsedList)

            instruments = parsedList
            Log.d("InstrumentRepo", "Parsed total ${instruments.size} active instruments")

            // Write to cache
            filteredCacheFile.writeText(gson.toJson(instruments))
            dateFile.writeText(todayStr)

            // Clean up raw files to save disk space
            destFile.delete()

            _repoState.value = RepoState.Success(instruments.size)
        } catch (e: Exception) {
            Log.e("InstrumentRepo", "Error fetching instruments", e)
            _repoState.value = RepoState.Error("Error: ${e.message}")
        }
    }

    private fun downloadFile(url: String, destFile: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} downloading instruments")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun parseGzipJson(file: File, outList: MutableList<Instrument>) {
        val todayMidnight = getTodayMidnightMillis()
        
        FileInputStream(file).use { fis ->
            GZIPInputStream(fis).use { gis ->
                InputStreamReader(gis, "UTF-8").use { isr ->
                    val jsonReader = JsonReader(isr)
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        jsonReader.beginObject()
                        
                        var instrumentKey = ""
                        var assetSymbol = ""
                        var expiry = 0L
                        var strikePrice = 0.0
                        var instrumentType = ""
                        var lotSize = 0

                        while (jsonReader.hasNext()) {
                            val name = jsonReader.nextName()
                            if (jsonReader.peek() == android.util.JsonToken.NULL) {
                                jsonReader.skipValue()
                                continue
                            }
                            
                            when (name) {
                                "instrument_key" -> instrumentKey = jsonReader.nextString()
                                "asset_symbol" -> assetSymbol = jsonReader.nextString()
                                "expiry" -> {
                                    val tokenType = jsonReader.peek()
                                    if (tokenType == android.util.JsonToken.NUMBER) {
                                        expiry = jsonReader.nextLong()
                                    } else {
                                        val expiryStr = jsonReader.nextString()
                                        // Parse expiry date string "yyyy-MM-dd" if it's a string
                                        expiry = try {
                                            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(expiryStr)?.time ?: 0L
                                        } catch (ex: Exception) {
                                            0L
                                        }
                                    }
                                }
                                "strike_price" -> strikePrice = jsonReader.nextDouble()
                                "instrument_type" -> instrumentType = jsonReader.nextString()
                                "lot_size" -> lotSize = jsonReader.nextInt()
                                else -> jsonReader.skipValue()
                            }
                        }
                        jsonReader.endObject()

                        // Filtering:
                        // Only options (CE/PE) or futures (FUT/FUTIDX) for target symbols with expiry >= today (or expiry == 0 for indices, but indexes are in FO as FUT or not here)
                        if ((assetSymbol == "NIFTY" || assetSymbol == "BANKNIFTY" || assetSymbol == "SENSEX") &&
                            (instrumentType == "CE" || instrumentType == "PE" || instrumentType.startsWith("FUT")) &&
                            expiry >= todayMidnight
                        ) {
                            outList.add(
                                Instrument(
                                    instrumentKey = instrumentKey,
                                    assetSymbol = assetSymbol,
                                    expiry = expiry,
                                    strikePrice = strikePrice,
                                    instrumentType = instrumentType,
                                    lotSize = lotSize
                                )
                            )
                        }
                    }
                    jsonReader.endArray()
                }
            }
        }
    }
}
