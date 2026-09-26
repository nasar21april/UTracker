package com.example.upstoxmarketdataapp.data

import android.content.Context
import android.util.Log
import com.upstox.ApiClient
import com.upstox.Configuration
import com.upstox.auth.OAuth
import com.upstox.feeder.MarketDataStreamerV3
import com.upstox.feeder.listener.OnMarketUpdateV3Listener
import com.upstox.feeder.MarketUpdateV3
import com.upstox.feeder.constants.Mode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class OptionTokenInfo(
    val symbol: String,
    val optionType: String,
    val strikePrice: Double,
    val label: String
)

data class Candle(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0L
)

class UpstoxService(private val context: Context) {

    private val sharedPrefs = context.getSharedPreferences("upstox_prefs", Context.MODE_PRIVATE)
    private val yoiPrefs = context.getSharedPreferences("upstox_yoi_cache", Context.MODE_PRIVATE)
    // Trading Mode Customization (Virtual vs. Real) - Declared early to avoid initialization order issues
    private val _tradingMode = MutableStateFlow(sharedPrefs.getString("trading_mode", "virtual") ?: "virtual")
    val tradingMode: StateFlow<String> = _tradingMode

    private val _isGuestUser = MutableStateFlow(sharedPrefs.getBoolean("is_guest", false))
    val isGuestUser: StateFlow<Boolean> = _isGuestUser

    private val _userName = MutableStateFlow("Guest")
    val userName: StateFlow<String> = _userName
    
    private val _availableFunds = MutableStateFlow(0.0)
    val availableFunds: StateFlow<Double> = _availableFunds
    
    private var streamer: MarketDataStreamerV3? = null
    private var connectionScope: CoroutineScope? = null
    private val okHttpClient = OkHttpClient()
    
    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    val instrumentRepository = InstrumentRepository(context)

    // Option Chain States
    private val _optionsChainState = MutableStateFlow<Map<String, StrikeRowState>>(emptyMap())
    val optionsChainState: StateFlow<Map<String, StrikeRowState>> = _optionsChainState

    // Index 1-minute candles state
    private val _indexCandlesState = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    val indexCandlesState: StateFlow<Map<String, List<Candle>>> = _indexCandlesState

    // Live daily OHLC state (tracks full day high/low instead of just 30 mins)
    private val _liveDayOhlcState = MutableStateFlow<Map<String, IndexOhlc>>(emptyMap())
    val liveDayOhlcState: StateFlow<Map<String, IndexOhlc>> = _liveDayOhlcState

    // Index 15-second candles state for Supertrend momentum tracking
    private val _index15sCandlesState = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    val index15sCandlesState: StateFlow<Map<String, List<Candle>>> = _index15sCandlesState

    private val _dailyOhlcState = MutableStateFlow<Map<String, List<IndexOhlc>>>(emptyMap())
    val dailyOhlcState: StateFlow<Map<String, List<IndexOhlc>>> = _dailyOhlcState

    // Index LTP mapping
    val indexLtpMap = ConcurrentHashMap<String, Double>()

    // Current Base Strikes
    val fixedBaseStrike = ConcurrentHashMap<String, Double>()

    // Option Token Mapping
    val optionTokens = ConcurrentHashMap<String, OptionTokenInfo>()
    val optionTokensSubscribed = ConcurrentHashMap.newKeySet<String>()

    // Future Token Mapping (instrumentKey -> symbol)
    val futureTokens = ConcurrentHashMap<String, String>()

    // Index Futures State (symbol -> list of futures)
    private val _indexFuturesState = MutableStateFlow<Map<String, List<FutureData>>>(emptyMap())
    val indexFuturesState: StateFlow<Map<String, List<FutureData>>> = _indexFuturesState

    // YOI prefetch progress
    private val _deepLinkedAuthCode = MutableStateFlow<String?>(null)
    val deepLinkedAuthCode: StateFlow<String?> = _deepLinkedAuthCode.asStateFlow()

    fun setDeepLinkedAuthCode(code: String?) {
        _deepLinkedAuthCode.value = code
    }

    private val _yoiProgress = MutableStateFlow("Idle")
    val yoiProgress: StateFlow<String> = _yoiProgress

    // Pivots Summary State
    private val _pivotsState = MutableStateFlow<Map<String, Map<String, PivotCalculator.Pivots>>>(emptyMap())
    val pivotsState: StateFlow<Map<String, Map<String, PivotCalculator.Pivots>>> = _pivotsState

    // Stateful Dynamic Pivot Ladders
    val pivotLadderMap = ConcurrentHashMap<String, PivotLadderState>()

    // Trades ledger
    private val _virtualTrades = MutableStateFlow<List<Trade>>(emptyList())
    val virtualTrades: StateFlow<List<Trade>> = _virtualTrades

    private val _realTrades = MutableStateFlow<List<Trade>>(emptyList())
    val realTrades: StateFlow<List<Trade>> = _realTrades

