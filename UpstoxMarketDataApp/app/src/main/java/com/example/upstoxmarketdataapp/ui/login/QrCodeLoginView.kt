package com.example.upstoxmarketdataapp.ui.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.upstoxmarketdataapp.tvauth.TvAuthServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Color as AndroidColor

fun generateQrCodeBitmap(text: String, size: Int = 512): Bitmap? {
    if (text.isEmpty()) return null
    return try {
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

@Composable
fun QrCodeLoginView(onAuthReceived: (String, String, String, String) -> Unit) {
    var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var localIp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val ip = TvAuthServer.getLocalIpAddress()
        if (ip != null) {
            val port = 5050
            val token = java.util.UUID.randomUUID().toString()
            val authUri = "upstox://auth?ip=$ip&port=$port&token=$token"
            localIp = ip
            qrCodeBitmap = generateQrCodeBitmap(authUri)
            
            TvAuthServer.startListening(
                port = port,
                token = token,
                onAuthReceived = { payload ->
                    onAuthReceived(payload.apiKey, payload.apiSecret, payload.redirectUri, payload.accessToken)
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            TvAuthServer.stop()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "TV Login",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Text(
                "Scan this QR code using the 'Link TV' button in the Upstox Tracker mobile app to login instantly.\n\nMake sure your Mobile and TV are connected to the same Wi-Fi network.",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (qrCodeBitmap != null) {
                Image(
                    bitmap = qrCodeBitmap!!.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(200.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(200.dp).background(Color.LightGray, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Generating...", color = Color.Black)
                }
            }
            
            if (localIp != null) {
                Text(
                    "TV IP: $localIp",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
