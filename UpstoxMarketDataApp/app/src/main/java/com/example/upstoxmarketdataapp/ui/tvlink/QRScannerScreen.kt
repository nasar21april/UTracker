package com.example.upstoxmarketdataapp.ui.tvlink

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.core.content.ContextCompat
import com.example.upstoxmarketdataapp.data.UpstoxService
import com.example.upstoxmarketdataapp.tvauth.TvAuthPayload
import com.google.gson.Gson
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerScreen(
    upstoxService: UpstoxService,
    onBack: () -> Unit,
    onScanSuccess: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var scannedStatus by remember { mutableStateOf("Point at TV QR Code") }
    var isSending by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link TV") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F2027),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                if (initError != null) {
                    Text(initError ?: "Camera Error", color = Color.Red, modifier = Modifier.padding(16.dp))
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { _ ->
                            val previewView = PreviewView(context)
                            try {
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(appContext)
                                val executor = ContextCompat.getMainExecutor(appContext)
                                val analysisExecutor = Executors.newSingleThreadExecutor()

                                cameraProviderFuture.addListener({
                                    // 1. Get Camera Provider
                                    val cameraProvider = try {
                                        cameraProviderFuture.get()
                                    } catch (e: Exception) {
                                        initError = "Error 1 (Provider): ${e.message}"
                                        return@addListener
                                    }

                                    // 2. Setup Preview
                                    val preview = try {
                                        Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                    } catch (e: Exception) {
                                        initError = "Error 2 (Preview): ${e.message}"
                                        return@addListener
                                    }

                                    // 3. Setup Image Analysis
                                    val imageAnalysis = try {
                                        ImageAnalysis.Builder()
                                            .setTargetResolution(Size(1280, 720))
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                    } catch (e: Exception) {
                                        initError = "Error 3 (Analysis): ${e.message}"
                                        return@addListener
                                    }

                                    // 4. Setup ZXing Scanner
                                    val reader = MultiFormatReader()

                                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                        if (isSending) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }

                                        try {
                                            // Ensure we have YUV_420_888 format (which is default for CameraX ImageAnalysis)
                                            if (imageProxy.format == android.graphics.ImageFormat.YUV_420_888 && imageProxy.planes.isNotEmpty()) {
                                                val buffer = imageProxy.planes[0].buffer
                                                val data = ByteArray(buffer.remaining())
                                                buffer.get(data)

                                                val width = imageProxy.width
                                                val height = imageProxy.height
                                                val rowStride = imageProxy.planes[0].rowStride

                                                val source = PlanarYUVLuminanceSource(
                                                    data, rowStride, height, 0, 0, width, height, false
                                                )
                                                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                                                val hints = mapOf(
                                                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                                                    com.google.zxing.DecodeHintType.TRY_HARDER to true
                                                )
                                                val result = reader.decode(binaryBitmap, hints)
                                                val rawValue = result.text

                                                if (rawValue != null && rawValue.startsWith("upstox://auth")) {
                                                    isSending = true
                                                    scannedStatus = "Connecting to TV..."
                                                    coroutineScope.launch {
                                                        try {
                                                            val uri = android.net.Uri.parse(rawValue)
                                                            val ip = uri.getQueryParameter("ip")
                                                            val port = uri.getQueryParameter("port")?.toIntOrNull()
                                                            val token = uri.getQueryParameter("token")

                                                            if (ip != null && port != null && token != null) {
                                                                val (k, s, r) = upstoxService.getSavedCredentials()
                                                                val accessToken = upstoxService.getSavedAccessToken()
                                                                val payload = TvAuthPayload(k, s, r, accessToken, token)

                                                                val success = sendToTv(ip, port, payload)
                                                                if (success) {
                                                                    scannedStatus = "TV Linked Successfully!"
                                                                    onScanSuccess()
                                                                } else {
                                                                    scannedStatus = "Connection Failed. Try again."
                                                                    kotlinx.coroutines.delay(2000)
                                                                    isSending = false
                                                                }
                                                            } else {
                                                                scannedStatus = "Invalid QR Code"
                                                                isSending = false
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("QRScanner", "Error parsing QR", e)
                                                            isSending = false
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: com.google.zxing.NotFoundException) {
                                            // No barcode found, normal behavior
                                        } catch (e: Exception) {
                                            Log.e("QRScanner", "ZXing error", e)
                                        } finally {
                                            reader.reset()
                                            imageProxy.close()
                                        }
                                    }

                                    // 5. Bind Use Cases
                                    try {
                                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                        cameraProvider.unbindAll()

                                        // Find activity for lifecycle owner
                                        var activityOwner: androidx.lifecycle.LifecycleOwner? = null
                                        var currentContext = context
                                        while (currentContext is android.content.ContextWrapper) {
                                            if (currentContext is androidx.lifecycle.LifecycleOwner) {
                                                activityOwner = currentContext
                                                break
                                            }
                                            currentContext = currentContext.baseContext
                                        }

                                        cameraProvider.bindToLifecycle(
                                            activityOwner ?: (context as androidx.lifecycle.LifecycleOwner),
                                            cameraSelector,
                                            preview,
                                            imageAnalysis
                                        )
                                    } catch (e: Exception) {
                                        Log.e("QRScanner", "Use case binding failed", e)
                                        initError = "Error 5 (Bind): ${e.message}"
                                    }
                                }, executor)
                            } catch (e: Exception) {
                                initError = "Camera provider error: ${e.message}"
                            }
                            previewView
                        }
                    )

                    ScannerOverlay(modifier = Modifier.fillMaxSize())

                    // Overlay
                    Box(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.6f)).padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(scannedStatus, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text("Camera permission required", color = Color.White)
            }
        }
    }
}

