# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class * extends androidx.room.RoomDatabase
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Solana / MWA / OkHttp — не обфусцировать классы, используемые для RPC и кошелька
-keep class com.solanamobile.** { *; }
-keep class com.sleeper.app.data.network.** { *; }