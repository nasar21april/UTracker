package com.example.upstoxmarketdataapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.upstoxmarketdataapp.data.UpstoxService
import com.example.upstoxmarketdataapp.ui.login.LoginScreen
import com.example.upstoxmarketdataapp.ui.feed.FeedScreen
import com.example.upstoxmarketdataapp.ui.tvlink.QRScannerScreen

@Composable
fun MainNavigation(upstoxService: UpstoxService) {
  // Start with Feed if token is valid, otherwise Login
  val startDestination = remember {
    if (upstoxService.isAccessTokenValid()) Feed else Login
  }

  val backStack = rememberNavBackStack(startDestination)

  NavDisplay(
    backStack = backStack,
    onBack = { 
      // If we are on Feed, hitting back should not go to Login. We exit if only 1 item left.
      if (backStack.size > 1) {
        backStack.removeLastOrNull()
      }
    },
    entryProvider =
      entryProvider {
        entry<Login> {
          LoginScreen(
            upstoxService = upstoxService,
            onLoginSuccess = {
              // Add Feed and clear Login
              backStack.removeLastOrNull()
              backStack.add(Feed)
            },
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<Feed> {
          FeedScreen(
            upstoxService = upstoxService,
            onLogout = {
              backStack.removeLastOrNull()
              backStack.add(Login)
            },
            onNavigateToScanner = {
              backStack.add(QRScanner)
            },
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<QRScanner> {
          QRScannerScreen(
            upstoxService = upstoxService,
            onBack = {
              backStack.removeLastOrNull()
            },
            onScanSuccess = {
              backStack.removeLastOrNull()
            }
          )
        }
      },
  )
}
