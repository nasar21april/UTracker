package com.example.upstoxmarketdataapp.simulator

import android.util.Base64
import com.example.upstoxmarketdataapp.data.UpstoxService
import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeedV3
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class SimulatorPlaybackEngine(
    private val upstoxService: UpstoxService,
    private val playbackFile: File
) {
    private var playbackJob: Job? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private val _isSeeking = MutableStateFlow(false)
    val isSeeking: StateFlow<Boolean> = _isSeeking

    private val _tickCount = MutableStateFlow(0)
    val tickCount: StateFlow<Int> = _tickCount

    private val _currentTime = MutableStateFlow("")
    val currentTime: StateFlow<String> = _currentTime

    // Speed: 1f = real-time, 2f = 2x, 5f = 5x, 10f = 10x
    @Volatile var playbackSpeed = 1.0f

    @Volatile var pauseOnSeekComplete = false

    var seekTimestamp = 0L
        set(value) {
            field = value
            if (value > 0L) {
                val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                _currentTime.value = sdf.format(java.util.Date(value))
            }
        }

    fun startPlayback() {
        if (_isPlaying.value) return
        
        // If we are currently paused and have an active job, just resume it
        if (_isPaused.value && playbackJob != null) {
            _isPaused.value = false
            _isPlaying.value = true
            return
        }
        
        _isPaused.value = false
        _isPlaying.value = true
        _isSeeking.value = seekTimestamp > 0L
        _tickCount.value = 0
        upstoxService.isReplaying = true
        

        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                var sessionStartRealTime: Long? = null
                var sessionStartTickTime: Long? = null
                var lastPlaybackSpeed = playbackSpeed
                BufferedReader(FileReader(playbackFile)).use { reader ->
                    var firstTickProcessed = false
                    var wasSeeking = false
                    var lineCount = 0
                    var line = reader.readLine()

                    while (line != null && isActive) {
                        try {
                            // Suspend tick processing while paused
                            while (_isPaused.value && isActive) {
                                delay(100)
                                sessionStartRealTime = null // Reset session time to prevent speed rushes on resume
                                sessionStartTickTime = null
                            }
                            if (!isActive) break

                            val json = JSONObject(line)
                            val ts = json.optLong("ts", -1L)
                            val b64 = json.optString("b64", "")

                            if (ts != -1L && b64.isNotEmpty()) {
                                lineCount++
                                // Enforce market hours (09:15 - 15:30 IST)
                                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
                                cal.timeInMillis = ts
                                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                                val minute = cal.get(java.util.Calendar.MINUTE)
                                val currentMins = hour * 60 + minute
                                val marketStartMins = 9 * 60 + 15
                                val marketEndMins = 15 * 60 + 30

                                if (currentMins < marketStartMins) {
                                    line = reader.readLine()
                                    continue
                                }
                                if (currentMins >= marketEndMins) {
                                    break
                                }

                                if (!firstTickProcessed) {
                                    firstTickProcessed = true
                                    upstoxService.clearCandlesForReplay()
                                }
                                
                                val isSeekingVal = seekTimestamp > 0L && ts < seekTimestamp
                                if (seekTimestamp > 0L && ts >= seekTimestamp) {
                                    seekTimestamp = 0L // Clear target once reached
                                    _isSeeking.value = false
                                    
                                    // Update currentTime immediately to match seek target exactly
                                    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                    sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                                    _currentTime.value = sdf.format(java.util.Date(ts))

                                    if (pauseOnSeekComplete) {
                                        pauseOnSeekComplete = false
                                        _isPaused.value = true
                                        _isPlaying.value = false
                                    }
                                }

                                // Reset timing reference on speed change or transition from seeking to normal playback
                                if (playbackSpeed != lastPlaybackSpeed || (!isSeekingVal && wasSeeking)) {
                                    sessionStartRealTime = null
                                    sessionStartTickTime = null
                                    lastPlaybackSpeed = playbackSpeed
                                }
                                wasSeeking = isSeekingVal

                                // Simulate real-time delay using absolute session synchronization
                                if (!isSeekingVal) {
                                    if (sessionStartRealTime == null || sessionStartTickTime == null) {
                                        sessionStartRealTime = System.currentTimeMillis()
                                        sessionStartTickTime = ts
                                    } else {
                                        val virtualElapsedMs = ts - sessionStartTickTime!!
                                        val targetRealElapsedMs = (virtualElapsedMs / playbackSpeed).toLong()
                                        val actualRealElapsedMs = System.currentTimeMillis() - sessionStartRealTime!!
                                        val sleepTime = targetRealElapsedMs - actualRealElapsedMs
                                        if (sleepTime > 0) {
                                            delay(sleepTime)
                                        }
                                    }
                                }

                                // Update displayed time - throttle updates during seeks to avoid UI thread choke
                                upstoxService.currentReplayTimestamp = ts
                                 if (!isSeekingVal) {
                                     val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                     sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                                     _currentTime.value = sdf.format(java.util.Date(ts))
                                 }

                                // Decode protobuf and inject
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                val feedResponse = MarketDataFeedV3.FeedResponse.parseFrom(bytes)
                                upstoxService.processMarketUpdate(feedResponse)
                                
                                if (!isSeekingVal) {
                                    _tickCount.value++
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SimulatorEngine", "Error on tick: ${e.message}")
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SimulatorEngine", "Playback error: ${e.message}")
            } finally {
                _isPlaying.value = false
                _isPaused.value = false
                _isSeeking.value = false
                upstoxService.isReplaying = false
                // Restore live data after playback completes or is stopped
                upstoxService.manualRefreshStrikes()
            }
        }
    }

    fun pausePlayback() {
        if (!_isPlaying.value) return
        _isPaused.value = true
        _isPlaying.value = false
    }

    fun stopPlayback() {
        _isPlaying.value = false
        _isPaused.value = false
        _isSeeking.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    suspend fun stopPlaybackAndJoin() {
        _isPlaying.value = false
        _isPaused.value = false
        _isSeeking.value = false
        playbackJob?.let {
            it.cancelAndJoin()
        }
        playbackJob = null
    }

    fun getFileSizeKb(): Long = playbackFile.length() / 1024
}
