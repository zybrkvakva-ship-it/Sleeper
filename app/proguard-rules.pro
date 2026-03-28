# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# ============================================================
# Room Database
# ============================================================
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class * extends androidx.room.RoomDatabase
-keep class com.sleeper.app.data.local.**Entity { *; }
-keep interface com.sleeper.app.data.local.dao.** { *; }
-keepclassmembers class com.sleeper.app.data.local.** { *; }

# ============================================================
# OkHttp + OkIO (Solana RPC, backend API)
# ============================================================
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# ============================================================
# JSON (org.json — используется в MiningBackendApi, SolanaRpcClient)
# ============================================================
-keep class org.json.** { *; }

# ============================================================
# Kotlin Coroutines
# ============================================================
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================
# Solana / MWA — не обфусцировать классы для RPC и кошелька
# ============================================================
-keep class com.solanamobile.** { *; }
-dontwarn com.solanamobile.**
-keep class com.sleeper.app.data.network.** { *; }

# ============================================================
# BouncyCastle (PDA curve check)
# ============================================================
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ============================================================
# Kotlin Serialization
# ============================================================
-keepattributes RuntimeVisibleAnnotations
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**