plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  id("com.google.gms.google-services")
}

android {
    namespace = "com.example.upstoxmarketdataapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.UpstoxTracker.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 25
        versionName = "1.2.4"
    }
    
    flavorDimensions.add("device")
    productFlavors {
        create("mobile") {
            dimension = "device"
            applicationId = "com.UpstoxTracker.app"
            resValue("string", "app_name", "UTracker")
            manifestPlaceholders["screenOrientation"] = "portrait"
        }
        create("tv") {
            dimension = "device"
            applicationId = "com.UpstoxTracker.app.tv"
            resValue("string", "app_name", "Upstox TV")
            manifestPlaceholders["screenOrientation"] = "landscape"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../upstox_release.keystore")
            storePassword = "upstox123"
            keyAlias = "upstox_alias"
            keyPassword = "upstox123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
      resValues = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        useLegacyPackaging = false
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation("androidx.work:work-runtime-ktx:2.9.0")
  implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
  implementation("com.google.firebase:firebase-database-ktx")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Upstox & Networking
  implementation("com.upstox.api:upstox-java-sdk:1.26") {
      exclude(group = "ch.qos.logback", module = "logback-classic")
      exclude(group = "ch.qos.logback", module = "logback-core")
  }
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.code.gson:gson:2.10.1")

  // QR Code Generation & Scanning
  implementation("com.google.zxing:core:3.5.3")
  implementation("androidx.camera:camera-camera2:1.4.1")
  implementation("androidx.camera:camera-lifecycle:1.4.1")
  implementation("androidx.camera:camera-view:1.4.1")

  // Force latest Fragment version to resolve outdated fragment warning
  implementation("androidx.fragment:fragment-ktx:1.8.2")

  // Material Icons
  implementation("androidx.compose.material:material-icons-core")
  // implementation("androidx.compose.material:material-icons-extended")
  implementation("io.coil-kt:coil-compose:2.6.0")
}

