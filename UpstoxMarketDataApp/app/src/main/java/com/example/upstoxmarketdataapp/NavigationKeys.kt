package com.example.upstoxmarketdataapp

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Login : NavKey
@Serializable data object Feed : NavKey
@Serializable data object QRScanner : NavKey