    // Dynamically select based on tradingMode
    val trades: StateFlow<List<Trade>> = kotlinx.coroutines.flow.combine(
        _tradingMode,
        _virtualTrades,
        _realTrades
    ) { mode, virt, real ->
        if (mode == "real") real else virt
    }.stateIn(
        scope = CoroutineScope(Dispatchers.Default),
        started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    // Native compatibility properties (from original feed)
    private val _feedUpdates = MutableSharedFlow<StockData>(extraBufferCapacity = 100)
    val feedUpdates: SharedFlow<StockData> = _feedUpdates

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _rawLogs = MutableSharedFlow<String>(extraBufferCapacity = 50)
    val rawLogs: SharedFlow<String> = _rawLogs

    @Volatile
    var lastReceivedTickTime = 0L

    private val _liveSocketTickTime = MutableStateFlow(0L)
    val liveSocketTickTime: StateFlow<Long> = _liveSocketTickTime

    var isReplaying: Boolean = false
    private val _currentReplayTimestampFlow = MutableStateFlow(0L)
    val currentReplayTimestampFlow: StateFlow<Long> = _currentReplayTimestampFlow

    var currentReplayTimestamp: Long
        get() = _currentReplayTimestampFlow.value
        set(value) {
            _currentReplayTimestampFlow.value = value
        }

    fun clearCandlesForReplay() {
        _indexCandlesState.value = emptyMap()
        _index15sCandlesState.value = emptyMap()
        _liveDayOhlcState.value = emptyMap()
        _optionsChainState.value = emptyMap()
        indexLtpMap.clear()
        fixedBaseStrike.clear()
    }

    // Symbols & labels configured
    val symbols = listOf("NIFTY", "BANKNIFTY", "SENSEX")
    val strikeLabels = listOf(
        "5 Below", "4 Below", "3 Below", "ATM + 2", "ATM + 1",
        "ATM",
        "ATM - 1", "ATM - 2", "3 Above", "4 Above", "5 Above"
    )

    init {
        // Load saved base strikes
        for (sym in symbols) {
            val saved = getSavedBaseStrike(sym)
            if (saved > 0.0) {
                fixedBaseStrike[sym] = saved
            }
        }
        initializeChainState()
        observeRepositoryState()
    }

    fun getSavedBaseStrike(sym: String): Double {
        return sharedPrefs.getFloat("base_strike_$sym", 0.0f).toDouble()
    }

    fun saveBaseStrike(sym: String, base: Double) {
        if (base > 0.0) {
            fixedBaseStrike[sym] = base
            sharedPrefs.edit().putFloat("base_strike_$sym", base.toFloat()).apply()
        }
    }

    private fun observeRepositoryState() {
        coroutineScope.launch {
            instrumentRepository.repoState.collect { state ->
                if (state is RepoState.Success) {
                    logRaw("Repository loaded successfully with ${state.count} instruments. Initializing/refreshing option strikes...")
                    for (sym in symbols) {
                        val base = fixedBaseStrike[sym] ?: getSavedBaseStrike(sym).takeIf { it > 0.0 }
                        if (base != null && base > 0.0) {
                            fixedBaseStrike[sym] = base
                            rebuildBoardAndSubscribe(sym, base)
                        } else {
                            val ltp = indexLtpMap[sym]
                            if (ltp != null && ltp > 0.0) {
                                val newBase = instrumentRepository.getValidStrike(sym, ltp)
                                saveBaseStrike(sym, newBase)
                                rebuildBoardAndSubscribe(sym, newBase)
                            }
                        }
                    }
                    // Auto-resolve any symbols that still don't have base strikes
                    launch(Dispatchers.IO) {
                        ensureAllSymbolsInitialized()
                    }
                }
            }
        }
    }

    private fun initializeChainState() {
        val initialMap = mutableMapOf<String, StrikeRowState>()
        val stepMap = mapOf("NIFTY" to 50.0, "BANKNIFTY" to 100.0, "SENSEX" to 100.0)
        val offsets = mapOf(
            "5 Below" to -5, "4 Below" to -4, "3 Below" to -3, "ATM + 2" to -2, "ATM + 1" to -1,
            "ATM" to 0,
            "ATM - 1" to 1, "ATM - 2" to 2, "3 Above" to 3, "4 Above" to 4, "5 Above" to 5
        )
        for (sym in symbols) {
            val base = fixedBaseStrike[sym] ?: getSavedBaseStrike(sym).takeIf { it > 0.0 } ?: 0.0
            val step = stepMap[sym] ?: 100.0
            for (lbl in strikeLabels) {
                val strike = if (base > 0.0) base + (offsets[lbl] ?: 0) * step else 0.0
                initialMap["${sym}_$lbl"] = StrikeRowState(
                    strikePrice = strike,
                    strikeLabel = lbl,
                    ceData = null,
                    peData = null,
                    ltp = 0.0,
                    updn = "--"
                )
            }
        }
        _optionsChainState.value = initialMap
    }

    fun getSavedAccessToken(): String {
        return sharedPrefs.getString("access_token", "") ?: ""
    }

    fun saveAccessToken(token: String) {
        sharedPrefs.edit()
            .putString("access_token", token)
            .putLong("token_saved_at", System.currentTimeMillis())
            .putBoolean("is_guest", false)
            .apply()
        _isGuestUser.value = false
        
        // Upload token to Firebase for Cloud Engine use
        try {
            val decryptedUrl = com.example.upstoxmarketdataapp.utils.CryptoUtils.decrypt("NysrLyxlcHAsKDYxOCw8LTo6MTotcmw5Z2w5cjs6OT4qMytyLSs7PXE+LDY+ciwwKis3Oj4sK25xOTYtOj0+LDo7Pis+PT4sOnE+Ly8=")
            val database = com.google.firebase.database.FirebaseDatabase.getInstance(decryptedUrl)
            val ref = database.getReference("upstox_token")
            val tokenData = mapOf(
                "token" to token,
                "saved_at" to System.currentTimeMillis()
            )
            ref.setValue(tokenData).addOnFailureListener { e ->
                Log.e("UpstoxService", "Failed to upload token to Firebase", e)
            }
        } catch (e: Exception) {
            Log.e("UpstoxService", "Firebase Database error during token save", e)
        }
    }

    fun loginAsGuest(analyticsToken: String) {
        sharedPrefs.edit()
            .putString("access_token", analyticsToken)
            .putLong("token_saved_at", System.currentTimeMillis())
            .putBoolean("is_guest", true)
            .putString("trading_mode", "virtual")
            .apply()
        _isGuestUser.value = true
        _tradingMode.value = "virtual"
    }

    fun isAccessTokenValid(): Boolean {
        val token = getSavedAccessToken()
        if (token.isEmpty()) return false
        
        if (sharedPrefs.getBoolean("is_guest", false)) {
            val savedAt = sharedPrefs.getLong("token_saved_at", 0L)
            // Guest token expires after 1 hour (3600_000 ms)
            if (System.currentTimeMillis() - savedAt > 3600_000L) {
                return false
            }
            return true 
        }
        
        val savedAt = sharedPrefs.getLong("token_saved_at", 0L)
        
        val calSaved = java.util.Calendar.getInstance()
        calSaved.timeInMillis = savedAt
        val calNow = java.util.Calendar.getInstance()
        
        val sameDay = calSaved.get(java.util.Calendar.YEAR) == calNow.get(java.util.Calendar.YEAR) &&
                calSaved.get(java.util.Calendar.DAY_OF_YEAR) == calNow.get(java.util.Calendar.DAY_OF_YEAR)
                
        return sameDay && (System.currentTimeMillis() - savedAt) < (23L * 3600L * 1000L)
    }

    // Theme Customization settings
    private val _themeMode = MutableStateFlow(sharedPrefs.getString("theme_mode", "dark") ?: "dark")
    val themeMode: StateFlow<String> = _themeMode

    fun saveTheme(theme: String) {
        sharedPrefs.edit().putString("theme_mode", theme).apply()
        _themeMode.value = theme
    }



    fun saveTradingMode(mode: String) {
        if (_isGuestUser.value && mode == "real") return // Guests cannot trade live
        sharedPrefs.edit().putString("trading_mode", mode).apply()
        _tradingMode.value = mode
    }

    // Product Type Customization (I = Intraday, D = Delivery)
    private val _productType = MutableStateFlow(sharedPrefs.getString("product_type", "I") ?: "I")
    val productType: StateFlow<String> = _productType

    fun saveProductType(product: String) {
        sharedPrefs.edit().putString("product_type", product).apply()
        _productType.value = product
    }

    suspend fun placeRealOrder(
        instrumentToken: String,
        transactionType: String,
        quantity: Int,
        product: String = "I",
        orderType: String = "MARKET"
    ): String {
        val accessToken = getSavedAccessToken()
        if (accessToken.isEmpty()) {
            throw Exception("Access token is missing. Please log in first.")
        }

        // Format body for JSON request
        val requestBodyJson = JSONObject().apply {
            put("quantity", quantity)
            put("product", product)
            put("validity", "DAY")
            put("price", 0.0)
            put("instrument_token", instrumentToken)
            put("order_type", orderType)
            put("transaction_type", transactionType)
            put("disclosed_quantity", 0)
            put("trigger_price", 0.0)
            put("is_amo", false)
        }

        val request = Request.Builder()
            .url("https://api.upstox.com/v2/order/place")
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                requestBodyJson.toString()
            ))
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Api-Version", "2.0")
            .build()

        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw Exception("Empty response body")
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(body).getJSONArray("errors").getJSONObject(0).getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $body"
                    }
                    throw Exception(errMsg)
                }
                val json = JSONObject(body)
                if (json.getString("status") == "success") {
                    val orderId = json.getJSONObject("data").getString("order_id")
                    "Order placed successfully! ID: $orderId"
                } else {
                    throw Exception("Order placement failed: $body")
                }
            }
        }
    }

    fun getSavedCredentials(): Triple<String, String, String> {
        val apiKey = sharedPrefs.getString("api_key", "") ?: ""
        val apiSecret = sharedPrefs.getString("api_secret", "") ?: ""
        val redirectUri = sharedPrefs.getString("redirect_uri", "") ?: ""
        return Triple(apiKey, apiSecret, redirectUri)
    }

    fun saveCredentials(apiKey: String, apiSecret: String, redirectUri: String) {
        sharedPrefs.edit()
            .putString("api_key", apiKey)
            .putString("api_secret", apiSecret)
            .putString("redirect_uri", redirectUri)
            .apply()
    }

    fun clearAllData() {
        sharedPrefs.edit()
            .remove("access_token")
            .remove("token_saved_at")
            .remove("is_guest")
            .apply()
        _isGuestUser.value = false
        yoiPrefs.edit().clear().apply()
        fixedBaseStrike.clear()
        optionTokens.clear()
        optionTokensSubscribed.clear()
        _virtualTrades.value = emptyList()
        _realTrades.value = emptyList()
        initializeChainState()
        disconnect()
    }

    fun logRaw(message: String) {
        Log.d("UpstoxService", message)
        _rawLogs.tryEmit(message)
    }

    suspend fun exchangeCodeForToken(code: String): String {
        logRaw("Exchanging auth code for access token...")
        val (apiKey, apiSecret, redirectUri) = getSavedCredentials()
        if (apiKey.isEmpty() || apiSecret.isEmpty() || redirectUri.isEmpty()) {
            throw Exception("API Key, Secret, or Redirect URI is missing")
        }

        val formBody = FormBody.Builder()
            .add("code", code)
            .add("client_id", apiKey)
            .add("client_secret", apiSecret)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .build()

        val request = Request.Builder()
            .url("https://api.upstox.com/v2/login/authorization/token")
            .post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Accept", "application/json")
            .addHeader("Api-Version", "2.0")
            .build()

        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw Exception("Empty response body")
                if (!response.isSuccessful) {
                    Log.e("UpstoxService", "Token exchange failed: $body")
                    logRaw("Token exchange failed: $body")
                    val errMsg = try {
                        JSONObject(body).getJSONArray("errors").getJSONObject(0).getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $body"
                    }
                    throw Exception(errMsg)
                }
                val json = JSONObject(body)
                val token = json.getString("access_token")
                saveAccessToken(token)
                logRaw("Successfully obtained access token!")
                token
            }
        }
    }

    fun connect(accessToken: String, indexKeys: Set<String>) {
        if (accessToken.isEmpty()) {
            _connectionStatus.value = "Error: Access Token is empty"
            return
        }

        fetchUserProfileAndFunds(accessToken)

        // Cancel previous connection scope if any to clean up background processes
        connectionScope?.cancel()

        // Create a new child Job and CoroutineScope on Dispatchers.IO for networking tasks
        val parentJob = coroutineScope.coroutineContext[Job]
        val connectionJob = SupervisorJob(parentJob)
        val newScope = CoroutineScope(coroutineScope.coroutineContext + connectionJob + Dispatchers.IO)
        connectionScope = newScope

        newScope.launch {
            try {
                // If there's an existing active streamer, disconnect it first
                val activeStreamer = streamer
                streamer = null
                if (activeStreamer != null) {
                    logRaw("Disconnecting previous WebSocket...")
                    try {
                        activeStreamer.disconnect()
                    } catch (de: Exception) {
                        logRaw("Disconnect warning: ${de.message}")
                    }
                }

                logRaw("Connecting WebSocket streamer...")
                _connectionStatus.value = "Connecting..."

                val defaultClient = Configuration.getDefaultApiClient()
                val oAuth = defaultClient.getAuthentication("OAUTH2") as OAuth
                oAuth.accessToken = accessToken

                // Connect with Index Keys
                val subscribeKeys = indexKeys.toMutableSet()
                // Make sure we include all 3 indices
                subscribeKeys.add("NSE_INDEX|Nifty 50")
                subscribeKeys.add("NSE_INDEX|Nifty Bank")
                subscribeKeys.add("BSE_INDEX|SENSEX")

                val newStreamer = MarketDataStreamerV3(defaultClient, subscribeKeys, Mode.FULL)
                streamer = newStreamer

                newStreamer.setOnMarketUpdateListener(object : OnMarketUpdateV3Listener {
                    override fun onUpdate(marketUpdate: MarketUpdateV3?) {
                        processMarketUpdate(marketUpdate)
                    }
                })

                newStreamer.connect()
                logRaw("WebSocket connection initiated...")
                _connectionStatus.value = "Connected"
                val now = System.currentTimeMillis()
                lastReceivedTickTime = now
                _liveSocketTickTime.value = now

                // Resubscribe option strikes for all active indices if base strikes are set
                // Delay 2 seconds to allow WebSocket handshake to fully complete before subscribing
                launch {
                    delay(2000L)
                    logRaw("Connected. Resubscribing option strikes for all active indices...")
                    for (sym in symbols) {
                        val base = fixedBaseStrike[sym] ?: getSavedBaseStrike(sym).takeIf { it > 0.0 }
                        if (base != null && base > 0.0) {
                            fixedBaseStrike[sym] = base
                            rebuildBoardAndSubscribe(sym, base)
                        } else {
                            val ltp = indexLtpMap[sym]
                            if (ltp != null && ltp > 0.0) {
                                if (instrumentRepository.repoState.value is RepoState.Success) {
                                    val newBase = instrumentRepository.getValidStrike(sym, ltp)
                                    saveBaseStrike(sym, newBase)
                                    rebuildBoardAndSubscribe(sym, newBase)
                                }
                            }
                        }
                    }
                    launch(Dispatchers.IO) {
                        ensureAllSymbolsInitialized()
                    }
                }

                // Fetch daily pivots on connection
                launch {
                    fetchAndCalculatePivots()
                }

                // Fetch 1-minute historical candles on connection
                launch {
                    logRaw("Fetching 1-minute historical candles for indices...")
                    val fullNifty = fetchIntradayCandles("NIFTY", "NSE_INDEX|Nifty 50")
                    val fullBankNifty = fetchIntradayCandles("BANKNIFTY", "NSE_INDEX|Nifty Bank")
                    val fullSensex = fetchIntradayCandles("SENSEX", "BSE_INDEX|SENSEX")
                    
                    val niftyCandles = fullNifty.takeLast(30)
                    val bankNiftyCandles = fullBankNifty.takeLast(30)
                    val sensexCandles = fullSensex.takeLast(30)
                    
                    val candlesMap = mutableMapOf<String, List<Candle>>()
                    if (niftyCandles.isNotEmpty()) candlesMap["NIFTY"] = niftyCandles
                    if (bankNiftyCandles.isNotEmpty()) candlesMap["BANKNIFTY"] = bankNiftyCandles
                    if (sensexCandles.isNotEmpty()) candlesMap["SENSEX"] = sensexCandles
                    
                    _indexCandlesState.value = candlesMap
                    logRaw("Fetched 1-minute historical candles: NIFTY(${niftyCandles.size}), BANKNIFTY(${bankNiftyCandles.size}), SENSEX(${sensexCandles.size})")
                    
                    // Initialize live day OHLC with true daily open/high/low from intraday data
                    val currentLiveMap = _liveDayOhlcState.value.toMutableMap()
                    val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Calendar.getInstance().time)
                    
                    if (fullNifty.isNotEmpty()) {
                        currentLiveMap["NIFTY"] = IndexOhlc(timestamp = todayStr, open = fullNifty.first().open, high = fullNifty.maxOf { it.high }, low = fullNifty.minOf { it.low }, close = fullNifty.last().close)
                    }
                    if (fullBankNifty.isNotEmpty()) {
                        currentLiveMap["BANKNIFTY"] = IndexOhlc(timestamp = todayStr, open = fullBankNifty.first().open, high = fullBankNifty.maxOf { it.high }, low = fullBankNifty.minOf { it.low }, close = fullBankNifty.last().close)
                    }
                    if (fullSensex.isNotEmpty()) {
                        currentLiveMap["SENSEX"] = IndexOhlc(timestamp = todayStr, open = fullSensex.first().open, high = fullSensex.maxOf { it.high }, low = fullSensex.minOf { it.low }, close = fullSensex.last().close)
                    }
                    _liveDayOhlcState.value = currentLiveMap
                    logRaw("Initialized true live day candles from intraday data.")
                }

                // Periodic auto-refresh: every 90s refresh strikes + candles during market hours
                launch {
                    while (isActive) {
                        delay(90_000L) // 90 seconds
                        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
                        val hour = cal.get(Calendar.HOUR_OF_DAY)
                        val minute = cal.get(Calendar.MINUTE)
                        val totalMinutes = hour * 60 + minute
                        val marketOpen = 9 * 60 + 15   // 9:15 AM
                        val marketClose = 15 * 60 + 35  // 3:35 PM
                        if (totalMinutes in marketOpen..marketClose) {
                            logRaw("Auto-refresh: rebuilding strikes & candles...")
                            for (sym in symbols) {
                                val ltp = indexLtpMap[sym] ?: indexCandlesState.value[sym]?.lastOrNull()?.close ?: continue
                                val base = instrumentRepository.getValidStrike(sym, ltp)
                                fixedBaseStrike[sym] = base
                                rebuildBoardAndSubscribe(sym, base)
                            }
                            // Refresh candles
                            val nc = fetchIntradayCandles("NIFTY", "NSE_INDEX|Nifty 50").takeLast(30)
                            val bnc = fetchIntradayCandles("BANKNIFTY", "NSE_INDEX|Nifty Bank").takeLast(30)
                            val sc = fetchIntradayCandles("SENSEX", "BSE_INDEX|SENSEX").takeLast(30)
                            val updatedMap = _indexCandlesState.value.toMutableMap()
                            if (nc.isNotEmpty()) updatedMap["NIFTY"] = nc
                            if (bnc.isNotEmpty()) updatedMap["BANKNIFTY"] = bnc
                            if (sc.isNotEmpty()) updatedMap["SENSEX"] = sc
                            _indexCandlesState.value = updatedMap
                        }
                    }
                }

                // Watchdog: detect silent WebSocket drops / data lag and auto-reconnect
                launch {
                    delay(15_000L) // Wait 15s initial warm-up
                    while (isActive) {
                        delay(6_000L) // Check every 6 seconds
                        val now = System.currentTimeMillis()
                        val diff = now - lastReceivedTickTime
                        // If connected and more than 15s without any incoming market tick
                        if (_connectionStatus.value == "Connected" && lastReceivedTickTime > 0L && diff > 15_000L) {
                            val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
                            val hour = cal.get(Calendar.HOUR_OF_DAY)
                            val minute = cal.get(Calendar.MINUTE)
                            val totalMinutes = hour * 60 + minute
                            val marketOpen = 9 * 60 + 15   // 9:15 AM
                            val marketClose = 15 * 60 + 35  // 3:35 PM
                            if (totalMinutes in marketOpen..marketClose) {
                                logRaw("Watchdog: Feed stale for ${diff / 1000}s during market hours. Auto-reconnecting WebSocket...")
                                connect(accessToken, emptySet())
                                break
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("UpstoxService", "Connection error", e)
                val stackTrace = Log.getStackTraceString(e)
                logRaw("Connection error: $stackTrace")
                _connectionStatus.value = "Error: ${e.message}"
            }
        }
    }

    fun disconnect() {
        // Cancel the active connection scope first
        connectionScope?.cancel()
        connectionScope = null
        
        val activeStreamer = streamer
        streamer = null
        _connectionStatus.value = "Disconnected"

        if (activeStreamer != null) {
            // Run disconnection on Dispatchers.IO to prevent NetworkOnMainThreadException
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    logRaw("Disconnecting WebSocket...")
                    activeStreamer.disconnect()
                } catch (e: Exception) {
                    Log.e("UpstoxService", "Disconnect error", e)
                    logRaw("Disconnect warning: ${e.message}")
                }
            }
        }
    }
    fun manualRefreshStrikes() {
        logRaw("Manual refresh strikes requested...")
        val token = getSavedAccessToken()
        if (token.isNotEmpty() && connectionStatus.value != "Connected") {
            logRaw("WebSocket is not connected during manual refresh. Reconnecting...")
            connect(token, emptySet())
        }

        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (sym in symbols) {
                var ltp = indexLtpMap[sym] ?: indexCandlesState.value[sym]?.lastOrNull()?.close

                // Aggressive Fallback: Fetch via REST API if still null
                if (ltp == null && token.isNotEmpty()) {
                    val instKey = when (sym) {
                        "NIFTY" -> "NSE_INDEX|Nifty 50"
                        "BANKNIFTY" -> "NSE_INDEX|Nifty Bank"
                        "SENSEX" -> "BSE_INDEX|SENSEX"
                        else -> ""
                    }
                    if (instKey.isNotEmpty()) {
                        try {
                            val request = okhttp3.Request.Builder()
                                .url("https://api.upstox.com/v2/market-quote/quotes?instrument_key=$instKey")
                                .addHeader("Accept", "application/json")
                                .addHeader("Authorization", "Bearer $token")
                                .build()
                            val response = okHttpClient.newCall(request).execute()
                            val responseBody = response.body?.string() ?: ""
                            if (response.isSuccessful && responseBody.contains("\"last_price\"")) {
                                val json = org.json.JSONObject(responseBody)
                                val data = json.optJSONObject("data")
                                val quote = data?.optJSONObject(instKey)
                                val lastPrice = quote?.optDouble("last_price")
                                if (lastPrice != null && lastPrice > 0) {
                                    ltp = lastPrice
                                    indexLtpMap[sym] = lastPrice
                                    logRaw("Aggressively fetched REST LTP for $sym: $lastPrice")
                                    
                                    val ohlcQuote = quote.optJSONObject("ohlc")
                                    if (ohlcQuote != null) {
                                        val qOpen = ohlcQuote.optDouble("open", lastPrice)
                                        val qHigh = ohlcQuote.optDouble("high", lastPrice)
                                        val qLow = ohlcQuote.optDouble("low", lastPrice)
                                        
                                        val currentLiveMap = _liveDayOhlcState.value.toMutableMap()
                                        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Calendar.getInstance().time)
                                        if (currentLiveMap[sym] == null) {
                                            currentLiveMap[sym] = IndexOhlc(timestamp = todayStr, open = qOpen, high = qHigh, low = qLow, close = lastPrice)
                                            _liveDayOhlcState.value = currentLiveMap
                                            logRaw("Initialized live candle for $sym from REST Quote: O=$qOpen H=$qHigh L=$qLow C=$lastPrice")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logRaw("Failed to fetch REST quote for $sym: ${e.message}")
                        }
                    }
                }

                if (ltp != null && ltp > 0.0) {
                    val base = instrumentRepository.getValidStrike(sym, ltp)
                    saveBaseStrike(sym, base)
                    rebuildBoardAndSubscribe(sym, base)
                }
            }
        }
    }

    suspend fun ensureAllSymbolsInitialized() {
        val token = getSavedAccessToken()
        logRaw("Ensuring all symbols option strikes are initialized...")
        for (sym in symbols) {
            val currentBase = fixedBaseStrike[sym] ?: getSavedBaseStrike(sym).takeIf { it > 0.0 }
            val atmRow = _optionsChainState.value["${sym}_ATM"]
            if (currentBase == null || currentBase == 0.0 || (atmRow != null && atmRow.strikePrice == 0.0)) {
                var ltp = indexLtpMap[sym] ?: indexCandlesState.value[sym]?.lastOrNull()?.close
                if ((ltp == null || ltp == 0.0) && token.isNotEmpty()) {
                    val instKey = when (sym) {
                        "NIFTY" -> "NSE_INDEX|Nifty 50"
                        "BANKNIFTY" -> "NSE_INDEX|Nifty Bank"
                        "SENSEX" -> "BSE_INDEX|SENSEX"
                        else -> ""
                    }
                    if (instKey.isNotEmpty()) {
                        try {
                            val request = okhttp3.Request.Builder()
                                .url("https://api.upstox.com/v2/market-quote/quotes?instrument_key=$instKey")
                                .addHeader("Accept", "application/json")
                                .addHeader("Authorization", "Bearer $token")
                                .build()
                            val response = okHttpClient.newCall(request).execute()
                            val responseBody = response.body?.string() ?: ""
                            if (response.isSuccessful && responseBody.contains("\"last_price\"")) {
                                val json = org.json.JSONObject(responseBody)
                                val quote = json.optJSONObject("data")?.optJSONObject(instKey)
                                val lastPrice = quote?.optDouble("last_price")
                                if (lastPrice != null && lastPrice > 0) {
                                    ltp = lastPrice
                                    indexLtpMap[sym] = lastPrice
                                    logRaw("Auto-resolved REST quote for $sym: $lastPrice")
                                }
                            }
                        } catch (e: Exception) {
                            logRaw("Failed auto-resolving quote for $sym: ${e.message}")
                        }
                    }
                }
                if (ltp != null && ltp > 0.0) {
                    val base = instrumentRepository.getValidStrike(sym, ltp)
                    saveBaseStrike(sym, base)
                    rebuildBoardAndSubscribe(sym, base)
                } else if (currentBase != null && currentBase > 0.0) {
                    rebuildBoardAndSubscribe(sym, currentBase)
                }
            }
        }
    }

    fun ensureStrikesForSymbol(sym: String) {
        val currentBase = fixedBaseStrike[sym] ?: getSavedBaseStrike(sym).takeIf { it > 0.0 }
        val atmRow = _optionsChainState.value["${sym}_ATM"]
        if (currentBase == null || currentBase == 0.0 || (atmRow != null && atmRow.strikePrice == 0.0)) {
            coroutineScope.launch(Dispatchers.IO) {
                ensureAllSymbolsInitialized()
            }
        }
    }

    fun rebuildBoardAndSubscribe(sym: String, baseStrike: Double) {
        saveBaseStrike(sym, baseStrike)
        coroutineScope.launch {
            logRaw("Rebuilding board for $sym at base strike $baseStrike...")
            val strikes = instrumentRepository.getOffsetStrikes(sym, baseStrike)

            // 1. Identify old tokens for this symbol
            val activeTradeTokens = trades.value.map { it.optionToken }.toSet()
            val oldTokens = optionTokens.filter { 
                it.value.symbol == sym && !activeTradeTokens.contains(it.key)
            }.keys.toSet()

            if (oldTokens.isNotEmpty()) {
                logRaw("Unsubscribing from ${oldTokens.size} old option tokens for $sym (kept active trades)...")
                try {
                    streamer?.unsubscribe(oldTokens)
                } catch (e: Exception) {
                    Log.e("UpstoxService", "Unsubscribe error", e)
                }
                oldTokens.forEach { token ->
                    optionTokens.remove(token)
                    optionTokensSubscribed.remove(token)
                }
            }

            // 2. Resolve new tokens and build row states
            val newTokensToSubscribe = mutableSetOf<String>()
            val currentChain = _optionsChainState.value.toMutableMap()

            val expiryLong = instrumentRepository.getNearestExpiryForSymbol(sym)
            val expiryStr = if (expiryLong != null) {
                java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(java.util.Date(expiryLong))
            } else ""

            for (lbl in strikeLabels) {
                val strike = strikes[lbl] ?: continue
                val rowKey = "${sym}_$lbl"
                val (ceToken, peToken) = instrumentRepository.getCePeTokens(sym, strike)

                val existingRow = currentChain[rowKey]
                val ceData = if (ceToken != null && existingRow?.ceData?.instrumentKey == ceToken) {
                    existingRow.ceData
                } else if (ceToken != null) {
                    OptionData(instrumentKey = ceToken)
                } else null

                val peData = if (peToken != null && existingRow?.peData?.instrumentKey == peToken) {
                    existingRow.peData
                } else if (peToken != null) {
                    OptionData(instrumentKey = peToken)
                } else null

                currentChain[rowKey] = StrikeRowState(
                    strikePrice = strike,
                    strikeLabel = lbl,
                    ceData = ceData,
                    peData = peData,
                    ltp = indexLtpMap[sym] ?: 0.0,
                    expiryStr = expiryStr,
                    updn = existingRow?.updn ?: "--"
                )

                if (ceToken != null) {
                    optionTokens[ceToken] = OptionTokenInfo(sym, "CE", strike, lbl)
                    optionTokensSubscribed.add(ceToken)
                    newTokensToSubscribe.add(ceToken)
                }
                if (peToken != null) {
                    optionTokens[peToken] = OptionTokenInfo(sym, "PE", strike, lbl)
                    optionTokensSubscribed.add(peToken)
                    newTokensToSubscribe.add(peToken)
                }
            }

            _optionsChainState.value = currentChain

            // 3. Resolve futures for this symbol
            val futures = instrumentRepository.getFuturesForSymbol(sym)
            val currentFuturesMap = _indexFuturesState.value.toMutableMap()
            val existingFutures = currentFuturesMap[sym] ?: emptyList()
            
            val newFuturesList = futures.map { futInst ->
                val existingFutData = existingFutures.find { it.instrumentKey == futInst.instrumentKey }
                if (existingFutData != null) {
                    existingFutData
                } else {
                    FutureData(instrumentKey = futInst.instrumentKey, expiry = futInst.expiry)
                }
            }
            currentFuturesMap[sym] = newFuturesList
            _indexFuturesState.value = currentFuturesMap

            futures.forEach { futInst ->
                futureTokens[futInst.instrumentKey] = sym
                newTokensToSubscribe.add(futInst.instrumentKey)
            }

            // 4. Subscribe to the new tokens (options + futures)
            if (newTokensToSubscribe.isNotEmpty()) {
                logRaw("Subscribing to ${newTokensToSubscribe.size} new tokens for $sym...")
                try {
                    streamer?.subscribe(newTokensToSubscribe, Mode.FULL)
                } catch (e: Exception) {
                    Log.e("UpstoxService", "Subscribe error", e)
                }

                // 5. Prefetch YOI
                prefetchYoiForTokens(newTokensToSubscribe)
            }
        }
    }

    private fun updateIndexLtpInChain(sym: String, ltp: Double) {
        val currentChain = _optionsChainState.value.toMutableMap()
        var changed = false
        for (lbl in strikeLabels) {
            val key = "${sym}_$lbl"
            val row = currentChain[key]
            if (row != null && row.ltp != ltp) {
                currentChain[key] = row.copy(ltp = ltp)
                changed = true
            }
        }
        if (changed) {
            _optionsChainState.value = currentChain
        }
    }

    private fun updateFutureFeed(sym: String, token: String, feed: Any?) {
        val ltp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getLtp(feed)
        val atp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getAtp(feed)

        if (ltp == 0.0) return

        val currentMap = _indexFuturesState.value.toMutableMap()
        val list = currentMap[sym]?.toMutableList() ?: return

        val index = list.indexOfFirst { it.instrumentKey == token }
        if (index != -1) {
            val old = list[index]
            val newAtp = if (atp > 0.0) atp else old.atp
            list[index] = old.copy(ltp = ltp, atp = newAtp, lastUpdateTime = System.currentTimeMillis())
            currentMap[sym] = list
            _indexFuturesState.value = currentMap
        }
    }

    private fun updateOptionFeed(token: String, info: OptionTokenInfo, feed: Any?, chainMap: MutableMap<String, StrikeRowState>? = null) {
        val ltp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getLtp(feed)
        val atp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getAtp(feed)
        val oi = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getOi(feed)
        
        // Handle delta type conversion safely (could be double or float)
        val delta = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getDelta(feed)
        
        val tbq = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getTbq(feed)
        val tsq = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getTsq(feed)

        if (ltp > 0.0) {
            updateTradePrices(token, ltp)
        }

        val rowKey = "${info.symbol}_${info.label}"
        val targetMap = chainMap ?: _optionsChainState.value.toMutableMap()
        val row = targetMap[rowKey] ?: return

        val updatedOption = if (info.optionType == "CE") {
            val ce = row.ceData ?: OptionData(token)
            ce.copy(
                ltp = if (ltp > 0.0) ltp else ce.ltp,
                atp = if (atp > 0.0) atp else ce.atp,
                oi = if (oi > 0.0) oi else ce.oi,
                delta = delta,
                tbq = if (tbq > 0.0) tbq else ce.tbq,
                tsq = if (tsq > 0.0) tsq else ce.tsq,
                lastUpdateTime = System.currentTimeMillis()
            )
        } else {
            val pe = row.peData ?: OptionData(token)
            pe.copy(
                ltp = if (ltp > 0.0) ltp else pe.ltp,
                atp = if (atp > 0.0) atp else pe.atp,
                oi = if (oi > 0.0) oi else pe.oi,
                delta = delta,
                tbq = if (tbq > 0.0) tbq else pe.tbq,
                tsq = if (tsq > 0.0) tsq else pe.tsq,
                lastUpdateTime = System.currentTimeMillis()
            )
        }

        val newRow = if (info.optionType == "CE") {
            row.copy(ceData = updatedOption)
        } else {
            row.copy(peData = updatedOption)
        }

        targetMap[rowKey] = newRow
        if (chainMap == null) {
            _optionsChainState.value = targetMap
        }
    }

    // YOI caching & fetching
    fun getCachedYoi(token: String): Double? {
        val valStr = yoiPrefs.getString(token, null) ?: return null
        return valStr.toDoubleOrNull()
    }

    fun saveCachedYoi(token: String, value: Double) {
        yoiPrefs.edit().putString(token, value.toString()).apply()
    }

    fun checkAndClearYoiCache() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val cachedDate = yoiPrefs.getString("cache_date", "")
        if (cachedDate != todayStr) {
            yoiPrefs.edit().clear().putString("cache_date", todayStr).apply()
            logRaw("Cleared stale YOI cache. Set cache date to $todayStr")
        }
    }

    suspend fun fetchYoi(token: String): Double? {
        val yesterdayStr = getYesterdayString()
        val encodedToken = java.net.URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        val url = "https://api.upstox.com/v3/historical-candle/$encodedToken/days/1/$yesterdayStr"
        val accessToken = getSavedAccessToken()
        if (accessToken.isEmpty()) return null

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use null
                    if (!response.isSuccessful) {
                        return@use null
                    }
                    val json = JSONObject(body)
                    if (json.getString("status") == "success") {
                        val candles = json.getJSONObject("data").getJSONArray("candles")
                        if (candles.length() > 0) {
                            val candle = candles.getJSONArray(0)
                            return@use candle.getDouble(6) // Index 6 is OI
                        }
                    }
                    null
                }
            } catch (e: java.lang.Exception) {
                null
            }
        }
    }

    private fun getYesterdayString(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(cal.time)
    }

    private fun prefetchYoiForTokens(tokens: Set<String>) {
        coroutineScope.launch {
            _yoiProgress.value = "Fetching YOI for ${tokens.size} tokens..."
            var count = 0
            val total = tokens.size

            checkAndClearYoiCache()

            for (token in tokens) {
                val cached = getCachedYoi(token)
                val yoi = if (cached != null) {
                    cached
                } else {
                    val fetched = fetchYoi(token)
                    if (fetched != null) {
                        saveCachedYoi(token, fetched)
                        fetched
                    } else null
                }

                if (yoi != null) {
                    updateYoiInChain(token, yoi)
                }
                count++
                _yoiProgress.value = "ðŸ”„ YOI progress: $count / $total"
                delay(50)
            }
            _yoiProgress.value = "âœ… YOI fetch complete ($total/$total)"
        }
    }

    private fun updateYoiInChain(token: String, yoi: Double) {
        val info = optionTokens[token] ?: return
        val rowKey = "${info.symbol}_${info.label}"
        val currentChain = _optionsChainState.value.toMutableMap()
        val row = currentChain[rowKey] ?: return

        val ceData = if (info.optionType == "CE") row.ceData?.copy(yoi = yoi) else row.ceData
        val peData = if (info.optionType == "PE") row.peData?.copy(yoi = yoi) else row.peData

        currentChain[rowKey] = row.copy(ceData = ceData, peData = peData)
        _optionsChainState.value = currentChain
    }

    // Pivots Summary Board Calculation
    suspend fun fetchIndexOhlc(token: String): List<IndexOhlc> {
        val cal = java.util.Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -14)
        val pastStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        
        val encodedToken = java.net.URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        val url = "https://api.upstox.com/v2/historical-candle/$encodedToken/day/$todayStr/$pastStr"
        val accessToken = getSavedAccessToken()
        if (accessToken.isEmpty()) return emptyList()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .build()

        return withContext(Dispatchers.IO) {
            val resultList = mutableListOf<IndexOhlc>()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use emptyList<IndexOhlc>()
                    if (!response.isSuccessful) {
                        return@use emptyList<IndexOhlc>()
                    }
                    val json = JSONObject(body)
                    if (json.getString("status") == "success") {
                        val candles = json.getJSONObject("data").getJSONArray("candles")
                        for (i in 0 until candles.length()) {
                            val candle = candles.getJSONArray(i)
                            resultList.add(IndexOhlc(
                                timestamp = candle.getString(0),
                                open = candle.getDouble(1),
                                high = candle.getDouble(2),
                                low = candle.getDouble(3),
                                close = candle.getDouble(4)
                            ))
                        }
                        resultList.reverse() // Oldest to newest
                        return@use resultList.takeLast(5)
                    }
                }
            } catch (e: Exception) {
            }
            return@withContext resultList
        }
    }

    suspend fun fetchScreenerCandles(instrumentKey: String): List<Candle> {
        val cal = java.util.Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -300)
        val pastStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        
        val encodedKey = java.net.URLEncoder.encode(instrumentKey, "UTF-8").replace("+", "%20")
        val url = "https://api.upstox.com/v2/historical-candle/$encodedKey/day/$todayStr/$pastStr"
        val accessToken = getSavedAccessToken()
        if (accessToken.isEmpty()) return emptyList()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .build()

        return withContext(Dispatchers.IO) {
            val resultList = mutableListOf<Candle>()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use emptyList<Candle>()
                    if (!response.isSuccessful) return@use emptyList<Candle>()
                    
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        val dataObj = json.getJSONObject("data")
                        val candlesArray = dataObj.getJSONArray("candles")
                        for (i in 0 until candlesArray.length()) {
                            val c = candlesArray.getJSONArray(i)
                            resultList.add(Candle(
                                timestamp = c.getString(0),
                                open = c.getDouble(1),
                                high = c.getDouble(2),
                                low = c.getDouble(3),
                                close = c.getDouble(4),
                                volume = c.getLong(5)
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            resultList.reversed()
        }
    }

    suspend fun fetchAndCalculatePivots() {
        logRaw("Fetching daily OHLC for pivots...")
        val tokens = mapOf(
            "NIFTY" to "NSE_INDEX|Nifty 50",
            "BANKNIFTY" to "NSE_INDEX|Nifty Bank",
            "SENSEX" to "BSE_INDEX|SENSEX"
        )
        val newPivots = mutableMapOf<String, Map<String, PivotCalculator.Pivots>>()
        val newOhlcMap = mutableMapOf<String, List<IndexOhlc>>()
        for ((sym, token) in tokens) {
            val ohlcList = fetchIndexOhlc(token)
            if (ohlcList.isNotEmpty()) {
                newOhlcMap[sym] = ohlcList
                val latestOhlc = ohlcList.last()
                val calculated = PivotCalculator.calculate(latestOhlc.high, latestOhlc.low, latestOhlc.close)
                newPivots[sym] = calculated

                val uniqueLevels = PivotCalculator.getAllUniquePivotLevels(calculated)
                val currentLtpVal = indexLtpMap[sym] ?: latestOhlc.close
                val ladder = pivotLadderMap[sym]
                if (ladder == null || ladder.sortedPivots != uniqueLevels) {
                    val newLadder = PivotLadderState(uniqueLevels)
                    newLadder.updateLtp(currentLtpVal)
                    pivotLadderMap[sym] = newLadder
                } else {
                    ladder.updateLtp(currentLtpVal)
                }

                // Fallback LTP if we do not have a live tick yet
                val currentLtp = indexLtpMap[sym] ?: 0.0
                if (currentLtp == 0.0) {
                    val fallbackLtp = latestOhlc.close
                    indexLtpMap[sym] = fallbackLtp
                    updateIndexLtpInChain(sym, fallbackLtp)

                    // If repo is successfully loaded, initialize base strikes and subscribe
                    if (instrumentRepository.repoState.value is RepoState.Success) {
                        val base = instrumentRepository.getValidStrike(sym, fallbackLtp)
                        fixedBaseStrike[sym] = base
                        rebuildBoardAndSubscribe(sym, base)
                    }
                }
            }
        }
        if (newOhlcMap.isNotEmpty()) {
            _dailyOhlcState.value = newOhlcMap
        }
        if (newPivots.isNotEmpty()) {
            _pivotsState.value = newPivots
            logRaw("Pivots calculated successfully for ${newPivots.keys.joinToString()}")
        }
    }

    suspend fun fetchIntradayCandles(sym: String, instrumentKey: String): List<Candle> {
        val encodedKey = java.net.URLEncoder.encode(instrumentKey, "UTF-8").replace("+", "%20")
        val url = "https://api.upstox.com/v2/historical-candle/intraday/$encodedKey/1minute"
        val accessToken = getSavedAccessToken()
        if (accessToken.isEmpty()) return emptyList()

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
        if (accessToken.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $accessToken")
        }

        return withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body?.string() ?: return@use emptyList()
                    if (!response.isSuccessful) {
                        logRaw("Failed to fetch candles for $sym: HTTP ${response.code} $body")
                        return@use emptyList()
                    }
                    val json = JSONObject(body)
                    if (json.getString("status") == "success") {
                        val candlesArray = json.getJSONObject("data").getJSONArray("candles")
                        val list = mutableListOf<Candle>()
                        for (i in 0 until candlesArray.length()) {
                            val c = candlesArray.getJSONArray(i)
                            list.add(
                                Candle(
                                    timestamp = c.getString(0),
                                    open = c.getDouble(1),
                                    high = c.getDouble(2),
                                    low = c.getDouble(3),
                                    close = c.getDouble(4)
                                )
                            )
                        }
                        list.reverse()
                        list
                    } else {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.e("UpstoxService", "Error fetching candles for $sym", e)
                emptyList()
            }
        }
    }

    suspend fun fetchDailyCandles(instrumentKey: String, days: Int = 90): List<Candle> {
        val encodedKey = java.net.URLEncoder.encode(instrumentKey, "UTF-8").replace("+", "%20")
        val cal = java.util.Calendar.getInstance()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val toDate = sdf.format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        val fromDate = sdf.format(cal.time)
        val url = "https://api.upstox.com/v2/historical-candle/$encodedKey/day/$toDate/$fromDate"

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
        // Historical candle API is public — auth is optional
        val accessToken = getSavedAccessToken()
        if (accessToken.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $accessToken")
        }
        val request = requestBuilder.build()

        return withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use emptyList()
                    if (!response.isSuccessful) return@use emptyList()
                    val json = JSONObject(body)
                    if (json.getString("status") == "success") {
                        val arr = json.getJSONObject("data").getJSONArray("candles")
                        val list = mutableListOf<Candle>()
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONArray(i)
                            list.add(Candle(
                                timestamp = c.getString(0).substring(0, 10), // yyyy-MM-dd
                                open  = c.getDouble(1),
                                high  = c.getDouble(2),
                                low   = c.getDouble(3),
                                close = c.getDouble(4)
                            ))
                        }
                        list.reverse() // oldest first
                        list
                    } else emptyList()
                }
            } catch (e: Exception) {
                Log.e("UpstoxService", "fetchDailyCandles error", e)
                emptyList()
            }
        }
    }

    fun updateIndexCandleWithTick(sym: String, ltp: Double) {
        // Update live day OHLC
        val currentLiveMap = _liveDayOhlcState.value.toMutableMap()
        val existingLive = currentLiveMap[sym]
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Calendar.getInstance().time)
        if (existingLive == null) {
            currentLiveMap[sym] = IndexOhlc(timestamp = todayStr, open = ltp, high = ltp, low = ltp, close = ltp)
        } else {
            currentLiveMap[sym] = existingLive.copy(
                high = maxOf(existingLive.high, ltp),
                low = minOf(existingLive.low, ltp),
                close = ltp
            )
        }
        _liveDayOhlcState.value = currentLiveMap

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00XXX", Locale.US)
        val sdf15s = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        
        val targetDate = if (isReplaying && currentReplayTimestamp > 0L) Date(currentReplayTimestamp) else Date()
        
        // 1-minute logic
        val currentMinuteStr = sdf.format(targetDate)
        val currentMap = _indexCandlesState.value.toMutableMap()
        val candles = (currentMap[sym] ?: emptyList()).toMutableList()

        if (candles.isEmpty()) {
            candles.add(Candle(timestamp = currentMinuteStr, open = ltp, high = ltp, low = ltp, close = ltp))
        } else {
            val lastCandle = candles.last()
            if (lastCandle.timestamp == currentMinuteStr) {
                val updated = lastCandle.copy(
                    high = maxOf(lastCandle.high, ltp),
                    low = minOf(lastCandle.low, ltp),
                    close = ltp
                )
                candles[candles.lastIndex] = updated
            } else {
                candles.add(Candle(timestamp = currentMinuteStr, open = ltp, high = ltp, low = ltp, close = ltp))
            }
        }

        if (candles.size > 30) {
            currentMap[sym] = candles.subList(candles.size - 30, candles.size)
        } else {
            currentMap[sym] = candles
        }
        _indexCandlesState.value = currentMap

        // 15-second logic
        val cal = Calendar.getInstance()
        if (isReplaying && currentReplayTimestamp > 0L) {
            cal.timeInMillis = currentReplayTimestamp
        }
        val seconds = cal.get(Calendar.SECOND)
        val bucket = (seconds / 15) * 15 // 0, 15, 30, 45
        cal.set(Calendar.SECOND, bucket)
        cal.set(Calendar.MILLISECOND, 0)
        val current15sStr = sdf15s.format(cal.time)

        val current15sMap = _index15sCandlesState.value.toMutableMap()
        val candles15s = (current15sMap[sym] ?: emptyList()).toMutableList()

        if (candles15s.isEmpty()) {
            candles15s.add(Candle(timestamp = current15sStr, open = ltp, high = ltp, low = ltp, close = ltp))
        } else {
            val lastCandle15s = candles15s.last()
            if (lastCandle15s.timestamp == current15sStr) {
                val updated = lastCandle15s.copy(
                    high = maxOf(lastCandle15s.high, ltp),
                    low = minOf(lastCandle15s.low, ltp),
                    close = ltp
                )
                candles15s[candles15s.lastIndex] = updated
            } else {
                candles15s.add(Candle(timestamp = current15sStr, open = ltp, high = ltp, low = ltp, close = ltp))
            }
        }

        // Keep 30 of the 15-second candles (7.5 minutes of history) for ATR calculation
        if (candles15s.size > 30) {
            current15sMap[sym] = candles15s.subList(candles15s.size - 30, candles15s.size)
        } else {
            current15sMap[sym] = candles15s
        }
        _index15sCandlesState.value = current15sMap
    }

    // Trading execution
    fun executeTrade(symbol: String, optionType: String, strikePrice: Double, token: String, action: String, quantity: Int, price: Double) {
        val isReal = (_tradingMode.value == "real")
        
        // Ensure token is subscribed on WebSocket streamer for live price updates
        if (token.isNotEmpty()) {
            try {
                if (!optionTokensSubscribed.contains(token)) {
                    optionTokensSubscribed.add(token)
                    streamer?.subscribe(setOf(token), Mode.FULL)
                }
            } catch (e: Exception) {
                Log.e("UpstoxService", "Failed to subscribe trade token $token", e)
            }
        }

        // Use existing trades directly without auto-resetting so multiple trades can accumulate P&L
        val currentTrades = if (isReal) _realTrades.value else _virtualTrades.value
        
        val existingTrade = currentTrades.find { 
            !it.isClosed && it.indexSymbol == symbol && it.optionType == optionType && it.strikePrice == strikePrice 
        }

        if (existingTrade != null) {
            if (existingTrade.action == action) {
                // Averaging
                val totalQty = existingTrade.quantity + quantity
                val avgPrice = ((existingTrade.entryPrice * existingTrade.quantity) + (price * quantity)) / totalQty
                val updatedTrades = currentTrades.map {
                    if (it.id == existingTrade.id) {
                        it.copy(quantity = totalQty, entryPrice = avgPrice, currentPrice = price)
                    } else {
                        it
                    }
                }
                if (isReal) _realTrades.value = updatedTrades else _virtualTrades.value = updatedTrades
                logRaw("Merged trade: $action $totalQty $symbol $strikePrice $optionType @ avg $avgPrice")
            } else {
                // Squaring off (netting opposite trades)
                if (quantity >= existingTrade.quantity) {
                    val updatedTrades = currentTrades.map {
                        if (it.id == existingTrade.id) {
                            it.copy(isClosed = true, exitPrice = price)
                        } else {
                            it
                        }
                    }
                    if (isReal) _realTrades.value = updatedTrades else _virtualTrades.value = updatedTrades
                    logRaw("Squared off trade: ${existingTrade.quantity} $symbol $strikePrice $optionType @ $price")
                    
                    val remainder = quantity - existingTrade.quantity
                    if (remainder > 0) {
                        val newTrade = Trade(
                            indexSymbol = symbol,
                            optionType = optionType,
                            strikePrice = strikePrice,
                            optionToken = token,
                            action = action,
                            quantity = remainder,
                            entryPrice = price,
                            currentPrice = price
                        )
                        if (isReal) _realTrades.value = _realTrades.value + newTrade else _virtualTrades.value = _virtualTrades.value + newTrade
                        logRaw("Executed new trade (remainder): $action $remainder $symbol $strikePrice $optionType @ $price")
                    }
                } else {
                    val updatedTrades = currentTrades.flatMap {
                        if (it.id == existingTrade.id) {
                            val closedTrade = it.copy(id = java.util.UUID.randomUUID().toString(), quantity = quantity, isClosed = true, exitPrice = price)
                            val openTrade = it.copy(quantity = it.quantity - quantity, currentPrice = price)
                            listOf(closedTrade, openTrade)
                        } else {
                            listOf(it)
                        }
                    }
                    if (isReal) _realTrades.value = updatedTrades else _virtualTrades.value = updatedTrades
                    logRaw("Partial squared off $quantity from trade ID: ${existingTrade.id} @ $price")
                }
            }
        } else {
            val trade = Trade(
                indexSymbol = symbol,
                optionType = optionType,
                strikePrice = strikePrice,
                optionToken = token,
                action = action,
                quantity = quantity,
                entryPrice = price,
                currentPrice = price
            )
            if (isReal) {
                _realTrades.value = _realTrades.value + trade
            } else {
                _virtualTrades.value = _virtualTrades.value + trade
            }
            logRaw("Executed new trade: $action $quantity $symbol $strikePrice $optionType @ $price")
        }
    }

    fun clearTrades() {
        _virtualTrades.value = emptyList()
        _realTrades.value = emptyList()
        try {
            val tracker = TradeTrackerManager(context)
            tracker.clearAllTrades()
        } catch (e: Exception) {
            Log.e("UpstoxService", "Failed to clear trade tracker", e)
        }
        logRaw("Cleared all virtual and real trades.")
    }

    fun squareOffTrade(tradeId: String) {
        if (_tradingMode.value == "real") return
        _virtualTrades.value = _virtualTrades.value.map { trade ->
            if (trade.id == tradeId && !trade.isClosed) {
                trade.copy(isClosed = true, exitPrice = trade.currentPrice)
            } else {
                trade
            }
        }
    }

    fun squareOffPartialTrade(tradeId: String, exitQty: Int) {
        if (_tradingMode.value == "real") return
        _virtualTrades.value = _virtualTrades.value.flatMap { trade ->
            if (trade.id == tradeId && !trade.isClosed) {
                if (exitQty >= trade.quantity) {
                    listOf(trade.copy(isClosed = true, exitPrice = trade.currentPrice))
                } else {
                    val remainingQty = trade.quantity - exitQty
                    val closedTrade = trade.copy(id = java.util.UUID.randomUUID().toString(), quantity = exitQty, isClosed = true, exitPrice = trade.currentPrice)
                    val openTrade = trade.copy(quantity = remainingQty)
                    listOf(closedTrade, openTrade)
                }
            } else {
                listOf(trade)
            }
        }
        logRaw("Partial squared off $exitQty from trade ID: $tradeId")
    }

    fun squareOffAllTrades() {
        if (_tradingMode.value == "real") return
        _virtualTrades.value = _virtualTrades.value.map { trade ->
            if (!trade.isClosed) {
                trade.copy(isClosed = true, exitPrice = trade.currentPrice)
            } else {
                trade
            }
        }
    }

    fun squareOffVirtualType(symbol: String, optionType: String) {
        if (_tradingMode.value == "real") return
        _virtualTrades.value = _virtualTrades.value.map { trade ->
            if (trade.indexSymbol == symbol && trade.optionType == optionType && !trade.isClosed) {
                trade.copy(isClosed = true, exitPrice = trade.currentPrice)
            } else {
                trade
            }
        }
    }

    private fun updateTradePrices(token: String, ltp: Double) {
        // Update virtual trades
        val currentVirt = _virtualTrades.value
        val updatedVirt = currentVirt.map { trade ->
            if (trade.optionToken == token) {
                trade.copy(currentPrice = ltp)
            } else {
                trade
            }
        }
        if (updatedVirt != currentVirt) {
            _virtualTrades.value = updatedVirt
        }

        // Update real trades
        val currentReal = _realTrades.value
        val updatedReal = currentReal.map { trade ->
            if (trade.optionToken == token) {
                trade.copy(currentPrice = ltp)
            } else {
                trade
            }
        }
        if (updatedReal != currentReal) {
            _realTrades.value = updatedReal
        }
    }

    fun processMarketUpdate(marketUpdate: Any?) {
        if (marketUpdate == null) return
        if (isReplaying && marketUpdate is com.upstox.feeder.MarketUpdateV3) {
            // Ignore live WebSocket ticks during simulation playback
            return
        }
        val feeds = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getFeeds(marketUpdate)
        if (feeds.isEmpty()) return

        val now = System.currentTimeMillis()
        lastReceivedTickTime = now
        _liveSocketTickTime.value = now
        var chainMap: MutableMap<String, StrikeRowState>? = null

        for ((key, feed) in feeds) {
            if (feed == null) continue
            try {
                // 1. Check if it's an Index feed
                if (key == "NSE_INDEX|Nifty 50" || key == "NSE_INDEX|Nifty Bank" || key == "BSE_INDEX|SENSEX") {
                    val sym = when (key) {
                        "NSE_INDEX|Nifty 50" -> "NIFTY"
                        "NSE_INDEX|Nifty Bank" -> "BANKNIFTY"
                        "BSE_INDEX|SENSEX" -> "SENSEX"
                        else -> ""
                    }

                    val ltp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getLtp(feed)

                    if (ltp > 0.0) {
                        indexLtpMap[sym] = ltp
                        updateIndexLtpInChain(sym, ltp)

                        // Update live candle on tick
                        updateIndexCandleWithTick(sym, ltp)

                        // Update dynamic pivot ladder state
                        pivotLadderMap[sym]?.updateLtp(ltp)

                        // Emitting StockData for legacy components if needed
                        val close = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getClose(feed)
                        val stockData = StockData(
                            instrumentKey = key,
                            ltp = ltp,
                            close = close,
                            lastUpdateTime = System.currentTimeMillis()
                        )
                        _feedUpdates.tryEmit(stockData)

                        // Auto-initialize or dynamically update base strike when price crosses strike interval
                        if (instrumentRepository.repoState.value is com.example.upstoxmarketdataapp.data.RepoState.Success) {
                            val liveBase = instrumentRepository.getValidStrike(sym, ltp)
                            val currentBase = fixedBaseStrike[sym]
                            val step = if (sym == "NIFTY") 50.0 else 100.0
                            if (liveBase > 0.0 && (currentBase == null || Math.abs(liveBase - currentBase) >= step)) {
                                fixedBaseStrike[sym] = liveBase
                                rebuildBoardAndSubscribe(sym, liveBase)
                            }
                        }
                    }
                } else if (key.startsWith("NSE_EQ|") || key.startsWith("BSE_EQ|")) {
                    val ltp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getLtp(feed)
                    if (ltp > 0.0) {
                        updateIndexCandleWithTick(key, ltp)
                    }
                }

                // 2. Check if it's an Option feed (or active trade)
                val optLtp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getLtp(feed)
                if (optLtp > 0.0) {
                    updateTradePrices(key, optLtp)
                }

                if (isReplaying) {
                    val parsed = parseOptionKey(key)
                    if (parsed != null) {
                        updateOptionFeedReplay(key, parsed, feed)
                    }
                } else {
                    if (optionTokens.containsKey(key)) {
                        val info = optionTokens[key] ?: continue
                        if (chainMap == null) {
                            chainMap = _optionsChainState.value.toMutableMap()
                        }
                        updateOptionFeed(key, info, feed, chainMap)
                    }
                }

                // 3. Check if it's a Future feed
                if (isReplaying) {
                    val parsedFut = parseFutureKey(key)
                    if (parsedFut != null) {
                        updateFutureFeedReplay(parsedFut.symbol, key, feed)
                    }
                } else {
                    if (futureTokens.containsKey(key)) {
                        val sym = futureTokens[key] ?: continue
                        updateFutureFeed(sym, key, feed)
                    }
                }
            } catch (ex: Exception) {
                Log.e("UpstoxService", "Error processing feed update for $key", ex)
            }
        }

        if (chainMap != null) {
            _optionsChainState.value = chainMap
        }
    }

    data class ParsedOptionKey(val symbol: String, val optionType: String, val strikePrice: Double)

    private fun parseOptionKey(key: String): ParsedOptionKey? {
        val sym = when {
            key.contains("BANKNIFTY") -> "BANKNIFTY"
            key.contains("NIFTY") -> "NIFTY"
            key.contains("SENSEX") -> "SENSEX"
            else -> return null
        }
        val type = when {
            key.endsWith("CE") || key.contains("CE") -> "CE"
            key.endsWith("PE") || key.contains("PE") -> "PE"
            else -> return null
        }
        val regex = "(\\d+)(?:CE|PE)".toRegex()
        val match = regex.find(key) ?: return null
        val strikeStr = match.groupValues[1]
        val strike = strikeStr.toDoubleOrNull() ?: return null
        return ParsedOptionKey(sym, type, strike)
    }

    private fun updateOptionFeedReplay(token: String, parsed: ParsedOptionKey, feed: Any?) {
        val ltp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getLtp(feed)
        val atp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getAtp(feed)
        val oi = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getOi(feed)
        val delta = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getDelta(feed)
        val tbq = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getTbq(feed)
        val tsq = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getTsq(feed)

        if (ltp > 0.0) {
            updateTradePrices(token, ltp)
        }

        val currentChain = _optionsChainState.value.toMutableMap()
        var updated = false

        for (lbl in strikeLabels) {
            val rowKey = "${parsed.symbol}_$lbl"
            val row = currentChain[rowKey] ?: continue
            if (Math.abs(row.strikePrice - parsed.strikePrice) < 0.01) {
                val updatedOption = if (parsed.optionType == "CE") {
                    val ce = row.ceData ?: OptionData(token)
                    ce.copy(
                        ltp = if (ltp > 0.0) ltp else ce.ltp,
                        atp = if (atp > 0.0) atp else ce.atp,
                        oi = if (oi > 0.0) oi else ce.oi,
                        delta = delta,
                        tbq = if (tbq > 0.0) tbq else ce.tbq,
                        tsq = if (tsq > 0.0) tsq else ce.tsq,
                        lastUpdateTime = currentReplayTimestamp
                    )
                } else {
                    val pe = row.peData ?: OptionData(token)
                    pe.copy(
                        ltp = if (ltp > 0.0) ltp else pe.ltp,
                        atp = if (atp > 0.0) atp else pe.atp,
                        oi = if (oi > 0.0) oi else pe.oi,
                        delta = delta,
                        tbq = if (tbq > 0.0) tbq else pe.tbq,
                        tsq = if (tsq > 0.0) tsq else pe.tsq,
                        lastUpdateTime = currentReplayTimestamp
                    )
                }
                currentChain[rowKey] = if (parsed.optionType == "CE") {
                    row.copy(ceData = updatedOption)
                } else {
                    row.copy(peData = updatedOption)
                }
                updated = true
            }
        }
        if (updated) {
            _optionsChainState.value = currentChain
        }
    }

    data class ParsedFutureKey(val symbol: String)

    private fun parseFutureKey(key: String): ParsedFutureKey? {
        if (!key.contains("FUT")) return null
        val sym = when {
            key.contains("BANKNIFTY") -> "BANKNIFTY"
            key.contains("NIFTY") -> "NIFTY"
            key.contains("SENSEX") -> "SENSEX"
            else -> return null
        }
        return ParsedFutureKey(sym)
    }

    private fun updateFutureFeedReplay(symbol: String, key: String, feed: Any?) {
        val ltp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getLtp(feed)
        val atp = com.example.upstoxmarketdataapp.simulator.MarketUpdateAdapter.getAtp(feed)
        val currentFuturesMap = _indexFuturesState.value.toMutableMap()
        val futures = (currentFuturesMap[symbol] ?: emptyList()).toMutableList()

        val existingIndex = futures.indexOfFirst { it.instrumentKey == key }
        val updatedFut = if (existingIndex >= 0) {
            val old = futures[existingIndex]
            old.copy(
                ltp = if (ltp > 0.0) ltp else old.ltp,
                atp = if (atp > 0.0) atp else old.atp,
                lastUpdateTime = currentReplayTimestamp
            )
        } else {
            FutureData(
                instrumentKey = key,
                expiry = currentReplayTimestamp,
                ltp = ltp,
                atp = atp,
                lastUpdateTime = currentReplayTimestamp
            )
        }

        if (existingIndex >= 0) {
            futures[existingIndex] = updatedFut
        } else {
            futures.add(updatedFut)
        }
        currentFuturesMap[symbol] = futures
        _indexFuturesState.value = currentFuturesMap
    }

    private fun fetchUserProfileAndFunds(token: String) {
        if (_isGuestUser.value) {
            _userName.value = "Guest"
            _availableFunds.value = 0.0
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Fetch Profile
                val profileRequest = okhttp3.Request.Builder()
                    .url("https://api.upstox.com/v2/user/profile")
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                    
                okHttpClient.newCall(profileRequest).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(body).optJSONObject("data")
                        _userName.value = json?.optString("user_name", "Trader") ?: "Trader"
                    }
                }
                
                // Fetch Funds
                val fundsRequest = okhttp3.Request.Builder()
                    .url("https://api.upstox.com/v2/user/get-funds-and-margin?segment=SEC")
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                    
                okHttpClient.newCall(fundsRequest).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(body).optJSONObject("data")?.optJSONObject("equity")
                        _availableFunds.value = json?.optDouble("available_margin", 0.0) ?: 0.0
                    }
                }
            } catch (e: Exception) {
                logRaw("Failed to fetch profile/funds: ${e.message}")
            }
        }
    }

    suspend fun fetchStockNews(instrumentKey: String): String = withContext(Dispatchers.IO) {
        val token = getSavedAccessToken()
        if (token.isEmpty()) return@withContext "[]"
        
        val url = "https://api.upstox.com/v2/news?instrument_keys=$instrumentKey"
        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .addHeader("Api-Version", "2.0")
            .build()
            
        try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(body)
                    val data = json.optJSONArray("data")
                    return@withContext data?.toString() ?: "[]"
                } else {
                    Log.e("UpstoxService", "News API error: ${response.code} $body")
                    return@withContext "[]"
                }
            }
        } catch (e: Exception) {
            Log.e("UpstoxService", "Exception fetching news", e)
            return@withContext "[]"
        }
    }

    suspend fun fetchCompanyRatios(isin: String): String = withContext(Dispatchers.IO) {
        val token = getSavedAccessToken()
        if (token.isEmpty()) return@withContext "[]"

        val url = "https://api.upstox.com/v2/fundamentals/$isin/key-ratios"
        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .addHeader("Api-Version", "2.0")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(body)
                    // data is a JSONArray: [{name:"P/E", company_value:"20.15"}, ...]
                    val data = json.optJSONArray("data")
                    return@withContext data?.toString() ?: "[]"
                } else {
                    Log.e("UpstoxService", "KeyRatios API error: ${response.code} $body")
                    return@withContext "[]"
                }
            }
        } catch (e: Exception) {
            Log.e("UpstoxService", "Exception fetching key-ratios", e)
            return@withContext "[]"
        }
    }

    suspend fun fetchCompanyProfile(isin: String): String = withContext(Dispatchers.IO) {
        val token = getSavedAccessToken()
        if (token.isEmpty()) return@withContext "{}"
        
        val url = "https://api.upstox.com/v2/fundamentals/$isin/profile"
        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .addHeader("Api-Version", "2.0")
            .build()
            
        try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(body)
                    val data = json.optJSONObject("data")
                    return@withContext data?.toString() ?: "{}"
                } else {
                    Log.e("UpstoxService", "Profile API error: ${response.code} $body")
                    return@withContext "{}"
                }
            }
        } catch (e: Exception) {
            Log.e("UpstoxService", "Exception fetching profile", e)
            return@withContext "{}"
        }
    }

    suspend fun fetchShareHoldings(isin: String): String = withContext(Dispatchers.IO) {
        val token = getSavedAccessToken()
        if (token.isEmpty()) return@withContext "[]"

        val url = "https://api.upstox.com/v2/fundamentals/$isin/share-holdings"
        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .addHeader("Api-Version", "2.0")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(body)
                    // data is a JSONArray: [{category:"promoters", history:[{period:"Mar 2026", value:50.0}]}, ...]
                    val data = json.optJSONArray("data")
                    return@withContext data?.toString() ?: "[]"
                } else {
                    Log.e("UpstoxService", "ShareHoldings API error: ${response.code} $body")
                    return@withContext "[]"
                }
            }
        } catch (e: Exception) {
            Log.e("UpstoxService", "Exception fetching share holdings", e)
            return@withContext "[]"
        }
    }
}
data class IndexOhlc(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)
