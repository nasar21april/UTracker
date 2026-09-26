package com.example.upstoxmarketdataapp.utils

import android.util.Base64

object CryptoUtils {
    private const val KEY = 0x5F.toByte()

    fun decrypt(encryptedBase64: String): String {
        return try {
            val decodedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val decryptedBytes = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                decryptedBytes[i] = (decodedBytes[i].toInt() xor KEY.toInt()).toByte()
            }
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun encrypt(plainText: String): String {
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        val encryptedBytes = ByteArray(bytes.size)
        for (i in bytes.indices) {
            encryptedBytes[i] = (bytes[i].toInt() xor KEY.toInt()).toByte()
        }
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }
}
