package com.example.upstoxmarketdataapp.simulator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class DataDownloader(private val cacheDir: File) {

    private val baseUrl = "https://raw.githubusercontent.com/datascientisttrading-eng/Upstox-Market-Replays/main"

    suspend fun fetchAvailableDates(): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/index.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                // Simple regex to extract dates assuming format ["2026-05-22", "2026-05-23"]
                val regex = "\"(\\d{4}-\\d{2}-\\d{2})\"".toRegex()
                regex.findAll(jsonString).map { it.groupValues[1] }.toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun downloadAndExtractFile(dateStr: String): File? = withContext(Dispatchers.IO) {
        try {
            val fileName = "market_data_$dateStr.jsonl.gz"
            val url = URL("$baseUrl/$fileName")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            if (connection.responseCode == 200) {
                val outputFile = File(cacheDir, "market_data_$dateStr.jsonl")
                
                GZIPInputStream(connection.inputStream).use { gzipIn ->
                    FileOutputStream(outputFile).use { out ->
                        gzipIn.copyTo(out)
                    }
                }
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
