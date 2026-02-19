# Add project specific ProGuard rules here.

# Keep native methods for RetroAchievements JNI bridge only
-keepclasseswithmembernames class paulscode.android.mupen64plusae.retroachievements.** {
    native <methods>;
}

-keep class paulscode.android.mupen64plusae.retroachievements.RCheevosNative {
    *;
}
