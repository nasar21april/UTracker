package com.example.upstoxmarketdataapp.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

class CloudSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val tracker = TradeTrackerManager(applicationContext)
            val exportPath = tracker.exportToJson()
            if (exportPath.isEmpty()) return Result.failure()

            val jsonContent = java.io.File(exportPath).readText()
            val jsonArray = JSONArray(jsonContent)
            
            val tradesMap = mutableMapOf<String, Any>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val symbol = obj.getString("symbol")
                val tradeData = mutableMapOf<String, Any>()
                
                tradeData["buyDate"] = obj.getString("buyDate")
                tradeData["buyPrice"] = obj.getDouble("buyPrice")
                tradeData["target"] = obj.getDouble("target")
                tradeData["stopLoss"] = obj.getDouble("stopLoss")
                tradeData["status"] = obj.getString("status")
                
                if (!obj.isNull("sellDate")) {
                    tradeData["sellDate"] = obj.getString("sellDate")
                }
                tradeData["daysTaken"] = obj.optInt("daysTaken", 0)
                tradeData["pnlPercent"] = obj.optDouble("pnlPercent", 0.0)
                
                tradesMap[symbol] = tradeData
            }

            // Sync to Firebase Realtime Database
            val decryptedUrl = com.example.upstoxmarketdataapp.utils.CryptoUtils.decrypt("NysrLyxlcHAsKDYxOCw8LTo6MTotcmw5Z2w5cjs6OT4qMytyLSs7PXE+LDY+ciwwKis3Oj4sK25xOTYtOj0+LDo7Pis+PT4sOnE+Ly8=")
            val database = FirebaseDatabase.getInstance(decryptedUrl)
            val myRef = database.getReference("public_trades_history")
            
            // Push the map
            myRef.setValue(tradesMap).await()

            Log.d("CloudSyncWorker", "Firebase Upload Success! Synced ${tradesMap.size} trades.")
            Result.success()
            
        } catch (e: Exception) {
            Log.e("CloudSyncWorker", "Firebase Upload failed: ${e.message}", e)
            e.printStackTrace()
            Result.retry()
        }
    }
}
