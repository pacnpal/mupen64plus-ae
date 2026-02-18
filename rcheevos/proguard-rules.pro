# Add project specific ProGuard rules here.
# Keep rcheevos native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class paulscode.android.mupen64plusae.retroachievements.RCheevosNative {
    *;
}