private suspend fun sendToTv(ip: String, port: Int, payload: TvAuthPayload): Boolean = withContext(Dispatchers.IO) {
    try {
        val socket = Socket(ip, port)
        socket.soTimeout = 5000 // 5 seconds timeout
        val writer = PrintWriter(socket.getOutputStream(), true)
        val json = Gson().toJson(payload)
        writer.println(json)
        socket.close()
        true
    } catch (e: Exception) {
        Log.e("QRScanner", "Socket error", e)
        false
    }
}

@Composable
fun ScannerOverlay(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val rectSize = canvasWidth * 0.7f
        val left = (canvasWidth - rectSize) / 2
        val top = (canvasHeight - rectSize) / 2
        val right = left + rectSize
        val bottom = top + rectSize

        val rectPath = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = left, top = top, right = right, bottom = bottom,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f)
                )
            )
        }

        // Draw darkened background
        clipPath(rectPath, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = 0.7f))
        }

        // Draw frame corners
        val strokeWidth = 12f
        val cornerLength = 80f
        val paintColor = Color(0xFF00E676)
        
        // Top-Left
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(left, top), end = androidx.compose.ui.geometry.Offset(left + cornerLength, top), strokeWidth = strokeWidth)
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(left, top), end = androidx.compose.ui.geometry.Offset(left, top + cornerLength), strokeWidth = strokeWidth)
        // Top-Right
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(right, top), end = androidx.compose.ui.geometry.Offset(right - cornerLength, top), strokeWidth = strokeWidth)
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(right, top), end = androidx.compose.ui.geometry.Offset(right, top + cornerLength), strokeWidth = strokeWidth)
        // Bottom-Left
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(left, bottom), end = androidx.compose.ui.geometry.Offset(left + cornerLength, bottom), strokeWidth = strokeWidth)
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(left, bottom), end = androidx.compose.ui.geometry.Offset(left, bottom - cornerLength), strokeWidth = strokeWidth)
        // Bottom-Right
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(right, bottom), end = androidx.compose.ui.geometry.Offset(right - cornerLength, bottom), strokeWidth = strokeWidth)
        drawLine(paintColor, start = androidx.compose.ui.geometry.Offset(right, bottom), end = androidx.compose.ui.geometry.Offset(right, bottom - cornerLength), strokeWidth = strokeWidth)
    }
}
