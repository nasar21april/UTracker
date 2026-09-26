# Keep attributes for reflection and serialization
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Keep our entire app package to avoid R8 minification/obfuscation crashes in custom code
-keep class com.example.upstoxmarketdataapp.** { *; }

# Keep all models of Upstox API and feeder packages
-keep class com.upstox.api.** { *; }
-keep class com.upstox.feeder.** { *; }
-keep class com.upstox.** { *; }
-keep interface com.upstox.feeder.listener.** { *; }
-keep class io.swagger.** { *; }
-dontwarn io.swagger.**
-dontwarn com.google.common.**

# Keep Gson & Gson Fire (used by Upstox SDK and our app)
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class io.gsonfire.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep ThreeTenBP (date/time library used by Upstox SDK)
-keep class org.threeten.bp.** { *; }
-dontwarn org.threeten.bp.**

# Keep Firebase Database and SDK
-keep class com.google.firebase.database.** { *; }
-keep class com.google.firebase.** { *; }

# Keep WorkManager workers
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep OkHttp & Okio
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.slf4j.**

# Keep Kotlin Coroutines internals
-keep class kotlinx.coroutines.** { *; }
-keepclassmembernames class kotlinx.coroutines.internal.MainDispatcherFactory {
    *;
}

# Kotlinx Serialization Rules (for Navigation routes like Login, Feed, QRScanner)
-keepclassmembers class * {
    *** Companion;
}
-keepclassmembers class *Companion* {
    *** serializer(...);
}
-keep @kotlinx.serialization.Serializable class * {
    *;
}
-keep class *$$serializer {
    *;
}
-keep class *$$serializer$* {
    *;
}
