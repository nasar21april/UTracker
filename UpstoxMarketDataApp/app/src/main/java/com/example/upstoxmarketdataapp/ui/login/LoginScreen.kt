package com.example.upstoxmarketdataapp.ui.login

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.upstoxmarketdataapp.data.UpstoxService
import kotlinx.coroutines.launch

private fun extractAuthCode(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.contains("code=")) {
        try {
            val uri = Uri.parse(trimmed)
            val code = uri.getQueryParameter("code")
            if (code != null) return code

            // Fallback split if there's no scheme or Uri parsing fails slightly
            val parts = trimmed.split("code=")
            if (parts.size > 1) {
                return parts[1].split("&")[0]
            }
        } catch (e: Exception) {
            // Fallback
        }
    }
    return trimmed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    upstoxService: UpstoxService,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoggedIn by remember { mutableStateOf(upstoxService.isAccessTokenValid()) }

    val savedCreds = remember { upstoxService.getSavedCredentials() }
    var apiKey by remember { mutableStateOf(savedCreds.first.ifEmpty { "" }) }
    var apiSecret by remember { mutableStateOf(savedCreds.second.ifEmpty { "" }) }
    var redirectUri by remember { mutableStateOf(savedCreds.third.ifEmpty { "http://localhost:5000/callback/" }) }
    
    var authCode by remember { mutableStateOf("") }
    var guestToken by remember { mutableStateOf("") }
    var showInfoDialog by remember { mutableStateOf(false) }
    
    val credsSaved = savedCreds.first.isNotEmpty() && savedCreds.second.isNotEmpty() && savedCreds.third.isNotEmpty()
    var credentialsExpanded by remember { mutableStateOf(!credsSaved) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isEighteenPlus by remember { mutableStateOf(false) }
    var selectedLoginMethod by remember { mutableStateOf<String?>(null) }

    val deepLinkedCode by upstoxService.deepLinkedAuthCode.collectAsState()
    LaunchedEffect(deepLinkedCode) {
        deepLinkedCode?.let { code ->
            if (code.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()) {
                authCode = code
                isLoading = true
                errorMessage = null
                upstoxService.saveCredentials(apiKey.trim(), apiSecret.trim(), redirectUri.trim())
                try {
                    upstoxService.exchangeCodeForToken(code.trim())
                    isLoggedIn = true
                    onLoginSuccess()
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Failed to exchange authorization code"
                } finally {
                    isLoading = false
                    upstoxService.setDeepLinkedAuthCode(null)
                }
            }
        }
    }

    val isDark = MaterialTheme.colorScheme.onBackground == Color.White
    val gradientBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFECEFF1), Color(0xFFCFD8DC), Color(0xFFB0BEC5)))
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 800

    val LoginForm = @Composable {
        Column(
            modifier = Modifier.fillMaxWidth().then(if (isWideScreen) Modifier else Modifier.verticalScroll(rememberScrollState())),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isWideScreen) 4.dp else 16.dp)
        ) {
            // Logo
            Box(
                modifier = Modifier.size(if (isWideScreen) 40.dp else 80.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.example.upstoxmarketdataapp.R.mipmap.ic_launcher),
                    contentDescription = "App Logo",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text("Upstox Live Feed", fontSize = if (isWideScreen) 20.sp else 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, letterSpacing = 1.sp)
            Text("Real-time WebSocket Market Data Client", fontSize = if (isWideScreen) 12.sp else 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            if (!isWideScreen) Spacer(modifier = Modifier.height(8.dp))

            // Terms and Age Verification Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Declaration",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Disclaimer & Terms of Use",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                    Text(
                        text = "• This application is developed solely for study, educational research, and guest paper-trading simulation. It does not facilitate real trading, financial advisory, or actual monetary investment.\n\n" +
                               "• As per the norms of the Government of India and relevant legal guidelines, users must be at least 18 years of age to access virtual trading platforms.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .clickable { isEighteenPlus = !isEighteenPlus }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isEighteenPlus,
                            onCheckedChange = { isEighteenPlus = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00E676))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "I certify that I am 18+ years old and accept these terms to proceed.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Error Banner
            AnimatedVisibility(visible = errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                        Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                    }
                }
            }

            if (isLoggedIn) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = Color(0xFF00E676), modifier = Modifier.size(64.dp))
                        Text("Authenticated successfully!", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Connected to Upstox API", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onLoginSuccess, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(), enabled = isEighteenPlus) {
                            Text("Sign In", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { upstoxService.clearAllData(); isLoggedIn = false }, border = BorderStroke(1.dp, Color(0xFFFF8A80)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("Log out", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                if (!isEighteenPlus) {
                    // Do nothing, wait for user to check the 18+ box above
                } else if (selectedLoginMethod == null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { selectedLoginMethod = "user" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Text("User Login", fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = { selectedLoginMethod = "guest" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6))) {
                            Text("Guest Login", fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                } else if (selectedLoginMethod == "user") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(if (isWideScreen) 8.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (isWideScreen) 4.dp else 12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("OAuth Setup", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = if (isWideScreen) 14.sp else 16.sp)
                                IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Info, contentDescription = "Help", tint = MaterialTheme.colorScheme.primary) }
                            }

                            if (!credentialsExpanded) {
                                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF00875A).copy(alpha = 0.12f)).clickable { credentialsExpanded = true }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00875A), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Credentials saved", color = Color(0xFF00875A), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                    Text("Edit", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key / Client ID") }, leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = apiSecret, onValueChange = { apiSecret = it }, label = { Text("API Secret / Client Secret") }, leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = redirectUri, onValueChange = { redirectUri = it }, label = { Text("Redirect URI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                if (apiKey.isNotBlank() && apiSecret.isNotBlank() && redirectUri.isNotBlank()) {
                                    Button(onClick = { upstoxService.saveCredentials(apiKey.trim(), apiSecret.trim(), redirectUri.trim()); credentialsExpanded = false }, modifier = Modifier.fillMaxWidth()) { Text("Save Credentials") }
                                }
                            }

                            Button(onClick = {
                                upstoxService.saveCredentials(apiKey.trim(), apiSecret.trim(), redirectUri.trim())
                                val authUrl = "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=${apiKey.trim()}&redirect_uri=${redirectUri.trim()}"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(), enabled = !isLoading && isEighteenPlus) {
                                Text("1. Open Login in Browser", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                            }

                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)).padding(vertical = 8.dp))

                            Text("2. Paste the full redirected URL or Auth Code:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = authCode, onValueChange = { authCode = it }, label = { Text("Code or URL") }, singleLine = true, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        if (authCode.isBlank()) return@IconButton
                                        isLoading = true
                                        errorMessage = null
                                        val actualCode = extractAuthCode(authCode)
                                        coroutineScope.launch {
                                            try {
                                                upstoxService.exchangeCodeForToken(actualCode)
                                                isLoggedIn = true
                                                onLoginSuccess()
                                            } catch (e: Exception) {
                                                errorMessage = e.message ?: "Failed to exchange code."
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF00E676)),
                                    enabled = !isLoading && isEighteenPlus && authCode.isNotBlank()
                                ) { Icon(Icons.Default.PlayArrow, contentDescription = "Submit", tint = Color.Black) }
                            }
                        }
                    }
                    TextButton(onClick = { selectedLoginMethod = null }) { Text("Back to Login Options", fontWeight = FontWeight.Bold) }
                } else if (selectedLoginMethod == "guest") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(if (isWideScreen) 8.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Guest Login", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = if (isWideScreen) 14.sp else 16.sp)
                            Text("Guest Login logs out every one hour", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            
                            val hardcodedGuestToken = "eyJ0eXAiOiJKV1QiLCJrZXlfaWQiOiJza192MS4wIiwiYWxnIjoiSFMyNTYifQ.eyJzdWIiOiI0OENWNVciLCJqdGkiOiI2YTI2ZWMwMmY5NGYyMDA2YTM2ZDA0OTgiLCJpc011bHRpQ2xpZW50IjpmYWxzZSwiaXNQbHVzUGxhbiI6dHJ1ZSwiaXNFeHRlbmRlZCI6dHJ1ZSwiaWF0IjoxNzgwOTM1NjgyLCJpc3MiOiJ1ZGFwaS1nYXRld2F5LXNlcnZpY2UiLCJleHAiOjE4MTI0OTIwMDB9.DFaUD26fFqqaEeMPQ5nmmil68JsHZuUiYEBu5seZ8Ks"
                            
                            Button(
                                onClick = {
                                    isLoading = true
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(500) // Small delay for UX
                                        upstoxService.loginAsGuest(hardcodedGuestToken)
                                        isLoggedIn = true
                                        isLoading = false
                                        onLoginSuccess()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading && isEighteenPlus
                            ) {
                                Icon(Icons.Default.Person, contentDescription = "Guest Login", tint = Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Login as Guest", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    TextButton(onClick = { selectedLoginMethod = null }) { Text("Back to Login Options", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(gradientBrush).padding(24.dp), contentAlignment = Alignment.Center) {
        if (isWideScreen && !isLoggedIn) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                QrCodeLoginView(onAuthReceived = { k, s, r, t ->
                    coroutineScope.launch {
                        upstoxService.saveCredentials(k, s, r)
                        upstoxService.saveAccessToken(t)
                        isLoggedIn = true
                        onLoginSuccess()
                    }
                })
            }
        } else {
            LoginForm()
        }

        if (showInfoDialog) {
            androidx.compose.material3.AlertDialog(onDismissRequest = { showInfoDialog = false }, title = { Text("How to setup") }, text = { Text("1. Login to Upstox Developer Portal.\n2. Create a new App (API).\n3. Copy your API Key (Client ID) and API Secret.\n4. Set a Redirect URI like 'https://127.0.0.1'.\n5. Paste these here to login.") }, confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text("Got it") } })
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF00E676)) }
        }
    }
}
