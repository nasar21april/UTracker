package com.example.upstoxmarketdataapp.tvauth

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID

data class TvAuthPayload(
    val apiKey: String,
    val apiSecret: String,
    val redirectUri: String,
    val accessToken: String,
    val token: String
)

object TvAuthServer {
    private var serverSocket: ServerSocket? = null
    private var expectedToken: String = ""

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: SocketException) {
            Log.e("TvAuthServer", "Exception getting local IP", ex)
        }
        return null
    }

    suspend fun startListening(port: Int = 5050, token: String, onAuthReceived: (TvAuthPayload) -> Unit) {
        val ip = getLocalIpAddress() ?: return
        expectedToken = token

        withContext(Dispatchers.IO) {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(port)
                Log.d("TvAuthServer", "Listening on $ip:$port...")
                
                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d("TvAuthServer", "Client connected from ${socket.inetAddress.hostAddress}")
                    
                    try {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val jsonString = reader.readLine()
                        if (jsonString != null) {
                            val payload = Gson().fromJson(jsonString, TvAuthPayload::class.java)
                            if (payload.token == expectedToken) {
                                Log.d("TvAuthServer", "Valid token received! Completing auth.")
                                withContext(Dispatchers.Main) {
                                    onAuthReceived(payload)
                                }
                                // Stop server after successful login
                                serverSocket?.close()
                                break
                            } else {
                                Log.e("TvAuthServer", "Invalid token received: ${payload.token}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TvAuthServer", "Error reading payload", e)
                    } finally {
                        socket.close()
                    }
                }
            } catch (e: Exception) {
                Log.e("TvAuthServer", "Server exception", e)
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
